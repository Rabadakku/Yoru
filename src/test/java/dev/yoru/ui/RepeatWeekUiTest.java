package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * One week of a weekly repeat from the Schedule (#59): the grid picks up a
 * weekly box only where the page allows it, a drag moves that week alone, a
 * double-click asks about that week, and the list under the grid does all of
 * it from the keyboard, named for a screen reader.
 */
public final class RepeatWeekUiTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static final class Memory implements Repository {
        State state=State.empty();boolean fail;
        public State load(){return state;}
        public void save(State next)throws IOException{if(fail)throw new IOException("Synthetic save failure");state=next;}
        public void close(){}
    }
    private static JButton button(Container root,String name){
        for(Component child:root.getComponents()){
            if(child instanceof JButton b&&name.equals(b.getName()))return b;
            if(child instanceof Container nested){var found=button(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    private static String text(Container root){
        var out=new StringBuilder();
        for(Component child:root.getComponents()){
            if(child instanceof JLabel l)out.append(l.getText()).append('\n');
            if(child instanceof Container nested)out.append(text(nested));
        }
        return out.toString();
    }
    private static ScheduleGrid grid(State state,LocalDate week,ZoneId zone,ScheduleGrid.Edits edits){
        var grid=new ScheduleGrid(state,week,zone,Instant.parse("2026-10-07T12:00:00Z"),edits);
        grid.setSize(1400,700);Preview.layout(grid);
        grid.paint(new BufferedImage(1400,700,BufferedImage.TYPE_INT_RGB).getGraphics());
        return grid;
    }

    public static void main(String[] args)throws Exception{
        try {
            SwingUtilities.invokeAndWait(()->{try{run();}catch(Exception e){throw new RuntimeException(e);}});
        } catch(java.lang.reflect.InvocationTargetException wrapped) {
            var cause=wrapped.getCause();
            if(cause instanceof RuntimeException r&&r.getCause() instanceof Exception inner)throw inner;
            if(cause instanceof Error error)throw error;
            throw wrapped;
        }
        System.out.println("PASS: "+checks+" repeat week UI checks (grid drag and double-click, keyboard list, names, failures)");
    }

    private static void run()throws Exception{
        Theme.apply(ThemeId.MIDNIGHT);
        var zone=ZoneId.of("Europe/Berlin");
        var monday=LocalDate.of(2026,10,5);
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"),zone));
        tracker.addActivity("Invented lecture",0);
        var lecture=tracker.repeat(tracker.state().activities().getFirst().id(),DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(10,30));

        // A grid that only draws leaves the weekly box inert, as before.
        var inert=grid(tracker.state(),monday,zone,new ScheduleGrid.Edits(){
            public void create(Instant a,Instant b){}
            public void update(UUID id,Instant a,Instant b){}
        });
        var spot=inert.pointOn(lecture.id(),monday);
        check(spot!=null,"The weekly box is drawn on its Monday");
        check(inert.actionableAt(spot)==null,"Where the page does not change weeks, the box cannot be picked up");

        // The Schedule's grid moves that week alone.
        var moved=new Object[3];var opened=new Object[2];
        var live=grid(tracker.state(),monday,zone,new ScheduleGrid.Edits(){
            public void create(Instant a,Instant b){}
            public void update(UUID id,Instant a,Instant b){throw new AssertionError("A weekly box is not a planned block");}
            @Override public void moveRepeat(UUID rule,LocalDate week,Instant a,Instant b){moved[0]=rule;moved[1]=week;moved[2]=a;}
            @Override public void openRepeat(UUID rule,LocalDate week){opened[0]=rule;opened[1]=week;}
            @Override public boolean repeatsEditable(){return true;}
        });
        spot=live.pointOn(lecture.id(),monday);
        check(lecture.id().equals(live.actionableAt(spot)),"On the Schedule the weekly box can be picked up");
        live.dispatchEvent(new MouseEvent(live,MouseEvent.MOUSE_PRESSED,0,0,spot.x,spot.y,1,false));
        live.dispatchEvent(new MouseEvent(live,MouseEvent.MOUSE_DRAGGED,0,0,spot.x,spot.y+60,0,false));
        live.dispatchEvent(new MouseEvent(live,MouseEvent.MOUSE_RELEASED,0,0,spot.x,spot.y+60,1,false));
        check(lecture.id().equals(moved[0])&&monday.equals(moved[1]),"Dragging asks to move that rule's week, named by its Monday");
        check(((Instant)moved[2]).isAfter(monday.atTime(9,0).atZone(zone).toInstant()),"to a later time");
        live.dispatchEvent(new MouseEvent(live,MouseEvent.MOUSE_PRESSED,0,0,spot.x,spot.y,2,false));
        check(lecture.id().equals(opened[0])&&monday.equals(opened[1]),"Double-clicking asks about that week");
        check(live.getToolTipText(new MouseEvent(live,MouseEvent.MOUSE_MOVED,0,0,spot.x,spot.y,0,false)).contains("this week only"),
            "The tooltip says a drag moves this week only");

        // The drag, as the app saves it.
        var shell=new TestShell(tracker,zone);
        RepeatWeek.moveOnGrid(shell,lecture.id(),monday,monday.atTime(11,0).atZone(zone).toInstant(),
            monday.atTime(12,30).atZone(zone).toInstant());
        var change=tracker.state().recurring().getFirst().changeOn(monday);
        check(change!=null&&change.start().equals(LocalTime.of(11,0))&&change.end().equals(LocalTime.of(12,30)),
            "A drag saves that week's new time");
        RepeatWeek.moveOnGrid(shell,lecture.id(),monday,monday.atTime(22,0).atZone(zone).toInstant(),
            monday.plusDays(1).atStartOfDay(zone).toInstant());
        check(tracker.state().recurring().getFirst().changeOn(monday).end().equals(LocalTime.of(23,59)),
            "A box dragged to the foot of the day ends a minute before midnight");
        var redrawn=grid(tracker.state(),monday,zone,null);
        check(redrawn.pointOn(lecture.id(),monday).y>spot.y,"The changed week is drawn where it was moved to");

        // The list under the grid.
        var card=RepeatWeek.card(shell,monday);
        var restore=button(card,"repeatWeek.restore."+lecture.id()+"."+monday);
        var skip=button(card,"repeatWeek.skip."+lecture.id()+"."+monday);
        check(restore!=null&&skip!=null&&button(card,"repeatWeek.change."+lecture.id()+"."+monday)!=null,
            "A changed week can be restored, changed again or skipped from the list");
        check(text(card).contains("Moved from Mon 09:00"),"The list says where the week came from");
        check(restore.getAccessibleContext().getAccessibleName().equals("Restore Invented lecture on Mon, Oct 5 to every week's time"),
            "Restore names what it restores: "+restore.getAccessibleContext().getAccessibleName());
        skip.doClick();
        check(tracker.state().recurring().getFirst().changeOn(monday).skipped(),"Skip from the list skips that week");
        card=RepeatWeek.card(shell,monday);
        check(text(card).contains("Skipped this week"),"A skipped week stays listed, so it can be put back");
        check(button(card,"repeatWeek.skip."+lecture.id()+"."+monday)==null,"and it cannot be skipped twice");
        var nextWeek=RepeatWeek.card(shell,monday.plusWeeks(1));
        check(button(nextWeek,"repeatWeek.restore."+lecture.id()+"."+monday.plusWeeks(1))==null
            &&text(nextWeek).contains("Every Monday"),"The next week follows the rule and has nothing to restore");

        // A failed save is reported and changes nothing.
        var before=tracker.state();
        repo.fail=true;
        button(card,"repeatWeek.restore."+lecture.id()+"."+monday).doClick();
        check(shell.errors.size()==1&&tracker.state().equals(before),"A failed restore is reported and keeps the skip");
        repo.fail=false;
        button(card,"repeatWeek.restore."+lecture.id()+"."+monday).doClick();
        check(tracker.state().recurring().getFirst().changes().isEmpty(),"Restore from the list puts the week back");

        // The change form's save, without its modal dialog.
        RepeatWeek.save(tracker,lecture.id(),monday,monday.plusDays(2),"2pm","3:15 PM");
        change=tracker.state().recurring().getFirst().changeOn(monday);
        check(change.movedTo().equals(monday.plusDays(2))&&change.start().equals(LocalTime.of(14,0))
            &&change.end().equals(LocalTime.of(15,15)),"The form moves the week to another day and time");
        try {
            RepeatWeek.save(tracker,lecture.id(),monday,monday,"later","3pm");
            throw new AssertionError("An unreadable time was saved");
        } catch(IllegalArgumentException expected) {
            check(expected.getMessage().startsWith("Start time: "),"An unreadable time names which one");
        }

        // With no repeats at all, the card says so rather than showing nothing.
        var empty=RepeatWeek.card(new TestShell(new Tracker(new Memory(),Clock.systemUTC()),zone),monday);
        check(text(empty).contains("Nothing repeats this week."),"An empty week says so");

        // Every theme draws the card.
        for(var theme:ThemeId.values()){
            Theme.apply(theme);
            var themed=RepeatWeek.card(shell,monday);
            themed.setSize(900,300);Preview.layout(themed);
            themed.paint(new BufferedImage(900,300,BufferedImage.TYPE_INT_RGB).getGraphics());
            check(themed.getPreferredSize().height>0,"The card lays out in "+theme);
        }
        Theme.apply(ThemeId.MIDNIGHT);
    }
}
