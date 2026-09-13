package dev.yoru.ui;

import java.awt.*;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;

/** Plain text with width-dependent height; retains JLabel's accessibility and labelFor. */
final class WrappingLabel extends JLabel {
    WrappingLabel(String text) { super(text); }

    private int availableWidth() {
        var parent = getParent();
        if (parent != null && parent.getWidth() > 0) {
            var insets = parent.getInsets();
            return Math.max(1, parent.getWidth() - insets.left - insets.right);
        }
        return 480;
    }

    private List<TextLayout> lines(int width) {
        var result = new ArrayList<TextLayout>();
        String text = getText();
        if (text == null || text.isEmpty()) return result;
        var context = getFontMetrics(getFont()).getFontRenderContext();
        for (String paragraph : text.split("\\n", -1)) {
            var attributed = new AttributedString(paragraph.isEmpty() ? " " : paragraph);
            attributed.addAttribute(TextAttribute.FONT, getFont());
            var iterator = attributed.getIterator();
            var measure = new LineBreakMeasurer(iterator, context);
            while (measure.getPosition() < iterator.getEndIndex())
                result.add(measure.nextLayout(Math.max(1, width)));
        }
        return result;
    }

    @Override public Dimension getPreferredSize() {
        int width = availableWidth();
        float height = 0;
        for (var line : lines(width)) height += line.getAscent() + line.getDescent() + line.getLeading();
        return new Dimension(width, Math.max(getFontMetrics(getFont()).getHeight(), (int)Math.ceil(height)));
    }

    @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setColor(getForeground());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float y = 0;
        for (var line : lines(getWidth())) {
            y += line.getAscent();
            line.draw(g, 0, y);
            y += line.getDescent() + line.getLeading();
        }
        g.dispose();
    }
}
