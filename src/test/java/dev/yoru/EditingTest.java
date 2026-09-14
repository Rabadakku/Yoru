package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * The editing gaps the #21 audit found, against the tracker: an activity's
 * daily target, stale deletes, and every period of a time-since tracker.
 *
 * Each edit targets an identity — an id, or the instant a period starts —
 * never a position or a label, so a control opened before something changed
 * is refused rather than applied to whatever now sits where it pointed.
 * Invented data only.
 */
public final class EditingTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    interface Action {void run()throws Exception;}
    private static String rejects(Action action,String why)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IOException expected){checks++;return expected.getMessage();}
        throw new AssertionError(why);
    }

    private static final class Memory implements Repository {
        State state=State.empty();boolean fail;int backups,saves;
        public State load(){return state;}
        public void save(State next)throws IOException{if(fail)throw new IOException("Disk full");state=next;saves++;}
        public void backup(){backups++;}
        public void close(){}
    }

    private static final class Time extends Clock {
        Instant now=Instant.parse("2026-09-09T12:00:00Z");
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
    }

    public static void main(String[] args)throws Exception {
        var repo=new Memory();
        var clock=new Time();
        var tracker=new Tracker(repo,clock);
        tracker.addActivity("Reading",30);
        var reading=tracker.state().activities().getFirst();
        tracker.log(reading.id(),clock.now.minusSeconds(3600),clock.now.minusSeconds(1800));
        var sessions=tracker.state().sessions();

        // An activity's daily target, changed by identity.
        int backups=repo.backups;
        tracker.retargetActivity(reading.id(),45);
        var retargeted=tracker.state().activities().getFirst();
        check(retargeted.targetMinutes()==45,"the target changes");
        check(retargeted.id().equals(reading.id())&&retargeted.name().equals("Reading"),"and nothing else about the activity does");
        check(tracker.state().sessions().equals(sessions),"every session stays attached");
        check(repo.backups==backups+1,"a backup is taken first, as for a rename");
        int saves=repo.saves;
        tracker.retargetActivity(reading.id(),45);
        check(repo.saves==saves&&repo.backups==backups+1,"setting the same target writes nothing");
        rejects(()->tracker.retargetActivity(reading.id(),1441),"a target over a day is refused");
        rejects(()->tracker.retargetActivity(reading.id(),-1),"a negative target is refused");
        rejects(()->tracker.retargetActivity(UUID.randomUUID(),10),"a removed activity is refused");
        repo.fail=true;
        rejects(()->tracker.retargetActivity(reading.id(),60),"a failed write is reported");
        repo.fail=false;
        check(tracker.state().activities().getFirst().targetMinutes()==45,"and leaves the target as it was");

        // A stale delete says so instead of appearing to work.
        var session=tracker.state().sessions().getFirst().id();
        tracker.deleteSession(session);
        check(tracker.state().sessions().isEmpty(),"a session is deleted");
        check(rejects(()->tracker.deleteSession(session),"deleting a session twice is refused").contains("no longer exists"),
            "with a reason");
        tracker.plan(reading.id(),clock.now.plusSeconds(3600),clock.now.plusSeconds(7200));
        var block=tracker.state().blocks().getFirst().id();
        tracker.deleteBlock(block);
        rejects(()->tracker.deleteBlock(block),"deleting a block twice is refused");
        rejects(()->tracker.deleteBlock(UUID.randomUUID()),"deleting a block that never existed is refused");

        // Every period of a time-since tracker, not only the current one.
        var t0=clock.now.minusSeconds(100_000);
        tracker.addHabit("Time since",HabitKind.TIME_SINCE,ZoneOffset.UTC,t0);
        var habit=tracker.state().habits().getFirst().id();
        clock.now=clock.now.plusSeconds(1000);
        tracker.restartHabit(habit);
        var t1=clock.now;
        clock.now=clock.now.plusSeconds(1000);
        tracker.restartHabit(habit);
        var t2=clock.now;
        check(starts(tracker,habit).equals(List.of(t0,t1,t2)),"three periods to work with");

        var moved=t1.minusSeconds(500);
        tracker.editHabitPeriod(habit,t1,moved);
        check(starts(tracker,habit).equals(List.of(t0,moved,t2)),"an earlier period's start moves, and only it");
        check(rejects(()->tracker.editHabitPeriod(habit,t1,t1.minusSeconds(100)),"a period that changed since it was opened is refused")
            .contains("Reopen"),"saying to reopen the history");
        check(rejects(()->tracker.editHabitPeriod(habit,moved,t0.minusSeconds(1)),"a start before the previous period is refused")
            .contains("after the one before"),"saying which neighbour is in the way");
        check(rejects(()->tracker.editHabitPeriod(habit,moved,t2),"a start at or after the next period is refused")
            .contains("before the one after"),"saying which neighbour is in the way");
        rejects(()->tracker.editHabitPeriod(habit,t2,clock.now.plusSeconds(1)),"a start in the future is refused");
        check(starts(tracker,habit).equals(List.of(t0,moved,t2)),"refusals change nothing");

        backups=repo.backups;
        tracker.deleteHabitPeriod(habit,moved);
        check(starts(tracker,habit).equals(List.of(t0,t2)),"deleting a period's start joins it to its neighbour");
        check(repo.backups==backups+1,"after a backup, as deleting a habit takes");
        rejects(()->tracker.deleteHabitPeriod(habit,moved),"deleting the same period twice is refused");
        tracker.deleteHabitPeriod(habit,t0);
        check(starts(tracker,habit).equals(List.of(t2)),"the first period can go too");
        check(rejects(()->tracker.deleteHabitPeriod(habit,t2),"the last period cannot be deleted").contains("Delete the tracker"),
            "pointing at the control that does that");

        tracker.editHabitStart(habit,t2.minusSeconds(10));
        check(starts(tracker,habit).equals(List.of(t2.minusSeconds(10))),"editing the current start still works");

        tracker.addHabit("Daily",HabitKind.DAILY,ZoneOffset.UTC,null);
        var daily=tracker.state().habits().getLast().id();
        rejects(()->tracker.editHabitPeriod(daily,t2,t2),"a daily tracker has no periods to edit");
        rejects(()->tracker.deleteHabitPeriod(daily,t2),"or to delete");

        // Everything above survives a reopen.
        var reopened=new Tracker(repo,clock);
        check(reopened.state().equals(tracker.state()),"every edit reached the vault");

        System.out.println("PASS: "+checks+" editing checks (activity targets, stale deletes, time-since periods)");
    }

    private static List<Instant> starts(Tracker tracker,UUID habit) {
        return tracker.state().habits().stream().filter(h->h.id().equals(habit)).findFirst().orElseThrow().starts();
    }
}
