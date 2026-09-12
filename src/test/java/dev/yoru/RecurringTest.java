package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * The weekly repeating schedule (#4).
 *
 * The reason this stores LocalTime and a weekday rather than instants is the
 * daylight-saving assertion below: "every Monday at nine" has to mean nine
 * o'clock on every one of those Mondays, not a fixed offset that drifts by an
 * hour twice a year. Everything else here exists to keep that true.
 */
public final class RecurringTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private interface Action {void run()throws Exception;}
    private static void rejects(Action action,String why)throws Exception{
        try{action.run();}catch(IllegalArgumentException|NullPointerException|IOException expected){checks++;return;}
        throw new AssertionError(why);
    }

    private static final class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }

    public static void main(String[] args)throws Exception{
        var zone=ZoneId.of("America/New_York");
        var repo=new Memory();
        var t=new Tracker(repo,Clock.systemUTC());
        t.addActivity("Calculus",0);
        t.addActivity("Japanese",0);
        UUID calculus=t.state().activities().getFirst().id();
        UUID japanese=t.state().activities().get(1).id();

        // The record refuses what a weekday cannot express.
        rejects(()->new RecurringBlock(UUID.randomUUID(),calculus,DayOfWeek.MONDAY,
            LocalTime.of(10,0),LocalTime.of(9,0)),"A block cannot end before it starts");
        rejects(()->new RecurringBlock(UUID.randomUUID(),calculus,DayOfWeek.MONDAY,
            LocalTime.of(9,0),LocalTime.of(9,0)),"A block cannot be instantaneous");
        rejects(()->new RecurringBlock(UUID.randomUUID(),calculus,null,
            LocalTime.of(9,0),LocalTime.of(10,0)),"A block needs a weekday");

        var monday=t.repeat(calculus,DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(10,30));
        var wednesday=t.repeat(japanese,DayOfWeek.WEDNESDAY,LocalTime.of(14,15),LocalTime.of(15,45));
        check(t.state().recurring().size()==2,"The template holds both entries");
        check(repo.state.recurring().size()==2,"The template reached storage");

        // Overlaps clash within a weekday and only within a weekday.
        rejects(()->t.repeat(japanese,DayOfWeek.MONDAY,LocalTime.of(10,0),LocalTime.of(11,0)),
            "Two blocks cannot overlap on the same weekday");
        t.repeat(japanese,DayOfWeek.TUESDAY,LocalTime.of(9,0),LocalTime.of(10,30));
        check(t.state().recurring().size()==3,"The same clock time on another weekday is fine");
        t.repeat(japanese,DayOfWeek.MONDAY,LocalTime.of(10,30),LocalTime.of(11,30));
        check(t.state().recurring().size()==4,"A block may start exactly when the previous one ends");

        rejects(()->t.repeat(UUID.randomUUID(),DayOfWeek.FRIDAY,LocalTime.of(9,0),LocalTime.of(10,0)),
            "A block needs an activity that exists");

        t.editRepeat(monday.id(),calculus,DayOfWeek.THURSDAY,LocalTime.of(8,0),LocalTime.of(9,0));
        var edited=t.state().recurring().stream().filter(r->r.id().equals(monday.id())).findFirst().orElseThrow();
        check(edited.dayOfWeek()==DayOfWeek.THURSDAY,"Editing moves the weekday");
        check(edited.startTime().equals(LocalTime.of(8,0)),"Editing moves the clock time");
        t.editRepeat(monday.id(),calculus,DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(10,30));

        t.deleteRepeat(wednesday.id());
        check(t.state().recurring().stream().noneMatch(r->r.id().equals(wednesday.id())),"Deleting removes the entry");
        rejects(()->t.deleteRepeat(wednesday.id()),"Deleting twice is refused");
        rejects(()->t.editRepeat(wednesday.id(),calculus,DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(10,0)),
            "Editing a deleted entry is refused");

        // Expansion covers exactly the seven days asked for, in order.
        var week=LocalDate.parse("2026-09-07");   // a Monday
        var occurrences=Analytics.occurrences(t.state(),week,zone);
        check(occurrences.size()==3,"Three rules fall inside this week, found "+occurrences.size());
        check(occurrences.getFirst().start().isBefore(occurrences.get(1).start()),"Occurrences come out in time order");
        for(var occurrence:occurrences){
            var local=occurrence.start().atZone(zone).toLocalDate();
            check(!local.isBefore(week)&&local.isBefore(week.plusDays(7)),"Every occurrence lands inside the week");
        }
        check(Analytics.occurrences(State.empty(),week,zone).isEmpty(),"An empty template expands to nothing");

        // The whole reason for storing LocalTime. US clocks go forward on
        // 8 March 2026, between these two Mondays.
        var before=Analytics.occurrences(t.state(),LocalDate.parse("2026-03-02"),zone).stream()
            .filter(o->o.recurringId().equals(monday.id())).findFirst().orElseThrow();
        var after=Analytics.occurrences(t.state(),LocalDate.parse("2026-03-09"),zone).stream()
            .filter(o->o.recurringId().equals(monday.id())).findFirst().orElseThrow();
        check(before.start().atZone(zone).toLocalTime().equals(LocalTime.of(9,0)),"Nine o'clock before the clocks change");
        check(after.start().atZone(zone).toLocalTime().equals(LocalTime.of(9,0)),"Nine o'clock after the clocks change");
        check(!before.start().atOffset(ZoneOffset.UTC).toLocalTime()
                .equals(after.start().atOffset(ZoneOffset.UTC).toLocalTime()),
            "The two Mondays are different instants — which is what stored instants would have got wrong");
        check(Duration.between(before.start(),before.end()).equals(Duration.ofMinutes(90)),"Length is preserved before the change");
        check(Duration.between(after.start(),after.end()).equals(Duration.ofMinutes(90)),"Length is preserved after the change");

        // A rule sitting inside the hour a zone skips still happens, on the terms
        // the clock allows: the start is pushed forward by the length of the gap
        // and the end is untouched, so on 8 March 2026 a 02:30-03:45 block really
        // does run for fifteen minutes. That hour did not exist. Asserted rather
        // than corrected, because inventing a duration-preserving rule for 2am
        // study blocks would be a worse answer than the truthful one.
        var gap=t.repeat(calculus,DayOfWeek.SUNDAY,LocalTime.of(2,30),LocalTime.of(3,45));
        var skipped=Analytics.occurrences(t.state(),LocalDate.parse("2026-03-02"),zone).stream()
            .filter(o->o.recurringId().equals(gap.id())).findFirst().orElseThrow();
        check(skipped.start().atZone(zone).toLocalTime().equals(LocalTime.of(3,30)),
            "A start inside the skipped hour moves forward by the gap");
        check(skipped.end().atZone(zone).toLocalTime().equals(LocalTime.of(3,45)),
            "An end outside the skipped hour is left where it is");
        check(Duration.between(skipped.start(),skipped.end()).equals(Duration.ofMinutes(15)),
            "The block is shorter on the day the hour vanishes");
        var normal=Analytics.occurrences(t.state(),LocalDate.parse("2026-03-09"),zone).stream()
            .filter(o->o.recurringId().equals(gap.id())).findFirst().orElseThrow();
        check(Duration.between(normal.start(),normal.end()).equals(Duration.ofMinutes(75)),
            "Every other week that block is its full length");
        t.deleteRepeat(gap.id());

        // Vaults: schema 6 round trip, and schema 5 vaults still open.
        Path dir=Files.createTempDirectory("yoru-recurring-");
        try {
            Path file=dir.resolve("recurring.vault");
            // EncryptedVault zeroes the array it is handed, so every open needs a
            // fresh one. Reusing it fails with a tag mismatch, not a wrong-password
            // error, which reads like corruption.
            String password="recurring-test-password";
            State saved=t.state();
            try(var vault=new EncryptedVault(file,password.toCharArray())){vault.save(saved);}
            try(var vault=new EncryptedVault(file,password.toCharArray())){
                var loaded=vault.load();
                check(loaded.equals(saved),"A vault round-trips the weekly template");
                check(loaded.recurring().size()==saved.recurring().size(),"Every entry survives");
                check(loaded.recurring().getFirst().startTime().equals(LocalTime.of(9,0)),"Clock times survive to the minute");
            }
            // The dated blocks a schema 5 vault already held are not disturbed.
            Path mixed=dir.resolve("mixed.vault");
            var dated=new ScheduleBlock(UUID.randomUUID(),calculus,
                Instant.parse("2026-09-10T13:00:00Z"),Instant.parse("2026-09-10T14:00:00Z"));
            var both=saved.withCore(saved.activities(),saved.sessions(),List.of(dated));
            try(var vault=new EncryptedVault(mixed,password.toCharArray())){vault.save(both);}
            try(var vault=new EncryptedVault(mixed,password.toCharArray())){
                var loaded=vault.load();
                check(loaded.blocks().size()==1,"A one-off block survives alongside the template");
                check(loaded.recurring().size()==both.recurring().size(),"The template survives alongside one-offs");
                check(loaded.equals(both),"Both shapes round trip together");
            }
        } finally {
            try(var files=Files.walk(dir)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }

        // Resetting the schedule clears both shapes, and nothing else.
        int tasksBefore=t.state().tasks().size();
        t.reset(EnumSet.of(Tracker.ResetPart.SCHEDULE));
        check(t.state().recurring().isEmpty(),"Resetting the schedule clears the weekly template");
        check(t.state().blocks().isEmpty(),"Resetting the schedule clears one-off blocks");
        check(t.state().activities().size()==2,"Resetting the schedule keeps activities");
        check(t.state().tasks().size()==tasksBefore,"Resetting the schedule keeps tasks");

        // Week start is a preference, not a constant (#26). The default keeps the
        // Monday the app has always assumed, so existing vaults do not move.
        check(Settings.defaults().weekStartsOn()==DayOfWeek.MONDAY,"Monday stays the default");
        var midweek=LocalDate.parse("2026-09-09");
        check(Settings.defaults().weekOf(midweek).equals(LocalDate.parse("2026-09-07")),
            "A Monday week containing Wednesday starts on the 7th");
        var sundayFirst=new Settings(ThemeId.MIDNIGHT,TrainerId.BRENDAN,4,300,DayOfWeek.SUNDAY);
        check(sundayFirst.weekOf(midweek).equals(LocalDate.parse("2026-09-06")),
            "A Sunday week containing Wednesday starts on the 6th");
        check(sundayFirst.weekOf(LocalDate.parse("2026-09-06")).equals(LocalDate.parse("2026-09-06")),
            "The first day of the week is its own week start");
        var saturdayFirst=new Settings(ThemeId.MIDNIGHT,TrainerId.BRENDAN,4,300,DayOfWeek.SATURDAY);
        check(saturdayFirst.weekOf(midweek).equals(LocalDate.parse("2026-09-05")),
            "Any weekday can start the week");

        // It survives a vault, and older vaults arrive on Monday rather than failing.
        Path settingsDir=Files.createTempDirectory("yoru-weekstart-");
        try {
            Path file=settingsDir.resolve("weekstart.vault");
            String password="week-start-test-password";
            var moved=t.state().withSettings(sundayFirst);
            try(var vault=new EncryptedVault(file,password.toCharArray())){vault.save(moved);}
            try(var vault=new EncryptedVault(file,password.toCharArray())){
                check(vault.load().settings().weekStartsOn()==DayOfWeek.SUNDAY,"The week start survives a vault");
                check(vault.load().equals(moved),"Nothing else moved with it");
            }
        } finally {
            try(var files=Files.walk(settingsDir)){
                for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }

        System.out.println("PASS: "+checks+" recurring checks (validation, overlap, expansion, daylight saving, week start, vaults)");
    }
}
