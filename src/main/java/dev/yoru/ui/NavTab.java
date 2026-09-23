package dev.yoru.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import static dev.yoru.ui.Theme.*;

/** A quiet, keyboard-accessible destination in the workspace sidebar. */
final class NavTab extends JButton {
    private boolean current;
    private final Color ground;
    NavTab(String text, Color ground) {
        super(text);
        this.ground = ground;
        setFont(labelFont()); setHorizontalAlignment(LEFT);
        setIcon(Glyphs.of(switch (text) {
            case "Today" -> Glyphs.Kind.TODAY;
            case "Tasks" -> Glyphs.Kind.TASK;
            case "Pages" -> Glyphs.Kind.PAGE;
            case "Habits" -> Glyphs.Kind.HABIT;
            case "Schedule" -> Glyphs.Kind.CALENDAR;
            case "Data" -> Glyphs.Kind.CHART;
            default -> Glyphs.Kind.SETTINGS;
        }, MUTED));
        setIconTextGap(SPACE_MD);
        setBorder(new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD));
        setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
        setRolloverEnabled(true); getModel().addChangeListener(e -> repaint());
        setForeground(TEXT); setAlignmentX(0);
    }
    void setCurrent(boolean next) { current = next; repaint(); }
    boolean isCurrent() { return current; }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (current || getModel().isRollover() || getModel().isPressed()) {
            g.setColor(shade(ground, DARK ? (current ? 22 : 12) : (current ? -17 : -8)));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
        }
        if (Theme.focused(this)) {
            g.setColor(ringFor(ground)); g.setStroke(new BasicStroke(RING));
            g.drawRoundRect(RING / 2, RING / 2, getWidth() - RING, getHeight() - RING, 10, 10);
        }
        g.dispose(); super.paintComponent(graphics);
    }
}
