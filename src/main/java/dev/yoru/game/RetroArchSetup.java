package dev.yoru.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reuses whatever RetroArch is already set up on this machine.
 *
 * Yoru is a libretro frontend, not a RetroArch launcher — PRODUCT-GOALS is
 * explicit that a button opening another application is not the experience
 * asked for. But RetroArch and Yoru want exactly the same three things: a core,
 * a system directory holding the BIOS, and the core's own options. There is no
 * reason to make someone configure them twice, and every reason not to: a game
 * that runs differently in Yoru than in RetroArch is a game whose behaviour
 * nobody can reason about.
 *
 * So the core is RetroArch's core, the BIOS is RetroArch's BIOS, and the
 * options are the ones RetroArch last wrote. None of those files are copied,
 * moved or modified; they are read where they sit.
 */
public final class RetroArchSetup {

    private RetroArchSetup() { }

    /** Where RetroArch keeps cores, config and assets on this platform. */
    public static Path configRoot() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) return Path.of(home, "Library/Application Support/RetroArch");
        if (os.contains("win")) return Path.of(home, "AppData/Roaming/RetroArch");
        return Path.of(home, ".config/retroarch");
    }

    /**
     * Where RetroArch keeps the BIOS and battery saves.
     *
     * On macOS these live under Documents rather than beside the config, which
     * is why this is a separate lookup rather than a subdirectory of the above.
     */
    public static Path dataRoot() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) return Path.of(home, "Documents/RetroArch");
        return configRoot();
    }

    /** The system directory, where a core looks for its BIOS. */
    public static Path systemDirectory() { return dataRoot().resolve("system"); }

    /** Where RetroArch writes battery saves, in a folder per core when it sorts them. */
    public static Path saveDirectory() { return dataRoot().resolve("saves"); }

    /** True when a GBA BIOS is sitting where mGBA would look for it. */
    public static boolean hasGbaBios() {
        return Files.isRegularFile(systemDirectory().resolve("gba_bios.bin"));
    }

    /**
     * The core options RetroArch last wrote for a core, if any.
     *
     * The format is one {@code key = "value"} per line. Anything that does not
     * parse is skipped rather than guessed at — a malformed line should cost
     * one setting, not the whole file.
     */
    public static Map<String,String> options(String coreName) {
        var file = configRoot().resolve("config").resolve(coreName).resolve(coreName + ".opt");
        var out = new LinkedHashMap<String,String>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);
                if (!key.isEmpty() && !value.isEmpty()) out.put(key, value);
            }
        } catch (IOException ignored) {
            // A setup that cannot be read is the same as one that is not there:
            // the core keeps its own defaults and the game still runs.
        }
        return out;
    }

    /** A short description of what was found, for the Game tab to show. */
    public static String describe(String coreName) {
        int settings = options(coreName).size();
        var parts = new StringBuilder();
        parts.append(settings > 0 ? settings + " core options from RetroArch" : "core defaults");
        parts.append(hasGbaBios() ? "  ·  RetroArch's GBA BIOS" : "  ·  no BIOS (mGBA will emulate one)");
        return parts.toString();
    }
}
