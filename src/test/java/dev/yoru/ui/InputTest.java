package dev.yoru.ui;

import javax.swing.*;
import java.awt.Container;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Guards the data-entry path that produced
 * "Text '2026-09-09 012:29' could not be parsed at index 13" in a user report.
 *
 * Lives in dev.yoru.ui so it can reach DateTimeField.toInstant, which is
 * deliberately package-private.
 */
public final class InputTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private interface Action { void run(); }
    private static void rejects(Action action, String why) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(why);
    }

    /** Both spinner editors, date first, found by walking the component tree. */
    private static List<JFormattedTextField> editors(Container root) {
        var found = new ArrayList<JFormattedTextField>();
        collect(root, found);
        return found;
    }
    private static void collect(Container container, List<JFormattedTextField> into) {
        for (var child : container.getComponents()) {
            if (child instanceof JFormattedTextField field) into.add(field);
            else if (child instanceof Container nested) collect(nested, into);
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var zone = ZoneId.of("America/New_York");
            var base = Instant.parse("2026-05-04T17:45:00Z");   // 13:45 local, EDT

            var field = new DateTimeField(base, zone);
            check(field.value().equals(base), "untouched field returns its initial instant");

            // A start and an end are two different fields. Named alike they are
            // one control to a screen reader, and neither says which end it is.
            check(("Date and time in " + zone).equals(field.getAccessibleContext().getAccessibleName()),
                "a lone field keeps the plain name");
            check(("Start date and time in " + zone).equals(
                    new DateTimeField(base, zone, "Start").getAccessibleContext().getAccessibleName()),
                "a start field says which end it is");
            check(("End date and time in " + zone).equals(
                    new DateTimeField(base, zone, "End").getAccessibleContext().getAccessibleName()),
                "an end field says which end it is");

            var time = editors(field).get(1);

            // The exact text from the crash report. Must revert, not throw.
            time.setText("012:29");
            check(field.value().equals(base), "unparseable time reverts to the last valid value");

            time.setText("not a time at all");
            check(field.value().equals(base), "free text reverts rather than reaching the domain");

            var date = editors(field).getFirst();
            date.setText("2026-99-99");
            check(field.value().equals(base), "impossible date reverts");

            // A genuinely valid edit still takes effect.
            time.setText("10:15:00");
            check(field.value().equals(Instant.parse("2026-05-04T14:15:00Z")), "valid typed time is accepted");

            // set() moves both editors together.
            var moved = Instant.parse("2026-01-02T15:30:00Z");   // 10:30 local, EST
            field.set(moved);
            check(field.value().equals(moved), "set moves date and time together");

            // Range clamping keeps the domain's 1900-2199 bound reachable.
            check(new DateTimeField(Instant.parse("1600-01-01T00:00:00Z"), zone).value()
                    .isAfter(Instant.parse("1899-12-31T00:00:00Z")), "far past clamps into range");

            // Daylight saving: 2026 US transitions are 8 March and 1 November.
            rejects(() -> DateTimeField.toInstant(LocalDateTime.of(2026, 3, 8, 2, 30), zone),
                "a skipped clock time is refused");
            rejects(() -> DateTimeField.toInstant(LocalDateTime.of(2026, 11, 1, 1, 30), zone),
                "a repeated clock time is refused");
            check(DateTimeField.toInstant(LocalDateTime.of(2026, 3, 8, 3, 30), zone)
                    .equals(Instant.parse("2026-03-08T07:30:00Z")), "the hour after the gap resolves");

            // The vault's zone must win over the machine's. DateEditor formats the
            // initial value in its constructor, before setTimeZone can apply, so
            // the text was rendered in the JVM zone while commitEdit parsed it in
            // the vault zone: every value shifted by the offset between the two.
            // CI runs in UTC and caught it; a developer in America/New_York never
            // could, because there the two zones agree.
            var machine = TimeZone.getDefault();
            try {
                for (String elsewhere : new String[]{"UTC", "Asia/Tokyo", "Pacific/Kiritimati", "America/Los_Angeles"}) {
                    TimeZone.setDefault(TimeZone.getTimeZone(elsewhere));
                    var travelling = new DateTimeField(base, zone);
                    check(travelling.value().equals(base),
                        "the field round-trips its own zone while the machine is in " + elsewhere);
                    check(editors(travelling).get(1).getText().equals("13:45:00"),
                        "the field shows the vault's local time, not the machine's, in " + elsewhere);
                    check(editors(travelling).getFirst().getText().equals("2026-05-04"),
                        "the field shows the vault's local date, not the machine's, in " + elsewhere);
                }
            } finally {
                TimeZone.setDefault(machine);
            }

            System.out.println("PASS: " + checks + " input checks (revert-on-invalid, DST gaps and overlaps, zones, clamping)");
        });
    }
}
