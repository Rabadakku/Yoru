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

    /** A vault change runs at once with no game, and waits for the game to stop otherwise. */
    private static void vaultChangesWaitForTheGame() throws Exception {
        var tracker = new Tracker(new Memory(), Clock.systemUTC());
        var game = new GameController(tracker);
        var ran = new AtomicBoolean();

        VaultLauncher.whenGameStopped(game, () -> ran.set(true));
        check(ran.get(), "with no game running, a vault change happens at once");

        // A game that is running: the change must not touch the vault yet.
        ran.set(false);
        Field phase = GameController.class.getDeclaredField("phase");
        phase.setAccessible(true);
        phase.set(game, GameController.Phase.RUNNING);
        check(game.running(), "the game reads as running");
        VaultLauncher.whenGameStopped(game, () -> ran.set(true));
        check(!ran.get(), "with the game running, the vault change waits instead of touching the vault");

        // Let the game stop: the queued change runs afterwards, never before.
        game.close(null);
        for (int i = 0; i < 400 && !ran.get(); i++) {
            Thread.sleep(5);
            SwingUtilities.invokeAndWait(() -> { });
        }
        check(ran.get(), "and runs once the game has stopped");
        check(game.phase() == GameController.Phase.IDLE, "with the game stopped rather than stuck");
    }

    public static void main(String[] args) throws Exception {
        vaultChangesWaitForTheGame();
        System.out.println("PASS: " + checks + " vault UI checks (a vault change never runs under a running game)");
    }
}
