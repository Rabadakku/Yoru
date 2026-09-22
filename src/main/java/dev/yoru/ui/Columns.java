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
        revalidate(); repaint();
    }

    /** In the vertical box each card takes its own height; the box pads nothing. */
    private static void holdToOwnHeight(JComponent card) {
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE,card.getPreferredSize().height));
    }
}

