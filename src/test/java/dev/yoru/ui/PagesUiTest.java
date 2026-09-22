package dev.yoru.ui;

import dev.yoru.application.*;
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
    static JTextPane editor(YoruApp app) {
        var tabs = (JTabbedPane)find(app, "pages.tabs");
        return (JTextPane)find((Container)tabs.getSelectedComponent(), "page.editor");
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
                    Preview.button(app,"Read / Edit").doClick(); Preview.button(app,"Read / Edit").doClick();
                    editor(app).getActionMap().get("yoru.undo").actionPerformed(null);
                    assert !editor(app).getText().endsWith("Draft") : "undo survives page and reading switches";
                    app.show("Today"); assert !tracker.pages().page(one.id()).body().endsWith("Draft");
                    app.openNote(one.id());
                    pane = editor(app); pane.setCaretPosition(pane.getDocument().getLength()); pane.replaceSelection("Saved on close");
                    Preview.button(app,"Lock & close").doClick();
                    assert repo.state.notes().page(one.id()).orElseThrow().body().endsWith("Saved on close");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            System.out.println("PASS: Pages navigation, failed-save protection, tab undo, reading switches and close flush");
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
