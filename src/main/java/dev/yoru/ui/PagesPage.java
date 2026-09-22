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
import static dev.yoru.ui.Theme.*;

/** The vault's Markdown workspace. Editors live for the lifetime of their tabs. */
final class PagesPage {
    private final Shell shell;
    private JPanel root, details;
    private PageExplorer explorer;
    private JTabbedPane tabs;
    private PageIndex index;
    private JLabel status;
    private final Map<UUID, DocumentTab> documents = new LinkedHashMap<>();
    private final Deque<UUID> back = new ArrayDeque<>(), forward = new ArrayDeque<>();
    private UUID selected;
    private boolean changing;

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
    private void build() {
        root = new JPanel(new BorderLayout(SPACE_MD, SPACE_SM));
        root.setOpaque(false); root.setName("pages.workspace");
        var tools = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_SM));
        tools.setOpaque(false);
        tools.add(label("Pages", TYPE_TITLE, TEXT));
        tools.add(button("Back", () -> navigate(back, forward)));
        tools.add(button("Forward", () -> navigate(forward, back)));
        tools.add(button("Open…", () -> switcher(false)));
        tools.add(button("Search…", () -> switcher(true)));
        tools.add(button("Read / Edit", this::toggleReading));
        tools.add(button("Find / Replace", () -> { var tab = active(); if (tab != null) { tab.reading = false; tab.draw(); tab.editor.find(); } }));
        tools.add(button("Details", () -> { details.setVisible(!details.isVisible()); updateDetails(); root.revalidate(); }));
        tools.add(button("Import…", this::importMarkdown));
        tools.add(button("Export…", this::exportMarkdown));
        tools.add(button("Close page", this::closeTab));
        root.add(tools, BorderLayout.NORTH);
        tabs = new JTabbedPane(); tabs.setMinimumSize(new Dimension(220, 100)); tabs.setName("pages.tabs");
        tabs.addChangeListener(e -> {
            if (changing) return;
            var old = documents.get(selected);
            if (old != null && !old.editor.flush()) {
                changing = true; tabs.setSelectedComponent(old.panel); changing = false; return;
            }
            UUID next = documents.values().stream().filter(t -> t.panel == tabs.getSelectedComponent()).map(t -> t.id).findFirst().orElse(null);
            if (selected != null && !Objects.equals(selected, next)) { back.push(selected); forward.clear(); }
            selected = next;
            updateDetails();
        });
        explorer = new PageExplorer(new PageExplorer.Host() {
            public Pages pages() { return PagesPage.this.pages(); }
            public Component owner() { return shell.owner(); }
            public void run(Shell.Work work) { change(work); }
            public void open(UUID id) { PagesPage.this.open(id); }
            public void newPage(UUID folder) { PagesPage.this.newPage(folder); }
            public UUID current() { return selected; }
        });
        explorer.setMinimumSize(new Dimension(grow(150), 100));
        var editing = new JPanel(new BorderLayout(SPACE_SM, 0)); editing.setOpaque(false);
        editing.add(tabs, BorderLayout.CENTER);
        details = new JPanel(new BorderLayout()); details.setOpaque(false);
        details.setPreferredSize(new Dimension(grow(240), 200)); details.setVisible(false);
        editing.add(details, BorderLayout.EAST);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, editing);
        split.setBorder(null); split.setResizeWeight(0); split.setDividerLocation(grow(220));
        split.setContinuousLayout(true); root.add(split, BorderLayout.CENTER);
        status = wrapping("Choose a page, or create one in the explorer.", TYPE_CAPTION, MUTED);
        status.setName("pages.saveStatus"); root.add(status, BorderLayout.SOUTH);
        int menu = GraphicsEnvironment.isHeadless() ? InputEvent.CTRL_DOWN_MASK : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_O, menu), "pages.open", () -> switcher(false));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, menu | InputEvent.SHIFT_DOWN_MASK), "pages.search", () -> switcher(true));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_E, menu), "pages.read", this::toggleReading);
    }
    private JScrollPane scroll(JComponent view) {
        var pane = new JScrollPane(view); pane.setBorder(null);
        pane.getViewport().setBackground(PANEL); pane.getVerticalScrollBar().setUnitIncrement(SPACE_XL);
        return pane;
    }
    private void bind(KeyStroke key, String name, Runnable action) {
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        root.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }
    boolean flush() {
        for (var tab : documents.values()) if (!tab.editor.flush()) return false;
        return true;
    }
    void stop() { documents.values().forEach(t -> t.editor.stop()); }
    void clear() { stop(); documents.clear(); selected = null; root = null; index = null; back.clear(); forward.clear(); }
    private DocumentTab active() { return documents.get(selected); }
    void open(UUID id) {
        if (root == null) build();
        if (!flush()) return;
        var page = notes().page(id).filter(p -> !p.trashed()).orElse(null);
        if (page == null) return;
        if (selected != null && !selected.equals(id)) { back.push(selected); forward.clear(); }
        choose(page);
    }
    private void choose(Page page) {
        changing = true;
        try {
            var tab = documents.get(page.id());
            if (tab == null) {
                tab = new DocumentTab(page);
                documents.put(page.id(), tab);
                tabs.addTab(page.title(), tab.panel);
            }
            selected = page.id(); tabs.setSelectedComponent(tab.panel);
        } finally { changing = false; }
        explorer.reveal(selected); updateDetails();
    }
    private void navigate(Deque<UUID> from, Deque<UUID> to) {
        if (!flush()) return;
        while (!from.isEmpty()) {
            var page = notes().page(from.pop()).filter(p -> !p.trashed());
            if (page.isEmpty()) continue;
            if (selected != null) to.push(selected);
            choose(page.get()); return;
        }
    }
    void newPage(UUID folder) {
        if (!flush()) return;
        String title = Dialogs.input(shell.owner(), "Page title", "New page", pages().freeTitle(folder, "Untitled"));
        if (title == null) return;
        change(() -> { var page = pages().createPage(folder, pages().freeTitle(folder, title), ""); open(page.id()); });
    }
    private void closeTab() {
        var tab = active();
        if (tab == null || !tab.editor.flush()) return;
        changing = true;
        tab.editor.stop(); documents.remove(tab.id); tabs.remove(tab.panel); selected = null;
        changing = false;
        var next = documents.values().stream().findFirst();
        if (next.isPresent()) choose(pages().page(next.get().id)); else updateDetails();
    }
    private void toggleReading() {
        var tab = active(); if (tab == null || !tab.editor.flush()) return;
        tab.reading = !tab.reading; tab.draw(); updateDetails();
    }
    private void change(Shell.Work work) {
        if (!flush()) return;
        try { work.run(); sync(); }
        catch (Exception e) { shell.error(e); }
    }
    private void sync() {
        index();
        changing = true;
        for (var it = documents.values().iterator(); it.hasNext();) {
            var tab = it.next(); var page = notes().page(tab.id).filter(p -> !p.trashed());
            if (page.isEmpty()) { tab.editor.stop(); tabs.remove(tab.panel); it.remove(); if (tab.id.equals(selected)) selected = null; }
            else {
                int at = tabs.indexOfComponent(tab.panel); tabs.setTitleAt(at, page.get().title());
                if (!tab.editor.dirty() && !tab.editor.text().equals(page.get().body())) { tab.editor.load(page.get().body()); tab.draw(); }
            }
        }
        changing = false; explorer.sync(notes());
        if (selected == null && !documents.isEmpty()) choose(pages().page(documents.values().iterator().next().id));
        else updateDetails();
    }
    private void updateDetails() {
        if (details == null || status == null) return;
        var column = stack(); var tab = active();
        if (tab == null) { status.setText("Choose a page, or create one in the explorer."); }
        else {
            var page = pages().page(tab.id);
            String text = tab.editor.text();
            long words = text.isBlank() ? 0 : text.strip().split("\\s+").length;
            status.setText((tab.saveError != null ? "Not saved — " + tab.saveError : tab.editor.dirty() ? "Unsaved changes" : "Saved") + " · " + words + " words · " + text.length() + " characters");
            if (!details.isVisible()) return;
            column.add(sectionHeader("OUTLINE"));
            for (var block : Markdown.parse(text).allBlocks()) if (block.kind() == Markdown.BlockKind.HEADING) {
                String title = text.substring(block.contentStart(), block.contentEnd());
                column.add(button(title, () -> { tab.reading = false; tab.draw(); tab.editor.reveal(block.start(), block.end()); }));
            }
            gap(column, SPACE_MD); column.add(sectionHeader("BACKLINKS"));
            for (var mention : index().backlinks(tab.id)) {
                column.add(button(pages().page(mention.source()).title(), () -> {
                    open(mention.source()); if (active() != null) active().editor.reveal(mention.start(), mention.end());
                }));
            }
            gap(column, SPACE_MD); column.add(sectionHeader("LINKED TASKS"));
            for (var task : pages().tasksLinkedTo(tab.id)) {
                var check = new JCheckBox(task.title(), task.done()); check.setOpaque(false);
                check.addActionListener(e -> change(() -> shell.tracker().taskStatus(task.id(), check.isSelected() ? TaskStatus.DONE : TaskStatus.TODO)));
                column.add(check);
                column.add(button("Unlink task", () -> change(() -> pages().unlinkTask(task.id(), tab.id))));
            }
            column.add(button("Link existing task…", () -> linkTask(tab.id)));
            column.add(button("New linked task…", () -> {
                String title = Dialogs.input(shell.owner(), "Task title", "New linked task", "");
                if (title != null) change(() -> shell.tracker().addTask(new Task(UUID.randomUUID(), null, null,
                    title, "", null, TaskStatus.TODO, "Pages", java.time.Instant.now(),
                    shell.tracker().state().tasks().size()).withPages(List.of(tab.id))));
            }));
        }
        details.removeAll(); details.add(scroll(column), BorderLayout.CENTER); details.revalidate();
    }
    private void linkTask(UUID id) {
        var choices = shell.tracker().state().tasks();
        if (choices.isEmpty()) { shell.show("Tasks"); return; }
        var list = new JComboBox<Task>(choices.toArray(Task[]::new));
        list.setRenderer((l, task, i, s, f) -> label(task.title(), TYPE_BODY, TEXT));
        if (JOptionPane.showConfirmDialog(shell.owner(), list, "Link task", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            change(() -> pages().linkTask(((Task)list.getSelectedItem()).id(), id));
    }
    private void switcher(boolean search) {
        if (!flush()) return;
        var row = QuickSwitcher.ask(shell.owner(), search ? "Search pages" : "Open page", "Type to search; Enter to open", query -> {
            var out = new ArrayList<QuickSwitcher.Row>();
            if (search) for (var hit : index().search(query)) {
                var m = hit.lines().isEmpty() ? null : hit.lines().getFirst();
                out.add(new QuickSwitcher.Row(hit.page().id(), hit.page().title(), m == null ? "" : m.line(), m == null ? -1 : m.start(), m == null ? -1 : m.end(), null));
            } else for (var page : QuickSwitcher.rank(index().resolver(), query, 50)) out.add(QuickSwitcher.Row.page(page, index().resolver().path(page)));
            if (!search && !query.isBlank() && index().resolver().resolve(query, null).isEmpty()) out.add(QuickSwitcher.Row.create(query));
            return out;
        });
        if (row == null) return;
        if (row.create() != null) change(() -> open(pages().createPage(null, row.create(), "").id()));
        else { open(row.page()); if (row.start() >= 0 && active() != null) active().editor.reveal(row.start(), row.end()); }
    }
    private void follow(UUID from, Markdown.Span link) {
        if (!flush()) return;
        String target = link.target() == null ? "" : link.target();
        if (target.matches("(?i)^(https?|mailto):.*")) {
            try { Desktop.getDesktop().browse(URI.create(target)); } catch (Exception e) { shell.error(new Exception("Could not open this link.", e)); }
            return;
        }
        if (target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) { shell.error(new Exception("This link type is not supported.")); return; }
        var page = index().resolver().resolve(target, pages().page(from));
        if (page.isEmpty()) {
            if (!Dialogs.confirm(shell.owner(), "Create the missing page “" + target + "”?", "New linked page", "Create")) return;
            change(() -> open(pages().createPage(pages().page(from).folderId(), target, "").id()));
            return;
        }
        open(page.get().id());
        if (link.anchor() != null && !link.anchor().isBlank()) {
            var tab = active(); String anchor = link.anchor();
            for (var b : Markdown.parse(tab.editor.text()).allBlocks()) {
                boolean match = anchor.startsWith("^") ? anchor.substring(1).equals(b.blockId())
                    : b.kind() == Markdown.BlockKind.HEADING && tab.editor.text().substring(b.contentStart(), b.contentEnd()).equalsIgnoreCase(anchor);
                if (match) { tab.reading = false; tab.draw(); tab.editor.reveal(b.start(), b.end()); break; }
            }
        }
    }
    private final class DocumentTab {
        final UUID id;
        final JPanel panel = new JPanel(new BorderLayout());
        final PageEditor editor;
        boolean reading;
        String saveError;
        DocumentTab(Page page) {
            id = page.id(); panel.setOpaque(false);
            editor = new PageEditor(new PageEditor.Host() {
                public boolean resolves(String target) { return index().resolver().resolve(target, pages().page(id)).isPresent(); }
                public void follow(Markdown.Span link, boolean elsewhere) { PagesPage.this.follow(id, link); }
                public List<PageEditor.Choice> linkChoices(String typed) {
                    int hash = typed.indexOf('#');
                    if (hash >= 0) {
                        String target = typed.substring(0, hash), fragment = typed.substring(hash + 1);
                        var destination = index().resolver().resolve(target, pages().page(id));
                        if (destination.isEmpty()) return List.of();
                        String body = destination.get().body();
                        var choices = new ArrayList<PageEditor.Choice>();
                        for (var block : Markdown.parse(body).allBlocks()) {
                            String anchor = fragment.startsWith("^") ? (block.blockId() == null ? null : "^" + block.blockId())
                                : block.kind() == Markdown.BlockKind.HEADING ? body.substring(block.contentStart(), block.contentEnd()) : null;
                            if (anchor != null && anchor.toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT)))
                                choices.add(new PageEditor.Choice(anchor, destination.get().title(), target + "#" + anchor));
                            if (choices.size() == 30) break;
                        }
                        return choices;
                    }
                    return QuickSwitcher.rank(index().resolver(), typed, 30).stream()
                        .map(p -> new PageEditor.Choice(p.title(), index().resolver().path(p), index().resolver().linkText(p, pages().page(id)))).toList();
                }
                public boolean save(String text) {
                    try { pages().updateBody(id, text); saveError = null; index(); if (status != null) status.setText("Saved"); return true; }
                    catch (Exception e) { saveError = e.getMessage(); if (status != null) status.setText("Not saved — " + e.getMessage() + " Your text is still open; retry before leaving."); return false; }
                }
                public void parsed(Markdown.Doc doc) { if (id.equals(selected)) updateDetails(); }
            });
            editor.load(page.body()); draw();
        }
        void draw() {
            panel.removeAll();
            if (!reading) panel.add(editor, BorderLayout.CENTER);
            else panel.add(scroll(PageReader.render(Markdown.parse(editor.text()), new PageReader.Host() {
                public boolean resolves(String target) { return index().resolver().resolve(target, pages().page(id)).isPresent(); }
                public void follow(Markdown.Span link, boolean elsewhere) { PagesPage.this.follow(id, link); }
                public void check(int offset, boolean done) {
                    if (!flush()) return;
                    String body = editor.text();
                    if (offset < 0 || offset >= body.length() || " xX".indexOf(body.charAt(offset)) < 0) return;
                    editor.pane().select(offset, offset + 1);
                    editor.pane().replaceSelection(done ? "x" : " ");
                    if (editor.flush()) { draw(); updateDetails(); }
                }
                public JComponent embed(Markdown.Span link) { return null; }
            })), BorderLayout.CENTER);
            panel.revalidate(); panel.repaint();
        }
    }
    private void importMarkdown() { MarkdownFiles.importInto(shell, this::flush, this::sync); }
    private void exportMarkdown() { MarkdownFiles.exportFrom(shell, this::flush); }
}
