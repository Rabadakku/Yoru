package dev.yoru.game;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ownership of a running game (#29).
 *
 * The bug this exists to prevent is not visible in {@link EmulationLoop}: that
 * class correctly reported "I did not stop", and the interface then threw the
 * answer away, nulled its field and rebuilt the page. The core stayed alive
 * with nothing pointing at it, the explanation vanished, and the next render
 * was free to start a second one.
 *
 * So this tests the layer above — what the interface does with the answer —
 * driven by a session that refuses to stop on command, which no real core can
 * be made to do.
 */
public final class SessionHandleTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    /** A session whose shutdown succeeds, refuses or throws, on demand. */
    private static final class Fake implements SessionHandle.Stoppable {
        boolean stops;
        boolean throwOnStop;
        String trouble;
        final AtomicInteger asked = new AtomicInteger();
        boolean disposed;

        Fake(boolean stops) { this.stops = stops; }

        @Override public boolean shutDown(long millis) {
            asked.incrementAndGet();
            if (throwOnStop) throw new IllegalStateException("core wedged");
            if (!stops) return false;
            disposed = true;
            return true;
        }
        @Override public String trouble() { return trouble; }
    }

    /**
     * A refusal keeps the session, the core, and the reason.
     *
     * Every one of these was lost by the old code path, so each is asserted
     * separately rather than as one "it failed" check.
     */
    private static void aRefusedShutdownKeepsEverything() {
        var handle = new SessionHandle();
        var game = new Fake(false);
        game.trouble = "The game did not stop cleanly.";
        handle.adopt(game);

        check(!handle.release(50), "release reports that it could not let go");
        check(handle.holds(), "the session is still held, not dropped");
        check(handle.session() == game, "and it is the same session, not a replacement");
        check(!game.disposed, "the core was never disposed");
        check(handle.stuck(), "the handle knows it is stuck");
        check(handle.failure() != null && handle.failure().contains("did not stop"),
            "and keeps the reason, got " + handle.failure());
    }

    /** While stuck, nothing new may be started on top of it. */
    private static void aStuckHandleRefusesAReplacement() {
        var handle = new SessionHandle();
        var stubborn = new Fake(false);
        handle.adopt(stubborn);
        handle.release(10);

        boolean refused = false;
        try { handle.adopt(new Fake(true)); }
        catch (IllegalStateException e) { refused = true; }
        check(refused, "adopting a second game while the first is still running is refused");
        check(handle.session() == stubborn, "and the original is still the one held");

        // The same is true of a healthy session that simply has not been closed.
        var busy = new SessionHandle();
        busy.adopt(new Fake(true));
        refused = false;
        try { busy.adopt(new Fake(true)); } catch (IllegalStateException e) { refused = true; }
        check(refused, "and so is replacing a session that was never released");
    }

    /** Retrying is the way out, and it works once the session cooperates. */
    private static void retryingSucceedsOnceItStops() {
        var handle = new SessionHandle();
        var game = new Fake(false);
        handle.adopt(game);

        check(!handle.release(10), "first attempt fails");
        check(!handle.release(10), "second attempt fails too");
        check(handle.attempts() == 2, "both attempts are counted, got " + handle.attempts());
        check(game.asked.get() == 2, "and both reached the session");

        game.stops = true;
        check(handle.release(10), "once it stops, release succeeds");
        check(!handle.holds(), "the handle is empty");
        check(!handle.stuck(), "and no longer stuck");
        check(handle.failure() == null, "with the failure cleared");
        check(game.disposed, "the core was disposed exactly once it agreed to stop");

        handle.adopt(new Fake(true));
        check(handle.holds(), "and a new game can then be started");
    }

    /** Closing twice is harmless. */
    private static void repeatedReleaseIsSafe() {
        var handle = new SessionHandle();
        var game = new Fake(true);
        handle.adopt(game);
        check(handle.release(10), "the first release succeeds");
        check(handle.release(10), "a second release on an empty handle also succeeds");
        check(handle.release(10), "and a third");
        check(game.asked.get() == 1, "without asking the session again, got " + game.asked.get());
        check(!handle.holds(), "and it stays empty");
    }

    /** A session that throws on the way out is kept, not dropped. */
    private static void athrowingShutdownIsNotSuccess() {
        var handle = new SessionHandle();
        var game = new Fake(true);
        game.throwOnStop = true;
        handle.adopt(game);

        check(!handle.release(10), "a throw is not a successful shutdown");
        check(handle.holds(), "so the session is kept");
        check(handle.failure() != null && handle.failure().contains("wedged"),
            "and the reason names what went wrong, got " + handle.failure());
        check(!game.disposed, "nothing was disposed");
    }

    /** The session's own explanation is preferred over a generic one. */
    private static void theSessionsOwnReasonIsUsed() {
        var handle = new SessionHandle();
        var game = new Fake(false);
        game.trouble = "Sound unavailable  ·  the game did not stop cleanly.";
        handle.adopt(game);
        handle.release(10);
        check(handle.failure().equals(game.trouble),
            "the session's notices are shown rather than a generic timeout message");

        var quiet = new SessionHandle();
        var silent = new Fake(false);
        quiet.adopt(silent);
        quiet.release(75);
        check(quiet.failure().contains("75"),
            "and with nothing to say, the timeout is reported, got " + quiet.failure());
    }

    /** An empty handle is not stuck and holds nothing. */
    private static void anEmptyHandleIsQuiet() {
        var handle = new SessionHandle();
        check(!handle.holds() && !handle.stuck(), "a fresh handle holds nothing");
        check(handle.release(10), "releasing nothing succeeds");
        boolean refused = false;
        try { handle.adopt(null); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "adopting nothing is refused");
    }

    public static void main(String[] args) {
        aRefusedShutdownKeepsEverything();
        aStuckHandleRefusesAReplacement();
        retryingSucceedsOnceItStops();
        repeatedReleaseIsSafe();
        athrowingShutdownIsNotSuccess();
        theSessionsOwnReasonIsUsed();
        anEmptyHandleIsQuiet();
        System.out.println("PASS: "+checks+" session handle checks (refusal, retention, retry, replacement)");
    }
}
