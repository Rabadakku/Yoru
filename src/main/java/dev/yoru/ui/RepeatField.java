package dev.yoru.ui;

import dev.yoru.application.Repeats;
import dev.yoru.domain.Model.Repeat;
import dev.yoru.domain.Model.RepeatUnit;
import java.awt.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * How a task repeats (#57), in the task editor: the common rules as one
 * choice, and the rest under Custom.
 *
 * A rule is made against the task's due date when the form is saved, so
 * "every week" means the weekday the task is due on, whatever the date field
 * says by then. A rule the task already has is kept exactly as it is — its
 * start, its end — until another choice is made.
 */
final class RepeatField extends JPanel {
    /** The choices in the list, in order. */
    enum Preset {
        NONE("Does not repeat"), DAY("Every day"), WEEKDAYS("Every weekday"), WEEK("Every week"),
        FORTNIGHT("Every 2 weeks"), MONTH("Every month"), YEAR("Every year"), CUSTOM("Custom…");
        final String label;
        Preset(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final Set<DayOfWeek> WEEKDAY_SET =
        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    private final Repeat existing;
    private final DayOfWeek weekStart;
    private final JComboBox<Preset> preset = plainCombo(new JComboBox<>(Preset.values()));
    private final JPanel custom = stack();
    private final JSpinner every = plainSpinner(new JSpinner(new SpinnerNumberModel(1, 1, 999, 1)));
    private final JComboBox<String> unit = plainCombo(new JComboBox<>(new String[]{"days", "weeks", "months", "years"}));
    private final Map<DayOfWeek, JToggleButton> days = new EnumMap<>(DayOfWeek.class);
    private final JPanel weekRow;
    /** Monthly: 0 on the same day of the month, 1 on the nth weekday. */
    private final Segmented byMonth = new Segmented("task.repeat.month", 0, "Same day of the month", "A weekday of the month");
    private final JComboBox<String> nth = plainCombo(new JComboBox<>(new String[]{"first", "second", "third", "fourth", "last"}));
    private final JComboBox<String> weekday = plainCombo(new JComboBox<>(Arrays.stream(DayOfWeek.values())
        .map(d -> d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)).toArray(String[]::new)));
    private final JPanel nthRow = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, 0));
    private final JPanel monthRow;
    /** 0 on its schedule, 1 from the day it is finished. */
    private final Segmented mode = new Segmented("task.repeat.mode", 0, "On its schedule", "From the day I finish it");
    /** 0 never, 1 on a date, 2 after a number of times. */
    private final Segmented ends = new Segmented("task.repeat.ends", 0, "Never", "On a date", "After a number of times");
    private final DateField until = new DateField(null, "Last day", true);
    private final JSpinner times = plainSpinner(new JSpinner(new SpinnerNumberModel(5, 1, 10_000, 1)));
    private final JPanel untilRow = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, 0));
    private final JPanel timesRow = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, 0));
    /** Whether the owner has touched the rule: until then an existing one is kept as it is. */
    private boolean changed;

    RepeatField(Repeat existing, DayOfWeek weekStart) {
        super(new BorderLayout(0, SPACE_SM));
        setOpaque(false);
        setName("task.repeat");
        this.existing = existing;
        this.weekStart = weekStart;
        preset.setName("task.repeat.preset");
        preset.getAccessibleContext().setAccessibleName("Repeat");
        add(preset, BorderLayout.NORTH);

        every.setName("task.repeat.every");
        unit.setName("task.repeat.unit");
        var interval = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, 0));
        interval.setOpaque(false);
        interval.setAlignmentX(0);
        interval.add(inline("Every"));
        interval.add(every);
        interval.add(unit);
        custom.add(interval);
        gap(custom, SPACE_SM);

        weekRow = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_XS, 0));
        weekRow.setOpaque(false);
        weekRow.setAlignmentX(0);
        for (int i = 0; i < 7; i++) {
            var day = weekStart.plus(i);
            var toggle = new JToggleButton(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            toggle.setName("task.repeat.day." + day);
            toggle.setFont(captionFont());
            toggle.setFocusPainted(false);
            toggle.getAccessibleContext().setAccessibleName(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            toggle.addActionListener(e -> { paintDay(toggle); changed = true; });
            days.put(day, toggle);
            weekRow.add(toggle);
        }
        custom.add(weekRow);

        monthRow = stack();
        nth.setName("task.repeat.nth");
        weekday.setName("task.repeat.weekday");
        monthRow.add(byMonth);
        nthRow.setOpaque(false);
        nthRow.setAlignmentX(0);
        nthRow.add(inline("The"));
        nthRow.add(nth);
        nthRow.add(weekday);
        monthRow.add(nthRow);
        custom.add(monthRow);
        gap(custom, SPACE_SM);

        custom.add(label("Next date", TYPE_CAPTION, MUTED));
        custom.add(mode);
        gap(custom, SPACE_SM);

        custom.add(label("Ends", TYPE_CAPTION, MUTED));
        custom.add(ends);
        times.setName("task.repeat.times");
        untilRow.setOpaque(false);
        untilRow.setAlignmentX(0);
        untilRow.add(until);
        custom.add(untilRow);
        timesRow.setOpaque(false);
        timesRow.setAlignmentX(0);
        timesRow.add(times);
        timesRow.add(inline("times"));
        custom.add(timesRow);
        add(custom, BorderLayout.CENTER);

        load(existing);
        preset.addActionListener(e -> { changed = true; shown(); });
        unit.addActionListener(e -> { changed = true; shown(); });
        for (var choice : List.of(byMonth, mode, ends)) choice.onChange(i -> { changed = true; shown(); });
        every.addChangeListener(e -> changed = true);
        times.addChangeListener(e -> changed = true);
        nth.addActionListener(e -> changed = true);
        weekday.addActionListener(e -> changed = true);
        shown();
    }

    /** A word between controls, as tall as they are so it reads on their line. */
    private static JLabel inline(String text) {
        var word = label(text, TYPE_LABEL, TEXT);
        word.setPreferredSize(new Dimension(word.getPreferredSize().width, controlHeight()));
        return word;
    }

    private static void paintDay(JToggleButton toggle) {
        toggle.setBackground(toggle.isSelected() ? ACCENT_TEXT : LINE);
        toggle.setForeground(toggle.isSelected() ? (DARK ? BG : PANEL) : TEXT);
    }

    /** Sets the controls to a rule the task already has. */
    private void load(Repeat rule) {
        for (var toggle : days.values()) paintDay(toggle);
        if (rule == null) { preset.setSelectedItem(Preset.NONE); return; }
        preset.setSelectedItem(presetOf(rule));
        every.setValue(rule.every());
        unit.setSelectedIndex(rule.unit().ordinal());
        for (var day : rule.days()) { days.get(day).setSelected(rule.unit() == RepeatUnit.WEEK); paintDay(days.get(day)); }
        if (rule.weekOfMonth() != 0) {
            byMonth.choose(1);
            nth.setSelectedIndex(rule.weekOfMonth() < 0 ? 4 : rule.weekOfMonth() - 1);
            weekday.setSelectedIndex(rule.days().iterator().next().ordinal());
        }
        mode.choose(rule.afterDone() ? 1 : 0);
        if (rule.until() != null) { ends.choose(1); until.set(rule.until()); }
        else if (rule.times() > 0) { ends.choose(2); times.setValue(rule.times()); }
    }

    /** The preset a rule is, or Custom when it is none of them. */
    static Preset presetOf(Repeat rule) {
        if (rule == null) return Preset.NONE;
        if (rule.afterDone() || rule.until() != null || rule.times() > 0) return Preset.CUSTOM;
        return switch (rule.unit()) {
            case DAY -> rule.every() == 1 ? Preset.DAY : Preset.CUSTOM;
            case WEEK -> rule.days().equals(WEEKDAY_SET) && rule.every() == 1 ? Preset.WEEKDAYS
                : rule.days().size() == 1 && rule.days().contains(rule.start().getDayOfWeek())
                    ? rule.every() == 1 ? Preset.WEEK : rule.every() == 2 ? Preset.FORTNIGHT : Preset.CUSTOM
                : Preset.CUSTOM;
            case MONTH -> rule.every() == 1 && rule.weekOfMonth() == 0 && rule.monthDay() == rule.start().getDayOfMonth()
                ? Preset.MONTH : Preset.CUSTOM;
            case YEAR -> rule.every() == 1 ? Preset.YEAR : Preset.CUSTOM;
        };
    }

    /** Shows the custom rule's parts only when Custom is chosen, and only the parts its unit uses. */
    private void shown() {
        if (untilRow == null || timesRow == null || nthRow == null || custom == null) return;
        boolean isCustom = preset.getSelectedItem() == Preset.CUSTOM;
        custom.setVisible(isCustom);
        weekRow.setVisible(unit.getSelectedIndex() == RepeatUnit.WEEK.ordinal());
        monthRow.setVisible(unit.getSelectedIndex() == RepeatUnit.MONTH.ordinal());
        nthRow.setVisible(byMonth.chosen() == 1);
        untilRow.setVisible(ends.chosen() == 1);
        timesRow.setVisible(ends.chosen() == 2);
        revalidate();
        repaint();
        var window = SwingUtilities.getWindowAncestor(this);
        if (window != null) window.pack();
    }

    Preset preset() { return (Preset) preset.getSelectedItem(); }
    void choose(Preset next) { preset.setSelectedItem(next); }
    JPanel customPanel() { return custom; }

    /**
     * The rule for a task due on {@code due}, or null when it does not repeat.
     * A rule the task already had, untouched, comes back exactly as it was.
     */
    Repeat value(LocalDate due) {
        if (!changed && existing != null) return existing;
        var start = due == null ? LocalDate.now() : due;
        return switch (preset()) {
            case NONE -> null;
            case DAY -> Repeat.daily(1, start);
            case WEEKDAYS -> Repeat.weekly(1, WEEKDAY_SET, start);
            case WEEK -> Repeat.weekly(1, Set.of(start.getDayOfWeek()), start);
            case FORTNIGHT -> Repeat.weekly(2, Set.of(start.getDayOfWeek()), start);
            case MONTH -> Repeat.monthly(1, start);
            case YEAR -> Repeat.yearly(1, start);
            case CUSTOM -> custom(start);
        };
    }

    private Repeat custom(LocalDate start) {
        int n = (Integer) every.getValue();
        var kind = RepeatUnit.values()[unit.getSelectedIndex()];
        var chosenDays = EnumSet.noneOf(DayOfWeek.class);
        days.forEach((day, toggle) -> { if (toggle.isSelected()) chosenDays.add(day); });
        Repeat rule = switch (kind) {
            case DAY -> Repeat.daily(n, start);
            case WEEK -> Repeat.weekly(n, chosenDays.isEmpty() ? Set.of(start.getDayOfWeek()) : chosenDays, start);
            case MONTH -> byMonth.chosen() == 1
                ? Repeat.monthlyOn(n, nth.getSelectedIndex() == 4 ? -1 : nth.getSelectedIndex() + 1,
                    DayOfWeek.values()[weekday.getSelectedIndex()], start)
                : Repeat.monthly(n, start);
            case YEAR -> Repeat.yearly(n, start);
        };
        rule = rule.afterDone(mode.chosen() == 1);
        if (ends.chosen() == 1) {
            if (until.value() == null) throw new IllegalArgumentException("Choose the last day it repeats, or choose Never.");
            rule = rule.ending(until.value(), 0);
        } else if (ends.chosen() == 2) rule = rule.ending(null, (Integer) times.getValue());
        return rule;
    }

    // ------------------------------------------------------------------ words

    /** A rule in words: "Every 2 weeks on Tue and Thu, after it is done, until Oct 1, 2026". */
    /** {@link #describe} for the middle of a sentence: "every week on Mon", keeping the day's capital. */
    static String inSentence(Repeat rule) {
        var words = describe(rule);
        return Character.toLowerCase(words.charAt(0)) + words.substring(1);
    }

    static String describe(Repeat rule) {
        if (rule == null) return "Does not repeat";
        var out = new StringBuilder("Every ");
        String unitName = Repeats.unitName(rule.unit());
        if (rule.unit() == RepeatUnit.WEEK && rule.every() == 1 && rule.days().equals(WEEKDAY_SET)) out.append("weekday");
        else out.append(rule.every() == 1 ? unitName : rule.every() + " " + unitName + "s");
        switch (rule.unit()) {
            case WEEK -> {
                if (!(rule.every() == 1 && rule.days().equals(WEEKDAY_SET))) out.append(" on ").append(join(rule.days().stream()
                    .sorted().map(d -> d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).toList()));
            }
            case MONTH -> out.append(rule.weekOfMonth() == 0 ? " on the " + ordinal(rule.monthDay())
                : " on the " + (rule.weekOfMonth() < 0 ? "last" : new String[]{"first", "second", "third", "fourth"}[rule.weekOfMonth() - 1])
                    + " " + rule.days().iterator().next().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            case YEAR -> out.append(" on ").append(rule.start().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                .append(' ').append(rule.start().getDayOfMonth());
            case DAY -> { }
        }
        if (rule.afterDone()) out.append(", from the day it is done");
        if (rule.until() != null) out.append(", until ").append(DateText.date(rule.until()));
        if (rule.times() > 0) out.append(", ").append(plural(rule.times(), "time"));
        return out.toString();
    }

    private static String join(List<String> words) {
        if (words.size() <= 1) return String.join("", words);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
    }

    static String ordinal(int n) {
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + suffix;
    }
}
