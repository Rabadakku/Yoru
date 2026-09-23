package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * Repeating tasks on screen (#57): the editor's Repeat field and the words it
 * uses, the ↻ on a row, Skip and Stop in its menu, the Repeating place on the
 * rail, and the dashed dates the calendar shows it coming back on.
 */
public final class RepeatUiTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static LocalDate d(String iso) { return LocalDate.parse(iso); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }
    private static Component named(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static JMenuItem item(TasksPanel board, UUID task, String name) {
        var menu = board.rowMenu(task);
        if (menu == null) return null;
        for (Component child : menu.getComponents())
            if (child instanceof JMenuItem entry && name.equals(entry.getName())) return entry;
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
        System.out.println("PASS: " + checks + " repeating task checks (the field, the words, the row, the menu, the rail, the calendar)");
    }

    private static void run() throws Exception {
        Theme.apply(ThemeId.MIDNIGHT);
        var due = d("2026-09-15");  // a Tuesday

        // The field: presets made against the due date, and a rule kept as it is until touched.
        var field = new RepeatField(null, DayOfWeek.MONDAY);
        check(field.preset() == RepeatField.Preset.NONE && field.value(due) == null, "A new task does not repeat");
        check(!field.customPanel().isVisible(), "and Custom's controls are out of the way");
        field.choose(RepeatField.Preset.WEEK);
        check(field.value(due).equals(Repeat.weekly(1, Set.of(DayOfWeek.TUESDAY), due)), "Every week is the weekday it is due on");
        field.choose(RepeatField.Preset.WEEKDAYS);
        check(field.value(due).days().size() == 5 && !field.value(due).days().contains(DayOfWeek.SATURDAY), "Every weekday is Monday to Friday");
        field.choose(RepeatField.Preset.MONTH);
        check(field.value(d("2026-01-31")).monthDay() == 31, "Every month is the day of the month it is due on");
        field.choose(RepeatField.Preset.CUSTOM);
        check(field.customPanel().isVisible(), "Custom shows its controls");
        ((JSpinner) named(field, "task.repeat.every")).setValue(3);
        ((JComboBox<?>) named(field, "task.repeat.unit")).setSelectedIndex(RepeatUnit.MONTH.ordinal());
        check(!named(field, "task.repeat.nth").isShowing() || !named(field, "task.repeat.nth").getParent().isVisible(),
            "the nth weekday waits until it is chosen");
        ((JButton) named(field, "task.repeat.month.1")).doClick();
        check(named(field, "task.repeat.nth").getParent().isVisible(), "and appears when it is");
        ((JComboBox<?>) named(field, "task.repeat.nth")).setSelectedIndex(4);
        ((JComboBox<?>) named(field, "task.repeat.weekday")).setSelectedIndex(DayOfWeek.FRIDAY.ordinal());
        ((JButton) named(field, "task.repeat.mode.1")).doClick();
        ((JButton) named(field, "task.repeat.ends.2")).doClick();
        check(named(field, "task.repeat.times").getParent().isVisible(), "After shows how many times");
        ((JSpinner) named(field, "task.repeat.times")).setValue(4);
        var custom = field.value(due);
        check(custom.equals(Repeat.monthlyOn(3, -1, DayOfWeek.FRIDAY, due).afterDone(true).ending(null, 4)),
            "Custom builds the rule its controls say: " + custom);
        var existing = Repeat.daily(2, d("2026-01-01")).ending(d("2026-12-31"), 0);
        var kept = new RepeatField(existing, DayOfWeek.MONDAY);
        check(kept.value(due) == existing, "A rule the task has comes back as it was, start and end included");
        check(kept.preset() == RepeatField.Preset.CUSTOM, "and shows as Custom when it is no preset");
        check(new RepeatField(Repeat.weekly(2, Set.of(DayOfWeek.TUESDAY), due), DayOfWeek.MONDAY).preset() == RepeatField.Preset.FORTNIGHT,
            "Every 2 weeks is recognised as its preset");

        // The words.
        check(RepeatField.describe(Repeat.daily(1, due)).equals("Every day"), "Every day");
        check(RepeatField.describe(Repeat.weekly(1, EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), due)).equals("Every weekday"), "Every weekday");
        check(RepeatField.describe(Repeat.weekly(2, EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), due)).equals("Every 2 weeks on Tue and Thu"),
            "Every 2 weeks on Tue and Thu");
        check(RepeatField.describe(Repeat.monthly(1, d("2026-01-31"))).equals("Every month on the 31st"), "Every month on the 31st");
        check(RepeatField.describe(Repeat.monthlyOn(1, 2, DayOfWeek.TUESDAY, due)).equals("Every month on the second Tuesday"),
            "Every month on the second Tuesday");
        check(RepeatField.describe(custom).equals("Every 3 months on the last Friday, from the day it is done, 4 times"),
            "and the rest of a custom rule: " + RepeatField.describe(custom));
        check(RepeatField.describe(Repeat.yearly(1, d("2028-02-29")).ending(d("2030-01-01"), 0)).equals("Every year on Feb 29, until Jan 1, 2030"),
            "a yearly rule with an end date");
        check(RepeatField.ordinal(22).equals("22nd") && RepeatField.ordinal(13).equals("13th"), "ordinals read right");

        // The board.
        var tracker = new Tracker(new Memory(), Clock.systemUTC());
        var today = LocalDate.now();
        UUID bins = UUID.randomUUID(), once = UUID.randomUUID();
        var made = Instant.now();
        tracker.addTask(new Task(bins, null, List.of(), "Take out the bins", "", today, TaskStatus.TODO, "", made, 0,
            null, List.of(), null, Repeat.weekly(1, Set.of(today.getDayOfWeek()), today), List.of()));
        tracker.addTask(new Task(once, null, null, "Post the letter", "", today, TaskStatus.TODO, "", made, 1));
        var board = new TasksPanel(tracker, () -> { }, () -> false);
        board.setSize(1280, 900);
        layout(board);
        var date = (JLabel) named(board, "task.due." + bins);
        check(date.getText().startsWith("↻ "), "A repeating task's date is marked ↻");
        check(date.getToolTipText().contains("Every week on"), "and its tooltip says how it repeats");
        check(!((JLabel) named(board, "task.due." + once)).getText().contains("↻"), "A task that does not repeat has no mark");
        check(item(board, once, "task.skip." + once) == null, "Only a repeating task can skip");
        var skip = item(board, bins, "task.skip." + bins);
        check(skip != null && skip.isEnabled(), "A repeating task's menu can skip this one");
        skip.doClick();
        var after = tracker.state().tasks().getFirst();
        check(after.due().equals(today.plusWeeks(1)) && after.history().size() == 1 && after.history().getFirst().skipped(),
            "Skipping moves it a week on and records the skip");
        ((JCheckBox) named(board, "task.done." + bins)).doClick();
        after = tracker.state().tasks().getFirst();
        check(after.status() == TaskStatus.TODO && after.due().equals(today.plusWeeks(2)) && after.history().size() == 2,
            "Ticking it off moves it on again, still to do");
        check(named(board, "tasks.place." + TaskLists.REPEATING) != null, "The rail has a Repeating place");
        ((JButton) named(board, "tasks.place." + TaskLists.REPEATING)).doClick();
        var statuses = new ArrayList<String>();
        new Object() { void walk(Container c) { for (var child : c.getComponents()) {
            if (child instanceof JButton b && b.getName() != null && b.getName().startsWith("task.status.")) statuses.add(b.getName());
            if (child instanceof Container nested) walk(nested); } } }.walk(board);
        check(statuses.equals(List.of("task.status." + bins)), "which shows the repeating tasks and no others");
        item(board, bins, "task.stopRepeat." + bins).doClick();
        check(!tracker.state().tasks().getFirst().repeats() && tracker.state().tasks().getFirst().history().size() == 2,
            "Stop repeating keeps the task and its history");

        // The calendar: later dates dashed, not something to pick up.
        var calendarTracker = new Tracker(new Memory(), Clock.systemUTC());
        var start = d("2026-09-01");
        UUID weekly = UUID.randomUUID();
        calendarTracker.addTask(new Task(weekly, null, List.of(), "Water plants", "", start, TaskStatus.TODO, "", made, 0,
            null, List.of(), null, Repeat.weekly(1, Set.of(DayOfWeek.TUESDAY), start), List.of()));
        var calendar = new TaskCalendar(calendarTracker.state(), YearMonth.of(2026, 9), d("2026-09-01"), (task, day) -> { });
        calendar.setSize(900, 620);
        calendar.relayout();
        check(calendar.taskAt(calendar.pointOn(weekly)).equals(weekly), "The real date holds the task");
        check(calendar.dateAt(calendar.pointOn(weekly)).equals(start), "on the day it is due");
        var ghost = calendar.pointOnUpcoming(weekly, d("2026-09-08"));
        check(ghost != null, "The next Tuesday shows it coming back");
        check(calendar.pointOnUpcoming(weekly, d("2026-09-09")) == null, "and a Wednesday does not");
        check(calendar.pointOnUpcoming(weekly, start) == null, "nor is its real date drawn twice");
        check(calendar.taskAt(ghost) == null, "A later date it comes back on cannot be picked up");
        var tip = calendar.getToolTipText(new java.awt.event.MouseEvent(calendar, 0, 0, 0,
            calendar.pointOn(weekly).x, calendar.pointOn(weekly).y, 0, false));
        check(tip != null && tip.contains("Water plants"), "and the real one says what it is");
        var upcoming = Repeats.between(Repeat.weekly(1, Set.of(DayOfWeek.TUESDAY), start), start.plusDays(1), d("2026-10-11"), DayOfWeek.MONDAY, 42);
        check(upcoming.size() == 5, "the month and the grid's spill hold five more Tuesdays: " + upcoming);
    }
}
