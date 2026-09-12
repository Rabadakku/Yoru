package dev.yoru.game;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
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
        void saved(byte[] bytes);
    }

    private final LibretroCore core;
    private final Object frameLock = new Object();
    private final EmulationLoop loop = new EmulationLoop();

    private BufferedImage published;
    private SourceDataLine speaker;
    private volatile long frames;
    private SaveSink sink;
    /** The bytes the sink has taken. Anything else the core holds is still owed. */
    private byte[] lastWritten;
    /** Whether a save has ever been handed over, so Close knows there is one to offer again. */
    private boolean savedOnce;

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
        if (save != null && save.length != expected) {
            // Refusing to load it is not enough: the next flush would write the
            // core's blank memory back over it. So this session keeps nothing.
            notice("The save is " + save.length + " bytes but this game uses " + expected
                + ". It has been left untouched, and this session will not be saved.");
            this.sink = null;
            return;
        }
        if (save != null) core.writeMemory(LibretroCore.MEMORY_SAVE_RAM, save);
        lastWritten = core.memory(LibretroCore.MEMORY_SAVE_RAM);
    }

    /** Hands on the game's save if it changed since it was last passed on. */
    public synchronized boolean flushSave() {
        return flushSave(false);
    }

    /**
     * Hands on the game's save, and only records it as written once the sink
     * has taken it.
     *
     * The old order — remember the bytes, then hand them over — meant a sink
     * that refused them left the session believing the vault held a save it
     * had never accepted: the poll saw nothing new and skipped it for the rest
     * of the session, and the final flush at Close skipped it too. Now the
     * bytes stay owed until the sink returns, so a refused save is offered
     * again on the next poll and, because {@code lastWritten} never moved, once
     * more before the core goes.
     *
     * The app's own sink is asynchronous: it queues the write onto the event
     * thread, so its return means the save was dispatched rather than written.
     * That is why a session that has handed a save over at all offers the
     * current one again at Close ({@code atClose}): it is the last moment the
     * vault can be told, and the tracker ignores bytes it already holds.
     */
    private synchronized boolean flushSave(boolean atClose) {
        if (sink == null) return false;
        byte[] now;
        try {
            now = core.memory(LibretroCore.MEMORY_SAVE_RAM);
        } catch (RuntimeException e) {
            notice("Could not read the game's save: " + e.getMessage());
            return false;
        }
        if (now.length == 0) return false;
        boolean owed = !Arrays.equals(now, lastWritten);
        if (!owed && !(atClose && savedOnce)) return false;
        try {
            sink.saved(now.clone());
            // Only a sink that returned has taken the save, so only now does the
            // session stop owing it. A sink that throws leaves lastWritten alone.
            lastWritten = now;
            savedOnce = true;
            return true;
        } catch (RuntimeException e) {
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
        // The last chance to hand the save over: offering the current bytes even
        // when they are the ones last dispatched is what makes a queued write
        // that failed recoverable, and the tracker ignores bytes it holds.
        flushSave(true);
        if (speaker != null) { speaker.stop(); speaker.close(); speaker = null; }
        core.close();
        disposed = true;
        return true;
    }
}
