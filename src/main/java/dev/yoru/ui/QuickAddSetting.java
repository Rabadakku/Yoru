package dev.yoru.ui;

import java.util.prefs.Preferences;

/**
 * Whether a new task's line is read for dates, tags, lists, priority and
 * repeats (#74). On by default; kept per computer, as the text size is, since
 * it is how this person types rather than anything about the vault.
 */
final class QuickAddSetting {
    private QuickAddSetting() { }

    private static final String KEY = "quickAdd.reads";

    private static Preferences preferences() { return Preferences.userRoot().node("dev/yoru/desktop"); }

    static boolean enabled() { return preferences().getBoolean(KEY, true); }

    static void enabled(boolean on) { preferences().putBoolean(KEY, on); }
}
