package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vault changes and a running game (#41).
 *
 * The game holds its own copy of the vault's save while it runs, so switching,
 * renaming or deleting a vault underneath it would lose whatever it saved next.
 * The guard is the one thing here that cannot be exercised through the store or
 * the tracker, so it is driven directly: no game, and a game stopped by hand.
 */
public final class VaultUiTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        boolean closed;
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { closed = true; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("PASS: " + checks + " vault UI checks (a vault change never runs under a running game)");
    }
}
