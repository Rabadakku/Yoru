package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.application.Pomodoro.Cue;
import dev.yoru.application.Pomodoro.Phase;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * The window's pomodoro (#61): what it records through the tracker, the cue
 * it plays once and never again, what it does when the owner clocks out by
 * hand or the computer sleeps, what a restart finds, the sounds themselves,
 * and Today's controls in every theme.
 */
public final class PomodoroClockTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }
    /** A clock that moves only when told. */
    private static final class Hands extends Clock {
        Instant now = Instant.parse("2026-09-23T13:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { var other = this; return new Clock() {
            public ZoneId getZone() { return zone; }
            public Clock withZone(ZoneId z) { return other.withZone(z); }
            public Instant instant() { return other.now; } }; }
        public Instant instant() { return now; }
        void add(long minutes) { now = now.plus(Duration.ofMinutes(minutes)); }
    }
    private static Component named(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { run(); } catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            var cause = wrapped.getCause();
            if (cause instanceof RuntimeException r && r.getCause() instanceof Exception inner) throw inner;
            if (cause instanceof Error error) throw error;
            throw wrapped;
        }
        System.out.println("PASS: " + checks + " pomodoro clock checks (recording, cues once, mute, clock-out, sleep, restart, sounds, Today, Settings)");
        System.exit(0);
    }

    private static void run() throws Exception {
        // Heard here rather than through the speakers of whoever runs the suite.
        var heard = new ArrayList<byte[]>();
        Cues.out = heard::add;
        // ---- the sounds
        var workEnd = Cues.pcm(Cue.WORK_DONE, Cues.Voice.BELL, 100);
        var breakEnd = Cues.pcm(Cue.BREAK_DONE, Cues.Voice.BELL, 100);
        check(!Arrays.equals(workEnd, breakEnd), "The end of work and the end of a break sound different");
        check(Cues.notes(Cue.WORK_DONE)[0] > Cues.notes(Cue.WORK_DONE)[1] && Cues.notes(Cue.BREAK_DONE)[0] < Cues.notes(Cue.BREAK_DONE)[1],
            "work's cue falls and a break's rises");
        var voices = new HashSet<String>();
        for (var voice : Cues.Voice.values()) voices.add(Arrays.toString(Cues.pcm(Cue.WORK_DONE, voice, 80)));
        check(voices.size() == 3, "each voice is its own sound");
        int loud = Cues.peak(workEnd), half = Cues.peak(Cues.pcm(Cue.WORK_DONE, Cues.Voice.BELL, 50));
        check(Math.abs(half * 2 - loud) < loud / 50 + 2, "half the volume is half as loud: " + half + " of " + loud);
        check(Cues.peak(Cues.pcm(Cue.WORK_DONE, Cues.Voice.SOFT, 0)) == 0, "no volume is silence");
        check(workEnd.length / 2 / Cues.RATE < 2, "a cue is short");

        // ---- the clock
        var hands = new Hands();
        var tracker = new Tracker(new Memory(), hands);
        tracker.addActivity("Study", 0);
        var study = tracker.state().activities().getFirst().id();
        int[] changes = {0};
        Cues.on(true);
        Cues.volume(40);
        PomodoroClock.shown(true);
        var clock = new PomodoroClock(() -> tracker, hands, () -> changes[0]++);
        clock.plan(Pomodoro.Plan.defaults());
        clock.reset();
        clock.activity(study);
        clock.start();
        check(tracker.active() != null && tracker.active().activityId().equals(study), "Starting work clocks in to the chosen activity");
        var began = hands.now;
        hands.add(25);
        clock.tick();
        check(tracker.active() == null, "At the end of work it clocks out");
        var session = tracker.state().sessions().getLast();
        check(session.start().equals(began) && session.end().equals(began.plus(Duration.ofMinutes(25))), "a 25-minute session, to the minute");
        check(clock.today() == 1, "and counts a pomodoro today");
        check(clock.lastCue() == Cue.WORK_DONE && clock.cuesPlayed() == 1, "The end of work plays its cue");
        check(heard.size() == 1 && Arrays.equals(heard.getFirst(), Cues.pcm(Cue.WORK_DONE, Cues.voice(), 40)),
            "in the voice and at the volume set");
        check(clock.notice() != null && clock.notice().contains("5-minute break"), "and says so on the page: " + clock.notice());
        check(changes[0] == 1, "and the page is told once");
        clock.tick();
        check(clock.cuesPlayed() == 1 && changes[0] == 1, "Another tick plays nothing again");
        check(clock.phase() == Phase.SHORT_BREAK && !clock.running(), "The break waits to be started");
        clock.start();
        check(tracker.active() == null, "A break records nothing");
        hands.add(5);
        clock.tick();
        check(clock.lastCue() == Cue.BREAK_DONE && clock.cuesPlayed() == 2, "Its end plays the other cue");
        check(tracker.state().sessions().size() == 1, "and still only the work was recorded");

        // Muted: the page still says it.
        Cues.on(false);
        clock.start();
        hands.add(25);
        clock.tick();
        check(clock.cuesPlayed() == 2 && heard.size() == 2 && clock.notice().contains("break"), "Muted, nothing plays but the page still says it");
        check(tracker.state().sessions().size() == 2 && clock.today() == 2, "and the work is recorded all the same");
        Cues.on(true);
        clock.skip();

        // Clocking out by hand pauses the pomodoro, and nothing is stopped twice.
        clock.start();
        hands.add(10);
        tracker.stop(hands.now);
        clock.tick();
        check(!clock.running() && clock.left().equals(Duration.ofMinutes(15)), "Clocking out by hand pauses it with the time left");
        check(tracker.state().sessions().size() == 3, "and the session is the one clocked out, once");

        // A restart picks up a running interval.
        clock.start();
        hands.add(5);
        var restarted = new PomodoroClock(() -> tracker, hands, () -> { });
        check(restarted.running() && restarted.left().equals(Duration.ofMinutes(10)) && restarted.phase() == Phase.WORK,
            "A restart carries on the interval, since its session is still running");
        clock = restarted;

        // Asleep through the end: the session ends where it should, and nothing plays.
        int played = clock.cuesPlayed();
        hands.add(180);
        clock.tick();
        check(tracker.active() == null && tracker.state().sessions().getLast().end().equals(hands.now.minus(Duration.ofMinutes(170))),
            "Waking late ends the work at its proper time");
        check(clock.cuesPlayed() == played, "without a sound for an interval long gone");
        check(!clock.running(), "and the break waits");
        clock.reset();

        // ---- Today, in every theme and at the largest text
        for (var theme : ThemeId.values()) for (int size : new int[]{100, 200}) {
            TextSize.use(size);
            Theme.apply(theme);
            PomodoroClock.shown(true);
            var app = Preview.trackerApp(theme, size == 200 ? 1600 : 1280, 900);
            Preview.button(app, "Today").doClick();
            Preview.layout(app);
            var left = (JLabel) named(app, "today.pomodoro.left");
            check(left != null && left.getText().matches("\\d\\d:\\d\\d"), theme + " at " + size + "%: the time left is shown");
            var controls = (Container) named(app, "today.timer.controls");
            for (var control : controls.getComponents())
                check(control.getX() + control.getWidth() <= controls.getWidth() && control.getY() + control.getHeight() <= controls.getHeight(),
                    theme + " at " + size + "%: " + ((JButton) control).getText() + " is inside its row");
            check(named(app, "today.pomodoro.go") instanceof JButton go && go.getText().contains("Start"), "It offers Start");
            var mode = (Container) named(app, "today.mode");
            check(mode != null, "and the switch back to the timer");
            var settings = new SettingsPage(app);
            check(named(settings.view(), "pomodoro.save") != null, "Settings has the pomodoro's section");
        }
        TextSize.use(100);
        PomodoroClock.shown(false);
    }
}
