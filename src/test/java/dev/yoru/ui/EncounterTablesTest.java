package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.game.WildEncounters;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Encounter tables belong to one vault session, not to the process (#12): read
 * once per game file, read again when the file changes, kept when a read fails,
 * owned by each game controller separately, and let go on request. No game file
 * is read; the reader is a counter.
 */
public final class EncounterTablesTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        var reads = new ArrayList<Path>();
        var failing = new boolean[1];
        List<WildEncounters.Area> fixture = List.of();
        var tables = new EncounterTables(rom -> {
            if (failing[0]) throw new IOException("unreadable game file");
            reads.add(rom);
            return new ArrayList<>(fixture);
        });
        Path first = Path.of("invented", "first.gba"), second = Path.of("invented", "second.gba");

        check(!tables.holding(), "nothing is held before the first encounter");
        var areas = tables.areas(first);
        check(reads.equals(List.of(first)), "the first encounter reads the game file");
        check(tables.areas(first) == areas && reads.size() == 1, "the next one reuses what was read");
        tables.areas(second);
        check(reads.equals(List.of(first, second)), "a different game file is read afresh");
        var held = tables.areas(second);

        failing[0] = true;
        try { tables.areas(first); throw new AssertionError("a failed read was treated as a table"); }
        catch (IOException expected) { checks++; }
        failing[0] = false;
        check(tables.areas(second) == held && reads.size() == 2, "a failed read keeps the tables already held");

        tables.forget();
        check(!tables.holding(), "forgetting lets the tables go");
        tables.areas(second);
        check(reads.size() == 3, "and the next encounter reads again");

        var tracker = new Tracker(new Memory(), Clock.systemUTC());
        var one = new GameController(tracker);
        var other = new GameController(tracker);
        check(one.encounters() != other.encounters(), "each session's controller owns its own tables");
        check(one.encounters() == one.encounters(), "and keeps the same ones for as long as it lives");

        System.out.println("PASS: " + checks + " encounter table checks (read once, reread on change, failed read, per session, forget)");
    }
}
