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
    private static String text(Container c) {
        var s = new StringBuilder();
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel l) s.append(l.getText());
            if (child instanceof Container nested) s.append(text(nested));
        }
        return s.toString();
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
            }, () -> tracker, () -> zone, rebuilds::incrementAndGet);
            Preview.button(card, "Connect Anki").doClick();
        });
        await("24 reviews today");
        await("Anki profile: Practice");
        await("15m studied in Anki today · 15m in your tracked time");
        await("1 sitting · 15m to your tracked time.");
        await("under “Anki” once nothing has been answered for 10 minutes");
        SwingUtilities.invokeAndWait(() -> { });
        assert rebuilds.get() == 1 : "the page is rebuilt once to show the new totals";
        var sittings = tracker.state().sessions();
        assert sittings.size() == 1 && AnkiTime.isSitting(sittings.getFirst()) : sittings;
        assert tracker.state().activities().getFirst().name().equals("Anki");
        assert reaches.getFirst() == AnkiCard.CATCH_UP_DAYS : "the first refresh catches up on a week";
        render("connected");
        SwingUtilities.invokeAndWait(card::refresh);
        await("Updated");
        assert reaches.getLast() == 1 : "later refreshes read a day: " + reaches;
        assert tracker.state().sessions().size() == 1 && rebuilds.get() == 1 : "and add nothing twice";
        fail.set(true); SwingUtilities.invokeAndWait(card::refresh); await("Showing previous data.");
        SwingUtilities.invokeAndWait(() -> { assert text(card).contains("24 reviews today"); });
        render("stale");
        fail.set(false); blocked.set(true); SwingUtilities.invokeAndWait(card::refresh);
        assert started.await(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(card::disconnect);
        Thread.sleep(150);
        SwingUtilities.invokeAndWait(() -> {
            assert !text(card).contains("24 reviews today");
            assert Preview.button(card, "Connect Anki") != null;
        });
        assert cancelled.get() : "disconnect cancels pending refresh";
        gate.countDown();
        render("disconnected");
        SwingUtilities.invokeAndWait(() -> card.disconnect("Another vault is open. Connect Anki again to add your Anki time to it."));
        await("Connect Anki again");
        System.out.println("PASS: Anki card asynchronous refresh, study time into the tracker, stale data, disconnect race and theme renders");
        System.exit(0);
    }
}
