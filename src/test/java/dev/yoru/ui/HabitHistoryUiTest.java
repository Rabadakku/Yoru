package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.time.*;
import java.util.*;
import javax.swing.*;

public final class HabitHistoryUiTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Component find(Container root,String name){
        for(var child:root.getComponents()){
            if(name.equals(child.getName()))return child;
            if(child instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}
        }return null;
    }
    private static class Memory implements Repository{
        State state=State.empty();public State load(){return state;}public void save(State s){state=s;}public void close(){}
    }
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{run();}catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("PASS: "+checks+" habit history UI checks (paging, jumping, editing, refresh, bounds, restart control)");
    }
    private static void run()throws Exception{
        Theme.install();
        var now=Instant.parse("2026-09-23T00:30:00Z");
        var tracker=new Tracker(new Memory(),Clock.fixed(now,ZoneOffset.UTC));
        tracker.addHabit("Reading",HabitKind.DAILY,ZoneId.of("America/Los_Angeles"),null);
        var habit=tracker.state().habits().getFirst();
        var updates=new int[1];
        var history=(DailyHabitHistory)HabitsPanel.historyGrid(tracker,()->updates[0]++,habit);
        check(find(history,"habit.day.2026-09-22")!=null,"Today is determined in the habit's zone");
        check(find(history,"habit.day.2026-09-23")==null,"Tomorrow cannot be corrected early");
        var earlier=(JButton)find(history,"habit.history.earlier");
        earlier.doClick();earlier.doClick();
        check(find(history,"habit.day.2026-07-28")!=null,"Two clicks reach beyond the old four-week limit");
        history.show(LocalDate.of(2024,2,29));
        for(int size:new int[]{100,200}) {
            TextSize.use(size);Theme.apply(ThemeId.MIDNIGHT);
            var scaled=new DailyHabitHistory(tracker,()->{},habit.id());
            scaled.show(LocalDate.of(2024,2,29));
            scaled.setSize(Theme.grow(580),Theme.grow(420));Preview.layout(scaled);
            var day=(JButton)find(scaled,"habit.day.2024-02-29");
            var insets=day.getInsets();
            check(day.getWidth()-insets.left-insets.right>=day.getFontMetrics(day.getFont()).stringWidth(day.getText()),
                "Two-digit dates fit at "+size+"% text");
        }
        TextSize.use(100);Theme.apply(ThemeId.MIDNIGHT);
        var leap=(JButton)find(history,"habit.day.2024-02-29");
        check(leap!=null&&leap.getText().equals("29"),"Jumping reaches a named leap-day cell");
        leap.doClick();
        check(tracker.state().habits().getFirst().checkIns().contains(LocalDate.of(2024,2,29)),"Clicking an old day corrects the stored history");
        check(updates[0]==1&&find(history,"habit.day.2024-02-29")!=null,"Saving refreshes the page without losing the viewed period");
        ((JButton)find(history,"habit.day.2024-02-29")).doClick();
        check(tracker.state().habits().getFirst().checkIns().isEmpty(),"A second click undoes the old check-in");
        history.show(LocalDate.of(2026,9,1));
        ((JButton)find(history,"habit.history.later")).doClick();
        check(find(history,"habit.day.2026-09-22")!=null,"The last forward page stops at today");
        check(!((JButton)find(history,"habit.history.later")).isEnabled(),"Forward is disabled on the current page");
        try{history.show(LocalDate.of(2026,9,23));throw new AssertionError("Future jump accepted");}catch(IllegalArgumentException expected){checks++;}
        try{history.show(LocalDate.of(1899,12,31));throw new AssertionError("Out-of-range jump accepted");}catch(IllegalArgumentException expected){checks++;}
        check(find(history,"habit.history.go")!=null&&find(history,"habit.history.date")!=null,"Jump controls are named and keyboard reachable");
        tracker.addHabit("Reset",HabitKind.TIME_SINCE,ZoneOffset.UTC,now.minus(Duration.ofDays(2)));
        var since=tracker.state().habits().getLast();
        var periods=HabitsPanel.history(tracker,since.id(),ZoneOffset.UTC,()->{});
        check(find(periods,"habit.period.add."+since.id()) instanceof JButton,"The missed restart action is present in history");
    }
}
