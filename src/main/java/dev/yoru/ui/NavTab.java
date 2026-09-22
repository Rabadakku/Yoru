package dev.yoru.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import static dev.yoru.ui.Theme.*;

/**
 * One destination in the top bar, drawn as a pill.
 *
 * The strip was bare labels twelve pixels apart with an underline under the
 * open page, and it read as one run of words rather than eight places to go.
 * Each tab now has room around it, the open page is a filled pill in the
 * accent, and the tab under the pointer is a quieter fill, so where you are
 * and where you are pointing are both visible at a glance.
 *
 * The keyboard ring is drawn here, outside the pill, the same width and colour
 * rule as every other control's, since this button paints itself.
 */
final class NavTab extends JButton {
    /**
     * The space inside each pill and between pills, when the window has room;
     * and the least they shrink to when it does not. The text never gets
     * less than the least: only the air around it gives way.
     */
    static final int PAD_H = SPACE_LG, PAD_V = SPACE_SM, MIN_PAD = SPACE_SM;
    static final int GAP = SPACE_XS + 2, MIN_GAP = 2;

    private final Color ground;
    private boolean current, hovered;

    NavTab(String text, Color ground) {
        super(text);
        this.ground = ground;
        setFont(labelFont());
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        // The border holds the least padding; the rest is width the strip hands
        // out, with the label centred in it.
        setBorder(new EmptyBorder(PAD_V, MIN_PAD, PAD_V, MIN_PAD));
        setHorizontalAlignment(SwingConstants.CENTER);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
        });
        restyle();
    }

    void setCurrent(boolean current) {
        if (this.current == current) return;
        this.current = current;
        restyle();
    }

    boolean isCurrent() { return current; }

    /** Roomy: the full padding either side. */
    @Override public Dimension getPreferredSize() {
        var d = super.getPreferredSize();
        return new Dimension(d.width + 2 * (PAD_H - MIN_PAD), d.height);
    }

    /** Tight: the least padding, which still holds the whole label. */
    @Override public Dimension getMinimumSize() { return super.getPreferredSize(); }

    /**
     * Lays the tabs out in one row, roomy when the bar has the width and
     * tightening evenly when it does not, so a narrow window never pushes a tab
     * off the end — which a FlowLayout did, quietly, to Settings.
     */
    static final class Strip implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component c) { }
        @Override public void removeLayoutComponent(Component c) { }

        private static Dimension size(Container parent, boolean roomy) {
            int w = 0, h = 0, n = 0;
            for (var c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                var d = roomy ? c.getPreferredSize() : c.getMinimumSize();
                w += d.width; h = Math.max(h, d.height); n++;
            }
            var in = parent.getInsets();
            return new Dimension(w + Math.max(0, n - 1) * (roomy ? GAP : MIN_GAP) + in.left + in.right, h + in.top + in.bottom);
        }

        @Override public Dimension preferredLayoutSize(Container parent) { return size(parent, true); }
        @Override public Dimension minimumLayoutSize(Container parent) { return size(parent, false); }

        @Override public void layoutContainer(Container parent) {
            var in = parent.getInsets();
            int avail = parent.getWidth() - in.left - in.right, height = parent.getHeight() - in.top - in.bottom;
            var roomy = size(parent, true);
            var tight = size(parent, false);
            int spanRoomy = roomy.width - in.left - in.right, spanTight = tight.width - in.left - in.right;
            // 1 when there is room for everything, 0 at the tightest, between in between.
            double t = spanRoomy <= spanTight ? 1 : Math.max(0, Math.min(1, (avail - spanTight) / (double) (spanRoomy - spanTight)));
            // Positions are kept exact and only the edges are rounded. Rounding
            // each tab's width on its own let nine roundings-up add a handful of
            // pixels to the row, which is how the last tab — Settings — ran past
            // the end of a bar that was wide enough for it (#46).
            double at = in.left, gap = MIN_GAP + t * (GAP - MIN_GAP);
            boolean first = true;
            for (var c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                if (!first) at += gap;
                first = false;
                var min = c.getMinimumSize();
                var pref = c.getPreferredSize();
                double width = min.width + t * (pref.width - min.width);
                int left = (int) Math.round(at), right = (int) Math.round(at + width);
                int h = Math.min(height, pref.height);
                c.setBounds(left, in.top + (height - h) / 2, right - left, h);
                at += width;
            }
        }
    }

    private void restyle() {
        setForeground(current ? ACCENT_TEXT : hovered ? TEXT : MUTED);
        repaint();
    }

    /** The open page's pill: the accent, faint enough that its text still reads on it. */
    static Color currentFill() {
        return new Color(ACCENT_TEXT.getRed(), ACCENT_TEXT.getGreen(), ACCENT_TEXT.getBlue(), DARK ? 46 : 40);
    }

    /** The pointed-at pill: a step off the bar, no accent. */
    Color hoverFill() { return shade(ground, DARK ? 14 : -12); }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight(), arc = h;
        boolean pressed = getModel().isArmed() && getModel().isPressed();
        if (current || hovered || pressed) {
            g.setColor(current ? currentFill() : pressed ? shade(hoverFill(), DARK ? 8 : -8) : hoverFill());
            g.fillRoundRect(RING, RING, w - 2 * RING, h - 2 * RING, arc, arc);
        }
        if (Theme.focused(this)) {
            g.setColor(ringFor(current ? ground : hoverFill()));
            g.setStroke(new BasicStroke(RING));
            g.drawRoundRect(RING / 2, RING / 2, w - RING, h - RING, arc, arc);
        }
        g.dispose();
        // Hovering changes only the text colour, so keep it current while painting.
        setForeground(current ? ACCENT_TEXT : hovered ? TEXT : MUTED);
        super.paintComponent(graphics);
    }
}
