package dev.yoru.ui;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How Yoru writes dates and times, and a forgiving reading of what people type.
 *
 * Written the way Notion writes them, "Sep 14, 2026" and "11:00 AM", because the
 * text in a field is the example of its own format: an ISO date in a spinner told
 * nobody what else it would take (#24). Reading accepts the forms people actually
 * type ("9/14", "sep 14", "tomorrow", "930p", "21:30") and refuses anything else
 * with a message that names a form it would take, so a typo is never quietly read
 * as some other day.
 *
 * Numeric dates are month first. Yoru's text is English throughout, and a date
 * that silently swapped day and month would be worse than one refused.
 */
final class DateText {
    private DateText() { }

    static final String DATE_EXAMPLE = "Sep 14, 2026";
    static final String TIME_EXAMPLE = "9:30 AM";
    /** The domain's range; a date outside it is refused rather than clamped where it is typed. */
    static final LocalDate FIRST = LocalDate.of(1900, 1, 1), LAST = LocalDate.of(2199, 12, 31);

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    /** "Sep 14, 2026". */
    static String date(LocalDate date) { return SHORT.format(date); }
    /** "September 14, 2026", for a column with room to say it in full. */
    static String longDate(LocalDate date) { return LONG.format(date); }
    /** "11:00 AM". Seconds are not written; a field that shows this keeps them unless the text is changed. */
    static String time(LocalTime time) { return CLOCK.format(time); }

    private static final String[] MONTHS = {"january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"};

    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern NUMERIC = Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2}|\\d{4}))?");
    private static final Pattern MONTH_FIRST = Pattern.compile("([a-z]+) (\\d{1,2})(?: (\\d{4}))?");
    private static final Pattern DAY_FIRST = Pattern.compile("(\\d{1,2}) ([a-z]+)(?: (\\d{4}))?");

    /**
     * Reads a typed date. {@code today} anchors "today", "tomorrow", "yesterday"
     * and a date typed without a year, which means this year.
     *
     * @throws IllegalArgumentException naming the text and a form Yoru would take
     */
    static LocalDate parseDate(String typed, LocalDate today) {
        String text = typed.strip().toLowerCase(Locale.ROOT)
            .replace(',', ' ').replaceAll("(\\d+)(st|nd|rd|th)\\b", "$1")
            .replaceAll("([a-z])\\.", "$1").replaceAll("\\s+", " ").strip();
        switch (text) {
            case "today", "now" -> { return today; }
            case "tomorrow", "tmrw", "tmr" -> { return today.plusDays(1); }
            case "yesterday" -> { return today.minusDays(1); }
            default -> { }
        }
        LocalDate read = null;
        Matcher m;
        try {
            if ((m = ISO.matcher(text)).matches())
                read = LocalDate.of(number(m, 1), number(m, 2), number(m, 3));
            else if ((m = NUMERIC.matcher(text)).matches())
                read = LocalDate.of(year(m.group(3), today), number(m, 1), number(m, 2));
            else if ((m = MONTH_FIRST.matcher(text)).matches() && month(m.group(1)) > 0)
                read = LocalDate.of(year(m.group(3), today), month(m.group(1)), number(m, 2));
            else if ((m = DAY_FIRST.matcher(text)).matches() && month(m.group(2)) > 0)
                read = LocalDate.of(year(m.group(3), today), month(m.group(2)), number(m, 1));
        } catch (DateTimeException impossible) {
            throw new IllegalArgumentException("“" + typed.strip() + "” is not a day on the calendar. Type a date like " + DATE_EXAMPLE + ".");
        }
        if (read == null)
            throw new IllegalArgumentException(typed.isBlank() ? "Type a date, like " + DATE_EXAMPLE + "."
                : "Yoru can't read “" + typed.strip() + "” as a date. Type it like " + DATE_EXAMPLE + " or 9/14.");
        if (read.isBefore(FIRST) || read.isAfter(LAST))
            throw new IllegalArgumentException("Choose a date between " + FIRST.getYear() + " and " + LAST.getYear() + ".");
        return read;
    }

    private static final Pattern CLOCK_TEXT = Pattern.compile("(\\d{1,2})(?::(\\d{2})(?::(\\d{2}))?)?(am|pm|a|p)?");
    private static final Pattern RUN = Pattern.compile("(\\d{3,4})(am|pm|a|p)?");

    /**
     * Reads a typed clock time: "9:30 AM", "9:30p", "930pm", "21:30", "9", "noon".
     *
     * @throws IllegalArgumentException naming the text and a form Yoru would take
     */
    static LocalTime parseTime(String typed) {
        String text = typed.strip().toLowerCase(Locale.ROOT).replace(".", "").replaceAll("\\s+", "");
        if (text.equals("noon")) return LocalTime.NOON;
        if (text.equals("midnight")) return LocalTime.MIDNIGHT;
        int hour, minute = 0, second = 0;
        String half;
        Matcher m;
        if ((m = RUN.matcher(text)).matches()) {
            String digits = m.group(1);
            hour = Integer.parseInt(digits.substring(0, digits.length() - 2));
            minute = Integer.parseInt(digits.substring(digits.length() - 2));
            half = m.group(2);
        } else if ((m = CLOCK_TEXT.matcher(text)).matches()) {
            hour = number(m, 1);
            if (m.group(2) != null) minute = number(m, 2);
            if (m.group(3) != null) second = number(m, 3);
            half = m.group(4);
        } else {
            throw unreadableTime(typed);
        }
        if (half != null) {
            if (hour < 1 || hour > 12) throw unreadableTime(typed);
            hour = hour % 12 + (half.startsWith("p") ? 12 : 0);
        }
        if (hour > 23 || minute > 59 || second > 59) throw unreadableTime(typed);
        return LocalTime.of(hour, minute, second);
    }

    private static IllegalArgumentException unreadableTime(String typed) {
        return new IllegalArgumentException(typed.isBlank() ? "Type a time, like " + TIME_EXAMPLE + "."
            : "Yoru can't read “" + typed.strip() + "” as a time. Type it like " + TIME_EXAMPLE + " or 21:30.");
    }

    private static int number(Matcher m, int group) { return Integer.parseInt(m.group(group)); }

    private static int year(String typed, LocalDate today) {
        if (typed == null) return today.getYear();
        int year = Integer.parseInt(typed);
        return typed.length() == 2 ? 2000 + year : year;
    }

    /** A month by name or by any prefix of three letters or more, so "sep" and "sept" both work. */
    private static int month(String word) {
        if (word.length() < 3) return 0;
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].startsWith(word)) return i + 1;
        return 0;
    }
}
