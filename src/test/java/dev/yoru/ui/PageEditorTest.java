package dev.yoru.ui;

import dev.yoru.pages.Markdown;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

public final class PageEditorTest {
    public static void main(String[] args) throws Exception {
        try { run(); System.out.println("PASS: editor save failures, retry, styling-safe undo, smart lists and large text"); }
        catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
    static void run() throws Exception {
        var saved = new AtomicReference<String>(); var fail = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            var editor = new PageEditor(new PageEditor.Host() {
                public boolean resolves(String t) { return false; }
                public void follow(Markdown.Span s, boolean elsewhere) { }
                public List<PageEditor.Choice> linkChoices(String t) { return List.of(); }
                public boolean save(String t) { if (fail.get()) return false; saved.set(t); return true; }
                public void parsed(Markdown.Doc doc) { }
            });
            editor.load("# Notes\n");
            editor.pane().setCaretPosition(editor.text().length()); editor.pane().replaceSelection("New text");
            fail.set(true); assert !editor.flush(); assert editor.dirty(); assert saved.get() == null;
            fail.set(false); assert editor.flush(); assert !editor.dirty(); assert saved.get().endsWith("New text");
            editor.pane().getActionMap().get("yoru.undo").actionPerformed(null);
            assert editor.text().equals("# Notes\n") : editor.text();
            editor.pane().getActionMap().get("yoru.redo").actionPerformed(null);
            assert editor.text().endsWith("New text");
            editor.load("- [ ] Read"); editor.pane().setCaretPosition(editor.text().length());
            editor.pane().getActionMap().get("yoru.newline").actionPerformed(null);
            assert editor.text().equals("- [ ] Read\n- [ ] ") : editor.text();
            editor.pane().getActionMap().get("yoru.newline").actionPerformed(null);
            assert editor.text().equals("- [ ] Read\n") : editor.text();
            editor.load("İstanbul target"); editor.pane().setCaretPosition(0); editor.find();
            ((JTextField)PagesUiTest.find(editor, "page.find")).setText("target");
            assert editor.pane().getSelectedText().equals("target") : "Unicode find selects the original offsets";
            ((JTextField)PagesUiTest.find(editor, "page.replace")).setText("result");
            Preview.button(editor, "Replace").doClick();
            assert editor.text().equals("İstanbul result");
            editor.pane().getActionMap().get("yoru.undo").actionPerformed(null);
            assert editor.text().equals("İstanbul target") : "replacement is undoable";
            long start = System.nanoTime();
            String large = "A paragraph with **emphasis** and [[links]].\n\n".repeat(1200);
            editor.load(large); assert editor.text().equals(large);
            assert System.nanoTime() - start < 10_000_000_000L : "Large page styling did not finish promptly";
            editor.stop();
        });
    }
}
