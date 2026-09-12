package dev.yoru.game;

/**
 * Ownership of a running game, and the rule that it is never let go of early.
 *
 * The interface used to call {@code close()}, ignore what it returned, null the
 * field and rebuild the page. When shutdown had actually refused — because the
 * loop was still inside the core — that dropped the last reference to a core
 * that was still executing, hid the warning explaining why, and left the way
 * open to start a second core beside the first.
 *
 * So the reference lives here instead, and the only way to let go of it is to
 * be told the session really stopped. A session that will not stop is retained,
 * not forgotten: {@link #failure()} says so, {@link #release} can be tried
 * again, and {@link #adopt} refuses to put a second game on top of it.
 *
 * Kept free of Swing and of the emulator so the policy can be tested against a
 * session that deliberately refuses to stop — which a real core cannot be made
 * to do on demand.
 */
public final class SessionHandle {

    /** What this handle needs of a session; {@link GameSession} satisfies it. */
    public interface Stoppable {
        /** Shuts down, returning false if it could not and is still in use. */
        boolean shutDown(long millis);
        /** Anything the player should be told, or null. */
        String trouble();
    }

    private Stoppable current;
    private String failure;
    private int attempts;

    /** The running session, or null. */
    public synchronized Stoppable session() { return current; }
    public synchronized boolean holds() { return current != null; }

    /** Why the last release was refused, or null. */
    public synchronized String failure() { return failure; }
    public synchronized int attempts() { return attempts; }

    /**
     * True while a session is retained only because it would not stop.
     *
     * The interface uses this to keep the Game tab on a failure notice with a
     * retry, rather than offering to start something new.
     */
    public synchronized boolean stuck() { return current != null && failure != null; }

    /**
     * Takes ownership of a new session.
     *
     * Refused while anything is still held. Replacing a live session would
     * leave a core running with nothing pointing at it, and starting a second
     * one is worse than refusing — libretro keeps its state globally, so two
     * cores fight over the same memory.
     */
    public synchronized void adopt(Stoppable session) {
        if (session == null) throw new IllegalArgumentException("No session to adopt.");
        if (current != null)
            throw new IllegalStateException(failure != null
                ? "The previous game has not shut down: " + failure
                : "A game is already running.");
        current = session;
        failure = null;
        attempts = 0;
    }

    /**
     * Tries to stop and let go.
     *
     * Returns true when the handle is now empty — including when it was already
     * empty, so a second Close after a successful one is harmless rather than
     * an error. On refusal the session is kept and the reason recorded.
     */
    public synchronized boolean release(long millis) {
        if (current == null) { failure = null; return true; }
        attempts++;
        boolean stopped;
        try {
            stopped = current.shutDown(millis);
        } catch (RuntimeException e) {
            // A session that throws on the way out has still not confirmed it
            // stopped, so it is kept rather than dropped.
            failure = "Shutting the game down failed: " + e.getMessage();
            return false;
        }
        if (!stopped) {
            String said = current.trouble();
            failure = said != null ? said
                : "The game did not stop within " + millis + "ms and is still running.";
            return false;
        }
        current = null;
        failure = null;
        return true;
    }
}
