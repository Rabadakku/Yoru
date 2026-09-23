package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** Optional per-computer macOS appearance; never changes a vault's chosen theme. */
final class SystemAppearance {
    private SystemAppearance() { }
    record Style(boolean dark, int accent) {
        ThemeId theme() { return dark ? ThemeId.MIDNIGHT : ThemeId.LINEN; }
        Color colour() {
            return new Color(switch (accent) {
                case -1 -> dark ? 0xBBBBBB : 0x606060;
                case 0 -> dark ? 0xFF9692 : 0xB62D32;
                case 1 -> dark ? 0xFFB56B : 0x995009;
                case 2 -> dark ? 0xEBD16F : 0x80600A;
                case 3 -> dark ? 0x86D799 : 0x286B39;
                case 5 -> dark ? 0xCAA5F2 : 0x7840AC;
                case 6 -> dark ? 0xF5A0C8 : 0xAE356E;
                default -> dark ? 0x8BBEFF : 0x1763BA;
            });
        }
    }
    static boolean enabled() { return DesktopChrome.mac() && Preferences.userRoot().node("dev/yoru/desktop").getBoolean("appearance.system",false); }
    static void choose(boolean enabled) { Preferences.userRoot().node("dev/yoru/desktop").putBoolean("appearance.system",enabled); }
    static Style read() {
        String mode = preference("AppleInterfaceStyle"), accent = preference("AppleAccentColor");
        int number = 4;
        try { number = Integer.parseInt(accent); } catch (NumberFormatException ignored) { }
        return new Style(mode.equalsIgnoreCase("Dark"),number);
    }
    private static String preference(String name) {
        try {
            var process = new ProcessBuilder("/usr/bin/defaults","read","-g",name).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(1,TimeUnit.SECONDS)) { process.destroyForcibly(); return ""; }
            if (process.exitValue() != 0) return "";
            return new String(process.getInputStream().readNBytes(100),StandardCharsets.UTF_8).strip();
        } catch (Exception ignored) { return ""; }
    }
}
