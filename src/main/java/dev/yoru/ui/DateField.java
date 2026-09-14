package dev.yoru.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.LocalDate;
import java.time.Month;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import static dev.yoru.ui.Theme.*;

/**
 * One date, typed or picked: Notion's date property without a time (#24).
 *
 * The field shows its date the way Yoru writes it, which is also the example of
 * what it reads, and an empty field shows that example greyed out. Typing is
 * forgiving (see {@link DateText}), the calendar button opens a month to pick
 * from, Up and Down step a day, and Alt+Down opens the calendar.
 *
 * Text that cannot be read is never saved and never quietly swapped for another
 * date: {@link #value()} refuses it with a message, and the caption under the
 * field turns red and says so. The helpers below are shared with
 * {@link DateTimeField}, so both pickers look and behave as one.
 */
final class DateField extends JPanel {
    static final String EXAMPLE_NOTE = "e.g. Sep 14, 9/14 or tomorrow";

    private final HintField text;
    private final JPanel box = new JPanel(new BorderLayout(SPACE_XS, 0));
    private final JLabel note;
    private final String role, resting;
    private final boolean optional;
    private LocalDate value;

    /**
     * @param role     what the date is, such as "Due": its accessible name and the start of a refusal
     * @param optional whether a blank field means no date, in which case the calendar offers Clear
     */
    DateField(LocalDate initial, String role, boolean optional) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(0);
        // A blank optional date says it has none, as Notion's "Empty" does: a greyed
        // example date there read as a date already set. The caption gives examples.
        text = new HintField(optional ? "No date" : DateText.DATE_EXAMPLE);
        this.role = role;
        this.optional = optional;
        this.value = initial;
        resting = optional ? EXAMPLE_NOTE + ", or blank" : EXAMPLE_NOTE;

        inner(text, "date.text");
        fitDate(text);
        text.getAccessibleContext().setAccessibleName(role);
        text.getAccessibleContext().setAccessibleDescription("Type a date like " + DateText.DATE_EXAMPLE
            + (optional ? ", or leave it blank for none" : ""));
        text.setToolTipText("Type a date like " + DateText.DATE_EXAMPLE + " or 9/14");
        text.setText(initial == null ? "" : DateText.date(initial));
        keys(text, this::shift, this::openCalendar);
        tidiesItself(text, this::tidy);

        box.setOpaque(true);
        box.setBackground(PANEL);
        box.add(text, BorderLayout.CENTER);
        box.add(calendarButton(this::openCalendar, role), BorderLayout.EAST);
        box.setBorder(groupBorder(box, text));
        box.setAlignmentX(0);
        box.setMaximumSize(box.getPreferredSize());
        add(box);
        note = label(resting, TYPE_CAPTION, MUTED);
        note.setName("date.note");
        add(note);
    }

    /**
     * The date on screen, or null when an optional field is blank.
     *
     * @throws IllegalArgumentException when the text is not a date, starting with this field's role
     */
    LocalDate value() {
        String typed = text.getText();
        if (typed.isBlank()) {
            if (optional) { settle(); return null; }
            throw refuse(new IllegalArgumentException("Type a date, like " + DateText.DATE_EXAMPLE + "."));
        }
        if (value != null && typed.equals(DateText.date(value))) { settle(); return value; }
        try {
            value = DateText.parseDate(typed, LocalDate.now());
        } catch (IllegalArgumentException unreadable) {
            throw refuse(unreadable);
        }
        settle();
        return value;
    }

    /** Shows one date, or none. */
    void set(LocalDate date) {
        value = date;
        text.setText(date == null ? "" : DateText.date(date));
        settle();
    }

    private IllegalArgumentException refuse(IllegalArgumentException cause) {
        note.setText("Couldn't read that date · try Sep 14 or 9/14");
        note.setForeground(DANGER);
        return new IllegalArgumentException(role + ": " + cause.getMessage(), cause);
    }

    private void settle() {
        note.setText(resting);
        note.setForeground(MUTED);
    }

    /** Rewrites readable text the way Yoru writes it; unreadable text stays, with the caption saying why. */
    private void tidy() {
        try { set(value()); } catch (IllegalArgumentException leftAsTyped) { }
    }

    private LocalDate readable() {
        try { return DateText.parseDate(text.getText(), LocalDate.now()); }
        catch (IllegalArgumentException unreadable) { return value; }
    }

    private void shift(int days) {
        var from = readable();
        set((from == null ? LocalDate.now() : from).plusDays(days));
    }

    private void openCalendar() {
        popup(box, readable(), LocalDate.now(), day -> { set(day); text.requestFocusInWindow(); },
            optional ? () -> { set(null); text.requestFocusInWindow(); } : null);
    }

    // ------------------------------------------------ shared with DateTimeField

    /** A text field that shows an example of its format, greyed, while it is empty. */
    static final class HintField extends JTextField {
        private final String hint;
        HintField(String hint) { this.hint = hint; }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!getText().isEmpty()) return;
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            g.setColor(MUTED);
            var insets = getInsets();
            var metrics = g.getFontMetrics();
            int inner = getHeight() - insets.top - insets.bottom;
            g.drawString(hint, insets.left, insets.top + (inner - metrics.getHeight()) / 2 + metrics.getAscent());
            g.dispose();
        }
    }

    /** A field inside a box: the box draws the border, so the field brings no frame or inset of its own. */
    static void inner(JTextField field, String name) {
        field.setName(name);
        field.setFont(labelFont());
        field.setBackground(PANEL);
        field.setForeground(TEXT);
        field.setCaretColor(CYAN);
        field.setBorder(new EmptyBorder(0, 0, 0, 0));
    }

    /** Wide enough for the widest date Yoru writes, so no month's name is ever cut off. */
    static void fitDate(JTextField field) {
        var metrics = field.getFontMetrics(field.getFont());
        int widest = 0;
        for (var month : Month.values()) widest = Math.max(widest, metrics.stringWidth(DateText.date(LocalDate.of(2026, month, 30 - (month == Month.FEBRUARY ? 2 : 0)))));
        fit(field, widest);
    }

    /** Wide enough for the widest time Yoru writes. */
    static void fitTime(JTextField field) {
        fit(field, field.getFontMetrics(field.getFont()).stringWidth("12:00 PM"));
    }

    private static void fit(JTextField field, int textWidth) {
        var size = new Dimension(textWidth + SPACE_SM, field.getPreferredSize().height);
        field.setPreferredSize(size);
        field.setMinimumSize(size);
        field.setMaximumSize(size);
    }

    /** Up and Down step the value; Alt+Down opens the calendar, as it opens a combo box. */
    static void keys(JTextField field, IntConsumer step, Runnable openCalendar) {
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "yoru.later", () -> step.accept(1));
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "yoru.earlier", () -> step.accept(-1));
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "yoru.calendar", openCalendar);
    }

    private static void bind(JComponent component, KeyStroke key, String name, Runnable action) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /** Tidies on Enter and when the keyboard leaves. */
    static void tidiesItself(JTextField field, Runnable tidy) {
        field.addActionListener(e -> tidy.run());
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { tidy.run(); }
        });
    }

    /** The calendar glyph, drawn rather than borrowed from a font that may not have one. */
    static JButton calendarButton(Runnable open, String role) {
        var b = button("", open);
        b.setName("date.calendar");
        b.setIcon(new CalendarIcon());
        b.setBackground(PANEL);
        b.setBorder(new EmptyBorder(0, SPACE_XS, 0, 0));
        b.setFocusable(false);
        b.setToolTipText("Pick from a calendar (Alt+Down)");
        b.getAccessibleContext().setAccessibleName((role == null ? "" : role + ": ") + "pick from a calendar");
        return b;
    }

    /** Opens a month under {@code anchor}; picking a day, or Clear, closes it. */
    static void popup(JComponent anchor, LocalDate selected, LocalDate today, Consumer<LocalDate> picked, Runnable clear) {
        var menu = new JPopupMenu();
        menu.setBorder(new EmptyBorder(0, 0, 0, 0));
        menu.setFocusable(false);
        menu.add(new CalendarPanel(selected, today, day -> { menu.setVisible(false); picked.accept(day); },
            clear == null ? null : () -> { menu.setVisible(false); clear.run(); }));
        menu.show(anchor, 0, anchor.getHeight() + SPACE_XS);
    }

    private static final class CalendarIcon implements Icon {
        @Override public int getIconWidth() { return SPACE_LG; }
        @Override public int getIconHeight() { return SPACE_LG; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(c.isEnabled() ? MUTED : DISABLED_TEXT);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(x + 1, y + 3, SPACE_LG - 2, SPACE_LG - 4, SPACE_XS, SPACE_XS);
            g.drawLine(x + 1, y + 7, x + SPACE_LG - 1, y + 7);
            g.drawLine(x + 5, y + 1, x + 5, y + 4);
            g.drawLine(x + SPACE_LG - 5, y + 1, x + SPACE_LG - 5, y + 4);
            g.dispose();
        }
    }
}
