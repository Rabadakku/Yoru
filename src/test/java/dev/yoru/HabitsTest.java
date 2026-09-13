package dev.yoru;
import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public final class HabitsTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Check "+checks);}
    static class Memory implements Repository {
        State state=State.empty();boolean fail;
        public State load(){return state;}
        public void save(State s)throws java.io.IOException{if(fail)throw new java.io.IOException("test failure");state=s;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception {
        var now=Instant.parse("2026-09-09T01:00:00Z");var zone=ZoneId.of("America/New_York");var today=LocalDate.of(2026,9,8);
        var memory=new Memory();var t=new Tracker(memory,Clock.fixed(now,ZoneOffset.UTC));
        t.addHabit("Chores",HabitKind.DAILY,zone,null);var id=t.state().habits().getFirst().id();
        t.checkIn(id,today,true);t.checkIn(id,today,true);check(t.state().habits().getFirst().checkIns().size()==1);
        t.checkIn(id,today.minusDays(1),true);check(t.state().habits().getFirst().streak(today)==2);
        t.checkIn(id,today,false);check(t.state().habits().getFirst().streak(today)==1);
        check(t.state().habits().getFirst().streak(today.plusDays(1))==0);
        try{t.checkIn(id,today.plusDays(1),true);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        t.addHabit("Quit habit",HabitKind.TIME_SINCE,zone,now.minusSeconds(90000));var quit=t.state().habits().getLast().id();
        t.restartHabit(quit);check(t.state().habits().getLast().starts().size()==2);
        check(t.state().habits().getLast().starts().getFirst().equals(now.minusSeconds(90000)));
        var before=t.state();memory.fail=true;
        try{t.checkIn(id,today,true);throw new AssertionError();}catch(java.io.IOException expected){checks++;}
        check(t.state().equals(before));memory.fail=false;
        t.addActivity("Study",0);check(t.state().habits().equals(before.habits()));
        var daily=t.state().habits().getFirst();
        var periods=t.state().habits().getLast().starts();
        t.renameHabit(id,"  Evening chores  ");
        check(t.state().habits().getFirst().name().equals("Evening chores"));
        check(t.state().habits().getFirst().checkIns().equals(daily.checkIns()));
        check(t.state().habits().getFirst().id().equals(id));
        t.renameHabit(quit,"New name");
        check(t.state().habits().getLast().starts().equals(periods));
        var unchanged=t.state();
        try{t.renameHabit(id,"  ");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        check(t.state().equals(unchanged));
        memory.fail=true;
        try{t.renameHabit(id,"Failed rename");throw new AssertionError();}catch(java.io.IOException expected){checks++;}
        check(t.state().equals(unchanged));
        try{t.deleteHabit(id);throw new AssertionError();}catch(java.io.IOException expected){checks++;}
        check(t.state().equals(unchanged));memory.fail=false;
        t.deleteHabit(id);
        check(t.state().habits().size()==1&&t.state().habits().getFirst().id().equals(quit));
        check(t.state().activities().equals(unchanged.activities()));
        check(t.state().sessions().equals(unchanged.sessions()));
        check(t.state().habits().getFirst().starts().equals(periods));
        try{t.deleteHabit(id);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        var dir=Files.createTempDirectory("yoru-habits-");var path=dir.resolve("test.vault");
        try(var vault=new EncryptedVault(path,LocalAccess.create(path))){vault.save(t.state());}
        check(LocalAccess.enabled(path));
        try(var vault=new EncryptedVault(path,LocalAccess.read(path))){check(vault.load().equals(t.state()));}
        byte[] secret=Files.readAllBytes(LocalAccess.keyPath(path));
        try{LocalAccess.create(path);throw new AssertionError();}catch(java.io.IOException expected){checks++;}
        check(Arrays.equals(secret,Files.readAllBytes(LocalAccess.keyPath(path))));
        try(var walk=Files.walk(dir)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        System.out.println("PASS: "+checks+" daily streak, timezone, history, atomic persistence and password-free checks");
    }
}
