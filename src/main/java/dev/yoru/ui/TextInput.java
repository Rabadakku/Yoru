package dev.yoru.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.undo.*;

/**
 * What every text field in the app owes the person typing in it: the
 * platform's own shortcuts, undo, and a menu on a right click.
 *
 * Yoru draws itself with Swing's cross-platform look and feel, whose text
 * bindings are the ones Windows uses. On a Mac that left Cmd-C, Cmd-V, Cmd-X,
 * Cmd-A and Cmd-Z doing nothing at all, in every field in the app (#49) —
 * copy and paste, which nobody thinks of as a feature until they are missing.
 *
 * The bindings are added to the shared input maps, so they reach every text
 * component including the ones inside dialogs, and undo and the menu are
 * installed the first time a field is focused or right-clicked, so nothing has
 * to remember to ask for them.
 */
final class TextInput {
    private TextInput() { }

    /** Marks a component that already has its undo and its menu. */
    private static final String READY = "yoru.textInput";
    private static final String UNDO = "yoru.undo", REDO = "yoru.redo";
    /** How long a run of typing stays one undo step. */
    private static final long JOIN_MS = 700;
    private static boolean listening;

    /**
     * The key shortcuts are written with: Cmd on a Mac, Ctrl elsewhere — and
     * Ctrl with no screen at all, so the headless tests can ask for bindings.
     */
    static int menuKey() {
        return GraphicsEnvironment.isHeadless() ? InputEvent.CTRL_DOWN_MASK
            : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    static void install() {
        int menu = menuKey();
        shortcuts(menu);
        if (listening) return;
        listening = true;
        // Every text component gets its undo the first time it is used, rather
        // than every place that makes a field having to remember to ask.
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("permanentFocusOwner", event -> {
                if (event.getNewValue() instanceof JTextComponent field) support(field);
            });
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent mouse) || !mouse.isPopupTrigger()) return;
            if (!(mouse.getComponent() instanceof JTextComponent field) || !field.isShowing() || !field.isEnabled()) return;
            support(field);
            field.requestFocusInWindow();
            menu(field).show(field, mouse.getX(), mouse.getY());
            mouse.consume();
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    /**
     * The platform's shortcuts on every kind of text component.
     *
     * The maps are the ones the look and feel shares between components, so a
     * field made later carries these too.
     */
    static void shortcuts(int menu) {
        for (String kind : new String[]{"TextField", "PasswordField", "FormattedTextField", "TextArea", "TextPane", "EditorPane"}) {
            if (!(UIManager.get(kind + ".focusInputMap") instanceof InputMap map)) continue;
            put(map, KeyEvent.VK_C, menu, DefaultEditorKit.copyAction);
            put(map, KeyEvent.VK_V, menu, DefaultEditorKit.pasteAction);
            put(map, KeyEvent.VK_X, menu, DefaultEditorKit.cutAction);
            put(map, KeyEvent.VK_A, menu, DefaultEditorKit.selectAllAction);
            put(map, KeyEvent.VK_Z, menu, UNDO);
            put(map, KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK, REDO);
            put(map, KeyEvent.VK_Y, menu, REDO);
            if (menu != InputEvent.META_DOWN_MASK) continue;
            // The Mac's own text navigation, which the cross-platform look and
            // feel does not know: by line with Cmd, by word with Option.
            put(map, KeyEvent.VK_LEFT, menu, DefaultEditorKit.beginLineAction);
            put(map, KeyEvent.VK_RIGHT, menu, DefaultEditorKit.endLineAction);
            put(map, KeyEvent.VK_UP, menu, DefaultEditorKit.beginAction);
            put(map, KeyEvent.VK_DOWN, menu, DefaultEditorKit.endAction);
            put(map, KeyEvent.VK_LEFT, menu | InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionBeginLineAction);
            put(map, KeyEvent.VK_RIGHT, menu | InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionEndLineAction);
            put(map, KeyEvent.VK_UP, menu | InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionBeginAction);
            put(map, KeyEvent.VK_DOWN, menu | InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionEndAction);
            put(map, KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK, DefaultEditorKit.previousWordAction);
            put(map, KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK, DefaultEditorKit.nextWordAction);
            put(map, KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionPreviousWordAction);
            put(map, KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, DefaultEditorKit.selectionNextWordAction);
            put(map, KeyEvent.VK_BACK_SPACE, InputEvent.ALT_DOWN_MASK, DefaultEditorKit.deletePrevWordAction);
            put(map, KeyEvent.VK_BACK_SPACE, menu, "yoru.deleteLineStart");
        }
    }

    private static void put(InputMap map, int key, int modifiers, String action) {
        map.put(KeyStroke.getKeyStroke(key, modifiers), action);
    }

    /** Gives one component its undo history, its redo, and the actions the menu uses. */
    static void support(JTextComponent field) {
        if (field.getClientProperty(READY) != null) return;
        field.putClientProperty(READY, Boolean.TRUE);
        // A component that keeps its own history — the page editor, whose
        // restyling makes the document's own edits unsafe — keeps it.
        if (field.getActionMap().get(UNDO) != null) return;
        var undo = new UndoManager();
        undo.setLimit(500);
        long[] last = {0};
        CompoundEdit[] group = {null};
        Runnable close = () -> { if (group[0] != null) { group[0].end(); group[0] = null; } };
        field.getDocument().addUndoableEditListener(event -> {
            var edit = event.getEdit();
            // Styling a document — the page editor restyles as you type — is not
            // a change to the text, and must not become an undo step.
            if (edit instanceof AbstractDocument.DefaultDocumentEvent changed
                && changed.getType() == javax.swing.event.DocumentEvent.EventType.CHANGE) return;
            long now = System.currentTimeMillis();
            // A run of typing undoes as one step, not one letter at a time.
            if (group[0] == null || now - last[0] > JOIN_MS) {
                close.run();
                group[0] = new CompoundEdit();
                undo.addEdit(group[0]);
            }
            group[0].addEdit(edit);
            last[0] = now;
        });
        field.putClientProperty("yoru.canUndo", (java.util.function.BooleanSupplier) () -> {
            close.run(); return undo.canUndo();
        });
        field.putClientProperty("yoru.canRedo", (java.util.function.BooleanSupplier) undo::canRedo);
        field.getActionMap().put(UNDO, action(() -> { close.run(); if (undo.canUndo()) undo.undo(); else beep(); }));
        field.getActionMap().put(REDO, action(() -> { close.run(); if (undo.canRedo()) undo.redo(); else beep(); }));
        field.getActionMap().put("yoru.deleteLineStart", action(() -> {
            try {
                int caret = field.getCaretPosition();
                int line = field.getText(0, caret).lastIndexOf('\n') + 1;
                if (caret > line) field.getDocument().remove(line, caret - line);
            } catch (BadLocationException ignored) { }
        }));
    }

    /** Availability shared by the native Edit menu and each field's context menu. */
    static boolean available(JTextComponent field, String action) {
        if (field == null || !field.isEnabled()) return false;
        support(field);
        boolean selected = field.getSelectionStart() != field.getSelectionEnd();
        return switch (action) {
            case UNDO, REDO -> {
                Object check = field.getClientProperty(action.equals(UNDO) ? "yoru.canUndo" : "yoru.canRedo");
                yield field.isEditable() && check instanceof java.util.function.BooleanSupplier ready && ready.getAsBoolean();
            }
            case DefaultEditorKit.cutAction -> field.isEditable() && selected && !(field instanceof JPasswordField);
            case DefaultEditorKit.copyAction -> selected && !(field instanceof JPasswordField);
            case DefaultEditorKit.pasteAction -> field.isEditable();
            case DefaultEditorKit.selectAllAction -> field.getDocument().getLength() > 0;
            default -> false;
        };
    }

    private static void beep() { Toolkit.getDefaultToolkit().beep(); }

    private static Action action(Runnable run) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { run.run(); }
        };
    }

    /**
     * The right-click menu: what the keyboard can do, for the hand on the mouse.
     *
     * A password field lends its text to nobody, so it can be pasted into but
     * never copied or cut out of — which is what Swing itself does.
     */
    static JPopupMenu menu(JTextComponent field) {
        boolean secret = field instanceof JPasswordField;
        boolean editable = field.isEditable();
        boolean selection = field.getSelectionStart() != field.getSelectionEnd();
        boolean any = field.getDocument().getLength() > 0;
        var menu = Menus.popup();
        menu.add(Menus.item("Undo", "text.undo", available(field, UNDO), () -> fire(field, UNDO), "Nothing to undo here"));
        menu.add(Menus.item("Redo", "text.redo", available(field, REDO), () -> fire(field, REDO), "Nothing to redo here"));
        menu.addSeparator();
        menu.add(Menus.item("Cut", "text.cut", editable && selection && !secret, field::cut,
            secret ? "A password is not copied out" : "Select some text first"));
        menu.add(Menus.item("Copy", "text.copy", selection && !secret, field::copy,
            secret ? "A password is not copied out" : "Select some text first"));
        menu.add(Menus.item("Paste", "text.paste", editable, field::paste, "This text cannot be changed"));
        menu.addSeparator();
        menu.add(Menus.item("Select all", "text.selectAll", any, field::selectAll, "There is nothing here yet"));
        return menu;
    }

    private static void fire(JTextComponent field, String name) {
        var action = field.getActionMap().get(name);
        if (action != null) action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, name));
    }
}
