package dev.yoru.ui;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The date and time text every picker writes and reads (#24).
 *
 * Expected values are written out by hand rather than derived from the
 * formatter under test, and every date of a leap year and every minute of a day
 * must read back as exactly what was written, so a field can never change a
 * value somebody only looked at.
 */
public final class DateTextTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    private static void date(String typed, LocalDate expected) {
        var read = DateText.parseDate(typed, TODAY);
        check(read.equals(expected), "“" + typed + "” reads as " + expected + ", got " + read);
    }
    private static void time(String typed, LocalTime expected) {
        var read = DateText.parseTime(typed);
        check(read.equals(expected), "“" + typed + "” reads as " + expected + ", got " + read);
    }
    private static void refusesDate(String typed) {
        try { DateText.parseDate(typed, TODAY); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(DateText.DATE_EXAMPLE) || expected.getMessage().contains("between"),
                "the refusal of “" + typed + "” says what to type instead: " + expected.getMessage());
            return;
        }
        throw new AssertionError("“" + typed + "” should not read as a date");
    }
    private static void refusesTime(String typed) {
        try { DateText.parseTime(typed); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(DateText.TIME_EXAMPLE), "the refusal of “" + typed + "” says what to type instead");
            return;
        }
        throw new AssertionError("“" + typed + "” should not read as a time");
    }

    public static void main(String[] args) {
        check(DateText.date(LocalDate.of(2026, 9, 14)).equals("Sep 14, 2026"), "a date is written the Notion way");
        check(DateText.date(LocalDate.of(2027, 1, 3)).equals("Jan 3, 2027"), "without a leading zero");
        check(DateText.longDate(LocalDate.of(2026, 9, 14)).equals("September 14, 2026"), "and in full for a column");
        check(DateText.span(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 26)).equals("Sep 20 – 26, 2026"), "a week in one month");
        check(DateText.span(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)).equals("Sep 28 – Oct 4, 2026"), "a week across two months");
        check(DateText.span(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)).equals("Dec 28, 2026 – Jan 3, 2027"), "a week across two years");
        check(DateText.time(LocalTime.of(11, 0)).equals("11:00 AM"), "a morning time");
        check(DateText.time(LocalTime.of(0, 5)).equals("12:05 AM"), "just after midnight");
        check(DateText.time(LocalTime.of(12, 30)).equals("12:30 PM"), "just after noon");
        check(DateText.time(LocalTime.of(21, 45, 17)).equals("9:45 PM"), "seconds are not written");
        check(DateText.DATE_EXAMPLE.equals(DateText.date(LocalDate.of(2026, 9, 14))), "the date example is a real formatted date");
        check(DateText.parseTime(DateText.TIME_EXAMPLE).equals(LocalTime.of(9, 30)), "and the time example reads back");

        var sept14 = LocalDate.of(2026, 9, 14);
        date("Sep 14, 2026", sept14);
        date("sep 14 2026", sept14);
        date("September 14, 2026", sept14);
        date("Sept. 14th, 2026", sept14);
        date("14 Sep 2026", sept14);
        date("2026-09-14", sept14);
        date("2026-9-14", sept14);
        date("9/14/2026", sept14);
        date("09/14/26", sept14);
        date("9-14-2026", sept14);
        date("9.14.2026", sept14);
        date("  9/14  ", sept14);
        date("sep 14", sept14);
        date("today", sept14);
        date("Tomorrow", LocalDate.of(2026, 9, 15));
        date("yesterday", LocalDate.of(2026, 9, 13));
        date("mar 1", LocalDate.of(2026, 3, 1));
        date("may 31", LocalDate.of(2026, 5, 31));
        date("Feb 29 2028", LocalDate.of(2028, 2, 29));
        date("1/1/1900", LocalDate.of(1900, 1, 1));
        date("dec 31 2199", LocalDate.of(2199, 12, 31));

        refusesDate("");
        refusesDate("   ");
        refusesDate("someday");
        refusesDate("14/9/2026");      // month first: there is no month 14
        refusesDate("Feb 29 2027");
        refusesDate("Sep 31");
        refusesDate("ju 4");           // two letters could be June or July
        refusesDate("2026-13-01");
        refusesDate("12/31/1899");
        refusesDate("jan 1 2200");
        refusesDate("9/14/202");

        time("9:30 AM", LocalTime.of(9, 30));
        time("9:30am", LocalTime.of(9, 30));
        time("9:30 p.m.", LocalTime.of(21, 30));
        time("9:30p", LocalTime.of(21, 30));
        time("930pm", LocalTime.of(21, 30));
        time("0930", LocalTime.of(9, 30));
        time("2130", LocalTime.of(21, 30));
        time("21:30", LocalTime.of(21, 30));
        time("13:45:00", LocalTime.of(13, 45));
        time("13:45:17", LocalTime.of(13, 45, 17));
        time("9", LocalTime.of(9, 0));
        time("9pm", LocalTime.of(21, 0));
        time("12am", LocalTime.of(0, 0));
        time("12 PM", LocalTime.of(12, 0));
        time("0:00", LocalTime.MIDNIGHT);
        time("noon", LocalTime.NOON);
        time("Midnight", LocalTime.MIDNIGHT);

        refusesTime("");
        refusesTime("012:29");         // the text from the original crash report
        refusesTime("not a time at all");
        refusesTime("24:00");
        refusesTime("9:60");
        refusesTime("13pm");
        refusesTime("0am");
        refusesTime("12345");
        refusesTime("9:3");

        // Everything written reads back as exactly what was written.
        for (var day = LocalDate.of(2028, 1, 1); day.getYear() == 2028; day = day.plusDays(1)) {
            check(DateText.parseDate(DateText.date(day), TODAY).equals(day), "a written date reads back: " + day);
            check(DateText.parseDate(DateText.longDate(day), TODAY).equals(day), "a date written in full reads back: " + day);
        }
        for (int minute = 0; minute < 24 * 60; minute++) {
            var clock = LocalTime.of(minute / 60, minute % 60);
            check(DateText.parseTime(DateText.time(clock)).equals(clock), "a written time reads back: " + clock);
        }

        System.out.println("PASS: " + checks + " date text checks (Notion format, forgiving reading, refusals, round trips)");
    }
}
