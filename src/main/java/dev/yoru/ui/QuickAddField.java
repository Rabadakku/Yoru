package dev.yoru.ui;

import dev.yoru.application.QuickAdd;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static dev.yoru.ui.Theme.*;

/**
 * One line that makes a whole task (#74): "Essay draft tomorrow 5pm #school
 * !high every monday".
 *
 * What it recognises is highlighted as it is typed and listed underneath, each
 * part with a × that keeps it as plain text instead; clicking a highlighted
 * part does the same. Enter adds the task and Escape clears the line. With
 * parsing switched off in Settings the whole line is the title, as it was.
 */
final class QuickAddField extends JPanel {
    private final HintField field = new HintField();
    private final JPanel parts = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
    private final Supplier<QuickAdd.Context> context;
    private final Consumer<QuickAdd.Result> add;
    private final Set<String> kept = new HashSet<>();
    private QuickAdd.Result result;

    /**
     * @param context what the words are read against, asked for afresh each time
     * @param add     saves a line; a refusal is the caller's to report, and the line stays
     * @param details opens the full form instead
     */
    QuickAddField(Supplier<QuickAdd.Context> context, Consumer<QuickAdd.Result> add, Runnable details) {
        super(new BorderLayout(0, SPACE_XS));
        this.context = context;
        this.add = add;
        setOpaque(false);
        setBorder(new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD));
        field.setName("task.quickAdd");
        field.getAccessibleContext().setAccessibleName("New task: type a title, and a date, #tag, /list, !priority or repeat after it");
        field.setToolTipText("Try “Essay draft tomorrow 5pm #school !high every monday”. Enter adds it.");
        var addButton = button("Add", this::submit);
        addButton.setName("task.quickAdd.add");
        addButton.getAccessibleContext().setAccessibleName("Add the typed task");
        var more = ghost(button("Details…", details));
        more.setName("task.newRow");
        more.setToolTipText("Open the full form");
        more.getAccessibleContext().setAccessibleName("New task with every detail");
        var line = new JPanel(new BorderLayout(SPACE_SM, 0));
        line.setOpaque(false);
        line.add(field, BorderLayout.CENTER);
        var end = tightRow();
        end.add(addButton);
        end.add(more);
        line.add(end, BorderLayout.EAST);
        add(line, BorderLayout.NORTH);
        parts.setOpaque(false);
        parts.setName("task.quickAdd.parts");
        add(parts, BorderLayout.CENTER);
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { reread(); }
            public void removeUpdate(DocumentEvent e) { reread(); }
            public void changedUpdate(DocumentEvent e) { }
        });
        field.addActionListener(e -> submit());
        field.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "quickAdd.clear");
        field.getActionMap().put("quickAdd.clear", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { clear(); }
        });
        // A click on a highlighted part keeps it as text; a drag is a selection and changes nothing.
        field.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (result == null || e.getClickCount() != 1 || field.getSelectionStart() != field.getSelectionEnd()) return;
                int at = field.viewToModel2D(e.getPoint());
                for (var part : result.parts()) if (at > part.start() && at < part.end()) { keep(part); return; }
            }
        });
        reread();
    }

    JTextField field() { return field; }

    /** What the line reads as now; null while it is empty. */
    QuickAdd.Result result() { return result; }

    /** Leaves one recognised part in the title as the words typed. */
    void keep(QuickAdd.Part part) {
        kept.add(part.key());
        reread();
        field.requestFocusInWindow();
    }

    void clear() {
        field.setText("");
        kept.clear();
        reread();
    }

    private void submit() {
        if (result == null || result.title().isBlank()) return;
        add.accept(result);
    }

    /** Reads the line again, and shows what it read. */
    private void reread() {
        var text = field.getText();
        field.getHighlighter().removeAllHighlights();
        parts.removeAll();
        if (text.isBlank()) {
            result = null;
        } else if (!QuickAddSetting.enabled()) {
            result = new QuickAdd.Result(text.strip(), null, null, List.of(), null, Priority.NONE, null, List.of());
        } else {
            result = QuickAdd.parse(text, context.get(), kept);
            for (var part : result.parts()) {
                try {
                    field.getHighlighter().addHighlight(part.start(), part.end(),
                        new DefaultHighlighter.DefaultHighlightPainter(TagChips.wash(colour(part.kind()))));
                } catch (BadLocationException stale) {
                    // The text moved under it; the next reading draws it.
                }
                parts.add(chip(part));
            }
        }
        parts.setVisible(parts.getComponentCount() > 0);
        revalidate();
        repaint();
    }

    /** A kind's colour, from the palette the tags and priorities draw from. */
    private static Color colour(QuickAdd.Kind kind) {
        return switch (kind) {
            case DATE, TIME -> CYAN;
            case TAG -> GOLD;
            case LIST -> PURPLE;
            case PRIORITY -> DANGER;
            case REPEAT -> HEAT[3];
        };
    }

    /** One recognised part as the line will save it, with a × to keep it as text. */
    private JComponent chip(QuickAdd.Part part) {
        var chip = new JPanel(new FlowLayout(FlowLayout.LEFT, SPACE_XS, 0));
        chip.setOpaque(true);
        chip.setBackground(TagChips.wash(colour(part.kind())));
        chip.setBorder(new EmptyBorder(RING, SPACE_SM, RING, RING));
        chip.setName("task.quickAdd.part." + part.kind().name());
        String says = part.kind().label + ": " + meaning(part);
        var words = label(says, TYPE_CAPTION, TEXT);
        chip.add(words);
        var keep = button("×", () -> keep(part));
        keep.setName("task.quickAdd.keep." + part.kind().name());
        keep.setFont(captionFont());
        keep.setBorder(new EmptyBorder(0, SPACE_XS, 0, SPACE_XS));
        keep.setBackground(chip.getBackground());
        keep.setToolTipText("Keep “" + part.text().strip() + "” as text");
        keep.getAccessibleContext().setAccessibleName("Keep “" + part.text().strip() + "” as text, not as the " + part.kind().label.toLowerCase(Locale.ROOT));
        chip.add(keep);
        return chip;
    }

    /** What a part will set, in words: "Thu, Oct 8", "5:00 PM", "every Monday". */
    private String meaning(QuickAdd.Part part) {
        return switch (part.kind()) {
            case DATE -> result.due() == null ? part.text().strip()
                : result.due().format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))
                    + " (" + QuickAdd.distance(result.due(), LocalDate.now()) + ")";
            case TIME -> result.time() == null ? part.text().strip() : DateText.time(result.time());
            case TAG -> part.text().strip().substring(1);
            case LIST -> result.list() == null ? part.text().strip() : result.list();
            case PRIORITY -> result.priority().label;
            case REPEAT -> result.repeat() == null ? part.text().strip() : RepeatField.inSentence(result.repeat());
        };
    }

    /** A field that says what it is for while it is empty. */
    static final class HintField extends JTextField {
        private final String hint = "New task — try “Essay draft tomorrow 5pm #school”";
        HintField() {
            super(32);
            styleInput(this);
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!getText().isEmpty()) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(MUTED);
            g2.setFont(getFont());
            var insets = getInsets();
            var m = g2.getFontMetrics();
            g2.drawString(clip(m, hint, getWidth() - insets.left - insets.right),
                insets.left, (getHeight() - m.getHeight()) / 2 + m.getAscent());
            g2.dispose();
        }

        /** Shortens the hint to fit, on whole characters. */
        private static String clip(FontMetrics m, String text, int width) {
            if (m.stringWidth(text) <= width) return text;
            int end = text.length();
            while (end > 0 && m.stringWidth(text.substring(0, end) + "…") > width) end--;
            return end == 0 ? "" : text.substring(0, end) + "…";
        }
    }
}
