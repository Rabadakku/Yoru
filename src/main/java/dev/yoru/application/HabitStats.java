package dev.yoru.application;

import dev.yoru.domain.Model.Habit;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * How a daily habit is really going (#55).
 *
 * A streak is the only number a habit showed, and one missed day sets it to
 * zero — which says nothing about the twenty-six days out of thirty that were
 * kept. Consistency is that share, and it is the number that answers "is this
 * holding?".
 *
 * Every measure counts from the day the habit began and stops at yesterday
 * plus today: a day that has not finished yet is not a day you missed, so
 * today counts only once it is checked off.
 */
public final class HabitStats {
    private HabitStats() { }

    /** How many of the last {@code days} days that could have been kept were. */
    public static Kept lastDays(Habit habit, LocalDate today, int days) {
        var last = lastDayThatCounts(habit, today);
        return between(habit, last.minusDays(days - 1L), last);
    }

    /** The week containing {@code today}, under the owner's week start. */
    public static Kept thisWeek(Habit habit, LocalDate today, DayOfWeek weekStartsOn) {
        return between(habit, today.with(TemporalAdjusters.previousOrSame(weekStartsOn)),
            lastDayThatCounts(habit, today));
    }

    /** Everything since the habit began. */
    public static Kept sinceTheStart(Habit habit, LocalDate today) {
        return between(habit, habit.since(), lastDayThatCounts(habit, today));
    }

    /**
     * The last day that counts either way: today once it is checked off, and
     * yesterday until then.
     *
     * A day still in progress is not a day you missed, and counting it would
     * make every morning look like a lapse.
     */
    private static LocalDate lastDayThatCounts(Habit habit, LocalDate today) {
        return habit.checkIns().contains(today) ? today : today.minusDays(1);
    }

    /** The days kept in [from, to], never reaching back before the habit began. */
    private static Kept between(Habit habit, LocalDate from, LocalDate to) {
        if (from.isBefore(habit.since())) from = habit.since();
        if (to.isBefore(from)) return new Kept(0, 0);
        int of = (int) (to.toEpochDay() - from.toEpochDay() + 1);
        int done = 0;
        for (var day = from; !day.isAfter(to); day = day.plusDays(1)) if (habit.checkIns().contains(day)) done++;
        return new Kept(done, of);
    }

    /** The longest run of days ever checked off, whenever it was. */
    public static int longestStreak(Habit habit) {
        var days = new java.util.TreeSet<>(habit.checkIns());
        int longest = 0, run = 0;
        LocalDate previous = null;
        for (var day : days) {
            run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = day;
        }
        return longest;
    }

    /** Today, in the habit's own zone. */
    public static LocalDate today(Habit habit) { return LocalDate.now(ZoneId.of(habit.zone())); }

    /** Days kept out of days that counted, and the share as a percentage. */
    public record Kept(int done, int of) {
        public int percent() { return of == 0 ? 0 : Math.round(done * 100f / of); }
        /** "26 of 30 days" — or nothing at all, when the habit is too new to say. */
        @Override public String toString() { return of == 0 ? "not started" : done + " of " + of + " days"; }
    }
}
