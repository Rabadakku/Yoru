package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/**
 * The mark at the sizes it has to survive, on all four grounds (#57).
 *
 *   java -cp build/classes dev.yoru.ui.LogoPreview <output-dir>
 *
 * 16 and 32 are the ones that matter: anything reads at 1024. Each size is also
 * written on its own, because a contact sheet is for reading and a file is for
 * looking at 1:1.
 */
public final class LogoPreview {
    private static final int[] MARK_SIZES = {16, 22, 32, 48, 64, 128, 256};
    private static final int[] ICON_SIZES = {16, 32, 64, 128, 256, 512, 1024};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/logo");
        Files.createDirectories(out);

        // Every size at 1:1, so the small ones can be judged as pixels.
        for (var id : ThemeId.values()) {
            Theme.apply(id);
            for (int size : MARK_SIZES) {
                var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                var g = image.createGraphics();
                Logo.mark(g, 0, 0, size, Theme.CYAN);
                g.dispose();
                ImageIO.write(image, "png", out.resolve("mark-" + id.name().toLowerCase() + "-" + size + ".png").toFile());
            }
        }
        for (boolean dark : new boolean[]{true, false}) {
            Theme.apply(ThemeId.MIDNIGHT);
            for (int size : ICON_SIZES) {
                ImageIO.write(Logo.appIcon(size, dark), "png",
                    out.resolve("icon-" + (dark ? "dark" : "light") + "-" + size + ".png").toFile());
            }
        }

        int pad = 22, row = 190, width = 1240;
        var sheet = new BufferedImage(width, row * (ThemeId.values().length + 2), BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int y = 0;
        for (var id : ThemeId.values()) {
            Theme.apply(id);
            g.setColor(Theme.BG);
            g.fillRect(0, y, width, row);
            g.setColor(Theme.MUTED);
            g.setFont(Theme.mono(12));
            g.drawString(id.name() + (Theme.DARK ? "  ·  dark" : "  ·  light") + "  ·  " + Theme.describe(id),
                pad, y + 20);

            // The bare mark, at the sizes the nav and the test use.
            int x = pad;
            for (int size : MARK_SIZES) {
                Logo.mark(g, x, y + 44, size, Theme.CYAN);
                g.setColor(Theme.MUTED);
                g.setFont(Theme.mono(9));
                g.drawString(size + "px", x, y + 44 + size + 12);
                x += size + 16;
            }

            // The app icon, both variants, as the OS would show them.
            x = pad;
            for (boolean dark : new boolean[]{true, false}) {
                var icon = Logo.appIcon(64, dark);
                g.drawImage(icon, x, y + 100, null);
                x += 80;
            }
            g.setColor(Theme.MUTED);
            g.setFont(Theme.mono(9));
            g.drawString("app icon 64px — night / moonlight", pad, y + 176);

            var lockup = Logo.lockup(26, Theme.CYAN);
            lockup.setSize(lockup.getPreferredSize());
            var strip = new BufferedImage(lockup.getWidth(), lockup.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var lg = strip.createGraphics();
            lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            lockup.paint(lg);
            lg.dispose();
            g.drawImage(strip, 460, y + 110, null);
            y += row;
        }

        // And the icon at every size, scaled to fit the sheet, both variants.
        for (boolean dark : new boolean[]{true, false}) {
            g.setColor(dark ? Color.WHITE : Color.WHITE);
            g.fillRect(0, y, width, row);
            g.setColor(Color.GRAY);
            g.setFont(Theme.mono(12));
            g.drawString("app icon — " + (dark ? "night" : "moonlight") + " — 16 to 1024, shown to scale below 128",
                pad, y + 20);
            int x = pad;
            for (int size : ICON_SIZES) {
                var icon = Logo.appIcon(size, dark);
                int shown = Math.min(size, 128);
                g.drawImage(icon, x, y + 46, shown, shown, null);
                g.setColor(Color.GRAY);
                g.setFont(Theme.mono(9));
                g.drawString(size + "px", x, y + 46 + shown + 12);
                x += shown + 16;
            }
            y += row;
        }
        g.dispose();
        ImageIO.write(sheet, "png", out.resolve("logo-sheet.png").toFile());
        System.out.println("Rendered the mark and both icon variants to " + out);
        System.exit(0);
    }
}
