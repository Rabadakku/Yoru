package dev.yoru.ui;

import javax.swing.*;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;
import static dev.yoru.ui.Theme.*;

/**
 * Which control a dialog gives the keyboard to.
 *
 * JOptionPane focuses the option a dialog opens on, and that option is always a
 * button, so every field in Yoru used to open unfocused: a password typed into
 * the unlock dialog went to the Unlock button, the field stayed empty, and the
 * dialog refused a password that had never reached it. That single behaviour is
 * what made passwords look broken, made the password a new vault does not need
 * look mandatory, and made a rename look like nothing had happened — so what is
 * pinned here is that the field a dialog asks somebody to fill in is the thing
 * holding the keyboard when it opens.
 */
public final class DialogFocusTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** The unlock dialog's shape: a name, a prompt, and the password field. */
    private static JPanel unlockForm(JPasswordField pass) {
        var form = stack();
        form.add(label("Main Vault", 20, CYAN));
        gap(form, 12);
        form.add(label("Password · 12 or more characters", 12, TEXT));
        form.add(pass);
        return form;
    }

    /** The new-vault dialog's shape: the password choice, then the fields. */
    private static JPanel createForm(JRadioButton protect, JPasswordField pass, JPasswordField repeat) {
        var form = stack();
        form.add(label("New Vault", 20, CYAN));
        form.add(protect);
        form.add(label("Password · 12 or more characters", 12, TEXT));
        form.add(pass);
        form.add(label("Repeat password", 12, TEXT));
        form.add(repeat);
        return form;
    }

    private static void theFieldIsWhatGetsTheKeyboard() {
        var pass = new JPasswordField(24);
        check(Dialogs.firstInput(unlockForm(pass)) == pass,
            "the unlock dialog offers its password field, not a label or a button");

        // The choice has a sensible default and the password is what somebody
        // has to type, so the first field wins over the first control.
        var protect = new JRadioButton("Require a password", true);
        var first = new JPasswordField(24);
        var second = new JPasswordField(24);
        check(Dialogs.firstInput(createForm(protect, first, second)) == first,
            "the new-vault dialog offers the first password field, not the choice");

        // A dialog that only says something keeps the keyboard on its button:
        // the wrapped message body is neither editable nor focusable.
        check(Dialogs.firstInput((java.awt.Container) bodyOf("Nothing to fill in here.")) == null
                || !(Dialogs.firstInput((java.awt.Container) bodyOf("x")) instanceof JTextField),
            "a dialog with nothing to fill in offers no field");

        var named = new JTextField(28);
        var panel = stack();
        panel.add(label("Name this vault.", 12, TEXT));
        panel.add(named);
        check(Dialogs.firstInput(panel) == named, "an input dialog offers its text field");

        var combo = new JComboBox<>(new String[] { "Main Vault", "Work" });
        var picker = stack();
        picker.add(label("Which vault?", 12, TEXT));
        picker.add(combo);
        check(Dialogs.firstInput(picker) == combo, "a chooser offers its list, so the keyboard can pick");

        // A field somebody cannot type into is not the field to focus.
        var disabled = new JPasswordField(24);
        disabled.setEnabled(false);
        var live = new JPasswordField(24);
        var mixed = stack();
        mixed.add(disabled);
        mixed.add(live);
        check(Dialogs.firstInput(mixed) == live, "a disabled field is skipped for one that can be typed into");
    }

    /** Wraps a plain message the way a dialog does, to prove it is not mistaken for a field. */
    private static Object bodyOf(String text) {
        var area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        var panel = stack();
        panel.add(area);
        return panel;
    }

    /**
     * The real thing, when there is a screen: a dialog opens, and the password
     * field — not the Unlock button — is what has the keyboard.
     */
    private static void theRealDialogFocusesItsField() throws Exception {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeAndWait(Theme::install);
        var pass = new JPasswordField(24);
        var holder = new AtomicReference<String>("no dialog");

        var driver = new Thread(() -> {
            try {
                JDialog dialog = null;
                for (int i = 0; i < 100 && dialog == null; i++) { Thread.sleep(50); dialog = visible(); }
                if (dialog == null) return;
                final JDialog open = dialog;
                for (int i = 0; i < 60 && open.getFocusOwner() == null; i++) Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> {
                    var owner = open.getFocusOwner();
                    holder.set(owner == null ? "nothing" : owner.getClass().getSimpleName());
                    open.setVisible(false);
                });
            } catch (Exception ignored) { }
        });
        driver.setDaemon(true);
        driver.start();

        Dialogs.confirm(null, unlockForm(pass), "Unlock vault", "Unlock");
        driver.join(12_000);
        check(holder.get().equals("JPasswordField"),
            "the open unlock dialog gives the keyboard to its password field, not its button (was " + holder.get() + ")");
    }

    private static JDialog visible() {
        for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isVisible()) return d;
        return null;
    }

    public static void main(String[] args) throws Exception {
        theFieldIsWhatGetsTheKeyboard();
        theRealDialogFocusesItsField();
        System.out.println("PASS: " + checks + " dialog focus checks (the field, not the button, holds the keyboard)"
            + (GraphicsEnvironment.isHeadless() ? " — the on-screen check needs a display and was skipped" : ""));
    }
}
