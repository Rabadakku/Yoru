package dev.yoru.ui;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;

/**
 * Copy, paste and the rest of the platform's text keys reach every kind of
 * field, and every field can undo (#49).
 *
 * The cross-platform look and feel binds these to Ctrl, so on a Mac the whole
 * app had no copy, no paste and no undo. The bindings are checked for the
 * platform's own menu key, whichever that is here, and the Mac's line and word
 * navigation is checked as if the menu key were Cmd.
 */
public final class TextInputTest {
    private static int checks;

    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(TextInputTest::run);
        System.out.println("PASS: " + checks + " text input checks (shortcuts on every field, undo, redo, the menu)");
        System.exit(0);
    }

    private static void run() {
        Theme.install();
        int menu = TextInput.menuKey();

        // Every kind of text component carries the platform's own shortcuts.
        for (String kind : new String[]{"TextField", "PasswordField", "FormattedTextField", "TextArea", "TextPane", "EditorPane"}) {
            var map = (InputMap) UIManager.get(kind + ".focusInputMap");
            check(map != null, kind + " has an input map");
            check(DefaultEditorKit.copyAction.equals(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu))), kind + " copies with the menu key");
            check(DefaultEditorKit.pasteAction.equals(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_V, menu))), kind + " pastes with the menu key");
            check(DefaultEditorKit.cutAction.equals(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_X, menu))), kind + " cuts with the menu key");
            check(DefaultEditorKit.selectAllAction.equals(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_A, menu))), kind + " selects all with the menu key");
            check("yoru.undo".equals(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu))), kind + " undoes with the menu key");
            check("yoru.redo".equals(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK))), kind + " redoes with shift");
        }

        // The Mac's own navigation, asked for as if this were a Mac.
        TextInput.shortcuts(InputEvent.META_DOWN_MASK);
        var field = (InputMap) UIManager.get("TextField.focusInputMap");
        check(DefaultEditorKit.beginLineAction.equals(field.get(
            KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.META_DOWN_MASK))), "Cmd-left goes to the start of the line");
        check(DefaultEditorKit.nextWordAction.equals(field.get(
            KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK))), "Option-right moves by word");
        check(DefaultEditorKit.deletePrevWordAction.equals(field.get(
            KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, InputEvent.ALT_DOWN_MASK))), "Option-delete removes a word");
        TextInput.shortcuts(menu);

        // Undo and redo, on a field that never asked for them.
        var typed = new JTextField();
        TextInput.support(typed);
        typed.setText("Chapter one");
        check(typed.getActionMap().get("yoru.undo") != null, "a field has undo");
        typed.getActionMap().get("yoru.undo").actionPerformed(null);
        check(typed.getText().isEmpty(), "undo takes back what was typed, got \"" + typed.getText() + "\"");
        typed.getActionMap().get("yoru.redo").actionPerformed(null);
        check(typed.getText().equals("Chapter one"), "redo puts it back, got \"" + typed.getText() + "\"");

        // Deleting to the start of the line, which the Mac does with Cmd-delete.
        typed.setCaretPosition(typed.getText().length());
        typed.getActionMap().get("yoru.deleteLineStart").actionPerformed(null);
        check(typed.getText().isEmpty(), "Cmd-delete clears back to the line's start");

        // The editor keeps its own history rather than being given another.
        var editor = new PageEditor(new PageEditor.Host() {
            public boolean resolves(String target) { return false; }
            public void follow(dev.yoru.pages.Markdown.Span link, boolean elsewhere) { }
            public java.util.List<PageEditor.Choice> linkChoices(String typed) { return java.util.List.of(); }
            public boolean save(String text) { return true; }
            public void parsed(dev.yoru.pages.Markdown.Doc doc) { }
        });
        var own = editor.pane().getActionMap().get("yoru.undo");
        TextInput.support(editor.pane());
        check(editor.pane().getActionMap().get("yoru.undo") == own, "the page editor keeps the undo it built for itself");
        check(!TextInput.available(editor.pane(), "yoru.undo"), "a fresh page has no undo command");
        editor.pane().setText("A draft");
        check(TextInput.available(editor.pane(), "yoru.undo"), "the native menu sees page editor history");
        own.actionPerformed(null);
        check(TextInput.available(editor.pane(), "yoru.redo"), "the native menu sees page editor redo");
        editor.stop();

        // The right-click menu offers what the keyboard can do, and a password
        // field lends its text to nobody.
        var menuFor = TextInput.menu(typed);
        var names = new java.util.ArrayList<String>();
        for (var item : menuFor.getComponents()) if (item instanceof JMenuItem entry) names.add(entry.getName());
        check(names.equals(java.util.List.of("text.undo", "text.redo", "text.cut", "text.copy", "text.paste", "text.selectAll")),
            "the menu offers undo, redo, cut, copy, paste and select all: " + names);
        var secret = new JPasswordField("hunter2");
        secret.selectAll();
        var secretMenu = TextInput.menu(secret);
        for (var item : secretMenu.getComponents()) {
            if (!(item instanceof JMenuItem entry)) continue;
            if ("text.copy".equals(entry.getName()) || "text.cut".equals(entry.getName()))
                check(!entry.isEnabled(), "a password is never copied out (" + entry.getName() + ")");
            if ("text.paste".equals(entry.getName())) check(entry.isEnabled(), "but a password can be pasted in");
        }
    }
}
