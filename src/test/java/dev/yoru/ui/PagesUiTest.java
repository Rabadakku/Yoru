package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.Page;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.io.IOException;
import java.time.Clock;
import javax.swing.*;

public final class PagesUiTest {
    static class Memory implements Repository {
        State state = State.empty(); boolean fail;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("Synthetic save failure"); state = next; }
        public void close() { }
    }
    static Component find(Container root, String name) {
        if (name.equals(root.getName())) return root;
        for (var c : root.getComponents()) {
            if (name.equals(c.getName())) return c;
            if (c instanceof Container nested) { var found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }
    /** The editor of the page on screen: the one card of the document stack that is showing. */
    static JTextPane editor(YoruApp app) {
        var documents = (Container)find(app, "pages.documents");
        for (var card : documents.getComponents())
            if (card.isVisible() && card instanceof Container c) {
                var pane = find(c, "page.editor");
                if (pane != null) return (JTextPane)pane;
            }
        throw new AssertionError("no page is open");
    }

    /**
     * The workspace around the editor: the title renames, every open page has a
     * tab, back and forward walk the pages visited, the sidebar says what the
     * page is connected to, and an embed draws the page it names.
     */
    static void workspace(YoruApp app, Tracker tracker, Page one, Page two) throws Exception {
        app.openNote(one.id());
        app.openNote(two.id());
        Preview.layout(app);

        // A tab for each open page, with its own close.
        assert find(app, "pages.tab." + one.id()) != null : "the first page keeps a tab";
        assert find(app, "pages.tab." + two.id()) != null : "the page just opened has a tab";
        assert find(app, "pages.tab.close." + two.id()) != null : "each tab closes on its own";

        // The header names the page on screen, and renames it.
        var title = (JTextField) find(app, "pages.title");
        assert title.getText().equals(tracker.pages().page(two.id()).title()) : "the header shows the open page: " + title.getText();
        title.setText("Second thoughts");
        title.postActionEvent();
        assert tracker.pages().page(two.id()).title().equals("Second thoughts") : "typing a title renames the page";
        assert ((JTextField) find(app, "pages.title")).getText().equals("Second thoughts");

        // Back and forward walk the pages that were opened.
        Preview.button(app, "pages.back").doClick();
        assert editorText(app).equals(tracker.pages().page(one.id()).body()) : "back returns to the page before";
        Preview.button(app, "pages.forward").doClick();
        assert editorText(app).equals(tracker.pages().page(two.id()).body()) : "forward returns again";

        // The sidebar: the outline of this page, what links to it, and its tasks.
        var task = new Task(java.util.UUID.randomUUID(), null, null, "Write the summary", "", null,
            TaskStatus.TODO, "Test", java.time.Instant.now(), 0);
        tracker.addTask(task);
        tracker.pages().linkTask(task.id(), one.id());
        app.openNote(one.id());
        Preview.layout(app);
        var labels = new java.util.ArrayList<String>();
        texts(app, labels);
        assert labels.contains("Outline") && labels.contains("Linked from") && labels.contains("Tasks")
            : "the sidebar shows the page's connections: " + labels;
        assert labels.contains("First") : "the page that links here is listed: " + labels;
        assert labels.contains("Write the summary") : "and so is the linked task: " + labels;

        // A narrow window leaves room to write; connections replace the explorer.
        app.setSize(900, 640);
        Preview.layout(app);
        assert !find(app, "pages.connections").isVisible();
        assert find(app, "pages.explorer").isVisible();
        assert find(app, "pages.documents").getWidth() >= 350 : "editor remains usable";
        Preview.button(app, "pages.sidebar").doClick();
        Preview.layout(app);
        assert find(app, "pages.connections").isVisible();
        assert !find(app, "pages.explorer").isVisible();
        assert find(app, "pages.documents").getWidth() >= 350 : "connections leave room to write";
        Preview.button(app, "pages.sidebar").doClick();
        Preview.layout(app);
        assert find(app, "pages.explorer").isVisible();
        app.setSize(1280, 900);
        Preview.layout(app);
        assert find(app, "pages.connections").isVisible();
        assert find(app, "pages.explorer").isVisible();

        // Reading view draws an embedded page inside the page that names it.
        var host = tracker.pages().createPage(null, "Host", "Before\n\n![[First]]\n\nAfter\n");
        app.openNote(host.id());
        Preview.button(app, "pages.mode").doClick();
        Preview.layout(app);
        var shown = new java.util.ArrayList<String>();
        texts(app, shown);
        assert shown.contains("First") : "the embedded page is named: " + shown;
        assert shown.stream().anyMatch(t -> t.contains("Before")) : "the page around the embed is still drawn";
        Preview.button(app, "pages.mode").doClick();

        // A page that embeds itself is drawn once, not for ever.
        var loop = tracker.pages().createPage(null, "Loop", "![[Loop]]\n");
        app.openNote(loop.id());
        Preview.button(app, "pages.mode").doClick();
        Preview.layout(app);
        Preview.button(app, "pages.mode").doClick();

        // Closing a tab leaves the others open.
        Preview.button(app, "pages.tab.close." + host.id()).doClick();
        assert find(app, "pages.tab." + host.id()) == null : "a closed page has no tab";
        assert find(app, "pages.tab." + one.id()) != null : "the others stay open";
    }

    static String editorText(YoruApp app) { return editor(app).getText(); }

    /** Every label, button caption and piece of drawn text on screen. */
    static void texts(Container root, java.util.List<String> into) {
        for (var child : root.getComponents()) {
            if (child instanceof JLabel l && l.getText() != null) into.add(l.getText());
            else if (child instanceof AbstractButton b && b.getText() != null) into.add(b.getText());
            else if (child instanceof javax.swing.text.JTextComponent t && t.getText() != null) into.add(t.getText());
            if (child instanceof Container nested) texts(nested, into);
        }
    }

    public static void main(String[] args) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var repo = new Memory(); var tracker = new Tracker(repo, Clock.systemUTC());
                    var one = tracker.pages().createPage(null, "First", "# First\n- [ ] Read\n");
                    var two = tracker.pages().createPage(null, "Second", "[[First]]\n");
                    var app = new YoruApp(tracker, repo); app.setSize(1280, 900);
                    app.openNote(one.id()); Preview.layout(app);
                    tracker.pages().renamePage(two.id(), "Renamed");
                    app.show("Pages");
                    var tree = (JTree)find(app, "pages.tree");
                    assert tree.getModel().getRoot().toString() != null;
                    boolean renamed = false;
                    for (int row = 0; row < tree.getRowCount(); row++)
                        if (tree.getPathForRow(row).getLastPathComponent().toString().contains("Renamed")) renamed = true;
                    assert renamed : "explorer refreshes after mutations";
                    tracker.pages().trash(java.util.List.of(two.id()), java.util.List.of()); app.show("Pages");
                    tracker.pages().restorePage(two.id()); app.show("Pages");
                    var pane = editor(app); pane.setCaretPosition(pane.getDocument().getLength()); pane.replaceSelection("Draft");
                    repo.fail = true; app.show("Tasks");
                    assert find(app,"pages.workspace") != null : "failed autosave must block navigation";
                    assert editor(app).getText().endsWith("Draft");
                    assert !tracker.pages().page(one.id()).body().endsWith("Draft");
                    repo.fail = false; app.openNote(two.id());
                    assert tracker.pages().page(one.id()).body().endsWith("Draft") : "switch flushes text";
                    app.openNote(one.id());
                    Preview.button(app,"pages.mode").doClick(); Preview.button(app,"pages.mode").doClick();
                    editor(app).getActionMap().get("yoru.undo").actionPerformed(null);
                    assert !editor(app).getText().endsWith("Draft") : "undo survives page and reading switches";
                    app.show("Today"); assert !tracker.pages().page(one.id()).body().endsWith("Draft");
                    workspace(app, tracker, one, two);
                    app.openNote(one.id());
                    pane = editor(app); pane.setCaretPosition(pane.getDocument().getLength()); pane.replaceSelection("Saved on close");
                    Preview.button(app,"Lock & close").doClick();
                    assert repo.state.notes().page(one.id()).orElseThrow().body().endsWith("Saved on close");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            System.out.println("PASS: Pages navigation, failed-save protection, tab undo, reading switches, close flush,\n      title renaming, tabs, back and forward, the connections sidebar and embedded pages");
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
