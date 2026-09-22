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

        // A stale delete says so instead of appearing to work. A delete that
        // goes ahead is backed up first, now that backups are pruned (#7); a
        // refused one takes no backup.
        var session=tracker.state().sessions().getFirst().id();
        backups=repo.backups;
        tracker.deleteSession(session);
        check(tracker.state().sessions().isEmpty(),"a session is deleted");
        check(repo.backups==backups+1,"after a backup");
        check(rejects(()->tracker.deleteSession(session),"deleting a session twice is refused").contains("no longer exists"),
            "with a reason");
        check(repo.backups==backups+1,"a refused delete takes no backup");
        tracker.plan(reading.id(),clock.now.plusSeconds(3600),clock.now.plusSeconds(7200));
        var block=tracker.state().blocks().getFirst().id();
        backups=repo.backups;
        tracker.deleteBlock(block);
        check(repo.backups==backups+1,"a block is backed up before it goes");
        rejects(()->tracker.deleteBlock(block),"deleting a block twice is refused");
        rejects(()->tracker.deleteBlock(UUID.randomUUID()),"deleting a block that never existed is refused");
        check(repo.backups==backups+1,"and the refusals take none");
        var tag=tracker.addTag("Invented tag",0x336699);
        backups=repo.backups;
        tracker.deleteTag(tag.id());
        check(repo.backups==backups+1,"a tag is backed up before it goes");
        rejects(()->tracker.deleteTag(tag.id()),"deleting a tag twice is refused");
        check(repo.backups==backups+1,"and the refusal takes none");
        var task=new Task(UUID.randomUUID(),null,"Invented task","",null,false,"manual");
        tracker.addTasks(List.of(task));
        backups=repo.backups;
        tracker.deleteTask(task.id());
        check(tracker.state().tasks().isEmpty()&&repo.backups==backups+1,"a task is backed up before it goes");
        rejects(()->tracker.deleteTask(task.id()),"deleting a task twice is refused");
        check(repo.backups==backups+1,"and the refusal takes none");

        // Every period of a time-since tracker, not only the current one.
        var t0=clock.now.minusSeconds(99_960);
        tracker.addHabit("Time since",HabitKind.TIME_SINCE,ZoneOffset.UTC,t0);
        var habit=tracker.state().habits().getFirst().id();
        clock.now=clock.now.plusSeconds(1020);
        tracker.restartHabit(habit);
        var t1=clock.now;
        clock.now=clock.now.plusSeconds(1020);
        tracker.restartHabit(habit);
        var t2=clock.now;
        check(starts(tracker,habit).equals(List.of(t0,t1,t2)),"three periods to work with");

        var moved=t1.minusSeconds(480);
        tracker.editHabitPeriod(habit,t1,moved);
        check(starts(tracker,habit).equals(List.of(t0,moved,t2)),"an earlier period's start moves, and only it");
        check(rejects(()->tracker.editHabitPeriod(habit,t1,t1.minusSeconds(100)),"a period that changed since it was opened is refused")
            .contains("Reopen"),"saying to reopen the history");
        check(rejects(()->tracker.editHabitPeriod(habit,moved,t0.minusSeconds(60)),"a start before the previous period is refused")
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

        tracker.editHabitStart(habit,t2.minusSeconds(60));
        check(starts(tracker,habit).equals(List.of(t2.minusSeconds(60))),"editing the current start still works");

        // Every period starts on a whole minute, however it was made (#50): two
        // trackers started in the same minute read the same and count the same.
        clock.now=t2.plusSeconds(3600).plusSeconds(37);
        tracker.restartHabit(habit);
        var whole=starts(tracker,habit).getLast();
        check(whole.equals(clock.now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)),
            "a restart at 37 seconds past is stored on the minute, got "+whole);
        tracker.addHabit("Second tracker",HabitKind.TIME_SINCE,ZoneOffset.UTC,clock.now.minusSeconds(11));
        var second=tracker.state().habits().getLast();
        check(second.starts().getFirst().equals(whole),"and so is a tracker started moments apart, got "+second.starts());
        // Two periods inside one minute cannot both be on it; the later keeps its seconds.
        clock.now=clock.now.plusSeconds(8);
        tracker.restartHabit(habit);
        check(starts(tracker,habit).getLast().equals(clock.now),
            "a restart in the same minute keeps its seconds rather than colliding");

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
