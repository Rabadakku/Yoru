package dev.yoru;

import dev.yoru.application.QuickAdd;
import dev.yoru.domain.Model.*;
import java.time.*;
import java.util.*;

/**
 * The quick-add grammar (#74), read against a fixed Wednesday, October 7,
 * 2026, with the week starting on Monday. Each line is a phrase and what it
 * must become; the rules for words that could mean two things are the ones
 * QuickAdd's own comment states.
 */
public final class QuickAddTest {
    private static int checks, phrases;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    private static final QuickAdd.Context CONTEXT = new QuickAdd.Context(TODAY, DayOfWeek.MONDAY,
        List.of("Reading group", "school"), List.of("Home errands", "Home", "Work"));

    private static QuickAdd.Result read(String line) { return QuickAdd.parse(line, CONTEXT); }
    private static LocalDate d(String iso) { return LocalDate.parse(iso); }
    private static LocalTime t(String hhmm) { return LocalTime.parse(hhmm); }

    /** A phrase that sets a title and a due date, and perhaps a time. */
    private static void due(String line, String title, String due, String time) {
        var r = read(line);
        phrases++;
        check(r.title().equals(title), "\"" + line + "\": the title is \"" + title + "\", not \"" + r.title() + "\"");
        check(Objects.equals(r.due(), due == null ? null : d(due)), "\"" + line + "\": due " + due + ", not " + r.due());
        check(Objects.equals(r.time(), time == null ? null : t(time)), "\"" + line + "\": at " + time + ", not " + r.time());
    }

    private static void repeats(String line, String title, String due, RepeatUnit unit, int every, Set<DayOfWeek> days) {
        var r = read(line);
        phrases++;
        check(r.title().equals(title), "\"" + line + "\": the title is \"" + title + "\", not \"" + r.title() + "\"");
        check(r.due().equals(d(due)), "\"" + line + "\": first due " + due + ", not " + r.due());
        check(r.repeat() != null && r.repeat().unit() == unit && r.repeat().every() == every,
            "\"" + line + "\": repeats every " + every + " " + unit + ", not " + r.repeat());
        if (days != null) check(r.repeat().days().equals(days), "\"" + line + "\": on " + days + ", not " + r.repeat().days());
        check(r.repeat().start().equals(r.due()), "\"" + line + "\": the rule starts on the due date");
    }

    public static void main(String[] args) {
        // The ticket's own example.
        var example = read("Essay draft tomorrow 5pm #school !high every monday");
        phrases++;
        check(example.title().equals("Essay draft"), "The example's title is \"Essay draft\": " + example.title());
        check(example.due().equals(d("2026-10-08")) && example.time().equals(t("17:00")), "due tomorrow at 5 pm");
        check(example.tags().equals(List.of("school")) && example.priority() == Priority.HIGH, "tagged school, high priority");
        check(example.repeat().unit() == RepeatUnit.WEEK && example.repeat().days().equals(Set.of(DayOfWeek.MONDAY)), "every Monday");
        check(example.parts().stream().map(QuickAdd.Part::kind).toList().equals(List.of(
            QuickAdd.Kind.DATE, QuickAdd.Kind.TIME, QuickAdd.Kind.TAG, QuickAdd.Kind.PRIORITY, QuickAdd.Kind.REPEAT)),
            "Every part is recognised, in the order typed: " + example.parts());
        var date = example.parts().getFirst();
        check("Essay draft tomorrow 5pm #school !high every monday".substring(date.start(), date.end()).equals("tomorrow"),
            "and each knows where it was typed");

        // Days.
        due("Buy milk today", "Buy milk", "2026-10-07", null);
        due("Call mom tonight", "Call mom", "2026-10-07", "20:00");
        due("Call mom tonight at 9pm", "Call mom", "2026-10-07", "21:00");
        due("Pay rent tomorrow", "Pay rent", "2026-10-08", null);
        due("Pay rent tmrw", "Pay rent", "2026-10-08", null);
        due("Submit form friday", "Submit form", "2026-10-09", null);
        due("Submit form on friday", "Submit form", "2026-10-09", null);
        due("Submit form by fri", "Submit form", "2026-10-09", null);
        due("Standup wednesday", "Standup", "2026-10-14", null);          // a week today: said on a Wednesday
        due("Standup this wednesday", "Standup", "2026-10-07", null);     // today
        due("Review next friday", "Review", "2026-10-16", null);          // next week's Friday
        due("Review next monday", "Review", "2026-10-12", null);
        due("Plan next week", "Plan", "2026-10-12", null);
        due("Budget next month", "Budget", "2026-11-01", null);
        due("Taxes next year", "Taxes", "2027-01-01", null);
        due("Hike this weekend", "Hike", "2026-10-10", null);
        due("Hike next weekend", "Hike", "2026-10-17", null);
        due("Hike weekend", "Hike", "2026-10-10", null);
        due("Dentist in 3 days", "Dentist", "2026-10-10", null);
        due("Dentist in a week", "Dentist", "2026-10-14", null);
        due("Dentist in two weeks", "Dentist", "2026-10-21", null);
        due("Renew passport in 1 month", "Renew passport", "2026-11-07", null);
        due("Renew passport in 12 months", "Renew passport", "2027-10-07", null);
        due("Rent due the 14th", "Rent", "2026-10-14", null);
        due("Rent the 1st", "Rent", "2026-11-01", null);                  // this month's 1st has passed
        due("Rent the 7th", "Rent", "2026-10-07", null);                  // today counts
        due("Rent the 31st", "Rent", "2026-10-31", null);
        due("Rent the 3th", "Rent the 3th", null, null);                  // not an ordinal
        due("Report 14 Oct", "Report", "2026-10-14", null);
        due("Report Oct 14", "Report", "2026-10-14", null);
        due("Report October 14th", "Report", "2026-10-14", null);
        due("Report oct 5", "Report", "2027-10-05", null);                // passed this year
        due("Report Oct 14 2027", "Report", "2027-10-14", null);
        due("Report Feb 29", "Report", "2028-02-29", null);               // the next leap year
        due("Report 2026-12-01", "Report", "2026-12-01", null);
        due("Report 12/1", "Report", "2026-12-01", null);
        due("Report 12/1/27", "Report", "2027-12-01", null);
        due("Essay, due tomorrow", "Essay", "2026-10-08", null);
        due("Move meeting from friday to monday", "Move meeting from to monday", "2026-10-09", null); // only the first date

        // Times.
        due("Report Oct 14 at 9", "Report", "2026-10-14", "09:00");
        due("Meeting at 3", "Meeting", "2026-10-07", "15:00");            // 1 to 7 is the afternoon
        due("Meeting at 9", "Meeting", "2026-10-07", "09:00");            // 8 to 12 the morning
        due("Meeting at 12", "Meeting", "2026-10-07", "12:00");
        due("Meeting 17:00", "Meeting", "2026-10-07", "17:00");
        due("Meeting 5:30pm tomorrow", "Meeting", "2026-10-08", "17:30");
        due("Call at 3 tomorrow", "Call", "2026-10-08", "15:00");
        due("Lunch at noon", "Lunch", "2026-10-07", "12:00");
        due("Backup at midnight", "Backup", "2026-10-07", "00:00");
        due("Flight friday 6:45 am", "Flight", "2026-10-09", "06:45");

        // Words that are not dates.
        due("Read chapter 4", "Read chapter 4", null, null);
        due("HW 3.1 polynomials", "HW 3.1 polynomials", null, null);
        due("Buy sun cream", "Buy sun cream", null, null);
        due("SAT prep", "SAT prep", null, null);
        due("Sort mail on sat", "Sort mail", "2026-10-10", null);
        due("Email someone@example.com about it", "Email someone@example.com about it", null, null);
        due("Read and/or skim", "Read and/or skim", null, null);
        due("tomorrow", "tomorrow", null, null);                          // nothing left for a title

        // Repeats.
        repeats("Water plants every day", "Water plants", "2026-10-07", RepeatUnit.DAY, 1, null);
        repeats("Stretch daily", "Stretch", "2026-10-07", RepeatUnit.DAY, 1, null);
        repeats("Journal every 3 days", "Journal", "2026-10-07", RepeatUnit.DAY, 3, null);
        repeats("Standup every weekday", "Standup", "2026-10-07", RepeatUnit.WEEK, 1,
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));
        repeats("Laundry every other week", "Laundry", "2026-10-07", RepeatUnit.WEEK, 2, Set.of(DayOfWeek.WEDNESDAY));
        repeats("Laundry weekly", "Laundry", "2026-10-07", RepeatUnit.WEEK, 1, Set.of(DayOfWeek.WEDNESDAY));
        repeats("Gym every mon and thu", "Gym", "2026-10-08", RepeatUnit.WEEK, 1, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));
        repeats("Gym every mon, wed & fri", "Gym", "2026-10-07", RepeatUnit.WEEK, 1,
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY));
        repeats("Piano every tuesday", "Piano", "2026-10-13", RepeatUnit.WEEK, 1, Set.of(DayOfWeek.TUESDAY));
        repeats("Piano each tuesday", "Piano", "2026-10-13", RepeatUnit.WEEK, 1, Set.of(DayOfWeek.TUESDAY));
        repeats("Bills every month", "Bills", "2026-10-07", RepeatUnit.MONTH, 1, null);
        repeats("Bills monthly", "Bills", "2026-10-07", RepeatUnit.MONTH, 1, null);
        repeats("Birthday card every year", "Birthday card", "2026-10-07", RepeatUnit.YEAR, 1, null);
        repeats("Report every month the 14th", "Report", "2026-10-14", RepeatUnit.MONTH, 1, null);
        check(read("Report every month the 14th").repeat().monthDay() == 14, "A monthly repeat falls on its due date's day");

        // Tags, lists and priority.
        var tags = read("Groceries #errands #home #Errands");
        phrases++;
        check(tags.title().equals("Groceries") && tags.tags().equals(List.of("errands", "home")), "Tags are read once each: " + tags.tags());
        var group = read("Discuss #reading group notes");
        phrases++;
        check(group.tags().equals(List.of("Reading group")) && group.title().equals("Discuss notes"), "A tag of two words the vault has is read whole");
        var school = read("Quiz #SCHOOL");
        phrases++;
        check(school.tags().equals(List.of("school")), "An existing tag is matched whatever its case, as it is named");
        var numbered = read("Do the #1 thing");
        phrases++;
        check(numbered.tags().isEmpty() && numbered.title().equals("Do the #1 thing"), "\"#1\" is not a tag");
        var list = read("Groceries /Home errands");
        phrases++;
        check("Home errands".equals(list.list()) && list.title().equals("Groceries"), "A list of two words is read whole, longest first");
        var at = read("Groceries @home");
        phrases++;
        check("Home".equals(at.list()), "@ names a list too");
        var unknown = read("Groceries /nowhere");
        phrases++;
        check(unknown.list() == null && unknown.title().equals("Groceries /nowhere"), "A list the vault does not have stays in the title");
        for (var line : List.of("Fix bug !urgent", "Fix bug !1", "Fix bug !p1")) {
            phrases++;
            check(read(line).priority() == Priority.URGENT && read(line).title().equals("Fix bug"), line + " is urgent");
        }
        phrases++;
        check(read("Fix bug !p2").priority() == Priority.HIGH && read("Fix bug !3").priority() == Priority.MEDIUM
            && read("Fix bug !low").priority() == Priority.LOW, "!p2, !3 and !low");
        phrases++;
        check(read("Wow!high").priority() == Priority.NONE, "A ! inside a word is not a priority");

        // Kept as text.
        var plain = QuickAdd.parse("Buy milk tomorrow #errands", CONTEXT, Set.of("DATE:tomorrow"));
        check(plain.due() == null && plain.title().equals("Buy milk tomorrow") && plain.tags().equals(List.of("errands")),
            "A part kept as text stays in the title, and the rest is still read");
        var keep = QuickAdd.parse("Buy milk tomorrow #errands", CONTEXT, Set.of("TAG:#errands"));
        check(keep.tags().isEmpty() && keep.title().equals("Buy milk #errands") && keep.due() != null, "A tag can be kept as text too");
        check(read("Buy milk tomorrow").parts().getFirst().key().equals("DATE:tomorrow"), "A part is named by its kind and words");

        // Another week start.
        var sunday = new QuickAdd.Context(TODAY, DayOfWeek.SUNDAY, List.of(), List.of());
        check(QuickAdd.parse("Plan next week", sunday).due().equals(d("2026-10-11")), "Next week starts on the owner's week start");
        check(QuickAdd.parse("Review next friday", sunday).due().equals(d("2026-10-16")), "and next Friday is in it");
        phrases += 2;

        check(phrases >= 50, "At least fifty phrases: " + phrases);
        System.out.println("PASS: " + checks + " quick-add checks (" + phrases + " phrases: days, times, repeats, tags, lists, priority, kept as text)");
    }
}
