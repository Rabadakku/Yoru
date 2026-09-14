package dev.yoru.ui;

import dev.yoru.game.Rom;
import dev.yoru.game.WildEncounters;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The chosen game's wild encounter tables, read once per game file and kept for
 * one vault session (#12).
 *
 * They used to sit in static fields on GameView, so they outlived the vault and
 * the window that read them, and nothing said when they were let go. Each game
 * controller now owns one of these, and the window drops it when it quits.
 */
final class EncounterTables {
    /** Reads a game file's tables: a seam, so a test can count reads without a game. */
    interface Reader { List<WildEncounters.Area> read(Path rom) throws IOException; }

    private final Reader reader;
    private Path from;
    private List<WildEncounters.Area> areas;

    EncounterTables() { this(rom -> WildEncounters.read(Rom.load(rom))); }

    EncounterTables(Reader reader) { this.reader = reader; }

    /** This game file's tables, read the first time they are asked for and again only when the file changes. */
    synchronized List<WildEncounters.Area> areas(Path rom) throws IOException {
        if (!rom.equals(from)) {
            areas = reader.read(rom);
            from = rom;
        }
        return areas;
    }

    /** Whether any tables are held, so a test can see they were let go. */
    synchronized boolean holding() { return areas != null; }

    /** Lets the tables go when the session that read them ends. */
    synchronized void forget() {
        from = null;
        areas = null;
    }
}
