package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Fixture;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import javax.swing.SwingUtilities;

/**
 * Two paths through the game's lifecycle that #7 names and GameSaveFailureTest
 * does not reach: Close pressed while the game is still starting, and a burst
 * of in-game saves queued behind a slow disk.
 *
 * No core is loaded and nothing sleeps: the start fails on a core file that
 * does not exist, and the slow disk is a repository held on a latch. Invented
 * saves only. A close during a start that succeeds needs a real core;
 * NativeLifecycleCheck covers the core's side of that by hand.
 */
public final class GameLifecycleTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    /** A repository whose first write waits until the test lets it finish. */
    private static final class SlowDisk implements Repository {
        State state = State.empty();
        final CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger saves = new AtomicInteger();
        public State load() { return state; }
        public void save(State next) throws IOException {
            if (saves.getAndIncrement() == 0) {
                writing.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
            }
            state = next;
        }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        aCloseDuringStartWaitsForTheStartToEnd();
        aBurstBehindASlowDiskWritesOnlyTheNewest();
        System.out.println("PASS: " + checks + " game lifecycle checks (close during start, saves behind a slow disk)");
    }

    private static void aCloseDuringStartWaitsForTheStartToEnd() throws Exception {
        var tracker = new Tracker(new Memory(), Clock.systemUTC());
        var game = new GameController(tracker);
        var nowhere = Files.createTempDirectory("yoru-no-core");
        var closed = new AtomicInteger();
        var finished = new CountDownLatch(2);
        SwingUtilities.invokeAndWait(() -> {
            game.play(nowhere.resolve("no-core"), nowhere.resolve("no-game.gba"));
            check(game.phase() == GameController.Phase.STARTING, "Play starts the core on a worker, so the game is still starting");
            game.close(() -> { closed.incrementAndGet(); finished.countDown(); });
            game.close(() -> { closed.incrementAndGet(); finished.countDown(); });
            check(closed.get() == 0, "Close asked during a start waits for the start to end rather than finishing at once");
            check(game.running(), "and vault switching stays blocked meanwhile");
        });
        check(finished.await(10, TimeUnit.SECONDS), "both closes finish once the start has ended");
        SwingUtilities.invokeAndWait(() -> {
            check(closed.get() == 2, "each close runs its callback exactly once, got " + closed.get());
            check(game.phase() == GameController.Phase.IDLE && !game.running(), "the game is idle, not stuck");
            check(String.valueOf(game.problem()).contains("could not start"), "and says the start failed: " + game.problem());
            var again = new AtomicInteger();
            game.close(again::incrementAndGet);
            check(again.get() == 1, "a close with nothing running finishes at once");
        });
        Files.deleteIfExists(nowhere);
    }

    private static void aBurstBehindASlowDiskWritesOnlyTheNewest() throws Exception {
        var disk = new SlowDisk();
        var tracker = new Tracker(disk, Clock.systemUTC());
        var game = new GameController(tracker);
        int burst = 200;
        byte[][] saves = new byte[burst + 1][];
        for (int i = 0; i <= burst; i++) saves[i] = Gen3Fixture.save(i + 1, 3);
        var acknowledged = new AtomicIntegerArray(burst + 1);

        // This thread stands in for the emulation thread. The first save starts
        // a write the disk holds, so the event thread is busy while the rest arrive.
        game.enqueueSave(0, saves[0], () -> acknowledged.incrementAndGet(0));
        check(disk.writing.await(10, TimeUnit.SECONDS), "the first save reaches the disk");
        for (int i = 1; i <= burst; i++) {
            int n = i;
            game.enqueueSave(0, saves[i], () -> acknowledged.incrementAndGet(n));
        }
        check(game.saveStatus() == GameController.SaveStatus.PENDING, "while the disk is busy the save reads as pending");
        disk.release.countDown();
        for (int i = 0; i < 20 && disk.saves.get() < 2; i++) SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
        check(disk.saves.get() == 2,
            "two writes, the one under way and the newest queued behind it, not " + (burst + 1) + ": got " + disk.saves.get());
        SwingUtilities.invokeAndWait(() -> {
            check(Arrays.equals(tracker.state().game().bytes(), saves[burst]), "the vault holds the newest save");
            check(acknowledged.get(0) == 1 && acknowledged.get(burst) == 1, "the two saves written are each acknowledged once");
            int replaced = 0;
            for (int i = 1; i < burst; i++) replaced += acknowledged.get(i);
            check(replaced == 0, "no save replaced before it was written is acknowledged, got " + replaced);
            check(game.saveStatus() == GameController.SaveStatus.SAVED, "and the status reads saved");
        });
    }
}
