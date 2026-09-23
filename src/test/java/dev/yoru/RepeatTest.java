package dev.yoru;

import dev.yoru.application.Repeats;
import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.time.*;
import java.util.*;

/**
 * The dates a repeating task falls on (#57), and what finishing or skipping
 * one does to it. Every date is fixed, so none of this depends on the day the
 * suite runs.
 */
public final class RepeatTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static void rejects(Runnable action, String why) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(why);
    }
    private static LocalDate d(String iso) { return LocalDate.parse(iso); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        var monday = DayOfWeek.MONDAY;
        var sunday = DayOfWeek.SUNDAY;

        // Every N days.
        var everyThree = Repeat.daily(3, d("2026-09-01"));
        check(Repeats.next(everyThree, d("2026-09-01"), monday, 1).equals(d("2026-09-04")), "every 3 days from the 1st is the 4th");
        check(Repeats.next(everyThree, d("2026-09-05"), monday, 1).equals(d("2026-09-07")), "and keeps its own rhythm after a later day");
        check(Repeats.next(everyThree, d("2026-08-01"), monday, 1).equals(d("2026-09-01")), "never before it starts");

        // Weekly on chosen days, every week and every other week.
        var tueThu = Repeat.weekly(1, EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), d("2026-09-01"));
        check(Repeats.next(tueThu, d("2026-09-01"), monday, 1).equals(d("2026-09-03")), "Tuesday's next is Thursday");
        check(Repeats.next(tueThu, d("2026-09-03"), monday, 1).equals(d("2026-09-08")), "Thursday's next is the next Tuesday");
        var fortnight = Repeat.weekly(2, EnumSet.of(DayOfWeek.TUESDAY), d("2026-09-01"));
        check(Repeats.next(fortnight, d("2026-09-01"), monday, 1).equals(d("2026-09-15")), "every other Tuesday skips a week");
        check(Repeats.next(fortnight, d("2026-09-15"), monday, 1).equals(d("2026-09-29")), "and keeps skipping");
        check(!Repeats.on(fortnight, d("2026-09-08"), monday), "the week between is not on it");
        // The owner's week start decides which days share a fortnight.
        var sundays = Repeat.weekly(2, EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), d("2026-09-06"));
        check(Repeats.next(sundays, d("2026-09-06"), sunday, 1).equals(d("2026-09-07")),
            "weeks starting Sunday: the 6th and the 7th share a week, so the 7th is next");
        check(Repeats.next(sundays, d("2026-09-06"), monday, 1).equals(d("2026-09-14")),
            "weeks starting Monday: the 7th begins the week off, so the next is the 14th");
        check(Repeats.next(sundays, d("2026-09-07"), sunday, 1).equals(d("2026-09-20")),
            "Sunday-first, the fortnight after is the 20th");
        check(Repeats.next(sundays, d("2026-09-14"), monday, 1).equals(d("2026-09-20")),
            "Monday-first, the 14th and the 20th share a week that is on");

        // Monthly on the 31st lands on the last day of a shorter month, and comes back to the 31st.
        var monthEnd = Repeat.monthly(1, d("2026-01-31"));
        check(Repeats.next(monthEnd, d("2026-01-31"), monday, 1).equals(d("2026-02-28")), "the 31st in February is the 28th");
        check(Repeats.next(monthEnd, d("2026-02-28"), monday, 1).equals(d("2026-03-31")), "and March has its 31st back");
        check(Repeats.next(monthEnd, d("2026-03-31"), monday, 1).equals(d("2026-04-30")), "April ends on the 30th");
        check(Repeats.next(Repeat.monthly(1, d("2028-01-31")), d("2028-01-31"), monday, 1).equals(d("2028-02-29")),
            "a leap February has its 29th");
        var quarterly = Repeat.monthly(3, d("2026-01-15"));
        check(Repeats.next(quarterly, d("2026-01-15"), monday, 1).equals(d("2026-04-15")), "every 3 months");
        check(Repeats.next(quarterly, d("2026-05-01"), monday, 1).equals(d("2026-07-15")), "keeps its quarters after a later day");

        // The nth weekday, and the last one.
        var secondTuesday = Repeat.monthlyOn(1, 2, DayOfWeek.TUESDAY, d("2026-09-08"));
        check(Repeats.next(secondTuesday, d("2026-09-08"), monday, 1).equals(d("2026-10-13")), "the second Tuesday of October is the 13th");
        check(Repeats.next(secondTuesday, d("2026-10-13"), monday, 1).equals(d("2026-11-10")), "of November the 10th");
        var lastFriday = Repeat.monthlyOn(1, -1, DayOfWeek.FRIDAY, d("2026-09-25"));
        check(Repeats.next(lastFriday, d("2026-09-25"), monday, 1).equals(d("2026-10-30")), "the last Friday of October is the 30th");
        check(Repeats.next(lastFriday, d("2026-10-30"), monday, 1).equals(d("2026-11-27")), "of November the 27th");

        // Yearly, with the 29th of February.
        var leapDay = Repeat.yearly(1, d("2028-02-29"));
        check(Repeats.next(leapDay, d("2028-02-29"), monday, 1).equals(d("2029-02-28")), "the 29th of February is the 28th in 2029");
        check(Repeats.next(leapDay, d("2031-03-01"), monday, 1).equals(d("2032-02-29")), "and the 29th again in 2032");
        check(Repeats.next(Repeat.yearly(2, d("2026-06-01")), d("2026-06-01"), monday, 1).equals(d("2028-06-01")), "every 2 years");

        // Ending: on a date, or after so many times.
        var tenDays = Repeat.daily(1, d("2026-09-01")).ending(d("2026-09-10"), 0);
        check(Repeats.next(tenDays, d("2026-09-09"), monday, 1).equals(d("2026-09-10")), "the last day it runs is still on");
        check(Repeats.next(tenDays, d("2026-09-10"), monday, 1) == null, "and after it there is nothing");
        var threeTimes = Repeat.daily(1, d("2026-09-01")).ending(null, 3);
        check(Repeats.next(threeTimes, d("2026-09-02"), monday, 2).equals(d("2026-09-03")), "after the second time, the third comes round");
        check(Repeats.next(threeTimes, d("2026-09-03"), monday, 3) == null, "and after three there is no fourth");
        rejects(() -> Repeat.daily(1, d("2026-09-10")).ending(d("2026-09-01"), 0), "a repeat cannot end before it starts");
        rejects(() -> Repeat.daily(0, d("2026-09-10")), "every 0 days is not a repeat");
        rejects(() -> new Repeat(RepeatUnit.MONTH, 1, Set.of(), 32, 0, d("2026-09-10"), false, null, 0), "there is no 32nd");

        // After completion: from the day it was finished, not the day it was due.
        var water = Repeat.daily(3, d("2026-09-01")).afterDone(true);
        check(Repeats.nextAfterDone(water, d("2026-09-05"), 1).equals(d("2026-09-08")), "watered on the 5th, next on the 8th");
        check(Repeats.following(water, d("2026-09-01"), d("2026-09-05"), monday, 1).equals(d("2026-09-08")),
            "whatever day it was due");
        var monthlyAfter = Repeat.monthly(1, d("2026-01-31")).afterDone(true);
        check(Repeats.nextAfterDone(monthlyAfter, d("2026-01-31"), 1).equals(d("2026-02-28")), "a month after the 31st of January");

        // An overdue task does not make up the days it missed.
        check(Repeats.following(Repeat.daily(1, d("2026-09-01")), d("2026-09-20"), d("2026-09-23"), monday, 1).equals(d("2026-09-23")),
            "a daily task done three days late is next due today");
        check(Repeats.following(tueThu, d("2026-09-01"), d("2026-09-23"), monday, 1).equals(d("2026-09-24")),
            "a weekly one, on its next day from today");

        // Upcoming dates for the calendar.
        var upcoming = Repeats.between(tueThu, d("2026-09-01"), d("2026-09-14"), monday, 10);
        check(upcoming.equals(List.of(d("2026-09-01"), d("2026-09-03"), d("2026-09-08"), d("2026-09-10"))),
            "the dates in a fortnight: " + upcoming);
        check(Repeats.between(tueThu, d("2026-09-01"), d("2026-12-31"), monday, 3).size() == 3, "at most as many as asked");

        // The tracker: finishing moves the task on and keeps the occurrence behind it.
        var memory = new Memory();
        var zone = ZoneId.of("America/New_York");
        // 01:30 on the 1st of November in New York is inside the hour the clocks go back.
        var clock = Clock.fixed(Instant.parse("2026-11-01T05:30:00Z"), ZoneOffset.UTC);
        var tracker = new Tracker(memory, clock);
        var id = UUID.randomUUID();
        var made = Instant.parse("2026-10-01T12:00:00Z");
        tracker.addTask(new Task(id, null, List.of(), "Take out the bins", "", d("2026-11-01"), TaskStatus.TODO, "", made, 0,
            null, List.of(), null, Repeat.weekly(1, EnumSet.of(DayOfWeek.SUNDAY), d("2026-11-01")), List.of()));
        tracker.taskStatus(id, TaskStatus.DONE, zone);
        var bins = tracker.state().tasks().getFirst();
        check(bins.status() == TaskStatus.TODO && bins.due().equals(d("2026-11-08")),
            "finishing it on the night the clocks change moves it to next Sunday: " + bins.due());
        check(bins.history().equals(List.of(new Occurrence(d("2026-11-01"), clock.instant(), false))),
            "and keeps the occurrence it finished");
        tracker.skipOccurrence(id, zone);
        bins = tracker.state().tasks().getFirst();
        check(bins.due().equals(d("2026-11-15")) && bins.history().getLast().skipped(), "skipping moves it on, marked skipped");
        check(Repeats.streak(bins.history()) == 0 && Repeats.done(bins.history()) == 1, "a skip ends the streak, and is not done");
        tracker.updateTask(bins.withRepeat(bins.repeat().ending(null, 3)));
        tracker.taskStatus(id, TaskStatus.DONE, zone);
        bins = tracker.state().tasks().getFirst();
        check(bins.status() == TaskStatus.DONE && bins.history().size() == 3, "on its third time it is done for good");
        try { tracker.skipOccurrence(id, zone); throw new AssertionError("a finished repeat has nothing to skip"); }
        catch (IllegalArgumentException expected) { checks++; }
        tracker.taskStatus(id, TaskStatus.TODO, zone);
        check(tracker.state().tasks().getFirst().status() == TaskStatus.TODO, "reopening it is an ordinary status change");
        var plain = UUID.randomUUID();
        tracker.addTask(new Task(plain, null, null, "Once", "", null, TaskStatus.TODO, "", made, 1));
        tracker.taskStatus(plain, TaskStatus.DONE, zone);
        check(tracker.state().tasks().getLast().status() == TaskStatus.DONE && tracker.state().tasks().getLast().history().isEmpty(),
            "a task that does not repeat is simply done");
        try { new Task(UUID.randomUUID(), null, List.of(), "No date", "", null, TaskStatus.TODO, "", made, 0,
                null, List.of(), null, Repeat.daily(1, d("2026-09-01")), List.of());
            throw new AssertionError("a repeating task without a date was accepted"); }
        catch (IllegalArgumentException expected) { checks++; }

        System.out.println("PASS: " + checks + " repeat checks (days, weeks, month ends, nth weekdays, leap years, week start, after completion, endings, daylight saving)");
    }
}
