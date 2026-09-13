package dev.yoru.game;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Runs a loaded game on its own thread and publishes frames, sound and saves.
 *
 * The core is a single-threaded C library: every call has to come from the same
 * thread, and it must not be the Swing thread, because a 60Hz emulation loop on
 * the event queue would starve the rest of the app. So the core lives here, on
 * one owned thread, and the screen only ever reads the picture this publishes.
 *
 * The battery save belongs to the vault, not to a file. A session is handed
 * the save to start from, and hands every new one the game writes to a
 * {@link SaveSink} — which is how an in-game save reaches the tracker.
 */
public final class GameSession implements AutoCloseable, SessionHandle.Stoppable {

    /** Where saves the game writes are sent. Called on the emulation thread. */
    @FunctionalInterface
    public interface SaveSink {
        void saved(byte[] bytes, Runnable acknowledged);
    }

    private final LibretroCore core;
    private final Object frameLock = new Object();
    private final EmulationLoop loop = new EmulationLoop();

    private BufferedImage published;
    private SourceDataLine speaker;
    private volatile long frames;
    private SaveSink sink;
    private SaveTransfer transfer;
    private boolean saveTransferFailed;
    private volatile boolean awaitingSave;

    public boolean readyToFinishClosing() { return awaitingSave && transfer.durable(); }

    /**
     * Everything the player needs told, kept as a list rather than one string,
     * so a less important warning can never replace a more important one.
     */
    private final java.util.List<String> notices =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private void notice(String text) {
        synchronized (notices) { if (!notices.contains(text)) notices.add(text); }
    }

    private GameSession(LibretroCore core) { this.core = core; }

    /**
     * Opens the core and loads the game, starting from {@code save} when there
     * is one. {@code workDirectory} is somewhere durable the core may keep its
     * own files, used as its system folder when RetroArch has no BIOS in place.
     */
    public static GameSession start(Path corePath, Path rom, byte[] save, SaveSink sink, Path workDirectory) throws Exception {
        // Start under the same configuration RetroArch would use: its core
        // options, and its system directory when a BIOS is sitting there. A
        // game that behaves differently in Yoru than in RetroArch is a game
        // whose behaviour nobody can reason about.
        var options = RetroArchSetup.options("mGBA");
        java.nio.file.Files.createDirectories(workDirectory);
        Path system = RetroArchSetup.hasGbaBios() ? RetroArchSetup.systemDirectory() : workDirectory;
        var core = LibretroCore.open(corePath, options, system);
        GameSession session;
        try {
            core.loadGame(rom);
            session = new GameSession(core);
            session.restore(save, sink);
        } catch (Exception e) {
            core.close();
            throw e;
        }
        session.openSpeaker();
        session.run();
        return session;
    }

    /**
     * Loads the save into the core and remembers what it was, so only saves the
     * game writes from now on are passed on.
     *
     * With no save to start from, what the core begins with is remembered
     * instead: a blank cartridge is not a save, and the vault should not fill
     * with one before the player has saved anything.
     */
    private void restore(byte[] save, SaveSink sink) {
        this.sink = sink;
        long expected = core.memorySize(LibretroCore.MEMORY_SAVE_RAM);
        validateSaveSize(save, expected);
        if (save != null) core.writeMemory(LibretroCore.MEMORY_SAVE_RAM, save);
        transfer = new SaveTransfer(core.memory(LibretroCore.MEMORY_SAVE_RAM), sink);
    }

    static void validateSaveSize(byte[] save, long expected) {
        if (expected <= 0 || (save != null && save.length != expected))
            throw new IllegalArgumentException("This emulator's save format does not match your game. "
                + "Your existing save has been left untouched. Choose a compatible core before playing.");
    }

    /** Hands on the game's save if it changed since it was last passed on. */
    public synchronized boolean flushSave() {
        return dispatchSave();
    }

    /** Dispatch changed bytes without waiting on the UI thread. Each snapshot
     * owns its receipt, so an older acknowledgement cannot release a newer save.
     * The controller retains failed writes for retry; shutdown retains the core
     * until the most recent receipt confirms persistence. */
    private synchronized boolean dispatchSave() {
        if (sink == null) return false;
        saveTransferFailed = false;
        byte[] now;
        try {
            now = core.memory(LibretroCore.MEMORY_SAVE_RAM);
        } catch (RuntimeException e) {
            saveTransferFailed = true;
            notice("Could not read the game's save: " + e.getMessage());
            return false;
        }
        if (now.length == 0) { saveTransferFailed = true; return false; }
        try {
            return transfer.offer(now);
        } catch (RuntimeException e) {
            saveTransferFailed = true;
            notice("Could not keep the game's save: " + e.getMessage());
            return false;
        }
    }

    public LibretroCore core() { return core; }
    public int width() { return core.width(); }
    public int height() { return core.height(); }
    public long frameCount() { return frames; }
    public boolean paused() { return loop.paused(); }
    /** Everything worth telling the player, or null when all is well. */
    public String trouble() {
        synchronized (notices) { return notices.isEmpty() ? null : String.join("  ·  ", notices); }
    }

    /** The most recent picture, or null before the first frame. */
    public BufferedImage frame() {
        synchronized (frameLock) { return published; }
    }

    public void press(int button, boolean down) { core.press(button, down); }

    public void pause() { loop.pause(); }
    public void resume() { loop.resume(); }

    private void openSpeaker() {
        try {
            // The core reports its own rate; mGBA asks for 65536Hz, which is not
            // a rate most devices offer natively but which Java will convert.
            var format = new AudioFormat((float) core.sampleRate(), 16, 2, true, false);
            speaker = AudioSystem.getSourceDataLine(format);
            speaker.open(format, (int) (core.sampleRate() * 4 / 10));
            speaker.start();
        } catch (Exception e) {
            speaker = null;
            notice("Sound unavailable: " + e.getMessage());
        }
    }

    private long period, next;

    private void run() {
        period = (long) (1_000_000_000L / Math.max(1, core.fps()));
        next = System.nanoTime();
        loop.start("yoru-game", this::tick, this::idle);
    }

    /** While paused: drain stale sound and burn no time in the core. */
    private void idle() {
        if (speaker != null) speaker.flush();
        sleep(20);
        next = System.nanoTime();
    }

    private void tick() {
        try {
            if (core.runFrame()) publish();
            frames++;
            feed(core.drainAudio());
        } catch (RuntimeException e) {
            notice("The game stopped: " + e.getMessage());
            loop.stop(0);
            return;
        }
        // Checked every few seconds and passed on only when the bytes changed,
        // so a crash costs seconds of a save rather than a session, and an idle
        // game costs nothing. Guarded like the core calls above: an exception
        // out of the loop body would end the emulation thread with no notice,
        // leaving a frozen picture that looks like a hang.
        if (frames % 300 == 0) {
            try { flushSave(); }
            catch (RuntimeException e) { notice("Could not keep the game's save: " + e.getMessage()); }
        }
        next += period;
        long wait = next - System.nanoTime();
        // A long stall (a breakpoint, a sleeping laptop) must not be repaid by
        // sprinting through hundreds of frames.
        if (wait < -period * 5) next = System.nanoTime();
        else if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
    }

    private void publish() {
        var image = new BufferedImage(core.width(), core.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, core.width(), core.height(), core.pixels(), 0, core.width());
        synchronized (frameLock) { published = image; }
    }

    private void feed(short[] samples) {
        if (speaker == null || samples.length == 0) return;
        var bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) bytes.putShort(sample);
        byte[] out = bytes.array();
        // Never block the emulation thread on the sound card: if the line is
        // full the frame is already late, and dropping sound beats dropping
        // frames.
        int room = speaker.available();
        if (room > 0) speaker.write(out, 0, Math.min(room, out.length));
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Shuts the game down, in an order that cannot overlap with the core.
     *
     * If the loop does not leave within the timeout, nothing is disposed. That
     * is deliberate: leaking a core is a bounded cost, while reading its memory
     * or unloading a native library that is still executing is a crash — one
     * that would take the player's unsaved game with it. The final save is
     * passed on before the core goes, so Close never loses an in-game save.
     *
     * Returns true when the session was fully shut down.
     */
    @Override public void close() { shutDown(5000); }

    /** True once the core has actually been disposed. */
    public synchronized boolean closed() { return disposed; }

    private boolean disposed;

    @Override public synchronized boolean shutDown(long millis) {
        // Idempotent: closing something already closed is success, not a second
        // attempt to dispose a core that is gone.
        if (disposed) return true;
        if (!loop.stop(millis)) {
            notice("The game did not stop cleanly, so it has been left running "
                + "rather than closed while still in use.");
            return false;
        }
        // Read only after the loop stops, then wait without blocking the EDT.
        dispatchSave();
        if (saveTransferFailed) return false;
        awaitingSave = !transfer.durable();
        if (awaitingSave) return false;
        if (speaker != null) { speaker.stop(); speaker.close(); speaker = null; }
        core.close();
        disposed = true;
        return true;
    }
}
