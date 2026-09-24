package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import static dev.yoru.ui.Theme.*;

/**
 * One week of a weekly repeat, on its own (#59): skipped, or held at another
 * time — or on another day — that week only.
 *
 * The rule and every other week stay exactly as they are, which is the point:
 * a lecture cancelled once, or moved to Wednesday for a week, is not a change
 * to the timetable. Changing every week stays with the weekly template.
 *
 * The grid is drawn rather than built from components, so the week's repeats
 * are also listed here under it, each with its own buttons: everything the
 * grid does with the pointer can be done from the keyboard.
 */
final class RepeatWeek {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);

    private RepeatWeek() { }

    /** One of this week's repeats as the list shows it: where it is this week, or skipped. */
    private record Entry(RecurringBlock rule, LocalDate week, LocalDate day, LocalTime start, LocalTime end,
                         boolean skipped, boolean changed) { }

    /**
     * Every repeat that falls in the week from {@code weekStart}: those that
     * follow their rule, those moved into it, and those skipped, in the order
     * the week holds them. A week moved out of the week shown is the week it
     * moved to's to list.
     */
    private static List<Entry> entries(State state, LocalDate weekStart, ZoneId zone) {
        var out = new ArrayList<Entry>();
        for (var o : Analytics.occurrences(state, weekStart, zone)) {
            var rule = rule(state, o.recurringId());
            if (rule == null) continue;
            var start = o.start().atZone(zone);
            var end = o.end().atZone(zone);
            out.add(new Entry(rule, o.week(), start.toLocalDate(), start.toLocalTime(),
                end.toLocalDate().isAfter(start.toLocalDate()) ? LocalTime.MAX : end.toLocalTime(), false, o.changed()));
        }
        for (var rule : state.recurring())
            for (var change : rule.changes())
                if (change.skipped() && !change.date().isBefore(weekStart) && change.date().isBefore(weekStart.plusDays(7)))
                    out.add(new Entry(rule, change.date(), change.date(), rule.startTime(), rule.endTime(), true, true));
        out.sort(Comparator.comparing(Entry::day).thenComparing(Entry::start));
        return out;
    }

    private static RecurringBlock rule(State state, UUID id) {
        return state.recurring().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    /** The card under the grid: this week's repeats, each with Change, Skip or Restore. */
    static JPanel card(Shell shell, LocalDate weekStart) {
        var tracker = shell.tracker();
        var entries = entries(tracker.state(), weekStart, shell.zone());
        var card = Theme.card();
        card.setName("repeatWeek.card");
        card.add(sectionHeader("EVERY WEEK · THIS WEEK"));
        gap(card, SPACE_MD);
        if (entries.isEmpty()) {
            card.add(emptyState("Nothing repeats this week.",
                "Weekly repeats from the template appear here, to skip or move one week on its own.", null));
            return card;
        }
        for (var entry : entries) {
            var line = new JPanel(new BorderLayout(SPACE_LG, 0));
            line.setOpaque(false);
            line.setBorder(entry == entries.getLast() ? listEnd() : listRow());
            String when = entry.day().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
                + CLOCK.format(entry.start()) + " – " + CLOCK.format(entry.end());
            line.add(label(when, TYPE_CAPTION, entry.skipped() ? MUTED : GOLD_TEXT), BorderLayout.WEST);
            // The name, and under it what this week is doing: two lines that
            // each give way, since a moved week says where it came from and a
            // narrow window or large text has no room for that beside the name.
            var what = stack();
            what.add(shortenable(shell.activityName(entry.rule().activityId()), TYPE_BODY, entry.skipped() ? MUTED : TEXT));
            what.add(shortenable(status(entry), TYPE_CAPTION, MUTED));
            line.add(what, BorderLayout.CENTER);
            var actions = row();
            String spoken = shell.activityName(entry.rule().activityId()) + " on " + DAY.format(entry.week());
            if (entry.changed()) {
                var restore = ghost(button("Restore", () -> shell.perform(() ->
                    tracker.restoreRepeatWeek(entry.rule().id(), entry.week()))));
                restore.setName("repeatWeek.restore." + entry.rule().id() + "." + entry.week());
                restore.getAccessibleContext().setAccessibleName("Restore " + spoken + " to every week's time");
                actions.add(restore);
            }
            if (!entry.skipped()) {
                var change = ghost(button("Change…", () -> change(shell, entry.rule().id(), entry.week())));
                change.setName("repeatWeek.change." + entry.rule().id() + "." + entry.week());
                change.getAccessibleContext().setAccessibleName("Change " + spoken + ", this week only");
                var skip = ghost(button("Skip", () -> shell.perform(() ->
                    tracker.skipRepeatWeek(entry.rule().id(), entry.week()))));
                skip.setName("repeatWeek.skip." + entry.rule().id() + "." + entry.week());
                skip.getAccessibleContext().setAccessibleName("Skip " + spoken + ", this week only");
                actions.add(change);
                actions.add(skip);
            }
            line.add(actions, BorderLayout.EAST);
            card.add(line);
        }
        return card;
    }

    /** What a row says about its week, beside the activity. */
    private static String status(Entry entry) {
        if (entry.skipped()) return "Skipped this week";
        if (!entry.changed()) return "Every " + entry.rule().dayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return "Moved from " + entry.week().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            + " " + CLOCK.format(entry.rule().startTime());
    }

    /** What the choice behind a double-click says: the rule, and where this week is held if it moved. */
    static JPanel summary(Shell shell, RecurringBlock rule, LocalDate week) {
        var change = rule.changeOn(week);
        var form = stack();
        form.add(label(shell.activityName(rule.activityId()), TYPE_HEADING, TEXT));
        gap(form, SPACE_SM);
        form.add(label("Every " + rule.dayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", "
            + CLOCK.format(rule.startTime()) + " – " + CLOCK.format(rule.endTime()), TYPE_CAPTION, MUTED));
        if (change != null && !change.skipped())
            form.add(label("The week of " + DAY.format(week) + " is held on " + DAY.format(change.movedTo()) + ", "
                + CLOCK.format(change.start()) + " – " + CLOCK.format(change.end()) + ".", TYPE_CAPTION, MUTED));
        gap(form, SPACE_MD);
        form.add(label("Change this week alone, or the rule for every week in the weekly template.", TYPE_BODY, MUTED));
        return form;
    }

    /** The buttons the choice offers: restoring only where there is a change to take away. */
    static List<String> choices(RecurringBlock rule, LocalDate week) {
        var options = new ArrayList<String>(List.of("Change this week…", "Skip this week"));
        if (rule.changeOn(week) != null) options.add("Restore this week");
        options.add("Every week…");
        options.add("Cancel");
        return options;
    }

    /**
     * The choice behind a double-click on a weekly box: this week, or every week.
     */
    static void open(Shell shell, UUID ruleId, LocalDate week) {
        var tracker = shell.tracker();
        var rule = rule(tracker.state(), ruleId);
        if (rule == null) return;
        var options = choices(rule, week);
        int picked = Dialogs.choose(shell.owner(), summary(shell, rule, week), "Weekly repeat", options.toArray(String[]::new));
        if (picked < 0) return;
        switch (options.get(picked)) {
            case "Change this week…" -> change(shell, ruleId, week);
            case "Skip this week" -> shell.perform(() -> tracker.skipRepeatWeek(ruleId, week));
            case "Restore this week" -> shell.perform(() -> tracker.restoreRepeatWeek(ruleId, week));
            case "Every week…" -> WeeklyTemplate.open(shell.owner(), tracker, shell::refresh);
            default -> { }
        }
    }

    /**
     * The form for one week's block: its day, within the owner's week that
     * holds it, and its times.
     */
    static final class ChangeForm extends Theme.VerticalPanel {
        final JComboBox<LocalDate> days = plainCombo(new JComboBox<>());
        final JTextField from;
        final JTextField to;

        ChangeForm(Shell shell, RecurringBlock rule, LocalDate week) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(0);
            setOpaque(false);
            var held = rule.changeOn(week);
            boolean moved = held != null && !held.skipped();
            var first = shell.tracker().state().settings().weekOf(week);
            for (int i = 0; i < 7; i++) days.addItem(first.plusDays(i));
            days.setSelectedItem(moved ? held.movedTo() : week);
            days.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                    var c = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                    if (value instanceof LocalDate d) c.setText(DAY.format(d));
                    c.setFont(bodyFont()); c.setBackground(sel ? LINE : PANEL); c.setForeground(TEXT);
                    return c;
                }
            });
            days.setName("repeatWeek.day");
            days.getAccessibleContext().setAccessibleName("Day this week");
            from = styleInput(new JTextField(DateText.time(moved ? held.start() : rule.startTime()), 8));
            from.setName("repeatWeek.from");
            from.getAccessibleContext().setAccessibleName("Start time this week");
            to = styleInput(new JTextField(DateText.time(moved ? held.end() : rule.endTime()), 8));
            to.setName("repeatWeek.to");
            to.getAccessibleContext().setAccessibleName("End time this week");
            add(label(shell.activityName(rule.activityId()) + " · the week of " + DAY.format(week), TYPE_HEADING, TEXT));
            gap(this, SPACE_SM);
            add(label("Every other week stays " + rule.dayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", "
                + DateText.time(rule.startTime()) + " to " + DateText.time(rule.endTime()) + ".", TYPE_CAPTION, MUTED));
            gap(this, SPACE_MD);
            var dayCaption = label("Day", TYPE_LABEL, TEXT);
            dayCaption.setLabelFor(days);
            add(dayCaption); add(days); gap(this, SPACE_MD);
            var times = tightRow();
            times.add(from); times.add(label("to", TYPE_BODY, MUTED)); times.add(to);
            add(label("Time", TYPE_LABEL, TEXT)); add(times);
            add(label("Times like 9:00 AM, 9:30p or 21:30.", TYPE_CAPTION, MUTED));
        }
    }

    /** Opens the change form, which reopens on a refusal with what was typed. */
    static void change(Shell shell, UUID ruleId, LocalDate week) {
        var tracker = shell.tracker();
        var rule = rule(tracker.state(), ruleId);
        if (rule == null) return;
        var form = new ChangeForm(shell, rule, week);
        while (Dialogs.confirm(shell.owner(), form, "Change this week", "Save")) {
            try {
                save(tracker, ruleId, week, (LocalDate) form.days.getSelectedItem(), form.from.getText(), form.to.getText());
                shell.refresh();
                return;
            } catch (Exception e) {
                Dialogs.error(shell.owner(), e.getMessage());
            }
        }
    }

    /** What the change form saves, callable without the modal dialog. */
    static void save(dev.yoru.application.Tracker tracker, UUID ruleId, LocalDate week, LocalDate day,
                     String start, String end) throws java.io.IOException {
        if (day == null) throw new IllegalArgumentException("Choose a day.");
        tracker.changeRepeatWeek(ruleId, week, day, time(start, "Start"), time(end, "End"));
    }

    private static LocalTime time(String typed, String which) {
        try { return DateText.parseTime(typed); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(which + " time: " + e.getMessage()); }
    }

    /**
     * A drag on the grid, as the day and times of one week. A box dragged to
     * the foot of the day ends a minute before midnight: a weekly block lives
     * inside one day, as the rules do.
     */
    static void moveOnGrid(Shell shell, UUID ruleId, LocalDate week, Instant start, Instant end) {
        var zone = shell.zone();
        var from = start.atZone(zone);
        var to = end.atZone(zone);
        var endTime = to.toLocalDate().isAfter(from.toLocalDate()) ? LocalTime.of(23, 59) : to.toLocalTime();
        shell.perform(() -> shell.tracker().changeRepeatWeek(ruleId, week, from.toLocalDate(),
            from.toLocalTime().withSecond(0).withNano(0), endTime.withSecond(0).withNano(0)));
    }
}
