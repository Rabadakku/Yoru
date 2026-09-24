package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import static dev.yoru.ui.Theme.*;

/**
 * The task board, laid out like a Notion database table (#25).
 *
 * View tabs and the one New action on top; sort, tags and import beneath; then a
 * table whose header and rows share one set of column widths, so every checkbox,
 * status, title, tag and date lands in the same place on every row. What a row
 * is for — ticking it off and moving its status — is on the row. What is needed
 * rarely — edit, track, reorder, delete — is in its trailing ⋯ menu, which the
 * keyboard reaches like any button and a right-click opens too. A click on a
 * title opens the task. AI has no write access; review is a separate user command.
 */
final class TasksPanel extends JPanel implements Scrollable {
    /** Which slice of the board is on screen. */
    private enum View {
        ALL("All"), TODAY("Due today"), SOON("Next 5 days"), OPEN("Open"), DONE("Completed"),
        CALENDAR("Calendar");
        final String label; View(String label){this.label=label;}
    }
    /** Manual order is the default; any other sort overrides it. */
    private enum Sort {
        MANUAL("My order"), DUE("Due date"), TITLE("Title"), STATUS("Status"), PRIORITY("Priority");
        final String label; Sort(String label){this.label=label;}
        @Override public String toString(){return label;}
    }

    /** The columns of the table on screen (#68): the fixed ones, then priority and this place's properties. */
    private List<TaskTable.Column> columns=List.of();

    private final Tracker tracker;
    private final Runnable refresh;
    private final BooleanSupplier closed;
    private java.util.function.Consumer<UUID> openPage = id -> {};
    private final JPanel rows=stack();
    private final JLabel summary=label("",TYPE_CAPTION,MUTED);
    private final Map<View,JButton> viewButtons=new EnumMap<>(View.class);
    /**
     * What the page is showing, kept while the page is rebuilt: the place on
     * the rail, and for each place the view, sort, month and search it was
     * left with (#56), so a list keeps its own board or calendar.
     */
    static final class ViewState {
        String scope=TaskLists.ALL;
        View view=View.ALL;
        Sort sort=Sort.MANUAL;
        YearMonth month=YearMonth.now();
        String query="";
        private final Map<String,Saved> saved=new HashMap<>();
        private record Saved(View view,Sort sort,YearMonth month,String query) { }
        /** Keeps the current place's filters, then takes up another place's, or a fresh board. */
        void enter(String next) {
            saved.put(scope,new Saved(view,sort,month,query));
            scope=next;
            var back=saved.get(next);
            view=back==null?View.ALL:back.view(); sort=back==null?Sort.MANUAL:back.sort();
            month=back==null?YearMonth.now():back.month(); query=back==null?"":back.query();
        }
    }
    private final ViewState state;
    /** What a property or priority cell does when used (#68). */
    private final TaskTable.Edits cellEdits=new TaskTable.Edits() {
        @Override public void priority(Task task) { choosePriority(task); }
        @Override public void value(Task task,Property property) { editValue(task,property); }
        @Override public void tick(Task task,Property property,boolean on) {
            try{tracker.properties().setValue(task.id(),property.id(),on?new Value.Tick():null);rebuildRows();}catch(Exception e){error(e);}
        }
        @Override public void open(String link) { openLink(link); }
    };
    private final JTextField search=styleInput(new JTextField(24));
    /** The table's last line: a whole task typed on one line (#74). Kept across rebuilds, so it keeps its focus. */
    private final QuickAddField quickAdd=new QuickAddField(this::quickContext,this::quickAdd,()->edit(null));
    private final JButton clearSearch=button("Clear",()->search.setText(""));
    private View view=View.ALL;
    private Sort sort=Sort.MANUAL;
    private YearMonth month=YearMonth.now();
    private final JPanel sortControls=new JPanel(new FlowLayout(FlowLayout.LEFT,SPACE_SM,0));
    private final List<JPanel> rowPanels=new ArrayList<>();
    private int dragFrom=-1, dragTo=-1;
    /** The rail's entries a dragged task can be dropped on, and the one it is over. */
    private final List<JButton> railEntries=new ArrayList<>();
    private final JPanel rail=new JPanel();
    private JButton dropOn;
    private JComponent tabsRow, toolsRow;
    private final JLabel inboxHelp = wrapping(TaskLists.INBOX_HELP, TYPE_CAPTION, MUTED);
    private JComboBox<Sort> order;
    private final TaskBulkActions bulk;
    private final JButton selectTasks;
    private final Map<UUID,JCheckBox> selectionChecks = new LinkedHashMap<>();
    /** Set while the page puts a place's saved search back, so the field's own listener does not rebuild twice. */
    private boolean restoring;

    TasksPanel(Tracker tracker, Runnable refresh, BooleanSupplier closed) {
        this(tracker,refresh,closed,new ViewState());
    }

    TasksPanel(Tracker tracker, Runnable refresh, BooleanSupplier closed, ViewState state) {
        super(new BorderLayout()); setOpaque(false);
        this.tracker=tracker; this.refresh=refresh; this.closed=closed;
        this.state=state;
        bulk = new TaskBulkActions(tracker, this::rebuildRows, this::error, closed);
        selectTasks = button("Select", () -> {
            if (bulk.active()) bulk.stop(); else bulk.start();
            rebuildRows();
            if (bulk.active() && !selectionChecks.isEmpty())
                selectionChecks.values().iterator().next().requestFocusInWindow();
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "tasks.cancelSelection");
        getActionMap().put("tasks.cancelSelection", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!bulk.active()) return;
                bulk.stop();
                rebuildRows();
                selectTasks.requestFocusInWindow();
            }
        });
        selectTasks.setName("tasks.select");
        selectTasks.getAccessibleContext().setAccessibleName("Select tasks for bulk actions");
        view=state.view; sort=state.sort; month=state.month;
        var p=stack();
        var board=stack();
        // The one thing this page makes sits on its title's line; the views and
        // the tools that refine them follow, two rows where there were three.
        var create=accentButton("New task",()->edit(null));
        create.setName("task.new");
        create.setToolTipText("Add a task");
        p.add(YoruApp.pageHeaderFor("Tasks","TASKS · NOTES · DUE DATES",create));

        var tabs=new JPanel(new WrapFlowLayout(FlowLayout.LEFT,SPACE_XS,SPACE_XS));
        tabs.setOpaque(false);
        for(var value:View.values()) {
            var b=button(value.label,()->{view=value;rebuildRows();});
            b.setName("view."+value.name().toLowerCase());
            viewButtons.put(value,b);tabs.add(b);
        }
        // What the list is showing belongs beside the views that decide it, and
        // folds under them at the window's minimum rather than running off it.
        tabsRow=splitRow(tabs,summary);
        board.add(tabsRow);gap(board,SPACE_MD);

        order=plainCombo(new JComboBox<>(Sort.values()));
        order.setName("task.sort");
        // One control height for the app; the width is this control's own.
        order.setPreferredSize(new Dimension(grow(SPACE_XXL*4),controlHeight()));
        order.setSelectedItem(sort);
        order.addActionListener(e->{if(restoring)return;sort=(Sort)order.getSelectedItem();rebuildRows();});
        sortControls.setOpaque(false);
        sortControls.add(label("Sort",TYPE_CAPTION,MUTED));
        sortControls.add(order);
        var tags=button("Tags",()->TagEditor.open(this,tracker,this::rebuildRows));
        tags.setName("task.tags");
        var properties=button("Properties",()->PropertyManager.open(this,tracker,this::rebuildRows));
        properties.setName("task.properties");
        properties.setToolTipText("Your own properties and statuses");
        var importer=button("Import ▾",()->{});
        importer.setName("task.import");
        importer.addActionListener(e->importMenu().show(importer,0,importer.getHeight()));
        var left=new JPanel(new WrapFlowLayout(FlowLayout.LEFT,SPACE_SM,SPACE_XS));
        left.setOpaque(false);
        left.add(sortControls);left.add(tags);left.add(properties);left.add(importer);left.add(selectTasks);
        var find=new JPanel(new BorderLayout(SPACE_SM,0));
        find.setOpaque(false);
        var caption=label("Search",TYPE_LABEL,MUTED);
        caption.setLabelFor(search);
        // A width of its own: in the slack of a BorderLayout a text field grows
        // to the width of the window, and a search box the width of the page
        // reads as the page's subject rather than as one of its tools.
        search.setPreferredSize(new Dimension(grow(SPACE_XXL*8),controlHeight()));
        search.setName("task.search");
        search.setText(state.query);
        search.getAccessibleContext().setAccessibleName("Search task titles, notes and tags");
        search.setToolTipText("Search titles, notes and tags · Escape to clear");
        clearSearch.setName("task.search.clear");
        find.add(caption,BorderLayout.WEST);
        find.add(search,BorderLayout.CENTER);
        find.add(clearSearch,BorderLayout.EAST);
        var tools=splitRow(left,find);
        search.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"),"clearSearch");
        search.getActionMap().put("clearSearch",new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { search.setText(""); }
        });
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { if(!restoring)rebuildRows(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { if(!restoring)rebuildRows(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { if(!restoring)rebuildRows(); }
        });
        toolsRow=tools;
        board.add(tools);gap(board,SPACE_MD);
        // Today's daily habits are checked off here too, as a place of their
        // own on the rail, rather than on the page that shows how they are
        // going (#54, #56).
        inboxHelp.setName("tasks.inbox.help");
        board.add(inboxHelp);
        board.add(bulk);
        board.add(rows);gap(board,SPACE_MD);
        rail.setName("tasks.rail");
        p.add(new TaskLists.Rail(rail,board));
        add(p,BorderLayout.NORTH);
        rebuildRows();
    }

    /** A view tab: quiet until chosen, then filled as Notion fills it, and bold so it is not told by colour alone. */
    private static void tab(JButton b,boolean chosen) {
        b.setBackground(chosen?LINE:BG);
        b.setForeground(chosen?TEXT:MUTED);
        b.setFont(chosen?labelFont().deriveFont(Font.BOLD):labelFont());
        b.setBorder(new EmptyBorder(SPACE_XS,SPACE_MD,SPACE_XS,SPACE_MD));
        b.getAccessibleContext().setAccessibleDescription(chosen?"Selected view":null);
    }

    private List<Task> visible() {
        var today=LocalDate.now();
        String place=state.scope;
        var filtered=tracker.state().tasks().stream().filter(t->TaskLists.holds(place,t)).filter(t->switch(view) {
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
            case STATUS->Comparator.comparing((Task t)->statusRank(t))
                .thenComparing(t->t.workOn()==null?LocalDate.MAX:t.workOn());
            // Most urgent first; no priority last, then by date as the due sort does (#68).
            case PRIORITY->Comparator.comparing((Task t)->t.priority()==Priority.NONE?Integer.MAX_VALUE:-t.priority().ordinal())
                .thenComparing(t->t.workOn()==null?LocalDate.MAX:t.workOn());
        };
        String query=search.getText().strip().toLowerCase(Locale.ROOT);
        return filtered.filter(t->matches(t,query)).sorted(by).toList();
    }

    /**
     * Manual order is a total order over every task, so it can only be edited
     * from the unfiltered list. Reordering four of nine visible rows would
     * renumber those four and interleave them with the five it could not see.
     */
    private boolean matches(Task task,String query) {
        if(query.isEmpty())return true;
        String tags=String.join("\n",tagsOf(task).stream().map(Tag::name).toList());
        return (task.title()+"\n"+task.notes()+"\n"+tags+"\n"+valuesText(task)).toLowerCase(Locale.ROOT).contains(query);
    }

    /** A task's property values as words, so a search finds a task by what its properties hold (#68). */
    private String valuesText(Task task) {
        var out=new StringBuilder();
        var database=tracker.state().database();
        for(var entry:task.values().entrySet()) {
            var property=database.property(entry.getKey());
            if(property==null) continue;
            switch(entry.getValue()) {
                case Value.Choice c->{ var o=property.option(c.option()); if(o!=null) out.append(o.name()).append('\n'); }
                case Value.Choices cs->{ for(var id:cs.options()){ var o=property.option(id); if(o!=null) out.append(o.name()).append('\n'); } }
                case Value.Tick ignored->{ }
                default->out.append(TaskTable.text(entry.getValue())).append('\n');
            }
        }
        return out.toString();
    }

    /** Where a task's status falls in the order the status button steps through. */
    private int statusRank(Task task) {
        return tracker.properties().statusChoices().indexOf(tracker.properties().statusOf(task));
    }

    private boolean reorderable() { return sort==Sort.MANUAL&&view==View.ALL&&search.getText().isBlank(); }

    /** The place on the rail the page is showing, or All when a list has been deleted from under it. */
    private String place() {
        if(!TaskLists.exists(tracker.state(),state.scope)) state.scope=TaskLists.ALL;
        return state.scope;
    }

    /** Shows another place on the rail, with the view and search it was last left with. */
    void showPlace(String place) {
        bulk.stop();
        state.view=view; state.sort=sort; state.month=month; state.query=search.getText();
        state.enter(place);
        view=state.view; sort=state.sort; month=state.month;
        restoring=true;
        try { search.setText(state.query); order.setSelectedItem(sort); }
        finally { restoring=false; }
        rebuildRows();
    }

    /** The list a new task is filed in: the one on screen, or none for the Inbox. */
    private UUID newTaskList() { return TaskLists.listOf(place()); }

    private void rebuildRows() {
        String place=place();
        inboxHelp.setVisible(TaskLists.INBOX.equals(place));
        state.view=view; state.sort=sort; state.month=month; state.query=search.getText();
        clearSearch.setEnabled(!search.getText().isEmpty());
        rows.removeAll();
        rowPanels.clear();
        TaskLists.fill(rail,tracker,place,this::showPlace,this::rebuildRows,railEntries);
        boolean habits=place.equals(TaskLists.HABITS);
        boolean canSelect = !habits && view != View.CALENDAR;
        bulk.update(canSelect ? visible() : List.of(), canSelect);
        selectTasks.setEnabled(canSelect);
        selectTasks.setText(bulk.active() ? "Cancel selection" : "Select");
        selectTasks.getAccessibleContext().setAccessibleName(bulk.active() ? "Cancel task selection" : "Select tasks for bulk actions");
        selectionChecks.clear();
        tabsRow.setVisible(!habits);
        toolsRow.setVisible(!habits);
        if(habits) {
            rows.add(HabitChecklist.card(tracker,()->{refresh.run();},this::error));
            rows.revalidate();rows.repaint();revalidate();repaint();
            return;
        }
        viewButtons.forEach((value,b)->tab(b,value==view));
        // The calendar is ordered by date. Offering a sort there would be a
        // control that silently does nothing.
        sortControls.setVisible(view!=View.CALENDAR);
        var tasks=visible();
        int open=(int)tracker.state().tasks().stream().filter(t->t.status()!=TaskStatus.DONE).count();
        summary.setText(TaskLists.name(tracker.state(),place)+" · "+tasks.size()+" shown · "+open+" open · "+tracker.state().tasks().size()+" total"
            +(view==View.CALENDAR?" · drag a task onto a day to move it"
                :reorderable()?"":" · reorder in All with search cleared"));
        if(view==View.CALENDAR) { buildCalendar(); return; }
        columns=TaskTable.columns(tracker.state().database(),tasks,TaskLists.listOf(place));
        var table=table();
        table.add(TaskTable.header(columns));
        if(tasks.isEmpty()) {
            var headline=switch(view) {
                case ALL->"No tasks yet.";
                case TODAY->"Nothing due today or overdue.";
                case SOON->"Nothing due in the next five days.";
                case OPEN->"Nothing open. All caught up.";
                case DONE->"Nothing completed yet.";
                case CALENDAR->"No tasks planned this month.";
            };
            boolean searching=!search.getText().isBlank();
            var empty=emptyState(searching?"No matching tasks.":headline,
                searching?"Try another title, note or tag, or clear your search.":"Add one with New task, or bring a list in from Import.",null);
            empty.setBorder(new EmptyBorder(SPACE_LG,SPACE_MD,SPACE_LG,SPACE_MD));
            table.add(empty);
        }
        for(int i=0;i<tasks.size();i++) { var line=taskRow(tasks,i); rowPanels.add(line); table.add(line); }
        table.add(newRow());
        rows.add(table);
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

        var calendar=new TaskCalendar(tracker.state().withTasks(visible()),month,LocalDate.now(),new TaskCalendar.Edits() {
            @Override public void reschedule(UUID taskId,LocalDate date) {
                var task=tracker.state().tasks().stream().filter(t->t.id().equals(taskId)).findFirst().orElse(null);
                if(task==null||Objects.equals(task.plannedFor(),date))return;
                try {
                    // Dragging moves the *plan*. A deadline is not something you drag;
                    // it is changed deliberately, in the editor.
                    tracker.updateTask(merged(task,task.activityId(),task.tagIds(),task.title(),task.notes(),
                        task.due(),task.status(),0,date));
                    rebuildRows();
                } catch(Exception e){error(e);}
            }
            @Override public void create(LocalDate date) { edit(null,date); }
            @Override public void open(UUID taskId) {
                tracker.state().tasks().stream().filter(t->t.id().equals(taskId)).findFirst().ifPresent(t->edit(t));
            }
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

    /**
     * The table's body. Each row is told the table's width before it is asked its
     * height, so a title that wraps is measured at the width it will get; when
     * the width changes, the page lays out once more with the new heights.
     *
     * BoxLayout caches every row's size until the container is invalidated, and
     * resizing a row does not invalidate anything, so the cache is dropped here
     * by hand. Without that a title wrapped to three lines kept a row measured
     * for two at the old width, and its last line was cut off.
     */
    private static JPanel table() {
        var table=new JPanel() {
            private int measured=-1;
            @Override public void doLayout() {
                int width=getWidth()-getInsets().left-getInsets().right;
                if(width!=measured) {
                    measured=width;
                    for(var row:getComponents()) row.setSize(width,row.getHeight());
                    ((BoxLayout)getLayout()).invalidateLayout(this);
                    SwingUtilities.invokeLater(this::revalidate);
                }
                super.doLayout();
            }
        };
        table.setLayout(new BoxLayout(table,BoxLayout.Y_AXIS));
        table.setOpaque(true);
        table.setBackground(PANEL);
        table.setBorder(new MatteBorder(HAIRLINE,0,HAIRLINE,0,LINE));
        table.setAlignmentX(0);
        return table;
    }


    /**
     * Lights a row while the pointer is over it.
     *
     * A row is a control — its title opens the task, its menu acts on it — and
     * it said nothing about that until something inside it was reached. The
     * fill moves the way a hovered button's does, a step short of it: the same
     * move over a whole row at a button's strength reads as a selection rather
     * than as the pointer.
     */
    private static void lightOnHover(JPanel line) {
        line.setOpaque(false);
        var over=new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                line.setOpaque(true);
                line.setBackground(shade(PANEL,DARK?12:-8));
                line.repaint();
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                // Only when the pointer has left the row itself: moving onto a
                // control inside it is still being over the row.
                if(line.contains(e.getPoint())) return;
                line.setOpaque(false);
                line.repaint();
            }
        };
        line.addMouseListener(over);
    }


    private JPanel taskRow(List<Task> tasks,int index) {
        var task=tasks.get(index);
        boolean done=task.status()==TaskStatus.DONE;
        var line=TaskTable.row(columns);
        lightOnHover(line);
        line.setBorder(restingBorder());

        var check=new JCheckBox();
        check.setOpaque(false);
        if (bulk.active()) {
            check.setSelected(bulk.selected(task.id()));
            check.setName("task.select." + task.id());
            check.getAccessibleContext().setAccessibleName("Select task: " + task.title());
            check.setToolTipText("Select for bulk actions · Space toggles selection");
            selectionChecks.put(task.id(), check);
            check.addActionListener(e -> selectTask(task.id()));
        } else {
            check.setSelected(done);
            check.setName("task.done."+task.id());
            check.getAccessibleContext().setAccessibleName("Done: "+task.title());
            check.setToolTipText(done?"Mark as not done":"Mark as done");
            check.addActionListener(e->status(task,check.isSelected()?TaskStatus.DONE:TaskStatus.TODO));
        }
        line.add(check);

        // The owner's own status inside the group, or the group's (#68).
        var own=tracker.properties().statusOf(task);
        var status=button(own.label(),()->cycle(task));
        status.setName("task.status."+task.id());
        status.setEnabled(!bulk.active());
        status.setBackground(TagChips.wash(own.option()!=null?new Color(own.option().colour())
            :switch(task.status()){case TODO->MUTED;case DOING->GOLD;case DONE->CYAN;}));
        status.setForeground(TEXT);
        status.setFont(captionFont());
        status.setBorder(new EmptyBorder(RING,SPACE_SM,RING,SPACE_SM));
        status.setToolTipText("Click to move this task to its next status");
        status.getAccessibleContext().setAccessibleName("Status: "+own.label()+". Activate for the next status");
        line.add(status);

        var title=new JTextArea(task.title());
        title.setName("task.title."+task.id());
        title.setEditable(false);
        title.setFocusable(false);
        title.setHighlighter(null);
        title.setOpaque(false);
        title.setLineWrap(true);
        title.setWrapStyleWord(true);
        title.setFont(labelFont());
        title.setForeground(done?MUTED:TEXT);
        title.setBorder(new EmptyBorder(0,0,0,0));
        title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        title.setToolTipText(task.notes().isBlank()?"Open to edit":task.notes());
        title.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==1) {
                    if (bulk.active()) selectTask(task.id()); else edit(task);
                }
            }
        });
        line.add(title);

        var tags=new TagChips.Cell(tagsOf(task),()->editTags(task));
        tags.setName("task.tags."+task.id());
        line.add(tags);
        line.add(due(task));
        // Priority and the place's properties, in the table's own columns (#68).
        for(var column:columns) {
            if(column.kind()==TaskTable.Kind.PRIORITY) line.add(TaskTable.priority(task,cellEdits));
            else if(column.kind()==TaskTable.Kind.PROPERTY) line.add(TaskTable.cell(task,column.property(),cellEdits,ZoneId.systemDefault()));
        }

        var more=button("⋯",()->{});
        more.setName("task.menu."+task.id());
        more.setBackground(PANEL);
        more.setForeground(MUTED);
        more.setBorder(new EmptyBorder(RING,SPACE_SM,RING,SPACE_SM));
        more.setToolTipText("Edit, track, link pages (" + task.pageIds().size() + "), move or delete");
        more.getAccessibleContext().setAccessibleName("Actions for "+task.title());
        more.addActionListener(e->{var menu=rowMenu(task.id());if(menu!=null)menu.show(more,0,more.getHeight());});
        line.add(more);

        install(line,new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { open(e); }
            @Override public void mouseReleased(MouseEvent e) { open(e); }
            private void open(MouseEvent e) {
                if(!e.isPopupTrigger()) return;
                var menu=rowMenu(task.id());
                if(menu!=null) menu.show(e.getComponent(),e.getX(),e.getY());
            }
        });
        if (!bulk.active()) installDrag(line,tasks,index);
        return line;
    }

    private void selectTask(UUID id) {
        bulk.toggle(id);
        SwingUtilities.invokeLater(() -> {
            var check = selectionChecks.get(id);
            if (check != null) check.requestFocusInWindow();
        });
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
     * Pointer drag to reorder (#23), alongside Move up and Move down rather than
     * instead of them: the menu is keyboard reachable and is what a headless test drives.
     * Dropped on a list on the rail, the task is filed there instead (#56), the
     * way Move to in its menu does it; that works in any view, while
     * reordering needs the unfiltered board in My order.
     *
     * Listeners go on the row and on its non-interactive children, because Swing
     * delivers to the deepest component under the cursor and does not walk up —
     * a press on the title would otherwise never reach the row. Buttons and the
     * checkbox consume their own presses, so pressing one does not start a drag.
     */
    private void installDrag(JPanel line,List<Task> tasks,int index) {
        boolean reorder=reorderable();
        install(line,new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragFrom=index; dragTo=index;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if(dragFrom<0) return;
                var source=(Component)e.getSource();
                aim(railEntryAt(source,e.getPoint()));
                if(!reorder) return;
                var inTable=SwingUtilities.convertPoint(source,e.getPoint(),line.getParent());
                dragTo=dropOn!=null?dragFrom:slotAt(inTable.y);
                for(int i=0;i<rowPanels.size();i++)
                    rowPanels.get(i).setBorder(i==dragTo&&dragTo!=dragFrom?insertionBorder():restingBorder());
                line.getParent().repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if(dragFrom<0) return;
                int from=dragFrom, to=dragTo;
                dragFrom=-1; dragTo=-1;
                var target=railEntryAt((Component)e.getSource(),e.getPoint());
                aim(null);
                if(target!=null) {
                    String key=(String)target.getClientProperty(TaskLists.TARGET);
                    var task=tasks.get(from);
                    var list=TaskLists.listOf(key);
                    if(!Objects.equals(list,task.listId())) { moveToList(task,list); return; }
                }
                if(!reorder||to<0||to==from) { rowPanels.forEach(row->row.setBorder(restingBorder())); return; }
                moveTo(tasks,from,to);
            }
        });
    }

    /** The rail entry a task dragged to {@code at} would be filed in, or null; All is not a place to file a task. */
    private JButton railEntryAt(Component source,Point at) {
        for(var entry:railEntries) {
            if(!entry.isShowing()&&entry.getWidth()==0) continue;
            if(TaskLists.ALL.equals(entry.getClientProperty(TaskLists.TARGET))) continue;
            var inside=SwingUtilities.convertPoint(source,at,entry);
            if(entry.contains(inside)) return entry;
        }
        return null;
    }

    /** Rings the entry a task would be dropped on, and only that one. */
    private void aim(JButton entry) {
        if(entry==dropOn) return;
        if(dropOn!=null) { dropOn.putClientProperty(TaskLists.DROP,null); dropOn.repaint(); }
        dropOn=entry;
        if(entry!=null) { entry.putClientProperty(TaskLists.DROP,Boolean.TRUE); entry.repaint(); }
    }

    private static void install(Component component,MouseAdapter handler) {
        // Anything that handles its own clicks is left alone.
        if(component instanceof AbstractButton||component instanceof JComboBox<?>) return;
        component.addMouseListener(handler);
        component.addMouseMotionListener(handler);
        if(component instanceof Container container)
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
        try{tracker.reorderTasks(everywhere(ids));rebuildRows();}catch(Exception e){error(e);}
    }

    /**
     * A new order for the tasks on screen, as a new order for every task.
     *
     * Manual order is one total order over every task, and a list shows only
     * some of them. The ones on screen swap among the places they already hold
     * in it, and every other task keeps its place, so reordering a list never
     * shuffles the tasks in another (#56).
     */
    List<UUID> everywhere(List<UUID> shown) {
        var all=tracker.state().tasks().stream()
            .sorted(Comparator.comparingInt(Task::order).thenComparing(Task::createdAt)).map(Task::id).toList();
        var onScreen=new HashSet<>(shown);
        var next=shown.iterator();
        return all.stream().map(id->onScreen.contains(id)?next.next():id).toList();
    }

    private JLabel due(Task task) {
        var when=task.workOn();
        var l=label("",TYPE_CAPTION,MUTED);
        l.setName("task.due."+task.id());
        if(when==null) { l.getAccessibleContext().setAccessibleName("No date"); return l; }
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
        var text=when.equals(today)?"Today"
            :when.equals(today.plusDays(1))?"Tomorrow"
            :DateText.longDate(when);
        // A planned day that is not the deadline gets a marker, because the two
        // being different is the thing worth noticing.
        if(task.plannedFor()!=null&&task.due()!=null&&!task.plannedFor().equals(task.due())) text="→ "+text;
        // The time it is due, when it has one (#74).
        if(task.dueTime()!=null&&(task.plannedFor()==null||task.plannedFor().equals(task.due()))) text=text+", "+DateText.time(task.dueTime());
        // A repeating task says so where its date is, since the date is what repeats (#57).
        if(task.repeats()) text="↻ "+text;
        l.setText(text);
        l.setForeground(colour);
        if(task.dueTime()!=null) l.setToolTipText("Due "+DateText.date(task.due())+" at "+DateText.time(task.dueTime()));
        if(task.scheduledLate())
            l.setToolTipText("Planned for "+DateText.date(task.plannedFor())+", but due "+DateText.date(task.due()));
        else if(task.plannedFor()!=null&&task.due()!=null)
            l.setToolTipText("Planned for "+DateText.date(task.plannedFor())+", due "+DateText.date(task.due()));
        else if(when.isBefore(today)&&task.status()!=TaskStatus.DONE)
            l.setToolTipText("Overdue since "+DateText.date(when));
        if(task.repeats()) {
            var history=task.history();
            String record=history.isEmpty()?"":" · done "+dev.yoru.application.Repeats.done(history)+" of "+history.size()
                +(dev.yoru.application.Repeats.streak(history)>1?", "+dev.yoru.application.Repeats.streak(history)+" in a row":"");
            String before=l.getToolTipText();
            l.setToolTipText((before==null?"":before+" · ")+RepeatField.describe(task.repeat())+record);
            l.getAccessibleContext().setAccessibleName(text.replace("↻ ","")+", repeats "+RepeatField.inSentence(task.repeat()));
        }
        return l;
    }

    /** A task's tags, in the order it was given them. */
    private List<Tag> tagsOf(Task task) {
        var byId=new HashMap<UUID,Tag>();
        tracker.state().tags().forEach(t->byId.put(t.id(),t));
        return task.tagIds().stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * The tags of one task, changed from its row (#66): the same field the
     * editor has, without the rest of the form.
     */
    private void editTags(Task task) {
        var field=new TagField(tracker.state().tags(),task.tagIds());
        while(Dialogs.confirm(this,field,"Tags · "+task.title(),"Save")) {
            try {
                var current=tracker.state().tasks().stream().filter(t->t.id().equals(task.id())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Task no longer exists."));
                tracker.saveTask(current.withTags(field.tagIds()),field.newTags());
                rebuildRows();
                return;
            } catch(Exception e){error(e);}
        }
    }

    /**
     * The last line of the table, as in Notion: a task typed where the list
     * ends, its date, tags, list, priority and repeat read from the line (#74),
     * with the full form a button away.
     */
    private JComponent newRow() {
        var slot=new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
        };
        slot.setOpaque(false);
        slot.setAlignmentX(0);
        slot.add(quickAdd,BorderLayout.CENTER);
        return slot;
    }

    /** What a typed line is read against: today, the week start, and the vault's tags and lists. */
    private dev.yoru.application.QuickAdd.Context quickContext() {
        var state=tracker.state();
        return new dev.yoru.application.QuickAdd.Context(LocalDate.now(),state.settings().weekStartsOn(),
            state.tags().stream().map(Tag::name).toList(),state.lists().stream().map(TaskList::name).toList());
    }

    /** Saves a typed line as a task in the place on screen, with the tags it names made in the same write. */
    void quickAdd(dev.yoru.application.QuickAdd.Result line) {
        try {
            var draft=dev.yoru.application.QuickAdd.draft(line,tracker.state(),newTaskList(),LocalDate.now(),Instant.now(),TagEditor::paletteColour);
            tracker.properties().save(draft.task(),draft.newTags(),Map.of());
            quickAdd.clear();
            rebuildRows();
            SwingUtilities.invokeLater(()->quickAdd.field().requestFocusInWindow());
        } catch(Exception e){error(e);}
    }

    /** The quick-add line, for the tests and the command palette. */
    QuickAddField quickAddField() { return quickAdd; }

    void pageOpener(java.util.function.Consumer<UUID> opener) { this.openPage = opener; }

    private void linkPage(Task task) {
        var resolver = new dev.yoru.pages.Links.Resolver(tracker.state().notes());
        var chosen = QuickSwitcher.ask(this, "Link a page", "Choose a page for this task", query ->
            QuickSwitcher.rank(resolver, query, 50).stream().map(p -> QuickSwitcher.Row.page(p, resolver.path(p))).toList());
        if (chosen == null) return;
        try { tracker.pages().linkTask(task.id(), chosen.page()); rebuildRows(); }
        catch (Exception e) { error(e); }
    }

    /** The ⋯ menu for one task on the board, or null when it is not shown. Package-private for the tests. */
    JPopupMenu rowMenu(UUID id) {
        var tasks=visible();
        int index=-1;
        for(int i=0;i<tasks.size();i++) if(tasks.get(i).id().equals(id)) index=i;
        if(index<0) return null;
        int at=index;
        var task=tasks.get(at);
        boolean done=task.status()==TaskStatus.DONE;
        var menu=Menus.popup();
        menu.add(Menus.item("Edit…","task.edit."+id,true,()->edit(task),null));
        menu.add(Menus.item(bulk.selected(id) ? "Deselect task" : "Select task", "task.selectMenu." + id,
            true, () -> selectTask(id), null));
        menu.add(Menus.item("Track time","task.track."+id,task.activityId()!=null&&!done,()->track(task),
            task.activityId()==null?"Assign an activity to time this task":"This task is finished"));
        menu.add(Menus.item("Priority…","task.priority.choose."+id,true,()->choosePriority(task),null));
        menu.addSeparator();
        menu.add(Menus.item("Link a page…", "task.linkPage." + id, true, () -> linkPage(task), null));
        menu.add(Menus.item("New page for task", "task.newPage." + id, true, () -> {
            try { openPage.accept(tracker.pages().createPageForTask(id, null).id()); }
            catch (Exception e) { error(e); }
        }, null));
        for (var pageId : task.pageIds()) tracker.state().notes().page(pageId).filter(p -> !p.trashed()).ifPresent(p -> {
            menu.add(Menus.item("Open page: " + p.title(), "task.openPage." + pageId, true, () -> openPage.accept(pageId), null));
            menu.add(Menus.item("Unlink page: " + p.title(), "task.unlinkPage." + pageId, true, () -> {
                try { tracker.pages().unlinkTask(id, pageId); rebuildRows(); } catch (Exception e) { error(e); }
            }, null));
        });
        if(task.repeats()) {
            menu.addSeparator();
            menu.add(Menus.item("Skip this one","task.skip."+id,!done,()->{
                try{tracker.skipOccurrence(id,ZoneId.systemDefault());rebuildRows();}catch(Exception e){error(e);}
            },"This task has finished repeating"));
            menu.add(Menus.item("Stop repeating","task.stopRepeat."+id,true,()->{
                try{tracker.updateTask(task.withRepeat(null));rebuildRows();}catch(Exception e){error(e);}
            },null));
        }
        menu.addSeparator();
        // Filing it elsewhere (#56): the Inbox and every list, the one it is in greyed.
        menu.add(Menus.item("Move to Inbox","task.moveTo."+TaskLists.INBOX+"."+id,task.listId()!=null,
            ()->moveToList(task,null),"Already in the Inbox"));
        for(var list:TaskLists.ordered(tracker.state()))
            menu.add(Menus.item("Move to "+list.name(),"task.moveTo."+list.id()+"."+id,!list.id().equals(task.listId()),
                ()->moveToList(task,list.id()),"Already in "+list.name()));
        menu.addSeparator();
        String why="Reordering needs the All view in My order with search cleared";
        menu.add(Menus.item("Move up","task.up."+id,reorderable()&&at>0,()->move(tasks,at,-1),reorderable()?null:why));
        menu.add(Menus.item("Move down","task.down."+id,reorderable()&&at<tasks.size()-1,()->move(tasks,at,1),reorderable()?null:why));
        menu.addSeparator();
        menu.add(Menus.item("Delete…","task.delete."+id,true,()->delete(task),null));
        return menu;
    }

    /** Import's two sources in one menu, rather than two buttons that looked as important as New. */
    JPopupMenu importMenu() {
        var menu=Menus.popup();
        menu.add(Menus.item("Paste task proposals…","task.import.paste",true,this::importPaste,null));
        menu.add(Menus.item("Notion export…","task.import.notion",true,this::importNotion,null));
        return menu;
    }

    private void moveToList(Task task,UUID list) {
        try{tracker.moveTask(task.id(),list);rebuildRows();}catch(Exception e){error(e);}
    }

    private void track(Task task) {
        try{tracker.start(task.activityId());Dialogs.info(this,"Timer started for this task's activity.");refresh.run();}
        catch(Exception ex){error(ex);}
    }

    private void delete(Task task) {
        if(!Dialogs.confirmDestructive(this,"Delete “"+task.title()+"”?\n\nIts notes and dates go with it. Time you have tracked is kept.",
            "Delete task","Delete")) return;
        try{tracker.deleteTask(task.id());rebuildRows();}catch(Exception e){error(e);}
    }

    private void status(Task task,TaskStatus next) {
        try{tracker.taskStatus(task.id(),next);rebuildRows();}catch(Exception e){error(e);}
    }

    /** The next status in the order the owner keeps them, their own inside each group (#68). */
    private void cycle(Task task) {
        try{tracker.properties().setStatus(task.id(),tracker.properties().nextStatus(task),ZoneId.systemDefault());rebuildRows();}
        catch(Exception e){error(e);}
    }

    /** A task's priority, chosen from the five (#68). */
    void choosePriority(Task task) {
        var chosen=Dialogs.select(this,"Priority for “"+task.title()+"”","Priority",List.of(Priority.values()));
        if(chosen==null) return;
        try{tracker.properties().setPriority(task.id(),chosen);rebuildRows();}catch(Exception e){error(e);}
    }

    /** One property's value on one task, in the control its type has; reopened on a refusal with what was typed (#68). */
    void editValue(Task task,Property property) {
        var current=tracker.state().tasks().stream().filter(t->t.id().equals(task.id())).findFirst().orElse(task);
        var editor=PropertyEditors.of(property,current.values().get(property.id()));
        var form=stack();
        form.add(label(current.title(),TYPE_CAPTION,MUTED));
        gap(form,SPACE_SM);
        form.add(editor.component());
        while(Dialogs.confirm(this,form,property.name(),"Save")) {
            try{
                var entry=editor.entry();
                if(entry!=null) tracker.properties().set(task.id(),property.id(),entry);
                rebuildRows();
                return;
            }catch(Exception e){error(e);}
        }
    }

    /**
     * Opens a link a URL property holds, in the browser. Only a web or mail
     * address: a link to a program or a file on the disk is not opened from a
     * cell someone may click without reading.
     */
    private void openLink(String link) {
        try {
            var uri=java.net.URI.create(link.strip());
            var scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            if(!Set.of("http","https","mailto").contains(scheme))
                throw new IllegalArgumentException("Only web and mail links open from here: "+link);
            if(!java.awt.Desktop.isDesktopSupported()) throw new IllegalArgumentException("This computer cannot open links from Yoru.");
            var desktop=java.awt.Desktop.getDesktop();
            if(scheme.equals("mailto")) desktop.mail(uri); else desktop.browse(uri);
        } catch(IllegalArgumentException e) { error(e); }
        catch(Exception e) { error(new IllegalArgumentException("That link could not be opened: "+e.getMessage())); }
    }

    private void move(List<Task> tasks,int index,int delta) {
        var ids=new ArrayList<>(tasks.stream().map(Task::id).toList());
        Collections.swap(ids,index,index+delta);
        try{tracker.reorderTasks(everywhere(ids));rebuildRows();}catch(Exception e){error(e);}
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
    static Task merged(Task existing,UUID activityId,List<UUID> tagIds,String title,String notes,
                       LocalDate due,TaskStatus status,int orderForNew) {
        return merged(existing,activityId,tagIds,title,notes,due,status,orderForNew,
            existing==null?null:existing.plannedFor());
    }

    static Task merged(Task existing,UUID activityId,List<UUID> tagIds,String title,String notes,
                       LocalDate due,TaskStatus status,int orderForNew,LocalDate plannedFor) {
        return merged(existing,activityId,tagIds,title,notes,due,status,orderForNew,plannedFor,null);
    }

    static Task merged(Task existing,UUID activityId,List<UUID> tagIds,String title,String notes,
                       LocalDate due,TaskStatus status,int orderForNew,LocalDate plannedFor,UUID listForNew) {
        return new Task(
            existing==null?UUID.randomUUID():existing.id(),
            activityId,tagIds,title,notes,due,status,
            existing==null?"":existing.source(),
            existing==null?Instant.now():existing.createdAt(),
            existing==null?orderForNew:existing.order(),
            plannedFor,
            // The form does not show page links, so an edit keeps them.
            existing==null?List.of():existing.pageIds(),
            // A new task goes in the list on screen; an edit stays where it is filed (#56).
            existing==null?listForNew:existing.listId(),
            // Its rule is set by the form's own field; the occurrences behind it always stay (#57).
            existing==null?null:existing.repeat(),
            existing==null?List.of():existing.history(),
            // Priority, properties and when it was edited stay (#68); the owner's
            // status stays while the group does, since it belongs to one group.
            existing==null?Details.NONE
                :carried(existing,status,due));
    }

    /** What an edit keeps of a task's details: its status of the owner's while the group stays, its time while it has a date. */
    private static Details carried(Task existing,TaskStatus status,LocalDate due) {
        var kept=status==existing.status()?existing.details():existing.details().withStatus(null);
        return due==null?kept.withDueTime(null):kept;
    }

    /** A new task lands at the bottom of the manual order, not on top of row one. */
    private int nextOrder() {
        return tracker.state().tasks().stream().mapToInt(Task::order).max().orElse(-1)+1;
    }

    /**
     * The due date a form starts from: the day a new task is made on (#67), and
     * its own for one being edited.
     *
     * Almost every task written down is due that day, and the date is one click
     * to clear when it is not; an empty field made a deadline something you had
     * to remember to set, so most tasks had none. The day is today for New task
     * and the + New task row, and the day double-clicked for one made from the
     * calendar.
     */
    static DateField dueField(Task existing,LocalDate day) {
        return new DateField(existing==null?day:existing.due(),"Due",true);
    }

    /** Today on this computer's clock and in its zone, which is the day a task is written down on. */
    static LocalDate today(Clock clock) { return LocalDate.now(clock); }

    private void edit(Task existing) { edit(existing,today(Clock.systemDefaultZone())); }

    private void edit(Task existing,LocalDate day) {
        var title=new JTextField(existing==null?"":existing.title(),36);
        var notes=new JTextArea(existing==null?"":existing.notes(),5,36);notes.setLineWrap(true);notes.setWrapStyleWord(true);
        var due=dueField(existing,day);
        // The time on the due date, or none (#74).
        var dueTime=styleInput(new JTextField(existing==null||existing.dueTime()==null?"":DateText.time(existing.dueTime()),10));
        dueTime.setName("task.form.dueTime");
        dueTime.getAccessibleContext().setAccessibleName("Due time, optional");
        var activity=plainCombo(new JComboBox<Object>());activity.addItem("Unassigned");tracker.state().activities().forEach(activity::addItem);
        if(existing!=null&&existing.activityId()!=null)for(int i=1;i<activity.getItemCount();i++)if(((Activity)activity.getItemAt(i)).id().equals(existing.activityId()))activity.setSelectedIndex(i);
        // Every status the owner keeps, their own inside each group (#68).
        var choices=tracker.properties().statusChoices();
        var status=plainCombo(new JComboBox<>(choices.toArray(dev.yoru.application.TaskProperties.StatusChoice[]::new)));
        status.setSelectedItem(existing==null?choices.getFirst():tracker.properties().statusOf(existing));
        status.setName("task.form.status");
        status.getAccessibleContext().setAccessibleName("Status");
        var priority=plainCombo(new JComboBox<>(Priority.values()));
        priority.setSelectedItem(existing==null?Priority.NONE:existing.priority());
        priority.setName("task.form.priority");
        priority.getAccessibleContext().setAccessibleName("Priority");
        var fields=new PropertyEditors.Section(tracker.state().database(),existing,existing==null?newTaskList():existing.listId());
        // Tags are typed, and a new one is made right here (#66).
        var tags=new TagField(tracker.state().tags(),existing==null?List.of():existing.tagIds());
        var planned=new DateField(existing==null?null:existing.plannedFor(),"Plan for",true);
        planned.setName("task.plannedFor");
        var repeat=new RepeatField(existing==null?null:existing.repeat(),tracker.state().settings().weekStartsOn());
        var form=stack();form.add(new JLabel("Title"));form.add(title);gap(form,SPACE_MD);form.add(new JLabel("Notes"));form.add(new JScrollPane(notes));gap(form,SPACE_MD);
        form.add(new JLabel("Due · the deadline"));form.add(due);gap(form,SPACE_SM);
        form.add(new JLabel("Due time · optional, like 5pm or 17:00"));form.add(dueTime);gap(form,SPACE_MD);
        form.add(new JLabel("Plan for · the day you mean to do it · blank to use the deadline"));form.add(planned);gap(form,SPACE_MD);
        form.add(new JLabel("Repeat · from the due date"));form.add(repeat);gap(form,SPACE_MD);
        form.add(new JLabel("Activity"));form.add(activity);gap(form,SPACE_MD);form.add(new JLabel("Status"));form.add(status);gap(form,SPACE_MD);
        form.add(new JLabel("Priority"));form.add(priority);gap(form,SPACE_MD);form.add(new JLabel("Tags · type to find or create"));form.add(tags);
        if(!fields.isEmpty()){gap(form,SPACE_MD);form.add(label("PROPERTIES",TYPE_CAPTION,MUTED));gap(form,SPACE_XS);form.add(fields);}
        // Reopened on a refusal with everything as typed, rather than closed with it lost.
        while(Dialogs.confirm(this,form,existing==null?"New task":"Edit task","Save")) {
            try {
                var deadline=due.value();
                var rule=repeat.value(deadline);
                // A repeat comes back on a date: with none given, it starts today.
                if(rule!=null&&deadline==null) deadline=rule.start();
                var chosen=(dev.yoru.application.TaskProperties.StatusChoice)status.getSelectedItem();
                var task=merged(existing,
                    activity.getSelectedItem() instanceof Activity a?a.id():null,
                    tags.tagIds(),
                    title.getText(),notes.getText(),deadline,
                    chosen.group(),nextOrder(),planned.value(),newTaskList()).withRepeat(rule);
                var time=dueTime.getText().isBlank()?null:DateText.parseTime(dueTime.getText());
                if(time!=null&&task.due()==null) throw new IllegalArgumentException("Choose a due date for the due time, or clear the time.");
                task=task.withDetails(task.details().withPriority((Priority)priority.getSelectedItem()).withStatus(chosen.id()).withDueTime(time));
                // The task, the tags made for it and its property values are one write (#68).
                tracker.properties().save(task,tags.newTags(),fields.entries());
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
                int added=tracker.importTasks(batch.newTags(),batch.tasks(),batch.database());
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
        var table=new JTable(model);plainTable(table);table.setRowHeight(controlHeight());table.setFont(bodyFont());
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
