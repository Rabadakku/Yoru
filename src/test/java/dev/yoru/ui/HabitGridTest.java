package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.time.*;
import java.util.UUID;

/**
 * A daily habit's grid stands in weekday columns (#9).
 *
 * The grid is the page: whether a habit is kept on weekends or only on work
 * days is the question it answers, and it can only answer it if every column
 * is one weekday. Lives in dev.yoru.ui because HabitsPanel is package-private.
 */
public final class HabitGridTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }

    /** The day cell for a date, found the way a screen reader finds it: by name. */
    private static JButton cell(Container root,LocalDate date){
        for(Component child:root.getComponents()){
            if(child instanceof JButton b&&("habit.day."+date).equals(b.getName()))return b;
            if(child instanceof Container nested){var found=cell(nested,date);if(found!=null)return found;}
        }
        return null;
    }

    /** Which column of its grid a cell sits in. */
    private static int column(JButton cell){
        var grid=cell.getParent();
        return grid.getComponentZOrder(cell)%7;
    }

    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            try{run();}catch(Exception e){throw new RuntimeException(e);}
        });
        System.out.println("PASS: "+checks+" habit grid checks (weekday columns, week start, no future days)");
    }

    private static void run()throws Exception{
        Theme.apply(ThemeId.MIDNIGHT);
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        tracker.addHabit("Evening reset",HabitKind.DAILY,ZoneId.systemDefault(),null);
        UUID habit=tracker.state().habits().getFirst().id();
        var today=LocalDate.now();
        tracker.checkIn(habit,today,true);
        tracker.checkIn(habit,today.minusDays(8),true);

        for(DayOfWeek start:DayOfWeek.values()){
            var settings=tracker.state().settings();
            tracker.settings(new Settings(settings.theme(),settings.trainer(),settings.dailyGoalHours(),
                settings.minSessionSeconds(),start));
            var page=HabitsPanel.view(tracker,()->{});

            var oldest=today.with(java.time.temporal.TemporalAdjusters.previousOrSame(start)).minusWeeks(3);
            check(cell(page,oldest)!=null,"The grid reaches back four weeks, to "+oldest);
            check(cell(page,oldest.minusDays(1))==null,"and no further");
            check(cell(page,today)!=null,"Today is on the grid");
            check(cell(page,today.plusDays(1))==null,"but a day that has not happened yet is not");

            // Every cell of a column falls on the same weekday, and the first
            // column is the week start the vault is set to.
            for(var date=oldest;!date.isAfter(today);date=date.plusDays(1)){
                var day=cell(page,date);
                check(day!=null,"Every day from "+oldest+" to today has a cell: "+date);
                int expected=(int)java.time.temporal.ChronoUnit.DAYS.between(
                    date.with(java.time.temporal.TemporalAdjusters.previousOrSame(start)),date);
                check(column(day)==expected,
                    "A week starting "+start+" puts "+date.getDayOfWeek()+" in column "+expected
                    +", not "+column(day));
            }
            check(cell(page,today).getParent().getComponentCount()==28,
                "The grid keeps its four rows of seven whatever the week holds");
        }

        // The grid is still the way a day is corrected.
        var page=HabitsPanel.view(tracker,()->{});
        var yesterday=cell(page,LocalDate.now().minusDays(1));
        yesterday.doClick();
        check(tracker.state().habits().getFirst().checkIns().contains(LocalDate.now().minusDays(1)),
            "Clicking a day checks it off");
        yesterday=cell(HabitsPanel.view(tracker,()->{}),LocalDate.now().minusDays(1));
        yesterday.doClick();
        check(!tracker.state().habits().getFirst().checkIns().contains(LocalDate.now().minusDays(1)),
            "and clicking it again takes it back");
    }
}
