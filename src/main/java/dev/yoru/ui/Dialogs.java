package dev.yoru.ui;
import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import static dev.yoru.ui.Theme.*;

/**
 * Every dialog in Yoru goes through here.
 *
 * Two reasons this exists rather than calling JOptionPane directly. Swing's
 * ERROR_MESSAGE and friends carry stock Java mascot artwork, which showed up in
 * screenshots of the app; every method below passes PLAIN_MESSAGE with a null
 * icon so it cannot appear. And the three page classes each grew their own
 * near-identical error() helper, so the wording and titles had already drifted.
 *
 * The option that holds focus when a dialog opens is the one Return fires, so
 * which option that is decides whether a stray keypress is harmless. Destructive
 * prompts therefore open on their safe choice; see {@link #defaultOption}.
 */
final class Dialogs {
    private Dialogs() { }

    /** Wraps plain strings so long messages do not stretch a dialog off-screen. */
    private static Object body(Object message) {
        if (!(message instanceof String text)) return message;
        var area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(proseFont());
        area.setForeground(TEXT);
        area.setColumns(Math.min(52, Math.max(24, text.length())));
        area.setSize(area.getPreferredSize());
        return area;
    }

    /**
     * Opens the dialog and returns the index of the chosen option, or
     * CLOSED_OPTION. The option handed over as the initial value is the one
     * JOptionPane gives the keyboard to.
     */
    private static int show(Component parent, Object message, String title, String[] options, boolean destructive) {
        Object content = body(message);
        if (content instanceof Container filled) giveKeyboardTo(filled, destructive);
        return JOptionPane.showOptionDialog(parent, content, title,
            JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options,
            focusedOption(options, destructive));
    }

    /**
     * Gives the keyboard to the first thing a dialog asks somebody to fill in.
     *
     * JOptionPane focuses the option it opens on, and that option is always a
     * button, so before this every field in Yoru opened unfocused: a password
     * typed into the unlock dialog went to the Unlock button, the field stayed
     * empty, and the dialog then refused a password that had never reached it.
     * That one behaviour is what made passwords look broken, made the password
     * a new vault did not need look mandatory, and made a rename look like
     * nothing had happened.
     *
     * The focus is queued rather than requested here because JOptionPane selects
     * its own initial value as the dialog opens, which would otherwise take the
     * keyboard straight back.
     */
    private static void giveKeyboardTo(Container content, boolean destructive) {
        JComponent field = firstInput(content);
        if (field == null) return;
        field.addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent event) {
                field.removeAncestorListener(this);
                SwingUtilities.invokeLater(field::requestFocusInWindow);
            }
            @Override public void ancestorRemoved(AncestorEvent event) { }
            @Override public void ancestorMoved(AncestorEvent event) { }
        });
        // Enter finishes a dialog from inside its field, which is what the
        // focused button used to be for — but never on a destructive one, where
        // the whole point of the focused option is that Enter cannot fire the
        // action. A field outlives its dialog when the caller loops on a bad
        // password, so the listener is fitted once rather than once per attempt.
        if (destructive || !(field instanceof JTextField typed)) return;
        if (typed.getClientProperty(ENTER_CONFIRMS) != null) return;
        typed.putClientProperty(ENTER_CONFIRMS, Boolean.TRUE);
        typed.addActionListener(event -> {
            var pane = (JOptionPane) SwingUtilities.getAncestorOfClass(JOptionPane.class, typed);
            if (pane == null) return;
            Object[] shown = pane.getOptions();
            if (shown == null || shown.length == 0) return;
            // Asked of the dialog that is actually open rather than the one this
            // listener was fitted in: a field outlives its dialog, and Enter
            // confirming is only ever right where the dialog opened on its
            // confirming option. A destructive dialog opens on its safe one.
            if (!shown[0].equals(pane.getInitialValue())) return;
            pane.setValue(shown[0]);
        });
    }

    private static final String ENTER_CONFIRMS = "yoru.enterConfirms";

    /**
     * The first control somebody is meant to fill in, in the order they are
     * shown. The wrapped message body is neither editable nor focusable, so a
     * dialog that only says something still opens on its button.
     */
    static JComponent firstInput(Container content) {
        for (Component child : content.getComponents()) {
            if (child instanceof JTextComponent field
                && field.isEnabled() && field.isEditable() && field.isFocusable()) return field;
            if (child instanceof JComboBox<?> combo && combo.isEnabled() && combo.isFocusable()) return combo;
            if (child instanceof Container inner) {
                var found = firstInput(inner);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The labels that mean "nothing happens". A dialog's safe option goes last by convention. */
    private static final java.util.List<String> SAFE =
        java.util.List.of("Cancel", "Keep", "Keep it", "Later", "No");

    /**
     * Index of the option that opens with the keyboard.
     *
     * For a destructive dialog that is the safe option, never the action: Enter
     * fires what holds focus, so opening on Delete is what turns a misplaced
     * Return into lost data. A plain confirmation keeps Enter on its confirming
     * action, which is what makes Enter useful at all.
     */
    static int defaultOption(String[] options, boolean destructive) {
        if (!destructive) return 0;
        for (int i = 0; i < options.length; i++) if (SAFE.contains(options[i])) return i;
        return options.length - 1;
    }

    /** The option object JOptionPane focuses when the dialog opens. */
    static String focusedOption(String[] options, boolean destructive) {
        return options[defaultOption(options, destructive)];
    }

    /**
     * True only for the confirming option, which is always the first. Read
     * together with {@link #defaultOption}: a destructive dialog cannot return
     * this index from Return alone.
     */
    static boolean confirmedBy(int picked) { return picked == 0; }

    static void error(Component parent, String message) { error(parent, "Yoru couldn't do that", message); }

    static void error(Component parent, String title, String message) {
        var panel = stack();
        panel.add(label("!  " + title.toUpperCase(), TYPE_CAPTION, DANGER));
        gap(panel, SPACE_MD);
        panel.add((Component) body(message == null || message.isBlank() ? "Something went wrong." : message));
        show(parent, panel, title, new String[]{"OK"}, false);
    }

    static void info(Component parent, String message) { info(parent, "Yoru", message); }

    static void info(Component parent, String title, Object message) {
        show(parent, message, title, new String[]{"OK"}, false);
    }

    static boolean confirm(Component parent, Object message, String title) {
        return confirm(parent, message, title, "OK");
    }

    /** True when the user picks the confirming option; false on cancel or close. */
    static boolean confirm(Component parent, Object message, String title, String confirmLabel) {
        return confirmedBy(show(parent, message, title, new String[]{confirmLabel, "Cancel"}, false));
    }

    /** Index of the chosen option, or -1 if dismissed. */
    static int choose(Component parent, Object message, String title, String... options) {
        int picked = show(parent, message, title, options, false);
        return picked == JOptionPane.CLOSED_OPTION ? -1 : picked;
    }

    /** Free text, or null if dismissed. */
    static String input(Component parent, String prompt, String title) {
        return input(parent,prompt,title,"");
    }

    static String input(Component parent, String prompt, String title,String initial) {
        var field = new JTextField(28);
        field.setText(initial);field.selectAll();
        styleInput(field);
        var panel = stack();
        panel.add(label(prompt, TYPE_LABEL, TEXT));
        gap(panel, SPACE_SM);
        panel.add(field);
        return confirm(parent, panel, title, "Continue") ? field.getText() : null;
    }

    /** One of the supplied values, or null if dismissed. */
    static <T> T select(Component parent, String prompt, String title, java.util.List<T> options) {
        var combo = plainCombo(new JComboBox<>(new Vector<>(options)));
        var panel = stack();
        panel.add(label(prompt, TYPE_LABEL, TEXT));
        gap(panel, SPACE_SM);
        panel.add(combo);
        return confirm(parent, panel, title, "Continue") ? options.get(combo.getSelectedIndex()) : null;
    }

    private static final boolean MAC =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

    /**
     * A file to open, or null if dismissed; with no extensions, any file.
     *
     * macOS gets its own file panel, since Swing's looks foreign there.
     */
    static java.nio.file.Path chooseFile(Component parent, String title, String description, String... extensions) {
        if (MAC) {
            var dialog = fileDialog(parent, title, FileDialog.LOAD);
            if (extensions.length > 0) dialog.setFilenameFilter((folder, name) -> hasExtension(name, extensions));
            dialog.setVisible(true);
            return dialog.getFile() == null ? null : java.nio.file.Path.of(dialog.getDirectory(), dialog.getFile());
        }
        var chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        if (extensions.length > 0)
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(description, extensions));
        return chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    /** Where to write a file, or null if dismissed. Asks before replacing one that is there. */
    static java.nio.file.Path saveFile(Component parent, String title, String suggestedName) {
        if (MAC) {
            // The macOS panel asks about replacing an existing file itself.
            var dialog = fileDialog(parent, title, FileDialog.SAVE);
            dialog.setFile(suggestedName);
            dialog.setVisible(true);
            return dialog.getFile() == null ? null : java.nio.file.Path.of(dialog.getDirectory(), dialog.getFile());
        }
        var chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(suggestedName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        var file = chooser.getSelectedFile().toPath();
        // Replacing a file destroys the one that is there, so it is asked as a
        // destructive question: it opens on Cancel and cannot be answered with
        // a stray Return.
        if (java.nio.file.Files.exists(file)
                && !confirmDestructive(parent, "Replace " + file.getFileName() + "?", "Replace", "Replace")) return null;
        return file;
    }

    private static FileDialog fileDialog(Component parent, String title, int mode) {
        var window = parent instanceof Window own ? own : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        return window instanceof Dialog dialog ? new FileDialog(dialog, title, mode)
            : new FileDialog(window instanceof Frame frame ? frame : null, title, mode);
    }

    private static boolean hasExtension(String name, String... extensions) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (var extension : extensions) if (lower.endsWith("." + extension.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    /** Kept separate from confirm so destructive actions read differently. */
    static boolean confirmDestructive(Component parent, Object message, String title, String confirmLabel) {
        var panel = stack();
        panel.add(label("!  THIS CANNOT BE UNDONE", TYPE_CAPTION, DANGER));
        gap(panel, SPACE_MD);
        panel.add((Component) body(message));
        return confirmedBy(show(parent, panel, title, new String[]{confirmLabel, "Cancel"}, true));
    }

    private static final class Vector<T> extends java.util.Vector<T> {
        Vector(java.util.List<T> values) { super(values); }
    }
}
