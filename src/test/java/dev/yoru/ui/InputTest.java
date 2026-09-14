package dev.yoru.ui;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Guards the data-entry path that produced
 * "Text '2026-09-09 012:29' could not be parsed at index 13" in a user report,
 * and the Notion-style pickers that replaced the spinners behind it (#24).
 *
 * Lives in dev.yoru.ui so it can reach DateTimeField.toInstant, DateField and
 * CalendarPanel, which are deliberately package-private.
 */
public final class InputTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private interface Action { void run(); }
    private static String rejects(Action action, String why) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return expected.getMessage(); }
        throw new AssertionError(why);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return (T) child;
            if (child instanceof Container nested) { T found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static List<JButton> buttons(Container root, String prefix) {
        var found = new ArrayList<JButton>();
        for (var child : root.getComponents()) {
            if (child instanceof JButton b && b.getName() != null && b.getName().startsWith(prefix)) found.add(b);
            if (child instanceof Container nested) found.addAll(buttons(nested, prefix));
        }
        return found;
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            dateTime();
            dateOnly();
            calendar();
        });
        System.out.println("PASS: " + checks + " input checks (Notion-style fields, refusals, seconds kept, DST, zones, clamping, calendar)");
    }

    private static void dateTime() {
        var zone = ZoneId.of("America/New_York");
        var base = Instant.parse("2026-05-04T17:45:00Z");   // 13:45 local, EDT

        var field = new DateTimeField(base, zone);
        check(field.value().equals(base), "an untouched field returns its initial instant");
        JTextField date = named(field, "datetime.date"), time = named(field, "datetime.time");
        JLabel note = named(field, "datetime.note");
        check(date.getText().equals("May 4, 2026") && time.getText().equals("1:45 PM"),
            "the field shows its value the way Notion writes it: " + date.getText() + " | " + time.getText());
        check(note.getText().contains("9/14") && note.getText().contains("9:30 PM"), "and an example of what else it reads");
        check(date.getPreferredSize().height > date.getInsets().top + date.getInsets().bottom,
            "the text has room to show: the old spinner's padding left a field with no height for its text");
        check(date.getPreferredSize().width >= date.getFontMetrics(date.getFont()).stringWidth("Sep 30, 2026"),
            "a date field is wide enough for any date it writes");

        // A start and an end are two different fields. Named alike they are
        // one control to a screen reader, and neither says which end it is.
        check(("Date and time in " + zone).equals(field.getAccessibleContext().getAccessibleName()),
            "a lone field keeps the plain name");
        var start = new DateTimeField(base, zone, "Start");
        check(("Start date and time in " + zone).equals(start.getAccessibleContext().getAccessibleName()),
            "a start field says which end it is");
        check(("End date and time in " + zone).equals(
                new DateTimeField(base, zone, "End").getAccessibleContext().getAccessibleName()),
            "an end field says which end it is");
        check("Start time".equals(((JTextField) named(start, "datetime.time")).getAccessibleContext().getAccessibleName()),
            "and each half says which end and which half it is");
        check(Dialogs.firstInput(start) == named(start, "datetime.date"), "a dialog opens with the keyboard on the date");

        // The exact text from the crash report is refused, never read and never swapped for another time.
        time.setText("012:29");
        String message = rejects(field::value, "unparseable time is refused");
        check(message.startsWith("Time: ") && message.contains("012:29"), "the refusal names the part and the text: " + message);
        check(note.getForeground().equals(Theme.DANGER) && note.getText().contains("time"), "and the caption turns red and says so");
        time.setText("not a time at all");
        rejects(field::value, "free text is refused rather than reaching the domain");
        time.setText("1:45 PM");
        check(field.value().equals(base), "restoring the text restores the value");
        check(!note.getForeground().equals(Theme.DANGER), "and clears the warning");
        date.setText("2026-99-99");
        check(rejects(field::value, "an impossible date is refused").startsWith("Date: "), "naming the date as the problem");
        date.setText("May 4, 2026");

        // Forgiving input.
        time.setText("10:15");
        check(field.value().equals(Instant.parse("2026-05-04T14:15:00Z")), "a typed 24-hour time is accepted");
        time.setText("2:05p");
        date.setText("5/6");
        check(field.value().equals(Instant.parse("2026-05-06T18:05:00Z")), "and so are 9/14 and 9:30p forms, this year by default: " + field.value());
        time.transferFocus();
        field.set(Instant.parse("2026-05-06T18:05:00Z"));
        check(time.getText().equals("2:05 PM") && date.getText().equals("May 6, 2026"), "readable text is rewritten the way Yoru writes it");

        // Seconds a session recorded survive an edit that did not touch the time.
        var precise = Instant.parse("2026-05-04T17:45:17Z");
        var kept = new DateTimeField(precise, zone, "Start");
        check(kept.value().equals(precise), "an untouched field keeps seconds it does not show");
        ((JTextField) named(kept, "datetime.date")).setText("May 5, 2026");
        check(kept.value().equals(Instant.parse("2026-05-05T17:45:17Z")), "changing only the date keeps the time to the second");

        // An instant inside the repeated hour opens and saves untouched.
        var repeated = Instant.parse("2026-11-01T05:30:00Z");   // 1:30 EDT, the first of two
        check(new DateTimeField(repeated, zone).value().equals(repeated), "an untouched field in a repeated hour is not refused");

        // set() moves both halves together.
        var moved = Instant.parse("2026-01-02T15:30:00Z");   // 10:30 local, EST
        field.set(moved);
        check(field.value().equals(moved), "set moves date and time together");

        // Range clamping keeps the domain's 1900-2199 bound reachable.
        check(new DateTimeField(Instant.parse("1600-01-01T00:00:00Z"), zone).value()
                .isAfter(Instant.parse("1899-12-31T00:00:00Z")), "far past clamps into range");

        // Daylight saving: 2026 US transitions are 8 March and 1 November.
        rejects(() -> DateTimeField.toInstant(LocalDateTime.of(2026, 3, 8, 2, 30), zone), "a skipped clock time is refused");
        rejects(() -> DateTimeField.toInstant(LocalDateTime.of(2026, 11, 1, 1, 30), zone), "a repeated clock time is refused");
        check(DateTimeField.toInstant(LocalDateTime.of(2026, 3, 8, 3, 30), zone)
                .equals(Instant.parse("2026-03-08T07:30:00Z")), "the hour after the gap resolves");
        var gap = new DateTimeField(Instant.parse("2026-03-08T12:00:00Z"), zone);
        ((JTextField) named(gap, "datetime.time")).setText("2:30 AM");
        rejects(gap::value, "typing a skipped clock time is refused too");

        // The vault's zone must win over the machine's. CI runs in UTC and caught
        // a version that rendered in the JVM zone and parsed in the vault's.
        var machine = TimeZone.getDefault();
        try {
            for (String elsewhere : new String[]{"UTC", "Asia/Tokyo", "Pacific/Kiritimati", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(elsewhere));
                var travelling = new DateTimeField(base, zone);
                check(travelling.value().equals(base), "the field round-trips its own zone while the machine is in " + elsewhere);
                check(((JTextField) named(travelling, "datetime.time")).getText().equals("1:45 PM"),
                    "the field shows the vault's local time, not the machine's, in " + elsewhere);
                check(((JTextField) named(travelling, "datetime.date")).getText().equals("May 4, 2026"),
                    "the field shows the vault's local date, not the machine's, in " + elsewhere);
                ((JTextField) named(travelling, "datetime.time")).setText("9:00 AM");
                check(travelling.value().equals(Instant.parse("2026-05-04T13:00:00Z")),
                    "a typed time is read in the vault's zone while the machine is in " + elsewhere);
            }
        } finally {
            TimeZone.setDefault(machine);
        }
    }

    private static void dateOnly() {
        var today = LocalDate.now();
        var due = new DateField(null, "Due", true);
        JTextField text = named(due, "date.text");
        check(text.getText().isEmpty() && due.value() == null, "an optional date starts blank and blank means none");
        check(Dialogs.firstInput(due) == text, "the date field is where a dialog puts the keyboard");
        text.setText("tomorrow");
        check(today.plusDays(1).equals(due.value()), "an optional date reads forgiving text");
        text.setText("the thirty-second");
        String message = rejects(due::value, "an unreadable date is refused");
        check(message.startsWith("Due: "), "starting with the field's role, so a form with two dates says which: " + message);
        check(((JLabel) named(due, "date.note")).getForeground().equals(Theme.DANGER), "and its caption turns red");
        text.setText("   ");
        check(due.value() == null, "clearing the text clears the date");

        var set = LocalDate.of(2026, 9, 14);
        var plan = new DateField(set, "Plan for", false);
        check(set.equals(plan.value()), "an untouched date returns what it was given");
        check(((JTextField) named(plan, "date.text")).getText().equals("Sep 14, 2026"), "written the Notion way");
        ((JTextField) named(plan, "date.text")).setText("");
        check(rejects(plan::value, "a required date refuses a blank").startsWith("Plan for: "), "saying which date is missing");
        plan.set(LocalDate.of(2027, 1, 3));
        check(((JTextField) named(plan, "date.text")).getText().equals("Jan 3, 2027") && plan.value().equals(LocalDate.of(2027, 1, 3)),
            "set shows and returns the new date");
    }

    private static void calendar() {
        var locale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);   // weeks start on Sunday
            var picked = new ArrayList<LocalDate>();
            var cleared = new boolean[1];
            var selected = LocalDate.of(2026, 9, 14);
            var today = LocalDate.of(2026, 9, 20);
            var month = new CalendarPanel(selected, today, picked::add, () -> cleared[0] = true);

            var days = buttons(month, "calendar.day.");
            check(days.size() == 42, "a month is always six weeks, so the popup never resizes: " + days.size());
            check(days.getFirst().getName().equals("calendar.day.2026-08-30"), "September 2026 starts on the Sunday before the 1st");
            check(days.stream().noneMatch(JButton::isFocusable), "calendar days never take the keyboard from the field");
            JButton chosen = named(month, "calendar.day.2026-09-14");
            check(chosen.getAccessibleContext().getAccessibleName().equals("September 14, 2026, selected"), "the chosen day is spoken as chosen");
            check(chosen.getFont().isBold(), "and marked by more than colour");
            check(((JButton) named(month, "calendar.day.2026-09-20")).getAccessibleContext().getAccessibleName().endsWith("today"),
                "today is spoken as today");

            chosen.doClick();
            ((JButton) named(month, "calendar.day.2026-09-03")).doClick();
            check(picked.equals(List.of(selected, LocalDate.of(2026, 9, 3))), "clicking a day picks it");
            ((JButton) named(month, "calendar.next")).doClick();
            check(month.month().equals(YearMonth.of(2026, 10)), "next steps a month");
            check(named(month, "calendar.day.2026-10-31") != null, "and shows that month's days");
            ((JButton) named(month, "calendar.previous")).doClick();
            ((JButton) named(month, "calendar.previous")).doClick();
            check(month.month().equals(YearMonth.of(2026, 8)), "previous steps back");
            ((JButton) named(month, "calendar.today")).doClick();
            check(picked.getLast().equals(today), "Today picks today whatever month is on show");
            ((JButton) named(month, "calendar.clear")).doClick();
            check(cleared[0], "Clear clears an optional date");
            check(named(new CalendarPanel(selected, today, day -> { }, null), "calendar.clear") == null,
                "a required date offers no Clear");

            Locale.setDefault(Locale.UK);   // weeks start on Monday
            check(buttons(new CalendarPanel(selected, today, day -> { }, null), "calendar.day.").getFirst().getName()
                .equals("calendar.day.2026-08-31"), "the week starts where the machine's locale starts it");
        } finally {
            Locale.setDefault(locale);
        }
    }
}
