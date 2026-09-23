package dev.yoru.ui;

import dev.yoru.application.Pomodoro;
import dev.yoru.application.Pomodoro.Cue;
import dev.yoru.application.Pomodoro.Phase;
import dev.yoru.application.Pomodoro.Plan;
import dev.yoru.application.Tracker;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * The window's pomodoro (#61): the timing, what it records, and what it says
 * when an interval ends.
 *
 * Owned by the window rather than by Today, and ticked with it, so an interval
 * ends on time whichever page is open. Work goes to the tracker as a session
 * of the chosen activity, begun and ended like clocked time; clocking out by
 * hand pauses it. Its settings, the day's count and where it had got to are
 * this computer's, kept in preferences, so a restart carries on.
 */
final class PomodoroClock implements Pomodoro.Work {
    private static final Preferences PREFERENCES = Preferences.userRoot().node("dev/yoru/desktop");
    private static final String MODE = "today.mode", PREFIX = "pomodoro.";

    private final Supplier<Tracker> tracker;
    private final Clock clock;
    private final Pomodoro engine;
    private final Runnable changed;
    private UUID activity;
    /** Set while the clock pauses itself because the session was already stopped elsewhere. */
    private boolean alreadyStopped;
    private String notice;
    private Instant noticeAt;
    private Cue lastCue;
    private int cuesPlayed;

    PomodoroClock(Supplier<Tracker> tracker, Clock clock, Runnable changed) {
        this.tracker = tracker;
        this.clock = clock;
        this.changed = changed;
        this.engine = new Pomodoro(plan(), this);
        restore();
    }

    // ---------------------------------------------------------------- settings

    /** Whether Today shows the pomodoro rather than the open-ended timer. */
    static boolean shown() { return "pomodoro".equals(PREFERENCES.get(MODE, "timer")); }
    static void shown(boolean pomodoro) { PREFERENCES.put(MODE, pomodoro ? "pomodoro" : "timer"); }

    static Plan plan() {
        try {
            return new Plan(PREFERENCES.getInt(PREFIX + "work", 25), PREFERENCES.getInt(PREFIX + "short", 5),
                PREFERENCES.getInt(PREFIX + "long", 15), PREFERENCES.getInt(PREFIX + "every", 4),
                PREFERENCES.getBoolean(PREFIX + "autostart", false));
        } catch (IllegalArgumentException unreadable) {
            return Plan.defaults();
        }
    }

    /** Keeps a new plan, and hands it to the clock for the next phase. */
    void plan(Plan next) {
        PREFERENCES.putInt(PREFIX + "work", next.workMinutes());
        PREFERENCES.putInt(PREFIX + "short", next.shortMinutes());
        PREFERENCES.putInt(PREFIX + "long", next.longMinutes());
        PREFERENCES.putInt(PREFIX + "every", next.longEvery());
        PREFERENCES.putBoolean(PREFIX + "autostart", next.autoStart());
        engine.plan(next);
        save();
    }

    // ------------------------------------------------------------------ reading

    Pomodoro engine() { return engine; }
    Phase phase() { return engine.phase(); }
    boolean running() { return engine.running(); }
    Duration left() { return engine.left(clock.instant()); }
    double progress() { return engine.progress(clock.instant()); }
    UUID activity() { return activity; }

    /** Pomodoros finished today, on this computer. */
    int today() {
        return LocalDate.now(clock.withZone(ZoneId.systemDefault())).toString().equals(PREFERENCES.get(PREFIX + "day", ""))
            ? PREFERENCES.getInt(PREFIX + "count", 0) : 0;
    }

    /** What the last interval's end said, for ten minutes after it. */
    String notice() {
        return notice != null && Duration.between(noticeAt, clock.instant()).compareTo(Duration.ofMinutes(10)) < 0 ? notice : null;
    }

    /** The last cue asked to play, and how many have been: for the tests. */
    Cue lastCue() { return lastCue; }
    int cuesPlayed() { return cuesPlayed; }

    /** What the phase is called: "Work · 2 of 4", "Short break", "Long break". */
    String phaseName() {
        return switch (engine.phase()) {
            case WORK -> "Work · " + engine.round() + " of " + engine.plan().longEvery();
            case SHORT_BREAK -> "Short break";
            case LONG_BREAK -> "Long break";
        };
    }

    // ------------------------------------------------------------------ acting

    void activity(UUID next) {
        activity = next;
        save();
    }

    void start() throws IOException {
        var state = tracker.get().state();
        if (activity == null || state.activities().stream().noneMatch(a -> a.id().equals(activity)))
            activity = state.activities().isEmpty() ? null : state.activities().getFirst().id();
        if (engine.phase() == Phase.WORK && activity == null && tracker.get().active() == null)
            throw new IllegalArgumentException("Add an activity to record work under.");
        engine.start(clock.instant());
        save();
    }

    // The page that pressed these rebuilds itself; only the ticker's changes need telling.
    void pause() throws IOException { engine.pause(clock.instant()); save(); }
    void skip() throws IOException { engine.skip(clock.instant()); save(); }
    void extend() { engine.extend(Duration.ofMinutes(5)); save(); }
    void reset() throws IOException { engine.reset(clock.instant()); notice = null; save(); }

    /**
     * One step of the window's ticker. A session stopped by hand pauses the
     * clock; an interval that has run out moves it on, plays its cue once and
     * asks for the owner's attention.
     */
    void tick() throws IOException {
        var now = clock.instant();
        if (engine.running() && engine.phase() == Phase.WORK && tracker.get().active() == null) {
            alreadyStopped = true;
            try { engine.pause(now); } finally { alreadyStopped = false; }
            save();
            changed.run();
            return;
        }
        var was = engine.phase();
        var tick = engine.tick(now);
        if (!tick.ended()) return;
        if (was == Phase.WORK) count();
        notice = switch (was) {
            case WORK -> "Work done. Time for a " + (engine.phase() == Phase.LONG_BREAK ? "long " : "")
                + engine.plan().length(engine.phase()).toMinutes() + "-minute break.";
            case SHORT_BREAK, LONG_BREAK -> "Break over. Back to work when you are ready.";
        };
        noticeAt = now;
        if (tick.cue() != null) {
            lastCue = tick.cue();
            if (Cues.play(tick.cue())) cuesPlayed++;
            attention();
        }
        save();
        changed.run();
    }

    private void count() {
        var today = LocalDate.now(clock.withZone(ZoneId.systemDefault())).toString();
        int count = today.equals(PREFERENCES.get(PREFIX + "day", "")) ? PREFERENCES.getInt(PREFIX + "count", 0) : 0;
        PREFERENCES.put(PREFIX + "day", today);
        PREFERENCES.putInt(PREFIX + "count", count + 1);
    }

    /** The dock or taskbar asks to be looked at, where the platform can. */
    private static void attention() {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !java.awt.Taskbar.isTaskbarSupported()) return;
            var taskbar = java.awt.Taskbar.getTaskbar();
            if (taskbar.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)) taskbar.requestUserAttention(true, false);
        } catch (RuntimeException unsupported) {
            // The window's own notice is enough.
        }
    }

    // ------------------------------------------------------------- recording

    @Override public void begin() throws IOException {
        var t = tracker.get();
        // Already clocked in: the work interval is that session.
        if (t.active() != null) return;
        t.start(activity);
    }

    @Override public void end(Instant at) throws IOException {
        var t = tracker.get();
        if (alreadyStopped || t.active() == null) return;
        var now = clock.instant();
        t.stop(at.isAfter(now) ? now : at);
    }

    // ------------------------------------------------------------- persistence

    private void save() {
        var saved = engine.save();
        PREFERENCES.put(PREFIX + "phase", saved.phase().name());
        PREFERENCES.putInt(PREFIX + "cycle", saved.inCycle());
        PREFERENCES.putBoolean(PREFIX + "running", saved.running());
        PREFERENCES.putLong(PREFIX + "ends", saved.endsAt() == null ? 0 : saved.endsAt().toEpochMilli());
        PREFERENCES.putLong(PREFIX + "left", saved.left().toMillis());
        PREFERENCES.put(PREFIX + "activity", activity == null ? "" : activity.toString());
    }

    /**
     * Picks up where the last run left off. A work interval comes back running
     * only if the session it was recording is still the one running, under
     * the same activity; otherwise it comes back paused.
     */
    private void restore() {
        try {
            String saved = PREFERENCES.get(PREFIX + "activity", "");
            activity = saved.isEmpty() ? null : UUID.fromString(saved);
            var phase = Phase.valueOf(PREFERENCES.get(PREFIX + "phase", Phase.WORK.name()));
            long ends = PREFERENCES.getLong(PREFIX + "ends", 0);
            long left = PREFERENCES.getLong(PREFIX + "left", engine.plan().length(phase).toMillis());
            var active = tracker.get().active();
            boolean same = active != null && active.activityId().equals(activity);
            engine.restore(new Pomodoro.Saved(phase, PREFERENCES.getInt(PREFIX + "cycle", 0), 0,
                PREFERENCES.getBoolean(PREFIX + "running", false), ends == 0 ? null : Instant.ofEpochMilli(ends),
                Duration.ofMillis(left)), same);
        } catch (IllegalArgumentException unreadable) {
            activity = null;
        }
    }
}
