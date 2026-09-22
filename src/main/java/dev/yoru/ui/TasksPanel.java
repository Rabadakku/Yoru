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
        MANUAL("My order"), DUE("Due date"), TITLE("Title"), STATUS("Status");
        final String label; Sort(String label){this.label=label;}
        @Override public String toString(){return label;}
    }

    /** Column widths shared by the header and every row: done, status, title, tag, due, menu. The title's 0 takes the rest. */
    private static final int[] COLUMNS={SPACE_XL,SPACE_XXL*2+SPACE_MD,0,SPACE_XXL*4,SPACE_XXL*5,SPACE_XXL};
    private static final int TITLE_COLUMN=2;
    /** Marks a label that is a pill, which keeps its own width instead of filling its column. */
    private static final String PILL="yoru.pill";

    private final Tracker tracker;
    private final Runnable refresh;
    private final BooleanSupplier closed;
    private final JPanel rows=stack();
    private final JLabel summary=label("",TYPE_CAPTION,MUTED);
    private final Map<View,JButton> viewButtons=new EnumMap<>(View.class);
    static final class ViewState {
        View view=View.ALL;
        Sort sort=Sort.MANUAL;
        YearMonth month=YearMonth.now();
        String query="";
    }
    private final ViewState state;
    private final JTextField search=styleInput(new JTextField(24));
    private final JButton clearSearch=button("Clear",()->search.setText(""));
    private View view=View.ALL;
    private Sort sort=Sort.MANUAL;
    private YearMonth month=YearMonth.now();
    private final JPanel sortControls=new JPanel(new FlowLayout(FlowLayout.LEFT,SPACE_SM,0));
    private final List<JPanel> rowPanels=new ArrayList<>();
    private int dragFrom=-1, dragTo=-1;

    TasksPanel(Tracker tracker, Runnable refresh, BooleanSupplier closed) {
        this(tracker,refresh,closed,new ViewState());
    }

    TasksPanel(Tracker tracker, Runnable refresh, BooleanSupplier closed, ViewState state) {
        super(new BorderLayout()); setOpaque(false);
        this.tracker=tracker; this.refresh=refresh; this.closed=closed;
        this.state=state;
        view=state.view; sort=state.sort; month=state.month;
        var p=stack();
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
        p.add(splitRow(tabs,summary));gap(p,SPACE_MD);

        var order=plainCombo(new JComboBox<>(Sort.values()));
        order.setName("task.sort");
        // One control height for the app; the width is this control's own.
        order.setPreferredSize(new Dimension(grow(SPACE_XXL*4),controlHeight()));
        order.setSelectedItem(sort);
        order.addActionListener(e->{sort=(Sort)order.getSelectedItem();rebuildRows();});
        sortControls.setOpaque(false);
        sortControls.add(label("Sort",TYPE_CAPTION,MUTED));
        sortControls.add(order);
        var tags=button("Tags",()->TagEditor.open(this,tracker,this::rebuildRows));
        tags.setName("task.tags");
        var importer=button("Import ▾",()->{});
        importer.setName("task.import");
        importer.addActionListener(e->importMenu().show(importer,0,importer.getHeight()));
        var left=new JPanel(new WrapFlowLayout(FlowLayout.LEFT,SPACE_SM,SPACE_XS));
        left.setOpaque(false);
        left.add(sortControls);left.add(tags);left.add(importer);
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
            public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuildRows(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuildRows(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildRows(); }
        });
        p.add(tools);gap(p,SPACE_MD);
        p.add(rows);gap(p,SPACE_MD);
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

    /**
     * A colour washed over the panel, for a pill whose text stays in the body ink.
     *
     * The body ink measured at least 6:1 on every status and palette tag wash on
     * all four themes, where coloured text on a grey pill had needed a darker
     * shade on each light theme just to reach 4.5:1.
     */
    private static Color wash(Color colour) {
        double a=DARK?0.30:0.24;
        return new Color((int)Math.round(colour.getRed()*a+PANEL.getRed()*(1-a)),
            (int)Math.round(colour.getGreen()*a+PANEL.getGreen()*(1-a)),
            (int)Math.round(colour.getBlue()*a+PANEL.getBlue()*(1-a)));
    }

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
        String tag=tracker.state().tags().stream().filter(t->t.id().equals(task.tagId()))
            .map(Tag::name).findFirst().orElse("");
        return (task.title()+"\n"+task.notes()+"\n"+tag).toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean reorderable() { return sort==Sort.MANUAL&&view==View.ALL&&search.getText().isBlank(); }

    private void rebuildRows() {
        state.view=view; state.sort=sort; state.month=month; state.query=search.getText();
        clearSearch.setEnabled(!search.getText().isEmpty());
        rows.removeAll();
        rowPanels.clear();
        viewButtons.forEach((value,b)->tab(b,value==view));
        // The calendar is ordered by date. Offering a sort there would be a
        // control that silently does nothing.
        sortControls.setVisible(view!=View.CALENDAR);
        var tasks=visible();
        int open=(int)tracker.state().tasks().stream().filter(t->t.status()!=TaskStatus.DONE).count();
        summary.setText(tasks.size()+" shown · "+open+" open · "+tracker.state().tasks().size()+" total"
            +(view==View.CALENDAR?" · drag a task onto a day to move it"
                :reorderable()?"":" · reorder in All with search cleared"));
        if(view==View.CALENDAR) { buildCalendar(); return; }
        var table=table();
        table.add(headerRow());
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

        var calendar=new TaskCalendar(tracker.state().withTasks(visible()),month,LocalDate.now(),(taskId,date)->{
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
        table.setBorder(new LineBorder(LINE,HAIRLINE,true));
        table.setAlignmentX(0);
        return table;
    }

    /** One line of the table in the shared columns, never taller than its cells need. */
    private static JPanel tableRow() {
        var line=new JPanel(new Columns()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
        };
        line.setOpaque(false);
        line.setAlignmentX(0);
        return line;
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

    private static JPanel headerRow() {
        var header=tableRow();
        header.setName("task.header");
        header.setBorder(listRow());
        for(String heading:new String[]{"","Status","Task name","Tag","Due",""}) header.add(label(heading,TYPE_CAPTION,MUTED));
        return header;
    }

    private JPanel taskRow(List<Task> tasks,int index) {
        var task=tasks.get(index);
        boolean done=task.status()==TaskStatus.DONE;
        var line=tableRow();
        lightOnHover(line);
        line.setBorder(restingBorder());

        var check=new JCheckBox();
        check.setOpaque(false);
        check.setSelected(done);
        check.setName("task.done."+task.id());
        check.getAccessibleContext().setAccessibleName("Done: "+task.title());
        check.setToolTipText(done?"Mark as not done":"Mark as done");
        check.addActionListener(e->status(task,check.isSelected()?TaskStatus.DONE:TaskStatus.TODO));
        line.add(check);

        var status=button(task.status().label,()->cycle(task));
        status.setName("task.status."+task.id());
        status.setBackground(wash(switch(task.status()){case TODO->MUTED;case DOING->GOLD;case DONE->CYAN;}));
        status.setForeground(TEXT);
        status.setFont(captionFont());
        status.setBorder(new EmptyBorder(RING,SPACE_SM,RING,SPACE_SM));
        status.setToolTipText("Click to move this task to its next status");
        status.getAccessibleContext().setAccessibleName("Status: "+task.status().label+". Activate for the next status");
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
                if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==1) edit(task);
            }
        });
        line.add(title);

        line.add(tagPill(task));
        line.add(due(task));

        var more=button("⋯",()->{});
        more.setName("task.menu."+task.id());
        more.setBackground(PANEL);
        more.setForeground(MUTED);
        more.setBorder(new EmptyBorder(RING,SPACE_SM,RING,SPACE_SM));
        more.setToolTipText("Edit, track, move or delete");
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
        if(reorderable()) installDrag(line,tasks,index);
        return line;
    }

    /** Lays a row's cells into the shared column widths; the title column takes what is left. */
    static final class Columns implements LayoutManager {
        /** The width assumed before a row has one, so a first measurement is a sensible one. */
        private static final int UNSIZED=SPACE_XXL*30;

        @Override public void addLayoutComponent(String name,Component cell) { }
        @Override public void removeLayoutComponent(Component cell) { }

        static int[] widths(int inner) {
            // The fixed columns hold text, so they grow with the text size (#31).
            var out=COLUMNS.clone();
            for(int i=0;i<out.length;i++) out[i]=grow(out[i]);
            int fixed=Arrays.stream(out).sum();
            out[TITLE_COLUMN]=Math.max(SPACE_XXL*4,inner-fixed-SPACE_MD*(COLUMNS.length-1));
            return out;
        }

        private static int inner(Container row) {
            var insets=row.getInsets();
            return (row.getWidth()>0?row.getWidth():UNSIZED)-insets.left-insets.right;
        }

        /** A wrapping title is as tall as its text at this width; anything else is its preferred height. */
        private static int height(Component cell,int width) {
            if(cell instanceof JTextArea wrapping) {
                wrapping.setSize(width,Short.MAX_VALUE);
                return wrapping.getPreferredSize().height;
            }
            return cell.getPreferredSize().height;
        }

        private static boolean fills(Component cell) {
            return cell instanceof JTextArea||cell instanceof JLabel l&&l.getClientProperty(PILL)==null;
        }

        @Override public Dimension preferredLayoutSize(Container row) {
            var insets=row.getInsets();
            int[] w=widths(inner(row));
            int tallest=0;
            for(int i=0;i<Math.min(w.length,row.getComponentCount());i++) tallest=Math.max(tallest,height(row.getComponent(i),w[i]));
            return new Dimension(Arrays.stream(w).sum()+SPACE_MD*(w.length-1)+insets.left+insets.right,
                tallest+insets.top+insets.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container row) { return new Dimension(0,preferredLayoutSize(row).height); }

        @Override public void layoutContainer(Container row) {
            var insets=row.getInsets();
            int[] w=widths(inner(row));
            int tallest=row.getHeight()-insets.top-insets.bottom, x=insets.left;
            for(int i=0;i<Math.min(w.length,row.getComponentCount());i++) {
                var cell=row.getComponent(i);
                int width=fills(cell)?w[i]:Math.min(cell.getPreferredSize().width,w[i]);
                int height=Math.min(tallest,height(cell,w[i]));
                cell.setBounds(x,insets.top+Math.max(0,(tallest-height)/2),width,height);
                x+=w[i]+SPACE_MD;
            }
        }
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
     *
     * Listeners go on the row and on its non-interactive children, because Swing
     * delivers to the deepest component under the cursor and does not walk up —
     * a press on the title would otherwise never reach the row. Buttons and the
     * checkbox consume their own presses, so pressing one does not start a drag.
     */
    private void installDrag(JPanel line,List<Task> tasks,int index) {
        install(line,new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragFrom=index; dragTo=index;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if(dragFrom<0) return;
                var inTable=SwingUtilities.convertPoint((Component)e.getSource(),e.getPoint(),line.getParent());
                dragTo=slotAt(inTable.y);
                for(int i=0;i<rowPanels.size();i++)
                    rowPanels.get(i).setBorder(i==dragTo&&dragTo!=dragFrom?insertionBorder():restingBorder());
                line.getParent().repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if(dragFrom<0) return;
                int from=dragFrom, to=dragTo;
                dragFrom=-1; dragTo=-1;
                if(to<0||to==from) { rowPanels.forEach(row->row.setBorder(restingBorder())); return; }
                moveTo(tasks,from,to);
            }
        });
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
        try{tracker.reorderTasks(ids);rebuildRows();}catch(Exception e){error(e);}
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
        l.setText(text);
        l.setForeground(colour);
        if(task.scheduledLate())
            l.setToolTipText("Planned for "+DateText.date(task.plannedFor())+", but due "+DateText.date(task.due()));
        else if(task.plannedFor()!=null&&task.due()!=null)
            l.setToolTipText("Planned for "+DateText.date(task.plannedFor())+", due "+DateText.date(task.due()));
        else if(when.isBefore(today)&&task.status()!=TaskStatus.DONE)
            l.setToolTipText("Overdue since "+DateText.date(when));
        return l;
    }

    /** Notion's select pill: the tag's name on a wash of its colour, or an empty cell for no tag. */
    private JLabel tagPill(Task task) {
        var tag=tracker.state().tags().stream().filter(t->t.id().equals(task.tagId())).findFirst().orElse(null);
        if(tag==null) return label("",TYPE_CAPTION,MUTED);
        var fill=wash(new Color(tag.colour()));
        var pill=new JLabel(tag.name()) {
            @Override protected void paintComponent(Graphics graphics) {
                var g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(fill);
                g.fillRoundRect(0,0,getWidth(),getHeight(),RADIUS,RADIUS);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        pill.putClientProperty("html.disable",true);
        pill.putClientProperty(PILL,Boolean.TRUE);
        pill.setName("task.tag."+task.id());
        pill.setFont(captionFont());
        pill.setForeground(TEXT);
        pill.setBorder(new EmptyBorder(RING,SPACE_SM,RING,SPACE_SM));
        pill.setToolTipText("Tag: "+tag.name());
        return pill;
    }

    /** The last line of the table, as in Notion: a quiet way to add a task right where the list ends. */
    private JComponent newRow() {
        var add=button("+  New task",()->edit(null));
        add.setName("task.newRow");
        add.setBackground(PANEL);
        add.setForeground(MUTED);
        add.setHorizontalAlignment(SwingConstants.LEFT);
        add.setBorder(new EmptyBorder(SPACE_SM,SPACE_MD,SPACE_SM,SPACE_MD));
        var slot=new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
        };
        slot.setOpaque(false);
        slot.setAlignmentX(0);
        slot.add(add,BorderLayout.CENTER);
        return slot;
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
        var menu=menu();
        menu.add(item("Edit…","task.edit."+id,true,()->edit(task),null));
        menu.add(item("Track time","task.track."+id,task.activityId()!=null&&!done,()->track(task),
            task.activityId()==null?"Assign an activity to time this task":"This task is finished"));
        menu.addSeparator();
        String why="Reordering needs the All view in My order with search cleared";
        menu.add(item("Move up","task.up."+id,reorderable()&&at>0,()->move(tasks,at,-1),reorderable()?null:why));
        menu.add(item("Move down","task.down."+id,reorderable()&&at<tasks.size()-1,()->move(tasks,at,1),reorderable()?null:why));
        menu.addSeparator();
        menu.add(item("Delete…","task.delete."+id,true,()->delete(task),null));
        return menu;
    }

    /** Import's two sources in one menu, rather than two buttons that looked as important as New. */
    JPopupMenu importMenu() {
        var menu=menu();
        menu.add(item("Paste task proposals…","task.import.paste",true,this::importPaste,null));
        menu.add(item("Notion export…","task.import.notion",true,this::importNotion,null));
        return menu;
    }

    private static JPopupMenu menu() {
        var menu=new JPopupMenu() {
            // The look-and-feel's separator is drawn in its own highlight colour,
            // which on these palettes was a bright accent line across the menu.
            @Override public void addSeparator() {
                var line=new JPopupMenu.Separator();
                line.setForeground(LINE);
                line.setBackground(PANEL);
                add(line);
            }
        };
        menu.setBackground(PANEL);
        menu.setBorder(new CompoundBorder(new LineBorder(LINE),new EmptyBorder(SPACE_XS,0,SPACE_XS,0)));
        return menu;
    }

    private static JMenuItem item(String text,String name,boolean enabled,Runnable action,String whyNot) {
        var item=new JMenuItem(text);
        item.setName(name);
        item.setFont(labelFont());
        item.setOpaque(true);
        item.setBackground(PANEL);
        item.setForeground(TEXT);
        item.setBorder(new EmptyBorder(SPACE_XS,SPACE_MD,SPACE_XS,SPACE_MD));
        item.setEnabled(enabled);
        if(!enabled&&whyNot!=null) item.setToolTipText(whyNot);
        item.addActionListener(e->action.run());
        return item;
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

    private void cycle(Task task) {
        status(task,switch(task.status()) {
            case TODO->TaskStatus.DOING; case DOING->TaskStatus.DONE; case DONE->TaskStatus.TODO;
        });
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
            plannedFor,
            // The form does not show page links, so an edit keeps them.
            existing==null?List.of():existing.pageIds());
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
                if(existing==null)tracker.addTask(task);else tracker.updateTask(task);
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
