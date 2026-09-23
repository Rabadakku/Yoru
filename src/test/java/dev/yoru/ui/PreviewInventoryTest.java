package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * The review renders are all there (#10).
 *
 * Preview skips a render quietly when the control that reaches it is missing —
 * no Arrange button, no Calendar view — and a page that stopped rendering at one
 * size or in one theme looked the same as one nobody had looked at. This renders
 * everything and holds the output to the inventory: every page and view, in
 * every theme, at both sizes, each file a real image of the right size that is
 * not a blank fill, and nothing unexpected beside them.
 */
public final class PreviewInventoryTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final int WIDTH = 1280, HEIGHT = 1000, MIN_WIDTH = 900, MIN_HEIGHT = 640;
    private static final List<String> VIEWS = List.of("today", "tasks", "pages", "pages-reading", "habits", "schedule",
        "data", "settings", "tasks-calendar", "tasks-bulk");

    public static void main(String[] args) {
        // Preview's windows leave timers running on the event thread, so this
        // exits explicitly either way: a failure must not leave the suite hanging.
        try {
            run();
            System.out.println("PASS: " + checks + " preview inventory checks (" + VIEWS.size() + " views, "
                + ThemeId.values().length + " themes, two sizes, none blank, none extra)");
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run() throws Exception {
        Path out = Files.createTempDirectory("yoru-preview-inventory");
        try {
            Preview.renderAll(out, WIDTH, HEIGHT);
            var expected = new TreeSet<String>();
            for (var theme : ThemeId.values())
                for (String view : VIEWS)
                    for (String size : new String[]{"", "-min"})
                        expected.add(view + "-" + theme.name().toLowerCase(Locale.ROOT) + size + ".png");
            Set<String> written;
            try (Stream<Path> files = Files.list(out)) {
                written = new TreeSet<>(files.map(p -> p.getFileName().toString()).toList());
            }
            var missing = new TreeSet<>(expected);
            missing.removeAll(written);
            check(missing.isEmpty(), "every view renders in every theme at both sizes; missing " + missing);
            var extra = new TreeSet<>(written);
            extra.removeAll(expected);
            check(extra.isEmpty(), "and nothing is rendered that the inventory does not know about: " + extra);

            for (String name : expected) {
                var image = ImageIO.read(out.resolve(name).toFile());
                check(image != null, name + " is a readable image");
                boolean small = name.endsWith("-min.png");
                check(image.getWidth() == (small ? MIN_WIDTH : WIDTH) && image.getHeight() == (small ? MIN_HEIGHT : HEIGHT),
                    name + " is rendered at its size, got " + image.getWidth() + "x" + image.getHeight());
                var colours = new HashSet<Integer>();
                for (int y = 0; y < image.getHeight() && colours.size() < 64; y += 7)
                    for (int x = 0; x < image.getWidth() && colours.size() < 64; x += 7) colours.add(image.getRGB(x, y));
                check(colours.size() >= 16, name + " has content rather than a blank fill: " + colours.size() + " colours");
            }
        } finally {
            try (Stream<Path> files = Files.list(out)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(out);
        }
    }
}
