package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.*;
import java.util.*;

/**
 * The weekly template editor and the grid that draws it (#4).
 *
 * Lives in dev.yoru.ui to reach WeeklyTemplate and ScheduleGrid. Only the happy
 * paths are driven: the failure paths open a modal dialog, which would hang a
 * headless run rather than fail it. Refusals are covered in RecurringTest,
 * against the tracker.
 */
public final class ScheduleUiTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }

    private static JButton button(Container root,String name){
        for(Component child:root.getComponents()){
            if(child instanceof JButton b&&name.equals(b.getName()))return b;
            if(child instanceof Container nested){var found=button(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    private static <T> T field(Container root,String name,Class<T> type){
        for(Component child:root.getComponents()){
            if(type.isInstance(child)&&name.equals(child.getName()))return type.cast(child);
            if(child instanceof Container nested){var found=field(nested,name,type);if(found!=null)return found;}
        }
        return null;
    }
    private static void layout(Container c){
        c.doLayout();
        for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);
    }

    public static void main(String[] args)throws Exception{
        try {
            SwingUtilities.invokeAndWait(()->{
                try{run();}catch(Exception e){throw new RuntimeException(e);}
            });
        } catch(java.lang.reflect.InvocationTargetException wrapped) {
            // Otherwise a failed assertion arrives as an InvocationTargetException
            // with no message, which reads like the harness broke.
            var cause=wrapped.getCause();
            if(cause instanceof RuntimeException r&&r.getCause() instanceof Exception inner)throw inner;
            if(cause instanceof Error error)throw error;
            throw wrapped;
        }
        System.out.println("PASS: "+checks+" schedule checks (template editor, grid rendering, session editing)");
    }

    private static void run()throws Exception{
        Theme.apply(ThemeId.MIDNIGHT);
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        tracker.addActivity("Calculus",0);
        tracker.addActivity("English",0);
        UUID calculus=tracker.state().activities().getFirst().id();

        var editor=new WeeklyTemplate(tracker,()->{});
        check(button(editor,"repeat.add")!=null,"The editor offers a way to add a repeating block");
        check(field(editor,"repeat.day",JComboBox.class)!=null,"The editor offers a weekday");
        check(field(editor,"repeat.from",JTextField.class).getText().equals("9:00 AM"),"It opens on a sensible default time");
        // Four unnamed controls in a row are the same control to a screen reader
        // until each one says what it is.
        check("Repeat day".equals(field(editor,"repeat.day",JComboBox.class)
                .getAccessibleContext().getAccessibleName()),"The weekday names itself");
        check("Start time".equals(field(editor,"repeat.from",JTextField.class)
                .getAccessibleContext().getAccessibleName()),"The start field names itself");
        check("End time".equals(field(editor,"repeat.to",JTextField.class)
                .getAccessibleContext().getAccessibleName()),"The end field names itself");
        check("Activity".equals(field(editor,"repeat.activity",JComboBox.class)
                .getAccessibleContext().getAccessibleName()),"The activity picker names itself");

        field(editor,"repeat.day",JComboBox.class).setSelectedItem(DayOfWeek.TUESDAY);
        field(editor,"repeat.from",JTextField.class).setText("11am");
        field(editor,"repeat.to",JTextField.class).setText("12:30p");
        button(editor,"repeat.add").doClick();
        check(tracker.state().recurring().size()==1,"Adding reaches the tracker");
        var added=tracker.state().recurring().getFirst();
        check(added.dayOfWeek()==DayOfWeek.TUESDAY,"The chosen weekday is stored");
        check(added.startTime().equals(LocalTime.of(11,0)),"The typed start time is stored, read forgivingly");
        check(added.endTime().equals(LocalTime.of(12,30)),"The typed end time is stored, read forgivingly");
        check(button(editor,"repeat.remove."+added.id())!=null,"The new entry appears with a remove control");
        check(button(editor,"repeat.edit."+added.id())!=null,"and an edit control (#21)");

        // The edit dialog is modal; what it saves is driven through the same call.
        var english=tracker.state().activities().get(1);
        WeeklyTemplate.save(tracker,added.id(),english,DayOfWeek.WEDNESDAY,"1pm","2:15 PM");
        var rescheduled=tracker.state().recurring().getFirst();
        check(rescheduled.id().equals(added.id()),"Editing keeps the entry's identity");
        check(rescheduled.dayOfWeek()==DayOfWeek.WEDNESDAY&&rescheduled.startTime().equals(LocalTime.of(13,0))
            &&rescheduled.endTime().equals(LocalTime.of(14,15))&&rescheduled.activityId().equals(english.id()),
            "and moves its day, times and activity");
        try {
            WeeklyTemplate.save(tracker,added.id(),english,DayOfWeek.WEDNESDAY,"soon","2pm");
            throw new AssertionError("an unreadable time was saved");
        } catch(IllegalArgumentException expected) {
            check(expected.getMessage().startsWith("Start time: "),"An unreadable time names which one: "+expected.getMessage());
        }
        check(tracker.state().recurring().getFirst().equals(rescheduled),"and changes nothing");

        button(editor,"repeat.remove."+added.id()).doClick();
        check(tracker.state().recurring().isEmpty(),"Removing reaches the tracker");
        check(button(editor,"repeat.remove."+added.id())==null,"The removed entry is gone from the list");

        // The grid draws the template beside real sessions without complaint.
        var monday=LocalDate.parse("2026-09-07");
        tracker.repeat(calculus,DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(10,30));
        tracker.repeat(calculus,DayOfWeek.FRIDAY,LocalTime.of(13,0),LocalTime.of(14,0));
        var zone=ZoneId.of("America/New_York");
        tracker.log(calculus,Instant.parse("2026-09-08T18:00:00Z"),Instant.parse("2026-09-08T19:00:00Z"));
        var grid=new ScheduleGrid(tracker.state(),monday,zone,Instant.parse("2026-09-09T15:00:00Z"));
        grid.setSize(900,600);
        layout(grid);
        grid.paint(new BufferedImage(900,600,BufferedImage.TYPE_INT_RGB).getGraphics());
        check(grid.getPreferredSize().height>0,"The grid lays out with a weekly template present");

        // A week with nothing in the template still renders.
        var bare=new ScheduleGrid(State.empty(),monday,zone,Instant.parse("2026-09-09T15:00:00Z"));
        bare.setSize(900,600);
        layout(bare);
        bare.paint(new BufferedImage(900,600,BufferedImage.TYPE_INT_RGB).getGraphics());
        check(bare.getPreferredSize().height>0,"An empty week still renders");

        // A session under the minimum is not drawn, because it is not counted (#34).
        var floorRepo=new Memory();
        Tracker floored=new Tracker(floorRepo,Clock.systemUTC());
        floored.addActivity("Study",0);
        UUID activity=floored.state().activities().getFirst().id();
        var longEnough=Instant.parse("2026-09-08T18:00:00Z");
        floored.log(activity,longEnough.minusSeconds(3600),longEnough);
        // Injected rather than logged: the tracker refuses to create a short
        // session now (#34), so this is how one still arrives — an older vault or
        // a JSON import. The grid must not draw it either way.
        var brief=Instant.parse("2026-09-09T18:00:00Z");
        var tooShort=new Session(UUID.randomUUID(),activity,brief.minusSeconds(120),brief);
        floorRepo.state=floored.state().withCore(floored.state().activities(),
            java.util.stream.Stream.concat(floored.state().sessions().stream(),
                java.util.stream.Stream.of(tooShort)).toList(),
            floored.state().blocks());
        floored=new Tracker(floorRepo,Clock.systemUTC());
        check(floored.state().sessions().size()==2,"An imported short session is present");
        var floorGrid=new ScheduleGrid(floored.state(),monday,zone,Instant.parse("2026-09-10T15:00:00Z"));
        floorGrid.setSize(900,600);layout(floorGrid);
        floorGrid.paint(new BufferedImage(900,600,BufferedImage.TYPE_INT_RGB).getGraphics());
        check(floorGrid.drawn()==1,"Only the session that counts is drawn, found "+floorGrid.drawn());
        // Lower the floor and the short one appears, so this is the setting and
        // not a hardcoded five minutes.
        floored.settings(new Settings(ThemeId.MIDNIGHT,TrainerId.BRENDAN,4,60,DayOfWeek.MONDAY));
        var openGrid=new ScheduleGrid(floored.state(),monday,zone,Instant.parse("2026-09-10T15:00:00Z"));
        openGrid.setSize(900,600);layout(openGrid);
        openGrid.paint(new BufferedImage(900,600,BufferedImage.TYPE_INT_RGB).getGraphics());
        check(openGrid.drawn()==2,"Lowering the minimum brings it back, found "+openGrid.drawn());

        // A recorded session can be corrected from the grid (#33), and opened
        // for deletion (#32) — neither was reachable here before.
        var edited=new UUID[1]; var opened=new UUID[1]; var openedRecorded=new boolean[1];
        var editable=new ScheduleGrid(floored.state(),monday,zone,Instant.parse("2026-09-10T15:00:00Z"),
            new ScheduleGrid.Edits() {
                public void create(Instant a,Instant b) { }
                public void update(UUID id,Instant a,Instant b) { }
                @Override public void updateSession(UUID id,Instant a,Instant b) { edited[0]=id; }
                @Override public void open(UUID id,boolean recorded) { opened[0]=id; openedRecorded[0]=recorded; }
            });
        editable.setSize(900,600);layout(editable);
        editable.paint(new BufferedImage(900,600,BufferedImage.TYPE_INT_RGB).getGraphics());
        UUID recordedId=floored.state().sessions().getFirst().id();
        var spot=editable.pointOn(recordedId);
        check(spot!=null,"A recorded session is drawn somewhere");
        check(recordedId.equals(editable.actionableAt(spot)),"A recorded session can be acted on");

        editable.dispatchEvent(new MouseEvent(editable,MouseEvent.MOUSE_PRESSED,0,0,spot.x,spot.y,1,false));
        editable.dispatchEvent(new MouseEvent(editable,MouseEvent.MOUSE_DRAGGED,0,0,spot.x,spot.y+40,0,false));
        editable.dispatchEvent(new MouseEvent(editable,MouseEvent.MOUSE_RELEASED,0,0,spot.x,spot.y+40,1,false));
        check(recordedId.equals(edited[0]),"Dragging a recorded session asks to correct that session");

        editable.dispatchEvent(new MouseEvent(editable,MouseEvent.MOUSE_PRESSED,0,0,spot.x,spot.y,2,false));
        check(recordedId.equals(opened[0]),"Double-clicking opens the editor");
        check(openedRecorded[0],"and says it is a recorded session, not a plan");

        // Each day is two lanes (#35): plan on the left, actual on the right.
        // Asserted by position, because the whole point is being able to read
        // one against the other rather than through it.
        var laneGrid=new ScheduleGrid(floored.state(),monday,zone,Instant.parse("2026-09-10T15:00:00Z"));
        laneGrid.setSize(1400,700);layout(laneGrid);
        laneGrid.paint(new BufferedImage(1400,700,BufferedImage.TYPE_INT_RGB).getGraphics());
        var recorded=laneGrid.pointOn(floored.state().sessions().stream()
            .filter(x->x.end()!=null&&java.time.Duration.between(x.start(),x.end()).getSeconds()>=300)
            .findFirst().orElseThrow().id());
        check(recorded!=null,"A recorded session is drawn");
        floored.plan(activity,Instant.parse("2026-09-08T13:00:00Z"),Instant.parse("2026-09-08T14:00:00Z"));
        var bothGrid=new ScheduleGrid(floored.state(),monday,zone,Instant.parse("2026-09-10T15:00:00Z"));
        bothGrid.setSize(1400,700);layout(bothGrid);
        bothGrid.paint(new BufferedImage(1400,700,BufferedImage.TYPE_INT_RGB).getGraphics());
        var planPoint=bothGrid.pointOn(floored.state().blocks().getFirst().id());
        var actualPoint=bothGrid.pointOn(floored.state().sessions().stream()
            .filter(x->x.end()!=null&&java.time.Duration.between(x.start(),x.end()).getSeconds()>=300)
            .findFirst().orElseThrow().id());
        check(planPoint!=null&&actualPoint!=null,"Both a plan and a session are drawn");
        check(planPoint.x<actualPoint.x,"The plan sits to the left of what actually happened");

        // Occurrences land on the right days of the week being shown.
        var occurrences=Analytics.occurrences(tracker.state(),monday,zone);
        check(occurrences.size()==2,"Both rules appear once in the week");
        check(occurrences.getFirst().start().atZone(zone).getDayOfWeek()==DayOfWeek.MONDAY,"Monday's rule lands on Monday");
        check(occurrences.get(1).start().atZone(zone).getDayOfWeek()==DayOfWeek.FRIDAY,"Friday's rule lands on Friday");
    }
}
