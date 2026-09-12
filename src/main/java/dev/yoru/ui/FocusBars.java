package dev.yoru.ui;

import java.awt.*;
import java.util.List;
import javax.swing.JPanel;

/**
 * Where the time actually went: one proportional bar per activity, longest
 * first (#6).
 *
 * "How much did I work" and "what did I work on" are different questions, and
 * until now Yoru only answered the first. The 14-day chart and the heat map
 * both total everything together, so a week spent entirely on one subject and a
 * week split evenly across four look identical.
 *
 * The bar length here is the share and nothing else. FocusPomo, which this is
 * modelled on, widens a short bar to fit the percentage it prints inside it —
 * so on that screen a 22% bar reads as about a third of the 78% one rather than
 * a quarter. Putting the label outside the track costs a little width and lets
 * the bars stay honest.
 */
final class FocusBars extends JPanel {

    /** One row, already reduced to what drawing needs. */
    record Row(String name, long seconds, double share) { }

    // Derived from the shared scale rather than a private one: a row height
    // that exists nowhere else is the kind of number that drifts on its own.
    static final int ROW_HEIGHT = Theme.SPACE_XL + Theme.SPACE_XS, BAR_HEIGHT = Theme.SPACE_MD,
        NAME_WIDTH = Theme.SPACE_XXL * 5, TAIL_WIDTH = Theme.SPACE_XXL * 5, GAP = Theme.SPACE_MD;

    private final List<Row> rows;

    FocusBars(List<Row> rows) {
        this.rows = rows;
        setOpaque(false);
        int height = Math.max(ROW_HEIGHT, rows.size() * ROW_HEIGHT);
        setPreferredSize(new Dimension(720, height));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        setAlignmentX(LEFT_ALIGNMENT);
        // The breakdown is drawn, not written, so without this the whole chart
        // is a blank rectangle to a screen reader.
        getAccessibleContext().setAccessibleName("Time by activity");
        getAccessibleContext().setAccessibleDescription(describe(rows));
    }

    /** The same rows the bars draw, said in words: "Maths 1h 30m 75% · Prose 30m 25%". */
    static String describe(List<Row> rows) {
        if (rows.isEmpty()) return "No time recorded yet.";
        var said = new StringBuilder();
        for (Row row : rows) {
            if (!said.isEmpty()) said.append(" · ");
            said.append(row.name()).append(' ')
                .append(dev.yoru.application.Analytics.report(row.seconds())).append(' ')
                .append(percent(row.share()));
        }
        return said.toString();
    }

    List<Row> rows() { return rows; }

    /**
     * Width available to the bars themselves, once the name and the figures
     * either side have taken theirs. Floored so that a narrow window shortens
     * the bars rather than inverting them.
     */
    static int track(int width) {
        return Math.max(40, width - NAME_WIDTH - TAIL_WIDTH - GAP * 2);
    }

    /**
     * Pixel length of one bar, proportional to its share.
     *
     * A share above zero never rounds away to nothing: time that was recorded
     * has to leave a mark, or a five-minute activity in a forty-hour week
     * simply vanishes from the chart it is supposed to appear in.
     */
    static int barWidth(double share, int track) {
        if (share <= 0) return 0;
        return Math.max(1, (int) Math.round(share * track));
    }

    /** Colour for the row at a given rank, cycling the theme's own heat bands. */
    static Color colourFor(int rank) {
        return Theme.HEAT[1 + Math.floorMod(rank, Theme.HEAT.length - 1)];
    }

    /**
     * Share as a percentage, never rounded to "0%" while it is still above
     * zero — a row that is drawn has to be labelled with something truthful.
     */
    static String percent(double share) {
        long whole = Math.round(share * 100);
        if (whole == 0 && share > 0) return "<1%";
        return whole + "%";
    }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int trackWidth = track(getWidth());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = i * ROW_HEIGHT;
            int middle = y + ROW_HEIGHT / 2;

            g.setFont(Theme.bodyFont());
            g.setColor(Theme.TEXT);
            g.drawString(clip(g, row.name(), NAME_WIDTH - 8), 0, middle + 4);

            int barX = NAME_WIDTH + GAP;
            g.setColor(Theme.LINE);
            g.fillRoundRect(barX, middle - BAR_HEIGHT / 2, trackWidth, BAR_HEIGHT, Theme.RADIUS, Theme.RADIUS);
            g.setColor(colourFor(i));
            g.fillRoundRect(barX, middle - BAR_HEIGHT / 2, barWidth(row.share(), trackWidth), BAR_HEIGHT,
                Theme.RADIUS, Theme.RADIUS);

            int tailX = barX + trackWidth + GAP;
            g.setColor(Theme.TEXT);
            g.drawString(percent(row.share()), tailX, middle + 4);
            g.setColor(Theme.MUTED);
            String time = dev.yoru.application.Analytics.duration(row.seconds());
            g.drawString(time, tailX + 56, middle + 4);
        }
        g.dispose();
    }

    /** Truncates a name that will not fit, rather than letting it run under the bar. */
    private static String clip(Graphics2D g, String text, int width) {
        var metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= width) return text;
        // Step back a whole grapheme at a time, not a char: a name whose tail
        // is an emoji or an accent must not end on an unpaired surrogate.
        var characters = java.text.BreakIterator.getCharacterInstance();
        characters.setText(text);
        int end = characters.last();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > width) end = characters.previous();
        return text.substring(0, end) + "…";
    }
}
