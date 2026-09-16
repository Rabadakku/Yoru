package dev.yoru.ui;

import java.util.List;
import java.util.prefs.Preferences;

/**
 * How large this computer draws Yoru's text (#31).
 *
 * Yoru sets every size itself, so the operating system's text size never
 * reaches it; this is the setting that does. It belongs to the computer rather
 * than the vault, as the recent-vault path does: a larger size answers a screen
 * and the eyes reading it, not a workspace, and keeping it out of the vault
 * leaves every vault readable by older versions.
 */
final class TextSize {
    private TextSize() { }

    /** The sizes offered, as percentages of the designed size. TextFitTest holds every page to each. */
    static final List<Integer> STEPS = List.of(100, 125, 150, 200);

    private static final String KEY = "text.size";

    private static Preferences preferences() { return Preferences.userRoot().node("dev/yoru/desktop"); }

    /** The size saved for this computer, or 100 when none is, or the saved one is no longer offered. */
    static int saved() {
        int percent = preferences().getInt(KEY, 100);
        return STEPS.contains(percent) ? percent : 100;
    }

    /** The size text is drawn at now. */
    static int current() { return Math.round(Theme.textScale * 100); }

    /** Draws text at {@code percent} in everything built from now on. */
    static void use(int percent) {
        if (!STEPS.contains(percent)) throw new IllegalArgumentException("Choose one of the offered text sizes.");
        Theme.textScale = percent / 100f;
    }

    /** Uses {@code percent}, and keeps it for this computer. */
    static void choose(int percent) {
        use(percent);
        preferences().putInt(KEY, percent);
    }
}
