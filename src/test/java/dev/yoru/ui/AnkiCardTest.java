package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import dev.yoru.domain.Model.ThemeId;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
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
        var blocked = new AtomicBoolean();
        var gate = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var days = new TreeMap<LocalDate, Long>();
        days.put(LocalDate.now(), 24L); days.put(LocalDate.now().minusDays(1), 16L);
        var snapshot = new AnkiConnect.Snapshot(24, days, Instant.now());
        SwingUtilities.invokeAndWait(() -> {
            Theme.apply(ThemeId.values()[0]);
            card = new AnkiCard(key -> {
                assert !SwingUtilities.isEventDispatchThread();
                if (blocked.get()) { started.countDown(); gate.await(); }
                if (fail.get()) throw new java.io.IOException("fixture error");
                return snapshot;
            });
            Preview.button(card, "Connect Anki").doClick();
        });
        await("24 reviews today");
        render("connected");
        fail.set(true); SwingUtilities.invokeAndWait(card::refresh); await("Showing previous data.");
        SwingUtilities.invokeAndWait(() -> { assert text(card).contains("24 reviews today"); });
        render("stale");
        fail.set(false); blocked.set(true); SwingUtilities.invokeAndWait(card::refresh);
        assert started.await(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(card::disconnect); gate.countDown();
        Thread.sleep(150);
        SwingUtilities.invokeAndWait(() -> {
            assert !text(card).contains("24 reviews today");
            assert Preview.button(card, "Connect Anki") != null;
        });
        render("disconnected");
        System.out.println("PASS: Anki card asynchronous refresh, stale data, disconnect race and theme renders");
        System.exit(0);
    }
}
