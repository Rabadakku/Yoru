package dev.yoru;

import dev.yoru.application.HabitStats;
import dev.yoru.domain.Model.Habit;
import dev.yoru.domain.Model.HabitKind;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Consistency says what a streak cannot (#55).
 *
 * A streak is zero the day after a miss, however well the month went, so the
 * share of days kept is the number that answers "is this holding?". Every case
 * here is a day count, so the dates are fixed rather than taken from the clock.
 */
public final class HabitStatsTest {
    private static int checks;

    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");

    private static Habit daily(LocalDate since, LocalDate... done) {
        return new Habit(UUID.randomUUID(), "Stretch", HabitKind.DAILY, "UTC",
            new HashSet<>(Set.of(done)), List.of(), since);
    }

    public static void main(String[] args) {
        // A habit with nothing recorded has nothing to say, rather than 0%.
        var fresh = daily(TODAY);
        check(HabitStats.lastDays(fresh, TODAY, 30).of() == 0, "a habit that began today counts no finished days");
        check(HabitStats.lastDays(fresh, TODAY, 30).toString().equals("not started"), "and says so");

        // Today is not a day you missed until it is over: it counts only once done.
        var yesterdayOnly = daily(TODAY.minusDays(1), TODAY.minusDays(1));
        var kept = HabitStats.lastDays(yesterdayOnly, TODAY, 30);
        check(kept.done() == 1 && kept.of() == 1, "an unchecked today is not counted against you, got " + kept);
        var withToday = daily(TODAY.minusDays(1), TODAY.minusDays(1), TODAY);
        check(HabitStats.lastDays(withToday, TODAY, 30).of() == 2, "a checked today counts, got "
            + HabitStats.lastDays(withToday, TODAY, 30));

        // Twenty-six of the last thirty days: the streak is 1, the consistency 87%.
        var days = new HashSet<LocalDate>();
        for (int i = 1; i <= 30; i++) days.add(TODAY.minusDays(i));
        days.remove(TODAY.minusDays(2));
        days.remove(TODAY.minusDays(9));
        days.remove(TODAY.minusDays(17));
        days.remove(TODAY.minusDays(23));
        var month = new Habit(UUID.randomUUID(), "Read", HabitKind.DAILY, "UTC", days, List.of(), TODAY.minusDays(40));
        var last30 = HabitStats.lastDays(month, TODAY, 30);
        check(last30.done() == 26 && last30.of() == 30, "the last thirty days are counted whole, got " + last30);
        check(last30.percent() == 87, "twenty-six of thirty reads as 87%, got " + last30.percent());
        check(month.streak(TODAY) == 1, "while the streak is only yesterday's, got " + month.streak(TODAY));

        // The window never reaches back before the habit began.
        var young = daily(TODAY.minusDays(3), TODAY.minusDays(3), TODAY.minusDays(2), TODAY.minusDays(1));
        check(HabitStats.lastDays(young, TODAY, 30).of() == 3, "a habit three days old is judged on three days, got "
            + HabitStats.lastDays(young, TODAY, 30));
        check(HabitStats.lastDays(young, TODAY, 30).percent() == 100, "and can be perfect");

        // The week runs from the day the vault says it does.
        var monday = HabitStats.thisWeek(month, TODAY, DayOfWeek.MONDAY);
        var sunday = HabitStats.thisWeek(month, TODAY, DayOfWeek.SUNDAY);
        check(monday.of() == 1 && sunday.of() == 2,
            "a Tuesday is one day into a Monday week and two into a Sunday one, got " + monday + " and " + sunday);

        // The best run is the longest anywhere in the history, not the current one.
        check(HabitStats.longestStreak(month) == 7, "the longest run is seven days, got " + HabitStats.longestStreak(month));
        check(HabitStats.longestStreak(fresh) == 0, "a habit with no days has no run");

        // Everything since the start, however long ago that was.
        var all = HabitStats.sinceTheStart(month, TODAY);
        check(all.done() == 26 && all.of() == 40, "since the start counts every day since, got " + all);

        // Across a month's end: the window is days, not calendar months.
        var march = LocalDate.parse("2026-03-10");
        var overFebruary = daily(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-27"), LocalDate.parse("2026-02-28"),
            LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-02"));
        var february = HabitStats.lastDays(overFebruary, march, 14);
        check(february.of() == 14 && february.done() == 4,
            "fourteen days back from the 10th of March cross February's end and count its last days, got " + february);

        // Across the night the clocks change: still one day each, in the habit's own zone.
        var springForward = new Habit(UUID.randomUUID(), "Stretch", HabitKind.DAILY, "America/New_York",
            new HashSet<>(Set.of(LocalDate.parse("2026-03-07"), LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-09"))),
            List.of(), LocalDate.parse("2026-03-01"));
        var dst = HabitStats.lastDays(springForward, LocalDate.parse("2026-03-10"), 30);
        check(dst.of() == 9 && dst.done() == 3, "the day the clocks go forward is one day like any other, got " + dst);
        check(springForward.streak(LocalDate.parse("2026-03-09")) == 3, "and a streak runs straight through it");

        System.out.println("PASS: " + checks + " habit consistency checks (windows, an unfinished today, week starts, best run, month ends, daylight saving)");
    }
}
