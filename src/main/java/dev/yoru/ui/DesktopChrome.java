package dev.yoru.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Platform window chrome and standard keyboard menus around the same workspace. */
final class DesktopChrome {
    private DesktopChrome() { }
    static boolean mac() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"); }
    static void prepare() {
        if (mac()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "Yoru");
        }
    }
    static void install(JFrame frame, YoruApp app) {
        if (mac()) {
            frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
            // Keep controls below the native traffic lights without drawing fake ones.
            frame.getRootPane().setBorder(BorderFactory.createEmptyBorder(28, 0, 0, 0));
        }
        frame.setTitle("Yoru");
        frame.setJMenuBar(menus(app, frame));
        if (mac() && Desktop.isDesktopSupported()) {
            var desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler(e ->
                SwingUtilities.invokeLater(() -> Dialogs.info(app,"Yoru","Your local workspace for time, tasks, habits and notes.")));
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler(e ->
                SwingUtilities.invokeLater(() -> app.show("Settings")));
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler((e,response) ->
                SwingUtilities.invokeLater(() -> { if (app.closeRequested()) response.performQuit(); else response.cancelQuit(); }));
        }
    }
    static JMenuBar menus(YoruApp app, JFrame frame) {
        int key = TextInput.menuKey();
        var bar = new JMenuBar(); bar.setName("workspace.menu");
        var file = new JMenu("File");
        file.add(item("New page", KeyStroke.getKeyStroke(KeyEvent.VK_N,key), app::newNote));
        file.add(item("Command palette…", KeyStroke.getKeyStroke(KeyEvent.VK_K,key), () -> CommandPalette.open(app)));
        file.addSeparator();
        file.add(item("Close vault", KeyStroke.getKeyStroke(KeyEvent.VK_W,key|InputEvent.SHIFT_DOWN_MASK), app::closeVault));
        bar.add(file);
        var edit = new JMenu("Edit");
        edit.add(textItem("Undo", "yoru.undo", KeyEvent.VK_Z, key));
        edit.add(textItem("Redo", "yoru.redo", KeyEvent.VK_Z, key|InputEvent.SHIFT_DOWN_MASK));
        edit.addSeparator();
        edit.add(textItem("Cut", DefaultEditorKit.cutAction, KeyEvent.VK_X, key));
        edit.add(textItem("Copy", DefaultEditorKit.copyAction, KeyEvent.VK_C, key));
        edit.add(textItem("Paste", DefaultEditorKit.pasteAction, KeyEvent.VK_V, key));
        edit.add(textItem("Select All", DefaultEditorKit.selectAllAction, KeyEvent.VK_A, key));
        edit.addMenuListener(new MenuListener() {
            public void menuSelected(MenuEvent e) {
                var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
                updateEditMenu(edit, focus instanceof JTextComponent field ? field : null);
            }
            public void menuDeselected(MenuEvent e) { }
            public void menuCanceled(MenuEvent e) { }
        });
        updateEditMenu(edit, null);
        bar.add(edit);
        var view = new JMenu("View");
        view.add(item("Toggle sidebar", KeyStroke.getKeyStroke(KeyEvent.VK_S,key|InputEvent.SHIFT_DOWN_MASK), app::toggleSidebar));
        view.addSeparator();
        int i = 1;
        for (String page : new String[]{"Today","Tasks","Pages","Habits","Schedule","Data","Settings"}) {
            view.add(item(page, KeyStroke.getKeyStroke(KeyEvent.VK_0+i++,key), () -> app.show(page)));
        }
        bar.add(view);
        var window = new JMenu("Window");
        window.add(item("Minimize", KeyStroke.getKeyStroke(KeyEvent.VK_M,key), () -> { if (frame != null) frame.setState(Frame.ICONIFIED); }));
        bar.add(window);
        var help = new JMenu("Help");
        help.add(item("About Yoru", null, () -> Dialogs.info(app, "Yoru", "Your local workspace for time, tasks, habits and notes.")));
        bar.add(help);
        return bar;
    }
    private static JMenuItem item(String text, KeyStroke shortcut, Runnable action) {
        var item = new JMenuItem(text); item.setAccelerator(shortcut);
        item.addActionListener(e -> action.run()); return item;
    }
    private static JMenuItem textItem(String text, String action, int code, int mask) {
        var item = item(text, KeyStroke.getKeyStroke(code,mask), () -> {
            var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
            if (!(focus instanceof JTextComponent field) || !TextInput.available(field, action)) return;
            TextInput.support(field);
            var command = field.getActionMap().get(action);
            if (command != null) command.actionPerformed(new ActionEvent(field,ActionEvent.ACTION_PERFORMED,action));
        });
        item.putClientProperty("text.action", action);
        return item;
    }
    static void updateEditMenu(JMenu menu, JTextComponent field) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            var item = menu.getItem(i);
            if (item != null && item.getClientProperty("text.action") instanceof String action)
                item.setEnabled(TextInput.available(field, action));
        }
    }
}
