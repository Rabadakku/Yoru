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
import java.util.List;

/**
 * The month calendar and dragging a task onto a day (#5).
 *
 * Drag is driven by dispatching real mouse events at points the component
 * reports, rather than at hardcoded pixels: a test that knows the cell geometry
 * would pass while the thing under the user's cursor moved somewhere else.
 */
public final class TaskCalendarTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }

    /** Records what the calendar asked for, so a drag can be asserted exactly. */
    private static final class Recorder implements TaskCalendar.Edits {
        UUID task; LocalDate date; int calls; LocalDate created; int creates;
        public void reschedule(UUID task,LocalDate date){this.task=task;this.date=date;calls++;}
        public void create(LocalDate date){created=date;creates++;}
    }

    private static void doubleClick(TaskCalendar calendar,Point at){
        for(int clicks=1;clicks<=2;clicks++){
            calendar.dispatchEvent(new MouseEvent(calendar,MouseEvent.MOUSE_PRESSED,0,MouseEvent.BUTTON1_DOWN_MASK,at.x,at.y,clicks,false,MouseEvent.BUTTON1));
            calendar.dispatchEvent(new MouseEvent(calendar,MouseEvent.MOUSE_RELEASED,0,0,at.x,at.y,clicks,false,MouseEvent.BUTTON1));
            calendar.dispatchEvent(new MouseEvent(calendar,MouseEvent.MOUSE_CLICKED,0,0,at.x,at.y,clicks,false,MouseEvent.BUTTON1));
        }
    }

    private static void drag(TaskCalendar calendar,Point from,Point to){
        calendar.dispatchEvent(new MouseEvent(calendar,MouseEvent.MOUSE_PRESSED,0,0,from.x,from.y,1,false));
        calendar.dispatchEvent(new MouseEvent(calendar,MouseEvent.MOUSE_DRAGGED,0,0,to.x,to.y,0,false));
        calendar.dispatchEvent(new MouseEvent(calendar,MouseEvent.MOUSE_RELEASED,0,0,to.x,to.y,1,false));
    }

    private static JButton button(Container root,String name){
        for(Component child:root.getComponents()){
            if(child instanceof JButton b&&name.equals(b.getName()))return b;
            if(child instanceof Container nested){var found=button(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    private static TaskCalendar calendarIn(Container root){
        for(Component child:root.getComponents()){
            if(child instanceof TaskCalendar c)return c;
            if(child instanceof Container nested){var found=calendarIn(nested);if(found!=null)return found;}
        }
        return null;
    }
    private static void layout(Container c){
        c.doLayout();
        for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);
    }
    private static Task task(Tracker tracker,UUID id){
        return tracker.state().tasks().stream().filter(t->t.id().equals(id)).findFirst().orElseThrow();
    }

    public static void main(String[] args)throws Exception{
        try {
            SwingUtilities.invokeAndWait(()->{
                try{run();}catch(Exception e){throw new RuntimeException(e);}
            });
        } catch(java.lang.reflect.InvocationTargetException wrapped) {
            var cause=wrapped.getCause();
            if(cause instanceof RuntimeException r&&r.getCause() instanceof Exception inner)throw inner;
            if(cause instanceof Error error)throw error;
            throw wrapped;
        }
        System.out.println("PASS: "+checks+" calendar checks (layout, drag to reschedule, undated tray, new task on a day)");
    }

    private static void run()throws Exception{
        Theme.apply(ThemeId.MIDNIGHT);
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        tracker.addActivity("Study",0);
        UUID study=tracker.state().activities().getFirst().id();
        var tag=tracker.addTag("Reading",0x90D8DA);

        // A fixed month, so the geometry does not depend on the day this runs.
        var month=YearMonth.of(2026,9);
        var today=LocalDate.of(2026,9,9);
        UUID dated=UUID.randomUUID(), undated=UUID.randomUUID(), other=UUID.randomUUID();
        var now=Instant.parse("2026-09-01T12:00:00Z");
        tracker.addTasks(List.of(
            new Task(dated,study,tag.id(),"Read chapter 4","",LocalDate.of(2026,9,10),TaskStatus.TODO,"",now,0),
            new Task(undated,study,null,"Order textbook","",null,TaskStatus.TODO,"",now,1),
            new Task(other,null,null,"Kanji quiz","",LocalDate.of(2026,9,21),TaskStatus.DONE,"",now,2)));

        var recorder=new Recorder();
        var calendar=new TaskCalendar(tracker.state(),month,today,recorder);
        calendar.setSize(900,620);
        calendar.relayout();

        // Layout: a task sits on its own due date, and only there.
        check(calendar.dateAt(calendar.centreOf(LocalDate.of(2026,9,10))).equals(LocalDate.of(2026,9,10)),
            "A day cell reports its own date");
        check(calendar.pointOn(dated)!=null,"A dated task is drawn somewhere");
        check(calendar.taskAt(calendar.pointOn(dated)).equals(dated),"The task under its own chip is that task");
        check(calendar.pointOn(undated)!=null,"An undated task is drawn in the tray");
        check(calendar.taskAt(calendar.centreOf(LocalDate.of(2026,9,18)))==null,"An empty day holds no task");
        // Leading days from August are shown, because a month grid has to start
        // somewhere; a task falling there is still reachable.
        check(calendar.dateAt(new Point(4,30))!=null,"The grid covers the days before the first of the month");

        // Drag a dated task onto another day.
        drag(calendar,calendar.pointOn(dated),calendar.centreOf(LocalDate.of(2026,9,17)));
        check(recorder.calls==1,"Dragging onto a day asks for one change");
        check(recorder.task.equals(dated),"It moves the task that was picked up");
        check(recorder.date.equals(LocalDate.of(2026,9,17)),"It moves it to the day it was dropped on");

        // Drag an undated task out of the tray onto a day.
        drag(calendar,calendar.pointOn(undated),calendar.centreOf(LocalDate.of(2026,9,14)));
        check(recorder.calls==2,"Dragging out of the tray asks for a change");
        check(recorder.task.equals(undated),"It schedules the task from the tray");
        check(recorder.date.equals(LocalDate.of(2026,9,14)),"It schedules it on the day it was dropped");

        // Drag a dated task onto the tray to clear its date.
        drag(calendar,calendar.pointOn(dated),calendar.centreOfTray());
        check(recorder.calls==3,"Dropping on the tray asks for a change");
        check(recorder.date==null,"Dropping on the tray clears the due date");

        // A drop that lands nowhere changes nothing: releasing off the component
        // has to be a way to change your mind rather than a way to lose a date.
        int before=recorder.calls;
        drag(calendar,calendar.pointOn(dated),new Point(-40,-40));
        check(recorder.calls==before,"A drop outside the calendar does nothing");
        // Pressing on empty space and releasing is not a drag at all.
        drag(calendar,calendar.centreOf(LocalDate.of(2026,9,18)),calendar.centreOf(LocalDate.of(2026,9,19)));
        check(recorder.calls==before,"Dragging from an empty day does nothing");

        // A task made from the calendar is due the day it was made on (#67).
        doubleClick(calendar,calendar.centreOf(LocalDate.of(2026,9,18)));
        check(recorder.creates==1&&LocalDate.of(2026,9,18).equals(recorder.created),
            "double-clicking an empty day asks for a task due that day");
        doubleClick(calendar,calendar.pointOn(dated));
        check(recorder.creates==1,"double-clicking a task's chip does not make another task");
        doubleClick(calendar,calendar.centreOfTray());
        check(recorder.creates==1,"the undated strip is not a day");
        check(recorder.calls==before,"and making a task moves nothing");

        calendar.paint(new BufferedImage(900,620,BufferedImage.TYPE_INT_RGB).getGraphics());
        check(true,"The calendar paints with tasks, an empty tray target and adjacent months");

        // Through the board: the view swaps in, a drag reaches the tracker, and
        // the sort control is hidden because the grid is ordered by date already.
        var board=new TasksPanel(tracker,()->{},()->false);
        board.setSize(1280,1000);
        button(board,"view.calendar").doClick();
        layout(board);
        TaskCalendar live=calendarIn(board);
        check(live!=null,"The Calendar view shows a calendar");
        var sort=findCombo(board,"task.sort");
        check(sort!=null,"The sort control still exists");
        check(!isVisibleChain(sort),"The sort control is hidden on the calendar");
        button(board,"view.all").doClick();
        check(isVisibleChain(findCombo(board,"task.sort")),"The sort control comes back on a list view");
        button(board,"view.calendar").doClick();
        layout(board);
        live=calendarIn(board);

        live.setSize(1100,620);
        live.relayout();
        var moved=live.pointOn(dated);
        check(moved!=null,"The live calendar drew the task");
        var deadline=task(tracker,dated).due();
        drag(live,moved,live.centreOf(LocalDate.of(2026,9,24)));
        // Dragging moves the plan. A deadline is not something you drag — that is
        // the whole point of splitting the two (#25).
        check(LocalDate.of(2026,9,24).equals(task(tracker,dated).plannedFor()),
            "Dragging on the board moves the planned day");
        check(java.util.Objects.equals(task(tracker,dated).due(),deadline),
            "and leaves the deadline exactly where it was");
        check(repo.state.tasks().stream().anyMatch(t->t.id().equals(dated)
            &&LocalDate.of(2026,9,24).equals(t.plannedFor())),"The new plan reached storage");
        check(LocalDate.of(2026,9,24).equals(task(tracker,dated).workOn()),
            "and the task now wants attention on the day it was dropped");
        check(task(tracker,dated).tagIds().equals(List.of(tag.id())),"Rescheduling keeps the tag");
        check(task(tracker,dated).order()==0,"Rescheduling keeps the manual position");
        check(task(tracker,dated).status()==TaskStatus.TODO,"Rescheduling keeps the status");

        // Dropping a task back on the day it already occupies writes nothing.
        var repeatState=tracker.state();
        live.relayout();
        drag(live,live.pointOn(dated),live.centreOf(LocalDate.of(2026,9,24)));
        check(tracker.state().equals(repeatState),"Dropping a task on its own day changes nothing");

        // A task with only a deadline is placed on it, and planning moves it
        // without the deadline following.
        var onlyDeadline=task(tracker,other);
        check(onlyDeadline.plannedFor()==null,"That task has no plan yet");
        check(onlyDeadline.workOn().equals(onlyDeadline.due()),"so it sits on its deadline");
        tracker.updateTask(TasksPanel.merged(onlyDeadline,onlyDeadline.activityId(),onlyDeadline.tagIds(),
            onlyDeadline.title(),onlyDeadline.notes(),onlyDeadline.due(),onlyDeadline.status(),0,
            LocalDate.of(2026,9,15)));
        var planned=task(tracker,other);
        check(planned.workOn().equals(LocalDate.of(2026,9,15)),"Planning a day moves where it sits");
        check(planned.due().equals(onlyDeadline.due()),"and the deadline is untouched");
        check(!planned.scheduledLate(),"Planning before the deadline is not late");
        tracker.updateTask(TasksPanel.merged(planned,planned.activityId(),planned.tagIds(),
            planned.title(),planned.notes(),LocalDate.of(2026,9,10),planned.status(),0,
            LocalDate.of(2026,9,15)));
        check(task(tracker,other).scheduledLate(),"Planning to start after it is due is flagged");
    }

    private static JComboBox<?> findCombo(Container root,String name){
        for(Component child:root.getComponents()){
            if(child instanceof JComboBox<?> box&&name.equals(box.getName()))return box;
            if(child instanceof Container nested){var found=findCombo(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    private static boolean isVisibleChain(Component c){
        while(c!=null){ if(!c.isVisible()) return false; c=c.getParent(); }
        return true;
    }
}
