package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import java.util.prefs.Preferences;
import javax.swing.*;

/**
 * The text size is this computer's, one of the offered steps, and reaches every
 * face Theme hands out (#31). Settings offers each step and marks the one in
 * use; choosing one keeps it. Runs under the suite's in-memory preferences.
 */
public final class TextSizeTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        var failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) {
            failure[0].printStackTrace();
            // The window built here leaves Swing's threads running.
            System.exit(1);
        }
        System.out.println("PASS: " + checks + " text size checks (default, saved, refused, faces, Settings)");
        System.exit(0);
    }

    private static void run() throws Exception {
        var saved = Preferences.userRoot().node("dev/yoru/desktop");
        saved.remove("text.size");
        check(TextSize.saved() == 100, "with nothing saved, text is drawn at its designed size");
        check(TextSize.STEPS.equals(java.util.List.of(100, 125, 150, 200)), "Settings offers 100, 125, 150 and 200%");

        TextSize.choose(150);
        check(TextSize.saved() == 150 && TextSize.current() == 150, "a chosen size is used and kept");
        check(Theme.labelFont().getSize() == Math.round(Theme.TYPE_LABEL * 1.5f), "a control's face grows with it");
        check(Theme.timerFont().getSize() == Math.round(Theme.TYPE_TIMER * 1.5f), "so does the timer's");
        Theme.apply(ThemeId.MIDNIGHT);
        check(((java.awt.Font) UIManager.get("Button.font")).getSize() == Theme.labelFont().getSize(),
            "the look-and-feel's faces are installed at the new size");

        saved.putInt("text.size", 175);
        check(TextSize.saved() == 100, "a saved size that is no longer offered falls back to the designed size");
        boolean refused = false;
        try { TextSize.use(175); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "only an offered size can be used");

        TextSize.choose(100);
        Theme.apply(ThemeId.MIDNIGHT);
        var app = Preview.trackerApp(ThemeId.MIDNIGHT, 1280, 900);
        Preview.button(app, "Settings").doClick();
        Preview.layout(app);
        for (int step : TextSize.STEPS) {
            var pick = named(app, "textSize." + step);
            check(pick != null && pick.getText().equals(step + "%"), "Settings offers " + step + "%");
            check(pick.getBackground().equals(Theme.CYAN) == (step == 100), step + "% is marked only when it is in use");
        }
        named(app, "textSize.125").doClick();
        check(TextSize.saved() == 125 && TextSize.current() == 125, "choosing 125% in Settings keeps it for this computer");
        TextSize.use(100);
    }

    private static JButton named(java.awt.Container root, String name) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton b && name.equals(b.getName())) return b;
            if (child instanceof java.awt.Container nested) {
                var found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
