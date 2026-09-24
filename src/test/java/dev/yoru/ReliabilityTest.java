package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Real edit/reopen/reset flows, including the fields convenience constructors used to erase. */
public final class ReliabilityTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    interface Action {void run()throws Exception;}
    private static void rejects(Action action)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IOException expected){checks++;return;}
        throw new AssertionError("Invalid operation was accepted");
    }
    private static final class Time extends Clock {
        Instant now=Instant.parse("2026-09-09T08:00:00Z");
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    private static final class Memory implements Repository {
        State state=State.empty();boolean fail,backupFail;int backups;
        public State load(){return state;}
        public void save(State next)throws IOException{if(fail)throw new IOException("Disk full");state=next;}
        public void backup()throws IOException{if(backupFail)throw new IOException("Backup failed");backups++;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception{
        var clock=new Time();var repo=new Memory();var t=new Tracker(repo,clock);
        t.addActivity("Study",0);UUID activity=t.state().activities().getFirst().id();
        final var tracker=t;var start=clock.instant();t.start(activity);clock.advance(7200);
        check(new Tracker(repo,clock).active().start().equals(start),"Running timer survives reopening");
        t.stop(clock.instant());

        UUID session=t.state().sessions().getFirst().id();
        t.editSession(session,activity,start,start.plusSeconds(3600));
        var before=t.state();repo.fail=true;rejects(()->tracker.editSession(session,activity,start,start.plusSeconds(4000)));
        check(t.state().equals(before),"Failed edit leaves live state unchanged");repo.fail=false;
        rejects(()->tracker.editSession(session,activity,start,clock.instant().plusSeconds(1)));
        rejects(()->tracker.editSession(session,activity,start,null));
        t.log(activity,start.plusSeconds(3600),start.plusSeconds(4200));
        rejects(()->tracker.editSession(session,activity,start,start.plusSeconds(4000)));
        check(t.state().sessions().getFirst().end().equals(start.plusSeconds(3600)),"Overlap leaves original session intact");

        t.plan(activity,start.plusSeconds(9000),start.plusSeconds(9600));var block=t.state().blocks().getFirst();
        t.editBlock(block.id(),activity,start.plusSeconds(9300),start.plusSeconds(9900));
        check(t.state().blocks().getFirst().start().equals(start.plusSeconds(9300)),"Schedule correction");
        t.plan(activity,start.plusSeconds(10000),start.plusSeconds(11000));
        rejects(()->tracker.editBlock(block.id(),activity,start.plusSeconds(9500),start.plusSeconds(10500)));

        t.addHabit("Time since",HabitKind.TIME_SINCE,ZoneOffset.UTC,start.minusSeconds(10020));
        UUID habit=t.state().habits().getFirst().id();t.editHabitStart(habit,start.minusSeconds(19980));
        check(t.state().habits().getFirst().starts().getFirst().equals(start.minusSeconds(19980)),"Backdated quit start");
        t.restartHabit(habit);rejects(()->tracker.editHabitStart(habit,start.minusSeconds(21000)));
        rejects(()->tracker.editHabitStart(habit,clock.instant().plusSeconds(1)));
        t.editHabitStart(habit,start);check(t.state().habits().getFirst().starts().size()==2,"Edit preserves prior periods");


        // An encrypted reopen and a reset backup, on invented data only.
        Path dir=Files.createTempDirectory("yoru-reliability-"),file=dir.resolve("test.vault");
        try{
            var saved=t.state();String password="test-password-for-reliability";
            try(var vault=new EncryptedVault(file,password.toCharArray())){vault.save(saved);}
            byte[] bytes=Files.readAllBytes(file);
            try(var vault=new EncryptedVault(file,password.toCharArray())){
                check(vault.load().equals(saved),"Edited data survives encrypted reopen");
                new Tracker(vault,clock).reset(EnumSet.of(Tracker.ResetPart.SESSIONS));
            }
            try(var files=Files.list(dir)){
                Path backup=files.filter(p->p.getFileName().toString().contains(".reset-")).findFirst().orElseThrow();
                check(Arrays.equals(bytes,Files.readAllBytes(backup)),"Reset backup preserves original encrypted bytes");
            }
        }finally{try(var files=Files.walk(dir)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        before=t.state();repo.backupFail=true;rejects(()->tracker.reset(EnumSet.of(Tracker.ResetPart.SESSIONS)));
        check(t.state().equals(before),"Failed backup blocks reset");repo.backupFail=false;
        t.reset(EnumSet.of(Tracker.ResetPart.SESSIONS));

        // A session under the floor arrives from an older vault or a JSON import,
        // since the tracker refuses to make one. It counts toward no total.
        clock.advance(4000);var end=clock.instant();
        var shortOne=new Session(UUID.randomUUID(),activity,end.minusSeconds(120),end);
        repo.state=t.state().withCore(t.state().activities(),
            java.util.stream.Stream.concat(t.state().sessions().stream(),java.util.stream.Stream.of(shortOne)).toList(),
            t.state().blocks());
        t=new Tracker(repo,clock);
        check(t.state().sessions().contains(shortOne),"An imported short session is present");
        t.reset(EnumSet.of(Tracker.ResetPart.SESSIONS));
        clock.advance(1800);t.log(activity,end,clock.instant());

        // An import replaces the whole vault, so it is backed up first like every
        // other whole-vault write; a backup that cannot be written blocks it.
        int beforeImport=repo.backups;
        final var importing=t;
        importing.restore(State.empty());
        check(importing.state().equals(State.empty()),"An imported vault replaces the live one");
        check(repo.backups==beforeImport+1,"and the vault it replaced is backed up first, got "+repo.backups);
        repo.backupFail=true;
        var kept=importing.state();
        rejects(()->importing.restore(State.empty()));
        check(importing.state().equals(kept)&&repo.backups==beforeImport+1,"a failed backup blocks the import and changes nothing");
        repo.backupFail=false;

        // Purging removes recorded time, so it is backed up first too — but only
        // when there is something to remove.
        var purgeClock=new Time();var purgeRepo=new Memory();
        var purging=new Tracker(purgeRepo,purgeClock);
        purging.addActivity("Study",0);UUID purged=purging.state().activities().getFirst().id();
        var purgeEnd=purgeClock.instant();
        purgeRepo.state=purging.state().withCore(purging.state().activities(),
            List.of(new Session(UUID.randomUUID(),purged,purgeEnd.minusSeconds(120),purgeEnd)),
            purging.state().blocks());
        purging=new Tracker(purgeRepo,purgeClock);
        int beforePurge=purgeRepo.backups;
        check(purging.purgeShortSessions()==1,"a stored session below the floor is purged");
        check(purgeRepo.backups==beforePurge+1,"and the vault was backed up before it went, got "+purgeRepo.backups);
        check(purging.purgeShortSessions()==0&&purgeRepo.backups==beforePurge+1,"purging nothing writes no backup");
        purgeRepo.backupFail=true;
        purgeRepo.state=purging.state().withCore(purging.state().activities(),
            List.of(new Session(UUID.randomUUID(),purged,purgeEnd.minusSeconds(120),purgeEnd)),
            purging.state().blocks());
        final var refusedPurge=new Tracker(purgeRepo,purgeClock);
        rejects(()->refusedPurge.purgeShortSessions());
        check(refusedPurge.state().sessions().size()==1,"a failed backup blocks the purge");
        System.out.println("PASS: "+checks+" reliability checks (edits, failed saves, reopen, imports, purges and reset backups)");
    }
}
