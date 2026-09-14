package dev.yoru.ui;

import dev.yoru.application.GameSync;
import dev.yoru.application.Tracker;
import dev.yoru.game.GameDelivery;
import dev.yoru.game.GameSession;
import dev.yoru.game.SessionHandle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The game running inside Yoru, from Play to Close (#42, #43).
 *
 * The game only runs once Play is pressed, keeps running whichever tab is on
 * screen, and stops only when it is closed — by Close, or by quitting Yoru.
 * Its battery save belongs to the vault: every save the game writes is kept
 * there as it happens, and study rewards go into it just before the game
 * starts and just after it stops, the two moments nothing else holds the save.
 *
 * Everything that can block — starting the core, waiting for it to stop —
 * happens on a worker thread, and every change to the vault on the event
 * thread, so the tracker sees one change at a time.
 */
final class GameController {

    enum Phase { IDLE, STARTING, RUNNING, CLOSING, STUCK }

    /** What the window hears about. Both are called on the event thread. */
    interface Listener {
        /** The game started, stopped or failed. */
        default void phaseChanged() { }
        /** The vault's save changed while the game ran. */
        default void saveChanged() { }
    }

    private final Tracker tracker;
    private final SessionHandle sessions = new SessionHandle();
    private final List<Runnable> whenClosed = new ArrayList<>();
    private Listener listener = new Listener() { };
    private Phase phase = Phase.IDLE;
    private boolean closeWhenStarted;
    private String problem;
    /** A dispatched save is not durable until the vault accepts it. Keep it for retry. */
    private byte[] pendingSave;
    private Runnable pendingAcknowledgement = () -> { };
    private String saveProblem;
    private int retryDelay = 1000;
    private final Timer retry = new Timer(1000, e -> retrySave());
    private final AtomicReference<Snapshot> incoming = new AtomicReference<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile long sessionGeneration;
    private record Snapshot(long session, byte[] bytes, Runnable acknowledged) { }
    enum SaveStatus { SAVED, PENDING, FAILED }

    SaveStatus saveStatus() {
        return saveProblem != null ? SaveStatus.FAILED
            : pendingSave != null || incoming.get() != null ? SaveStatus.PENDING : SaveStatus.SAVED;
    }

    void retrySave() {
        if (pendingSave != null) saved(pendingSave);
    }

    /** At most one EDT callback and the newest snapshot wait behind a slow disk. */
    void enqueueSave(long generation, byte[] bytes, Runnable acknowledged) {
        if (generation != sessionGeneration) return;
        var next = new Snapshot(generation, bytes.clone(), acknowledged);
        incoming.updateAndGet(current -> generation == sessionGeneration ? next : current);
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!queued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            try {
                Snapshot snapshot = incoming.getAndSet(null);
                if (snapshot != null && snapshot.session() == sessionGeneration) accept(snapshot);
            } finally {
                queued.set(false);
                if (incoming.get() != null) scheduleDrain();
            }
        });
    }
    private List<GameDelivery.Outcome> outcomes = List.of();
    /** The game's encounter tables, kept for this session only (#12). */
    private final EncounterTables encounters = new EncounterTables();

    GameController(Tracker tracker) {
        this.tracker = tracker;
        retry.setRepeats(false);
    }

    EncounterTables encounters() { return encounters; }

    /** Who hears about changes: the window showing the game, replaced when the window is rebuilt. */
    void listen(Listener next) { listener = next == null ? new Listener() { } : next; }

    Phase phase() { return phase; }
    /** The last thing that went wrong, or null. */
    String problem() { return saveProblem != null ? saveProblem : problem; }
    /** What happened to each reward the last time any were sent into the game. */
    List<GameDelivery.Outcome> outcomes() { return outcomes; }
    GameSession session() { return (GameSession) sessions.session(); }
    String failure() { return sessions.failure(); }
    int attempts() { return sessions.attempts(); }

    /** True whenever a game holds, or is about to hold, its own copy of the save. */
    boolean running() { return phase != Phase.IDLE; }

    /**
     * Sends waiting rewards into the vault's save, if nothing else holds it.
     * Safe to call at any time; while the game runs it does nothing.
     */
    List<GameDelivery.Outcome> sync() {
        if (running()) return List.of();
        try {
            var result = GameSync.deliver(tracker);
            if (!result.isEmpty()) outcomes = result;
            return result;
        } catch (IOException | RuntimeException e) {
            problem = "Your Pokémon could not be sent into the game: " + e.getMessage();
            return List.of();
        }
    }

    /** Sends rewards in, then starts the game from the vault's save. */
    void play(Path core, Path rom) {
        if (phase != Phase.IDLE) return;
        problem = null;
        sync();
        byte[] save = tracker.state().game() == null ? null : tracker.state().game().bytes();
        long generation = ++sessionGeneration;
        phase = Phase.STARTING;
        listener.phaseChanged();
        worker("yoru-game-start", () -> {
            try {
                var session = GameSession.start(core, rom, save,
                    (bytes, acknowledged) -> enqueueSave(generation, bytes, acknowledged), GameFiles.workDirectory());
                sessions.adopt(session);
                SwingUtilities.invokeLater(() -> {
                    phase = Phase.RUNNING;
                    listener.phaseChanged();
                    if (closeWhenStarted) { closeWhenStarted = false; stop(); }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    phase = Phase.IDLE;
                    problem = "The game could not start: " + e.getMessage();
                    closeWhenStarted = false;
                    listener.phaseChanged();
                    finishClosing();
                });
            }
        });
    }

    /** An in-game save, kept in the vault as it happens. */
    private void accept(Snapshot snapshot) {
        pendingAcknowledgement = snapshot.acknowledged();
        saved(snapshot.bytes());
    }

    void saved(byte[] bytes) {
        pendingSave=bytes.clone();
        try {
            tracker.gameSaved(pendingSave);
        } catch (IOException | RuntimeException e) {
            saveProblem = "Could not save to your vault. Yoru is keeping the latest save and will retry.";
            retry.setInitialDelay(retryDelay);
            retry.restart();
            retryDelay=Math.min(30_000,retryDelay*2);
            listener.phaseChanged();
            return;
        }
        // A notification failure cannot turn an already committed save into a
        // failed write or leave its retry armed.
        Runnable acknowledged = pendingAcknowledgement;
        pendingAcknowledgement = () -> { };
        pendingSave=null;
        saveProblem=null;
        retry.stop();
        retryDelay=1000;
        try {
            acknowledged.run();
            listener.saveChanged();
        } finally {
            if (phase == Phase.STUCK) {
                if (session() == null) finishStuckClose();
                else if (session().readyToFinishClosing()) stop();
            }
        }
    }

    /**
     * Stops the game, keeps its final save, then sends in anything caught while
     * it ran. {@code then} runs afterwards on the event thread, whether or not
     * the game actually stopped — and only afterwards, however many times this
     * is asked while a start or a close is still under way.
     */
    void close(Runnable then) {
        if (then != null) whenClosed.add(then);
        switch (phase) {
            case IDLE -> finishClosing();
            case STARTING -> closeWhenStarted = true;
            case CLOSING -> { }
            case RUNNING, STUCK -> stop();
        }
    }

    private void stop() {
        phase = Phase.CLOSING;
        listener.phaseChanged();
        worker("yoru-game-close", () -> {
            boolean released = sessions.release(5000);
            // Queued after the final save the shutdown passed on, so the vault
            // already holds it by the time rewards go in.
            SwingUtilities.invokeLater(() -> {
                // The event queue has now processed the core's final save. A failed
                // vault write must keep Close/switch blocked, even after the core stops.
                Snapshot finalSnapshot = incoming.getAndSet(null);
                if (finalSnapshot != null && finalSnapshot.session() == sessionGeneration) accept(finalSnapshot);
                if (released && pendingSave!=null) retrySave();
                phase = released && pendingSave==null ? Phase.IDLE : Phase.STUCK;
                if (!released && session() != null && session().readyToFinishClosing()) {
                    stop();
                    return;
                }
                if (phase==Phase.IDLE) { problem=null;sync(); }
                listener.phaseChanged();
                finishClosing();
            });
        });
    }

    /**
     * A close that only waited on the vault, finished once the vault has the save (#7).
     *
     * The core had already stopped and been released; only the failed write kept
     * the phase STUCK. The automatic retry used to make that save durable and
     * then leave the phase where it was, because it only finished a close that
     * still had a session to stop, so Play and vault switching stayed blocked
     * until Close was pressed a second time.
     */
    private void finishStuckClose() {
        phase = Phase.IDLE;
        problem = null;
        sync();
        listener.phaseChanged();
        finishClosing();
    }

    private void finishClosing() {
        var waiting = List.copyOf(whenClosed);
        whenClosed.clear();
        waiting.forEach(Runnable::run);
    }

    private static void worker(String name, Runnable work) {
        var thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }
}
