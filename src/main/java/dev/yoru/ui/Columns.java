package dev.yoru.ui;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * Two cards side by side, stacked when there is not room for both.
 *
 * Yoru's pages pair things that answer each other — the clock and what is
 * next, the streaks and the trackers — and a fixed two-column grid broke the
 * page below about 900 px rather than reflowing.
 */
final class Columns extends JPanel {
    private static final int STACK_BELOW=760;
    private final JComponent left,right;
    private boolean stacked;
Columns(JComponent left,JComponent right) {
        this.left=left; this.right=right;
        setOpaque(false);
        setAlignmentX(0);
        apply(false);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { apply(getWidth()<STACK_BELOW); }
        });
    }
    @Override public void doLayout() {
        apply(getWidth() < STACK_BELOW);
        // Held when the cards were stacked, which is before their rows had a
        // width. A row that then moves its figures under its name grows, and
        // a card held to its old height cut off the time-since counter and its
        // controls at 125% text in the smallest window.
        if (stacked) {
            holdToOwnHeight(left);
            holdToOwnHeight(right);
            ((LayoutManager2) getLayout()).invalidateLayout(this);
        }
        super.doLayout();
    }

    /**
     * Measured in the arrangement its width calls for, once it has one.
     *
     * The switch otherwise happened during layout, after the page had already
     * set aside the height of the other arrangement: stacked columns were
     * squeezed into the height of side-by-side ones until another pass came.
     */
    @Override public Dimension getPreferredSize() {
        if (getWidth() > 0) apply(getWidth() < STACK_BELOW);
        return super.getPreferredSize();
    }

    private void apply(boolean stack) {
        if(getComponentCount()>0&&stack==stacked)return;
        stacked=stack;
        removeAll();
        if(stack) {
            // A vertical box, not a 2x1 grid: a grid forces both rows to the
            // same height, so the shorter card was padded with empty space
            // instead of being the size its own content asks for.
            setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));
            holdToOwnHeight(left);
            holdToOwnHeight(right);
            add(left);
            add(Box.createVerticalStrut(SPACE_LG));
            add(right);
        } else {
            setLayout(new GridLayout(1,2,SPACE_LG,0));
            add(left); add(right);
        }
        // What holds this measured it in the other arrangement, and a box
        // layout keeps what it measured until it is told otherwise.
        for (Container holder = getParent(); holder != null; holder = holder.getParent())
            if (holder.getLayout() instanceof LayoutManager2 cached) cached.invalidateLayout(holder);
        revalidate(); repaint();
    }

    /** In the vertical box each card takes its own height; the box pads nothing. */
    private static void holdToOwnHeight(JComponent card) {
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE,card.getPreferredSize().height));
    }
}

