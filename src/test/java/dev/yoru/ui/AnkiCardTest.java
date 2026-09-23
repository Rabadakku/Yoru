package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import dev.yoru.application.AnkiTime;
import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.domain.Model.ThemeId;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class AnkiCardTest {
    private static AnkiCard card;
    /** Everything the card says: it is one line, drawn as a button. */
    private static String text(Container c) {
        var s = new StringBuilder();
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel l) s.append(l.getText());
            if (child instanceof AbstractButton b && b.getText() != null) s.append(b.getText());
            if (child instanceof Container nested) s.append(text(nested));
        }
        return s.toString();
    }
    private static Component find(Container c, String name) {
        for (Component child : c.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static void await(String wanted) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var ready = new AtomicBoolean();
        while (!ready.get() && System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> ready.set(text(card).contains(wanted)));
            Thread.sleep(20);
        }
        assert ready.get() : wanted;
    }
    /** Waits for a read in flight to finish, so the next one is not dropped. */
    private static void settle() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var idle = new AtomicBoolean();
        while (!idle.get() && System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> idle.set(!card.refreshing()));
            Thread.sleep(20);
        }
        assert idle.get() : "a refresh is still in flight";
    }

    private static void render(String state) throws Exception {
        Path out = Path.of("build/anki-preview"); Files.createDirectories(out);
        SwingUtilities.invokeAndWait(() -> {
            for (var theme : ThemeId.values()) {
                Theme.apply(theme); card.render(); card.setSize(760, 460); Preview.layout(card);
                var image = new BufferedImage(760, 460, BufferedImage.TYPE_INT_RGB);
                var g = image.createGraphics(); g.setColor(Theme.BG); g.fillRect(0, 0, 760, 460); card.printAll(g); g.dispose();
                try { ImageIO.write(image, "png", out.resolve(theme.name() + "-" + state + ".png").toFile()); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });
    }
    public static void main(String[] args) throws Exception {
        var fail = new AtomicBoolean();
        var cancelled = new AtomicBoolean();
        var blocked = new AtomicBoolean();
        var gate = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var days = new TreeMap<LocalDate, Long>();
        days.put(LocalDate.now(), 24L); days.put(LocalDate.now().minusDays(1), 16L);
        // A zone where it is about noon now, so a sitting forty minutes ago is
        // today's whatever time the suite runs.
        int utc = LocalTime.now(ZoneOffset.UTC).toSecondOfDay();
        int shift = Math.floorMod(12 * 3600 - utc + 12 * 3600, 24 * 3600) - 12 * 3600;
        var zone = ZoneOffset.ofTotalSeconds(shift / 60 * 60);
        // Fifteen minutes of answers that finished twenty-five minutes ago.
        var reviews = new ArrayList<AnkiTime.Review>();
        long answered = Instant.now().minus(Duration.ofMinutes(40)).toEpochMilli();
        for (int i = 0; i < 90; i++) { answered += 10_000; reviews.add(new AnkiTime.Review(answered, 10_000)); }
        var now = Instant.now();
        var snapshot = new AnkiConnect.Snapshot("Practice", 24, days, reviews, now.minus(Duration.ofDays(7)), now);
        var tracker = new Tracker(new Repository() {
            State state = State.empty();
            public State load() { return state; }
            public void save(State next) { state = next; }
            public void close() { }
        }, Clock.systemUTC());
        var reaches = new CopyOnWriteArrayList<Integer>();
        var rebuilds = new AtomicInteger();
        var opened = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            Theme.apply(ThemeId.values()[0]);
            card = new AnkiCard((key, reach) -> {
                assert !SwingUtilities.isEventDispatchThread();
                reaches.add(reach);
                if (blocked.get()) {
                    started.countDown();
                    try { gate.await(); } catch (InterruptedException e) { cancelled.set(true); throw e; }
                }
                if (fail.get()) throw new java.io.IOException("fixture error");
                return snapshot;
            }, () -> tracker, () -> zone, rebuilds::incrementAndGet, opened::incrementAndGet);
            // Switched on in Settings, as the owner does (#85), then shown.
            try { tracker.anki(tracker.state().anki().enabled(true)); }
            catch (Exception e) { throw new RuntimeException(e); }
            card.render();
            card.refresh();
        });
        await("24 reviews");
        await("15m tracked");
        await("updated");
        SwingUtilities.invokeAndWait(() -> { });
        assert rebuilds.get() == 1 : "the page is rebuilt once to show the new totals";
        var sittings = tracker.state().sessions();
        assert sittings.size() == 1 && AnkiTime.isSitting(sittings.getFirst()) : sittings;
        assert tracker.state().activities().getFirst().name().equals("Anki");
        assert reaches.getFirst() == AnkiCard.CATCH_UP_DAYS : "the first refresh catches up on a week";
        render("connected");
        // The line is the way to the details: one click opens the page that holds them (#85).
        SwingUtilities.invokeAndWait(() -> {
            var line = (AbstractButton) find(card, "today.anki.status");
            assert line != null : "the status line is a control";
            line.doClick();
        });
        assert opened.get() == 1 : "clicking the line opens the details once";
        // The counts are kept in the vault, so the line survives Anki closing (#51).
        assert tracker.state().anki().last() != null && tracker.state().anki().last().today() == 24
            : "the counts reach the vault: " + tracker.state().anki().last();
        settle();
        SwingUtilities.invokeAndWait(card::refresh);
        settle();
        await("24 reviews");
        assert reaches.getLast() == 1 : "later refreshes read a day: " + reaches;
        assert tracker.state().sessions().size() == 1 && rebuilds.get() == 1 : "and add nothing twice";
        settle();
        fail.set(true); SwingUtilities.invokeAndWait(card::refresh); await("Anki is closed");
        SwingUtilities.invokeAndWait(() -> { assert text(card).contains("24 reviews"); });
        render("stale");
        settle();
        fail.set(false); blocked.set(true); SwingUtilities.invokeAndWait(card::refresh);
        assert started.await(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(card::disconnect);
        Thread.sleep(150);
        assert cancelled.get() : "disconnect cancels pending refresh";
        gate.countDown();
        // Switched off in Settings: the line goes, and Anki is not contacted.
        SwingUtilities.invokeAndWait(() -> {
            try { tracker.anki(tracker.state().anki().enabled(false)); } catch (Exception e) { throw new RuntimeException(e); }
            card.render();
            assert !card.isVisible() : "switched off, the card shows nothing at all";
            int before = reaches.size();
            card.refresh();
            assert reaches.size() == before : "and asks Anki nothing";
        });
        System.out.println("PASS: Anki status line, counts kept in the vault, stale data, disconnect race and theme renders");
        System.exit(0);
    }
}
