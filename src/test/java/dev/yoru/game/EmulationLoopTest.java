package dev.yoru.game;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shutdown ordering (#29).
 *
 * The failure this guards against is not a wrong value on a screen — it is
 * unloading a native library while it is executing, which crashes the process
 * and takes the player's unsaved game with it. It cannot be tested against a
 * real core, because a real core cannot be made to hang on demand. So the
 * lifecycle is separated from the emulator and driven here by a tick that
 * blocks until this test lets it go.
 */
public final class EmulationLoopTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    /** A tick that will not return until released. */
    private static void aBlockedTickIsReportedRatherThanAssumedGone() throws Exception {
        var loop = new EmulationLoop();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        loop.start("blocked", () -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, () -> { });

        check(entered.await(2, TimeUnit.SECONDS), "the tick started");
        check(loop.executing(), "and is inside the tick");

        long began = System.nanoTime();
        boolean stopped = loop.stop(200);
        long waited = (System.nanoTime() - began) / 1_000_000;

        check(!stopped, "stop() reports failure rather than pretending the loop left");
        check(!loop.halted(), "and the loop really has not halted");
        check(loop.executing(), "execution is still inside the tick — nothing may be disposed");
        check(waited >= 180, "it waited for the timeout rather than returning at once, waited " + waited + "ms");

        release.countDown();
        // Once released it leaves of its own accord, because running is false.
        for (int i = 0; i < 200 && !loop.halted(); i++) Thread.sleep(10);
        check(loop.halted(), "and it leaves once the tick returns");
        check(!loop.executing(), "with nothing left inside the core");
    }

    /** A cooperative loop stops quickly and says so. */
    private static void aWellBehavedLoopStops() throws Exception {
        var loop = new EmulationLoop();
        var ticks = new AtomicInteger();
        loop.start("busy", () -> {
            ticks.incrementAndGet();
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, () -> { });
        Thread.sleep(50);
        check(ticks.get() > 0, "it ran, got " + ticks.get() + " ticks");
        check(loop.stop(2000), "stop() confirms it left");
        check(loop.halted(), "and it has");
        int after = ticks.get();
        Thread.sleep(30);
        check(ticks.get() == after, "and it does not tick again once stopped");
    }

    /**
     * A tick that throws still counts as stopped.
     *
     * Otherwise a crashed emulator would be indistinguishable from a hung one,
     * and shutdown would refuse to dispose a core that had already died —
     * leaking it every time something went wrong.
     */
    private static void aThrowingTickStillHalts() throws Exception {
        // The throw is the point of the case, so its stack trace is expected
        // output. Swallowing it here keeps a passing run quiet, so that noise
        // in this suite means something is actually wrong.
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { });
        try {
        var loop = new EmulationLoop();
        loop.start("throws", () -> { throw new IllegalStateException("core exploded"); }, () -> { });
        for (int i = 0; i < 200 && !loop.halted(); i++) Thread.sleep(10);
        check(loop.halted(), "the loop finished rather than hanging");
        check(!loop.executing(), "and is not reported as still inside the tick");
        check(loop.stop(100), "so shutdown may proceed");
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
    }

    /** Pausing runs the idle task and stops touching the tick entirely. */
    private static void pausingStopsTheTick() throws Exception {
        var loop = new EmulationLoop();
        var ticks = new AtomicInteger();
        var idles = new AtomicInteger();
        loop.start("pausable", ticks::incrementAndGet, () -> {
            idles.incrementAndGet();
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread.sleep(40);
        loop.pause();
        check(loop.paused(), "it is paused");
        Thread.sleep(20);
        int frozen = ticks.get();
        int idleBefore = idles.get();
        Thread.sleep(60);
        check(ticks.get() == frozen, "the tick does not run while paused, went " + frozen + " to " + ticks.get());
        check(idles.get() > idleBefore, "but the idle task does");

        loop.resume();
        Thread.sleep(40);
        check(ticks.get() > frozen, "and resuming starts it again");
        check(loop.stop(2000), "a paused-then-resumed loop still stops");
    }

    /** A loop that was paused and never resumed still shuts down. */
    private static void aPausedLoopStillStops() throws Exception {
        var loop = new EmulationLoop();
        loop.start("idle", () -> { }, () -> {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        loop.pause();
        Thread.sleep(30);
        check(loop.stop(2000), "a paused loop leaves when asked");
        check(loop.halted(), "and has halted");
    }

    /** Starting twice is a programming error, not something to paper over. */
    private static void startingTwiceIsRefused() {
        var loop = new EmulationLoop();
        loop.start("once", () -> { }, () -> { });
        boolean refused = false;
        try { loop.start("twice", () -> { }, () -> { }); }
        catch (IllegalStateException e) { refused = true; }
        check(refused, "a second start is refused");
        loop.stop(1000);
    }

    public static void main(String[] args) throws Exception {
        aBlockedTickIsReportedRatherThanAssumedGone();
        aWellBehavedLoopStops();
        aThrowingTickStillHalts();
        pausingStopsTheTick();
        aPausedLoopStillStops();
        startingTwiceIsRefused();
        System.out.println("PASS: "+checks+" emulation loop checks (blocked tick, shutdown, pause)");
    }
}
