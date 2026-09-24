package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import dev.yoru.persistence.PortableVault;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * One week of a weekly repeat, changed on its own (#59): skipped, moved to
 * another time or day, and put back — with the rule and every other week left
 * exactly as they were.
 */
public final class RepeatWeekTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private interface Action{void run()throws Exception;}
    private static void refuses(Action action,String why)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IOException expected){checks++;return;}
        throw new AssertionError("Expected a refusal: "+why);
    }
    private static final class Memory implements Repository{
        State state=State.empty();boolean failSave;int saves;
        public State load(){return state;}
        public void save(State next)throws IOException{if(failSave)throw new IOException("Synthetic save failure");state=next;saves++;}
        public void backup(){}
        public void close(){}
    }

    private static final ZoneId ZONE=ZoneId.of("Europe/Berlin");
    /** A Monday. The invented timetable: a lecture Mondays 09:00–10:30, a lab Mondays 13:00–15:00, a seminar Wednesdays 09:00–10:00. */
    private static final LocalDate MONDAY=LocalDate.of(2026,10,5);
    private static LocalDate d(String text){return LocalDate.parse(text);}
    private static LocalTime t(String text){return LocalTime.parse(text);}

    private static List<Analytics.Occurrence> of(Tracker tracker,UUID rule,LocalDate weekStart){
        return Analytics.occurrences(tracker.state(),weekStart,ZONE).stream().filter(o->o.recurringId().equals(rule)).toList();
    }

    public static void main(String[] args)throws Exception{
        domain();
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"),ZONE));
        tracker.addActivity("Invented lecture",0);
        tracker.addActivity("Invented lab",0);
        var study=tracker.state().activities().get(0);
        var lab=tracker.state().activities().get(1);
        var lecture=tracker.repeat(study.id(),DayOfWeek.MONDAY,t("09:00"),t("10:30"));
        var labs=tracker.repeat(lab.id(),DayOfWeek.MONDAY,t("13:00"),t("15:00"));
        var seminar=tracker.repeat(study.id(),DayOfWeek.WEDNESDAY,t("09:00"),t("10:00"));

        // Skip one week: that Monday has no lecture, the weeks either side do.
        tracker.skipRepeatWeek(lecture.id(),MONDAY);
        check(of(tracker,lecture.id(),MONDAY).isEmpty(),"A skipped week has no block");
        check(of(tracker,lecture.id(),MONDAY.minusWeeks(1)).size()==1&&of(tracker,lecture.id(),MONDAY.plusWeeks(1)).size()==1,
            "The weeks either side still follow the rule");
        check(of(tracker,labs.id(),MONDAY).size()==1,"Another rule on the same day is untouched");
        var rule=tracker.state().recurring().getFirst();
        check(rule.startTime().equals(t("09:00"))&&rule.endTime().equals(t("10:30"))&&rule.dayOfWeek()==DayOfWeek.MONDAY,
            "The rule itself does not change");
        int saves=repo.saves;
        tracker.skipRepeatWeek(lecture.id(),MONDAY);
        check(repo.saves==saves,"Skipping a week already skipped writes nothing");

        // Restore it.
        tracker.restoreRepeatWeek(lecture.id(),MONDAY);
        check(of(tracker,lecture.id(),MONDAY).size()==1&&tracker.state().recurring().getFirst().changes().isEmpty(),
            "Restoring puts the week back and keeps no change");
        saves=repo.saves;
        tracker.restoreRepeatWeek(lecture.id(),MONDAY);
        check(repo.saves==saves,"Restoring a week that follows the rule writes nothing");

        // Move one week to 11:00 the same day.
        tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY,t("11:00"),t("12:30"));
        var moved=of(tracker,lecture.id(),MONDAY);
        check(moved.size()==1&&moved.getFirst().changed()&&moved.getFirst().week().equals(MONDAY),
            "A moved week is drawn once, marked changed, and still names its own week");
        check(moved.getFirst().start().equals(MONDAY.atTime(11,0).atZone(ZONE).toInstant())
            &&moved.getFirst().end().equals(MONDAY.atTime(12,30).atZone(ZONE).toInstant()),"At the time it was moved to");
        check(!of(tracker,lecture.id(),MONDAY.plusWeeks(1)).getFirst().changed(),"Next week follows the rule");

        // Move it to Tuesday instead.
        tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY.plusDays(1),t("09:00"),t("10:30"));
        var tuesday=of(tracker,lecture.id(),MONDAY).getFirst();
        check(tuesday.start().atZone(ZONE).toLocalDate().equals(MONDAY.plusDays(1)),"A week can move to another day");
        check(Analytics.occurrencesOn(tracker.state(),MONDAY.plusDays(1),ZONE).stream().anyMatch(o->o.recurringId().equals(lecture.id())),
            "And the day it moved to lists it");
        check(Analytics.occurrencesOn(tracker.state(),MONDAY,ZONE).stream().noneMatch(o->o.recurringId().equals(lecture.id())),
            "While its own day does not");
        check(tracker.state().recurring().getFirst().changes().size()==1,"Changing a changed week replaces its change");

        // Moved across the edge of the week shown: the week it lands in draws it.
        tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY.minusDays(1),t("18:00"),t("19:00"));
        check(of(tracker,lecture.id(),MONDAY).isEmpty(),"A week moved into the week before is not this week's to draw");
        var sunday=of(tracker,lecture.id(),MONDAY.minusWeeks(1));
        check(sunday.size()==2&&sunday.stream().filter(Analytics.Occurrence::changed).count()==1,
            "The week before draws its own Monday and the Sunday moved into it");

        // Put back where the rule has it: no change is kept.
        tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY,t("09:00"),t("10:30"));
        check(tracker.state().recurring().getFirst().changes().isEmpty(),"A week moved back to the rule's time keeps no change");

        // Refusals.
        var before=tracker.state();
        refuses(()->tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY,t("12:30"),t("13:30")),
            "a week moved onto the lab that Monday overlaps it");
        refuses(()->tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY.plusDays(2),t("09:30"),t("10:30")),
            "a week moved onto Wednesday's seminar overlaps it");
        refuses(()->tracker.changeRepeatWeek(lecture.id(),MONDAY.plusDays(1),MONDAY.plusDays(1),t("11:00"),t("12:00")),
            "a Monday rule has no block on a Tuesday");
        refuses(()->tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY.plusDays(9),t("11:00"),t("12:00")),
            "a week moved more than six days");
        refuses(()->tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY,t("11:00"),t("10:00")),"a week that ends before it starts");
        refuses(()->tracker.skipRepeatWeek(UUID.randomUUID(),MONDAY),"a rule that no longer exists");
        check(tracker.state().equals(before),"Refusals change nothing");

        // Moving the lab's week off Monday frees its time for the lecture's that week.
        tracker.changeRepeatWeek(labs.id(),MONDAY,MONDAY.plusDays(3),t("13:00"),t("15:00"));
        tracker.changeRepeatWeek(lecture.id(),MONDAY,MONDAY,t("13:00"),t("14:30"));
        check(of(tracker,lecture.id(),MONDAY).getFirst().start().equals(MONDAY.atTime(13,0).atZone(ZONE).toInstant()),
            "A week may take a time another rule's week left that day");
        refuses(()->tracker.restoreRepeatWeek(labs.id(),MONDAY),"putting the lab back would land on the moved lecture");

        // Two weeks of one rule moved onto the same day, apart.
        tracker.changeRepeatWeek(seminar.id(),MONDAY.plusDays(2),MONDAY.plusDays(4),t("09:00"),t("10:00"));
        tracker.changeRepeatWeek(seminar.id(),MONDAY.plusDays(9),MONDAY.plusDays(4),t("11:00"),t("12:00"));
        check(Analytics.occurrencesOn(tracker.state(),MONDAY.plusDays(4),ZONE).stream()
            .filter(o->o.recurringId().equals(seminar.id())).count()==2,"Two weeks of one rule can share a day");

        // Failed save.
        before=tracker.state();
        repo.failSave=true;
        refuses(()->tracker.skipRepeatWeek(seminar.id(),MONDAY.plusWeeks(3).plusDays(2)),"the save failed");
        refuses(()->tracker.restoreRepeatWeek(seminar.id(),MONDAY.plusDays(2)),"the save failed");
        check(tracker.state().equals(before),"A failed save keeps every week as it was");
        repo.failSave=false;

        // Editing the rule: the same day keeps its changed weeks, another day puts them back.
        var seminarRule=rule(tracker,seminar.id());
        tracker.editRepeat(seminar.id(),lab.id(),DayOfWeek.WEDNESDAY,t("08:00"),t("08:45"));
        check(rule(tracker,seminar.id()).changes().equals(seminarRule.changes()),"Changing the rule's time keeps its changed weeks");
        tracker.editRepeat(seminar.id(),lab.id(),DayOfWeek.THURSDAY,t("08:00"),t("08:45"));
        check(rule(tracker,seminar.id()).changes().isEmpty(),"Moving the rule to another day puts its weeks back");

        // Deleting an activity keeps the weeks of the rules that move to Uncategorized.
        var withChanges=rule(tracker,lecture.id());
        check(!withChanges.changes().isEmpty(),"The lecture has a changed week to keep");
        tracker.removeActivity(study.id(),true);
        check(rule(tracker,lecture.id()).changes().equals(withChanges.changes()),
            "A repeat moved to Uncategorized keeps its changed weeks");

        // Daylight saving: a week moved onto the day the clocks go back keeps its wall-clock time.
        var fall=LocalDate.of(2026,10,25); // a Sunday in Berlin
        var fallRepo=new Memory();
        var fallTracker=new Tracker(fallRepo,Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"),ZONE));
        fallTracker.addActivity("Invented run",0);
        var x=fallTracker.state().activities().getFirst();
        var mondayRun=fallTracker.repeat(x.id(),DayOfWeek.MONDAY,t("07:00"),t("08:00"));
        fallTracker.changeRepeatWeek(mondayRun.id(),fall.plusDays(1),fall,t("07:00"),t("08:00"));
        var onSunday=Analytics.occurrencesOn(fallTracker.state(),fall,ZONE).getFirst();
        check(onSunday.start().atZone(ZONE).toLocalTime().equals(t("07:00"))
            &&Duration.between(onSunday.start(),onSunday.end()).toMinutes()==60,"07:00 on the day the clocks change is still 07:00, for an hour");

        roundTrips(tracker.state());
        System.out.println("PASS: "+checks+" repeat week checks (skip, move, restore, overlaps, rule edits, DST, vault and export)");
    }

    private static RecurringBlock rule(Tracker tracker,UUID id){
        return tracker.state().recurring().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();
    }

    private static void domain()throws Exception{
        var id=UUID.randomUUID();var activity=UUID.randomUUID();
        refuses(()->new RepeatChange(MONDAY,null,t("09:00"),null),"a skipped week with times");
        refuses(()->new RepeatChange(MONDAY,MONDAY,t("10:00"),t("09:00")),"a week ending before it starts");
        refuses(()->new RepeatChange(MONDAY,MONDAY.plusDays(7),t("09:00"),t("10:00")),"a week moved a whole week");
        refuses(()->new RepeatChange(MONDAY,MONDAY.minusDays(7),t("09:00"),t("10:00")),"a week moved a whole week back");
        check(new RepeatChange(MONDAY,MONDAY.plusDays(6),t("09:00"),t("10:00")).movedTo().equals(MONDAY.plusDays(6)),"Six days is allowed");
        refuses(()->new RecurringBlock(id,activity,DayOfWeek.TUESDAY,t("09:00"),t("10:00"),List.of(RepeatChange.skip(MONDAY))),
            "a change on a day the rule has no block");
        refuses(()->new RecurringBlock(id,activity,DayOfWeek.MONDAY,t("09:00"),t("10:00"),
            List.of(RepeatChange.skip(MONDAY),new RepeatChange(MONDAY,MONDAY,t("11:00"),t("12:00")))),"two changes to one week");
        var later=RepeatChange.skip(MONDAY.plusWeeks(2));var sooner=RepeatChange.skip(MONDAY);
        var ordered=new RecurringBlock(id,activity,DayOfWeek.MONDAY,t("09:00"),t("10:00"),List.of(later,sooner));
        check(ordered.changes().equals(List.of(sooner,later)),"Changes are kept in date order, so equal rules compare equal");
        check(ordered.withChange(MONDAY,null).changes().equals(List.of(later)),"A change can be taken away");
        var many=new ArrayList<RepeatChange>();
        for(int i=0;i<=RecurringBlock.MAX_CHANGES;i++) many.add(RepeatChange.skip(MONDAY.plusWeeks(i)));
        refuses(()->new RecurringBlock(id,activity,DayOfWeek.MONDAY,t("09:00"),t("10:00"),many),"more changed weeks than a rule keeps");
    }

    private static void roundTrips(State state)throws Exception{
        check(state.recurring().stream().anyMatch(r->r.changes().stream().anyMatch(RepeatChange::skipped)==false&&!r.changes().isEmpty()),
            "The state carries a moved week to round-trip");
        var skipping=state.recurring().getFirst().withChange(MONDAY.plusWeeks(5),RepeatChange.skip(MONDAY.plusWeeks(5)));
        var next=new ArrayList<>(state.recurring());next.set(0,skipping);
        var full=state.withRecurring(next);
        check(PortableVault.parse(PortableVault.export(full,Instant.parse("2026-10-01T08:00:00Z"))).equals(full),
            "Skipped and moved weeks survive the portable export");
        var older=PortableVault.export(full,Instant.parse("2026-10-01T08:00:00Z"))
            .replace("\"yoru\": "+PortableVault.FORMAT,"\"yoru\": 9").replaceAll("(?s),\\s*\"changes\": \\[[^\\]]*\\]","");
        check(!older.contains("\"changes\""),"The format-9 copy has no changes");
        check(PortableVault.parse(older).recurring().stream().allMatch(r->r.changes().isEmpty()),
            "A format-9 export reads with every week following its rule");
        var dir=Files.createTempDirectory("yoru-repeat-week");
        try {
            var file=dir.resolve("synthetic.vault");
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){vault.save(full);}
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){
                check(vault.load().equals(full),"Skipped and moved weeks survive reopening the vault");
            }
        } finally {
            try(var files=Files.walk(dir)){for(var f:files.sorted(Comparator.reverseOrder()).toList())Files.delete(f);}
        }
    }
}
