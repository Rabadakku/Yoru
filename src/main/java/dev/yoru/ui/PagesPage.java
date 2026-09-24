package dev.yoru.ui;

import dev.yoru.application.Pages;
import dev.yoru.domain.Model.*;
import dev.yoru.pages.*;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import static dev.yoru.ui.Theme.*;

/**
 * The vault's Markdown workspace: the explorer, the open pages and what each
 * page is connected to.
 *
 * The page on screen keeps its editor for as long as its tab is open, so
 * moving between pages, switching to reading and leaving for another part of
 * the app all keep the text, the caret and the undo history. Nothing leaves
 * this screen with unsaved text: every way out flushes first, and a save that
 * fails stops the move rather than losing what was typed.
 */
final class PagesPage {
    private final Shell shell;
    private JPanel root, documents, sidebar, tabStrip, empty;
    private CardLayout cards;
    private PageExplorer explorer;
    private JSplitPane split;
    private PageIndex index;
    private JLabel status, where;
    private JTextField title;
    private JButton mode, sidebarToggle;
    private final Map<UUID, DocumentTab> open = new LinkedHashMap<>();
    private final Deque<UUID> back = new ArrayDeque<>(), forward = new ArrayDeque<>();
    /** The pages being drawn as embeds right now, so a page cannot embed itself for ever. */
    private final Deque<UUID> embedding = new ArrayDeque<>();
    private static final int EMBED_DEPTH = 3;
    private UUID selected;
    private boolean sidebarOpen = true, narrowConnections, renaming;

    PagesPage(Shell shell) { this.shell = shell; }

    private Pages pages() { return shell.tracker().pages(); }
    private Notes notes() { return shell.tracker().state().notes(); }

    private PageIndex index() {
        if (index == null) index = new PageIndex(notes()); else index.update(notes());
        return index;
    }

    JPanel view() {
        if (root == null) build();
        sync();
        return root;
    }

    // ---------------------------------------------------------------- building

    private void build() {
        root = new JPanel(new BorderLayout()) {
            @Override public void doLayout() {
                layoutPanels();
                super.doLayout();
            }
        };
        root.setOpaque(false);
        root.setName("pages.workspace");

        explorer = new PageExplorer(new PageExplorer.Host() {
            public Pages pages() { return PagesPage.this.pages(); }
            public Component owner() { return shell.owner(); }
            public void run(Shell.Work work) { change(work); }
            public void open(UUID id) { PagesPage.this.open(id); }
            public void newPage(UUID folder) { PagesPage.this.newPage(folder); }
            public UUID current() { return selected; }
        });
        explorer.setMinimumSize(new Dimension(grow(160), 100));
        explorer.setPreferredSize(new Dimension(grow(240), 100));

        cards = new CardLayout();
        documents = new JPanel(cards);
        documents.setName("pages.documents");
        documents.setOpaque(true);
        documents.setBackground(PANEL);
        empty = emptyState("No page open.", "Choose a page on the left, or make a new one.",
            named(button("New page", () -> newPage(explorer.targetFolder())), "pages.new"));
        empty.setOpaque(false);
        documents.add(empty, "none");

        tabStrip = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_XS, SPACE_XS));
        tabStrip.setOpaque(false);
        tabStrip.setName("pages.tabs");

        sidebar = new JPanel(new BorderLayout());
        sidebar.setName("pages.connections");
        sidebar.setOpaque(true);
        sidebar.setBackground(PANEL);
        sidebar.setBorder(new MatteBorder(0, HAIRLINE, 0, 0, LINE));
        sidebar.setPreferredSize(new Dimension(grow(260), 100));

        var middle = new JPanel(new BorderLayout());
        middle.setOpaque(true);
        middle.setBackground(PANEL);
        middle.add(header(), BorderLayout.NORTH);
        middle.add(documents, BorderLayout.CENTER);
        middle.add(sidebar, BorderLayout.EAST);

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, middle);
        split.setBackground(PANEL);
        split.setBorder(new LineBorder(LINE));
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    // The look-and-feel's divider is a raised bevel in its own
                    // grey; the panes are separated the way every other edge in
                    // the app is, with one hairline.
                    @Override public void paint(Graphics g) { g.setColor(LINE); g.fillRect(0, 0, getWidth(), getHeight()); }
                };
            }
        });
        split.setDividerSize(HAIRLINE * 3);
        split.setDividerLocation(grow(240));
        root.add(split, BorderLayout.CENTER);

        status = label("", TYPE_CAPTION, MUTED);
        status.setName("pages.saveStatus");
        status.setBorder(new EmptyBorder(SPACE_XS, SPACE_XS, 0, SPACE_XS));
        root.add(status, BorderLayout.SOUTH);
        keys();
    }

    /** At narrow widths, the explorer and connections share the side-panel space. */
    private void layoutPanels() {
        if (split == null) return;
        boolean narrow = root.getWidth() > 0 && root.getWidth() < grow(900);
        boolean connections = selected != null && (narrow ? narrowConnections : sidebarOpen);
        sidebar.setVisible(connections);
        boolean showExplorer = !narrow || !connections;
        if (explorer.isVisible() != showExplorer) {
            explorer.setVisible(showExplorer);
            split.setDividerSize(showExplorer ? HAIRLINE * 3 : 0);
            split.setDividerLocation(showExplorer ? grow(240) : 0);
        }
    }

    /** Back and forward, the page's own title, and what to do with it. */
    private JPanel header() {
        var bar = new JPanel(new BorderLayout(SPACE_SM, 0));
        bar.setOpaque(false);
        bar.setBorder(new CompoundBorder(new MatteBorder(0, 0, HAIRLINE, 0, LINE),
            new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD)));

        var moves = new JPanel(new FlowLayout(FlowLayout.LEFT, SPACE_XS, 0));
        moves.setOpaque(false);
        moves.add(PageExplorer.icon(Glyphs.Kind.BACK, "Back", "pages.back", () -> navigate(back, forward)));
        moves.add(PageExplorer.icon(Glyphs.Kind.FORWARD, "Forward", "pages.forward", () -> navigate(forward, back)));
        bar.add(moves, BorderLayout.WEST);

        title = styleInput(new JTextField());
        title.setName("pages.title");
        title.getAccessibleContext().setAccessibleName("Page title");
        title.setFont(sans(TYPE_HEADING).deriveFont(Font.BOLD));
        title.setBorder(new EmptyBorder(SPACE_XS, SPACE_XS, SPACE_XS, SPACE_XS));
        title.setBackground(PANEL);
        title.addActionListener(e -> { rename(); documents.requestFocusInWindow(); focusEditor(); });
        title.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { rename(); }
        });
        where = label("", TYPE_CAPTION, MUTED);
        var naming = new JPanel(new BorderLayout());
        naming.setOpaque(false);
        naming.add(title, BorderLayout.CENTER);
        naming.add(where, BorderLayout.SOUTH);
        bar.add(naming, BorderLayout.CENTER);

        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, SPACE_XS, 0));
        actions.setOpaque(false);
        mode = button("Read", this::toggleReading);
        mode.setName("pages.mode");
        mode.setToolTipText("Switch between editing and reading (Cmd/Ctrl-E)");
        ghost(mode);
        actions.add(mode);
        actions.add(PageExplorer.icon(Glyphs.Kind.SEARCH, "Search pages (Cmd/Ctrl-Shift-F)", "pages.search", () -> switcher(true)));
        sidebarToggle = PageExplorer.icon(Glyphs.Kind.SIDEBAR, "Show or hide the page's connections", "pages.sidebar", () -> {
            if (root.getWidth() < grow(900)) narrowConnections = !narrowConnections;
            else sidebarOpen = !sidebarOpen;
            layoutPanels();
            updateSidebar();
            root.revalidate();
        });
        actions.add(sidebarToggle);
        var more = PageExplorer.icon(Glyphs.Kind.FILES, "More", "pages.more", null);
        more.addActionListener(e -> menu().show(more, 0, more.getHeight()));
        actions.add(more);
        bar.add(actions, BorderLayout.EAST);

        var head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(bar, BorderLayout.NORTH);
        head.add(tabStripHolder(), BorderLayout.CENTER);
        return head;
    }

    private JComponent tabStripHolder() {
        var holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setBorder(new CompoundBorder(new MatteBorder(0, 0, HAIRLINE, 0, LINE),
            new EmptyBorder(SPACE_XS, SPACE_MD, SPACE_XS, SPACE_MD)));
        holder.add(tabStrip, BorderLayout.CENTER);
        tabStrip.getAccessibleContext().setAccessibleName("Open pages");
        return holder;
    }

    private JPopupMenu menu() {
        var menu = Menus.popup();
        boolean any = selected != null;
        menu.add(Menus.item("Open page…", "pages.menu.open", true, () -> switcher(false), null));
        menu.add(Menus.item("Search in pages…", "pages.menu.search", true, () -> switcher(true), null));
        menu.addSeparator();
        menu.add(Menus.item("New page", "pages.menu.new", true, () -> newPage(explorer.targetFolder()), null));
        menu.add(Menus.item("Find in page…", "pages.menu.find", any, () -> {
            var tab = active();
            if (tab == null) return;
            if (tab.reading) toggleReading();
            tab.editor.find();
        }, "Open a page first"));
        menu.add(Menus.item("Close page", "pages.menu.close", any, this::closeTab, "Open a page first"));
        menu.addSeparator();
        menu.add(Menus.item("Import Markdown…", "pages.menu.import", true, this::importMarkdown, null));
        menu.add(Menus.item("Export as Markdown…", "pages.menu.export", true, this::exportMarkdown, null));
        return menu;
    }

    private void keys() {
        int menu = TextInput.menuKey();
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_O, menu), "pages.open", () -> switcher(false));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, menu | InputEvent.SHIFT_DOWN_MASK), "pages.search", () -> switcher(true));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_E, menu), "pages.read", this::toggleReading);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_N, menu), "pages.new", () -> newPage(explorer.targetFolder()));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), "pages.close", this::closeTab);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, menu), "pages.back", () -> navigate(back, forward));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, menu), "pages.forward", () -> navigate(forward, back));
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        root.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }

    private JScrollPane scroll(JComponent view) {
        var pane = new JScrollPane(view);
        pane.setBorder(null);
        pane.getViewport().setBackground(PANEL);
        pane.getVerticalScrollBar().setUnitIncrement(SPACE_XL);
        return pane;
    }

    // ---------------------------------------------------------------- leaving

    /** Saves every open page. False when a save failed, which stops whatever was leaving. */
    boolean flush() {
        for (var tab : open.values()) if (!tab.editor.flush()) return false;
        return true;
    }

    void stop() { open.values().forEach(t -> t.editor.stop()); }

    void clear() {
        stop();
        open.clear();
        selected = null;
        root = null;
        index = null;
        back.clear();
        forward.clear();
    }

    private DocumentTab active() { return open.get(selected); }

    // ---------------------------------------------------------------- opening

    void open(UUID id) {
        if (root == null) build();
        if (!flush()) return;
        var page = notes().page(id).filter(p -> !p.trashed()).orElse(null);
        if (page == null) return;
        if (selected != null && !selected.equals(id)) { back.push(selected); forward.clear(); }
        choose(page);
    }

    private void choose(Page page) {
        var tab = open.get(page.id());
        if (tab == null) {
            tab = new DocumentTab(page);
            open.put(page.id(), tab);
            documents.add(tab.panel, page.id().toString());
        }
        selected = page.id();
        cards.show(documents, page.id().toString());
        explorer.reveal(selected);
        drawTabs();
        updateSidebar();
        focusEditor();
    }

    private void focusEditor() {
        var tab = active();
        if (tab != null && !tab.reading) tab.editor.focusText();
    }

    private void navigate(Deque<UUID> from, Deque<UUID> to) {
        if (!flush()) return;
        while (!from.isEmpty()) {
            var page = notes().page(from.pop()).filter(p -> !p.trashed());
            if (page.isEmpty()) continue;
            if (selected != null) to.push(selected);
            choose(page.get());
            return;
        }
        Toolkit.getDefaultToolkit().beep();
    }

    /** A new page appears at once, named Untitled, with its title selected to type over. */
    void newPage(UUID folder) {
        if (!flush()) return;
        change(() -> {
            var page = pages().createPage(folder, pages().freeTitle(folder, "Untitled"), "");
            open(page.id());
            title.requestFocusInWindow();
            title.selectAll();
        });
    }

    private void closeTab() {
        var tab = active();
        if (tab == null || !tab.editor.flush()) return;
        tab.editor.stop();
        open.remove(tab.id);
        documents.remove(tab.panel);
        back.remove(tab.id);
        forward.remove(tab.id);
        selected = null;
        var next = open.values().stream().findFirst();
        if (next.isPresent()) choose(pages().page(next.get().id));
        else {
            cards.show(documents, "none");
            drawTabs();
            updateSidebar();
        }
    }

    private void toggleReading() {
        var tab = active();
        if (tab == null || !tab.editor.flush()) return;
        tab.reading = !tab.reading;
        tab.draw();
        mode.setText(tab.reading ? "Edit" : "Read");
        updateSidebar();
    }

    private void rename() {
        var tab = active();
        if (tab == null || renaming) return;
        String wanted = title.getText().strip();
        var page = notes().page(tab.id).orElse(null);
        if (page == null || wanted.isEmpty() || wanted.equals(page.title())) { showTitle(); return; }
        renaming = true;
        try {
            pages().renamePage(tab.id, wanted);
            sync();
        } catch (Exception e) {
            shell.error(e);
            showTitle();
        } finally {
            renaming = false;
        }
    }

    private void showTitle() {
        var page = selected == null ? null : notes().page(selected).orElse(null);
        title.setText(page == null ? "Pages" : page.title());
        title.setEnabled(page != null);
        title.setForeground(page == null ? MUTED : TEXT);
        where.setText(page == null ? "" : folderPath(page));
        mode.setEnabled(page != null);
        if (active() != null) mode.setText(active().reading ? "Edit" : "Read");
    }

    private String folderPath(Page page) {
        var parts = new ArrayDeque<String>();
        for (var at = page.folderId() == null ? null : notes().folder(page.folderId()).orElse(null);
             at != null; at = at.parentId() == null ? null : notes().folder(at.parentId()).orElse(null))
            parts.push(at.name());
        return parts.isEmpty() ? "Top level" : String.join(" / ", parts);
    }

    // ---------------------------------------------------------------- tabs

    /** One pill per open page, the current one filled, each with its own close. */
    private void drawTabs() {
        tabStrip.removeAll();
        for (var tab : open.values()) {
            var page = notes().page(tab.id).orElse(null);
            if (page == null) continue;
            boolean active = tab.id.equals(selected);
            Color ground = active ? shade(PANEL, DARK ? 14 : -9) : PANEL;
            var row = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics graphics) {
                    var g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(ground);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g.dispose();
                }
            };
            row.setOpaque(false);
            var pill = button(page.title(), () -> { if (flush()) open(tab.id); });
            pill.setName("pages.tab." + tab.id);
            pill.setFont(labelFont());
            pill.setToolTipText(page.title());
            pill.setForeground(active ? TEXT : MUTED);
            pill.setBorder(new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_SM));
            pill.setContentAreaFilled(false);
            // Keep the full title in the tooltip and accessible name while Swing
            // elides the visible caption, leaving the close control reachable.
            pill.getAccessibleContext().setAccessibleName(page.title());
            Dimension natural = pill.getPreferredSize();
            pill.setPreferredSize(new Dimension(Math.min(grow(200), natural.width), natural.height));
            var close = button("×", () -> { if (flush()) { selectQuietly(tab.id); closeTab(); } });
            close.setName("pages.tab.close." + tab.id);
            close.setFont(labelFont());
            close.setToolTipText("Close " + page.title());
            close.getAccessibleContext().setAccessibleName("Close " + page.title());
            close.setBackground(ground);
            close.setForeground(MUTED);
            close.setBorder(new EmptyBorder(SPACE_SM, SPACE_SM, SPACE_SM, SPACE_SM));
            close.setPreferredSize(new Dimension(grow(28), natural.height));
            row.add(pill, BorderLayout.CENTER);
            row.add(close, BorderLayout.EAST);
            tabStrip.add(row);
        }
        tabStrip.setVisible(!open.isEmpty());
        tabStrip.revalidate();
        tabStrip.repaint();
        showTitle();
    }

    /** Makes a tab current without recording a step in the history, for closing it. */
    private void selectQuietly(UUID id) {
        if (open.containsKey(id)) { selected = id; cards.show(documents, id.toString()); }
    }

    // ---------------------------------------------------------------- the vault changed

    private void change(Shell.Work work) {
        if (!flush()) return;
        try { work.run(); sync(); }
        catch (Exception e) { shell.error(e); }
    }

    private void sync() {
        index();
        for (var it = open.values().iterator(); it.hasNext();) {
            var tab = it.next();
            var page = notes().page(tab.id).filter(p -> !p.trashed());
            if (page.isEmpty()) {
                // Trashed or deleted elsewhere: its tab goes, its text is already saved.
                tab.editor.stop();
                documents.remove(tab.panel);
                it.remove();
                back.remove(tab.id);
                forward.remove(tab.id);
                if (tab.id.equals(selected)) selected = null;
            } else if (!tab.editor.dirty() && !tab.editor.text().equals(page.get().body())) {
                // Changed under us: a rename elsewhere rewrote this page's links.
                tab.editor.load(page.get().body());
                tab.draw();
            }
        }
        explorer.sync(notes());
        if (selected == null && !open.isEmpty()) choose(pages().page(open.values().iterator().next().id));
        else if (selected == null) cards.show(documents, "none");
        drawTabs();
        updateSidebar();
    }

    // ---------------------------------------------------------------- the sidebar

    private void updateSidebar() {
        if (sidebar == null || status == null) return;
        var tab = active();
        status.setText(statusLine(tab));
        layoutPanels();
        if (!sidebar.isVisible()) { root.revalidate(); return; }
        var column = stack();
        column.setBorder(new EmptyBorder(SPACE_MD, SPACE_MD, SPACE_XL, SPACE_MD));
        String text = tab.editor.text();

        column.add(sectionHeader("OUTLINE"));
        gap(column, SPACE_XS);
        var doc = Markdown.parse(text);
        int headings = 0;
        for (var block : doc.allBlocks()) {
            if (block.kind() != Markdown.BlockKind.HEADING) continue;
            headings++;
            String heading = doc.headingText(block);
            int level = block.level();
            column.add(sidebarRow(heading, null, grow(SPACE_MD) * Math.max(0, level - 1), () -> {
                if (tab.reading) toggleReading();
                tab.editor.reveal(block.start(), block.end());
            }));
        }
        if (headings == 0) column.add(quiet("No headings yet."));

        gap(column, SPACE_LG);
        column.add(sectionHeader("LINKED FROM"));
        gap(column, SPACE_XS);
        var backlinks = index().backlinks(tab.id);
        for (var mention : backlinks) {
            var source = notes().page(mention.source()).orElse(null);
            if (source == null) continue;
            column.add(sidebarRow(source.title(), mention.line().strip(), 0, () -> {
                open(mention.source());
                var opened = active();
                if (opened != null) opened.editor.reveal(mention.start(), mention.end());
            }));
        }
        if (backlinks.isEmpty()) column.add(quiet("No page links here yet."));

        gap(column, SPACE_LG);
        column.add(sectionHeader("TASKS"));
        gap(column, SPACE_XS);
        var linked = pages().tasksLinkedTo(tab.id);
        for (var task : linked) {
            var row = new JPanel(new BorderLayout(SPACE_XS, 0));
            row.setOpaque(false);
            row.setAlignmentX(0);
            var check = new JCheckBox(task.title(), task.done());
            check.setOpaque(false);
            check.setFont(labelFont());
            check.setForeground(task.done() ? MUTED : TEXT);
            check.setToolTipText(task.title());
            check.addActionListener(e -> change(() ->
                shell.tracker().taskStatus(task.id(), check.isSelected() ? TaskStatus.DONE : TaskStatus.TODO)));
            var unlink = button("×", () -> change(() -> pages().unlinkTask(task.id(), tab.id)));
            unlink.setName("pages.task.unlink." + task.id());
            unlink.setFont(labelFont());
            unlink.setToolTipText("Unlink this task");
            unlink.getAccessibleContext().setAccessibleName("Unlink " + task.title());
            ghost(unlink);
            unlink.setBorder(new EmptyBorder(0, SPACE_XS, 0, SPACE_XS));
            row.add(check, BorderLayout.CENTER);
            row.add(unlink, BorderLayout.EAST);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            column.add(row);
        }
        if (linked.isEmpty()) column.add(quiet("No tasks linked to this page."));
        gap(column, SPACE_SM);
        var taskActions = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_XS, SPACE_XS));
        taskActions.setOpaque(false);
        taskActions.setAlignmentX(0);
        taskActions.add(ghost(button("Link a task…", () -> linkTask(tab.id))));
        taskActions.add(ghost(button("New task", () -> newTask(tab.id))));
        column.add(taskActions);

        sidebar.removeAll();
        sidebar.add(scroll(column), BorderLayout.CENTER);
        sidebar.revalidate();
        sidebar.repaint();
    }

    private String statusLine(DocumentTab tab) {
        if (tab == null) return "No page open. Cmd/Ctrl-O opens one, Cmd/Ctrl-N makes one.";
        String text = tab.editor.text();
        long words = text.isBlank() ? 0 : text.strip().split("\\s+").length;
        String saved = tab.saveError != null ? "Not saved — " + tab.saveError + " Your text is still here; try again."
            : tab.editor.dirty() ? "Unsaved changes" : "Saved";
        return saved + " · " + plural((int) words, "word") + " · " + text.length() + " characters";
    }

    /** A quiet, left-aligned line in the sidebar: the whole text in its tooltip, so nothing is lost when it shortens. */
    private static JButton sidebarRow(String text, String detail, int indent, Runnable action) {
        var row = button(text, action);
        row.setFont(labelFont());
        row.setHorizontalAlignment(SwingConstants.LEFT);
        row.setBackground(PANEL);
        row.setForeground(TEXT);
        row.setBorder(new EmptyBorder(SPACE_XS, SPACE_XS + indent, SPACE_XS, SPACE_XS));
        row.setToolTipText(detail == null || detail.isEmpty() ? text : text + " — " + detail);
        row.getAccessibleContext().setAccessibleName(text);
        row.setAlignmentX(0);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static JLabel quiet(String text) {
        var l = label(text, TYPE_CAPTION, MUTED);
        l.setBorder(new EmptyBorder(SPACE_XS, SPACE_XS, SPACE_XS, SPACE_XS));
        return l;
    }

    // ---------------------------------------------------------------- tasks

    /** A task in a picker, named by its title. */
    private record TaskChoice(Task task) {
        @Override public String toString() { return task.title(); }
    }

    private void linkTask(UUID page) {
        var tasks = shell.tracker().state().tasks().stream()
            .filter(t -> !t.pageIds().contains(page)).map(TaskChoice::new).toList();
        if (tasks.isEmpty()) {
            Dialogs.info(shell.owner(), "There are no other tasks to link. Make one on the Tasks page, or with New task here.");
            return;
        }
        var chosen = Dialogs.select(shell.owner(), "Link a task to this page", "Link a task", tasks);
        if (chosen != null) change(() -> pages().linkTask(chosen.task().id(), page));
    }

    private void newTask(UUID page) {
        String title = Dialogs.input(shell.owner(), "What is the task?", "New task linked to this page");
        if (title == null || title.isBlank()) return;
        change(() -> {
            var task = new Task(UUID.randomUUID(), null, null, title, "", null, TaskStatus.TODO, "Pages",
                shell.tracker().now(), shell.tracker().state().tasks().size());
            shell.tracker().addTask(task);
            pages().linkTask(task.id(), page);
        });
    }

    // ---------------------------------------------------------------- finding and following

    private void switcher(boolean search) {
        if (!flush()) return;
        var row = QuickSwitcher.ask(shell.owner(), search ? "Search pages" : "Open page",
            search ? "Words to find; \"quoted\" for a phrase" : "Type to search; Enter opens", query -> {
                var out = new ArrayList<QuickSwitcher.Row>();
                if (search) {
                    for (var hit : index().search(query)) {
                        var m = hit.lines().isEmpty() ? null : hit.lines().getFirst();
                        out.add(new QuickSwitcher.Row(hit.page().id(), hit.page().title(), m == null ? "" : m.line().strip(),
                            m == null ? -1 : m.start(), m == null ? -1 : m.end(), null));
                    }
                } else {
                    for (var page : QuickSwitcher.rank(index().resolver(), query, 50))
                        out.add(QuickSwitcher.Row.page(page, index().resolver().path(page)));
                    if (!query.isBlank() && index().resolver().resolve(query, null).isEmpty())
                        out.add(QuickSwitcher.Row.create(query.strip()));
                }
                return out;
            });
        if (row == null) return;
        if (row.create() != null) change(() -> open(pages().createPage(null, row.create(), "").id()));
        else {
            open(row.page());
            if (row.start() >= 0 && active() != null) active().editor.reveal(row.start(), row.end());
        }
    }

    /** Follows a link: a page, a heading inside one, a web address, or an offer to create what is missing. */
    private void follow(UUID from, Markdown.Span link) {
        if (!flush()) return;
        String target = link.target() == null ? "" : link.target();
        if (target.matches("(?i)^(https?|mailto):.*")) {
            try { Desktop.getDesktop().browse(URI.create(target)); }
            catch (Exception e) { shell.error(new Exception("Could not open this link.", e)); }
            return;
        }
        if (target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) { shell.error(new Exception("This link type is not supported.")); return; }
        var here = pages().page(from);
        if (target.isBlank() && link.anchor() != null) { reveal(from, link.anchor()); return; }
        var page = index().resolver().resolve(target, here);
        if (page.isEmpty()) {
            if (!Dialogs.confirm(shell.owner(), "Create the missing page “" + target + "”?", "New linked page", "Create")) return;
            change(() -> open(pages().createPage(here.folderId(), target, "").id()));
            return;
        }
        open(page.get().id());
        if (link.anchor() != null && !link.anchor().isBlank()) reveal(page.get().id(), link.anchor());
    }

    /** Scrolls to a heading, or to a block by its ^id, in an open page. */
    private void reveal(UUID page, String anchor) {
        var tab = open.get(page);
        if (tab == null) return;
        String text = tab.editor.text();
        for (var b : Markdown.parse(text).allBlocks()) {
            boolean match = anchor.startsWith("^") ? anchor.substring(1).equals(b.blockId())
                : b.kind() == Markdown.BlockKind.HEADING
                    && text.substring(b.contentStart(), b.contentEnd()).strip().equalsIgnoreCase(anchor.strip());
            if (!match) continue;
            if (tab.reading) toggleReading();
            tab.editor.reveal(b.start(), b.end());
            return;
        }
    }

    // ---------------------------------------------------------------- embeds

    /**
     * An embedded page, drawn inside the page that embeds it.
     *
     * A page that embeds itself, or a ring of pages that embed each other,
     * would draw for ever; each one being drawn is remembered, and the depth is
     * capped, so the ring shows as a link instead.
     */
    private JComponent embed(UUID from, Markdown.Span link) {
        String target = link.target() == null ? "" : link.target();
        if (target.isBlank()) return null;
        var page = index().resolver().resolve(target, pages().page(from)).orElse(null);
        if (page == null) return null;
        String body = page.body();
        if (link.anchor() != null && !link.anchor().isBlank()) {
            String section = section(body, link.anchor());
            if (section == null) return null;
            body = section;
        }
        var box = new Theme.VerticalPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD)));
        var heading = button(page.title() + (link.anchor() == null ? "" : " › " + link.anchor()), () -> open(page.id()));
        heading.setFont(labelFont());
        heading.setForeground(ACCENT_TEXT);
        heading.setBackground(PANEL);
        heading.setHorizontalAlignment(SwingConstants.LEFT);
        heading.setBorder(new EmptyBorder(0, 0, SPACE_XS, 0));
        heading.setToolTipText("Open " + page.title());
        heading.setAlignmentX(0);
        box.add(heading);
        if (embedding.contains(page.id()) || embedding.size() >= EMBED_DEPTH) {
            box.add(quiet("This page is already shown above; open it to read it."));
            return box;
        }
        embedding.push(page.id());
        try {
            box.add(PageReader.render(Markdown.parse(body), readerHost(page.id(), true)));
        } finally {
            embedding.pop();
        }
        return box;
    }

    /** The text under a heading, or the block with a ^id, as its own little document. */
    private static String section(String body, String anchor) {
        var doc = Markdown.parse(body);
        for (var b : doc.allBlocks()) {
            if (anchor.startsWith("^")) {
                if (anchor.substring(1).equals(b.blockId())) return body.substring(b.start(), b.end());
                continue;
            }
            if (b.kind() != Markdown.BlockKind.HEADING
                || !body.substring(b.contentStart(), b.contentEnd()).strip().equalsIgnoreCase(anchor.strip())) continue;
            int end = body.length();
            for (var next : doc.allBlocks())
                if (next.kind() == Markdown.BlockKind.HEADING && next.start() > b.start() && next.level() <= b.level()) {
                    end = next.start();
                    break;
                }
            return body.substring(b.start(), end);
        }
        return null;
    }

    /** How a rendered page answers links, checkboxes and embeds. embedded pages are read-only. */
    private PageReader.Host readerHost(UUID id, boolean embedded) {
        return new PageReader.Host() {
            public boolean resolves(String target) { return index().resolver().resolve(target, pages().page(id)).isPresent(); }
            public void follow(Markdown.Span link, boolean elsewhere) { PagesPage.this.follow(id, link); }
            public void check(int offset, boolean done) {
                if (embedded) { open(id); return; }
                var tab = open.get(id);
                if (tab == null) return;
                tab.editor.check(offset, done);
                if (tab.editor.flush()) { tab.draw(); updateSidebar(); }
            }
            public JComponent embed(Markdown.Span link) { return PagesPage.this.embed(id, link); }
        };
    }

    // ---------------------------------------------------------------- one open page

    private final class DocumentTab {
        final UUID id;
        final JPanel panel = new JPanel(new BorderLayout());
        final PageEditor editor;
        boolean reading;
        String saveError;

        DocumentTab(Page page) {
            id = page.id();
            panel.setOpaque(false);
            editor = new PageEditor(new PageEditor.Host() {
                public boolean resolves(String target) { return index().resolver().resolve(target, pages().page(id)).isPresent(); }
                public void follow(Markdown.Span link, boolean elsewhere) { PagesPage.this.follow(id, link); }
                public List<PageEditor.Choice> linkChoices(String typed) { return choices(id, typed); }
                public boolean save(String text) {
                    try {
                        pages().updateBody(id, text);
                        saveError = null;
                        index();
                        if (status != null) status.setText(statusLine(DocumentTab.this));
                        return true;
                    } catch (Exception e) {
                        saveError = e.getMessage();
                        if (status != null) status.setText(statusLine(DocumentTab.this));
                        return false;
                    }
                }
                public void parsed(Markdown.Doc doc) { if (id.equals(selected)) updateSidebar(); }
            });
            editor.load(page.body());
            draw();
        }

        void draw() {
            panel.removeAll();
            if (!reading) panel.add(editor, BorderLayout.CENTER);
            else panel.add(scroll(PageReader.render(Markdown.parse(editor.text()), readerHost(id, false))), BorderLayout.CENTER);
            panel.revalidate();
            panel.repaint();
        }
    }

    /** What [[ offers: headings inside a named page after #, otherwise the pages themselves. */
    private List<PageEditor.Choice> choices(UUID from, String typed) {
        int hash = typed.indexOf('#');
        var resolver = index().resolver();
        if (hash >= 0) {
            String target = typed.substring(0, hash), fragment = typed.substring(hash + 1);
            var destination = resolver.resolve(target, pages().page(from));
            if (destination.isEmpty()) return List.of();
            String body = destination.get().body();
            var choices = new ArrayList<PageEditor.Choice>();
            for (var block : Markdown.parse(body).allBlocks()) {
                String anchor = fragment.startsWith("^") ? (block.blockId() == null ? null : "^" + block.blockId())
                    : block.kind() == Markdown.BlockKind.HEADING ? body.substring(block.contentStart(), block.contentEnd()).strip() : null;
                if (anchor != null && anchor.toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT)))
                    choices.add(new PageEditor.Choice(anchor, destination.get().title(), target + "#" + anchor));
                if (choices.size() == 30) break;
            }
            return choices;
        }
        return QuickSwitcher.rank(resolver, typed, 30).stream()
            .map(p -> new PageEditor.Choice(p.title(), resolver.path(p), resolver.linkText(p, pages().page(from)))).toList();
    }

    private void importMarkdown() { MarkdownFiles.importInto(shell, this::flush, this::sync); }

    private void exportMarkdown() { MarkdownFiles.exportFrom(shell, this::flush); }
}
