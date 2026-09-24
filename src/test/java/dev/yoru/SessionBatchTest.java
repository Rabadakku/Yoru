package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Whole-timeline validation and persistence failures use invented sessions. */
public final class SessionBatchTest {
    private static int checks;
    private static final Instant NOW=Instant.parse("2026-09-24T20:00:00Z");
    private static class Memory implements Repository{
        State state=State.empty();int saves,backups;boolean failSave,failBackup;
        public State load(){return state;}
        public void save(State next)throws IOException{if(failSave)throw new IOException("Synthetic save failure");saves++;state=next;}
        public void backup()throws IOException{if(failBackup)throw new IOException("Synthetic backup failure");backups++;}
        public void close(){}
    }
    private record Fixture(Memory repo,Tracker tracker,UUID source,UUID target,List<Session> sessions){}
    private static Fixture fixture()throws Exception{
        var repo=new Memory();var t=new Tracker(repo,Clock.fixed(NOW,ZoneOffset.UTC));
        t.addActivity("Reading",0);t.addActivity("Writing",0);
        var a=t.state().activities().get(0).id();var b=t.state().activities().get(1).id();
        t.log(a,at("08:00"),at("09:00"));t.log(a,at("09:00"),at("10:00"));t.log(b,at("12:00"),at("13:00"));
        return new Fixture(repo,t,a,b,t.state().sessions());
    }
    private static Instant at(String time){return Instant.parse("2026-09-24T"+time+":00Z");}
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private interface Action{void run()throws Exception;}
    private static void refuses(Action action)throws Exception{
        try{action.run();throw new AssertionError("Expected refusal");}catch(IllegalArgumentException|IOException|DateTimeException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        var f=fixture();var t=f.tracker();var repo=f.repo();
        var ids=f.sessions().subList(0,2).stream().map(Session::id).toList();
        var before=t.state();int saves=repo.saves,backups=repo.backups;
        t.editSessions(ids,new SessionBatch.Move(f.target()));
        check(repo.saves==saves+1&&repo.backups==backups+1,"Moving many sessions uses one backup and one write");
        for(int i=0;i<2;i++){
            var session=t.state().sessions().get(i);var old=f.sessions().get(i);
            check(session.activityId().equals(f.target())&&session.id().equals(old.id())&&session.start().equals(old.start())&&session.end().equals(old.end()),"Move changes only the selected activity");
        }
        check(t.state().sessions().get(2).equals(f.sessions().get(2)),"Unselected sessions stay unchanged");
        saves=repo.saves;backups=repo.backups;
        t.editSessions(ids,new SessionBatch.Move(f.target()));t.editSessions(ids,new SessionBatch.Shift(Duration.ZERO));
        check(repo.saves==saves&&repo.backups==backups,"No-op edits take no backup and no save");
        t.editSessions(ids,new SessionBatch.Shift(Duration.ofMinutes(30)));
        check(t.state().sessions().get(0).start().equals(at("08:30"))&&t.state().sessions().get(1).start().equals(at("09:30")),"Adjacent selections shift together without intermediate overlap refusal");
        check(t.state().sessions().get(0).seconds(NOW)==3600&&t.state().sessions().get(1).seconds(NOW)==3600,"Shifting retains durations");
        t.editSessions(List.of(ids.get(0),ids.get(0)),new SessionBatch.Shift(Duration.ofMinutes(-30)));
        check(t.state().sessions().get(0).start().equals(at("08:00")),"Duplicate IDs shift once");
        var stable=t.state();saves=repo.saves;backups=repo.backups;
        refuses(()->t.editSessions(ids,new SessionBatch.Shift(Duration.ofHours(3))));
        refuses(()->t.editSessions(ids,new SessionBatch.Shift(Duration.ofHours(15))));
        refuses(()->t.editSessions(ids,new SessionBatch.Move(UUID.randomUUID())));
        refuses(()->t.editSessions(List.of(ids.get(0),UUID.randomUUID()),new SessionBatch.Move(f.target())));
        refuses(()->t.deleteSessions(List.of()));refuses(()->t.deleteSessions(List.of(ids.get(0),UUID.randomUUID())));
        check(t.state().equals(stable)&&repo.saves==saves&&repo.backups==backups,"Invalid overlap, future, stale or empty selections never write or back up");
        repo.failSave=true;refuses(()->t.editSessions(ids,new SessionBatch.Move(f.source())));refuses(()->t.deleteSessions(ids));
        check(t.state().equals(stable),"Failed edit and delete writes retain every selected record");repo.failSave=false;
        repo.failBackup=true;refuses(()->t.editSessions(ids,new SessionBatch.Move(f.source())));refuses(()->t.deleteSessions(ids));
        check(t.state().equals(stable),"Backup failure blocks both operations");repo.failBackup=false;
        t.start(f.source());var running=t.active().id();var activeState=t.state();
        refuses(()->t.editSessions(List.of(ids.get(0),running),new SessionBatch.Move(f.target())));
        refuses(()->t.deleteSessions(List.of(ids.get(0),running)));
        check(t.state().equals(activeState),"Including the running timer refuses the whole batch");
        t.deleteSessions(ids);
        check(t.state().sessions().size()==2&&t.active().id().equals(running),"Deleting completed sessions preserves an unselected running timer");
        check(t.state().activities().equals(before.activities())&&t.state().anki().equals(before.anki())&&t.state().tasks().equals(before.tasks()),"Deletion preserves activities, Anki counts and tasks");
        var dst=State.empty();var activity=new Activity(UUID.randomUUID(),"Practice",0);
        var imported=new Session(new UUID(0x416E6B6900008000L,0x8000000000000021L),activity.id(),Instant.parse("2026-11-01T05:15:00Z"),Instant.parse("2026-11-01T05:45:00Z"));
        var dstRepo=new Memory();dstRepo.state=dst.withCore(List.of(activity),List.of(imported),List.of());
        var dstTracker=new Tracker(dstRepo,Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"),ZoneOffset.UTC));
        dstTracker.editSessions(List.of(imported.id()),new SessionBatch.Shift(Duration.ofHours(1)));
        var shifted=dstTracker.state().sessions().getFirst();var zone=ZoneId.of("America/New_York");
        check(shifted.start().atZone(zone).toLocalTime().equals(imported.start().atZone(zone).toLocalTime()),"Elapsed shifts cross the daylight-saving repeated hour correctly");
        check(shifted.seconds(NOW)==1800&&AnkiTime.isSitting(shifted)&&shifted.id().equals(imported.id()),"Duration and the Anki import identity survive edits");
        var saved=dstTracker.state();
        check(PortableVault.parse(PortableVault.export(saved,NOW)).equals(saved),"Batch-edited data survives portable roundtrip");
        var dir=Files.createTempDirectory("yoru-session-batch");
        try{
            var path=dir.resolve("synthetic.vault");
            try(var vault=new EncryptedVault(path,"fixture-password".toCharArray())){vault.save(saved);}
            try(var vault=new EncryptedVault(path,"fixture-password".toCharArray())){check(vault.load().equals(saved),"Batch-edited data survives encrypted reopening");}
        }finally{try(var paths=Files.walk(dir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("PASS: "+checks+" session batch checks (atomic writes, overlap, running timers, failures, Anki IDs, DST, vaults)");
    }
}
