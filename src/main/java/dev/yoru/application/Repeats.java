package dev.yoru.application;

import dev.yoru.domain.Model.Occurrence;
import dev.yoru.domain.Model.Repeat;
import dev.yoru.domain.Model.RepeatUnit;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * The dates a repeating task falls on (#57), worked out from its rule.
 *
 * Only dates: a repeat is a calendar thing, so a daylight-saving change cannot
 * move it, and "today" is decided by whoever asks, in the owner's zone. Nothing
 * here is stored; a task keeps its rule and the occurrences behind it.
 */
public final class Repeats {
    private Repeats() { }

    /** How far any search looks before deciding a rule has no more dates: centuries of the sparsest rule. */
    private static final int LIMIT = 5_000;

    /**
     * The first date after {@code after} the rule falls on, on its schedule, or
     * null when the rule has ended. {@code used} is how many occurrences have
     * come round already, the one just finished included.
     */
    public static LocalDate next(Repeat rule, LocalDate after, DayOfWeek weekStart, int used) {
        if (ended(rule, used)) return null;
        return within(rule, onOrAfterSchedule(rule, after.plusDays(1), weekStart));
    }

    /** After finishing on {@code finished}: the same interval on from that day, or null when the rule has ended. */
    public static LocalDate nextAfterDone(Repeat rule, LocalDate finished, int used) {
        if (ended(rule, used)) return null;
        var unit = switch (rule.unit()) {
            case DAY -> ChronoUnit.DAYS;
            case WEEK -> ChronoUnit.WEEKS;
            case MONTH -> ChronoUnit.MONTHS;
            case YEAR -> ChronoUnit.YEARS;
        };
        return within(rule, finished.plus(rule.every(), unit));
    }

    /**
     * Where a task goes once one occurrence is behind it: from the day it was
     * finished, or on its schedule from its due date. An occurrence missed
     * while it was overdue is not made up: the next one is today at the
     * earliest, never a date already gone.
     */
    public static LocalDate following(Repeat rule, LocalDate due, LocalDate today, DayOfWeek weekStart, int used) {
        if (rule.afterDone()) return nextAfterDone(rule, today, used);
        var from = due.isBefore(today.minusDays(1)) ? today.minusDays(1) : due;
        return next(rule, from, weekStart, used);
    }

    /** Every date the rule falls on from {@code from} to {@code to}, at most {@code limit} of them. */
    public static List<LocalDate> between(Repeat rule, LocalDate from, LocalDate to, DayOfWeek weekStart, int limit) {
        var out = new ArrayList<LocalDate>();
        var day = onOrAfterSchedule(rule, from, weekStart);
        while (day != null && !day.isAfter(to) && out.size() < limit) {
            if (rule.until() != null && day.isAfter(rule.until())) break;
            out.add(day);
            day = onOrAfterSchedule(rule, day.plusDays(1), weekStart);
        }
        return out;
    }

    /** Whether the rule falls on {@code day}, on its schedule. */
    public static boolean on(Repeat rule, LocalDate day, DayOfWeek weekStart) {
        return day.equals(onOrAfterSchedule(rule, day, weekStart));
    }

    /** How many finished in a row, most recent first; a skipped one ends the run. */
    public static int streak(List<Occurrence> history) {
        int run = 0;
        for (int i = history.size() - 1; i >= 0 && !history.get(i).skipped(); i--) run++;
        return run;
    }

    /** How many of the occurrences behind a task were finished rather than skipped. */
    public static int done(List<Occurrence> history) {
        return (int) history.stream().filter(o -> !o.skipped()).count();
    }

    private static boolean ended(Repeat rule, int used) {
        return rule.times() > 0 && used >= rule.times();
    }

    private static LocalDate within(Repeat rule, LocalDate day) {
        if (day == null) return null;
        return rule.until() != null && day.isAfter(rule.until()) ? null : day;
    }

    /** The first date on or after {@code from} (and never before the rule's start) that the rule falls on. */
    private static LocalDate onOrAfterSchedule(Repeat rule, LocalDate from, DayOfWeek weekStart) {
        var start = rule.start();
        if (from.isBefore(start)) from = start;
        return switch (rule.unit()) {
            case DAY -> {
                long gap = ChronoUnit.DAYS.between(start, from);
                long steps = (gap + rule.every() - 1) / rule.every();
                yield start.plusDays(steps * rule.every());
            }
            case WEEK -> {
                var firstWeek = start.with(TemporalAdjusters.previousOrSame(weekStart));
                for (int i = 0; i < LIMIT; i++) {
                    var day = from.plusDays(i);
                    long week = ChronoUnit.WEEKS.between(firstWeek, day.with(TemporalAdjusters.previousOrSame(weekStart)));
                    if (week % rule.every() == 0 && rule.days().contains(day.getDayOfWeek())) yield day;
                }
                yield null;
            }
            case MONTH -> {
                var first = YearMonth.from(start);
                long behind = ChronoUnit.MONTHS.between(first, YearMonth.from(from));
                long k = Math.max(0, behind / rule.every());
                for (int i = 0; i < LIMIT; i++, k++) {
                    var day = dayIn(rule, first.plusMonths(k * rule.every()));
                    if (day != null && !day.isBefore(from)) yield day;
                }
                yield null;
            }
            case YEAR -> {
                var date = MonthDay.from(start);
                long k = Math.max(0, (from.getYear() - start.getYear()) / rule.every());
                for (int i = 0; i < LIMIT; i++, k++) {
                    var day = date.atYear(start.getYear() + (int) (k * rule.every()));
                    if (!day.isBefore(from)) yield day;
                }
                yield null;
            }
        };
    }

    /** The day in a month a monthly rule falls on. */
    private static LocalDate dayIn(Repeat rule, YearMonth month) {
        if (rule.weekOfMonth() == 0) return month.atDay(Math.min(rule.monthDay(), month.lengthOfMonth()));
        var weekday = rule.days().iterator().next();
        return rule.weekOfMonth() < 0
            ? month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(weekday))
            : month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(rule.weekOfMonth(), weekday));
    }

    /** The unit, for words: "day", "week", "month", "year". */
    public static String unitName(RepeatUnit unit) {
        return switch (unit) { case DAY -> "day"; case WEEK -> "week"; case MONTH -> "month"; case YEAR -> "year"; };
    }
}
