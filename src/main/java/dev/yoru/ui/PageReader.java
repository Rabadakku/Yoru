package dev.yoru.ui;

import dev.yoru.pages.Markdown;
import dev.yoru.pages.Markdown.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import static dev.yoru.ui.Theme.*;

/**
 * A page read rather than edited: its Markdown drawn as it means, not as it is
 * written, in the app's own type and colours.
 *
 * Built from Swing components rather than an HTML view, because the HTML view
 * understands too little CSS to follow Yoru's themes, and components can take
 * the text size, the focus ring and the keyboard like everything else.
 * Checkboxes tick the page itself; links open what they name.
 */
final class PageReader {
    private PageReader() { }

    interface Host {
        /** Whether a link's target is a page that exists. */
        boolean resolves(String target);
        /** Opens what a link names; a link to no page creates it. */
        void follow(Span link, boolean elsewhere);
        /** Ticks or unticks the checkbox whose state character sits at this offset. */
        void check(int offset, boolean done);
        /** An embedded page, drawn in place, or null when it cannot be: missing, or already being drawn. */
        JComponent embed(Span embed);
    }

    /** The whole page, top to bottom, as components. */
    static JPanel render(Markdown.Doc doc, Host host) {
        var column = new Theme.VerticalPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(new EmptyBorder(SPACE_MD, SPACE_XL, SPACE_XXL, SPACE_XL));
        blocks(column, doc, doc.blocks(), host, 0);
        if (doc.blocks().isEmpty()) {
            var empty = label("This page is empty. Switch to editing to write in it.", TYPE_BODY, MUTED);
            column.add(empty);
        }
        return column;
    }

    private static void blocks(JPanel into, Markdown.Doc doc, List<Block> blocks, Host host, int depth) {
        String text = doc.text();
        Block previous = null;
        for (var b : blocks) {
            if (b.kind() == BlockKind.COMMENT) continue;
            if (previous != null) into.add(Box.createVerticalStrut(spaceBetween(previous, b)));
            JComponent part = switch (b.kind()) {
                case HEADING -> heading(doc, b, host);
                case PARAGRAPH -> {
                    // A paragraph that is only ![[Page]] shows the page itself, as Obsidian does.
                    var only = b.spans().size() == 1 ? b.spans().getFirst() : null;
                    JComponent embedded = only != null && only.kind() == SpanKind.EMBED
                        && text.substring(b.contentStart(), b.contentEnd()).strip().length() == only.end() - only.start()
                        ? host.embed(only) : null;
                    yield embedded != null ? embedded
                        : inline(text, b.contentStart(), b.contentEnd(), b.spans(), bodyFont(), TEXT, host);
                }
                case LIST_ITEM -> listItem(doc, b, host);
                case QUOTE -> quote(doc, b, host, depth);
                case CALLOUT -> callout(doc, b, host, depth);
                case CODE -> code(text, b);
                case MATH -> math(text, b);
                case TABLE -> table(doc, b, host);
                case RULE -> rule();
                case FRONTMATTER -> properties(text, b);
                case FOOTNOTE -> footnote(doc, b, host);
                default -> null;
            };
            if (part == null) continue;
            part.setAlignmentX(0);
            into.add(part);
            previous = b;
        }
    }

    private static int spaceBetween(Block previous, Block next) {
        if (previous.kind() == BlockKind.LIST_ITEM && next.kind() == BlockKind.LIST_ITEM) return SPACE_XS;
        if (next.kind() == BlockKind.HEADING) return next.level() <= 2 ? SPACE_XL : SPACE_LG;
        return SPACE_MD;
    }

    // ---------------------------------------------------------------- blocks

    private static JComponent heading(Markdown.Doc doc, Block b, Host host) {
        float factor = switch (b.level()) { case 1 -> 1.75f; case 2 -> 1.45f; case 3 -> 1.25f; case 4 -> 1.1f; default -> 1f; };
        var font = sans(Math.round(TYPE_PROSE * factor)).deriveFont(Font.BOLD);
        var pane = inline(doc.text(), b.contentStart(), b.contentEnd(), b.spans(), font, TEXT, host);
        pane.setName("page.heading." + doc.headingText(b));
        if (b.level() <= 2) {
            var box = new JPanel(new BorderLayout());
            box.setOpaque(false);
            box.add(pane, BorderLayout.CENTER);
            box.setBorder(new CompoundBorder(new MatteBorder(0, 0, HAIRLINE, 0, LINE), new EmptyBorder(0, 0, SPACE_XS, 0)));
            return box;
        }
        return pane;
    }

    private static JComponent listItem(Markdown.Doc doc, Block b, Host host) {
        var row = new JPanel(new BorderLayout(SPACE_SM, 0));
        row.setOpaque(false);
        // A level is the marker's indent in columns, a tab counting four; two
        // spaces and a tab both read as one step in.
        row.setBorder(new EmptyBorder(0, grow(SPACE_LG) * ((b.level() + 2) / 4), 0, 0));
        String text = doc.text();
        boolean done = b.check() == 'x' || b.check() == 'X';
        JComponent marker;
        if (b.check() != 0) {
            var box = new JCheckBox();
            box.setOpaque(false);
            box.setSelected(b.check() != ' ');
            // The state character sits just before the content: "- [x] text".
            int at = text.lastIndexOf('[', b.contentStart() - 1) + 1;
            box.addActionListener(e -> host.check(at, box.isSelected()));
            box.getAccessibleContext().setAccessibleName("Done: " + text.substring(b.contentStart(), b.contentEnd()).strip());
            marker = box;
        } else {
            String m = b.info().matches("\\d+[.)]") ? b.info() : "•";
            var bullet = label(m, TYPE_PROSE, ACCENT_TEXT);
            bullet.setVerticalAlignment(SwingConstants.TOP);
            marker = bullet;
        }
        var top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(marker, BorderLayout.NORTH);
        row.add(top, BorderLayout.WEST);
        row.add(inline(text, b.contentStart(), b.contentEnd(), b.spans(), bodyFont(), done ? MUTED : TEXT, host), BorderLayout.CENTER);
        return row;
    }

    private static JComponent quote(Markdown.Doc doc, Block b, Host host, int depth) {
        var box = new Theme.VerticalPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(new CompoundBorder(new MatteBorder(0, RING + 1, 0, 0, LINE), new EmptyBorder(SPACE_XS, SPACE_LG, SPACE_XS, 0)));
        blocks(box, doc, b.children(), host, depth + 1);
        return box;
    }

    /** Obsidian's callout colours, mapped onto the theme's own. */
    private static Color calloutColour(String type) {
        return switch (type) {
            case "warning", "caution", "attention" -> GOLD;
            case "danger", "error", "failure", "fail", "missing", "bug" -> DANGER;
            case "tip", "hint", "important", "success", "check", "done" -> CYAN;
            case "example", "question", "help", "faq" -> PURPLE;
            default -> ACCENT_TEXT;
        };
    }

    private static JComponent callout(Markdown.Doc doc, Block b, Host host, int depth) {
        var colour = calloutColour(b.info());
        var box = new Theme.VerticalPanel() {
            @Override protected void paintComponent(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), DARK ? 30 : 26));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS * 2, RADIUS * 2);
                g.setColor(colour);
                g.fillRoundRect(0, 0, RING + 1, getHeight(), RING, RING);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(new EmptyBorder(SPACE_MD, SPACE_LG, SPACE_MD, SPACE_LG));
        String text = doc.text();
        String title = text.substring(b.contentStart(), b.contentEnd()).strip();
        var head = new JPanel(new BorderLayout(SPACE_SM, 0));
        head.setOpaque(false);
        head.setAlignmentX(0);
        JComponent name = title.isEmpty()
            ? label(Character.toUpperCase(b.info().charAt(0)) + b.info().substring(1), TYPE_PROSE, colour)
            : inline(text, b.contentStart(), b.contentEnd(), b.spans(), bodyFont().deriveFont(Font.BOLD), colour, host);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        head.add(name, BorderLayout.CENTER);
        box.add(head);
        var body = new Theme.VerticalPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setAlignmentX(0);
        if (!b.children().isEmpty()) {
            body.add(Box.createVerticalStrut(SPACE_SM));
            blocks(body, doc, b.children(), host, depth + 1);
        }
        box.add(body);
        // A folded callout ("-") opens closed; "+" and none open.
        if (b.check() != 0) {
            var toggle = new JButton(b.check() == '-' ? "▸" : "▾");
            toggle.setContentAreaFilled(false);
            toggle.setBorder(new EmptyBorder(0, 0, 0, 0));
            toggle.setForeground(colour);
            toggle.setFont(labelFont());
            toggle.getAccessibleContext().setAccessibleName("Show or hide " + (title.isEmpty() ? b.info() : title));
            body.setVisible(b.check() != '-');
            toggle.addActionListener(e -> {
                body.setVisible(!body.isVisible());
                toggle.setText(body.isVisible() ? "▾" : "▸");
                box.revalidate();
            });
            head.add(toggle, BorderLayout.WEST);
        }
        return box;
    }

    private static JComponent code(String text, Block b) {
        var area = new JTextArea(text.substring(b.contentStart(), b.contentEnd()));
        area.setEditable(false);
        area.setFont(mono(TYPE_BODY));
        area.setForeground(TEXT);
        area.setBackground(MarkdownStyler.codeFill());
        area.setBorder(new EmptyBorder(SPACE_MD, SPACE_LG, SPACE_MD, SPACE_LG));
        area.setLineWrap(false);
        area.getAccessibleContext().setAccessibleName(b.info() == null ? "Code" : b.info() + " code");
        var scroll = new JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(new LineBorder(LINE));
        scroll.getViewport().setBackground(area.getBackground());
        var size = area.getPreferredSize();
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height + grow(SPACE_LG)));
        if (b.info() == null) return scroll;
        var box = new JPanel(new BorderLayout());
        box.setOpaque(false);
        var lang = label(b.info(), TYPE_CAPTION, MUTED);
        lang.setBorder(new EmptyBorder(0, 0, SPACE_XS, 0));
        box.add(lang, BorderLayout.NORTH);
        box.add(scroll, BorderLayout.CENTER);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        return box;
    }

    private static JComponent math(String text, Block b) {
        var l = label(text.substring(b.contentStart(), Math.max(b.contentStart(), b.contentEnd())).strip(), TYPE_PROSE, TEXT);
        l.setFont(mono(TYPE_PROSE));
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    private static JComponent table(Markdown.Doc doc, Block b, Host host) {
        var grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        grid.setBorder(new LineBorder(LINE));
        String[] aligns = b.info().split(",");
        var at = new GridBagConstraints();
        at.fill = GridBagConstraints.BOTH;
        at.weightx = 1;
        int r = 0;
        for (var row : b.children()) {
            int c = 0;
            for (var cell : row.children()) {
                at.gridx = c; at.gridy = r;
                Font font = r == 0 ? bodyFont().deriveFont(Font.BOLD) : bodyFont();
                var pane = inline(doc.text(), cell.contentStart(), cell.contentEnd(), cell.spans(), font, TEXT, host);
                if (c < aligns.length) {
                    var style = new SimpleAttributeSet();
                    StyleConstants.setAlignment(style, switch (aligns[c]) {
                        case "c" -> StyleConstants.ALIGN_CENTER; case "r" -> StyleConstants.ALIGN_RIGHT; default -> StyleConstants.ALIGN_LEFT; });
                    pane.getStyledDocument().setParagraphAttributes(0, pane.getDocument().getLength(), style, false);
                }
                var holder = new JPanel(new BorderLayout());
                holder.setBackground(r == 0 ? shade(PANEL, DARK ? 6 : -6) : PANEL);
                holder.setBorder(new CompoundBorder(new MatteBorder(r == 0 ? 0 : HAIRLINE, c == 0 ? 0 : HAIRLINE, 0, 0, LINE),
                    new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD)));
                holder.add(pane);
                grid.add(holder, at);
                c++;
            }
            r++;
        }
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        return grid;
    }

    private static JComponent rule() {
        var line = new JPanel();
        line.setBackground(LINE);
        line.setPreferredSize(new Dimension(1, HAIRLINE));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, HAIRLINE));
        return line;
    }

    /** Frontmatter shown as what it is: the page's properties. */
    private static JComponent properties(String text, Block b) {
        var box = new Theme.VerticalPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD)));
        box.add(label("PROPERTIES", TYPE_SECTION, ACCENT_TEXT));
        for (String line : text.substring(b.contentStart(), b.contentEnd()).split("\n")) {
            if (line.isBlank()) continue;
            var l = wrapping(line, TYPE_CAPTION, MUTED);
            l.setFont(mono(TYPE_CAPTION));
            box.add(l);
        }
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        return box;
    }

    private static JComponent footnote(Markdown.Doc doc, Block b, Host host) {
        var row = new JPanel(new BorderLayout(SPACE_SM, 0));
        row.setOpaque(false);
        row.add(label(b.info() + ".", TYPE_CAPTION, MUTED), BorderLayout.WEST);
        row.add(inline(doc.text(), b.contentStart(), b.contentEnd(), b.spans(), captionFont(), MUTED, host), BorderLayout.CENTER);
        return row;
    }

    // ---------------------------------------------------------------- inline

    private static final int BOLD = 1, ITALIC = 2, STRIKE = 4, MARK = 8, CODE = 16, LINK = 32, TAG = 64, QUIET = 128, MISSING = 256;

    /**
     * [from, to) of the text as a wrapping, read-only pane: markers hidden,
     * emphasis drawn, links clickable. Built a character at a time from flags,
     * which is what lets emphasis inside a link's label, or a link inside a
     * callout's title, come out right without special cases.
     */
    static WrapPane inline(String text, int from, int to, List<Span> spans, Font font, Color ink, Host host) {
        int n = Math.max(0, to - from);
        var flags = new int[n];
        var hidden = new boolean[n];
        var links = new Span[n];
        var inserts = new TreeMap<Integer, Map.Entry<String, Span>>();
        for (var s : spans) {
            int a = s.start() - from, e = s.end() - from, ca = s.contentStart() - from, ce = s.contentEnd() - from;
            switch (s.kind()) {
                case BOLD -> mark(flags, hidden, a, e, ca, ce, BOLD);
                case ITALIC -> mark(flags, hidden, a, e, ca, ce, ITALIC);
                case STRIKE -> mark(flags, hidden, a, e, ca, ce, STRIKE);
                case HIGHLIGHT -> mark(flags, hidden, a, e, ca, ce, MARK);
                case CODE -> mark(flags, hidden, a, e, ca, ce, CODE);
                case MATH -> mark(flags, hidden, a, e, ca, ce, CODE);
                case ESCAPE -> hide(hidden, a, ca);
                case COMMENT, BLOCK_ID -> hide(hidden, a, e);
                case WIKILINK, EMBED -> {
                    hide(hidden, a, e);
                    String shown = s.alias() != null && !s.alias().isEmpty() && s.kind() == SpanKind.WIKILINK ? s.alias()
                        : s.target().isEmpty() && s.anchor() != null ? s.anchor()
                        : s.anchor() != null && !s.anchor().startsWith("^") ? s.target() + " › " + s.anchor() : s.target();
                    if (s.kind() == SpanKind.EMBED) shown = "↪ " + shown;
                    inserts.put(a, Map.entry(shown, s));
                }
                case LINK -> {
                    hide(hidden, a, ca);
                    hide(hidden, ce, e);
                    for (int i = Math.max(0, ca); i < Math.min(n, ce); i++) { flags[i] |= LINK; links[i] = s; }
                }
                case IMAGE -> {
                    hide(hidden, a, e);
                    inserts.put(a, Map.entry("🖼 " + (s.alias() == null || s.alias().isBlank() ? s.target() : s.alias()), s));
                }
                case URL -> { for (int i = Math.max(0, a); i < Math.min(n, e); i++) { flags[i] |= LINK; links[i] = s; } }
                case TAG -> { for (int i = Math.max(0, a); i < Math.min(n, e); i++) flags[i] |= TAG; }
                case FOOTNOTE_REF -> { for (int i = Math.max(0, a); i < Math.min(n, e); i++) flags[i] |= QUIET; }
            }
        }
        var pane = new WrapPane();
        var doc = pane.getStyledDocument();
        try {
            int i = 0;
            while (i < n) {
                var insert = inserts.get(i);
                if (insert != null) {
                    var s = insert.getValue();
                    boolean missing = (s.kind() == SpanKind.WIKILINK || s.kind() == SpanKind.EMBED) && !s.target().isEmpty() && !host.resolves(s.target());
                    doc.insertString(doc.getLength(), insert.getKey(), style(font, ink, LINK | (missing ? MISSING : 0), s));
                }
                if (hidden[i]) { i++; continue; }
                int start = i;
                while (i < n && !hidden[i] && flags[i] == flags[start] && links[i] == links[start] && (i == start || !inserts.containsKey(i))) i++;
                doc.insertString(doc.getLength(), text.substring(from + start, from + i), style(font, ink, flags[start], links[start]));
            }
            var tail = inserts.get(n);
            if (tail != null) doc.insertString(doc.getLength(), tail.getKey(), style(font, ink, LINK, tail.getValue()));
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        pane.follow(host);
        return pane;
    }

    private static void mark(int[] flags, boolean[] hidden, int a, int e, int ca, int ce, int flag) {
        hide(hidden, a, ca);
        hide(hidden, ce, e);
        for (int i = Math.max(0, ca); i < Math.min(flags.length, ce); i++) flags[i] |= flag;
    }

    private static void hide(boolean[] hidden, int a, int e) {
        for (int i = Math.max(0, a); i < Math.min(hidden.length, e); i++) hidden[i] = true;
    }

    private static AttributeSet style(Font font, Color ink, int flags, Span link) {
        var a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, (flags & CODE) != 0 ? mono(TYPE_PROSE).getFamily() : font.getFamily());
        StyleConstants.setFontSize(a, font.getSize());
        StyleConstants.setBold(a, font.isBold() || (flags & BOLD) != 0);
        StyleConstants.setItalic(a, font.isItalic() || (flags & ITALIC) != 0);
        StyleConstants.setStrikeThrough(a, (flags & STRIKE) != 0);
        Color colour = ink;
        if ((flags & LINK) != 0) colour = (flags & MISSING) != 0 ? MUTED : ACCENT_TEXT;
        else if ((flags & TAG) != 0) colour = GOLD_TEXT;
        else if ((flags & QUIET) != 0) colour = MUTED;
        StyleConstants.setForeground(a, colour);
        StyleConstants.setUnderline(a, (flags & MISSING) != 0);
        if ((flags & MARK) != 0) StyleConstants.setBackground(a, MarkdownStyler.highlightFill());
        if ((flags & CODE) != 0) StyleConstants.setBackground(a, MarkdownStyler.codeFill());
        if (link != null) a.addAttribute(MarkdownStyler.LINK_TARGET, link);
        return a;
    }

    /**
     * A read-only text pane that wraps to the width it is given and asks for
     * exactly the height its lines need at that width, so it can sit in a
     * column like a label.
     */
    static final class WrapPane extends JTextPane {
        WrapPane() {
            setEditable(false);
            setOpaque(false);
            setBorder(null);
            setMargin(new Insets(0, 0, 0, 0));
            setFocusable(true);
            setAlignmentX(0);
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, false);
        }

        @Override public Dimension getPreferredSize() {
            int width = getWidth();
            if (width <= 0 && getParent() != null) width = getParent().getWidth();
            if (width <= 0) return super.getPreferredSize();
            var view = getUI().getRootView(this);
            view.setSize(width, Integer.MAX_VALUE);
            int height = (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS));
            return new Dimension(Math.min(width, super.getPreferredSize().width), height);
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override public Dimension getMinimumSize() { return new Dimension(0, super.getMinimumSize().height); }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        /**
         * A column asks for heights before it hands out widths, so the first
         * height this gave may be for the wrong width. Given its real width, it
         * asks for another layout when that changes how tall it needs to be.
         */
        @Override public void setBounds(int x, int y, int width, int height) {
            boolean resized = width != getWidth();
            super.setBounds(x, y, width, height);
            if (resized && width > 0 && getPreferredSize().height != height) SwingUtilities.invokeLater(this::revalidate);
        }

        /** Links in the pane open on click; the pointer says which text is a link. */
        void follow(Host host) {
            var mouse = new MouseAdapter() {
                private Span at(MouseEvent e) {
                    int offset = viewToModel2D(e.getPoint());
                    if (offset < 0) return null;
                    var element = getStyledDocument().getCharacterElement(offset);
                    return element.getAttributes().getAttribute(MarkdownStyler.LINK_TARGET) instanceof Span s ? s : null;
                }
                @Override public void mouseClicked(MouseEvent e) {
                    var link = at(e);
                    if (link != null && SwingUtilities.isLeftMouseButton(e))
                        host.follow(link, (e.getModifiersEx() & Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) != 0);
                }
                @Override public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(at(e) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }
    }

    /** A predicate from the reader's host, for callers that style rather than render. */
    static Predicate<String> resolving(Host host) { return host::resolves; }
}
