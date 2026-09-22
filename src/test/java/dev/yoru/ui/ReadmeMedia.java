package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;

/**
 * Draws the README's pictures from the app's own pages over an invented vault
 * (ShowcaseVault): the banner and a framed shot of each feature. Nothing here
 * reads a real vault.
 *
 *   java -Djava.awt.headless=true -Duser.home=path/to/empty-dir -Duser.timezone=UTC \
 *        -cp build/classes dev.yoru.ui.ReadmeMedia docs/media
 *
 * The empty home keeps installed artwork and preferences out of the pictures,
 * and UTC keeps the date line from saying where they were drawn. Not run by
 * ./test.sh: it writes into the repository.
 */
public final class ReadmeMedia {
    static final double SCALE = 2.0;
    static final Color NIGHT = new Color(0x0A0F1D), NIGHT2 = new Color(0x141D36);
    static final Color INK = new Color(0xEEF3FB), MUTED = new Color(0xA3B0C6), ACCENT = new Color(0x8FD9DA),
        GOLD = new Color(0xE8C170), EDGE = new Color(0x34425F), CARD = new Color(0x172038);

    static Font font(String name, float size) {
        var f = new Font(name, Font.PLAIN, 12);
        if (f.getFamily().equals(Font.DIALOG)) f = new Font(Font.SANS_SERIF, Font.BOLD, 12);
        return f.deriveFont(size);
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/media");
        Files.createDirectories(out);
        var landscape = ImageIO.read(out.resolve("yoru-moonlit-landscape.png").toFile());
        var themed = new EnumMap<ThemeId, BufferedImage>(ThemeId.class);
        for (var theme : ThemeId.values())
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Theme.apply(theme);
                    var memory = ShowcaseVault.memory();
                    themed.put(theme, paint(open(ShowcaseVault.tracker(memory, theme), memory, "Today"), 1440, 900, SCALE));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        var pages = new HashMap<String, BufferedImage>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.apply(ThemeId.MIDNIGHT);
                var memory = ShowcaseVault.memory();
                var app = open(ShowcaseVault.tracker(memory, ThemeId.MIDNIGHT), memory, "Today");
                pages.put("Today", paint(app, 1440, 1900, SCALE));
                for (String page : new String[]{"Schedule", "Tasks", "Habits"}) {
                    Preview.button(app, page).doClick();
                    pages.put(page, paint(app, 1440, 1400, SCALE));
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        var anki = ankiCard();

        var today = themed.get(ThemeId.MIDNIGHT);
        jpeg(hero(landscape, today), out.resolve("hero.jpg"), 0.9f);
        png(framed(crop(today, new Rectangle(0, 0, 1440, 860)), 1800), out.resolve("today.png"));
        // Whole-width crops, so each page keeps its own margin inside the frame.
        png(framed(crop(pages.get("Today"), new Rectangle(0, 812, 1440, 478)), 1800), out.resolve("heatmap.png"));
        png(framed(crop(pages.get("Schedule"), new Rectangle(0, 232, 1440, 652)), 1800), out.resolve("schedule.png"));
        png(framed(crop(pages.get("Tasks"), new Rectangle(0, 100, 1440, 724)), 1800), out.resolve("tasks.png"));
        png(framed(crop(pages.get("Habits"), new Rectangle(0, 100, 1440, 712)), 1800), out.resolve("habits.png"));
        png(framed(anki, 1200), out.resolve("anki.png"));
        png(themes(themed), out.resolve("themes.png"));
        System.out.println("Wrote the README's pictures to " + out);
        System.exit(0);
    }

    // ---- the app, painted ----------------------------------------------------------
    static YoruApp open(Tracker tracker, Repository memory, String page) {
        var app = new YoruApp(tracker, memory);
        app.setSize(1440, 900);
        var nav = Preview.button(app, page);
        if (nav != null) nav.doClick();
        Preview.layout(app);
        return app;
    }
    static BufferedImage paint(Component c, int w, int h, double scale) {
        c.setSize(w, h);
        if (c instanceof Container k) Preview.layout(k);
        var img = new BufferedImage((int) (w * scale), (int) (h * scale), BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        quality(g);
        g.scale(scale, scale);
        c.paint(g);
        g.dispose();
        return img;
    }
    static BufferedImage crop(BufferedImage src, Rectangle r) {
        return src.getSubimage((int) (r.x * SCALE), (int) (r.y * SCALE), (int) (r.width * SCALE), (int) (r.height * SCALE));
    }
    /** The Anki card, connected, just after it added this evening's sitting. */
    static BufferedImage ankiCard() throws Exception {
        var ready = new CountDownLatch(1);
        var card = new AnkiCard[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                var tracker = ShowcaseVault.tracker(ShowcaseVault.memory(), ThemeId.MIDNIGHT, false);
                var now = Instant.now();
                var zone = ZoneId.systemDefault();
                var reviews = new ArrayList<>(ShowcaseVault.weekOfAnki(LocalDate.now(zone), zone));
                reviews.addAll(ShowcaseVault.todaysAnki(now));
                var days = new TreeMap<LocalDate, Long>();
                for (var r : reviews) days.merge(r.answered().atZone(zone).toLocalDate(), 1L, Long::sum);
                var snapshot = new AnkiConnect.Snapshot("Personal", days.getOrDefault(LocalDate.now(zone), 0L), days,
                    reviews, now.minus(Duration.ofDays(7)), now);
                card[0] = new AnkiCard((key, reach) -> snapshot, () -> tracker, () -> zone, ready::countDown);
                Preview.button(card[0], "Connect Anki").doClick();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("The Anki card did not record.");
        var image = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            card[0].setSize(620, 1000);
            Preview.layout(card[0]);
            var holder = new JPanel(new BorderLayout());
            holder.setBackground(Theme.BG);
            holder.add(card[0]);
            image[0] = paint(holder, 620, card[0].getPreferredSize().height, SCALE);
        });
        return image[0];
    }

    // ---- drawing ------------------------------------------------------------------
    static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
    static Color alpha(Color c, double a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * Math.max(0, Math.min(1, a)))); }

    static void night(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, NIGHT, 0, h, NIGHT2));
        g.fillRect(0, 0, w, h);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(w * 0.8, 0), (float) Math.max(w, h) * 0.6f,
            new float[]{0f, 1f}, new Color[]{alpha(ACCENT, 0.10), alpha(ACCENT, 0)}));
        g.fillRect(0, 0, w, h);
        var random = new Random(w * 31L + h);
        for (int i = 0; i < w * h / 9000; i++) {
            double s = 0.8 + random.nextDouble() * 1.8;
            g.setColor(alpha(INK, 0.15 + random.nextDouble() * 0.35));
            g.fill(new Ellipse2D.Double(random.nextDouble() * w, random.nextDouble() * h, s, s));
        }
    }
    /** src drawn as a window with rounded corners, a hairline edge and a soft shadow. */
    static void window(Graphics2D g, BufferedImage src, int x, int y, int w, int h, int radius) {
        for (int i = 10; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, 9));
            g.fill(new RoundRectangle2D.Double(x - i * 3, y - i * 3 + 22, w + i * 6, h + i * 6, radius + i * 6, radius + i * 6));
        }
        var layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var lg = layer.createGraphics();
        quality(lg);
        lg.setColor(Color.WHITE);
        lg.fill(new RoundRectangle2D.Double(0, 0, w, h, radius, radius));
        lg.setComposite(AlphaComposite.SrcIn);
        lg.drawImage(src, 0, 0, w, h, null);
        lg.dispose();
        g.drawImage(layer, x, y, null);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(x + 1, y + 1, w - 2, h - 2, radius, radius));
    }

    static BufferedImage themes(Map<ThemeId, BufferedImage> themed) {
        int w = 2000, tw = 560, gap = 70;
        int th = (int) Math.round(tw * 780 / 1392.0);
        int h = 100 + 2 * (th + 90) + 40;
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        quality(g);
        night(g, w, h);
        var order = ThemeId.values();
        for (int i = 0; i < order.length; i++) {
            int row = i < 3 ? 0 : 1, inRow = row == 0 ? 3 : 2, col = row == 0 ? i : i - 3;
            int rowWidth = inRow * tw + (inRow - 1) * gap;
            int x = (w - rowWidth) / 2 + col * (tw + gap), y = 70 + row * (th + 90);
            var src = themed.get(order[i]);
            var view = src.getSubimage((int) (24 * SCALE), (int) (60 * SCALE), (int) (1392 * SCALE), (int) (780 * SCALE));
            window(g, view, x, y, tw, th, 22);
            String name = order[i].name().charAt(0) + order[i].name().substring(1).toLowerCase(Locale.ROOT);
            g.setFont(font("AvenirNext-DemiBold", 32));
            g.setColor(INK);
            g.drawString(name, (float) (x + (tw - g.getFontMetrics().stringWidth(name)) / 2.0), y + th + 55);
        }
        g.dispose();
        return out;
    }

    // ---- files ---------------------------------------------------------------------
    static void png(BufferedImage img, Path path) throws Exception { ImageIO.write(img, "png", path.toFile()); }
    static void jpeg(BufferedImage img, Path path, float quality) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality);
        Files.deleteIfExists(path);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(img, null, null), params);
        } finally { writer.dispose(); }
    }
    /** A feature shot: the window on the night sky, width pixels wide. */
    static BufferedImage framed(BufferedImage src, int width) {
        int pad = width / 20;
        int w = width - 2 * pad, h = (int) Math.round(src.getHeight() * (double) w / src.getWidth());
        var out = new BufferedImage(width, h + 2 * pad, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        quality(g);
        night(g, out.getWidth(), out.getHeight());
        window(g, src, pad, pad, w, h, 28);
        g.dispose();
        return out;
    }
    static void wordmark(Graphics2D g, double x, double baseline, double size) {
        g.setFont(font("AvenirNext-Medium", (float) size));
        int mark = (int) (size * 0.82);
        Logo.mark(g, (int) x, (int) (baseline - mark * 0.78), mark, ACCENT);
        g.setColor(INK);
        g.drawString("yoru", (float) (x + mark + size * 0.18), (float) baseline);
    }

    static BufferedImage hero(BufferedImage landscape, BufferedImage today) {
        int w = 2000, h = 900;
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        quality(g);
        double s = Math.max(w / (double) landscape.getWidth(), h / (double) landscape.getHeight());
        double lw = landscape.getWidth() * s, lh = landscape.getHeight() * s;
        g.drawImage(landscape, (int) ((w - lw) * 0.7), (int) ((h - lh) / 2), (int) lw, (int) lh, null);
        g.setPaint(new GradientPaint(0, 0, alpha(NIGHT, 0.85), (float) (w * 0.62), 0, alpha(NIGHT, 0.15)));
        g.fillRect(0, 0, w, h);
        wordmark(g, 120, 330, 150);
        g.setColor(INK);
        g.setFont(font("AvenirNext-DemiBold", 50));
        g.drawString("Study time that", 126, 450);
        g.drawString("turns into play.", 126, 512);
        g.setColor(MUTED);
        g.setFont(font("AvenirNext-Medium", 29));
        g.drawString("Timer, tasks, schedule, habits and Anki,", 128, 590);
        g.drawString("in one local-first desktop app.", 128, 632);
        // The app, floating off the right edge.
        var shot = today.getSubimage(0, 0, today.getWidth(), (int) (860 * SCALE));
        int ww = 1120, wh = (int) Math.round(shot.getHeight() * (double) ww / shot.getWidth());
        window(g, shot, 900, 150, ww, wh, 26);
        g.dispose();
        return out;
    }

}
