package dev.yoru.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * The Yoru mark (#57).
 *
 * The night sky, drawn as geometry: one crescent, a disc with a second disc
 * bitten out of it, the bite angled up the diagonal so the horns point the way
 * a moon's do. The placeholder before #7 was the kanji 夜 in whatever sans-serif
 * the platform happened to have; #7 added a rising row of blocks to say
 * "streak", which the heat map already says better. This is the mark on its
 * own — one element, one ink, the shape the name actually means.
 *
 * Single-ink on purpose. Two of the four themes are light, and a mark that needs
 * a different drawing per theme is a mark that will drift; this one adapts by
 * swapping one colour. {@code LogoTest} asserts the contrast on all four grounds
 * rather than leaving it to the eye.
 *
 * The geometry is exact rather than hand-drawn: the bite's radius and offset are
 * the only two numbers, and the opening angle, the horns, the thickness and the
 * mark's own bounding box all follow from them, so the shape at 16px is the
 * shape at 1024px. Below {@link #PIXEL_CUTOFF} it is rasterised on the pixel
 * grid, because a horn thinner than a pixel renders as grey mush; at and above
 * it, it is filled as a vector path, which is what keeps 1024px smooth. Both
 * paths are exactly symmetric about the crescent's own axis, and the test pins
 * the mark down by that rather than by eye.
 *
 * The app icon is the same crescent on a night sky, inside Apple's icon grid:
 * a squircle body of 824/1024 of the canvas with continuous corners, a sky that
 * runs from night to horizon (or from moonlight to dusk, for the light variant),
 * a few pixel stars, and no bevel that would smear at 32px.
 */
final class Logo {
    private Logo() { }

    // ---------------------------------------------------------------- mark -- //

    // Two circles, and everything else follows. The bite sits out along the
    // up-right diagonal: put it on the horizontal instead and the mark reads as
    // a letter C; angle it and it reads as a moon.
    private static final double BITE_RADIUS = 0.80;  // of the outer radius
    private static final double BITE_OFFSET = 0.38;  // of the outer radius, from the centre
    private static final double BITE_ANGLE = -Math.PI / 4;

    /**
     * How much of the box the crescent's own bounding box fills.
     *
     * The disc is larger than the mark and hangs off two corners: only the
     * crescent is centred. A mark that kept the circle's margins instead would
     * look undersized next to the wordmark it sits beside.
     */
    private static final double INK = 0.94;

    /** Below this the mark is rasterised; at and above it, antialiased. */
    static final int PIXEL_CUTOFF = 24;

    /** Samples per axis in the pixel path: four means sixteen samples a pixel. */
    private static final int SUBSAMPLES = 4;

    /** The crescent's bounding box in the unit frame. Derived, never typed in. */
    private static final Rectangle2D EXTENT = extent();

    /**
     * Draws the mark into a size×size box with its top-left at (x, y).
     * Nothing is filled behind it, so it composes onto any ground.
     */
    static void mark(Graphics2D graphics, int x, int y, int size, Color ink) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        g.setColor(ink);
        // Scale the crescent's own box to the mark box, then place the disc so
        // that box lands centred. A half-pixel either way is visible at 16px,
        // which is why this is done from the derived extent, not by eye.
        double scale = INK * size / Math.max(EXTENT.getWidth(), EXTENT.getHeight());
        double cx = (size - EXTENT.getWidth() * scale) / 2 - EXTENT.getX() * scale;
        double cy = (size - EXTENT.getHeight() * scale) / 2 - EXTENT.getY() * scale;
        if (size < PIXEL_CUTOFF) pixels(g, size, scale, cx, cy);
        else g.fill(crescent(scale, cx, cy));
        g.dispose();
    }

    /** The crescent as a path: the outer disc minus the bite. */
    private static Area crescent(double scale, double cx, double cy) {
        var moon = new Area(new Ellipse2D.Double(cx - scale, cy - scale, scale * 2, scale * 2));
        double bx = cx + BITE_OFFSET * scale * Math.cos(BITE_ANGLE);
        double by = cy + BITE_OFFSET * scale * Math.sin(BITE_ANGLE);
        double br = BITE_RADIUS * scale;
        moon.subtract(new Area(new Ellipse2D.Double(bx - br, by - br, br * 2, br * 2)));
        return moon;
    }

    /**
     * The mark on the pixel grid, with no antialiasing at all.
     *
     * Down here the crescent is a dozen pixels across and its horns are under a
     * pixel wide. Antialiasing those spreads them into a grey haze that reads as
     * a smudge and, at some sizes, breaks the horn off the body entirely; a hard
     * edge keeps the silhouette the shape actually has. Each pixel is sampled
     * 4×4 and lit when the shape covers half of it, which is the same rule a
     * crisp pixel font uses.
     */
    private static void pixels(Graphics2D g, int size, double scale, double cx, double cy) {
        double bx = cx + BITE_OFFSET * scale * Math.cos(BITE_ANGLE);
        double by = cy + BITE_OFFSET * scale * Math.sin(BITE_ANGLE);
        double br = BITE_RADIUS * scale, rr = scale * scale, brr = br * br;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                int hits = 0;
                for (int sy = 0; sy < SUBSAMPLES; sy++) {
                    for (int sx = 0; sx < SUBSAMPLES; sx++) {
                        double px = column + (sx + 0.5) / SUBSAMPLES;
                        double py = row + (sy + 0.5) / SUBSAMPLES;
                        double dx = px - cx, dy = py - cy;
                        if (dx * dx + dy * dy > rr) continue;
                        double ex = px - bx, ey = py - by;
                        if (ex * ex + ey * ey < brr) continue;
                        hits++;
                    }
                }
                if (hits * 2 >= SUBSAMPLES * SUBSAMPLES) g.fillRect(column, row, 1, 1);
            }
        }
    }

    /**
     * The crescent's bounding box in the unit frame — the outer circle at radius
     * one, the bite at {@link #BITE_OFFSET} along {@link #BITE_ANGLE}.
     *
     * Its boundary is the outer arc on the closed side and the bite arc on the
     * open one, so the box is the outer arc's extent between the horns: the two
     * horn tips plus whichever of the four cardinal points still lie on the arc.
     * Working it out here means the mark stays centred and correctly scaled if
     * the bite is ever moved, instead of silently drifting inside a box someone
     * typed in once.
     */
    private static Rectangle2D extent() {
        double half = Math.acos((BITE_OFFSET * BITE_OFFSET + 1 - BITE_RADIUS * BITE_RADIUS)
            / (2 * BITE_OFFSET));
        double from = BITE_ANGLE + half, to = BITE_ANGLE + 2 * Math.PI - half;
        var box = new Rectangle2D.Double();
        boolean first = true;
        for (int corner = 0; corner < 6; corner++) {
            double angle;
            if (corner == 0) angle = from;               // the two horn tips
            else if (corner == 1) angle = to;
            else {                                       // then the four cardinals
                double at = (corner - 2) * Math.PI / 2;
                double spin = at;
                while (spin < from) spin += 2 * Math.PI;
                if (spin > to) continue;
                angle = at;
            }
            double px = Math.cos(angle), py = Math.sin(angle);
            if (first) { box.setRect(px, py, 0, 0); first = false; }
            else box.add(px, py);
        }
        return box;
    }

    // ----------------------------------------------------------- app icon -- //

    /** Apple's icon grid: the body is 824 of the canvas's 1024, and centred. */
    static final double BODY = 824.0 / 1024.0;

    /** The body's corner is continuous, not a circular arc. Five matches it. */
    private static final double BODY_POWER = 5.0;

    /** The mark inside the body, so the sky has a margin to be sky in. */
    private static final double MARK = 0.74;

    /** Below this the sky is left empty: a star that is one pixel is a speck. */
    private static final int STARS_SHOWN = 32;

    /**
     * The stars, in body coordinates from its top-left, with their relative
     * size. They sit up and to the right, in the crescent's open side, which is
     * the only part of the body the mark leaves as sky at this size.
     */
    private static final double[][] STARS = {{0.735, 0.185, 1.0}, {0.812, 0.315, 0.62}, {0.648, 0.335, 0.55}};

    /** The sky's two ends. The accent is mixed into both, so a theme tints it. */
    private static final Color NIGHT = new Color(0x0B1020), HORIZON = new Color(0x2E4C7A);
    private static final Color MOON_SKY = new Color(0xF3F7FE), MOON_DUSK = new Color(0xC9DAEE);

    /**
     * The app icon: the crescent on a night sky, in a squircle on Apple's grid.
     *
     * Two variants, because the Dock is not always dark: {@code dark} is night
     * over a horizon glow, and the light one is moonlight over dusk. Both are
     * tinted by the active palette, so switching theme moves the icon with it.
     */
    static BufferedImage appIcon(int size, boolean dark) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double body = size * BODY, inset = (size - body) / 2;
        var shape = squircle(inset, body, BODY_POWER);

        g.setPaint(new GradientPaint(0f, (float) inset, skyGround(dark, 0),
            0f, (float) (inset + body), skyGround(dark, 1)));
        g.fill(shape);

        // A sheet of light across the top, clipped to the body. It reads as
        // glass at 1024 and is gone by 32, which is the point of fading it.
        var sheen = (Graphics2D) g.create();
        sheen.clip(shape);
        sheen.setPaint(new GradientPaint(0f, (float) inset, new Color(255, 255, 255, 40),
            0f, (float) (inset + body * 0.45), new Color(255, 255, 255, 0)));
        sheen.fillRect((int) inset, (int) inset, (int) Math.ceil(body), (int) Math.ceil(body * 0.45));
        sheen.dispose();

        if (size >= STARS_SHOWN) stars(g, inset, body, dark);

        double box = body * MARK;
        int side = Math.max(4, (int) Math.round(box)), at = (int) Math.round(inset + (body - side) / 2);
        mark(g, at, at, side, skyInk(dark));
        g.dispose();
        return image;
    }

    /** A superellipse body: the macOS corner is smooth where a round corner kinks. */
    private static Path2D squircle(double inset, double body, double power) {
        var path = new Path2D.Double();
        double a = body / 2, cx = inset + a, cy = inset + a;
        for (int step = 0; step <= 256; step++) {
            double angle = 2 * Math.PI * step / 256, cos = Math.cos(angle), sin = Math.sin(angle);
            double px = cx + a * Math.signum(cos) * Math.pow(Math.abs(cos), 2 / power);
            double py = cy + a * Math.signum(sin) * Math.pow(Math.abs(sin), 2 / power);
            if (step == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.closePath();
        return path;
    }

    /** Four-point sparkles. Drawn, so they stay round at 1024 and square at 32. */
    private static void stars(Graphics2D g, double inset, double body, boolean dark) {
        g.setColor(starInk(dark));
        for (var star : STARS) {
            double r = Math.max(1, Math.round(body * 0.012 * star[2])), k = r * 0.28;
            double cx = inset + star[0] * body, cy = inset + star[1] * body;
            var path = new Path2D.Double();
            double[][] points = {{0, -r}, {k, -k}, {r, 0}, {k, k}, {0, r}, {-k, k}, {-r, 0}, {-k, -k}};
            for (int i = 0; i < points.length; i++) {
                double px = cx + points[i][0], py = cy + points[i][1];
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.closePath();
            g.fill(path);
        }
    }

    /**
     * The icon's ground, {@code at} being how far down the body in 0..1.
     *
     * Package-private so the test can measure the ink against the ground it is
     * actually drawn on, at every height, rather than against the palette.
     */
    static Color skyGround(boolean dark, double at) {
        double t = Math.max(0, Math.min(1, at));
        var sky = dark ? mix(NIGHT, HORIZON, t) : mix(MOON_SKY, MOON_DUSK, t);
        return mix(sky, Theme.CYAN, dark ? 0.16 : 0.12);
    }

    /** The crescent's ink in the icon, bright on night and deep on moonlight. */
    static Color skyInk(boolean dark) {
        return dark ? mix(Color.WHITE, Theme.CYAN, 0.30) : mix(Theme.CYAN, NIGHT, 0.62);
    }

    /** Where the stars are, in body units. Read-only: the test counts them. */
    static double[][] stars() { return STARS.clone(); }

    /** The stars' colour, alpha included, bright over night and deep over moonlight. */
    static Color starInk(boolean dark) {
        var tint = dark ? mix(Color.WHITE, Theme.CYAN, 0.25) : mix(NIGHT, Theme.CYAN, 0.30);
        return new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 205);
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(
            (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
            (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    // -------------------------------------------------------------- lockup -- //

    /**
     * The lockup: mark then wordmark, in the interface's own mono face.
     *
     * The letterforms are the app's typeface, not drawn — the app is a terminal
     * aesthetic and a bespoke typeface would fight it as well as being a far
     * larger job. What is original here is the mark and the arrangement.
     */
    static Lockup lockup(int height, Color ink) { return new Lockup(height, ink); }

    /** A drawn lockup that reports its own width, so callers can lay it out. */
    static final class Lockup extends javax.swing.JPanel {
        private final int box;
        private final Color ink;
        private final String word = "yoru";

        Lockup(int height, Color ink) {
            this.box = height;
            this.ink = ink;
            setOpaque(false);
            var metrics = getFontMetrics(Theme.mono(Math.round(height * 0.78f)));
            int width = box + Math.round(height * 0.42f) + metrics.stringWidth(word);
            setPreferredSize(new Dimension(width, height));
            setMaximumSize(new Dimension(width, height));
            setAlignmentX(0);
            getAccessibleContext().setAccessibleName("Yoru");
        }

        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            mark(g, 0, 0, box, ink);
            g.setColor(ink);
            g.setFont(Theme.mono(Math.round(box * 0.78f)));
            var metrics = g.getFontMetrics();
            g.drawString(word, box + Math.round(box * 0.42f),
                (box - metrics.getHeight()) / 2 + metrics.getAscent());
            g.dispose();
        }
    }
}
