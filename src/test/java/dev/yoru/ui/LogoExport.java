package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/**
 * Draws the mark out to the files the installers need (#57).
 *
 *   java -cp build/classes dev.yoru.ui.LogoExport [output-dir]
 *
 * The drawing in {@link Logo} is the source of truth; these are build products,
 * and none of them is committed. This writes the app icon at Apple's sizes as
 * PNG, the same images under the names an {@code .iconset} is made of, and a
 * multi-image {@code .ico}. Run it through {@code tools/export-logo-icons.sh},
 * which also turns the iconset into a {@code .icns} with the OS's own iconutil.
 *
 * It lives in the test tree with the other off-screen harnesses so the shipped
 * jar carries the drawing rather than a copy of what it drew.
 */
public final class LogoExport {
    /** Apple's icon sizes. Past 512 is what a Retina Dock asks for at 2x. */
    static final int[] SIZES = {16, 32, 64, 128, 256, 512, 1024};

    /** The .ico sizes. 48 is the classic shell size and is not a power of two. */
    static final int[] ICO_SIZES = {16, 32, 48, 64, 128, 256};

    /** The names iconutil reads, each with the pixel size behind it. */
    private static final Object[][] ICONSET = {
        {"icon_16x16", 16}, {"icon_16x16@2x", 32}, {"icon_32x32", 32}, {"icon_32x32@2x", 64},
        {"icon_128x128", 128}, {"icon_128x128@2x", 256}, {"icon_256x256", 256},
        {"icon_256x256@2x", 512}, {"icon_512x512", 512}, {"icon_512x512@2x", 1024}};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/icons");
        Files.createDirectories(out);
        // The icon ships in the default palette; a theme tints what the app shows
        // at runtime, which is the point of drawing it rather than shipping a file.
        Theme.apply(ThemeId.MIDNIGHT);

        for (boolean dark : new boolean[]{true, false}) {
            String variant = dark ? "night" : "moonlight";
            Path iconset = Files.createDirectories(out.resolve("Yoru-" + variant + ".iconset"));
            for (int size : SIZES) {
                write(Logo.appIcon(size, dark), out.resolve("yoru-" + variant + "-" + size + ".png"));
                // And the bare mark with nothing behind it, for anything that
                // composes it onto a ground of its own.
                var mark = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                var g = mark.createGraphics();
                Logo.mark(g, 0, 0, size, Logo.skyInk(dark));
                g.dispose();
                write(mark, out.resolve("yoru-mark-" + variant + "-" + size + ".png"));
            }
            for (Object[] image : ICONSET) write(Logo.appIcon((Integer) image[1], dark),
                iconset.resolve(image[0] + ".png"));
            ico(out.resolve("yoru-" + variant + ".ico"), dark);
            System.out.println("wrote the " + variant + " icon: " + SIZES.length + " sizes as PNG, "
                + ICONSET.length + " iconset images, and an .ico of " + ICO_SIZES.length);
        }

        System.out.println("output: " + out.toAbsolutePath());
        System.out.println("an .icns is one more step, on macOS: "
            + "iconutil -c icns " + out.resolve("Yoru-night.iconset"));
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        ImageIO.write(image, "png", file.toFile());
    }

    /**
     * A Windows .ico: a directory of entries, each one a whole PNG.
     *
     * PNG entries are the Vista-and-later form. They are what a 256px entry has
     * to be anyway, and every Windows since XP SP2 reads them, so there is no
     * reason to carry a second encoder for the small ones.
     *
     * Written by hand rather than through a DataOutputStream, because the format
     * is little-endian and everything in java.io is not. Getting that wrong
     * produces a file that looks right in a hex dump and is garbage to Windows,
     * which is why {@code LogoTest} reads it back.
     */
    static void ico(Path file, boolean dark) throws IOException {
        var images = new byte[ICO_SIZES.length][];
        for (int i = 0; i < ICO_SIZES.length; i++) {
            var buffer = new ByteArrayOutputStream();
            ImageIO.write(Logo.appIcon(ICO_SIZES[i], dark), "png", buffer);
            images[i] = buffer.toByteArray();
        }
        var out = new ByteArrayOutputStream();
        out.write(0); out.write(0);                  // reserved
        out.write(1); out.write(0);                  // 1 is an icon, 2 a cursor
        out.write(ICO_SIZES.length & 0xFF); out.write(ICO_SIZES.length >> 8);
        int offset = 6 + 16 * ICO_SIZES.length;      // the directory, then the images
        for (int i = 0; i < ICO_SIZES.length; i++) {
            // 256 is written as 0: the field is one byte and 256 does not fit.
            int size = ICO_SIZES[i] == 256 ? 0 : ICO_SIZES[i];
            out.write(size); out.write(size);
            out.write(0); out.write(0);              // palette size, 0 for truecolour
            out.write(1); out.write(0);              // colour planes
            out.write(32); out.write(0);             // bits a pixel
            little(out, images[i].length);
            little(out, offset);
            offset += images[i].length;
        }
        for (byte[] image : images) out.write(image, 0, image.length);
        Files.write(file, out.toByteArray());
    }

    /** Four bytes, least significant first. */
    private static void little(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
