package dev.yoru.game;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The thread that drives the core, and the guarantee that it has stopped.
 *
 * Separated from {@link GameSession} for one reason: the ordering it enforces
 * is the difference between a clean shutdown and disposing a native library
 * while it is executing, and that ordering has to be testable without a real
 * emulator. Everything here can be driven by a plain {@link Runnable}, so a
 * test can hand it a tick that deliberately refuses to finish.
 *
 * <h2>Why a latch rather than Thread.join</h2>
 * A join that times out returns no information — the caller cannot tell whether
 * the thread finished or is still inside the core, and carrying on regardless
 * is what turns a hung frame into a crash. {@link #stop} reports whether the
 * loop actually left, and a caller that gets {@code false} must not dispose
 * anything.
 */
final class EmulationLoop {

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean inside = new AtomicBoolean();
    private Thread thread;

    /** True while a tick is actually executing — the window disposal must avoid. */
    boolean executing() { return inside.get(); }

    boolean running() { return running.get(); }
    boolean paused() { return paused.get(); }
    void pause() { paused.set(true); }
    void resume() { paused.set(false); }

    /**
     * Starts ticking on a daemon thread.
     *
     * {@code idle} runs instead of {@code tick} while paused, so a paused loop
     * can still do cheap housekeeping without touching the core.
     */
    void start(String name, Runnable tick, Runnable idle) {
        if (thread != null) throw new IllegalStateException("Already started.");
        running.set(true);
        thread = new Thread(() -> {
            try {
                while (running.get()) {
                    if (paused.get()) { idle.run(); continue; }
                    inside.set(true);
                    try { tick.run(); } finally { inside.set(false); }
                }
            } finally {
                // Counted down however the loop leaves, including on an
                // exception, so a crashed tick does not look like a hang.
                inside.set(false);
                stopped.countDown();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Asks the loop to stop and waits up to {@code millis} for it to leave.
     *
     * Returns false when it is still running, and the caller must then treat
     * the core as in use: no reading its memory, and above all no closing it.
     * An interrupt is reported the same way rather than being swallowed, and
     * the interrupt flag is restored so the caller can still act on it.
     */
    boolean stop(long millis) {
        running.set(false);
        if (thread == null) return true;
        try {
            return stopped.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Whether the loop has finished, without waiting. */
    boolean halted() { return stopped.getCount() == 0; }
}
