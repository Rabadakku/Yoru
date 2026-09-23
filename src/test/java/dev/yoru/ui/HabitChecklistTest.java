package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.time.*;
import java.util.*;

/**
 * Today's daily habits, ticked off beside the tasks (#54).
 *
 * The list is driven through its own boxes: a tick has to reach the vault for
 * the right day, and the right day is the habit's own, so a habit kept in
 * Auckland turns over at Auckland's midnight whatever the clock says in UTC.
 */
public final class HabitChecklistTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    private static JCheckBox box(Container root, UUID habit) {
        for (Component child : root.getComponents()) {
            if (child instanceof JCheckBox b && ("habit.today." + habit).equals(b.getName())) return b;
            if (child instanceof Container nested) { var found = box(nested, habit); if (found != null) return found; }
        }
        return null;
    }

    private static boolean says(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel l && text.equals(l.getText())) return true;
            if (child instanceof Container nested && says(nested, text)) return true;
        }
        return false;
    }

    private static Habit habit(Tracker tracker, UUID id) {
        return tracker.state().habits().stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow();
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
        System.out.println("PASS: " + checks + " habit checklist checks (tick, untick, the day turning over, the zone, the keys)");
    }

    private static void run() throws Exception {
        Theme.apply(ThemeId.MIDNIGHT);
        var tracker = new Tracker(new Memory(), Clock.systemUTC());
        var auckland = ZoneId.of("Pacific/Auckland");
        var angeles = ZoneId.of("America/Los_Angeles");
        tracker.addHabit("Stretch", HabitKind.DAILY, auckland, null);
        tracker.addHabit("Read", HabitKind.DAILY, angeles, null);
        tracker.addHabit("Since coffee", HabitKind.TIME_SINCE, angeles, Instant.parse("2026-09-01T08:00:00Z"));
        var stretch = tracker.state().habits().get(0).id();
        var read = tracker.state().habits().get(1).id();
        var coffee = tracker.state().habits().get(2).id();

        // 23:30 on the 23rd in Auckland, 04:30 on the 23rd in Los Angeles.
        var evening = Clock.fixed(Instant.parse("2026-09-23T11:30:00Z"), ZoneOffset.UTC);
        var refreshed = new int[1];
        var card = HabitChecklist.card(tracker, () -> refreshed[0]++, e -> { throw new AssertionError(e); }, evening);
        check(box(card, stretch) != null && box(card, read) != null, "every daily habit has a box");
        check(box(card, coffee) == null, "a time-since tracker is not something you tick off");
        check(says(card, "0 of 2 done"), "the card says how many are done");
        check(box(card, stretch).getAccessibleContext().getAccessibleName().equals("Stretch, not done today"),
            "each box says its habit and today's state to a screen reader");

        box(card, stretch).doClick();
        check(habit(tracker, stretch).checkIns().equals(Set.of(LocalDate.of(2026, 9, 23))),
            "a tick records today in the habit's own zone");
        check(refreshed[0] == 1, "and the page is redrawn once");
        card = HabitChecklist.card(tracker, () -> refreshed[0]++, e -> { throw new AssertionError(e); }, evening);
        check(box(card, stretch).isSelected(), "the tick shows once the page is redrawn");
        check(says(card, "1 of 2 done"), "and the count follows it");
        check(box(card, stretch).getAccessibleContext().getAccessibleName().equals("Stretch, done today"),
            "a ticked box says it is done");

        box(card, stretch).doClick();
        check(habit(tracker, stretch).checkIns().isEmpty(), "ticking it again takes today off");
        box(card, stretch).doClick();
        box(card, read).doClick();
        check(habit(tracker, read).checkIns().equals(Set.of(LocalDate.of(2026, 9, 23))),
            "a habit kept in Los Angeles is ticked for its own today");

        // An hour later it is past midnight in Auckland and not in Los Angeles.
        var later = Clock.fixed(Instant.parse("2026-09-23T12:30:00Z"), ZoneOffset.UTC);
        card = HabitChecklist.card(tracker, () -> { }, e -> { throw new AssertionError(e); }, later);
        check(!box(card, stretch).isSelected(), "Auckland's habit comes back unticked on its new day");
        check(box(card, read).isSelected(), "while Los Angeles's is still ticked for the day it is still in");
        check(says(card, "1 of 2 done"), "and the count is today's in each habit's zone");
        box(card, stretch).doClick();
        check(habit(tracker, stretch).checkIns().contains(LocalDate.of(2026, 9, 24)),
            "the new tick lands on Auckland's new day");
        check(habit(tracker, stretch).checkIns().contains(LocalDate.of(2026, 9, 23)),
            "and yesterday's stays where it was");

        // The keys: Up and Down move between boxes, Space is the box's own.
        card = HabitChecklist.card(tracker, () -> { }, e -> { throw new AssertionError(e); }, later);
        var first = box(card, stretch);
        var second = box(card, read);
        var down = KeyStroke.getKeyStroke("DOWN");
        var up = KeyStroke.getKeyStroke("UP");
        check(first.getInputMap(JComponent.WHEN_FOCUSED).get(down) != null
            && first.getActionMap().get(first.getInputMap(JComponent.WHEN_FOCUSED).get(down)).isEnabled(),
            "Down moves on from the first box");
        check(!first.getActionMap().get(first.getInputMap(JComponent.WHEN_FOCUSED).get(up)).isEnabled(),
            "Up has nowhere to go from the first box");
        check(!second.getActionMap().get(second.getInputMap(JComponent.WHEN_FOCUSED).get(down)).isEnabled(),
            "Down has nowhere to go from the last box");
        check("pressed".equals(second.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("pressed SPACE"))),
            "Space still ticks the box that has focus");

        // With no daily habits there is nothing to tick and the card says so.
        var empty = new Tracker(new Memory(), Clock.systemUTC());
        var none = HabitChecklist.card(empty, () -> { }, e -> { }, later);
        var anyBox = new boolean[1];
        new Object() { void walk(Container c) { for (var child : c.getComponents()) {
            if (child instanceof JCheckBox) anyBox[0] = true;
            if (child instanceof Container nested) walk(nested); } } }.walk(none);
        check(!anyBox[0] && none.getComponentCount() > 0, "with no daily habits the card has nothing to tick, and says so");
    }
}
