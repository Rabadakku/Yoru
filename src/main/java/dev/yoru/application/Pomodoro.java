package dev.yoru.application;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The pomodoro (#61): work, a short break, work, and after so many a long one.
 *
 * Only the timing lives here, with no screen and no clock of its own: every
 * call is told what time it is, which is what lets a test put it to sleep for
 * an hour and wake it again. Work is recorded through {@link Work}, as
 * sessions of the chosen activity like any clocked time; breaks are not.
 *
 * A phase ends at the moment it was due to, not when somebody next looked:
 * a work interval that ran out while the computer slept is recorded to its
 * proper end. After such a gap the next phase waits to be started rather than
 * running on by itself through time nobody was there for, and no sound is
 * played for an interval that ended long ago.
 */
public final class Pomodoro {
    public enum Phase { WORK, SHORT_BREAK, LONG_BREAK }

    /** What to play when a phase ends: the end of work, or the end of a break. */
    public enum Cue { WORK_DONE, BREAK_DONE }

    /** The intervals, in minutes, how many work intervals earn a long break, and whether the next starts by itself. */
    public record Plan(int workMinutes, int shortMinutes, int longMinutes, int longEvery, boolean autoStart) {
        public Plan {
            if (workMinutes < 1 || workMinutes > 180) throw new IllegalArgumentException("Work for 1 to 180 minutes.");
            if (shortMinutes < 1 || shortMinutes > 60) throw new IllegalArgumentException("A short break is 1 to 60 minutes.");
            if (longMinutes < 1 || longMinutes > 120) throw new IllegalArgumentException("A long break is 1 to 120 minutes.");
            if (longEvery < 1 || longEvery > 12) throw new IllegalArgumentException("A long break comes after 1 to 12 work intervals.");
        }
        /** 25 minutes of work, 5 of break, and 15 after every fourth. */
        public static Plan defaults() { return new Plan(25, 5, 15, 4, false); }
        public Duration length(Phase phase) {
            return Duration.ofMinutes(switch (phase) {
                case WORK -> workMinutes;
                case SHORT_BREAK -> shortMinutes;
                case LONG_BREAK -> longMinutes;
            });
        }
    }

    /** Where work goes: begun when a work interval starts or resumes, ended when it stops. */
    public interface Work {
        void begin() throws IOException;
        void end(Instant at) throws IOException;
    }

    /** What a tick found: the cue to play, if any, and whether the phase moved on. */
    public record Tick(Cue cue, boolean ended) {
        static final Tick NOTHING = new Tick(null, false);
    }

    /** Longer than this since a phase ended and nobody was there: no sound, and nothing starts by itself. */
    static final Duration AWAY = Duration.ofMinutes(2);

    private final Work work;
    private Plan plan;
    private Phase phase = Phase.WORK;
    private boolean running;
    private Instant endsAt;
    private Duration left;
    /** Work intervals finished since the last long break. */
    private int inCycle;
    /** Work intervals finished, for "3 pomodoros today". */
    private int finished;
    /** Time added to this phase with +5, so the ring still starts from empty. */
    private Duration extra = Duration.ZERO;

    public Pomodoro(Plan plan, Work work) {
        this.plan = Objects.requireNonNull(plan);
        this.work = Objects.requireNonNull(work);
        this.left = plan.length(phase);
    }

    public Phase phase() { return phase; }
    public boolean running() { return running; }
    public Plan plan() { return plan; }
    public int finished() { return finished; }
    /** Which work interval of the cycle this is, from 1: "2 of 4". */
    public int round() { return Math.min(plan.longEvery(), inCycle + (phase == Phase.WORK ? 1 : 0)); }

    /** Time left in the phase at {@code now}. */
    public Duration left(Instant now) {
        if (!running) return left;
        var rest = Duration.between(now, endsAt);
        return rest.isNegative() ? Duration.ZERO : rest;
    }

    /** How far through the phase, 0 to 1, for the ring. */
    public double progress(Instant now) {
        double whole = plan.length(phase).toMillis() + extra.toMillis();
        return whole <= 0 ? 0 : Math.max(0, Math.min(1, 1 - left(now).toMillis() / whole));
    }
    public void start(Instant now) throws IOException {
        if (running) return;
        if (phase == Phase.WORK) work.begin();
        running = true;
        endsAt = now.plus(left);
    }

    /** Stops the clock; work done so far is recorded. */
    public void pause(Instant now) throws IOException {
        if (!running) return;
        left = left(now);
        running = false;
        endsAt = null;
        if (phase == Phase.WORK) work.end(now);
    }

    /** Ends this phase now and moves on; skipped work is recorded but not counted as a pomodoro. */
    public void skip(Instant now) throws IOException {
        boolean was = running;
        if (running && phase == Phase.WORK) work.end(now);
        running = false;
        endsAt = null;
        moveOn(false);
        if (was && plan.autoStart()) start(now);
    }

    /** Five more minutes, or whatever is asked, on the phase in front of you. */
    public void extend(Duration more) {
        if (more.isNegative() || more.isZero()) throw new IllegalArgumentException("Add some time.");
        extra = extra.plus(more);
        if (running) endsAt = endsAt.plus(more); else left = left.plus(more);
    }

    /** Back to the start of a cycle, stopping any work that is running. */
    public void reset(Instant now) throws IOException {
        if (running && phase == Phase.WORK) work.end(now);
        running = false;
        endsAt = null;
        phase = Phase.WORK;
        inCycle = 0;
        extra = Duration.ZERO;
        left = plan.length(phase);
    }

    /** A new plan takes effect from the next phase; one not yet started takes it at once. */
    public void plan(Plan next) {
        boolean untouched = !running && extra.isZero() && left.equals(plan.length(phase));
        plan = Objects.requireNonNull(next);
        if (untouched) left = plan.length(phase);
    }

    /**
     * Moves on if the phase has run out by {@code now}. At most one phase ends
     * per tick, at the moment it was due to; after a long gap the next one
     * waits for its Start and no cue is played.
     */
    public Tick tick(Instant now) throws IOException {
        if (!running || now.isBefore(endsAt)) return Tick.NOTHING;
        var ended = endsAt;
        var was = phase;
        if (was == Phase.WORK) work.end(ended);
        running = false;
        endsAt = null;
        moveOn(was == Phase.WORK);
        boolean away = Duration.between(ended, now).compareTo(AWAY) > 0;
        if (plan.autoStart() && !away) start(now);
        return new Tick(away ? null : was == Phase.WORK ? Cue.WORK_DONE : Cue.BREAK_DONE, true);
    }

    private void moveOn(boolean counted) {
        extra = Duration.ZERO;
        if (phase == Phase.WORK) {
            if (counted) { finished++; inCycle++; }
            phase = counted && inCycle >= plan.longEvery() ? Phase.LONG_BREAK : Phase.SHORT_BREAK;
        } else {
            if (phase == Phase.LONG_BREAK) inCycle = 0;
            phase = Phase.WORK;
        }
        left = plan.length(phase);
    }

    /** Where a restarted app picks up: the phase, the cycle, and when it ends or how long is left. */
    public record Saved(Phase phase, int inCycle, int finished, boolean running, Instant endsAt, Duration left) { }

    public Saved save() { return new Saved(phase, inCycle, finished, running, endsAt, left); }

    /** Takes up a saved state; work that should be running is only resumed if {@code workRunning}. */
    public void restore(Saved saved, boolean workRunning) {
        phase = saved.phase();
        inCycle = Math.max(0, saved.inCycle());
        finished = Math.max(0, saved.finished());
        extra = Duration.ZERO;
        // A saved running work interval whose session is gone was stopped
        // elsewhere, by clocking out: it comes back paused, not running.
        running = saved.running() && saved.endsAt() != null && (phase != Phase.WORK || workRunning);
        endsAt = running ? saved.endsAt() : null;
        left = saved.left() == null || saved.left().isNegative() ? plan.length(phase) : saved.left();
    }

    /** The count of finished intervals, set back: a new day starts at none. */
    public void finished(int count) { finished = Math.max(0, count); }
}
