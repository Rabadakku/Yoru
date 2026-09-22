package dev.yoru.ui;

import dev.yoru.pages.Markdown;
import dev.yoru.pages.Markdown.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.*;
import static dev.yoru.ui.Theme.*;

/**
 * A page's Markdown, edited as text and drawn as it is written.
 *
 * The text is exactly what is saved: styling only changes how ranges are
 * drawn, never the characters. Typing is saved on its own a moment after it
 * stops, so there is no Save button to forget. Lists continue themselves,
 * [[ offers the pages it could link to, and Cmd/Ctrl-click follows a link.
 *
 * Undo keeps its own history of text changes rather than the document's.
 * The document's edits record its element structure, which restyling
 * rearranges underneath them, so undoing one after a restyle could corrupt
 * the text; a change recorded as "these characters, at this offset" cannot.
 */
final class PageEditor extends JPanel {
    /** What the editor needs from the screen it is on. */
    interface Host {
        boolean resolves(String target);
        void follow(Span link, boolean elsewhere);
        /** Pages a link could name, best first, for what has been typed after [[. */
        List<Choice> linkChoices(String typed);
        /** Saves the text; called a moment after typing stops, and on flush. */
        boolean save(String text);
        /** The text was parsed again: for the outline. */
        void parsed(Markdown.Doc doc);
    }

    /** One page offered after [[: its title, where it lives, and the link text that names it. */
    record Choice(String title, String where, String link) { }

    /** Lines wider than this are hard to read; the page centres in the space beyond it. */
    private static final int MEASURE = 720;
    private static final int SAVE_DELAY = 800, STYLE_DELAY = 60, JOIN_WINDOW = 1000;
    private static final Pattern LIST = Pattern.compile("^([ \\t]*)([-*+]|(\\d{1,9})([.)]))([ \\t]+)(\\[([ xX])\\][ \\t]+)?");
    private static final Pattern QUOTE = Pattern.compile("^((?:[ \\t]*>[ \\t]?)+)");

    private final Host host;
    private final JTextPane pane = new JTextPane() {
        // Wraps to the width it is given, like a page, rather than widening to its longest line.
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    };
    private final JScrollPane scroll;
    private final FindBar find = new FindBar();
    private final Timer restyle, autosave;
    private final Completion completion = new Completion();
    private Markdown.Doc doc = Markdown.parse("");
    private boolean loading, dirty;

    PageEditor(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);
        pane.setName("page.editor");
        pane.getAccessibleContext().setAccessibleName("Page text");
        pane.setFont(proseFont());
        pane.setBackground(PANEL);
        pane.setForeground(TEXT);
        pane.setCaretColor(TEXT);
        pane.setSelectionColor(shade(ACCENT_TEXT, DARK ? -60 : 70));
        pane.setSelectedTextColor(TEXT);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, false);
        ((AbstractDocument) pane.getDocument()).setDocumentFilter(history);
        scroll = new JScrollPane(pane, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(SPACE_XL);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { measure(); }
        });
        measure();
        add(find, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        restyle = new Timer(STYLE_DELAY, e -> style());
        restyle.setRepeats(false);
        autosave = new Timer(SAVE_DELAY, e -> flush());
        autosave.setRepeats(false);
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        pane.addCaretListener(e -> completion.follow());
        keys();
        links();
    }

    // ---------------------------------------------------------------- the page

    /** Shows this text, as if it had just been opened: no undo, nothing unsaved. */
    void load(String text) {
        restyle.stop();
        autosave.stop();
        completion.hide();
        loading = true;
        try {
            pane.setText(text);
        } finally {
            loading = false;
        }
        history.clear();
        dirty = false;
        style();
        pane.setCaretPosition(0);
        scroll.getViewport().setViewPosition(new Point());
    }

    String text() { return pane.getText(); }

    Markdown.Doc doc() { return doc; }

    boolean dirty() { return dirty; }

    /** Saves now whatever is waiting to be saved. */
    boolean flush() {
        autosave.stop();
        if (!dirty) return true;
        if (!host.save(text())) return false;
        dirty = false;
        return true;
    }

    void stop() { autosave.stop(); restyle.stop(); completion.hide(); }


    /** Selects [start, end) and scrolls it to the upper part of the view. */
    void reveal(int start, int end) {
        int length = pane.getDocument().getLength();
        start = Math.max(0, Math.min(start, length));
        end = Math.max(start, Math.min(end, length));
        pane.requestFocusInWindow();
        pane.select(start, end);
        try {
            var at = pane.modelToView2D(start);
            if (at != null) {
                var view = scroll.getViewport();
                int y = Math.max(0, (int) at.getY() - view.getHeight() / 4);
                int furthest = Math.max(0, pane.getHeight() - view.getHeight());
                view.setViewPosition(new Point(0, Math.min(y, furthest)));
            }
        } catch (BadLocationException ignored) { }
    }

    void focusText() { pane.requestFocusInWindow(); }

    JTextPane pane() { return pane; }

    /** Opens the find bar, seeded with the selection. */
    void find() { find.open(pane.getSelectedText()); }

    private void changed() {
        if (loading) return;
        dirty = true;
        restyle.restart();
        autosave.restart();
    }

    private void measure() {
        int width = scroll.getViewport().getWidth();
        int side = Math.max(grow(SPACE_XL), (width - grow(MEASURE)) / 2);
        var insets = pane.getInsets();
        if (insets.left == side && insets.top > 0) return;
        pane.setBorder(new EmptyBorder(grow(SPACE_XL), side, grow(SPACE_XXL) * 4, side));
    }

    private void style() {
        restyle.stop();
        String text = text();
        doc = Markdown.parse(text);
        var styled = pane.getStyledDocument();
        history.paused = true;
        try {
            MarkdownStyler.apply(styled, doc, pane.getFont(), host::resolves);
            var tabs = new SimpleAttributeSet();
            int tab = pane.getFontMetrics(pane.getFont()).charWidth(' ') * 4;
            var stops = new TabStop[40];
            for (int i = 0; i < stops.length; i++) stops[i] = new TabStop(tab * (i + 1));
            StyleConstants.setTabSet(tabs, new TabSet(stops));
            styled.setParagraphAttributes(0, styled.getLength(), tabs, false);
        } finally {
            history.paused = false;
        }
        find.refresh();
        host.parsed(doc);
    }

    // ---------------------------------------------------------------- undo

    private record Change(int offset, String removed, String inserted) { }

    /** The text changes, grouped so one undo takes back a run of typing rather than a letter. */
    private final History history = new History();

    private final class History extends DocumentFilter {
        private final Deque<List<Change>> undos = new ArrayDeque<>(), redos = new ArrayDeque<>();
        private long last;
        private boolean replaying, paused, together, breakNext;

        void clear() { undos.clear(); redos.clear(); breakNext = true; }

        @Override public void insertString(FilterBypass fb, int offset, String text, AttributeSet a) throws BadLocationException {
            record(offset, "", text);
            fb.insertString(offset, text, a);
        }

        @Override public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            record(offset, fb.getDocument().getText(offset, length), "");
            fb.remove(offset, length);
        }

        @Override public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet a) throws BadLocationException {
            record(offset, fb.getDocument().getText(offset, length), text == null ? "" : text);
            fb.replace(offset, length, text, a);
        }

        private void record(int offset, String removed, String inserted) {
            if (replaying || paused || loading || removed.isEmpty() && inserted.isEmpty()) return;
            redos.clear();
            long now = System.currentTimeMillis();
            var change = new Change(offset, removed, inserted);
            var group = undos.peek();
            boolean join = group != null && !breakNext && (together || now - last < JOIN_WINDOW && typing(group.getLast(), change));
            if (join) group.add(change);
            else {
                undos.push(new ArrayList<>(List.of(change)));
                while (undos.size() > 1000) undos.removeLast();
            }
            last = now;
            breakNext = false;
        }

        /** Whether this change carries on a run of typing or deleting at the same place. */
        private static boolean typing(Change before, Change next) {
            if (next.removed().length() > 1 || next.inserted().length() > 1) return false;
            if (before.removed().length() > 1 || before.inserted().length() > 1) return false;
            // A word ends at a space: the next word is its own undo.
            if (next.inserted().equals(" ") || next.inserted().equals("\n")) return false;
            int end = before.offset() + before.inserted().length();
            return next.offset() == end || next.offset() + next.removed().length() == before.offset();
        }

        /** Runs several edits as one undo step. */
        void together(Runnable edits) {
            breakNext = true;
            together = true;
            try {
                edits.run();
            } finally {
                together = false;
                breakNext = true;
            }
        }

        void undo() { step(undos, redos, true); }
        void redo() { step(redos, undos, false); }

        private void step(Deque<List<Change>> from, Deque<List<Change>> to, boolean back) {
            var group = from.poll();
            if (group == null) { Toolkit.getDefaultToolkit().beep(); return; }
            var document = pane.getDocument();
            replaying = true;
            int caret = 0;
            try {
                if (back) {
                    for (int i = group.size() - 1; i >= 0; i--) {
                        var c = group.get(i);
                        document.remove(c.offset(), c.inserted().length());
                        document.insertString(c.offset(), c.removed(), null);
                        caret = c.offset() + c.removed().length();
                    }
                } else {
                    for (var c : group) {
                        document.remove(c.offset(), c.removed().length());
                        document.insertString(c.offset(), c.inserted(), null);
                        caret = c.offset() + c.inserted().length();
                    }
                }
            } catch (BadLocationException e) {
                // The history no longer matches the text; start it again rather than guess.
                clear();
                return;
            } finally {
                replaying = false;
            }
            to.push(group);
            breakNext = true;
            pane.setCaretPosition(Math.min(caret, document.getLength()));
        }
    }

    // ---------------------------------------------------------------- keys

    private void keys() {
        int menu = (GraphicsEnvironment.isHeadless() ? InputEvent.CTRL_DOWN_MASK : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "yoru.undo", history::undo);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK), "yoru.redo", history::redo);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "yoru.redo2", history::redo);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "yoru.newline", this::newline);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "yoru.indent", () -> indent(true));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "yoru.outdent", () -> indent(false));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_B, menu), "yoru.bold", () -> wrap("**"));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_I, menu), "yoru.italic", () -> wrap("*"));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_H, menu | InputEvent.SHIFT_DOWN_MASK), "yoru.highlight", () -> wrap("=="));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_X, menu | InputEvent.SHIFT_DOWN_MASK), "yoru.strike", () -> wrap("~~"));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_QUOTE, menu), "yoru.code", () -> wrap("`"));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_K, menu), "yoru.link", this::markdownLink);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_L, menu), "yoru.check", this::toggleCheck);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menu), "yoru.check2", this::toggleCheck);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), "yoru.follow", () -> followAt(pane.getCaretPosition(), false));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_H, menu), "yoru.replace", this::find);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), "yoru.find", this::find);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, menu), "yoru.findNext", () -> find.step(true));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, menu | InputEvent.SHIFT_DOWN_MASK), "yoru.findPrevious", () -> find.step(false));
        // The completion list sees its keys before the text does.
        pane.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { if (completion.key(e)) e.consume(); }
        });
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        pane.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        pane.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private String content() {
        try {
            return pane.getDocument().getText(0, pane.getDocument().getLength());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int lineStart(String text, int at) { return at == 0 ? 0 : text.lastIndexOf('\n', at - 1) + 1; }

    private static int lineEnd(String text, int at) {
        int n = text.indexOf('\n', at);
        return n < 0 ? text.length() : n;
    }

    private void edit(int offset, int length, String text) {
        try {
            ((AbstractDocument) pane.getDocument()).replace(offset, length, text, null);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Enter continues what the line is: a list gets its next marker, a
     * numbered list its next number, a checklist an open box, a quote its >.
     * Enter on an empty item ends the list instead, as Obsidian does.
     */
    private void newline() {
        if (pane.getSelectionStart() != pane.getSelectionEnd() || completion.showing()) { pane.replaceSelection("\n"); return; }
        String text = content();
        int caret = pane.getCaretPosition();
        int start = lineStart(text, caret), end = lineEnd(text, caret);
        String line = text.substring(start, end);
        String next = null, lead = "";
        int markerEnd = 0;
        Matcher m = LIST.matcher(line);
        if (m.lookingAt()) {
            markerEnd = m.end();
            lead = m.group(1);
            String marker = m.group(3) != null ? (Long.parseLong(m.group(3)) + 1) + m.group(4) : m.group(2);
            next = lead + marker + m.group(5) + (m.group(6) != null ? "[ ] " : "");
        } else if ((m = QUOTE.matcher(line)).lookingAt() && !m.group(1).isBlank()) {
            markerEnd = m.end();
            next = m.group(1);
        }
        if (next == null || caret - start < markerEnd) { pane.replaceSelection("\n"); return; }
        if (line.substring(markerEnd).isBlank()) {
            // An empty item: a nested one steps out a level, a top-level one
            // ends the list and its marker goes.
            String kept = lead.isEmpty() ? "" : outdented(line);
            history.together(() -> edit(start, end - start, kept));
            return;
        }
        String insert = "\n" + next;
        history.together(() -> pane.replaceSelection(insert));
    }

    /**
     * Tab and Shift-Tab move list items, or every selected line, a level in or
     * out. Elsewhere Tab types a tab, as in any text editor.
     */
    private void indent(boolean in) {
        if (completion.showing()) return;
        String text = content();
        int a = pane.getSelectionStart(), b = pane.getSelectionEnd();
        int start = lineStart(text, a);
        boolean lines = text.substring(a, b).contains("\n") || LIST.matcher(text.substring(start, lineEnd(text, a))).lookingAt();
        if (!lines) {
            if (in) pane.replaceSelection("\t");
            return;
        }
        int endLine = lineEnd(text, b > a && text.charAt(b - 1) == '\n' ? b - 1 : b);
        var out = new StringBuilder();
        int shiftFirst = 0, shiftTotal = 0;
        boolean first = true;
        for (String line : text.substring(start, endLine).split("\n", -1)) {
            String changed = in ? "\t" + line : outdented(line);
            int delta = changed.length() - line.length();
            if (first) shiftFirst = delta;
            shiftTotal += delta;
            first = false;
            out.append(changed).append('\n');
        }
        out.setLength(out.length() - 1);
        int from = start, to = endLine, sf = shiftFirst, st = shiftTotal;
        history.together(() -> edit(from, to - from, out.toString()));
        pane.select(Math.max(start, a + sf), Math.max(start, b + st));
    }

    /** A line a level further out: one tab, or up to four spaces, fewer. */
    private static String outdented(String line) {
        if (line.startsWith("\t")) return line.substring(1);
        int spaces = 0;
        while (spaces < 4 && spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
        return line.substring(spaces);
    }

    /** Wraps the selection in a marker, or unwraps it if it is already wrapped. */
    private void wrap(String marker) {
        String text = content();
        int a = pane.getSelectionStart(), b = pane.getSelectionEnd();
        int n = marker.length();
        boolean wrapped = a >= n && b + n <= text.length()
            && text.startsWith(marker, a - n) && text.startsWith(marker, b)
            // "**bold**" is not an italic "*" around "*bold*".
            && !(marker.equals("*") && a >= 2 && text.charAt(a - 2) == '*' && b + 1 < text.length() && text.charAt(b + 1) == '*');
        if (wrapped) {
            history.together(() -> { edit(b, n, ""); edit(a - n, n, ""); });
            pane.select(a - n, b - n);
            return;
        }
        String inner = text.substring(a, b);
        history.together(() -> edit(a, b - a, marker + inner + marker));
        if (a == b) pane.setCaretPosition(a + n);
        else pane.select(a + n, b + n);
    }

    /** [selection](|) — or, when a web address is selected, [|](address). */
    private void markdownLink() {
        int a = pane.getSelectionStart(), b = pane.getSelectionEnd();
        String selected = content().substring(a, b);
        boolean address = selected.matches("(?i)(https?://|mailto:)\\S+");
        String link = address ? "[](" + selected + ")" : "[" + selected + "]()";
        history.together(() -> edit(a, b - a, link));
        pane.setCaretPosition(address ? a + 1 : a + link.length() - 1);
    }

    /** Ticks or unticks the checkbox on each selected line; a plain item gains one, a plain line becomes one. */
    private void toggleCheck() {
        String text = content();
        int a = pane.getSelectionStart(), b = pane.getSelectionEnd();
        int start = lineStart(text, a), end = lineEnd(text, b);
        var out = new StringBuilder();
        for (String line : text.substring(start, end).split("\n", -1)) {
            Matcher m = LIST.matcher(line);
            if (m.lookingAt()) {
                if (m.group(6) == null) out.append(line, 0, m.end()).append("[ ] ").append(line.substring(m.end()));
                else {
                    int box = m.start(7);
                    out.append(line, 0, box).append(m.group(7).equals(" ") ? 'x' : ' ').append(line.substring(box + 1));
                }
            } else {
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                out.append(indent).append("- [ ] ").append(line.stripLeading());
            }
            out.append('\n');
        }
        out.setLength(out.length() - 1);
        int caret = pane.getCaretPosition();
        int grew = out.length() - (end - start);
        history.together(() -> edit(start, end - start, out.toString()));
        pane.setCaretPosition(Math.max(start, Math.min(caret + Math.max(0, grew), pane.getDocument().getLength())));
    }

    /** Ticks or unticks a box by its state character's offset, as the reading view's checkboxes do. */
    void check(int offset, boolean done) {
        String text = content();
        if (offset < 0 || offset >= text.length() || " xX".indexOf(text.charAt(offset)) < 0) return;
        history.together(() -> edit(offset, 1, done ? "x" : " "));
    }

    // ---------------------------------------------------------------- links

    private void links() {
        var mouse = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || !followKey(e)) return;
                int offset = pane.viewToModel2D(e.getPoint());
                if (followAt(offset, e.isShiftDown())) e.consume();
            }
            @Override public void mouseMoved(MouseEvent e) {
                boolean over = followKey(e) && linkAt(pane.viewToModel2D(e.getPoint())) != null;
                pane.setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        };
        pane.addMouseListener(mouse);
        pane.addMouseMotionListener(mouse);
        pane.setToolTipText(null);
    }

    private static boolean followKey(InputEvent e) {
        return (e.getModifiersEx() & (GraphicsEnvironment.isHeadless() ? InputEvent.CTRL_DOWN_MASK : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx())) != 0;
    }

    private Span linkAt(int offset) {
        if (offset < 0) return null;
        for (var s : doc.allSpans())
            if (offset >= s.start() && offset < s.end() && switch (s.kind()) {
                case WIKILINK, EMBED, LINK, IMAGE, URL -> true; default -> false; })
                return s;
        return null;
    }

    /** Follows the link at an offset; false when there is none. */
    private boolean followAt(int offset, boolean elsewhere) {
        var link = linkAt(offset);
        if (link == null && offset > 0) link = linkAt(offset - 1);
        if (link == null) return false;
        flush();
        host.follow(link, elsewhere);
        return true;
    }

    // ---------------------------------------------------------------- [[ completion

    /**
     * The list [[ opens: pages whose names hold what has been typed since the
     * brackets, best first. Up and Down choose, Enter or Tab puts the link in,
     * Escape leaves the text as typed. Typing "[[" also closes the brackets,
     * so the caret sits between them.
     */
    private final class Completion {
        private final DefaultListModel<Choice> model = new DefaultListModel<>();
        private final JList<Choice> list = new JList<>(model);
        private final JPopupMenu popup = new JPopupMenu();
        private int open = -1;

        Completion() {
            list.setName("page.completion");
            list.setFocusable(false);
            list.setBackground(PANEL);
            list.setForeground(TEXT);
            list.setSelectionBackground(shade(ACCENT_TEXT, DARK ? -70 : 80));
            list.setSelectionForeground(TEXT);
            list.setVisibleRowCount(8);
            list.setCellRenderer((l, choice, index, selected, focus) -> {
                var row = new JPanel(new BorderLayout(SPACE_MD, 0));
                row.setOpaque(true);
                row.setBackground(selected ? l.getSelectionBackground() : PANEL);
                row.setBorder(new EmptyBorder(SPACE_XS, SPACE_MD, SPACE_XS, SPACE_MD));
                row.add(label(choice.title(), TYPE_LABEL, TEXT), BorderLayout.CENTER);
                if (!choice.where().isEmpty()) row.add(label(choice.where(), TYPE_CAPTION, MUTED), BorderLayout.EAST);
                return row;
            });
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    if (i >= 0) { list.setSelectedIndex(i); accept(); }
                }
            });
            var scroller = new JScrollPane(list);
            scroller.setBorder(null);
            popup.setFocusable(false);
            popup.setBorder(new LineBorder(LINE));
            popup.setBackground(PANEL);
            popup.add(scroller);
            pane.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) {
                    if (loading || history.replaying || e.getLength() != 1) return;
                    int at = e.getOffset();
                    SwingUtilities.invokeLater(() -> opened(at));
                }
                @Override public void removeUpdate(DocumentEvent e) { SwingUtilities.invokeLater(Completion.this::follow); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
        }

        boolean showing() { return popup.isVisible(); }

        /** A "[" typed right after another starts a link: close it and offer pages. */
        private void opened(int at) {
            String text = content();
            if (at < 1 || at >= text.length() || text.charAt(at) != '[' || text.charAt(at - 1) != '[') { follow(); return; }
            if (at >= 2 && text.charAt(at - 2) == '[') return;
            boolean closed = text.startsWith("]]", at + 1);
            if (!closed) history.together(() -> edit(at + 1, 0, "]]"));
            pane.setCaretPosition(at + 1);
            open = at + 1;
            follow();
        }

        /** Keeps the list in step with the caret: filtered by what is typed, gone when the caret leaves the link. */
        void follow() {
            if (open < 0) return;
            String text = content();
            int caret = pane.getCaretPosition();
            if (open > text.length() || caret < open || open < 2 || !text.startsWith("[[", open - 2)) { hide(); return; }
            String typed = text.substring(open, caret);
            if (typed.contains("\n") || typed.contains("]") || typed.contains("|") || typed.contains("#")) { hide(); return; }
            model.clear();
            for (var c : host.linkChoices(typed)) model.addElement(c);
            if (model.isEmpty()) { popup.setVisible(false); return; }
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
            try {
                var r = pane.modelToView2D(open);
                if (r == null) return;
                int width = grow(320);
                list.setFixedCellWidth(width - 2);
                popup.pack();
                if (!popup.isVisible()) popup.show(pane, (int) r.getX(), (int) (r.getY() + r.getHeight()) + SPACE_XS);
                else { popup.pack(); popup.revalidate(); }
                pane.requestFocusInWindow();
            } catch (BadLocationException | IllegalComponentStateException ignored) { }
        }

        void hide() {
            open = -1;
            popup.setVisible(false);
        }

        boolean key(KeyEvent e) {
            if (!popup.isVisible()) return false;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_DOWN -> list.setSelectedIndex(Math.min(model.size() - 1, list.getSelectedIndex() + 1));
                case KeyEvent.VK_UP -> list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1));
                case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> accept();
                case KeyEvent.VK_ESCAPE -> hide();
                default -> { return false; }
            }
            list.ensureIndexIsVisible(list.getSelectedIndex());
            return true;
        }

        private void accept() {
            var choice = list.getSelectedValue();
            int from = open;
            hide();
            if (choice == null || from < 0) return;
            String text = content();
            int to = pane.getCaretPosition();
            int close = text.startsWith("]]", to) ? to + 2 : to;
            String link = choice.link() + "]]";
            history.together(() -> edit(from, close - from, link));
            pane.setCaretPosition(from + link.length());
        }
    }

    // ---------------------------------------------------------------- find

    /** Find in page: every match lit, the current one selected, Enter and Shift-Enter to step. */
    private final class FindBar extends JPanel {
        private final JTextField field = styleInput(new JTextField(18));
        private final JLabel count = label("", TYPE_CAPTION, MUTED);
        private final Highlighter.HighlightPainter lit = new DefaultHighlighter.DefaultHighlightPainter(MarkdownStyler.highlightFill());
        private final List<Object> marks = new ArrayList<>();
        private List<Integer> hits = List.of();
        private int current = -1;

        FindBar() {
            super(new BorderLayout(SPACE_SM, 0));
            setBackground(PANEL);
            setBorder(new CompoundBorder(new MatteBorder(0, 0, HAIRLINE, 0, LINE), new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD)));
            setVisible(false);
            field.setName("page.find");
            field.getAccessibleContext().setAccessibleName("Find in page");
            var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, SPACE_XS, 0));
            buttons.setOpaque(false);
            buttons.add(count);
            buttons.add(ghost(button("↑", () -> step(false))));
            buttons.add(ghost(button("↓", () -> step(true))));
            buttons.add(ghost(button("Done", this::close)));
            ((JButton) buttons.getComponent(1)).getAccessibleContext().setAccessibleName("Previous match");
            ((JButton) buttons.getComponent(2)).getAccessibleContext().setAccessibleName("Next match");
            add(field, BorderLayout.CENTER);
            add(buttons, BorderLayout.EAST);
            var replace = styleInput(new JTextField());
            replace.setName("page.replace");
            replace.getAccessibleContext().setAccessibleName("Replace with");
            var replacement = new JPanel(new BorderLayout(SPACE_SM, 0));
            replacement.setOpaque(false);
            replacement.add(replace, BorderLayout.CENTER);
            var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, SPACE_XS, 0));
            actions.setOpaque(false);
            actions.add(button("Replace", () -> {
                if (current < 0 || field.getText().isEmpty()) return;
                int at = hits.get(current);
                edit(at, field.getText().length(), replace.getText());
                search(true);
            }));
            actions.add(button("Replace all", () -> {
                if (field.getText().isEmpty()) return;
                String next = Pattern.compile(Pattern.quote(field.getText()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(text()).replaceAll(Matcher.quoteReplacement(replace.getText()));
                edit(0, pane.getDocument().getLength(), next);
                search(true);
            }));
            replacement.add(actions, BorderLayout.EAST);
            add(replacement, BorderLayout.SOUTH);
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { search(true); }
                @Override public void removeUpdate(DocumentEvent e) { search(true); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
            field.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) { step(!e.isShiftDown()); e.consume(); }
                    else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { close(); e.consume(); }
                }
            });
        }

        void open(String seed) {
            setVisible(true);
            if (seed != null && !seed.isBlank() && !seed.contains("\n")) field.setText(seed);
            field.selectAll();
            field.requestFocusInWindow();
            search(true);
            PageEditor.this.revalidate();
        }

        void close() {
            setVisible(false);
            clearMarks();
            PageEditor.this.revalidate();
            pane.requestFocusInWindow();
        }

        void refresh() { if (isVisible()) search(false); }

        private void clearMarks() {
            for (var m : marks) pane.getHighlighter().removeHighlight(m);
            marks.clear();
        }

        private void search(boolean jump) {
            clearMarks();
            String query = field.getText();
            var found = new ArrayList<Integer>();
            if (!query.isEmpty()) {
                var matcher = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(content());
                while (found.size() < 5000 && matcher.find()) {
                    int at = matcher.start();
                    found.add(at);
                    try { marks.add(pane.getHighlighter().addHighlight(at, at + query.length(), lit)); }
                    catch (BadLocationException ignored) { }
                }
            }
            hits = found;
            if (hits.isEmpty()) current = -1;
            else if (jump || current < 0 || current >= hits.size()) {
                int caret = pane.getSelectionStart();
                current = 0;
                for (int i = 0; i < hits.size(); i++) if (hits.get(i) >= caret) { current = i; break; }
                if (jump) showMatch();
            }
            count.setText(query.isEmpty() ? "" : hits.isEmpty() ? "No matches" : (current + 1) + " of " + hits.size());
        }

        void step(boolean forward) {
            if (!isVisible()) { open(pane.getSelectedText()); return; }
            if (hits.isEmpty()) { Toolkit.getDefaultToolkit().beep(); return; }
            current = Math.floorMod(current + (forward ? 1 : -1), hits.size());
            showMatch();
            count.setText((current + 1) + " of " + hits.size());
        }

        private void showMatch() {
            if (current < 0) return;
            int at = hits.get(current);
            reveal(at, at + field.getText().length());
            field.requestFocusInWindow();
        }
    }
}
