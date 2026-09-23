package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.anki.AnkiConnect;
import dev.yoru.persistence.EncryptedVault;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Restart offline, background retry and failed cache saves, with invented data. */
public final class AnkiReconnectTest {
    static final class Memory implements Repository {
        State state; boolean fail;
        Memory(State state) { this.state = state; }
        public State load() { return state; }
        public void save(State next) throws java.io.IOException {
            if (fail) throw new java.io.IOException("Synthetic save failure");
            state = next;
        }
        public void close() { }
    }
    static String text(AnkiCard card) {
        return Arrays.stream(card.getComponents()).filter(c -> c instanceof AbstractButton)
            .map(c -> ((AbstractButton)c).getText()).findFirst().orElse("");
    }
    interface Check { boolean ready(); }
    static void await(Check check) throws Exception {
        var ready = new AtomicBoolean();
        long end = System.nanoTime() + 5_000_000_000L;
        while (!ready.get() && System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> ready.set(check.ready()));
            Thread.sleep(20);
        }
        assert ready.get() : "Anki state did not arrive";
    }
    public static void main(String[] args) {
        try { run(); System.out.println("PASS: Anki encrypted restart offline, background reconnect, stale dates and cache-save failure"); }
        catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
    static void run() throws Exception {
        var dir = Files.createTempDirectory("yoru-anki-reopen");
        try {
            var today = LocalDate.now(ZoneOffset.UTC);
            var yesterday = today.minusDays(1).atTime(8, 42).toInstant(ZoneOffset.UTC);
            var days = new TreeMap<LocalDate, Long>(); days.put(today.minusDays(1), 24L);
            var cached = new AnkiSnapshot("Practice", 24, days, yesterday);
            var saved = State.empty().withAnki(new Anki(true, "", false, 1, cached));
            var file = dir.resolve("synthetic.vault");
            try (var vault = new EncryptedVault(file, "fixture-password".toCharArray())) { vault.save(saved); }
            State reopened;
            try (var vault = new EncryptedVault(file, "fixture-password".toCharArray())) { reopened = vault.load(); }
            assert reopened.anki().last().equals(cached);
            assert Arrays.stream(AnkiSnapshot.class.getRecordComponents()).map(c -> c.getName()).toList()
                .equals(List.of("profile", "today", "days", "fetchedAt")) : "Only summary data is stored";
            var memory = new Memory(reopened); var tracker = new Tracker(memory, Clock.systemUTC());
            var online = new AtomicBoolean(); var reads = new AtomicInteger();
            var latest = new AtomicReference<>(new AnkiConnect.Snapshot("Practice", 31,
                new TreeMap<>(Map.of(today, 31L)), Instant.now()));
            var holder = new AtomicReference<AnkiCard>();
            SwingUtilities.invokeAndWait(() -> {
                Theme.install();
                var card = new AnkiCard((key, reach) -> {
                    reads.incrementAndGet();
                    if (!online.get()) throw new java.io.IOException("Offline fixture");
                    return latest.get();
                }, () -> tracker, () -> ZoneOffset.UTC, () -> {}, () -> {});
                holder.set(card); card.render();
                assert text(card).contains("24 reviews at last refresh");
                assert text(card).contains("08:42");
                card.syncConnection();
            });
            var card = holder.get();
            await(() -> !card.refreshing() && text(card).contains("Anki is closed"));
            SwingUtilities.invokeAndWait(() -> { assert text(card).contains("24 reviews at last refresh"); });
            var timerField = AnkiCard.class.getDeclaredField("timer"); timerField.setAccessible(true);
            var timer = (javax.swing.Timer)timerField.get(card);
            SwingUtilities.invokeAndWait(() -> {
                assert timer.isRunning() && timer.getDelay() == 60_000;
                card.removeNotify();
                assert timer.isRunning() : "Leaving Today must retain the retry timer";
                online.set(true);
                // Accelerate the real repeating timer; no manual refresh initiates recovery.
                timer.setInitialDelay(10); timer.setDelay(10); timer.restart();
            });
            // Observe the saved result, not a tiny idle gap between accelerated retries.
            // SwingWorker completion is batched; on a busy runner the next timer
            // tick can start another refresh before the polling check sees idle.
            await(() -> tracker.state().anki().last().today() == 31);
            SwingUtilities.invokeAndWait(() -> { timer.stop(); });
            await(() -> !card.refreshing());
            assert tracker.state().anki().last().today() == 31;
            SwingUtilities.invokeAndWait(() -> {
                memory.fail = true;
                latest.set(new AnkiConnect.Snapshot("Practice", 99, new TreeMap<>(Map.of(today, 99L)), Instant.now()));
                card.refresh();
            });
            await(() -> !card.refreshing() && text(card).contains("could not be saved"));
            SwingUtilities.invokeAndWait(() -> {
                assert text(card).contains("31 reviews") && !text(card).contains("99 reviews");
                memory.fail = false;
                try { tracker.anki(tracker.state().anki().enabled(false)); }
                catch (Exception e) { throw new RuntimeException(e); }
                card.syncConnection();
                assert !timer.isRunning() && !card.isVisible();
                int before = reads.get(); card.refresh(); assert reads.get() == before;
                card.disconnect();
            });
        } finally {
            try (var walk = Files.walk(dir)) {
                for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
