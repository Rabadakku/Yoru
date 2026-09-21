package dev.yoru.ui;
import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import dev.yoru.persistence.VaultStore;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import static dev.yoru.ui.Theme.*;
public final class YoruApp extends JPanel implements Shell {
    private final Tracker tracker;
    /** The open vault. It is replaced when another vault is switched to (#41). */
    private Repository vault;
    /** The folder Yoru keeps vaults in, and the name of the one that is open. Null in fixtures. */
    private final VaultStore store;
    private String vaultName;
    /**
     * The session's unlock secret for the open vault.
     *
     * Held so a vault change that fails can put the vault back exactly as it
     * was — a rename that could not finish, a deletion the disk refused —
     * without asking for a password the person already gave. It is zeroed when
     * the vault closes.
     */
    private char[] secret;
    private final ZoneId zone=ZoneId.systemDefault();
    private final JPanel content=new JPanel(new BorderLayout());
    private String page="Today";
    private boolean reducedMotion;
    private LocalDate displayDate=LocalDate.now();
    private LocalDate week;
    private final MusicPlayer music=new MusicPlayer();
    private final DateTimeFormatter dateTime=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Window for the focus-distribution chart, in days; 0 means all time. */
    private int mixDays=7;
    /** The game, from Play to Close. It outlives a rebuilt window, as the game itself does. */
    private final GameController game;
    private final GamePage gamePage=new GamePage(this);
    private final CollectionPage collectionPage=new CollectionPage(this);
    private final TodayPage todayPage=new TodayPage(this);
    private final SettingsPage settingsPage=new SettingsPage(this);
    private javax.swing.Timer ticker;
    private JButton recordingStatus;
    private final TasksPanel.ViewState taskViewState=new TasksPanel.ViewState();
    private final Map<String,JButton> navigation = new LinkedHashMap<>();
    private boolean closed;
    /** What the bar needs to show every tab whole; measured when the bar is built. */
    private final int navMinimumWidth;

    /** The size the pages are reviewed at: the frame never opens smaller than this. */
    private static final int MIN_WINDOW_WIDTH=900, MIN_WINDOW_HEIGHT=640;
    /** How tall the fourteen-day chart's plot stands, goal line and all. */
    private static final int CHART_HEIGHT=120;

    /**
     * One row per page: the tab's label, the id the breadcrumb shows, and the
     * page's own title.
     *
     * These were three separate lists and had already drifted — the tab said
     * {@code today}, the breadcrumb said {@code ~/ today}, and the page called
     * itself {@code session.workspace}, which is not the page's name at all.
     * One row now feeds all three, so they cannot disagree again (audit §6.7).
     */
    private record Page(String nav,String id,String title) { }

    private static final List<Page> PAGES=List.of(
        new Page("Today","today","today.session"),
        new Page("Tasks","tasks","tasks.board"),
        new Page("Habits","habits","habits.log"),
        new Page("Schedule","schedule","schedule.week"),
        new Page("Collection","collection","collection.pc"),
        new Page("Game","game","game.campaign"),
        new Page("Data","data","data.overview"),
        new Page("Settings","settings","settings.options"));

    /** The row for a nav label; every page in {@link #PAGES} has exactly one. */
    private static Page rowFor(String nav) {
        return PAGES.stream().filter(p->p.nav().equals(nav)).findFirst().orElse(PAGES.getFirst());
    }

    /**
     * The shared page header for one page, titled from {@link #PAGES}.
     *
     * The title used to be built by each page for itself, at its own size and
     * with its own gap to the subtitle; one page had no gap at all. A page
     * names itself and a subtitle and gets the same header as every other page.
     */
    static JPanel pageHeaderFor(String nav,String subtitle) {
        return pageHeader(rowFor(nav).nav(),subtitle);
    }

    /** The same header with the page's own actions on the title's line. */
    static JPanel pageHeaderFor(String nav,String subtitle,JComponent... actions) {
        return pageHeader(rowFor(nav).nav(),subtitle,actions);
    }

    public YoruApp(Tracker tracker,Repository vault) { this(tracker,vault,null,null,null); }
    private YoruApp(Tracker tracker,Repository vault,VaultStore store,String openName,char[] secret) {
        this(tracker,vault,store,openName,secret,new GameController(tracker));
    }
    private YoruApp(Tracker tracker,Repository vault,VaultStore store,String openName,char[] secret,
                    GameController game) {
        super(new BorderLayout());
        this.tracker=tracker;
        this.vault=vault;
        this.store=store;
        this.vaultName=openName;
        this.secret=secret;
        this.game=game;
        game.listen(new GameController.Listener() {
            @Override public void phaseChanged() { if(!closed) refreshGamePages(true); }
            @Override public void saveChanged() { if(!closed) refreshGamePages(false); }
        });
        week=tracker.state().settings().weekOf(LocalDate.now());
        setPreferredSize(new Dimension(1280,900));
        // The window's minimum is derived from the bar rather than fixed here —
        // see windowMinimum(). A panel minimum as well would only disagree with
        // it, and the one this used to carry made the window impossible to size
        // below a desktop.
        var root=new JPanel(new BorderLayout());
        root.setBackground(BG);
        add(root,BorderLayout.CENTER);
        var bar = new JPanel(new BorderLayout());
        Color barGround=shade(BG,DARK?-8:-14);
        bar.setBackground(barGround);
        bar.setBorder(new EmptyBorder(SPACE_SM, SPACE_XL, SPACE_SM, SPACE_XL));
        var brand = Logo.lockup(TYPE_TITLE, CYAN);
        brand.setBorder(new EmptyBorder(0,0,0,SPACE_XL));
        bar.add(brand, BorderLayout.WEST);
        // Content-sized, not an even split: a GridLayout gave every tab the same
        // slice and the longest label, "collection", was the one that ellipsized
        // to "colle…" the moment the window opened at its minimum. The strip now
        // takes exactly the width its labels need, and never wraps — a wrapped
        // strip would be twice as tall.
        var tabs = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); tabs.setOpaque(false);
        for (var entry : PAGES) {
            var button = button(entry.nav(), () -> showPage(entry.nav()));
            button.setName(entry.nav());
            button.getAccessibleContext().setAccessibleName(entry.nav());
            button.setContentAreaFilled(false);
            // Give navigation labels room to read as separate destinations.
            // The measured window minimum includes this padding at every text size.
            button.setBorder(new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD));
            navigation.put(entry.nav(), button);
            tabs.add(button);
        }
        bar.add(tabs, BorderLayout.CENTER);
        var lock = button("Close vault", this::close);
        // The text, the tooltip and the accessible name all say the same thing;
        // the tooltip carries the one fact the label has no room for.
        lock.setToolTipText("Close vault (active timer keeps running)");
        // Named for the tests that drive it; the visible label is above.
        lock.setName("Lock & close");
        bar.add(lock, BorderLayout.EAST);
        // What the bar needs to show every tab whole: the brand, the strip at its
        // natural width and the close button, plus the bar's own margins. The
        // window's floor is this or the page size, whichever is larger.
        navMinimumWidth=brand.getPreferredSize().width+stripNaturalWidth()+lock.getPreferredSize().width
            +2*SPACE_XL;
        root.add(bar, BorderLayout.NORTH);
        content.setBackground(BG);
        content.setBorder(new EmptyBorder(SPACE_XL,SPACE_XL,SPACE_XL,SPACE_XL));
        root.add(content,BorderLayout.CENTER);
        showPage("Today");
        // 70ms: the Emerald walk cycle reads about right at this rate; 120 dragged.
        ticker=new javax.swing.Timer(70,e-> {
            if(!displayDate.equals(LocalDate.now())){displayDate=LocalDate.now();showPage(page);}
            var running=tracker.active();
            boolean animate=running!=null && !reducedMotion;
            todayPage.tick(animate,running,page.equals("Today"));
            updateRecordingStatus();
            // Synced here rather than from the clock-in and clock-out buttons, so
            // a session recovered when the vault opens — which passes through
            // neither — still starts the music.
            music.sync(running!=null&&!game.running());
        }
        );
        acceptDrops();
        ticker.start();
    }
    /**
     * Quits: the game first, so its last save is in the vault before the vault
     * is locked, then everything else.
     */
    private void close() {
        if(closed)return;
        game.close(()->{
            boolean stuck=game.phase()==GameController.Phase.STUCK;
            if(stuck&&!Dialogs.confirm(this,"The game has not finished closing. Its most recent save may not be in your vault.\n\nQuit anyway?","Quit Yoru","Quit"))return;
            try {
                vault.close();
                forgetSecret();
            }
            catch(Exception e) {
                error(e);
            }
            quit();
            // A core that would not stop still holds a thread nothing else will end.
            if(stuck)System.exit(0);
        });
    }

    /**
     * Quits to install an update: the game and the vault close exactly as they do
     * for the close button, then the installer's step runs and the process ends,
     * so nothing this copy started can keep it alive while an update waits for it.
     * A game that will not stop is not overridden here, unlike an ordinary quit.
     */
    private void closeForUpdate(Runnable afterVaultClosed) {
        if(closed)return;
        game.close(()->{
            if(game.phase()==GameController.Phase.STUCK) {
                Dialogs.info(this,"The game has not finished closing, so Yoru will not update now. Try again once it has closed.");
                return;
            }
            try {
                vault.close();
                forgetSecret();
            }
            catch(Exception e) {
                error(e);
                return;
            }
            quit();
            afterVaultClosed.run();
            System.exit(0);
        });
    }

    /**
     * Leaves the window, with the vault already closed — quitting, or deleting
     * the vault that was open (#41), after which there is nothing to show.
     */
    private void quit() {
        if(closed)return;
        closed=true;
        todayPage.close();
        gamePage.leave();
        music.close();
        // The session's encounter tables go with it (#12).
        game.encounters().forget();
        ticker.stop();
        var window=SwingUtilities.getWindowAncestor(this);
        if(window!=null)window.dispose();
    }

    /** Forgets the session's unlock secret, now that no vault needs reopening. */
    private void forgetSecret() {
        if(secret!=null)java.util.Arrays.fill(secret,'\0');
        secret=null;
    }
    @Override public void error(Exception e) {
        Dialogs.error(this,e.getMessage());
    }
    @Override public void perform(Work work) {
        try {
            work.run();
            showPage(page);
        }
        catch(Exception e) {
            error(e);
        }
    }
    @Override public Tracker tracker() { return tracker; }
    @Override public GameController game() { return game; }
    @Override public Component owner() { return this; }
    @Override public boolean reducedMotion() { return reducedMotion; }
    @Override public void show(String next) { showPage(next); }
    @Override public void refresh() { if(!closed) showPage(page); }
    @Override public ZoneId zone() { return zone; }
    @Override public String activityName(UUID id) { return name(id); }
    @Override public void reducedMotion(boolean on) { reducedMotion=on; }
    @Override public void quitForUpdate(Runnable afterVaultClosed) { closeForUpdate(afterVaultClosed); }
    private String name(UUID id) {
        return tracker.state().activities().stream().filter(a->a.id().equals(id)).map(Activity::name).findFirst().orElse("Activity");
    }
    /** The page on screen's scroll pane, so a rebuild of the same page can keep its place. */
    private JScrollPane pageScroll;

    private void showPage(String next) {
        // A rebuild of the page already on screen keeps its place. Saves, edits
        // and moves all rebuild the page, and each one used to throw the reader
        // back to the top (#8).
        Point keep=next.equals(page)&&pageScroll!=null?pageScroll.getViewport().getViewPosition():null;
        // The game keeps running on every tab; only its picture stops while
        // another page is on screen. It stops when it is closed, not before.
        gamePage.leave();
        page=next;
        todayPage.leave();
        markNavigation();
        content.removeAll();
        var heading=new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.setBorder(new EmptyBorder(0,0,SPACE_XL,0));
        // No breadcrumb: the pages are flat, and the title below already names the page (#9).
        var date=label(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d",Locale.ENGLISH)),TYPE_CAPTION,MUTED);
        date.setToolTipText("Times shown in "+zone);
        heading.add(date,BorderLayout.EAST);
        recordingStatus=button("",()->showPage("Today"));
        recordingStatus.setName("session.status");
        recordingStatus.setContentAreaFilled(false);
        recordingStatus.setForeground(ACCENT_TEXT);
        recordingStatus.setBorder(new EmptyBorder(0,0,0,SPACE_MD));
        recordingStatus.setToolTipText("Return to your timer to clock out or edit this session");
        heading.add(recordingStatus,BorderLayout.WEST);
        updateRecordingStatus();
        content.add(heading,BorderLayout.NORTH);
        JPanel view=switch(page) {
            case "Schedule"->schedule();
            case "Game"->gamePage.view();
            case "Data"->data();
            case "Collection"->collectionPage.view();
            case "Habits"->HabitsPanel.view(tracker, () -> showPage("Habits"));
            case "Tasks"->new TasksPanel(tracker, () -> showPage("Tasks"), () -> closed,taskViewState);
            case "Settings"->settingsPage.view();
            default->todayPage.view();
        }
        ;
        var scroll=new JScrollPane(view);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        // Always, so the gutter is reserved whether or not this page is long
        // enough to need it: as-needed inserted the 10 px bar the moment a page
        // crossed the viewport and reflowed every card on it, which read as the
        // content jumping sideways when you opened a different page. The track is
        // painted as the ground, so a reserved gutter is padding, not a bar.
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.getVerticalScrollBar().setUnitIncrement(SPACE_XL);
        content.add(scroll);
        pageScroll=scroll;
        content.revalidate();
        content.repaint();
        if(keep!=null)scrollTo(keep);
    }

    /** Keep a running session visible while the user works on another page. */
    void updateRecordingStatus() {
        if(recordingStatus==null)return;
        var active=tracker.active();
        recordingStatus.setVisible(active!=null);
        if(active==null)return;
        String text="● Recording · "+Analytics.duration(Math.max(0,
            Duration.between(active.start(),tracker.now()).getSeconds()));
        if(!text.equals(recordingStatus.getText()))recordingStatus.setText(text);
    }

    /** Puts the page on screen back where it was, or as far down as it still reaches. */
    void scrollTo(Point keep) {
        if(pageScroll==null)return;
        content.validate();
        var viewport=pageScroll.getViewport();
        var shown=viewport.getView();
        int furthest=Math.max(0,(shown==null?0:shown.getPreferredSize().height)-viewport.getHeight());
        viewport.setViewPosition(new Point(0,Math.max(0,Math.min(keep.y,furthest))));
    }
    /**
     * The tab strip at its natural width: every tab's longest label plus its two
     * {@link Theme#SPACE_MD} insets, measured at the tab font.
     *
     * The Game tab grows a dot while the game runs, so that label is measured at
     * its widest. Measuring rather than guessing is what makes the window's floor
     * move with the labels if a page is ever renamed.
     */
    private int stripNaturalWidth() {
        var metrics=getFontMetrics(labelFont());
        int strip=0;
        for(var entry:PAGES) {
            String label=entry.nav();
            strip+=Math.max(metrics.stringWidth(label),metrics.stringWidth(label+" ●"))+2*SPACE_MD;
        }
        return strip;
    }

    /**
     * The smallest window this page will open at.
     *
     * The bar is content-sized now, so its floor has to come from its content:
     * the brand, the strip and the close button, plus the bar's own margins.
     * Below that the strip has nowhere left to shrink and a tab starts to
     * ellipsize — which is how "Collection" became "colle…" at the old fixed
     * minimum, where the even split starved the longest label. The page floor
     * wins when it is the larger of the two, since the pages are reviewed there.
     */
    Dimension windowMinimum() {
        return new Dimension(Math.max(MIN_WINDOW_WIDTH,navMinimumWidth),MIN_WINDOW_HEIGHT);
    }

    /** Highlights the open tab, and marks the Game tab while the game runs. */
    private void markNavigation() {
        navigation.forEach((name, button) -> {
            boolean running=name.equals("Game")&&game.running();
            boolean current=name.equals(page);
            button.setText(running?"Game ●":name);
            button.setForeground(current ? ACCENT_TEXT : MUTED);
            // The underline is drawn inside the tab's own padding and that padding
            // keeps the resting height exactly: the strip no longer stretches its
            // tabs to a shared cell, so a tab that changed height when it was
            // opened would nudge every tab beside it.
            button.setBorder(new CompoundBorder(new MatteBorder(0,0,RING,0,current?ACCENT_TEXT:shade(BG,DARK?-8:-14)),
                new EmptyBorder(SPACE_SM,SPACE_MD,SPACE_SM-RING,SPACE_MD)));
            // Which page is open was said in colour alone, and the Game tab's dot
            // was painted but never spoken. Both ride on the accessible name, in
            // the one place the colours are applied, so nothing can drift.
            button.getAccessibleContext().setAccessibleName(name+(running?", running":"")+(current?", current":""));
        });
    }

    /**
     * The game moved on: rebuild the pages that show it, and only those, so a
     * half-typed task is never swept away because the game saved.
     */
    private void refreshGamePages(boolean phase) {
        markNavigation();
        if(phase&&page.equals("Game")||page.equals("Today")||page.equals("Collection")) showPage(page);
    }

    @Override public void paint(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        super.paint(g);
        g.dispose();
    }

    @Override public void addActivity() {
        var nameField=new JTextField(24);
        var target=new JTextField("0",8);
        var form=stack();
        // A bare text box beside a bare JLabel says nothing about itself: the
        // caption is tied to the field, and the field is named, because
        // setLabelFor alone leaves getAccessibleName() null.
        var nameCaption=new JLabel("What would you like to track?");
        nameCaption.setLabelFor(nameField);
        nameField.getAccessibleContext().setAccessibleName("What would you like to track?");
        form.add(nameCaption);
        form.add(nameField);
        gap(form,SPACE_MD);
        var targetCaption=new JLabel("Optional daily target in minutes (0 = none)");
        targetCaption.setLabelFor(target);
        target.getAccessibleContext().setAccessibleName("Optional daily target in minutes (0 = none)");
        form.add(targetCaption);
        form.add(target);
        gap(form,SPACE_MD);
        var templates=row();
        for(String n:List.of("Study","Coding","Japanese","Workout"))templates.add(button(n,()->nameField.setText(n)));
        form.add(templates);
        if(Dialogs.confirm(this,form,"New activity","Create"))perform(()->tracker.addActivity(nameField.getText(),Integer.parseInt(target.getText().strip())));
    }
    @Override public void timeDialog(boolean plan) { editTime(null,null,plan); }
    @Override public void editTime(Session session) { editTime(session,null); }
    private void editTime(Session session,ScheduleBlock block) { editTime(session,block,block!=null); }
    /** One editor for manual sessions, a live timer, and planned blocks. */
    private void editTime(Session session,ScheduleBlock block,boolean plan) {
        if(tracker.state().activities().isEmpty()){addActivity();return;}
        boolean existing=plan?block!=null:session!=null;
        var activity=plainCombo(new JComboBox<Activity>(tracker.state().activities().toArray(Activity[]::new)));
        UUID activityId=block!=null?block.activityId():session==null?null:session.activityId();
        for(int i=0;i<activity.getItemCount();i++)if(activity.getItemAt(i).id().equals(activityId))activity.setSelectedIndex(i);
        Instant startValue=block!=null?block.start():session==null?Instant.now().minusSeconds(plan?0:3600):session.start();
        Instant endValue=block!=null?block.end():session==null||session.end()==null?Instant.now().plusSeconds(plan?3600:0):session.end();
        var start=new DateTimeField(startValue,zone,"Start");var end=new DateTimeField(endValue,zone,"End");var form=stack();
        var activityCaption=bodyLabel("Activity");activityCaption.setLabelFor(activity);
        activity.getAccessibleContext().setAccessibleName("Activity");
        var startCaption=bodyLabel("Start · "+zone);startCaption.setLabelFor(start);
        var endCaption=bodyLabel("End");endCaption.setLabelFor(end);
        form.add(activityCaption);form.add(activity);gap(form,SPACE_MD);
        form.add(startCaption);form.add(start);gap(form,SPACE_MD);form.add(endCaption);form.add(end);
        var running=new JCheckBox("Keep timer running",session!=null&&session.end()==null);running.setOpaque(false);running.setForeground(TEXT);
        if(session!=null&&session.end()==null)form.add(running);
        if(!plan){gap(form,SPACE_MD);form.add(label("Correcting time also recalculates the encounters it earned.",TYPE_CAPTION,MUTED));}
        while(Dialogs.confirm(this,form,plan?"Schedule block":existing?"Edit tracked time":"Log time","Save")) {
            try {
                UUID id=((Activity)activity.getSelectedItem()).id();Instant from=start.value(),to=running.isSelected()?null:end.value();
                if(plan){if(existing)tracker.editBlock(block.id(),id,from,to);else tracker.plan(id,from,to);}
                else if(existing)tracker.editSession(session.id(),id,from,to);else tracker.log(id,from,to);
                showPage(page);return;
            }catch(Exception e){error(e);}
        }
    }
    /** Activity used for a block dragged straight onto the grid. */
    private UUID activityForBlock() {
        var activities=tracker.state().activities();
        if(activities.isEmpty()) throw new IllegalArgumentException("Create an activity before planning time.");
        var running=tracker.active();
        if(running!=null) return running.activityId();
        return activities.getFirst().id();
    }

    /**
     * The editor behind a double-click on the week grid (#32, #33).
     *
     * One dialog for both shapes, because from the calendar's point of view they
     * are the same object — a coloured span you want to correct or remove. What
     * differs is which tracker call it lands on, and that a recorded session is
     * described as time you spent rather than time you planned.
     */
    private void editOnGrid(UUID id,boolean recorded) {
        var state=tracker.state();
        UUID activityId; Instant from,to;
        if(recorded) {
            var session=state.sessions().stream().filter(s->s.id().equals(id)).findFirst().orElse(null);
            if(session==null||session.end()==null) return;
            activityId=session.activityId(); from=session.start(); to=session.end();
        } else {
            var block=state.blocks().stream().filter(b->b.id().equals(id)).findFirst().orElse(null);
            if(block==null) return;
            activityId=block.activityId(); from=block.start(); to=block.end();
        }
        var start=new DateTimeField(from,zone,"Start");
        var end=new DateTimeField(to,zone,"End");
        var form=stack();
        form.add(label(recorded?"Recorded session":"Planned block",TYPE_HEADING,TEXT));gap(form,SPACE_SM);
        form.add(label(name(activityId)+" · "+recorded("recorded","planned",recorded),TYPE_CAPTION,MUTED));gap(form,SPACE_MD);
        var startCaption=new JLabel("Start · "+zone);startCaption.setLabelFor(start);
        var endCaption=new JLabel("End · "+zone);endCaption.setLabelFor(end);
        form.add(startCaption);form.add(start);gap(form,SPACE_MD);
        form.add(endCaption);form.add(end);gap(form,SPACE_MD);
        form.add(label(recorded?"Correcting a session moves the time it contributed to your totals."
            :"A planned block is a plan; deleting it records nothing.",TYPE_CAPTION,MUTED));

        int choice=Dialogs.choose(this,form,recorded?"Edit recorded session":"Edit planned block",
            "Save","Delete","Cancel");
        if(choice==0) perform(()->{
            if(recorded) tracker.editSession(id,activityId,start.value(),end.value());
            else tracker.editBlock(id,activityId,start.value(),end.value());
            showPage("Schedule");
        });
        else if(choice==1) {
            var warning=stack();
            warning.add(label(recorded?"Delete this recorded session?":"Delete this planned block?",TYPE_HEADING,TEXT));
            gap(warning,SPACE_MD);
            warning.add(label(recorded?"The time it recorded is removed from your totals."
                :"Nothing recorded is affected.",TYPE_BODY,MUTED));
            if(!Dialogs.confirmDestructive(this,warning,recorded?"Delete session":"Delete block","Delete")) return;
            perform(()->{
                if(recorded) tracker.deleteSession(id); else tracker.deleteBlock(id);
                showPage("Schedule");
            });
        }
    }

    private static String recorded(String yes,String no,boolean recorded){ return recorded?yes:no; }

    private JPanel schedule() {
        var p=stack();
        // Wrapping: with larger text the five controls take two lines (#31).
        var nav=wrappingRow();
        // A bare arrow is not a name: both get a tooltip and an accessible one.
        var back=button("←",()->{week=week.minusWeeks(1);showPage("Schedule");});
        back.setToolTipText("Previous week");
        back.getAccessibleContext().setAccessibleName("Previous week");
        nav.add(back);
        nav.add(label(week+" — "+week.plusDays(6),TYPE_HEADING,TEXT));
        var forward=button("→",()->{week=week.plusWeeks(1);showPage("Schedule");});
        forward.setToolTipText("Next week");
        forward.getAccessibleContext().setAccessibleName("Next week");
        nav.add(forward);
        nav.add(button("This week",()->{
            week=tracker.state().settings().weekOf(LocalDate.now());showPage("Schedule");
        }));
        // What the page makes goes on its title's line; what moves the grid
        // stays with the grid it moves.
        p.add(pageHeaderFor("Schedule","RECORDED SESSIONS · PLANNED BLOCKS · WEEKLY TEMPLATE",
            button("+ Plan block",()->timeDialog(true)),
            ghost(button("Weekly template…",()->WeeklyTemplate.open(this,tracker,()->showPage("Schedule"))))));
        p.add(nav);
        gap(p,SPACE_MD);

        var grid=new ScheduleGrid(tracker.state(),week,zone,Instant.now(),new ScheduleGrid.Edits() {
            public void create(Instant start,Instant end) { perform(()->tracker.plan(activityForBlock(),start,end)); }
            public void update(UUID id,Instant start,Instant end) {
                var existing=tracker.state().blocks().stream().filter(b->b.id().equals(id)).findFirst().orElse(null);
                if(existing!=null) perform(()->tracker.editBlock(id,existing.activityId(),start,end));
            }
            public void updateSession(UUID id,Instant start,Instant end) {
                var existing=tracker.state().sessions().stream().filter(x->x.id().equals(id)).findFirst().orElse(null);
                if(existing!=null) perform(()->tracker.editSession(id,existing.activityId(),start,end));
            }
            public void open(UUID id,boolean recorded) { editOnGrid(id,recorded); }
        });
        var frame=new JPanel(new BorderLayout());
        frame.setOpaque(true);
        frame.setBackground(PANEL);
        frame.setBorder(new CompoundBorder(controlBorder(LINE),new EmptyBorder(SPACE_MD,SPACE_MD,SPACE_MD,SPACE_MD)));
        frame.setAlignmentX(0);
        frame.add(grid,BorderLayout.CENTER);
        p.add(frame);
        gap(p,SPACE_LG);

        // Four swatches instead of six colour words: the thing being described
        // is a shape, so showing it beats naming it.
        var legend=row();
        var sample=ScheduleGrid.colour(0);
        legend.add(legendItem(new Mark(sample,Mark.SOLID),"recorded"));
        legend.add(legendItem(new Mark(sample,Mark.OUTLINE),"planned"));
        legend.add(legendItem(new Mark(sample,Mark.DASHED),"every week"));
        legend.add(legendItem(new Mark(DANGER,Mark.NOW),"now"));
        p.add(legend);
        gap(p,SPACE_LG);

        // The blocks themselves still need a list to be edited or removed from,
        // until the grid supports direct manipulation.
        var planned=tracker.state().blocks().stream()
            .filter(b->b.start().isBefore(week.plusDays(7).atStartOfDay(zone).toInstant())
                    && b.end().isAfter(week.atStartOfDay(zone).toInstant()))
            .sorted(Comparator.comparing(ScheduleBlock::start)).toList();
        var list=card();
        list.add(sectionHeader("PLANNED THIS WEEK"));
        gap(list,SPACE_MD);
        if(planned.isEmpty())
            list.add(emptyState("No blocks planned this week.","Recorded time still appears above; plan a block to compare against it.",null));
        for(var b:planned) {
            // No fixed height: the row's own border sizes it, so it cannot clip
            // a longer activity name.
            var line=new JPanel(new BorderLayout(SPACE_LG,0));
            line.setOpaque(false);
            // Under the last block the rule divides it from the card's edge
            // rather than from another block, and reads as a row lost.
            line.setBorder(b==planned.getLast()?listEnd():listRow());
            var when=b.start().atZone(zone);
            // A locale-stable day and a 24-hour time: the grid, the list and the
            // editor all say the same thing about when a block starts.
            line.add(label(when.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.ENGLISH)+" "
                +when.format(DateTimeFormatter.ofPattern("HH:mm"))+" – "+b.end().atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),TYPE_CAPTION,GOLD_TEXT),BorderLayout.WEST);
            // The name gives way and the match figure stays whole: one label cut
            // "…" through both, and the figure was the part lost.
            var what=new JPanel();
            what.setLayout(new BoxLayout(what,BoxLayout.X_AXIS));
            what.setOpaque(false);
            what.add(shortenable(name(b.activityId()),TYPE_BODY,TEXT));
            what.add(label(" · "+String.format("%.0f%% matched",100*Analytics.adherence(tracker.state(),b,Instant.now())),TYPE_BODY,TEXT));
            what.add(Box.createHorizontalGlue());
            line.add(what,BorderLayout.CENTER);
            var actions=row();
            actions.add(ghost(button("Edit",()->editTime(null,b))));
            actions.add(ghost(button("Delete",()->{
                if(Dialogs.confirmDestructive(this,"Delete this planned block?","Delete block","Delete"))
                    perform(()->tracker.deleteBlock(b.id()));
            })));
            line.add(actions,BorderLayout.EAST);
            list.add(line);
        }
        p.add(list);
        gap(p,SPACE_LG);

        var note=card();
        note.add(sectionHeader("PLANNED ≠ REQUIRED",GOLD));
        gap(note,SPACE_MD);
        note.add(bodyLabel("Matched time = work on that activity inside its scheduled block."));
        gap(note,SPACE_SM);
        note.add(bodyLabel("An empty block is information, not a judgment. You can adjust tomorrow."));
        p.add(note);
        return p;
    }

    /**
     * One legend sample, drawn rather than described: a filled block, an
     * outline, a dashed outline, or the now rule.
     */
    private static final class Mark extends JPanel {
        static final int SOLID=0, OUTLINE=1, DASHED=2, NOW=3;
        private final Color colour;
        private final int kind;
        Mark(Color colour,int kind) {
            this.colour=colour; this.kind=kind;
            setOpaque(false);
            var size=new Dimension(SPACE_LG,SPACE_LG);
            setPreferredSize(size); setMaximumSize(size); setAlignmentX(0);
            getAccessibleContext().setAccessibleName(switch(kind) {
                case SOLID->"recorded time"; case OUTLINE->"planned block";
                case DASHED->"weekly repeat"; default->"the current time";
            });
        }
        @Override protected void paintComponent(Graphics graphics) {
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(),h=getHeight();
            if(kind==NOW) {
                g.setColor(colour);
                g.fillRect(0,h/2-RING/2,w,RING);
            } else if(kind==SOLID) {
                g.setColor(colour);
                g.fillRoundRect(0,2,w-1,h-5,RADIUS,RADIUS);
            } else {
                if(kind==DASHED) g.setStroke(new BasicStroke(HAIRLINE,BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,10,new float[]{4,4},0));
                g.setColor(colour);
                g.drawRoundRect(0,2,w-1,h-5,RADIUS,RADIUS);
            }
            g.dispose();
        }
    }

    /** A legend entry: the mark itself, then the word for it. */
    private static JPanel legendItem(JComponent mark,String text) {
        var item=tightRow();
        item.add(mark);
        item.add(label(text,TYPE_CAPTION,MUTED));
        return item;
    }
    private JTable table(String[] headers,Object[][] rows) {
        var t=new JTable(new DefaultTableModel(rows,headers) {
            public boolean isCellEditable(int r,int c) {
                return false;
            }
        }
        );
        // One table styling for the whole app: the shared row height, grid and
        // selection colours, rather than three near-copies that disagreed.
        plainTable(t);
        t.setBackground(PANEL);
        t.setForeground(TEXT);
        t.setSelectionForeground(CYAN);
        t.setFont(bodyFont());
        t.getTableHeader().setBackground(LINE);
        t.getTableHeader().setForeground(TEXT);
        t.getTableHeader().setFont(bodyFont());
        return t;
    }

    private JPanel data() {
        var p=stack();
        // An ellipsis on every action that opens a dialog, and none on the ones
        // that do not: the label is then the promise of what happens next.
        p.add(pageHeaderFor("Data","DURATION · HISTORY · EXPORT",
            button("+ Log time",()->timeDialog(false)),
            ghost(button("Export sessions CSV…",this::export)),
            ghost(button("Export vault JSON…",this::exportVault)),
            ghost(button("Import vault JSON…",this::importVault))));
        // The activity manager lives here as well as beside the Today picker: this
        // page is where the session counts and durations a removal would act on
        // are already on screen.
        p.add(ActivityManager.activities(tracker,this,()->showPage("Data")));
        gap(p,SPACE_LG);

        var days=Analytics.daily(tracker.state(),null,zone,Instant.now());
        var chart=card();
        chart.add(sectionHeader("LAST 14 DAYS · HOURS"));
        gap(chart,SPACE_LG);
        boolean anyTime=false;
        for(int i=0;i<14;i++)anyTime|=days.getOrDefault(LocalDate.now().minusDays(i),0L)>0;
        if(!anyTime) {
            // Fourteen empty bars is not a chart, it is a rendering fault.
            chart.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
        } else {
            int goal=tracker.state().settings().dailyGoalHours();
            long max=3600;
            for(int i=0;i<14;i++)max=Math.max(max,days.getOrDefault(LocalDate.now().minusDays(i),0L));
            // The goal is part of the scale, so the fortnight is drawn against
            // what the days were for and not only against their own best one: a
            // quiet fortnight used to stretch to fill the card and look like a
            // busy one. The dashes across the bars are where the goal falls, so
            // the chart answers "did I get there" without any arithmetic.
            long goalSeconds=Math.max(1,goal*3600L);
            max=Math.max(max,goalSeconds);
            int floor=grow(CHART_HEIGHT);
            int span=floor-SPACE_XL; // air over the goal line, so it reads as part of the chart
                                     // rather than as a rule under the durations above it
            int goalLine=(int)(span*goalSeconds/max);
            // The chart keeps its full height whatever the fortnight held, so
            // the goal line has somewhere to be drawn even when no day reached
            // it, and two visits to this page compare like with like.
            var bars=new JPanel(new GridLayout(1,14,SPACE_SM,0)) {
                @Override public Dimension getPreferredSize() { return tall(super.getPreferredSize()); }
                @Override public Dimension getMinimumSize() { return tall(super.getMinimumSize()); }
                @Override public Dimension getMaximumSize() { return tall(super.getMaximumSize()); }
                private Dimension tall(Dimension d) { return new Dimension(d.width,floor); }
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    var ink=(Graphics2D)g.create();
                    ink.setColor(LINE);
                    // A miter limit under 1 is rejected outright, and dashes are
                    // the only thing this stroke ever draws.
                    ink.setStroke(new BasicStroke(HAIRLINE,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,
                        HAIRLINE,new float[]{SPACE_XS,SPACE_XS},0));
                    int y=getHeight()-goalLine;
                    ink.drawLine(0,y,getWidth(),y);
                    ink.dispose();
                }
            };
            bars.setOpaque(false);
            bars.setToolTipText("The dashed line is your "+goal+"h daily goal");
            // The day numbers sit in their own row under the floor, and the
            // durations in their own row over it, so the bars share one
            // baseline and one ceiling instead of each starting and ending
            // wherever its own column's label left off.
            // Two lines of room whatever the text size: a column is narrow, and
            // at 200% "2h 45m" wraps. A grid asks its cells how tall they are
            // before it has told them how wide they will be, so a wrapping
            // caption reports one line and loses its second — the band is sized
            // for the wrap up front, and the captions sit on its floor.
            int caption=label("0m",TYPE_CAPTION,MUTED).getPreferredSize().height;
            var durations=new JPanel(new GridLayout(1,14,SPACE_SM,0)) {
                @Override public Dimension getPreferredSize() { return band(super.getPreferredSize()); }
                @Override public Dimension getMinimumSize() { return band(super.getMinimumSize()); }
                @Override public Dimension getMaximumSize() { return band(super.getMaximumSize()); }
                private Dimension band(Dimension d) { return new Dimension(d.width,2*caption); }
            };
            durations.setOpaque(false);
            durations.setAlignmentX(0);
            var dayNumbers=new JPanel(new GridLayout(1,14,SPACE_SM,0));
            dayNumbers.setOpaque(false);
            dayNumbers.setAlignmentX(0);
            for(int i=13;i>=0;i--) {
                LocalDate d=LocalDate.now().minusDays(i);
                long sec=days.getOrDefault(d,0L);
                boolean none=sec==0;
                var cell=stack();
                glue(cell);
                // A day with nothing on it is a hairline on the baseline rather
                // than the two-pixel stub of the lightest tier it used to be:
                // that stub read as a bar that had failed to draw, so a quiet
                // week looked like a broken chart instead of a quiet week. The
                // rest are coloured by the time recorded, not by the column's
                // index, so the chart agrees with the heat map above it.
                int height=none?HAIRLINE:Math.max(RING,(int)(span*sec/max));
                var bar=new Theme.Bar(none?LINE:Heatmap.colour(d,sec,goal));
                bar.setPreferredSize(new Dimension(25,height));
                bar.setMaximumSize(new Dimension(60,height));
                bar.setToolTipText(d+" · "+Analytics.report(sec));
                cell.add(bar);
                bars.add(cell);
                // A day with nothing on it says nothing: fourteen captions, half
                // of them "0m", were a row of noise over a row of hairlines.
                // Wraps onto two lines when a larger text size leaves the column too narrow (#31).
                var duration=wrapping(none?"":Analytics.report(sec),TYPE_CAPTION,MUTED);
                duration.setVerticalAlignment(SwingConstants.BOTTOM);
                durations.add(duration);
                // Today's number is the one in body ink: the chart then says
                // which end is now without counting the columns.
                dayNumbers.add(label(""+d.getDayOfMonth(),TYPE_CAPTION,d.equals(LocalDate.now())?TEXT:MUTED));
            }
            bars.setAlignmentX(0);
            bars.setBorder(new javax.swing.border.MatteBorder(0,0,HAIRLINE,0,LINE));
            chart.add(durations);
            gap(chart,SPACE_SM);
            chart.add(bars);
            gap(chart,SPACE_SM);
            chart.add(dayNumbers);
            gap(chart,SPACE_MD);
            chart.add(TodayPage.heatLegend(LocalDate.now(),tracker.state().settings().dailyGoalHours()));
        }
        p.add(chart);

        // Where the time went, not just how much (#6). The 14-day chart above
        // and the heat map both total every activity together, so a week spent
        // entirely on one subject and a week split four ways draw identically.
        gap(p,SPACE_LG);
        var asOf=Instant.now();
        var mixFrom=mixDays==0?Instant.EPOCH:asOf.minus(Duration.ofDays(mixDays));
        var mix=card();
        var mixHead=row();
        mixHead.add(sectionHeader("FOCUS DISTRIBUTION"));
        for(int span:new int[]{7,30,0}) {
            var pick=button(span==0?"All time":"Last "+span+" days",()->{mixDays=span;showPage("Data");});
            // The current range keeps its filled accent rather than being
            // disabled, so the active choice stays the most prominent.
            mixHead.add(selected(pick,mixDays==span));
        }
        mix.add(mixHead);
        gap(mix,SPACE_MD);
        long inWindow=Analytics.recorded(tracker.state(),mixFrom,asOf,asOf);
        if(mixDays>0) {
            var previousFrom=mixFrom.minus(Duration.ofDays(mixDays));
            var swing=Analytics.change(inWindow,Analytics.recorded(tracker.state(),previousFrom,mixFrom,asOf));
            mix.add(label(Analytics.duration(inWindow)+" · "+(swing.isPresent()
                ?String.format("%.0f%% %s than the previous %d days",Math.abs(swing.getAsDouble())*100,
                    swing.getAsDouble()<0?"less":"more",mixDays)
                :"nothing in the "+mixDays+" days before to compare against"),TYPE_CAPTION,MUTED));
            gap(mix,SPACE_MD);
        }
        var slices=Analytics.distribution(tracker.state(),mixFrom,asOf,asOf);
        if(slices.isEmpty()) mix.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
        else {
            var mixRows=new ArrayList<FocusBars.Row>();
            for(var slice:slices)mixRows.add(new FocusBars.Row(name(slice.activityId()),slice.seconds(),slice.share()));
            mix.add(new FocusBars(mixRows));
        }
        p.add(mix);
        gap(p,SPACE_LG);

        var now=Instant.now();
        var all=tracker.state().sessions();
        var sessions=all.stream().filter(x->Analytics.counts(x,tracker.state(),now))
            .sorted(Comparator.comparing(Session::start).reversed()).toList();
        int excluded=all.size()-sessions.size();
        Object[][] rows=new Object[sessions.size()][4];
        for(int i=0;i<sessions.size();i++) {
            var s=sessions.get(i);
            rows[i]=new Object[] {
                name(s.activityId()),s.start().atZone(zone).format(dateTime),s.end()==null?"Running":s.end().atZone(zone).format(dateTime),Analytics.duration(Duration.between(s.start(),s.end()==null?Instant.now():s.end()).getSeconds())
            }
            ;
        }
        // Header row with nothing under it reads as a bug; the empty state says
        // what fills it instead.
        var t=sessions.isEmpty()?null:table(new String[] {
            "Activity","Start (local)","End (local)","Duration"
        }
        ,rows);
        if(t==null) p.add(emptyState("No sessions recorded yet.","Clock in, or log time by hand.",null));
        else {
            var sc=new JScrollPane(t);
            sc.setBorder(Theme.controlBorder(LINE));
            p.add(sc);
        }
        if(excluded>0) {
            gap(p,SPACE_SM);
            // Said out loud rather than silently dropped: the sessions are still
            // in the vault and in the JSON export, they just do not count.
            p.add(bodyLabel(plural(excluded,"session")+" under "
                +tracker.state().settings().minSessionSeconds()/60+"m "
                +(excluded==1?"is":"are")+" not counted, and not listed above."));
            p.add(bodyLabel("They were recorded before the minimum applied at clock-out. "
                +"New short sessions are no longer stored at all."));
            gap(p,SPACE_SM);
            var clear=row();
            clear.add(button("Delete them",()->{
                var warning=stack();
                warning.add(label("Delete "+plural(excluded,"short session")+"?",TYPE_HEADING,TEXT));
                gap(warning,SPACE_MD);
                warning.add(label("They count toward nothing already. This deletes them for good.",TYPE_BODY,MUTED));
                if(!Dialogs.confirmDestructive(this,warning,"Delete short sessions","Delete"))return;
                perform(()->{int gone=tracker.purgeShortSessions();
                    Dialogs.info(this,plural(gone,"short session")+" deleted.");showPage("Data");});
            }));
            p.add(clear);
        }
        gap(p,SPACE_MD);
        // Both actions need a row, so both open disabled with the reason on
        // them rather than as a dialog that tells you to do something first.
        var editSession=button("Edit selected session",()-> {
            int index=t.getSelectedRow();
            if(index>=0)editTime(sessions.get(t.convertRowIndexToModel(index)),null);
        });
        var deleteSession=button("Delete selected session",()-> {
            int index=t.getSelectedRow();
            if(index>=0&&Dialogs.confirmDestructive(this,"Delete this recorded session.","Delete session","Delete"))
                perform(()->tracker.deleteSession(sessions.get(t.convertRowIndexToModel(index)).id()));
        });
        editSession.setEnabled(false); deleteSession.setEnabled(false);
        if(t==null) {
            String why="No recorded sessions yet";
            editSession.setToolTipText(why); deleteSession.setToolTipText(why);
        } else {
            editSession.setToolTipText("Select a session in the table first");
            deleteSession.setToolTipText("Select a session in the table first");
            t.getSelectionModel().addListSelectionListener(e->{
                boolean picked=t.getSelectedRow()>=0;
                editSession.setEnabled(picked);
                editSession.setToolTipText(picked?"Edit the selected session":"Select a session in the table first");
                deleteSession.setEnabled(picked);
                deleteSession.setToolTipText(picked?"Delete the selected session":"Select a session in the table first");
            });
            t.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&t.getSelectedRow()>=0)editTime(sessions.get(t.convertRowIndexToModel(t.getSelectedRow())),null);}});
        }
        var sessionActions=row();
        sessionActions.add(editSession);sessionActions.add(deleteSession);
        p.add(sessionActions);
        gap(p,SPACE_XL);
        Object[][] dailyRows=days.entrySet().stream().sorted(Map.Entry.<LocalDate,Long>comparingByKey().reversed()).map(e->new Object[] {
            e.getKey(),Analytics.duration(e.getValue())
        }
        ).toArray(Object[][]::new);
        p.add(sectionHeader("DAILY TOTALS · "+zone));
        gap(p,SPACE_MD);
        if(dailyRows.length==0) p.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
        else {
            var totals=new JScrollPane(table(new String[] {
                "Date","Time"
            }
            ,dailyRows));
            // Without this the pane paints the look-and-feel's own edge, which
            // is white on every dark theme; the sessions pane above takes the
            // shared one, and a table has nowhere to show it but here.
            totals.setBorder(Theme.controlBorder(LINE));
            p.add(totals);
        }
        return p;
    }
    /**
     * Vaults, managed here (#41): the one that is open, and what can be done to
     * it. A vault is a name — nothing on this page shows a path or asks for
     * one, because Yoru chose the folder and keeps everything in it.
     */
    @Override public JPanel vaultCard() {
        var c=card();
        c.add(sectionHeader("VAULT"));
        gap(c,SPACE_MD);
        String name=vaultName!=null?vaultName:vault==null?null:vault.name();
        if(store==null||name==null) {
            c.add(label("This workspace is not one of Yoru's vaults.",TYPE_LABEL,TEXT));
            gap(c,SPACE_SM);
            c.add(bodyLabel("Vaults Yoru manages are made from the welcome screen and kept in its own folder,"));
            c.add(bodyLabel("where they can be opened, renamed, switched and deleted without choosing a file."));
            return c;
        }
        c.add(label(name,TYPE_HEADING,TEXT));
        gap(c,SPACE_SM);
        c.add(bodyLabel("Yoru keeps this vault, and any others, in a folder of its own on this computer."));
        gap(c,SPACE_MD);
        var actions=row();
        actions.add(named(button("Switch vault…",this::switchVault),"vault.switch"));
        actions.add(named(button("New vault…",this::newVault),"vault.new"));
        actions.add(named(button("Rename vault…",this::renameVault),"vault.rename"));
        // Only for a vault that has a password: there is nothing to take off one
        // that opens without asking, and an action that cannot act is noise.
        if(!store.passwordless(name))
            actions.add(named(button("Remove password…",this::removeVaultPassword),"vault.removePassword"));
        actions.add(named(button("Delete vault…",this::deleteVault),"vault.delete"));
        c.add(actions);
        return c;
    }

    private JButton named(JButton button,String name) {
        button.setName(name);
        return button;
    }

    /**
     * Moves to another vault. The game is stopped first: it holds the save of
     * the vault it was playing, and would write it into whichever vault came
     * next. Nothing moves until the vault being moved to has opened in full.
     */
    private void switchVault() {
        VaultLauncher.whenGameStopped(game,()->{
            var next=VaultLauncher.choose(this,store,vaultName);
            if(next!=null)moveTo(next);
        });
    }

    /** Makes a new vault and moves into it, by the same rule. */
    private void newVault() {
        VaultLauncher.whenGameStopped(game,()->{
            var made=VaultLauncher.create(this,store);
            if(made!=null)moveTo(made);
        });
    }

    /** Moves the window onto an opened vault, tracking its name and its secret. */
    private void moveTo(VaultLauncher.Opened next) {
        try {
            tracker.switchTo(next.vault());
        }
        catch(Exception e) {
            // switchTo loaded nothing and closed the vault it could not open, so
            // the window is still on the vault it had.
            error(new Exception("Yoru is still in \""+vaultName+"\".\n\n"+e.getMessage(),e));
            return;
        }
        vault=next.vault();
        vaultName=next.name();
        forgetSecret();
        secret=next.secret();
        VaultLauncher.remember(vaultName);
        todayPage.vaultChanged();
        rebuildTo(page);
    }

    /**
     * Renames the open vault.
     *
     * The vault is closed across the move — the name is how its file is found —
     * and reopened under the new one. A rename that fails is walked back by the
     * store, so reopening under the old name is the whole recovery.
     */
    private void renameVault() {
        VaultLauncher.whenGameStopped(game,()->{
            String current=vaultName;
            String wanted=Dialogs.input(this,"Rename \""+current+"\".","Rename vault",current);
            if(wanted==null||wanted.strip().equals(current))return;
            String target;
            try { target=VaultStore.validate(wanted); }
            catch(IllegalArgumentException e) { error(e); return; }
            try {
                vault.close();
                store.rename(current,target);
            }
            catch(Exception e) {
                if(!reopen(current))return;
                Dialogs.error(this,"The vault was not renamed",e.getMessage());
                rebuildTo(page);
                return;
            }
            if(!reopen(target))return;
            vaultName=target;
            VaultLauncher.remember(target);
            rebuildTo(page);
        });
    }

    /**
     * Takes the password off the open vault, so it opens without asking (#41).
     *
     * Done to the vault that is open rather than to a copy opened for the
     * purpose: this window holds the file's lock, so nothing else may open it,
     * and the state written is the one on screen rather than the one last read
     * off the disk. The session keeps the new unlock secret, because a rename
     * or a failed delete still has to reopen the vault afterwards.
     */
    private void removeVaultPassword() {
        VaultLauncher.whenGameStopped(game,()->{
            String name=vaultName;
            if(store==null||name==null||!(vault instanceof EncryptedVault open))return;
            if(store.passwordless(name)) {
                Dialogs.info(this,"\""+name+"\" already opens without a password.");
                return;
            }
            if(!VaultLauncher.confirmRemovePassword(this,name))return;
            char[] fresh;
            try { fresh=store.removePassword(name,open,tracker.state()); }
            catch(Exception e) {
                // The store put its own key back and never wrote the vault, so
                // the password that was there is still the password.
                Dialogs.error(this,"The password was not removed",
                    e.getMessage()+"\n\n\""+name+"\" still opens with the password it had.");
                return;
            }
            forgetSecret();
            secret=fresh;
            Dialogs.info(this,"\""+name+"\" now opens without a password.\n\n"
                +"Its unlock key is kept beside it, so anyone who can read your files can open it.");
            rebuildTo(page);
        });
    }

    /**
     * Deletes the open vault, after saying exactly what goes.
     *
     * The store takes a verified copy before it removes anything and puts the
     * vault back if a removal fails, so a deletion that fails is reported and
     * the vault is reopened exactly where it was. A deletion that works leaves
     * nothing open, so Yoru closes.
     */
    private void deleteVault() {
        VaultLauncher.whenGameStopped(game,()->{
            String name=vaultName;
            if(!VaultLauncher.confirmDelete(this,name,store.contents(name,tracker.state())))return;
            try { vault.close(); }
            catch(Exception e) { error(e); return; }
            try {
                store.delete(name);
            }
            catch(Exception e) {
                if(!reopen(name))return;
                Dialogs.error(this,"Nothing was lost",e.getMessage());
                rebuildTo(page);
                return;
            }
            forgetSecret();
            Dialogs.info(this,"\""+name+"\" is deleted, with everything that was in it.\n\n"
                +"Yoru will close now; any other vaults are on the welcome screen next time.");
            quit();
        });
    }

    /**
     * Reopens the named vault with this session's secret, so undoing a vault
     * change never means typing the password again.
     *
     * The tracker is moved onto it rather than the field alone being replaced:
     * every write goes through the tracker, and a tracker still pointed at the
     * vault that was closed would write nowhere.
     */
    private boolean reopen(String name) {
        try {
            var reopened=store.open(name,secret.clone());
            tracker.switchTo(reopened);
            vault=reopened;
            return true;
        }
        catch(Exception e) {
            Dialogs.error(this,"The vault could not be reopened",
                "\""+name+"\" is still on disk and will open again from the welcome screen.\n\n"
                    +"Yoru will close now, rather than keep working without a vault.\n\n"+e.getMessage());
            quit();
            return false;
        }
    }

    public static String csv(String value) {
        if(!value.isEmpty()&&(!value.stripLeading().isEmpty()&&"=+-@".indexOf(value.stripLeading().charAt(0))>=0||"\t\r\n".indexOf(value.charAt(0))>=0))value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private void export() {
        Path file=Dialogs.saveFile(this,"Export sessions","yoru-sessions.csv");
        if(file==null)return;
        perform(()-> {
            var out=new StringBuilder("activity,start_utc,end_utc,duration_seconds\n");for(var s:tracker.state().sessions())out.append(csv(name(s.activityId()))).append(',').append(csv(s.start().toString())).append(',').append(csv(s.end()==null?"":s.end().toString())).append(',').append(s.end()==null?"":Duration.between(s.start(),s.end()).getSeconds()).append('\n');Files.writeString(file,out);Dialogs.info(this,"Export saved. This CSV is unencrypted; share it only where you intend.");
        }
        );
    }
    /** The whole vault as readable JSON: the debugging and acceptance-check tool. */
    private void exportVault() {
        Path file=Dialogs.saveFile(this,"Export vault","yoru-vault-"+LocalDate.now()+".json");
        if(file==null)return;
        perform(()->{
            Files.writeString(file,dev.yoru.persistence.PortableVault.export(tracker.state(),Instant.now()));
            Dialogs.info(this,"Vault exported to "+file.getFileName()+".\n\n"
                +"This file is NOT encrypted. It holds everything in your vault, your game save\n"
                +"included, as plain readable text. Keep it where you keep the vault.");
        });
    }

    /**
     * Replaces the whole vault from an export. Parsed and validated in full
     * before anything is written, so a file that is wrong anywhere leaves the
     * open vault untouched.
     */
    private void importVault() {
        if(game.running()){Dialogs.info(this,"Close the game first. It holds your game save while it runs.");return;}
        Path file=Dialogs.chooseFile(this,"Choose a Yoru vault export","Yoru export (JSON)","json");
        if(file==null)return;
        final State incoming;
        try { incoming=dev.yoru.persistence.PortableVault.parse(Files.readString(file)); }
        catch(Exception e) { error(new Exception("That file could not be read as a Yoru export.\n\n"+e.getMessage())); return; }

        var current=tracker.state();
        var warning=stack();
        warning.add(label("Replace everything in this workspace?",TYPE_HEADING,TEXT));gap(warning,SPACE_MD);
        warning.add(label(file.getFileName().toString(),TYPE_BODY,CYAN));gap(warning,SPACE_MD);
        warning.add(label("Incoming: "+summary(incoming),TYPE_BODY,TEXT));
        warning.add(label("Replacing: "+summary(current),TYPE_BODY,MUTED));gap(warning,SPACE_MD);
        warning.add(bodyLabel("Everything currently in this workspace is discarded. Export it first"));
        warning.add(bodyLabel("if you might want it back."));
        if(!Dialogs.confirmDestructive(this,warning,"Import vault","Replace everything"))return;
        perform(()->{
            tracker.restore(incoming);
            Dialogs.info(this,"Vault replaced from "+file.getFileName()+".");
            rebuild();
        });
    }

    private static String summary(State state) {
        return state.activities().size()+" activities, "+state.sessions().size()+" sessions, "
            +state.tasks().size()+" tasks, "+state.habits().size()+" habits, "
            +state.rewards().size()+" Pokémon earned"+(state.game()==null?"":", a game save");
    }

    /** Applies a settings change, rebuilding the window when the palette moved. */
    @Override public void applySettings(java.util.function.UnaryOperator<Settings> change) {
        try {
            var previous=tracker.state().settings();
            var next=change.apply(previous);
            tracker.settings(next);
            // The visible week was computed under the old preference; moving the
            // week start without realigning it leaves the grid straddling two.
            if(next.weekStartsOn()!=previous.weekStartsOn()) week=next.weekOf(LocalDate.now());
            if(next.theme()!=Theme.current()) { Theme.apply(next.theme()); rebuild(); }
            else showPage(page);
        } catch(Exception e) { error(e); }
    }

    /**
     * A new text size, kept for this computer (#31). Like a palette, it only
     * reaches components built after it, so the look-and-feel's faces are
     * installed again and the window is rebuilt, on Settings where it was chosen.
     */
    @Override public void textSize(int percent) {
        if(percent==TextSize.current()) return;
        try {
            TextSize.choose(percent);
            Theme.apply(Theme.current());
            rebuild();
        } catch(RuntimeException e) { error(e); }
    }

    /**
     * A palette swap only reaches components built after it, so the window is
     * rebuilt rather than repainted. The tracker and vault carry over untouched.
     */
    private void rebuild() { rebuildTo("Settings"); }

    /**
     * Rebuilds the window, staying on a page.
     *
     * A vault switch lands on the page it was asked from, which is where the
     * vault controls live; a theme change lands on Settings, where the palette
     * was chosen. The tracker, the open vault and the session's secret carry
     * over untouched.
     */
    private void rebuildTo(String next) {
        var window=SwingUtilities.getWindowAncestor(this);
        // A theme change rebuilds the whole window and lands back on Settings,
        // where the palette was picked partway down; the new window keeps that place (#9).
        Point keep=next.equals(page)&&pageScroll!=null?pageScroll.getViewport().getViewPosition():null;
        ticker.stop();
        gamePage.leave();
        music.close();
        if(window instanceof JFrame frame) {
            var fresh=new YoruApp(tracker,vault,store,vaultName,secret,game);
            frame.setContentPane(fresh);
            // Rebuilt from the new bar: a palette swap can change the brand's
            // width, and the floor has to follow it.
            frame.setMinimumSize(fresh.windowMinimum());
            frame.setIconImages(Theme.appIcons());
            SwingUtilities.updateComponentTreeUI(frame);
            frame.revalidate();
            frame.repaint();
            fresh.showPage(next);
            if(keep!=null)fresh.scrollTo(keep);
        }
    }

    /** Accepts a folder, a .zip or a single PNG, from the picker or a drop. */
    @Override public void installArtwork(java.io.File chosen) {
        ArtworkImport.start(this,chosen.toPath(),()->showPage(page),this::error);
    }

    @Override public void importArtwork() {
        var chooser=new JFileChooser();
        chooser.setDialogTitle("Choose your Emerald game, artwork folder or .zip");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if(chooser.showDialog(this,"Add artwork")==JFileChooser.APPROVE_OPTION)
            installArtwork(chooser.getSelectedFile());
    }

    /** Dropping onto any part of the window imports, so the path is hard to miss. */
    private void acceptDrops() {
        setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if(!canImport(support)) return false;
                try {
                    var files=(List<?>)support.getTransferable()
                        .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if(files.isEmpty()) return false;
                    installArtwork((java.io.File)files.getFirst());
                    return true;
                } catch(Exception e) { error(e); return false; }
            }
        });
    }

    @Override public void chooseReset() {
        var form=stack();var all=new JCheckBox("All data");all.setOpaque(false);all.setForeground(GOLD_TEXT);form.add(all);
        var choices=new LinkedHashMap<Tracker.ResetPart,JCheckBox>();
        for(var part:Tracker.ResetPart.values()) {var check=new JCheckBox(part.label);check.setOpaque(false);check.setForeground(TEXT);choices.put(part,check);form.add(check);}
        all.addActionListener(e->choices.values().forEach(c->c.setSelected(all.isSelected())));
        if(!Dialogs.confirm(this,form,"Choose reset scope","Continue"))return;
        var parts=EnumSet.noneOf(Tracker.ResetPart.class);choices.forEach((part,check)->{if(check.isSelected())parts.add(part);});
        if(parts.isEmpty()){Dialogs.info(this,"Nothing selected. No data was changed.");return;}
        if(parts.contains(Tracker.ResetPart.GAME)&&game.running()){Dialogs.info(this,"Close the game before resetting its save.");return;}
        String selected=choices.entrySet().stream().filter(e->parts.contains(e.getKey())).map(e->"• "+e.getValue().getText()).collect(java.util.stream.Collectors.joining("\n"));
        if(!Dialogs.confirmDestructive(this,"Reset these sections?\n\n"+selected+"\n\nA backup will be saved first. This also removes an active timer if sessions are selected.","Confirm reset","Reset"))return;
        perform(()->{tracker.reset(parts);todayPage.forgetActivityFilter();});
    }
    /**
     * First run with no artwork: say so once, and offer to fix it (#16).
     *
     * The distributed build ships no sprites — it cannot, they are not ours — so
     * a new copy opens to a collection of dex numbers with no explanation. This
     * is that explanation, shown once.
     *
     * The "don't ask again" flag lives in Preferences rather than the vault,
     * for the same reason the recent-vault path does: it is about this machine,
     * not about this workspace, and it should not travel when the vault moves.
     */
    static void offerArtwork(YoruApp app) {
        var preferences=java.util.prefs.Preferences.userRoot().node("dev/yoru/desktop");
        if(preferences.getBoolean("artwork.prompt.dismissed",false)) return;
        if(!SpriteAssets.survey().empty()) return;
        var message=stack();
        message.add(label("Yoru ships without game artwork.",TYPE_HEADING,TEXT));gap(message,SPACE_MD);
        message.add(bodyLabel("The collection works either way — it shows National Dex numbers"));
        message.add(bodyLabel("until you add sprites of your own. Nothing else is affected."));gap(message,SPACE_MD);
        message.add(bodyLabel("Choose your Emerald game to extract sprites and box wallpapers,"));
        message.add(bodyLabel("or add a folder or .zip of your own PNG artwork."));
        int choice=Dialogs.choose(app,message,"Add your own artwork",
            "Choose game or artwork…","Not now","Don't ask again");
        if(choice==0) app.importArtwork();
        else if(choice==2) preferences.putBoolean("artwork.prompt.dismissed",true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(()-> {
            // Before anything is built, the vault launcher included: this
            // computer's text size reaches every window (#31).
            TextSize.use(TextSize.saved());
            Theme.install();try {
                var opened=VaultLauncher.open();if(opened==null)return;
                var tracker=new Tracker(opened.vault(),Clock.systemUTC());
                // The launcher runs on the default palette; switch before building the app.
                Theme.apply(tracker.state().settings().theme());
                // The store, the name and the session's secret travel with the
                // window: rename, switch and delete all happen from the Data page.
                var app=new YoruApp(tracker,opened.vault(),opened.store(),opened.name(),opened.secret());
                var frame=new JFrame("Yoru / 夜 — local study workspace");frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);frame.setContentPane(app);frame.pack();
                // Without an explicit icon the platform substitutes stock Java artwork.
                frame.setIconImages(Theme.appIcons());
                if(Taskbar.isTaskbarSupported()) {
                    try { Taskbar.getTaskbar().setIconImage(Theme.appIcons().getLast()); }
                    catch(UnsupportedOperationException|SecurityException ignored) { }
                }
                // Small enough to sit beside other windows, and never narrower
                // than the bar: the minimum comes from the tabs themselves, so no
                // label can be cut down to an ellipsis. Layout reflows below this.
                frame.setMinimumSize(app.windowMinimum());frame.setLocationRelativeTo(null);frame.addWindowListener(new WindowAdapter() {
                    public void windowClosing(WindowEvent e) {
                        // Whichever window is showing now: a theme change rebuilds it.
                        if(frame.getContentPane() instanceof YoruApp current)current.close();
                    }
                }
                );frame.setVisible(true);
                // Asked once the window is up, not from the constructor: a modal
                // dialog raised mid-construction appears behind its own parent.
                offerArtwork(app);
            }
            catch(Exception e) {
                Dialogs.error(null,e.getMessage());
            }
        }
        );
    }
}
