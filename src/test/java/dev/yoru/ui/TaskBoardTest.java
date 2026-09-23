package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * The task board and tag manager (#2, #3).
 *
 * Lives in dev.yoru.ui so it can reach TasksPanel and TagEditor, which are
 * package-private. The board is driven through its real controls rather than
 * its internals: filtering, sorting and reordering are the behaviour, and a
 * panel that renders the right rows for the wrong reason is still broken.
 */
public final class TaskBoardTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private interface Action {void run()throws Exception;}
    private static void rejects(Action action,String why)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IOException expected){checks++;return;}
        throw new AssertionError(why);
    }

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
    private static JComboBox<?> combo(Container root,String name){
        for(Component child:root.getComponents()){
            if(child instanceof JComboBox<?> box&&name.equals(box.getName()))return box;
            if(child instanceof Container nested){var found=combo(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    /** One entry of a task's ⋯ menu, found by name the way the buttons are. */
    private static JMenuItem item(TasksPanel board,UUID task,String prefix){
        var menu=board.rowMenu(task);
        if(menu==null)return null;
        for(Component child:menu.getComponents())
            if(child instanceof JMenuItem entry&&(prefix+task).equals(entry.getName()))return entry;
        return null;
    }
    private static Component named(Container root,String name){
        for(Component child:root.getComponents()){
            if(name.equals(child.getName()))return child;
            if(child instanceof Container nested){var found=named(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    /** Task ids in the order their rows are laid out. */
    private static void collect(Container root,List<String> into){
        for(Component child:root.getComponents()){
            if(child instanceof JButton b&&b.getName()!=null&&b.getName().startsWith("task.status."))
                into.add(b.getName().substring("task.status.".length()));
            if(child instanceof Container nested)collect(nested,into);
        }
    }
    private static List<String> shown(Container panel){
        var ids=new ArrayList<String>();collect(panel,ids);return ids;
    }
    /** A row is the panel its status chip sits in. */
    private static Container rowOf(Container board,UUID task){
        var chip=button(board,"task.status."+task);
        return chip==null?null:(Container)chip.getParent();
    }
    /** Drags one row onto another's slot, in real mouse events. */
    private static void dragRow(Container board,UUID from,UUID onto){
        var source=rowOf(board,from);
        var target=rowOf(board,onto);
        int y=(target.getY()+target.getHeight()/2)-source.getY();
        source.dispatchEvent(new MouseEvent(source,MouseEvent.MOUSE_PRESSED,0,0,10,5,1,false));
        source.dispatchEvent(new MouseEvent(source,MouseEvent.MOUSE_DRAGGED,0,0,10,y,0,false));
        source.dispatchEvent(new MouseEvent(source,MouseEvent.MOUSE_RELEASED,0,0,10,y,1,false));
    }
    private static void layoutAll(Container c){
        c.doLayout();
        for(Component child:c.getComponents())if(child instanceof Container nested)layoutAll(nested);
    }

    private static UUID shownTask(Container board,int index){
        return UUID.fromString(shown(board).get(index));
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
            // invokeAndWait buries the assertion two layers down, which turns a
            // clear failure into an InvocationTargetException with no message.
            var cause=wrapped.getCause();
            if(cause instanceof RuntimeException r&&r.getCause() instanceof Exception inner)throw inner;
            if(cause instanceof Error error)throw error;
            throw wrapped;
        }
        // A new task is due the day it is made; an existing one keeps its own date (#67).
        var made=java.time.LocalDate.of(2026,9,23);
        check(TasksPanel.dueField(null,made).value().equals(made),
            "a new task's form opens with the day it is made");
        // Today is the owner's own day, not the UTC one: 00:00:30 in Tokyo is
        // still the afternoon before in UTC, and the task is written down today.
        var afterMidnight=Clock.fixed(Instant.parse("2026-09-22T15:00:30Z"),ZoneId.of("Asia/Tokyo"));
        check(TasksPanel.today(afterMidnight).equals(java.time.LocalDate.of(2026,9,23)),
            "just after midnight, a new task is due on the new day in the owner's zone");
        var beforeMidnight=Clock.fixed(Instant.parse("2026-09-23T03:59:30Z"),ZoneId.of("America/New_York"));
        check(TasksPanel.today(beforeMidnight).equals(java.time.LocalDate.of(2026,9,22)),
            "just before midnight it is still the old day, even when UTC has moved on");
        var dated = new Task(java.util.UUID.randomUUID(), null, null, "Dated", "",
            java.time.LocalDate.now().plusDays(3), TaskStatus.TODO, "Test", java.time.Instant.now(), 0);
        check(TasksPanel.dueField(dated,made).value().equals(dated.due()), "editing a task shows the date it has");
        var undated = new Task(java.util.UUID.randomUUID(), null, null, "Undated", "", null,
            TaskStatus.TODO, "Test", java.time.Instant.now(), 1);
        check(TasksPanel.dueField(undated,made).value() == null, "and a task with no date still has none");

        System.out.println("PASS: "+checks+" board checks (views, sorting, status, reorder, tags)");
    }

    private static void run()throws Exception{
        Theme.apply(ThemeId.MIDNIGHT);
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        tracker.addActivity("Study",0);
        UUID study=tracker.state().activities().getFirst().id();
        var today=LocalDate.now();
        var now=Instant.now();

        var cs=tracker.addTag("Reading",0x90D8DA);
        var jpn=tracker.addTag("Language",0xE8B24C);
        check(tracker.state().tags().size()==2,"Tags are created");
        rejects(()->tracker.addTag("reading",0x111111),"Tag names are unique regardless of case");
        rejects(()->tracker.addTag("Bad",0x1FFFFFF),"Tag colours are 24-bit RGB");

        // overdue / today / future / none, mixed statuses and tags.
        UUID overdue=UUID.randomUUID(),dueToday=UUID.randomUUID(),
             later=UUID.randomUUID(),undated=UUID.randomUUID(),finished=UUID.randomUUID();
        tracker.addTasks(List.of(
            new Task(overdue,study,cs.id(),"Beta lab","",today.minusDays(2),TaskStatus.DOING,"",now,0),
            new Task(dueToday,study,jpn.id(),"Alpha kanji","",today,TaskStatus.TODO,"",now,1),
            new Task(later,null,null,"Delta reading","",today.plusDays(9),TaskStatus.TODO,"",now,2),
            new Task(undated,study,null,"Gamma email","",null,TaskStatus.TODO,"",now,3),
            new Task(finished,null,cs.id(),"Epsilon textbook","",today.minusDays(1),TaskStatus.DONE,"",now,4)));

        var board=new TasksPanel(tracker,()->{},()->false);
        board.setSize(1280,900);

        // Views.
        check(shown(board).size()==5,"The All view shows every task");
        button(board,"view.today").doClick();
        var dueNow=shown(board);
        check(dueNow.size()==2,"Due today shows today and overdue, found "+dueNow.size());
        check(dueNow.contains(overdue.toString())&&dueNow.contains(dueToday.toString()),
            "Due today covers the overdue task as well as today's");
        check(!dueNow.contains(finished.toString()),"Due today leaves finished work out");
        button(board,"view.soon").doClick();
        var soon=shown(board);
        check(soon.contains(overdue.toString()),"Next 5 days keeps overdue work in sight");
        check(soon.contains(dueToday.toString()),"Next 5 days includes today");
        check(!soon.contains(later.toString()),"Next 5 days stops short of work due further out");
        check(!soon.contains(undated.toString()),"Next 5 days leaves undated work out");
        check(!soon.contains(finished.toString()),"Next 5 days leaves finished work out");
        // The boundary, stated rather than left to the fixture's due dates: the
        // fifth day is inside the window and the sixth is not.
        tracker.updateTask(TasksPanel.merged(task(tracker,later),task(tracker,later).activityId(),task(tracker,later).tagId(),task(tracker,later).title(),task(tracker,later).notes(),today.plusDays(5),task(tracker,later).status(),0));
        button(board,"view.soon").doClick();
        check(shown(board).contains(later.toString()),"The fifth day is inside the window");
        tracker.updateTask(TasksPanel.merged(task(tracker,later),task(tracker,later).activityId(),task(tracker,later).tagId(),task(tracker,later).title(),task(tracker,later).notes(),today.plusDays(6),task(tracker,later).status(),0));
        button(board,"view.soon").doClick();
        check(!shown(board).contains(later.toString()),"The sixth day is outside it");
        tracker.updateTask(TasksPanel.merged(task(tracker,later),task(tracker,later).activityId(),task(tracker,later).tagId(),task(tracker,later).title(),task(tracker,later).notes(),today.plusDays(9),task(tracker,later).status(),0));
        button(board,"view.done").doClick();
        check(shown(board).equals(List.of(finished.toString())),"Completed shows only finished work");
        button(board,"view.open").doClick();
        check(shown(board).size()==4&&!shown(board).contains(finished.toString()),"Open excludes finished work");
        button(board,"view.all").doClick();
        check(shown(board).size()==5,"Returning to All restores every task");

        // Sorting.
        var sort=combo(board,"task.sort");
        check(sort!=null,"The board offers a sort control");
        check(shown(board).equals(List.of(overdue,dueToday,later,undated,finished).stream().map(UUID::toString).toList()),
            "My order follows the stored order");
        sort.setSelectedItem(sortNamed(sort,"Title"));
        check(shown(board).getFirst().equals(dueToday.toString()),"Title sort leads with Alpha");
        check(shown(board).getLast().equals(undated.toString()),"Title sort ends with Gamma");
        sort.setSelectedItem(sortNamed(sort,"Due date"));
        var byDue=shown(board);
        check(byDue.getFirst().equals(overdue.toString()),"Due sort leads with the oldest date");
        check(byDue.getLast().equals(undated.toString()),"Due sort puts undated work last");
        sort.setSelectedItem(sortNamed(sort,"Status"));
        check(shown(board).getLast().equals(finished.toString()),"Status sort puts done last");

        // Reordering is only offered where it means something.
        check(!item(board,later,"task.up.").isEnabled(),"A sorted board cannot be reordered by hand");
        sort.setSelectedItem(sortNamed(sort,"My order"));
        button(board,"view.open").doClick();
        check(!item(board,later,"task.up.").isEnabled(),"A filtered board cannot be reordered by hand");
        button(board,"view.all").doClick();
        check(!item(board,overdue,"task.up.").isEnabled(),"The first row cannot move up");
        check(!item(board,finished,"task.down.").isEnabled(),"The last row cannot move down");
        check(item(board,dueToday,"task.up.").isEnabled(),"Reordering is offered on the unfiltered board");
        item(board,dueToday,"task.up.").doClick();
        check(shown(board).getFirst().equals(dueToday.toString()),"Moving up reorders the board");
        check(task(tracker,dueToday).order()<task(tracker,overdue).order(),"The new order is stored");
        item(board,dueToday,"task.down.").doClick();
        check(shown(board).getFirst().equals(overdue.toString()),"Moving down undoes moving up");

        // Pointer drag (#23), alongside the arrows rather than instead of them.
        board.setSize(1280,1000);
        layoutAll(board);
        check(rowOf(board,dueToday)!=null,"A row can be found to drag");
        var beforeDrag=shown(board);
        dragRow(board,later,overdue);
        layoutAll(board);
        var afterDrag=shown(board);
        check(afterDrag.getFirst().equals(later.toString()),"Dragging a row to the top moves it there");
        check(!afterDrag.equals(beforeDrag),"The drag actually reordered the board");
        check(task(tracker,later).order()<task(tracker,overdue).order(),"The dragged order is stored");
        check(repo.state.tasks().stream().filter(t->t.id().equals(later)).findFirst().orElseThrow().order()
            <repo.state.tasks().stream().filter(t->t.id().equals(overdue)).findFirst().orElseThrow().order(),
            "The dragged order reached storage");
        check(afterDrag.size()==beforeDrag.size(),"Dragging loses no rows");
        check(new HashSet<>(afterDrag).equals(new HashSet<>(beforeDrag)),"Dragging duplicates no rows");
        check(jpn.id().equals(task(tracker,dueToday).tagId()),"Dragging keeps tags on the untouched rows");

        // The insertion line must not change a row's height: it is drawn out of
        // the row's own padding, so the rows below it cannot jump by two pixels
        // as the line comes and goes under the cursor.
        var resting=Theme.listRow().getBorderInsets(new JPanel());
        var insertion=TasksPanel.insertionBorder().getBorderInsets(new JPanel());
        check(resting.top==insertion.top&&resting.bottom==insertion.bottom,
            "A drag's insertion line has the resting row's insets ("+insertion.top+"/"+insertion.bottom
                +" against "+resting.top+"/"+resting.bottom+")");

        // Dropping a row back on itself is not a reorder.
        var settled=shown(board);
        dragRow(board,later,later);
        layoutAll(board);
        check(shown(board).equals(settled),"Dropping a row on itself changes nothing");

        // Restore the arrow-era order so the assertions after this still read.
        layoutAll(board);

        // A sorted or filtered board offers no drag, for the same reason it offers
        // no arrows: manual order is a total order over every task.
        sort.setSelectedItem(sortNamed(sort,"Title"));
        layoutAll(board);
        var sortedOrder=shown(board);
        dragRow(board,shownTask(board,2),shownTask(board,0));
        check(shown(board).equals(sortedOrder),"A sorted board ignores a drag");
        sort.setSelectedItem(sortNamed(sort,"My order"));
        button(board,"view.open").doClick();
        layoutAll(board);
        var filteredOrder=shown(board);
        dragRow(board,shownTask(board,2),shownTask(board,0));
        check(shown(board).equals(filteredOrder),"A filtered board ignores a drag");
        button(board,"view.all").doClick();
        layoutAll(board);

        // Status cycles in place and persists.
        check(task(tracker,dueToday).status()==TaskStatus.TODO,"Starts on TODO");
        button(board,"task.status."+dueToday).doClick();
        check(task(tracker,dueToday).status()==TaskStatus.DOING,"TODO advances to DOING");
        button(board,"task.status."+dueToday).doClick();
        check(task(tracker,dueToday).status()==TaskStatus.DONE,"DOING advances to DONE");
        button(board,"task.status."+dueToday).doClick();
        check(task(tracker,dueToday).status()==TaskStatus.TODO,"DONE wraps back to TODO");
        check(jpn.id().equals(task(tracker,dueToday).tagId()),"Cycling status keeps the tag");
        check(task(tracker,dueToday).due().equals(today),"Cycling status keeps the due date");

        // Track is in every row's menu, and enabled only where it can actually clock in.
        check(!item(board,later,"task.track.").isEnabled(),"A task with no activity cannot be tracked");
        check(item(board,undated,"task.track.").isEnabled(),"A task with an activity can be tracked");
        check(item(board,undated,"task.edit.")!=null&&item(board,undated,"task.delete.")!=null,
            "Every row's menu offers edit and delete");
        check(board.rowMenu(UUID.randomUUID())==null,"A task that is not on the board has no menu");

        // An edit must not quietly drop the tag or reset the order.
        var before=task(tracker,overdue);
        var edited=TasksPanel.merged(before,before.activityId(),before.tagId(),"Beta lab, revised",
            before.notes(),before.due(),before.status(),99);
        check(edited.id().equals(before.id()),"Editing keeps the identity");
        check(cs.id().equals(edited.tagId()),"Editing keeps the tag");
        check(edited.order()==before.order(),"Editing keeps the manual position");
        check(edited.createdAt().equals(before.createdAt()),"Editing keeps the creation time");
        check(edited.source().equals(before.source()),"Editing keeps the provenance");
        var created=TasksPanel.merged(null,study,cs.id(),"Fresh","",null,TaskStatus.TODO,99);
        check(created.order()==99,"A new task lands where it was told to");
        check(created.source().isEmpty(),"A new task has no import provenance");

        // Deleting a tag keeps the work and untags it.
        tracker.deleteTag(cs.id());
        check(tracker.state().tags().size()==1,"The tag is gone");
        check(task(tracker,overdue).tagId()==null,"Its tasks are untagged");
        check(task(tracker,overdue).title().equals("Beta lab"),"Its tasks are otherwise untouched");
        check(tracker.state().tasks().size()==5,"Deleting a tag deletes no work");
        check(jpn.id().equals(task(tracker,dueToday).tagId()),"Other tags are unaffected");

        tracker.editTag(jpn.id(),"Language II",0x123456);
        check(tracker.state().tags().getFirst().name().equals("Language II"),"Tags rename");
        check(tracker.state().tags().getFirst().colour()==0x123456,"Tags recolour");

        var tags=new TagEditor(tracker,()->{});
        check(button(tags,"tag.rename."+jpn.id())!=null,"The tag manager offers rename");
        check(button(tags,"tag.colour."+jpn.id())!=null,"The tag manager offers recolour");
        check(button(tags,"tag.delete."+jpn.id())!=null,"The tag manager offers delete");
        check(button(tags,"tag.add")!=null,"The tag manager offers a new tag");

        // The recolour grid paints the palette, not eight identical squares: a
        // dot whose colour cannot be seen before it is picked is a blind pick.
        var grid=TagEditor.palette(new int[]{-1});
        var fills=new HashSet<Integer>();
        int dots=0;
        for(Component child:grid.getComponents())
            if(child instanceof JButton dot){dots++;fills.add(dot.getBackground().getRGB());}
        check(dots==8,"The recolour grid offers the whole palette, got "+dots+" dots");
        check(fills.size()==8,"Each recolour dot shows its own colour, got "+fills.size()+" distinct fills");

        // Everything still paints.
        var fresh=new TasksPanel(tracker,()->{},()->false);
        fresh.setSize(1280,900);
        layout(fresh);
        fresh.paint(new BufferedImage(1280,900,BufferedImage.TYPE_INT_RGB).getGraphics());
        check(shown(fresh).size()==5,"The rebuilt board still renders every task");

        // The Notion layout (#25): a header, a done checkbox, pills and written dates.
        var header=(Container)named(fresh,"task.header");
        check(header!=null,"The table has a header row");
        var headings=new ArrayList<String>();
        for(Component cell:header.getComponents())if(cell instanceof JLabel l&&!l.getText().isEmpty())headings.add(l.getText());
        check(headings.equals(List.of("Status","Task","Tag","Due")),"The header names each column: "+headings);
        check(button(fresh,"task.new")!=null,"New is the page's one primary action");
        check(button(fresh,"task.newRow")!=null,"and the table ends in a New task row");
        check(button(fresh,"task.up."+dueToday)==null&&button(fresh,"task.track."+dueToday)==null,
            "Rare row actions are in the menu, not a row of buttons");
        var importNames=Arrays.stream(fresh.importMenu().getComponents()).map(Component::getName).toList();
        check(importNames.contains("task.import.paste")&&importNames.contains("task.import.notion"),
            "Import groups paste and the Notion export in one menu");
        check(named(fresh,"task.tag."+dueToday) instanceof JLabel pill&&pill.getText().equals("Language II"),
            "A tagged task shows its tag as a pill");
        check(named(fresh,"task.tag."+overdue)==null,"An untagged task leaves the tag cell empty");
        check(((JLabel)named(fresh,"task.due."+dueToday)).getText().equals("Today"),"Today's date says Today");
        check(((JLabel)named(fresh,"task.due."+later)).getText().equals(DateText.longDate(today.plusDays(9))),
            "A later date is written out in full");
        check(((JLabel)named(fresh,"task.due."+undated)).getText().isEmpty(),"An undated task leaves the date cell empty");

        var done=(JCheckBox)named(fresh,"task.done."+undated);
        check(!done.isSelected(),"An open task's checkbox is clear");
        done.doClick();
        check(task(tracker,undated).status()==TaskStatus.DONE,"Ticking the checkbox finishes the task");
        check(((JCheckBox)named(fresh,"task.done."+undated)).isSelected(),"and the rebuilt row shows it ticked");
        ((JCheckBox)named(fresh,"task.done."+undated)).doClick();
        check(task(tracker,undated).status()==TaskStatus.TODO,"Clearing it reopens the task");

        // Searching intersects the chosen view and never edits the manual order.
        var savedView=new TasksPanel.ViewState();
        var searchable=new TasksPanel(tracker,()->{},()->false,savedView);
        var search=(JTextField)named(searchable,"task.search");
        search.setText("LANGUAGE II");
        check(shown(searchable).contains(dueToday.toString()),"Search matches tag names without case sensitivity");
        check(!shown(searchable).contains(overdue.toString()),"Search excludes unrelated rows");
        check(!item(searchable,dueToday,"task.down.").isEnabled(),"Search cannot reorder a partial list");
        search.setText("nothing-matches-this-invented-query");
        check(shown(searchable).isEmpty(),"A missing query has no rows");
        button(searchable,"task.search.clear").doClick();
        check(shown(searchable).size()==5,"Clear restores all tasks");
        button(searchable,"view.done").doClick();
        search.setText(task(tracker,finished).title().toUpperCase(Locale.ROOT));
        check(shown(searchable).equals(List.of(finished.toString())),"Search intersects completed view");
        var reopened=new TasksPanel(tracker,()->{},()->false,savedView);
        check(shown(reopened).equals(shown(searchable)),"View and query survive page recreation");
        check(((JTextField)named(reopened,"task.search")).getText().equals(search.getText()),"Restored query stays visible");
        search.getActionMap().get("clearSearch").actionPerformed(null);
        check(search.getText().isEmpty(),"Escape clears search");

        // A long title wraps and its row grows, instead of vanishing into an ellipsis.
        var wordy=UUID.randomUUID();
        tracker.addTasks(List.of(new Task(wordy,null,null,
            "A deliberately long invented task title that has to wrap onto a second line at the narrowest window rather than disappear behind an ellipsis",
            "",null,TaskStatus.TODO,"",now,50)));
        var narrow=new TasksPanel(tracker,()->{},()->false);
        narrow.setSize(840,1400);
        layout(narrow);layout(narrow);
        var longRow=rowOf(narrow,wordy);
        var shortRow=rowOf(narrow,undated);
        check(longRow.getHeight()>shortRow.getHeight(),"A long title's row is taller: "+longRow.getHeight()+" against "+shortRow.getHeight());
        var wrapped=(JTextArea)named(narrow,"task.title."+wordy);
        check(wrapped.getHeight()>=wrapped.getPreferredSize().height,"and the whole title fits in it: title "
            +wrapped.getWidth()+"x"+wrapped.getHeight()+" wants "+wrapped.getPreferredSize()+", row "+longRow.getSize()
            +" wants "+longRow.getPreferredSize());
        check(longRow.getY()+longRow.getHeight()<=rowOf(narrow,finished).getY()||rowOf(narrow,finished).getY()+rowOf(narrow,finished).getHeight()<=longRow.getY(),
            "without overlapping another row");
        var status=button(narrow,"task.status."+wordy);
        check(status.getWidth()<=status.getPreferredSize().width,"A status pill keeps its own width rather than filling its column");

        // A row is a control, so it answers the pointer like one.
        var hovered=rowOf(narrow,wordy);
        hovered.dispatchEvent(new MouseEvent(hovered,MouseEvent.MOUSE_ENTERED,0,0,10,5,0,false));
        check(hovered.isOpaque(),"A row lights up under the pointer");
        hovered.dispatchEvent(new MouseEvent(hovered,MouseEvent.MOUSE_EXITED,0,0,10,5,0,false));
        check(hovered.isOpaque(),"and stays lit while the pointer only crosses onto a control inside it");
        hovered.dispatchEvent(new MouseEvent(hovered,MouseEvent.MOUSE_EXITED,0,0,10,hovered.getHeight()+20,0,false));
        check(!hovered.isOpaque(),"and goes back to the page when the pointer leaves the row");
    }

    private static Object sortNamed(JComboBox<?> combo,String label){
        for(int i=0;i<combo.getItemCount();i++)
            if(String.valueOf(combo.getItemAt(i)).equals(label))return combo.getItemAt(i);
        throw new AssertionError("No sort called "+label);
    }
    private static void layout(Container c){
        c.doLayout();
        for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);
    }
}
