package dev.yoru.ui;

import dev.yoru.domain.Model.Tag;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import static dev.yoru.ui.Theme.*;

/**
 * How a task's tags are drawn on the board (#66): Notion's select pills, as
 * many as the cell has room for, then "+2" for the rest.
 */
final class TagChips {
    private TagChips() { }

    /** The text after the last pill that fits: how many are not shown. */
    static String more(int hidden) { return "+" + hidden; }

    /**
     * A colour washed over the panel, for a pill whose text stays in the body ink.
     *
     * The body ink measured at least 6:1 on every status and palette tag wash on
     * all four themes, where coloured text on a grey pill had needed a darker
     * shade on each light theme just to reach 4.5:1.
     */
    static Color wash(Color colour) {
        double a = DARK ? 0.30 : 0.24;
        return new Color((int) Math.round(colour.getRed() * a + PANEL.getRed() * (1 - a)),
            (int) Math.round(colour.getGreen() * a + PANEL.getGreen() * (1 - a)),
            (int) Math.round(colour.getBlue() * a + PANEL.getBlue() * (1 - a)));
    }

    /**
     * A task's tag cell: its pills, drawn to the width it is given, and a
     * control, so the tags can be changed from the row without opening the
     * task (#66). With no tags it is empty until the pointer or focus is on it.
     */
    static final class Cell extends JButton {
        private final List<Tag> tags;
        private boolean over;
        /** How many pills the last paint had room for, so a test can ask. */
        private int shown;

        /** What the cell holds, for its tooltip and screen reader: "Tags", or a property's name (#68). */
        private final String noun;
        /** What an empty cell offers on hover. */
        private final String hint;

        Cell(List<Tag> tags, Runnable edit) { this(tags, edit, "Tags", "+ tag"); }

        /** Pills of any kind that look like tags: a select property's options (#68). */
        Cell(List<Tag> tags, Runnable edit, String noun, String hint) {
            this.tags = List.copyOf(tags);
            this.noun = noun;
            this.hint = hint;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(new EmptyBorder(0, 0, 0, 0));
            setFont(captionFont());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            var names = String.join(", ", this.tags.stream().map(Tag::name).toList());
            boolean tagged = noun.equals("Tags");
            setToolTipText(this.tags.isEmpty() ? (tagged ? "Add a tag" : "Choose " + noun) : noun + ": " + names + " · click to change");
            getAccessibleContext().setAccessibleName(this.tags.isEmpty()
                ? (tagged ? "No tags. Activate to add one" : noun + ": none. Activate to choose")
                : noun + ": " + names + ". Activate to change " + (tagged ? "them" : "it"));
            addActionListener(e -> edit.run());
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { over = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { over = false; repaint(); }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
                @Override public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
        }

        List<Tag> tags() { return tags; }

        /** How many of its tags the cell drew whole or shortened; the rest are in "+n". */
        int shown() { return shown; }

        private int padX() { return SPACE_SM; }
        private int pillHeight(FontMetrics m) { return m.getAscent() + m.getDescent() + 2 * RING; }

        @Override public Dimension getPreferredSize() {
            var m = getFontMetrics(getFont());
            int width = 0;
            for (var tag : tags) width += (width > 0 ? SPACE_XS : 0) + m.stringWidth(tag.name()) + 2 * padX();
            if (tags.isEmpty()) width = m.stringWidth(hint) + 2 * padX();
            return new Dimension(width, pillHeight(m) + 2 * RING);
        }

        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            var m = g.getFontMetrics();
            int h = pillHeight(m), y = (getHeight() - h) / 2, x = 0;
            if (tags.isEmpty()) {
                shown = 0;
                if (over || isFocusOwner()) {
                    g.setColor(MUTED);
                    g.drawString(hint, padX(), y + RING + m.getAscent());
                }
                ring(g);
                g.dispose();
                return;
            }
            shown = 0;
            for (int i = 0; i < tags.size(); i++) {
                var tag = tags.get(i);
                int left = tags.size() - i - 1;
                // Room this pill may take: the rest of the cell, less a "+n" pill
                // for whatever still follows it.
                int reserve = left == 0 ? 0 : SPACE_XS + m.stringWidth(more(left)) + 2 * padX();
                int room = getWidth() - x - reserve;
                int whole = m.stringWidth(tag.name()) + 2 * padX();
                String text = tag.name();
                boolean cut = false;
                if (whole > room) {
                    // The first pill shortens rather than vanish; a later one
                    // that does not fit goes into the count instead.
                    if (i > 0) { drawMore(g, m, x, y, h, tags.size() - i); break; }
                    text = shorten(m, tag.name(), room - 2 * padX());
                    whole = m.stringWidth(text) + 2 * padX();
                    cut = true;
                }
                g.setColor(wash(new Color(tag.colour())));
                g.fillRoundRect(x, y, whole, h, RADIUS, RADIUS);
                g.setColor(TEXT);
                g.drawString(text, x + padX(), y + RING + m.getAscent());
                shown++;
                x += whole + SPACE_XS;
                if (cut && left > 0) { drawMore(g, m, x, y, h, left); break; }
            }
            ring(g);
            g.dispose();
        }

        private void drawMore(Graphics2D g, FontMetrics m, int x, int y, int h, int hidden) {
            String text = more(hidden);
            int w = m.stringWidth(text) + 2 * padX();
            g.setColor(LINE);
            g.fillRoundRect(x, y, w, h, RADIUS, RADIUS);
            g.setColor(TEXT);
            g.drawString(text, x + padX(), y + RING + m.getAscent());
        }

        /** The focus ring every control has, drawn round the pills. */
        private void ring(Graphics2D g) {
            if (!isFocusOwner()) return;
            g.setColor(ringFor(PANEL));
            g.setStroke(new BasicStroke(RING));
            g.drawRoundRect(1, 1, getWidth() - RING, getHeight() - RING, RADIUS, RADIUS);
        }

        private static String shorten(FontMetrics m, String text, int room) {
            if (m.stringWidth(text) <= room) return text;
            String ellipsis = "…";
            int end = text.length();
            while (end > 0 && m.stringWidth(text.substring(0, end) + ellipsis) > room) end--;
            return text.substring(0, end) + ellipsis;
        }
    }
}
