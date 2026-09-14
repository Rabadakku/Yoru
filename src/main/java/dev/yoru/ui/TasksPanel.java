package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import static dev.yoru.ui.Theme.*;

/** Local assignment board. AI has no write access; review is a separate user command. */
final class TasksPanel extends JPanel implements Scrollable {
    /** Which slice of the board is on screen. */
    private enum View {
        ALL("All"), TODAY("Due today"), SOON("Next 5 days"), OPEN("Open"), DONE("Completed"),
        CALENDAR("Calendar");
        final String label; View(String label){this.label=label;}
    }
    /** Manual order is the default; any other sort overrides it. */
    private enum Sort {
        MANUAL("My order"), DUE("Due date"), TITLE("Title"), STATUS("Status");
        final String label; Sort(String label){this.label=label;}
        @Override public String toString(){return label;}
    }

    private final Tracker tracker;
    private final Runnable refresh;
    private final BooleanSupplier closed;
    private final JPanel rows=stack();
    private final JLabel summary=label("",TYPE_CAPTION,MUTED);
    private final Map<View,JButton> viewButtons=new EnumMap<>(View.class);
    private View view=View.ALL;
    private Sort sort=Sort.MANUAL;
    private YearMonth month=YearMonth.now();
    private final JPanel sortControls=row();
    private final List<JPanel> rowPanels=new ArrayList<>();
    private int dragFrom=-1, dragTo=-1;

    TasksPanel(Tracker tracker, Runnable refresh, BooleanSupplier closed) {
        super(new BorderLayout()); setOpaque(false);
        this.tracker=tracker; this.refresh=refresh; this.closed=closed;
        var p=stack();
        p.add(YoruApp.pageHeaderFor("Tasks","TASKS · NOTES · DUE DATES"));
        var actions=row();
        actions.add(button("+ Task",()->edit(null)));
        actions.add(button("Tags…",()->TagEditor.open(this,tracker,this::rebuildRows)));
        actions.add(button("Paste proposals…",this::importPaste));
        actions.add(button("Import Notion…",this::importNotion));
        p.add(actions);gap(p,SPACE_LG);

        var bar=row();
        bar.add(label("VIEW",TYPE_CAPTION,MUTED));
        for(var value:View.values()) {
            var b=compact(button(value.label,()->{view=value;rebuildRows();}));
            b.setName("view."+value.name().toLowerCase());
            viewButtons.put(value,b);bar.add(b);
        }
        var order=plainCombo(new JComboBox<>(Sort.values()));
        order.setName("task.sort");
        // One control height for the app; the width is this control's own.
        order.setPreferredSize(new Dimension(132,SPACE_XXL));
        order.addActionListener(e->{sort=(Sort)order.getSelectedItem();rebuildRows();});
        sortControls.setOpaque(false);
        sortControls.add(label("SORT",TYPE_CAPTION,MUTED));
        sortControls.add(order);
        bar.add(sortControls);
        p.add(bar);gap(p,SPACE_SM);
        p.add(summary);gap(p,SPACE_MD);
        p.add(rows);gap(p,SPACE_MD);
        add(p,BorderLayout.NORTH);
        rebuildRows();
    }

    /**
     * Row-sized button: the app's only exception to the shared control recipe.
     *
     * The standard button is built for a toolbar, not for a row of a list, so
     * this one carries the caption's face and half the padding — the one place
     * that is allowed to differ, and the one place to look if a row is too tall.
     */
    private static JButton compact(JButton b) {
        b.setFont(captionFont());
        b.setBorder(new CompoundBorder(new LineBorder(LINE),
            new EmptyBorder(SPACE_XS,SPACE_SM,SPACE_XS,SPACE_SM)));
        return b;
    }

    /**
     * The status pill's text colour: the palette role as it stands on a dark
     * theme, one step deeper on a light one.
     *
     * The pill is 11 px text on the LINE tint, and on Linen and Sakura the raw
     * roles measured 2.0-3.2:1 there — "Done" was the weakest thing on the row.
     * One fixed step darker puts every state over 4.5:1 on both light palettes
     * (Linen 4.65-7.79:1, Sakura 4.54-7.98:1) and leaves Midnight and Ember
     * pixel-for-pixel as they were.
     */
    private static Color pillInk(Color role) { return DARK?role:shade(role,-60); }

    private List<Task> visible() {
        var today=LocalDate.now();
        var filtered=tracker.state().tasks().stream().filter(t->switch(view) {
            case ALL->true;
            // Filtered on the day you mean to work on it, not the deadline. That
            // is the question these views answer: what am I doing today.
            case TODAY->t.status()!=TaskStatus.DONE&&t.workOn()!=null&&!t.workOn().isAfter(today);
            // Overdue counts as due: a horizon view that hides what you already
            // missed is exactly the wrong thing on the screen meant to catch it.
            case SOON->t.status()!=TaskStatus.DONE&&t.workOn()!=null&&!t.workOn().isAfter(today.plusDays(5));
            case OPEN->t.status()!=TaskStatus.DONE;
            case DONE->t.status()==TaskStatus.DONE;
            case CALENDAR->true;
        });
        Comparator<Task> by=switch(sort) {
            case MANUAL->Comparator.comparingInt(Task::order).thenComparing(Task::createdAt);
            case DUE->Comparator.comparing((Task t)->t.workOn()==null?LocalDate.MAX:t.workOn())
                .thenComparing(t->t.title().toLowerCase(Locale.ROOT));
            case TITLE->Comparator.comparing(t->t.title().toLowerCase(Locale.ROOT));
            case STATUS->Comparator.comparing((Task t)->t.status().ordinal())
                .thenComparing(t->t.workOn()==null?LocalDate.MAX:t.workOn());
        };
        return filtered.sorted(by).toList();
    }

    /**
     * Manual order is a total order over every task, so it can only be edited
     * from the unfiltered list. Reordering four of nine visible rows would
     * renumber those four and interleave them with the five it could not see.
     */
    private boolean reorderable() { return sort==Sort.MANUAL&&view==View.ALL; }

    private void rebuildRows() {
        rows.removeAll();
        viewButtons.forEach((value,b)->selected(b,value==view));
        // The calendar is ordered by date. Offering a sort there would be a
        // control that silently does nothing.
        sortControls.setVisible(view!=View.CALENDAR);
        var tasks=visible();
        int open=(int)tracker.state().tasks().stream().filter(t->t.status()!=TaskStatus.DONE).count();
        summary.setText(tasks.size()+" shown  /  "+open+" open  /  "+tracker.state().tasks().size()+" total"
            +(view==View.CALENDAR?"  ·  drag a task onto a day to move it"
                :reorderable()?"":"  ·  switch to All + My order to reorder"));
        if(view==View.CALENDAR) { buildCalendar(); return; }
        if(tasks.isEmpty()) {
            var empty=card();
            var headline=switch(view) {
                case ALL->"No tasks yet.";
                case TODAY->"Nothing due today or overdue.";
                case SOON->"Nothing due in the next five days.";
                case OPEN->"Nothing open. All caught up.";
                case DONE->"Nothing completed yet.";
                case CALENDAR->"No tasks planned this month.";
            };
            empty.add(label(headline,TYPE_HEADING,TEXT));gap(empty,SPACE_MD);
            empty.add(bodyLabel("Create one, or paste a reply from your own assistant and review it here."));
            rows.add(empty);
        }
        rowPanels.clear();
        for(int i=0;i<tasks.size();i++) { var line=taskRow(tasks,i); rowPanels.add(line); rows.add(line); }
        rows.revalidate();rows.repaint();
        revalidate();repaint();
    }

    /**
     * The month view. Drag a task onto a day to move its due date, or onto the
     * strip underneath to clear it. Sorting does not apply here — the grid is
     * ordered by date already — so the controls that would lie are left out.
     */
    private void buildCalendar() {
        var nav=row();
        nav.add(button("←",()->{month=month.minusMonths(1);rebuildRows();}));
        nav.add(label(month.getMonth().getDisplayName(java.time.format.TextStyle.FULL,Locale.ENGLISH)
            +" "+month.getYear(),TYPE_HEADING,TEXT));
        nav.add(button("→",()->{month=month.plusMonths(1);rebuildRows();}));
        var thisMonth=button("This month",()->{month=YearMonth.now();rebuildRows();});
        thisMonth.setName("calendar.today");
        nav.add(thisMonth);
        rows.add(nav);

        var calendar=new TaskCalendar(tracker.state(),month,LocalDate.now(),(taskId,date)->{
            var task=tracker.state().tasks().stream().filter(t->t.id().equals(taskId)).findFirst().orElse(null);
            if(task==null||Objects.equals(task.plannedFor(),date))return;
            try {
                // Dragging moves the *plan*. A deadline is not something you drag;
                // it is changed deliberately, in the editor.
                tracker.updateTask(merged(task,task.activityId(),task.tagId(),task.title(),task.notes(),
                    task.due(),task.status(),0,date));
                rebuildRows();
            } catch(Exception e){error(e);}
        });
        calendar.setName("task.calendar");
        var frame=new JPanel(new BorderLayout());
        frame.setOpaque(true);frame.setBackground(PANEL);
        frame.setBorder(new CompoundBorder(controlBorder(LINE),new EmptyBorder(SPACE_MD,SPACE_MD,SPACE_MD,SPACE_MD)));
        frame.setAlignmentX(0);
        frame.add(calendar,BorderLayout.CENTER);
        rows.add(frame);
        rows.revalidate();rows.repaint();
        revalidate();repaint();
    }

    private JPanel taskRow(List<Task> tasks,int index) {
        var task=tasks.get(index);
        boolean done=task.status()==TaskStatus.DONE;
        var line=new JPanel(new BorderLayout(12,0));
        line.setOpaque(true);line.setBackground(PANEL);
        line.setBorder(restingBorder());

        var status=compact(button(task.status().label,()->cycle(task)));
        status.setName("task.status."+task.id());
        status.setForeground(switch(task.status()){case TODO->pillInk(MUTED);case DOING->pillInk(GOLD);case DONE->pillInk(CYAN);});
        status.setPreferredSize(new Dimension(72,26));
        status.setToolTipText("Click to move this task to its next status");
        line.add(status,BorderLayout.WEST);

        var title=label(task.title(),TYPE_LABEL,done?MUTED:TEXT);
        if(!task.notes().isBlank())title.setToolTipText(task.notes());
        line.add(title,BorderLayout.CENTER);

        // Fixed widths, and Track always present even when it cannot run: every
        // column has to land in the same place on every row or this stops being
        // a board and goes back to being a ragged list. The widths are this
        // board's own; the gap between them is the shared one.
        var right=tightRow();
        var dueLabel=due(task);
        dueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        dueLabel.setPreferredSize(new Dimension(96,20));
        right.add(dueLabel);
        var chip=chip(task);
        chip.setPreferredSize(new Dimension(112,20));
        right.add(chip);
        var up=compact(button("↑",()->move(tasks,index,-1)));
        up.setName("task.up."+task.id());up.setEnabled(reorderable()&&index>0);
        up.setToolTipText(reorderable()?"Move up":"Reordering needs the All view in My order");
        var down=compact(button("↓",()->move(tasks,index,1)));
        down.setName("task.down."+task.id());down.setEnabled(reorderable()&&index<tasks.size()-1);
        down.setToolTipText(reorderable()?"Move down":"Reordering needs the All view in My order");
        right.add(up);right.add(down);
        var track=compact(button("Track",()->{
            try{tracker.start(task.activityId());Dialogs.info(this,"Timer started for this task's activity.");refresh.run();}
            catch(Exception ex){error(ex);}
        }));
        track.setName("task.track."+task.id());
        track.setEnabled(task.activityId()!=null&&!done);
        track.setToolTipText(task.activityId()==null?"Assign an activity to time this task"
            :done?"This task is finished":"Clock in on this task's activity");
        right.add(track);
        var editButton=compact(button("Edit",()->edit(task)));
        editButton.setName("task.edit."+task.id());
        right.add(editButton);
        line.add(right,BorderLayout.EAST);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE,line.getPreferredSize().height));
        if(reorderable()) installDrag(line,tasks,index);
        return line;
    }

    private static javax.swing.border.Border restingBorder() {
        return listRow();
    }

    /**
     * Where the row would land: a line above the row the cursor is over.
     *
     * The same insets as the resting row, so a drag does not make the rows
     * below it jump by two pixels as the line appears and disappears. The
     * accent line is drawn out of the row's own top padding — {@code RING}
     * pixels less of it — rather than added above it, which is what keeps the
     * total equal to {@link Theme#listRow()}.
     *
     * Package-private so a test can hold the two borders to that.
     */
    static javax.swing.border.Border insertionBorder() {
        return new CompoundBorder(new MatteBorder(RING,0,HAIRLINE,0,CYAN),
            new EmptyBorder(SPACE_SM-RING,SPACE_MD,SPACE_SM,SPACE_MD));
    }

    /**
     * Pointer drag to reorder (#23), alongside the arrows rather than instead of
     * them: the arrows are keyboard reachable and are what a headless test drives.
     *
     * Listeners go on the row and on its non-interactive children, because Swing
     * delivers to the deepest component under the cursor and does not walk up —
     * a press on the title label would otherwise never reach the row. Buttons
     * consume their own presses, so pressing Edit does not start a drag.
     */
    private void installDrag(JPanel line,List<Task> tasks,int index) {
        var handler=new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                dragFrom=index; dragTo=index;
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if(dragFrom<0) return;
                var inRows=SwingUtilities.convertPoint((java.awt.Component)e.getSource(),e.getPoint(),rows);
                dragTo=slotAt(inRows.y);
                for(int i=0;i<rowPanels.size();i++)
                    rowPanels.get(i).setBorder(i==dragTo&&dragTo!=dragFrom?insertionBorder():restingBorder());
                rows.repaint();
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if(dragFrom<0) return;
                int from=dragFrom, to=dragTo;
                dragFrom=-1; dragTo=-1;
                if(to<0||to==from) { rebuildRows(); return; }
                moveTo(tasks,from,to);
            }
        };
        install(line,handler);
    }

    private static void install(java.awt.Component component,java.awt.event.MouseAdapter handler) {
        // Anything that handles its own clicks is left alone.
        if(component instanceof JButton||component instanceof JComboBox<?>) return;
        component.addMouseListener(handler);
        component.addMouseMotionListener(handler);
        if(component instanceof java.awt.Container container)
            for(var child:container.getComponents()) install(child,handler);
    }

    /** The row index the cursor is inside, or -1 past the end of the list. */
    private int slotAt(int y) {
        for(int i=0;i<rowPanels.size();i++) {
            var box=rowPanels.get(i).getBounds();
            if(y<box.y+box.height) return i;
        }
        return rowPanels.isEmpty()?-1:rowPanels.size()-1;
    }

    /** Lifts one task out of the order and drops it back at another position. */
    private void moveTo(List<Task> tasks,int from,int to) {
        var ids=new ArrayList<>(tasks.stream().map(Task::id).toList());
        var moved=ids.remove(from);
        ids.add(Math.max(0,Math.min(ids.size(),to)),moved);
        try{tracker.reorderTasks(ids);rebuildRows();}catch(Exception e){error(e);}
    }

    private JLabel due(Task task) {
        var when=task.workOn();
        if(when==null)return label("no date",TYPE_CAPTION,MUTED);
        var today=LocalDate.now();
        // Four steps of urgency, not three: overdue in danger, today in gold,
        // tomorrow in the body ink, and everything further out muted. Tomorrow
        // used to read exactly like a date a fortnight away, so the one date
        // worth acting on tonight carried no more weight than the rest.
        var colour=task.status()==TaskStatus.DONE?MUTED
            :task.scheduledLate()?DANGER
            :when.isBefore(today)?DANGER
            :when.equals(today)?GOLD_TEXT
            :when.equals(today.plusDays(1))?TEXT:MUTED;
        var text=when.equals(today)?"today"
            :when.equals(today.plusDays(1))?"tomorrow"
            :when.toString();
        // A planned day that is not the deadline gets a marker, because the two
        // being different is the thing worth noticing.
        if(task.plannedFor()!=null&&task.due()!=null&&!task.plannedFor().equals(task.due())) text="→ "+text;
        var l=label(text,TYPE_CAPTION,colour);
        if(task.scheduledLate())
            l.setToolTipText("Planned for "+task.plannedFor()+", but due "+task.due());
        else if(task.plannedFor()!=null&&task.due()!=null)
            l.setToolTipText("Planned for "+task.plannedFor()+", due "+task.due());
        else if(when.isBefore(today)&&task.status()!=TaskStatus.DONE)
            l.setToolTipText("Overdue since "+when);
        return l;
    }

    private JComponent chip(Task task) {
        var tag=tracker.state().tags().stream().filter(t->t.id().equals(task.tagId())).findFirst().orElse(null);
        // Plain MUTED: the shaded version measured under 4.5:1 on the light themes.
        if(tag==null)return label("untagged",TYPE_CAPTION,MUTED);
        var chip=tightRow();
        // A tag is its colour, and the stored palette is drawn for a dark
        // ground: on a light card the raw teal and amber measured 1.6-1.9:1, so
        // the one cue that says which tag this is disappeared. Deepening the
        // dot on a light theme keeps its hue and puts every palette entry over
        // 3:1 on both light grounds; the dark themes already had 5:1 and are
        // left alone.
        chip.add(TagEditor.swatch(shade(new Color(tag.colour()),DARK?0:-70).getRGB(),SPACE_MD));
        chip.add(label(tag.name(),TYPE_CAPTION,MUTED));
        return chip;
    }

    private void cycle(Task task) {
        var next=switch(task.status()) {
            case TODO->TaskStatus.DOING; case DOING->TaskStatus.DONE; case DONE->TaskStatus.TODO;
        };
        try{tracker.taskStatus(task.id(),next);rebuildRows();}catch(Exception e){error(e);}
    }

    private void move(List<Task> tasks,int index,int delta) {
        var ids=new ArrayList<>(tasks.stream().map(Task::id).toList());
        Collections.swap(ids,index,index+delta);
        try{tracker.reorderTasks(ids);rebuildRows();}catch(Exception e){error(e);}
    }

    public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 22;}
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(22,r.height-22);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return false;}
    private void error(Exception e){Dialogs.error(this,e.getMessage());}

    /**
     * The task an edit should save, rebuilt field by field. Extracted so this is
     * testable without driving a modal dialog: the short Task constructor drops
     * the tag and resets order and createdAt, and an edit that quietly did that
     * would still look correct on screen until the board was re-sorted.
     */
    static Task merged(Task existing,UUID activityId,UUID tagId,String title,String notes,
                       LocalDate due,TaskStatus status,int orderForNew) {
        return merged(existing,activityId,tagId,title,notes,due,status,orderForNew,
            existing==null?null:existing.plannedFor());
    }

    static Task merged(Task existing,UUID activityId,UUID tagId,String title,String notes,
                       LocalDate due,TaskStatus status,int orderForNew,LocalDate plannedFor) {
        return new Task(
            existing==null?UUID.randomUUID():existing.id(),
            activityId,tagId,title,notes,due,status,
            existing==null?"":existing.source(),
            existing==null?Instant.now():existing.createdAt(),
            existing==null?orderForNew:existing.order(),
            plannedFor);
    }

    /** A new task lands at the bottom of the manual order, not on top of row one. */
    private int nextOrder() {
        return tracker.state().tasks().stream().mapToInt(Task::order).max().orElse(-1)+1;
    }

    private void edit(Task existing) {
        var title=new JTextField(existing==null?"":existing.title(),36);
        var notes=new JTextArea(existing==null?"":existing.notes(),5,36);notes.setLineWrap(true);notes.setWrapStyleWord(true);
        var due=new DateField(existing==null?null:existing.due(),"Due",true);
        var activity=plainCombo(new JComboBox<Object>());activity.addItem("Unassigned");tracker.state().activities().forEach(activity::addItem);
        if(existing!=null&&existing.activityId()!=null)for(int i=1;i<activity.getItemCount();i++)if(((Activity)activity.getItemAt(i)).id().equals(existing.activityId()))activity.setSelectedIndex(i);
        var status=plainCombo(new JComboBox<>(TaskStatus.values()));
        status.setSelectedItem(existing==null?TaskStatus.TODO:existing.status());
        var tag=plainCombo(new JComboBox<Object>());tag.addItem("No tag");tracker.state().tags().forEach(tag::addItem);
        if(existing!=null&&existing.tagId()!=null)for(int i=1;i<tag.getItemCount();i++)if(((Tag)tag.getItemAt(i)).id().equals(existing.tagId()))tag.setSelectedIndex(i);
        var planned=new DateField(existing==null?null:existing.plannedFor(),"Plan for",true);
        planned.setName("task.plannedFor");
        var form=stack();form.add(new JLabel("Title"));form.add(title);gap(form,SPACE_MD);form.add(new JLabel("Notes"));form.add(new JScrollPane(notes));gap(form,SPACE_MD);
        form.add(new JLabel("Due · the deadline"));form.add(due);gap(form,SPACE_MD);
        form.add(new JLabel("Plan for · the day you mean to do it · blank to use the deadline"));form.add(planned);gap(form,SPACE_MD);
        form.add(new JLabel("Activity"));form.add(activity);gap(form,SPACE_MD);form.add(new JLabel("Status"));form.add(status);gap(form,SPACE_MD);form.add(new JLabel("Tag"));form.add(tag);
        // Reopened on a refusal with everything as typed, rather than closed with it lost.
        while(Dialogs.confirm(this,form,existing==null?"New task":"Edit task","Save")) {
            try {
                var task=merged(existing,
                    activity.getSelectedItem() instanceof Activity a?a.id():null,
                    tag.getSelectedItem() instanceof Tag t?t.id():null,
                    title.getText(),notes.getText(),due.value(),
                    (TaskStatus)status.getSelectedItem(),nextOrder(),planned.value());
                if(existing==null)tracker.addTasks(List.of(task));else tracker.updateTask(task);
                rebuildRows();
                return;
            }catch(Exception e){error(e);}
        }
    }
    private void importPaste() {
        var form=new TaskPastePanel();
        while(Dialogs.confirm(this,form,"Paste task proposals","Import tasks")) {
            if(closed.getAsBoolean())return;
            try { review(form.proposals()); return; }
            catch(java.io.IOException e) { form.showError(e.getMessage()); }
        }
    }

    /**
     * A Notion export, reviewed before anything is written.
     *
     * The panel re-reads the export on every remap and writes nothing, so the
     * only write is the one the tracker makes for the batch the user approved.
     */
    private void importNotion() {
        var form=new NotionImportPanel(tracker);
        while(Dialogs.confirm(this,form,"Import a Notion export","Import tasks")) {
            if(closed.getAsBoolean())return;
            try {
                var batch=form.batch();
                int added=tracker.importTasks(batch.newTags(),batch.tasks());
                int skipped=batch.tasks().size()-added;
                Dialogs.info(this,"Import complete",added+" task"+(added==1?"":"s")+" imported from the Notion export."
                    +(skipped>0?" "+skipped+" already existed and were skipped.":""));
                rebuildRows();return;
            } catch(java.io.IOException|IllegalArgumentException e){form.showError(e.getMessage());}
        }
    }

    private void review(List<Task> proposed) {
        if(proposed.isEmpty()){Dialogs.info(this,"No tasks were found in that reply. Nothing was saved.");return;}
        Object[][] rows=new Object[proposed.size()][4];
        for(int i=0;i<proposed.size();i++){var t=proposed.get(i);rows[i]=new Object[]{true,t.title(),t.due()==null?"":DateText.date(t.due()),t.notes()};}
        var model=new DefaultTableModel(rows,new String[]{"Add","Task title","Due (e.g. Sep 14)","Notes / evidence"}){public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}};
        var table=new JTable(model);plainTable(table);table.setRowHeight(SPACE_XXL);table.setFont(bodyFont());
        table.setBackground(PANEL);table.setForeground(TEXT);
        // TEXT on LINE measures 7.0-10.1:1 on all four themes; the accent it
        // replaced on the same fill was 2.4:1 on the two light ones, where a
        // selected row has to be read, not squinted at.
        table.setSelectionBackground(LINE);table.setSelectionForeground(TEXT);
        table.getColumnModel().getColumn(0).setMaxWidth(45);
        var scroll=new JScrollPane(table);scroll.setPreferredSize(new Dimension(900,300));
        var activity=plainCombo(new JComboBox<Object>());activity.addItem("Unassigned");tracker.state().activities().forEach(activity::addItem);
        var form=stack();form.add(new JLabel("Review titles, dates and source evidence. Edit cells or uncheck tasks."));gap(form,SPACE_SM);form.add(scroll);gap(form,SPACE_MD);
        var evidence=new JTextArea(5,70);evidence.setEditable(false);evidence.setLineWrap(true);evidence.setWrapStyleWord(true);
        table.getSelectionModel().addListSelectionListener(e->{int row=table.getSelectedRow();if(row>=0)evidence.setText(String.valueOf(model.getValueAt(row,3)));});
        form.add(new JLabel("Selected task evidence"));form.add(new JScrollPane(evidence));gap(form,SPACE_SM);form.add(new JLabel("Assign selected tasks to an activity"));form.add(activity);
        while(Dialogs.confirm(this,form,"Import tasks","Import tasks")){
            if(table.isEditing()&&!table.getCellEditor().stopCellEditing())continue;
            try{
                var selected=new ArrayList<Task>();
                // Imported tasks land at the bottom of the manual order; the short
                // constructor would give every one of them order 0, above the rest.
                int order=nextOrder();
                for(int i=0;i<rows.length;i++)if(Boolean.TRUE.equals(model.getValueAt(i,0))){String due=String.valueOf(model.getValueAt(i,2)).strip();selected.add(new Task(proposed.get(i).id(),activity.getSelectedItem() instanceof Activity a?a.id():null,null,String.valueOf(model.getValueAt(i,1)),String.valueOf(model.getValueAt(i,3)),due.isBlank()?null:DateText.parseDate(due,LocalDate.now()),TaskStatus.TODO,proposed.get(i).source(),Instant.now(),order++));}
                int count=tracker.addTasks(selected);Dialogs.info(this,count+" tasks added. Existing title/date/activity duplicates were skipped.");rebuildRows();return;
            }catch(Exception e){error(e);}
        }
    }
}
