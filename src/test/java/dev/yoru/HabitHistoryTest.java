package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Invented old check-ins and missed restarts; failures may not rewrite history. */
public final class HabitHistoryTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private interface Action{void run()throws Exception;}
    private static void refuses(Action action)throws Exception {
        try{action.run();throw new AssertionError("Expected refusal");}catch(IllegalArgumentException|IOException expected){checks++;}
    }
    private static class Memory implements Repository {
        State state=State.empty();int saves,backups;boolean failSave,failBackup;
        public State load(){return state;}
        public void save(State next)throws IOException{if(failSave)throw new IOException("Synthetic save failure");saves++;state=next;}
        public void backup()throws IOException{if(failBackup)throw new IOException("Synthetic backup failure");backups++;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception {
        var now=Instant.parse("2026-09-23T00:30:35Z");
        var repo=new Memory();var tracker=new Tracker(repo,Clock.fixed(now,ZoneOffset.UTC));
        var zone=ZoneId.of("America/Los_Angeles");
        var today=LocalDate.of(2026,9,22);var old=LocalDate.of(2024,2,29);
        tracker.addHabit("Evening reading",HabitKind.DAILY,zone,null);
        var id=tracker.state().habits().getFirst().id();
        check(tracker.state().habits().getFirst().since().equals(today),"Creation uses the injected clock in the habit zone");
        tracker.checkIn(id,old,true);
        check(tracker.state().habits().getFirst().checkIns().contains(old),"A leap-day correction more than four weeks old saves");
        check(tracker.state().habits().getFirst().since().equals(old),"Earlier evidence extends the start");
        check(repo.backups==1,"An old correction is backed up first");
        tracker.checkIn(id,today,true);
        tracker.renameHabit(id,"Reading");
        check(tracker.state().habits().getFirst().since().equals(old),"Renaming keeps the beginning");
        tracker.checkIn(id,old,false);
        check(tracker.state().habits().getFirst().since().equals(old),"Removing the earliest check-in does not erase intervening missed days");
        tracker.checkIn(id,today,false);
        check(tracker.state().habits().getFirst().since().equals(old),"Removing the last check-in retains the beginning");
        int saves=repo.saves,backups=repo.backups;
        tracker.checkIn(id,today,false);
        check(repo.saves==saves&&repo.backups==backups,"A repeated removal is a no-op");
        var before=tracker.state();
        refuses(()->tracker.checkIn(id,today.plusDays(1),true));
        check(tracker.state().equals(before),"Future is checked in the habit's zone");
        repo.failSave=true;refuses(()->tracker.checkIn(id,old,true));
        check(tracker.state().equals(before),"Failed correction retains the whole history");repo.failSave=false;
        repo.failBackup=true;refuses(()->tracker.checkIn(id,old,true));
        check(tracker.state().equals(before),"A failed backup blocks the correction");repo.failBackup=false;
        var first=now.minus(Duration.ofDays(5)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        tracker.addHabit("Evening reset",HabitKind.TIME_SINCE,ZoneOffset.UTC,first);
        var since=tracker.state().habits().getLast().id();
        tracker.restartHabit(since);
        var existing=tracker.state().habits().getLast().starts();
        var middle=now.minus(Duration.ofDays(2));
        tracker.addHabitPeriod(since,middle);
        var periods=tracker.state().habits().getLast().starts();
        check(periods.size()==3&&periods.containsAll(existing),"A missed restart splits a past period without changing its neighbours");
        check(periods.get(1).equals(middle.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)),"A missed restart uses a whole minute");
        tracker.addHabitPeriod(since,first.minus(Duration.ofDays(1)));
        check(tracker.state().habits().getLast().starts().getFirst().equals(first.minus(Duration.ofDays(1))),"A missed initial start can precede the current history");
        before=tracker.state();
        refuses(()->tracker.addHabitPeriod(since,middle.plusSeconds(3)));
        refuses(()->tracker.addHabitPeriod(since,now.plusSeconds(1)));
        refuses(()->tracker.addHabitPeriod(id,middle));
        refuses(()->tracker.addHabitPeriod(UUID.randomUUID(),middle));
        check(tracker.state().equals(before),"Duplicate-minute, future, wrong-kind and stale actions leave history intact");
        repo.failSave=true;refuses(()->tracker.addHabitPeriod(since,first.plusSeconds(3600)));
        check(tracker.state().equals(before),"A failed period save is atomic");repo.failSave=false;
        repo.failBackup=true;refuses(()->tracker.addHabitPeriod(since,first.plusSeconds(3600)));
        check(tracker.state().equals(before),"A failed period backup blocks saving");repo.failBackup=false;
        var savedStart=tracker.state().habits().getLast().starts().getFirst();
        repo.failBackup=true;refuses(()->tracker.editHabitPeriod(since,savedStart,savedStart.minusSeconds(60)));
        check(tracker.state().equals(before),"Editing an existing period also requires its backup");repo.failBackup=false;
        var state=tracker.state();
        check(PortableVault.parse(PortableVault.export(state,now)).equals(state),"Corrected histories survive portable roundtrip");
        var dir=Files.createTempDirectory("yoru-habit-history");
        try {
            var file=dir.resolve("synthetic.vault");
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){vault.save(state);}
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){check(vault.load().equals(state),"Corrected histories survive encrypted reopening");}
        }finally{try(var files=Files.walk(dir)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("PASS: "+checks+" habit history checks (old days, preserved beginning, missed restarts, atomic failures, vaults)");
    }
}
