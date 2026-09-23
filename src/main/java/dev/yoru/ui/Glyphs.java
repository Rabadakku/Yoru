package dev.yoru.ui;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.Icon;
import static dev.yoru.ui.Theme.*;

/**
 * Small line icons for the Pages screen, drawn rather than shipped: the app
 * bundles no images, and a drawn icon takes the theme's colour and the text
 * size like the words beside it.
 */
final class Glyphs {
    private Glyphs() { }

    enum Kind { TODAY, HABIT, CALENDAR, CHART, SETTINGS, PAGE, FOLDER, FOLDER_OPEN, PAGE_PLUS, FOLDER_PLUS, BACK, FORWARD, SORT, COLLAPSE, SEARCH, TRASH, FILES, SIDEBAR, CHEVRON_RIGHT, CHEVRON_DOWN, OUTLINE, LINK, TASK }

    /** An icon of this kind, drawn in {@code ink} at the label font's size. */
    static Icon of(Kind kind, Color ink) { return new Line(kind, ink, grow(16)); }

    private record Line(Kind kind, Color ink, int size) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            float s = size / 16f;
            g.scale(s, s);
            g.setColor(c != null && !c.isEnabled() ? DISABLED_TEXT : ink);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case TODAY -> {
                    g.draw(new Ellipse2D.Float(3,3,10,10));
                    g.draw(new Line2D.Float(8,0,8,1)); g.draw(new Line2D.Float(8,15,8,16));
                    g.draw(new Line2D.Float(0,8,1,8)); g.draw(new Line2D.Float(15,8,16,8));
                }
                case HABIT -> {
                    g.draw(new Arc2D.Float(2,2,12,12,40,280,Arc2D.OPEN));
                    g.draw(new Line2D.Float(5,8,7,10)); g.draw(new Line2D.Float(7,10,11,6));
                }
                case CALENDAR -> {
                    g.draw(new RoundRectangle2D.Float(2,3,12,11,2,2));
                    g.draw(new Line2D.Float(2,6,14,6)); g.draw(new Line2D.Float(5,1,5,4)); g.draw(new Line2D.Float(11,1,11,4));
                }
                case CHART -> {
                    g.draw(new Line2D.Float(3,13,3,8)); g.draw(new Line2D.Float(8,13,8,3)); g.draw(new Line2D.Float(13,13,13,6));
                }
                case SETTINGS -> {
                    g.draw(new Line2D.Float(2,4,14,4)); g.draw(new Line2D.Float(2,12,14,12));
                    g.setColor(c.getBackground()); g.fill(new Ellipse2D.Float(4,2,4,4)); g.fill(new Ellipse2D.Float(9,10,4,4));
                    g.setColor(ink); g.draw(new Ellipse2D.Float(4,2,4,4)); g.draw(new Ellipse2D.Float(9,10,4,4));
                }

                case PAGE -> page(g);
                case PAGE_PLUS -> { page(g); plus(g, 11, 11); }
                case FOLDER -> folder(g, false);
                case FOLDER_OPEN -> folder(g, true);
                case FOLDER_PLUS -> { folder(g, false); plus(g, 11.5f, 11); }
                case BACK -> g.draw(path(10, 3.5f, 5.5f, 8, 10, 12.5f));
                case FORWARD -> g.draw(path(6, 3.5f, 10.5f, 8, 6, 12.5f));
                case SORT -> {
                    g.draw(new Line2D.Float(3, 4, 13, 4));
                    g.draw(new Line2D.Float(3, 8, 10, 8));
                    g.draw(new Line2D.Float(3, 12, 7, 12));
                }
                case COLLAPSE -> {
                    g.draw(path(4.5f, 3, 8, 6, 11.5f, 3));
                    g.draw(path(4.5f, 13, 8, 10, 11.5f, 13));
                }
                case SEARCH -> {
                    g.draw(new Ellipse2D.Float(2.5f, 2.5f, 8.5f, 8.5f));
                    g.draw(new Line2D.Float(10, 10, 13.5f, 13.5f));
                }
                case TRASH -> {
                    g.draw(new Line2D.Float(2.5f, 4.5f, 13.5f, 4.5f));
                    g.draw(path(4, 4.5f, 5, 14, 11, 14, 12, 4.5f));
                    g.draw(new Line2D.Float(6.5f, 2.5f, 9.5f, 2.5f));
                }
                case FILES -> {
                    g.draw(new RoundRectangle2D.Float(2.5f, 2.5f, 11, 11, 2, 2));
                    g.draw(new Line2D.Float(5.5f, 6, 10.5f, 6));
                    g.draw(new Line2D.Float(5.5f, 9, 10.5f, 9));
                    g.draw(new Line2D.Float(5.5f, 12, 8.5f, 12));
                }
                case CHEVRON_RIGHT -> g.draw(path(6.5f, 4.5f, 10, 8, 6.5f, 11.5f));
                case CHEVRON_DOWN -> g.draw(path(4.5f, 6.5f, 8, 10, 11.5f, 6.5f));
                case OUTLINE -> {
                    g.draw(new Line2D.Float(2.5f, 4, 13.5f, 4));
                    g.draw(new Line2D.Float(5.5f, 8, 13.5f, 8));
                    g.draw(new Line2D.Float(8.5f, 12, 13.5f, 12));
                }
                case LINK -> {
                    g.draw(new RoundRectangle2D.Float(1.5f, 5.5f, 7.5f, 5, 5, 5));
                    g.draw(new RoundRectangle2D.Float(7, 5.5f, 7.5f, 5, 5, 5));
                }
                case TASK -> {
                    g.draw(new RoundRectangle2D.Float(2.5f, 2.5f, 11, 11, 3, 3));
                    g.draw(path(5, 8, 7.2f, 10.2f, 11, 6));
                }
                case SIDEBAR -> {
                    g.draw(new RoundRectangle2D.Float(2, 3, 12, 10, 2, 2));
                    g.draw(new Line2D.Float(10, 3, 10, 13));
                }
            }
            g.dispose();
        }

        private static void page(Graphics2D g) {
            g.draw(path(3.5f, 1.5f, 9.5f, 1.5f, 12.5f, 4.5f, 12.5f, 14.5f, 3.5f, 14.5f, 3.5f, 1.5f));
            g.draw(path(9.5f, 1.5f, 9.5f, 4.5f, 12.5f, 4.5f));
        }

        private static void folder(Graphics2D g, boolean open) {
            if (open) {
                g.draw(path(1.5f, 12.5f, 1.5f, 3.5f, 6, 3.5f, 7.5f, 5, 12.5f, 5, 12.5f, 7));
                g.draw(path(1.5f, 12.5f, 4, 7, 14.5f, 7, 12.5f, 12.5f, 1.5f, 12.5f));
            } else {
                g.draw(path(1.5f, 12.5f, 1.5f, 3.5f, 6, 3.5f, 7.5f, 5, 14.5f, 5, 14.5f, 12.5f, 1.5f, 12.5f));
            }
        }

        private static void plus(Graphics2D g, float cx, float cy) {
            var fill = g.getColor();
            g.setColor(PANEL);
            g.fill(new Ellipse2D.Float(cx - 4, cy - 4, 8, 8));
            g.setColor(fill);
            g.draw(new Line2D.Float(cx - 2.5f, cy, cx + 2.5f, cy));
            g.draw(new Line2D.Float(cx, cy - 2.5f, cx, cy + 2.5f));
        }

        private static Path2D path(float... xy) {
            var p = new Path2D.Float();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i + 1 < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            return p;
        }
    }
}
