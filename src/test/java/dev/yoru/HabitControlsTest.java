package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.PortableVault;
import dev.yoru.persistence.EncryptedVault;
import java.nio.file.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

public final class HabitControlsTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private interface Action{void run()throws Exception;}
    private static void refuses(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(IllegalArgumentException|IOException expected){checks++;}}
    private static class Memory implements Repository{
        State state=State.empty();boolean failSave,failBackup;int saves,backups;
        public State load(){return state;}
        public void save(State next)throws IOException{if(failSave)throw new IOException("Synthetic save failure");state=next;saves++;}
        public void backup()throws IOException{if(failBackup)throw new IOException("Synthetic backup failure");backups++;}
        public void close(){}
    }
    private static List<UUID> ids(Tracker t){return t.state().habits().stream().map(Habit::id).toList();}
    public static void main(String[] args)throws Exception{
        var now=Instant.parse("2026-09-24T01:00:00Z");var repo=new Memory();var t=new Tracker(repo,Clock.fixed(now,ZoneOffset.UTC));
        t.addHabit("Reading",HabitKind.DAILY,ZoneId.of("Asia/Tokyo"),null);
        t.addHabit("Reset",HabitKind.TIME_SINCE,ZoneOffset.UTC,now.minusSeconds(3600));
        t.addHabit("Stretch",HabitKind.DAILY,ZoneOffset.UTC,null);
        t.addHabit("Rest",HabitKind.TIME_SINCE,ZoneOffset.UTC,now.minusSeconds(7200));
        var original=ids(t);var daily=original.getFirst();var since=original.get(1);
        t.checkIn(daily,LocalDate.of(2026,9,24),true);
        var record=t.state().habits().getFirst();
        t.habitZone(daily,ZoneId.of("America/Los_Angeles"));
        var changed=t.state().habits().getFirst();
        check(changed.zone().equals("America/Los_Angeles"),"The daily boundary changes");
        check(changed.checkIns().equals(record.checkIns())&&changed.since().equals(record.since()),"Recorded dates and beginning stay unchanged even across the date line");
        int saves=repo.saves,backups=repo.backups;
        t.habitZone(daily,ZoneId.of("America/Los_Angeles"));t.moveHabit(daily,-1);
        check(repo.saves==saves&&repo.backups==backups,"Same-zone and first-up operations are no-ops");
        t.moveHabit(daily,1);
        check(ids(t).equals(List.of(original.get(2),since,daily,original.get(3))),"A daily habit moves only past its daily neighbour");
        check(t.state().habits().get(2).equals(changed),"Reordering preserves every field");
        t.moveHabit(since,1);
        check(ids(t).equals(List.of(original.get(2),original.get(3),daily,since)),"Time-since order is independent");
        t.moveHabit(daily,-1);t.moveHabit(since,-1);
        check(ids(t).equals(original),"Moving back restores both lists");
        var before=t.state();
        refuses(()->t.moveHabit(UUID.randomUUID(),1));refuses(()->t.moveHabit(daily,0));refuses(()->t.habitZone(since,ZoneOffset.UTC));
        check(t.state().equals(before),"Stale, invalid and wrong-kind edits leave state intact");
        repo.failSave=true;refuses(()->t.moveHabit(daily,1));refuses(()->t.habitZone(daily,ZoneOffset.UTC));
        check(t.state().equals(before),"Failed saves preserve order and zone");repo.failSave=false;
        repo.failBackup=true;refuses(()->t.moveHabit(daily,1));refuses(()->t.habitZone(daily,ZoneOffset.UTC));
        check(t.state().equals(before),"Backup failure blocks both changes");repo.failBackup=false;
        t.moveHabit(daily,1);
        var saved=t.state();
        check(PortableVault.parse(PortableVault.export(saved,now)).equals(saved),"Changed zone and order survive portable roundtrip");
        var dir=Files.createTempDirectory("yoru-habit-controls");
        try {
            var file=dir.resolve("synthetic.vault");
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){vault.save(saved);}
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){check(vault.load().equals(saved),"Changed zone and order survive encrypted reopening");}
        }finally{try(var files=Files.walk(dir)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("PASS: "+checks+" habit control checks (order, zones, calendar dates, no-ops, failures)");
    }
}
