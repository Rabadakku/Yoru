package dev.yoru.ui;

import dev.yoru.game.LibretroCore;
import dev.yoru.game.RetroArchSetup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Where this computer keeps the emulator core and the game file.
 *
 * Facts about the machine, not the workspace: they live in Preferences rather
 * than the vault, so moving a vault to another computer never carries a path
 * that means nothing there. Neither file is ever copied anywhere.
 *
 * A system property overrides each — yoru.game.core and yoru.game.rom — which
 * is how someone runs Yoru against files somewhere unusual without a chooser,
 * and how a headless run keeps away from real preferences.
 */
final class GameFiles {
    private static final Preferences PREFERENCES = Preferences.userRoot().node("dev/yoru/game");

    private GameFiles() { }

    /** The core to run: an override, the one last chosen, or the platform's usual place for it. */
    static Path core() {
        var chosen = path("core");
        return chosen != null ? chosen : LibretroCore.defaultCorePath();
    }

    /** The game file, or null until one is chosen. */
    static Path rom() { return path("rom"); }

    static void rememberCore(Path file) { PREFERENCES.put("core", file.toAbsolutePath().toString()); }

    static void rememberRom(Path file) { PREFERENCES.put("rom", file.toAbsolutePath().toString()); }

    private static Path path(String key) {
        String override = System.getProperty("yoru.game." + key);
        if (override != null && !override.isBlank()) return Path.of(override);
        String stored = PREFERENCES.get(key, null);
        return stored != null && Files.isRegularFile(Path.of(stored)) ? Path.of(stored) : null;
    }

    /** Somewhere durable the core may keep files of its own. */
    static Path workDirectory() { return Path.of(System.getProperty("user.home"), ".yoru", "game"); }

    /**
     * Saves for this game already on this computer: the one earlier versions of
     * Yoru kept beside the core, RetroArch's, and one sitting next to the game
     * file. Offered so a game in progress is carried into the vault rather than
     * started over.
     */
    static List<Path> existingSaves(Path rom) {
        if (rom == null) return List.of();
        String stem = stem(rom);
        var saves = RetroArchSetup.saveDirectory();
        var out = new ArrayList<Path>();
        for (var candidate : List.of(workDirectory().resolve(stem + ".srm"), saves.resolve("mGBA").resolve(stem + ".srm"),
                saves.resolve(stem + ".srm"), rom.resolveSibling(stem + ".sav"), rom.resolveSibling(stem + ".srm")))
            if (Files.isRegularFile(candidate) && !out.contains(candidate)) out.add(candidate);
        return out;
    }

    /** A save file as a person would describe it: where it is, and when it was written. */
    static String describe(Path file) {
        String home = System.getProperty("user.home"), where = file.toString();
        if (where.startsWith(home)) where = "~" + where.substring(home.length());
        try {
            var written = Files.getLastModifiedTime(file).toInstant().atZone(ZoneId.systemDefault());
            return where + "  ·  " + written.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"));
        } catch (IOException e) {
            return where;
        }
    }

    /** The file's name without its extension, which emulators name saves after. */
    static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
