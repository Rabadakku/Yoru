package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.time.*;
import java.util.*;

/**
 * An invented vault for the README's pictures (ReadmeMedia): a year of study,
 * a week of plans, tasks, habits and Anki sittings, and a timer running now.
 *
 * No game save, so no page names a Pokémon or draws game art; every value is
 * made up here, from fixed seeds, so a rerun draws the same shapes.
 */
final class ShowcaseVault {
    static Repository memory() {
        return new Repository() {
            State state = State.empty();
            public State load() { return state; }
            public void save(State next) { state = next; }
            public void close() { }
        };
    }

    static Tracker tracker(Repository memory, ThemeId theme) throws Exception { return tracker(memory, theme, true); }

    /** Today's Anki answers, from a little over three hours ago. */
    static List<AnkiTime.Review> todaysAnki(Instant now) {
        var random = new Random(11);
        var out = new ArrayList<AnkiTime.Review>();
        long t = now.minus(Duration.ofMinutes(205)).toEpochMilli();
        for (int i = 0; i < 120; i++) { long took = 5_000 + random.nextInt(9_000); t += took + 1_200; out.add(new AnkiTime.Review(t, took)); }
        return out;
    }
    /** The past week's Anki answers, one sitting each evening. */
    static List<AnkiTime.Review> weekOfAnki(LocalDate today, ZoneId zone) {
        var random = new Random(5);
        var out = new ArrayList<AnkiTime.Review>();
        for (int day = 6; day >= 1; day--) {
            long t = today.minusDays(day).atTime(21, 0).atZone(zone).toInstant().toEpochMilli();
            int cards = 70 + random.nextInt(80);
            for (int i = 0; i < cards; i++) { long took = 5_000 + random.nextInt(9_000); t += took + 1_500; out.add(new AnkiTime.Review(t, took)); }
        }
        return out;
    }

    static Tracker tracker(Repository memory, ThemeId theme, boolean todayAnki) throws Exception {
        var tracker = new Tracker(memory, Clock.systemUTC());
        for (String name : new String[]{"Study", "Coding", "Japanese", "Reading"}) tracker.addActivity(name, 0);
        var a = tracker.state().activities();
        var zone = ZoneId.systemDefault();
        var today = LocalDate.now(zone);
        var random = new Random(7);
        // Most of a year, building up: lighter at first, steadier lately, with gaps.
        for (int day = 330; day >= 1; day--) {
            var date = today.minusDays(day);
            int skip = day > 200 ? 45 : day > 90 ? 22 : 10;
            if (random.nextInt(100) < skip) continue;
            int budget = (day > 200 ? 40 : 60) + random.nextInt(day > 200 ? 120 : 220);
            var start = date.atTime(8 + random.nextInt(2), random.nextInt(4) * 15).atZone(zone).toInstant();
            while (budget > 20) {
                long minutes = Math.min(budget, 30 + random.nextInt(90));
                var end = start.plus(Duration.ofMinutes(minutes));
                tracker.log(a.get(random.nextInt(a.size())).id(), start, end);
                budget -= minutes;
                start = end.plus(Duration.ofMinutes(30 + random.nextInt(90)));
            }
        }
        // Plans for the past week, so the schedule has plan and actual side by side.
        for (int day = 6; day >= 1; day--) {
            var date = today.minusDays(day);
            tracker.plan(a.get(day % a.size()).id(), date.atTime(9, 0).atZone(zone).toInstant(), date.atTime(10, 30).atZone(zone).toInstant());
            if (day % 2 == 0) tracker.plan(a.get((day + 1) % a.size()).id(), date.atTime(13, 0).atZone(zone).toInstant(), date.atTime(14, 0).atZone(zone).toInstant());
        }
        var now = Instant.now();
        // Earlier today, well clear of the running timer.
        tracker.log(a.get(1).id(), now.minus(Duration.ofMinutes(330)), now.minus(Duration.ofMinutes(250)));

        var reviews = new ArrayList<>(weekOfAnki(today, zone));
        if (todayAnki) reviews.addAll(todaysAnki(now));
        tracker.addAnkiTime(reviews, now.minus(Duration.ofDays(7)));

        tracker.repeat(a.get(0).id(), DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 30));
        tracker.repeat(a.get(2).id(), DayOfWeek.WEDNESDAY, LocalTime.of(14, 15), LocalTime.of(15, 45));
        tracker.repeat(a.get(1).id(), DayOfWeek.FRIDAY, LocalTime.of(13, 0), LocalTime.of(14, 30));
        tracker.repeat(a.get(3).id(), DayOfWeek.TUESDAY, LocalTime.of(19, 0), LocalTime.of(20, 0));

        var reading = tracker.addTag("Reading", 0x90D8DA);
        var language = tracker.addTag("Language", 0xE8B24C);
        var cs = tracker.addTag("CS", 0xB39DDB);
        var stamp = Instant.now();
        UUID study = a.get(0).id(), coding = a.get(1).id(), japanese = a.get(2).id();
        tracker.addTasks(List.of(
            new Task(UUID.randomUUID(), study, reading.id(), "Read chapter 4", "Review the worked examples first.", today.plusDays(1), TaskStatus.TODO, "Manual entry", stamp, 0),
            new Task(UUID.randomUUID(), coding, cs.id(), "Finish lab 3", "Write the tests before the report.", today.minusDays(1), TaskStatus.DOING, "Manual entry", stamp, 1),
            new Task(UUID.randomUUID(), japanese, language.id(), "Kanji review deck", "", today, TaskStatus.TODO, "Manual entry", stamp, 2),
            new Task(UUID.randomUUID(), null, null, "Order the textbook", "", null, TaskStatus.DONE, "Manual entry", stamp, 3),
            new Task(UUID.randomUUID(), coding, cs.id(), "Problem set 5", "", today.plusDays(3), TaskStatus.TODO, "Manual entry", stamp, 4),
            new Task(UUID.randomUUID(), study, reading.id(), "Essay outline", "", today.plusDays(5), TaskStatus.DOING, "Manual entry", stamp, 5),
            new Task(UUID.randomUUID(), japanese, language.id(), "Listening practice", "", today.plusDays(2), TaskStatus.TODO, "Manual entry", stamp, 6),
            new Task(UUID.randomUUID(), study, reading.id(), "Flashcards for the quiz", "", today.plusDays(6), TaskStatus.TODO, "Manual entry", stamp, 7),
            new Task(UUID.randomUUID(), coding, cs.id(), "Refactor the parser", "", today.plusDays(9), TaskStatus.TODO, "Manual entry", stamp, 8),
            new Task(UUID.randomUUID(), japanese, language.id(), "Grammar review", "", today.minusDays(3), TaskStatus.DONE, "Manual entry", stamp, 9),
            new Task(UUID.randomUUID(), study, null, "Office hours question", "", today.plusDays(4), TaskStatus.TODO, "Manual entry", stamp, 10)));

        tracker.addHabit("Evening reset", HabitKind.DAILY, zone, null);
        tracker.addHabit("Read 20 pages", HabitKind.DAILY, zone, null);
        tracker.addHabit("Stretch", HabitKind.DAILY, zone, null);
        var habits = tracker.state().habits();
        for (int day = 0; day < 28; day++) {
            if (day % 9 != 4) tracker.checkIn(habits.get(0).id(), today.minusDays(day), true);
            if (random.nextInt(10) < 7) tracker.checkIn(habits.get(1).id(), today.minusDays(day), true);
            if (day < 12 && day != 5) tracker.checkIn(habits.get(2).id(), today.minusDays(day), true);
        }
        tracker.addHabit("Time since last energy drink", HabitKind.TIME_SINCE, zone, now.minus(Duration.ofDays(23)).minusSeconds(7340));

        // The week on screen ends today, so the schedule shows a week already lived.
        tracker.settings(new Settings(theme, TrainerId.BRENDAN, 4, 300, today.plusDays(1).getDayOfWeek()));
        tracker.start(study);
        tracker.editSession(tracker.active().id(), study, now.minus(Duration.ofMinutes(42)), null);
        return tracker;
    }
}
