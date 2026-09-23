package dev.yoru;

import dev.yoru.application.Pomodoro;
import dev.yoru.application.Pomodoro.Cue;
import dev.yoru.application.Pomodoro.Phase;
import dev.yoru.application.Pomodoro.Plan;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The pomodoro's timing (#61), on a clock that only moves when the test says:
 * what gets recorded as work, what a pause, a skip and five more minutes do,
 * auto-start, and a computer that sleeps through the end of an interval.
 */
public final class PomodoroTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** Work as recorded: each begun interval and where it ended. */
    private static final class Ledger implements Pomodoro.Work {
        final List<Instant[]> intervals = new ArrayList<>();
        Instant now;
        Instant open;
        public void begin() { if (open != null) throw new AssertionError("work begun twice"); open = now; }
        public void end(Instant at) {
            if (open == null) throw new AssertionError("work ended that was never begun");
            intervals.add(new Instant[]{open, at});
            open = null;
        }
        Duration total() {
            return intervals.stream().map(i -> Duration.between(i[0], i[1])).reduce(Duration.ZERO, Duration::plus);
        }
    }

    private static Instant t0 = Instant.parse("2026-09-23T09:00:00Z");
    private static Instant at(long minutes) { return t0.plus(Duration.ofMinutes(minutes)); }

    public static void main(String[] args) throws Exception {
        // A full cycle, starting each phase by hand: four work intervals and a long break.
        var ledger = new Ledger();
        var pomodoro = new Pomodoro(Plan.defaults(), ledger);
        check(pomodoro.phase() == Phase.WORK && !pomodoro.running() && pomodoro.left(t0).equals(Duration.ofMinutes(25)),
            "It starts on 25 minutes of work, waiting");
        long clock = 0;
        var cues = new ArrayList<Cue>();
        var phases = new ArrayList<Phase>();
        for (int step = 0; step < 8; step++) {
            ledger.now = at(clock);
            pomodoro.start(at(clock));
            phases.add(pomodoro.phase());
            clock += pomodoro.left(at(clock)).toMinutes();
            ledger.now = at(clock);
            check(pomodoro.tick(at(clock).minusSeconds(1)).cue() == null, "Nothing ends a second early");
            var tick = pomodoro.tick(at(clock));
            check(tick.ended(), "the phase ends on the minute");
            cues.add(tick.cue());
        }
        check(phases.equals(List.of(Phase.WORK, Phase.SHORT_BREAK, Phase.WORK, Phase.SHORT_BREAK, Phase.WORK,
            Phase.SHORT_BREAK, Phase.WORK, Phase.LONG_BREAK)), "Work and short breaks, then a long one after the fourth: " + phases);
        check(ledger.intervals.size() == 4, "A full cycle records exactly the four work intervals");
        check(ledger.total().equals(Duration.ofMinutes(100)), "a hundred minutes of work, and no break time");
        check(cues.equals(List.of(Cue.WORK_DONE, Cue.BREAK_DONE, Cue.WORK_DONE, Cue.BREAK_DONE, Cue.WORK_DONE, Cue.BREAK_DONE,
            Cue.WORK_DONE, Cue.BREAK_DONE)), "Each end plays its own cue, once");
        check(pomodoro.finished() == 4 && pomodoro.phase() == Phase.WORK && pomodoro.round() == 1,
            "four finished, and the next cycle starts from its first round");
        check(clock == 25 * 4 + 5 * 3 + 15, "the long break is 15 minutes");

        // Pause keeps the time left and records the work so far; resuming carries on.
        ledger = new Ledger();
        pomodoro = new Pomodoro(Plan.defaults(), ledger);
        ledger.now = at(0);
        pomodoro.start(at(0));
        pomodoro.pause(at(10));
        check(!pomodoro.running() && pomodoro.left(at(40)).equals(Duration.ofMinutes(15)), "Paused, the clock stands still");
        check(ledger.intervals.size() == 1 && ledger.total().equals(Duration.ofMinutes(10)), "and the ten minutes worked are recorded");
        ledger.now = at(20);
        pomodoro.start(at(20));
        check(pomodoro.tick(at(34)).cue() == null && pomodoro.tick(at(35)).cue() == Cue.WORK_DONE, "Resumed, it ends 15 minutes later");
        check(ledger.total().equals(Duration.ofMinutes(25)) && ledger.intervals.size() == 2, "25 minutes of work in two pieces");
        check(pomodoro.phase() == Phase.SHORT_BREAK && !pomodoro.running(), "then the break waits to be started");

        // Five more minutes, running or not.
        pomodoro.extend(Duration.ofMinutes(5));
        check(pomodoro.left(at(35)).equals(Duration.ofMinutes(10)), "+5 on a waiting break makes it ten");
        check(pomodoro.progress(at(35)) == 0, "and the ring still starts empty");
        pomodoro.start(at(35));
        pomodoro.extend(Duration.ofMinutes(5));
        check(pomodoro.tick(at(49)).cue() == null && pomodoro.tick(at(50)).cue() == Cue.BREAK_DONE, "+5 while running moves the end");

        // Skipping work: what was done is recorded, but it is not a pomodoro.
        int before = pomodoro.finished();
        ledger.now = at(50);
        pomodoro.start(at(50));
        pomodoro.skip(at(58));
        check(pomodoro.phase() == Phase.SHORT_BREAK && pomodoro.finished() == before, "A skipped work interval does not count");
        check(ledger.intervals.getLast()[1].equals(at(58)), "but its eight minutes are kept");
        pomodoro.skip(at(58));
        check(pomodoro.phase() == Phase.WORK && !pomodoro.running(), "Skipping a break goes back to work, waiting");

        // Auto-start: the next phase begins as soon as one ends.
        ledger = new Ledger();
        pomodoro = new Pomodoro(new Plan(1, 1, 2, 2, true), ledger);
        ledger.now = at(0);
        pomodoro.start(at(0));
        ledger.now = at(1);
        check(pomodoro.tick(at(1)).cue() == Cue.WORK_DONE && pomodoro.running() && pomodoro.phase() == Phase.SHORT_BREAK,
            "With auto-start the break runs straight away");
        ledger.now = at(2);
        pomodoro.tick(at(2));
        check(pomodoro.running() && pomodoro.phase() == Phase.WORK && ledger.open != null, "and work after it, recorded again");
        ledger.now = at(3);
        pomodoro.tick(at(3));
        check(pomodoro.phase() == Phase.LONG_BREAK && pomodoro.running(), "the second work interval earns the long break here");

        // Asleep through the end: the work ends where it should have, nothing plays, nothing runs on.
        ledger = new Ledger();
        pomodoro = new Pomodoro(new Plan(25, 5, 15, 4, true), ledger);
        ledger.now = at(0);
        pomodoro.start(at(0));
        ledger.now = at(180);
        var woke = pomodoro.tick(at(180));
        check(woke.ended() && woke.cue() == null, "Waking hours later ends the interval without a sound");
        check(ledger.intervals.size() == 1 && ledger.intervals.getFirst()[1].equals(at(25)),
            "and records it to its proper end, not to the moment of waking");
        check(!pomodoro.running() && pomodoro.phase() == Phase.SHORT_BREAK, "The break waits rather than running through the night");
        check(pomodoro.tick(at(181)).cue() == null && !pomodoro.tick(at(181)).ended(), "and nothing more ends by itself");
        // A short gap is not being away: the cue still plays.
        ledger = new Ledger();
        pomodoro = new Pomodoro(Plan.defaults(), ledger);
        ledger.now = at(0);
        pomodoro.start(at(0));
        ledger.now = at(26);
        check(pomodoro.tick(at(26)).cue() == Cue.WORK_DONE, "A minute late, the cue still plays once");
        check(pomodoro.tick(at(26)).cue() == null, "and never twice");

        // A clock that jumps backwards changes nothing.
        ledger = new Ledger();
        pomodoro = new Pomodoro(Plan.defaults(), ledger);
        ledger.now = at(0);
        pomodoro.start(at(0));
        check(!pomodoro.tick(at(-60)).ended() && pomodoro.left(at(-60)).equals(Duration.ofMinutes(85)),
            "A clock set back an hour just shows more time left");

        // A restarted app picks up where it was.
        var saved = pomodoro.save();
        var again = new Pomodoro(Plan.defaults(), new Ledger());
        again.restore(saved, true);
        check(again.running() && again.phase() == Phase.WORK && again.left(at(20)).equals(Duration.ofMinutes(5)),
            "Restored with its session still running, it carries on");
        var stopped = new Pomodoro(Plan.defaults(), new Ledger());
        stopped.restore(saved, false);
        check(!stopped.running(), "Restored after the session was clocked out elsewhere, it waits");

        // A new plan: one not started takes it now, a running one from its next phase.
        var idle = new Pomodoro(Plan.defaults(), new Ledger());
        idle.plan(new Plan(50, 10, 30, 2, false));
        check(idle.left(at(0)).equals(Duration.ofMinutes(50)), "A plan changed before starting takes effect at once");
        try { new Plan(0, 5, 15, 4, false); throw new AssertionError("a zero-minute interval was accepted"); }
        catch (IllegalArgumentException expected) { checks++; }

        System.out.println("PASS: " + checks + " pomodoro checks (a full cycle, pause, skip, +5, auto-start, sleep and wake, restore)");
    }
}
