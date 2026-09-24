package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.time.*;
import javax.swing.*;

public final class HabitControlsUiTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static JMenuItem item(JPopupMenu menu,String name){for(var part:menu.getComponents())if(part instanceof JMenuItem i&&name.equals(i.getName()))return i;throw new AssertionError(name);}
    private static class Memory implements Repository{State state=State.empty();public State load(){return state;}public void save(State s){state=s;}public void close(){}}
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{run();}catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("PASS: "+checks+" habit controls UI checks (menus, order, boundary state, time zone form)");
    }
    private static void run()throws Exception{
        Theme.install();var now=Instant.parse("2026-09-24T12:00:00Z");
        var t=new Tracker(new Memory(),Clock.fixed(now,ZoneOffset.UTC));
        t.addHabit("Reading",HabitKind.DAILY,ZoneOffset.UTC,null);t.addHabit("Stretch",HabitKind.DAILY,ZoneOffset.UTC,null);
        t.addHabit("Reset",HabitKind.TIME_SINCE,ZoneOffset.UTC,now.minusSeconds(3600));
        var first=t.state().habits().getFirst();var updates=new int[1];var owner=new JPanel();
        var menu=HabitsPanel.dailyMenu(t,()->updates[0]++,owner,first);
        check(!item(menu,"habit.up."+first.id()).isEnabled(),"First habit cannot move up");
        item(menu,"habit.down."+first.id()).doClick();
        check(t.state().habits().get(1).id().equals(first.id())&&updates[0]==1,"The real move action reorders and refreshes");
        menu=HabitsPanel.dailyMenu(t,()->updates[0]++,owner,first);
        check(!item(menu,"habit.down."+first.id()).isEnabled()&&item(menu,"habit.up."+first.id()).isEnabled(),"Boundary state follows the new order");
        check(item(menu,"habit.zone."+first.id()).isEnabled(),"Time zone is available in the daily menu");
        var form=new HabitZoneForm(first);
        check(form.zones.getSelectedItem().equals("Z"),"A fixed-offset zone already in a vault remains selectable");
        var rendered=(JLabel)form.zones.getRenderer().getListCellRendererComponent(new JList<>(),"Z",0,false,false);
        check(rendered.getText().equals("UTC"),"A stored UTC offset is shown with its familiar name");
        form.zones.setSelectedItem("Pacific/Auckland");form.save(t,first.id());
        check(t.state().habits().get(1).zone().equals("Pacific/Auckland"),"The actual form saves the chosen zone");
        var since=t.state().habits().getLast();
        var periods=HabitsPanel.sinceMenu(t,()->{},owner,since,ZoneOffset.UTC);
        check(!item(periods,"habit.up."+since.id()).isEnabled()&&!item(periods,"habit.down."+since.id()).isEnabled(),"A single time-since habit has no neighbour to move past");
        check(form.zones.getAccessibleContext().getAccessibleName().equals("Time zone"),"The zone control is named for accessibility");
    }
}
