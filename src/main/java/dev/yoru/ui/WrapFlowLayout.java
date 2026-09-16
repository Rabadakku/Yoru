package dev.yoru.ui;

import java.awt.*;

/**
 * A FlowLayout that asks for the height of every line it wraps onto (#30).
 *
 * FlowLayout moves what does not fit onto further lines, but still asks its
 * container for a single line's height, so everything after the first line was
 * laid out below the container's edge and never drawn. At the window's minimum
 * width the focus card's Edit timer button vanished that way.
 *
 * Lines break exactly where FlowLayout breaks them. The width wrapped at is the
 * container's own once it has one, and otherwise the width of what holds it.
 */
final class WrapFlowLayout extends FlowLayout {
    WrapFlowLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

    @Override public Dimension preferredLayoutSize(Container target) { return size(target, true); }

    /** As narrow as the widest single component, and as tall as the lines that makes. */
    @Override public Dimension minimumLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            var insets = target.getInsets();
            int widest = 0;
            for (var c : target.getComponents()) if (c.isVisible()) widest = Math.max(widest, c.getMinimumSize().width);
            return new Dimension(widest + insets.left + insets.right + 2 * getHgap(), size(target, false).height);
        }
    }

    private Dimension size(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int width = target.getWidth();
            for (Container holder = target.getParent(); width == 0 && holder != null; holder = holder.getParent())
                width = holder.getWidth();
            var insets = target.getInsets();
            int room = width == 0 ? Integer.MAX_VALUE : width - insets.left - insets.right - 2 * getHgap();
            int line = 0, lineHeight = 0, widest = 0, height = 0;
            for (var c : target.getComponents()) {
                if (!c.isVisible()) continue;
                var d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (line > 0 && line + getHgap() + d.width > room) {
                    widest = Math.max(widest, line);
                    height += lineHeight + getVgap();
                    line = 0;
                    lineHeight = 0;
                }
                line += (line > 0 ? getHgap() : 0) + d.width;
                lineHeight = Math.max(lineHeight, d.height);
            }
            widest = Math.max(widest, line);
            height += lineHeight;
            return new Dimension(widest + insets.left + insets.right + 2 * getHgap(),
                height + insets.top + insets.bottom + 2 * getVgap());
        }
    }
}
