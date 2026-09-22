package dev.yoru.ui;

import dev.yoru.domain.Model.Page;
import dev.yoru.pages.Links;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import static dev.yoru.ui.Theme.*;

/**
 * The keyboard's way to a page: type a few letters of its title, or of
 * anything written in it, and press Enter.
 *
 * One small window serves three questions — which page to open (Cmd/Ctrl-O),
 * which pages hold some words (Cmd/Ctrl-Shift-F), and which page to link a
 * task to — so they look and behave alike: arrows move, Enter takes, Escape
 * leaves. Opening a title that no page has offers to create it.
 */
final class QuickSwitcher {
    private QuickSwitcher() { }

    /**
     * One answer: a page and what to show for it, or a page to create. start
     * and end mark the words matched in the page's text, or -1.
     */
    record Row(UUID page, String title, String detail, int start, int end, String create) {
        static Row page(Page page, String detail) { return new Row(page.id(), page.title(), detail, -1, -1, null); }
        static Row create(String title) { return new Row(null, title, "New page", -1, -1, title); }
    }

    /** Asks, and returns the row taken, or null. rows turns what is typed into answers, best first. */
    static Row ask(Component owner, String title, String hint, Function<String, List<Row>> rows) {
        var window = owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner);
        var dialog = new JDialog(window, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        var field = styleInput(new JTextField(32));
        field.setName("switcher.query");
        field.setFont(proseFont());
        field.getAccessibleContext().setAccessibleName(title);
        field.putClientProperty("JTextField.placeholderText", hint);
        var model = new DefaultListModel<Row>();
        var list = new JList<>(model);
        list.setName("switcher.results");
        list.setFocusable(false);
        list.setBackground(PANEL);
        list.setSelectionBackground(shade(ACCENT_TEXT, DARK ? -90 : 95));
        list.setVisibleRowCount(10);
        list.setCellRenderer((l, row, index, selected, focus) -> {
            var cell = new JPanel(new BorderLayout(0, 2));
            cell.setOpaque(true);
            cell.setBackground(selected ? l.getSelectionBackground() : PANEL);
            cell.setBorder(new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD));
            var name = shortenable(row.create() != null ? "Create “" + row.title() + "”" : row.title(), TYPE_LABEL,
                row.create() != null ? ACCENT_TEXT : TEXT);
            cell.add(name, BorderLayout.NORTH);
            if (row.detail() != null && !row.detail().isEmpty())
                cell.add(shortenable(row.detail(), TYPE_CAPTION, MUTED), BorderLayout.CENTER);
            return cell;
        });
        var hintLabel = label(hint, TYPE_CAPTION, MUTED);
        var scroll = new JScrollPane(list);
        scroll.setBorder(new MatteBorder(HAIRLINE, 0, 0, 0, LINE));
        scroll.getViewport().setBackground(PANEL);
        var box = new JPanel(new BorderLayout(0, SPACE_SM));
        box.setBackground(PANEL);
        box.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(SPACE_MD, SPACE_MD, SPACE_MD, SPACE_MD)));
        box.add(field, BorderLayout.NORTH);
        box.add(scroll, BorderLayout.CENTER);
        box.add(hintLabel, BorderLayout.SOUTH);
        dialog.setContentPane(box);

        Row[] taken = {null};
        Runnable refill = () -> {
            model.clear();
            for (var row : rows.apply(field.getText())) model.addElement(row);
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        Runnable take = () -> {
            taken[0] = list.getSelectedValue();
            if (taken[0] != null) dialog.dispose();
        };
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refill.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refill.run(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int at = list.getSelectedIndex();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> list.setSelectedIndex(Math.min(model.size() - 1, at + 1));
                    case KeyEvent.VK_UP -> list.setSelectedIndex(Math.max(0, at - 1));
                    case KeyEvent.VK_ENTER -> take.run();
                    case KeyEvent.VK_ESCAPE -> dialog.dispose();
                    default -> { return; }
                }
                list.ensureIndexIsVisible(list.getSelectedIndex());
                e.consume();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) { list.setSelectedIndex(i); take.run(); }
            }
        });
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { dialog.dispose(); }
        });
        refill.run();
        list.setFixedCellWidth(grow(520));
        dialog.pack();
        if (window != null) {
            var at = window.getLocationOnScreen();
            dialog.setLocation(at.x + (window.getWidth() - dialog.getWidth()) / 2, at.y + window.getHeight() / 6);
        }
        dialog.setVisible(true);
        return taken[0];
    }

    /**
     * Pages whose title or path holds what is typed, best first: an exact title,
     * then titles that start with it, then titles with a word that starts with
     * it, then titles or paths that merely contain it, then titles holding its
     * letters in order. Within each, the most recently edited first.
     */
    static List<Page> rank(Links.Resolver resolver, String typed, int limit) {
        String q = typed.strip().toLowerCase(Locale.ROOT);
        var scored = new ArrayList<Map.Entry<Page, Integer>>();
        for (var p : resolver.pages()) {
            int score = score(p.title().toLowerCase(Locale.ROOT), resolver.path(p).toLowerCase(Locale.ROOT), q);
            if (score > 0) scored.add(Map.entry(p, score));
        }
        scored.sort(Comparator.comparing((Map.Entry<Page, Integer> e) -> -e.getValue())
            .thenComparing(e -> e.getKey().updatedAt(), Comparator.reverseOrder()));
        return scored.stream().limit(limit).map(Map.Entry::getKey).toList();
    }

    static int score(String title, String path, String q) {
        if (q.isEmpty()) return 1;
        if (title.equals(q)) return 6;
        if (title.startsWith(q)) return 5;
        for (String word : title.split("[^\\p{L}\\p{N}]+")) if (!word.isEmpty() && word.startsWith(q)) return 4;
        if (title.contains(q)) return 3;
        if (path.contains(q)) return 2;
        int at = 0;
        for (char c : q.toCharArray()) {
            if (Character.isWhitespace(c)) continue;
            at = title.indexOf(c, at);
            if (at < 0) return 0;
            at++;
        }
        return 1;
    }
}
