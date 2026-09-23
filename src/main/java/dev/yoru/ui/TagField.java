package dev.yoru.ui;

import dev.yoru.domain.Model.Tag;
import dev.yoru.domain.Model.Task;
import dev.yoru.importer.NotionImport;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import static dev.yoru.ui.Theme.*;

/**
 * A task's tags, typed in, as in Notion's multi-select (#66).
 *
 * The chosen tags stand as pills, each with its own ×. Typing filters the
 * vault's tags in the list under the field; Enter takes the highlighted one,
 * and a name no tag has yet is offered as "Create". The new tag is not written
 * here: it is handed back with the task, so the two are saved together or not
 * at all ({@code Tracker.saveTask}). Backspace in an empty field takes the last
 * pill off.
 *
 * The list is part of the field rather than a popup: a popup that takes focus
 * from the field it completes is the usual way such a list goes wrong, and one
 * that stays the same size keeps the form from jumping as you type.
 */
final class TagField extends JPanel {
    /** Rows of suggestions shown at once. */
    static final int ROWS = 5;

    /** One line of the list: a tag to add, or a name to make into one. */
    record Option(Tag tag, String create) {
        @Override public String toString() { return tag != null ? tag.name() : "Create “" + create + "”"; }
    }

    private final List<Tag> known;
    private final List<Tag> chosen = new ArrayList<>();
    private final List<Tag> created = new ArrayList<>();
    private final JPanel pills = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_XS, SPACE_XS));
    private final JTextField input = styleInput(new JTextField(18));
    private final DefaultListModel<Option> options = new DefaultListModel<>();
    private final JList<Option> list = new JList<>(options);
    private final JLabel limit = label("", TYPE_CAPTION, MUTED);

    TagField(List<Tag> known, List<UUID> initial) {
        super(new BorderLayout(0, SPACE_XS));
        setOpaque(false);
        setName("tags.field");
        this.known = List.copyOf(known);
        for (var id : initial) this.known.stream().filter(t -> t.id().equals(id)).findFirst().ifPresent(chosen::add);

        pills.setOpaque(false);
        // Inside the box the pills share, so the box is the field's one border.
        input.setBorder(new EmptyBorder(RING, SPACE_XS, RING, SPACE_XS));
        input.setName("tags.input");
        input.getAccessibleContext().setAccessibleName("Add a tag: type to find one, or a new name to create it");
        input.setToolTipText("Type to find a tag, or a new name to create one · Enter adds · Backspace removes the last");
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { suggest(); }
            public void removeUpdate(DocumentEvent e) { suggest(); }
            public void changedUpdate(DocumentEvent e) { suggest(); }
        });
        keys();

        var box = new JPanel(new BorderLayout(SPACE_SM, 0));
        box.setOpaque(true);
        box.setBackground(PANEL);
        box.setBorder(controlBorder(LINE));
        box.add(pills, BorderLayout.CENTER);
        add(box, BorderLayout.NORTH);

        list.setName("tags.options");
        list.setVisibleRowCount(ROWS);
        list.setFont(labelFont());
        list.setBackground(PANEL);
        list.setForeground(TEXT);
        list.setSelectionBackground(LINE);
        list.setSelectionForeground(TEXT);
        list.setFocusable(false);
        list.getAccessibleContext().setAccessibleName("Matching tags");
        // A label, not a panel of parts: a list stamps its renderer, and a panel
        // stamped there is never laid out, so its parts draw nowhere.
        list.setCellRenderer((view, option, index, selected, focused) -> {
            var row = new JLabel(option.toString());
            row.putClientProperty("html.disable", true);
            row.setOpaque(true);
            row.setBackground(selected ? LINE : PANEL);
            row.setForeground(option.tag() != null ? TEXT : ACCENT_TEXT);
            row.setFont(labelFont());
            row.setBorder(new EmptyBorder(SPACE_XS, SPACE_SM, SPACE_XS, SPACE_SM));
            row.setIconTextGap(SPACE_SM);
            int colour = option.tag() != null ? option.tag().colour() : NotionImport.colourFor(option.create());
            row.setIcon(swatch(colour));
            return row;
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) take(options.get(index));
            }
        });
        var scroll = new JScrollPane(list);
        scroll.setBorder(controlBorder(LINE));
        add(scroll, BorderLayout.CENTER);
        limit.setName("tags.limit");
        add(limit, BorderLayout.SOUTH);
        drawPills();
        suggest();
    }

    /** The tags chosen, in the order they were added. */
    List<UUID> tagIds() { return chosen.stream().map(Tag::id).toList(); }

    /** Tags made here that are still chosen: what has to be saved with the task. */
    List<Tag> newTags() { return created.stream().filter(chosen::contains).toList(); }

    JTextField input() { return input; }

    List<Option> options() { return Collections.list(options.elements()); }

    /**
     * Enter, Up, Down and Backspace, bound on the field itself.
     *
     * Enter is only taken while there is something to take: with the field
     * empty it falls through to the dialog, whose default button saves.
     */
    private void keys() {
        bind("ENTER", "tags.take", () -> {
            var picked = list.getSelectedValue();
            take(picked != null ? picked : options.isEmpty() ? null : options.firstElement());
        }, () -> !input.getText().isBlank() && !options.isEmpty());
        bind("DOWN", "tags.down", () -> move(1), () -> !options.isEmpty());
        bind("UP", "tags.up", () -> move(-1), () -> !options.isEmpty());
        bind("BACK_SPACE", "tags.dropLast", () -> remove(chosen.getLast()),
            () -> input.getText().isEmpty() && input.getCaretPosition() == 0 && !chosen.isEmpty());
    }

    private void bind(String key, String name, Runnable act, java.util.function.BooleanSupplier when) {
        var bound = input.getInputMap().get(KeyStroke.getKeyStroke(key));
        var previous = bound == null ? null : input.getActionMap().get(bound);
        input.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        input.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (when.getAsBoolean()) act.run();
                else if (previous != null) previous.actionPerformed(e);
            }
            // Disabled, the key goes on to whoever else wants it: the dialog's
            // default button for Enter, the field's own delete for Backspace.
            @Override public boolean isEnabled() { return when.getAsBoolean() || previous != null && previous.isEnabled(); }
        });
    }

    private void move(int step) {
        int at = list.getSelectedIndex() + step;
        at = Math.max(0, Math.min(options.size() - 1, at));
        list.setSelectedIndex(at);
        list.ensureIndexIsVisible(at);
    }

    /** The list for what is typed: matching tags not yet chosen, then an offer to create it. */
    private void suggest() {
        String typed = input.getText().strip();
        String key = typed.toLowerCase(Locale.ROOT);
        options.clear();
        var all = new ArrayList<Tag>(known);
        all.addAll(created);
        boolean exact = false;
        for (var tag : all) {
            String name = tag.name().toLowerCase(Locale.ROOT);
            if (name.equals(key)) exact = true;
            if (!chosen.contains(tag) && name.contains(key)) options.addElement(new Option(tag, null));
        }
        if (!typed.isEmpty() && !exact && creatable(typed)) options.addElement(new Option(null, typed));
        if (!options.isEmpty()) list.setSelectedIndex(0);
        boolean full = chosen.size() >= Task.MAX_TAGS;
        input.setEnabled(!full);
        limit.setText(full ? "A task can carry " + Task.MAX_TAGS + " tags." : "");
    }

    /** A name a tag may have: what the tag itself will accept. */
    private static boolean creatable(String name) {
        try { new Tag(UUID.randomUUID(), name, 0); return true; }
        catch (IllegalArgumentException refused) { return false; }
    }

    /** Adds the option's tag, making it first when it is a new name. */
    void take(Option option) {
        if (option == null || chosen.size() >= Task.MAX_TAGS) return;
        Tag tag = option.tag();
        if (tag == null) {
            tag = new Tag(UUID.randomUUID(), option.create(), NotionImport.colourFor(option.create()));
            created.add(tag);
        }
        if (!chosen.contains(tag)) chosen.add(tag);
        input.setText("");
        drawPills();
        suggest();
        input.requestFocusInWindow();
    }

    void remove(Tag tag) {
        chosen.remove(tag);
        drawPills();
        suggest();
        input.requestFocusInWindow();
    }

    private void drawPills() {
        pills.removeAll();
        for (var tag : chosen) pills.add(pill(tag));
        pills.add(input);
        pills.revalidate();
        pills.repaint();
        revalidate();
    }

    /** A tag's colour as a small square, for the list. */
    private static Icon swatch(int colour) {
        int side = grow(SPACE_MD);
        return new Icon() {
            @Override public int getIconWidth() { return side; }
            @Override public int getIconHeight() { return side; }
            @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(colour));
                g.fillRoundRect(x, y, side, side, RING * 2, RING * 2);
                g.dispose();
            }
        };
    }

    /** One chosen tag: its name on a wash of its colour, and the × that takes it off. */
    private JComponent pill(Tag tag) {
        var fill = TagChips.wash(new Color(tag.colour()));
        var pill = new JPanel(new FlowLayout(FlowLayout.LEFT, SPACE_XS, 0)) {
            @Override protected void paintComponent(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), RADIUS, RADIUS);
                g.dispose();
            }
        };
        pill.setOpaque(false);
        pill.setName("tags.pill." + tag.name());
        pill.setBorder(new EmptyBorder(RING, SPACE_SM, RING, RING));
        var name = new JLabel(tag.name());
        name.putClientProperty("html.disable", true);
        name.setFont(captionFont());
        name.setForeground(TEXT);
        pill.add(name);
        var drop = new JButton("×");
        drop.setName("tags.remove." + tag.name());
        drop.setFont(captionFont());
        drop.setForeground(TEXT);
        drop.setContentAreaFilled(false);
        drop.setBorder(new EmptyBorder(0, SPACE_XS, 0, SPACE_XS));
        drop.setToolTipText("Remove " + tag.name());
        drop.getAccessibleContext().setAccessibleName("Remove the tag " + tag.name());
        drop.addActionListener(e -> remove(tag));
        pill.add(drop);
        pill.getAccessibleContext().setAccessibleName("Tag " + tag.name());
        return pill;
    }
}
