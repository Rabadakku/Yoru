package dev.yoru.ui;
import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import dev.yoru.persistence.VaultStore;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import static dev.yoru.ui.Theme.*;
// dev.yoru.ui is exported only so java.desktop can build Theme's UI delegates
// (see module-info.java); no other module uses this class, so the package-private
// types its Shell methods return are not an API anybody could miss.
@SuppressWarnings("exports")
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
    private LocalDate displayDate;
    private final MusicPlayer music=new MusicPlayer();
    /** The pomodoro keeps its time whichever page is open (#61). */
    private PomodoroClock pomodoro;
    private final TodayPage todayPage=new TodayPage(this);
    private final PagesPage pagesPage=new PagesPage(this);
    private final SettingsPage settingsPage=new SettingsPage(this);
    private final SchedulePage schedulePage=new SchedulePage(this);
    private final DataPage dataPage=new DataPage(this,this::rebuild,()->this.closed);
    private javax.swing.Timer ticker;
    private JButton recordingStatus;
    private final TasksPanel.ViewState taskViewState=new TasksPanel.ViewState();
    private final Map<String,JButton> navigation = new LinkedHashMap<>();
    private boolean closed;
    /** AI assistants' way into the open vault (#47); kept when a new palette rebuilds the window. */
    private final Assistants assistants;
    /** What the bar needs to show every tab whole; measured when the bar is built. */
    private final int navMinimumWidth;
    private JPanel sidebar;
    private long appearanceCheck;
    private boolean appearanceBusy;

    /** The size the pages are reviewed at: the frame never opens smaller than this. */
    private static final int MIN_WINDOW_WIDTH=900, MIN_WINDOW_HEIGHT=640;

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
        new Page("Pages","pages","pages.workspace"),
        new Page("Habits","habits","habits.log"),
        new Page("Schedule","schedule","schedule.week"),
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

    public YoruApp(Tracker tracker,Repository vault) { this(tracker,vault,null,null,null,null); }
    /** A window on a vault the store manages, as the launcher opens one, for tests of the vault's own controls. */
    static YoruApp managed(Tracker tracker,Repository vault,VaultStore store,String name,char[] secret) {
        return new YoruApp(tracker,vault,store,name,secret,null);
    }
    private YoruApp(Tracker tracker,Repository vault,VaultStore store,String openName,char[] secret,Assistants assistants) {
        super(new BorderLayout());
        this.tracker=tracker;
        this.assistants=assistants!=null?assistants:new Assistants(tracker,zone,dev.yoru.ai.Bridge.folder());
        this.assistants.attach(new Assistants.Window() {
            public boolean flush() { return !closed && pagesPage.flush(); }
            public void refresh() { YoruApp.this.refresh(); }
        });
        this.vault=vault;
        this.store=store;
        this.vaultName=openName;
        this.secret=secret;
        displayDate=today();
        setPreferredSize(new Dimension(1280,900));
        // The window's minimum is derived from the bar rather than fixed here —
        // see windowMinimum(). A panel minimum as well would only disagree with
        // it, and the one this used to carry made the window impossible to size
        // below a desktop.
        var root=new JPanel(new BorderLayout());
        root.setBackground(BG);
        add(root,BorderLayout.CENTER);
        Color sidebarGround = shade(BG, DARK ? -6 : -7);
        sidebar = new JPanel(new BorderLayout());
        sidebar.setVisible(java.util.prefs.Preferences.userRoot().node("dev/yoru/desktop").getBoolean("sidebar.visible",true));
        sidebar.setName("workspace.sidebar"); sidebar.setBackground(sidebarGround);
        sidebar.setPreferredSize(new Dimension(grow(204), 1));
        sidebar.setBorder(new CompoundBorder(new MatteBorder(0,0,0,1,LINE),
            new EmptyBorder(SPACE_XL, SPACE_MD, SPACE_MD, SPACE_MD)));
        var brand = new JPanel(new BorderLayout()); brand.setOpaque(false);
        brand.setBorder(new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_XXL, SPACE_MD));
        var wordmark = label("Yoru", TYPE_HEADING, TEXT);
        wordmark.setFont(headingFont().deriveFont(Font.BOLD));
        brand.add(wordmark, BorderLayout.NORTH);
        var workspace = label("Personal workspace", TYPE_CAPTION, MUTED);
        brand.add(workspace, BorderLayout.SOUTH);
        sidebar.add(brand, BorderLayout.NORTH);
        var destinations = new JPanel(); destinations.setOpaque(false);
        destinations.setLayout(new BoxLayout(destinations, BoxLayout.Y_AXIS));
        for (var entry : PAGES) {
            if (entry.nav().equals("Settings")) destinations.add(Box.createVerticalStrut(SPACE_XL));
            var button = new NavTab(entry.nav(), sidebarGround);
            button.addActionListener(e -> showPage(entry.nav())); button.setName(entry.nav());
            button.getAccessibleContext().setAccessibleName(entry.nav());
            navigation.put(entry.nav(), button); destinations.add(button);
            destinations.add(Box.createVerticalStrut(SPACE_XS));
        }
        destinations.add(Box.createVerticalGlue());
        var navigationScroll = new JScrollPane(destinations);
        navigationScroll.setBorder(null); navigationScroll.getViewport().setBackground(sidebarGround);
        navigationScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sidebar.add(navigationScroll, BorderLayout.CENTER);
        var lock = ghost(button("Close vault", this::close)); lock.setName("Lock & close");
        lock.setToolTipText("Close vault (active timer keeps running)");
        lock.setBackground(sidebarGround); sidebar.add(lock, BorderLayout.SOUTH);
        root.add(sidebar, BorderLayout.WEST);
        navMinimumWidth = grow(800);
        content.setBackground(BG);
        content.setBorder(new EmptyBorder(SPACE_XL,SPACE_XL,SPACE_XL,SPACE_XL));
        root.add(content,BorderLayout.CENTER);
        pomodoro=new PomodoroClock(()->this.tracker,java.time.Clock.systemUTC(),()->{ if(page.equals("Today")) showPage("Today"); });
        showPage("Today");
        // Four times a second: often enough that a clock showing seconds never
        // visibly skips one, and a quarter of the wakeups the 70 ms the removed
        // game's walk cycle needed, which a laptop's battery paid for all day.
        ticker=new javax.swing.Timer(250,e-> {
            if(!displayDate.equals(today())){displayDate=today();showPage(page);}
            var running=tracker.active();
            try { pomodoro.tick(); }
            catch(Exception failure) { pomodoroFailed(failure); }
            todayPage.tick(page.equals("Today"));
            if (SystemAppearance.enabled() && System.currentTimeMillis()-appearanceCheck > 30_000) refreshAppearance();
            updateRecordingStatus();
            // Synced here rather than from the clock-in and clock-out buttons, so
            // a session recovered when the vault opens — which passes through
            // neither — still starts the music.
            music.sync(running!=null);
        }
        );
        ticker.start();
        this.assistants.sync();
    }
    @Override public PomodoroClock pomodoro() { return pomodoro; }
    @Override public Assistants assistants() { return assistants; }

    /** A pomodoro that could not record its work says so once, rather than every tick. */
    private String pomodoroFailure;
    private void pomodoroFailed(Exception failure) {
        if(java.util.Objects.equals(failure.getMessage(),pomodoroFailure)) return;
        pomodoroFailure=failure.getMessage();
        error(failure);
    }

    /** Quits: everything unsaved is written, then the vault is locked. */
    private void close() {
        if(closed || !pagesPage.flush())return;
        try {
            vault.close();
            forgetSecret();
        }
        catch(Exception e) {
            error(e);
        }
        quit();
    }

    /**
     * Quits to install an update: the vault closes exactly as it does for the
     * close button, then the installer's step runs and the process ends, so
     * nothing this copy started can keep it alive while an update waits for it.
     */
    private void closeForUpdate(Runnable afterVaultClosed) {
        if(closed || !pagesPage.flush())return;
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
    }

    /**
     * Leaves the window, with the vault already closed — quitting, or deleting
     * the vault that was open (#41), after which there is nothing to show.
     */
    private void quit() {
        if(closed)return;
        closed=true;
        assistants.close();
        pagesPage.stop();
        todayPage.close();
        music.close();
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
        if (!pagesPage.flush()) return;
        try {
            work.run();
            showPage(page);
        }
        catch(Exception e) {
            error(e);
        }
    }
    @Override public Tracker tracker() { return tracker; }
    @Override public Component owner() { return this; }
    @Override public boolean reducedMotion() { return reducedMotion; }
    @Override public void show(String next) { showPage(next); }
    /** The page on screen, for the tests. */
    String page() { return page; }
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

    void openNote(UUID id) { showPage("Pages"); pagesPage.open(id); }

    private void showPage(String next) {
        if (!pagesPage.flush()) return;
        // A rebuild of the page already on screen keeps its place. Saves, edits
        // and moves all rebuild the page, and each one used to throw the reader
        // back to the top (#8).
        Point keep=next.equals(page)&&pageScroll!=null?pageScroll.getViewport().getViewPosition():null;
        page=next;
        todayPage.leave();
        markNavigation();
        content.removeAll();
        var heading=new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.setBorder(new EmptyBorder(0,0,SPACE_XL,0));
        // No breadcrumb: the pages are flat, and the title below already names the page (#9).
        var date=label(today().format(DateTimeFormatter.ofPattern("EEEE, MMMM d",Locale.ENGLISH)),TYPE_CAPTION,MUTED);
        date.setToolTipText("Times shown in "+zone);
        heading.add(date,BorderLayout.EAST);
        var leading = new JPanel(new FlowLayout(FlowLayout.LEFT,SPACE_MD,0)); leading.setOpaque(false);
        var toggle = ghost(button("", this::toggleSidebar));
        toggle.setIcon(Glyphs.of(Glyphs.Kind.SIDEBAR,MUTED)); toggle.setName("sidebar.toggle");
        toggle.setToolTipText("Show or hide sidebar"); toggle.getAccessibleContext().setAccessibleName("Toggle sidebar");
        leading.add(toggle);
        recordingStatus=button("",()->showPage("Today"));
        recordingStatus.setName("session.status");
        recordingStatus.setContentAreaFilled(false);
        recordingStatus.setForeground(ACCENT_TEXT);
        recordingStatus.setBorder(new EmptyBorder(0,0,0,SPACE_MD));
        recordingStatus.setToolTipText("Return to your timer to clock out or edit this session");
        leading.add(recordingStatus);
        heading.add(leading,BorderLayout.WEST);
        updateRecordingStatus();
        content.add(heading,BorderLayout.NORTH);
        JPanel view=switch(page) {
            case "Pages"->pagesPage.view();
            case "Schedule"->schedulePage.view();
            case "Data"->dataPage.view();
            case "Habits"->HabitsPanel.view(tracker, () -> showPage("Habits"));
            case "Tasks" -> {
                var tasks = new TasksPanel(tracker, () -> showPage("Tasks"), () -> closed, taskViewState);
                tasks.pageOpener(this::openNote); yield tasks;
            }
            case "Settings"->settingsPage.view();
            default->todayPage.view();
        }
        ;
        if (page.equals("Pages")) {
            content.add(view, BorderLayout.CENTER); pageScroll = null;
            content.revalidate(); content.repaint(); return;
        }
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
     * The smallest window this page will open at.
     *
     * The bar is content-sized now, so its floor has to come from its content:
     * the brand, the strip and the close button, plus the bar's own margins.
     * Below that the strip has nowhere left to shrink and a tab starts to
     * ellipsize — which is how "Collection" became "colle…" at the old fixed
     * minimum, where the even split starved the longest label. The page floor
     * wins when it is the larger of the two, since the pages are reviewed there.
     */
    void newNote() { if (!pagesPage.flush()) return; showPage("Pages"); pagesPage.newPage(null); }
    void closeVault() { close(); }
    boolean closeRequested() { close(); return closed; }

    void toggleSidebar() {
        sidebar.setVisible(!sidebar.isVisible());
        java.util.prefs.Preferences.userRoot().node("dev/yoru/desktop").putBoolean("sidebar.visible",sidebar.isVisible());
        revalidate(); repaint();
    }

    Dimension windowMinimum() {
        return new Dimension(Math.max(MIN_WINDOW_WIDTH,navMinimumWidth),MIN_WINDOW_HEIGHT);
    }

    /** Highlights the open tab. */
    private void markNavigation() {
        navigation.forEach((name, button) -> {
            boolean current=name.equals(page);
            // The pill is painted by the tab, so being current changes no size:
            // opening a page never nudges the tabs beside it.
            if(button instanceof NavTab tab) tab.setCurrent(current);
            // Which page is open was said in colour alone; it rides on the
            // accessible name too, in the one place the colours are applied.
            button.getAccessibleContext().setAccessibleName(name+(current?", current":""));
        });
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
    @Override public void editBlock(ScheduleBlock block) { editTime(null,block); }
    private void editTime(Session session,ScheduleBlock block) { editTime(session,block,block!=null); }
    /** One editor for manual sessions, a live timer, and planned blocks. */
    private void editTime(Session session,ScheduleBlock block,boolean plan) {
        if(tracker.state().activities().isEmpty()){addActivity();return;}
        boolean existing=plan?block!=null:session!=null;
        var activity=plainCombo(new JComboBox<Activity>(tracker.state().activities().toArray(Activity[]::new)));
        UUID activityId=block!=null?block.activityId():session==null?null:session.activityId();
        for(int i=0;i<activity.getItemCount();i++)if(activity.getItemAt(i).id().equals(activityId))activity.setSelectedIndex(i);
        Instant startValue=block!=null?block.start():session==null?now().minusSeconds(plan?0:3600):session.start();
        Instant endValue=block!=null?block.end():session==null||session.end()==null?now().plusSeconds(plan?3600:0):session.end();
        var start=new DateTimeField(startValue,zone,"Start");var end=new DateTimeField(endValue,zone,"End");var form=stack();
        var activityCaption=bodyLabel("Activity");activityCaption.setLabelFor(activity);
        activity.getAccessibleContext().setAccessibleName("Activity");
        var startCaption=bodyLabel("Start · "+zone);startCaption.setLabelFor(start);
        var endCaption=bodyLabel("End");endCaption.setLabelFor(end);
        form.add(activityCaption);form.add(activity);gap(form,SPACE_MD);
        form.add(startCaption);form.add(start);gap(form,SPACE_MD);form.add(endCaption);form.add(end);
        var running=new JCheckBox("Keep timer running",session!=null&&session.end()==null);running.setOpaque(false);running.setForeground(TEXT);
        if(session!=null&&session.end()==null)form.add(running);
        while(Dialogs.confirm(this,form,plan?"Schedule block":existing?"Edit tracked time":"Log time","Save")) {
            try {
                UUID id=((Activity)activity.getSelectedItem()).id();Instant from=start.value(),to=running.isSelected()?null:end.value();
                if(plan){if(existing)tracker.editBlock(block.id(),id,from,to);else tracker.plan(id,from,to);}
                else if(existing)tracker.editSession(session.id(),id,from,to);else tracker.log(id,from,to);
                showPage(page);return;
            }catch(Exception e){error(e);}
        }
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

    /**
     * Moves to another vault. Unsaved page text is saved first, and nothing
     * moves until the vault being moved to has opened in full.
     */
    private void switchVault() {
        if (!pagesPage.flush()) return;
                var next=VaultLauncher.choose(this,store,vaultName);
        if(next!=null)moveTo(next);
    
    }

    /** Makes a new vault and moves into it, by the same rule. */
    private void newVault() {
        if (!pagesPage.flush()) return;
                var made=VaultLauncher.create(this,store);
        if(made!=null)moveTo(made);
    
    }

    /** Moves the window onto an opened vault, tracking its name and its secret. */
    private void moveTo(VaultLauncher.Opened next) {
        if (!pagesPage.flush()) { try { next.vault().close(); } catch (Exception e) { error(e); } return; }
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
        pagesPage.clear();
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
        if (!pagesPage.flush()) return;
                String current=vaultName;
        String wanted=Dialogs.input(this,"Rename \""+current+"\".","Rename vault",current);
        if(wanted==null||wanted.strip().equals(current))return;
        String target;
        try { target=VaultStore.validate(wanted); }
        catch(IllegalArgumentException e) { error(e); return; }
        try {
            if (!pagesPage.flush()) return;
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
        if (!pagesPage.flush()) return;
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

    /** Applies a settings change, rebuilding the window when the palette moved. */
    @Override public void applySettings(java.util.function.UnaryOperator<Settings> change) {
        try {
            var previous=tracker.state().settings();
            var next=change.apply(previous);
            tracker.settings(next);
            // The visible week was computed under the old preference; moving the
            // week start without realigning it leaves the grid straddling two.
            if(next.weekStartsOn()!=previous.weekStartsOn()) schedulePage.weekStartsOn(next);
            if (next.theme()!=previous.theme()) { SystemAppearance.choose(false); Theme.systemStyle=null; }
            if(!SystemAppearance.enabled() && next.theme()!=Theme.current()) { Theme.apply(next.theme()); rebuild(); }
            else showPage(page);
        } catch(Exception e) { error(e); }
    }

    @Override public void selectTheme(ThemeId id) {
        if (!pagesPage.flush()) return;
        try {
            var settings=tracker.state().settings();
            tracker.settings(new Settings(id,settings.dailyGoalHours(),settings.minSessionSeconds(),settings.weekStartsOn()));
            SystemAppearance.choose(false); Theme.systemStyle=null; Theme.apply(id); rebuild();
        } catch (Exception e) { error(e); }
    }
    @Override public void systemAppearance(boolean on) {
        if (!pagesPage.flush()) return;
        SystemAppearance.choose(on);
        if (on) { appearanceCheck=0; refreshAppearance(); }
        else { Theme.systemStyle=null; Theme.apply(tracker.state().settings().theme()); rebuild(); }
    }
    private void refreshAppearance() {
        if (appearanceBusy || closed) return;
        appearanceBusy=true; appearanceCheck=System.currentTimeMillis();
        new SwingWorker<SystemAppearance.Style,Void>() {
            protected SystemAppearance.Style doInBackground() { return SystemAppearance.read(); }
            protected void done() {
                appearanceBusy=false;
                if (closed || !SystemAppearance.enabled()) return;
                try {
                    var style=get();
                    if (!style.equals(Theme.systemStyle) && pagesPage.flush()) {
                        Theme.systemStyle=style; Theme.apply(style.theme()); rebuildTo(page);
                    }
                } catch (Exception e) { error(e); }
            }
        }.execute();
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
        if (!pagesPage.flush()) return;
        var window=SwingUtilities.getWindowAncestor(this);
        // A theme change rebuilds the whole window and lands back on Settings,
        // where the palette was picked partway down; the new window keeps that place (#9).
        Point keep=next.equals(page)&&pageScroll!=null?pageScroll.getViewport().getViewPosition():null;
        ticker.stop();
        music.close();
        todayPage.close();
        pagesPage.stop();
        if(window instanceof JFrame frame) {
            closed=true;
            var fresh=new YoruApp(tracker,vault,store,vaultName,secret,assistants);
            frame.setContentPane(fresh);
            DesktopChrome.install(frame,fresh);
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

    @Override public void chooseReset() {
        var form=stack();var all=new JCheckBox("All data");all.setOpaque(false);all.setForeground(GOLD_TEXT);form.add(all);
        var choices=new LinkedHashMap<Tracker.ResetPart,JCheckBox>();
        for(var part:Tracker.ResetPart.values()) {var check=new JCheckBox(part.label);check.setOpaque(false);check.setForeground(TEXT);choices.put(part,check);form.add(check);}
        all.addActionListener(e->choices.values().forEach(c->c.setSelected(all.isSelected())));
        if(!Dialogs.confirm(this,form,"Choose reset scope","Continue"))return;
        var parts=EnumSet.noneOf(Tracker.ResetPart.class);choices.forEach((part,check)->{if(check.isSelected())parts.add(part);});
        if(parts.isEmpty()){Dialogs.info(this,"Nothing selected. No data was changed.");return;}
        String selected=choices.entrySet().stream().filter(e->parts.contains(e.getKey())).map(e->"• "+e.getValue().getText()).collect(java.util.stream.Collectors.joining("\n"));
        if(!Dialogs.confirmDestructive(this,"Reset these sections?\n\n"+selected+"\n\nA backup will be saved first. This also removes an active timer if sessions are selected.","Confirm reset","Reset"))return;
        perform(()->tracker.reset(parts));
    }
    /**
     * Tells the owner where their game save went, once (#58).
     *
     * Yoru no longer has the game, and a vault written by an older build may
     * still be holding its save. Opening such a vault writes the save to a file
     * beside it rather than dropping it; this is the sentence that says so, and
     * it is said once, because the file is only written once.
     */
    private static void sayWhereTheGameSaveWent(YoruApp app, Repository vault) {
        if (!(vault instanceof dev.yoru.persistence.EncryptedVault encrypted)) return;
        var rescued = encrypted.rescuedSave();
        if (rescued == null) return;
        Dialogs.info(app, "Your game save was kept",
            "Yoru no longer includes the game, so the save this vault was holding has been written\n"
            + "beside it as " + rescued.getFileName() + ".\n\n"
            + "Open it with any Game Boy Advance emulator. Yoru will not touch it again.");
    }

    /** Opens the window. Started through {@link dev.yoru.Yoru}, which sends {@code --mcp} elsewhere first. */
    public static void main(String[] args) {
        DesktopChrome.prepare();
        if (SystemAppearance.enabled()) Theme.systemStyle=SystemAppearance.read();
        SwingUtilities.invokeLater(()-> {
            // Before anything is built, the vault launcher included: this
            // computer's text size reaches every window (#31).
            TextSize.use(TextSize.saved());
            Theme.install();try {
                var opened=VaultLauncher.open();if(opened==null)return;
                var tracker=new Tracker(opened.vault(),Clock.systemUTC());
                // The launcher runs on the default palette; switch before building the app.
                Theme.apply(SystemAppearance.enabled() && Theme.systemStyle != null ? Theme.systemStyle.theme() : tracker.state().settings().theme());
                // The store, the name and the session's secret travel with the
                // window: rename, switch and delete all happen from the Data page.
                var app=new YoruApp(tracker,opened.vault(),opened.store(),opened.name(),opened.secret(),null);
                var frame=new JFrame("Yoru / 夜 — local study workspace");frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);frame.setContentPane(app);DesktopChrome.install(frame,app);frame.pack();
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
                sayWhereTheGameSaveWent(app,opened.vault());
            }
            catch(Exception e) {
                Dialogs.error(null,e.getMessage());
            }
        }
        );
    }
}
