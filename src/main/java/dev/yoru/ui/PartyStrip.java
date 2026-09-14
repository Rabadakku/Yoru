package dev.yoru.ui;

import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.StorageEdit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * The party, first on the Collection page (#8): six buttons, one per slot,
 * each with the Pokémon's picture, name and level.
 *
 * The party used to be a column painted beside the box grid, under two reward
 * cards, so at the window's minimum it started below the fold, showed a dex
 * number where a picture was missing, and reached a screen reader only as
 * children of the grid. As buttons the slots take Tab and the arrow keys, say
 * who is in them, and draw the shared focus ring. Six across when each has room
 * to be read, three by two when not.
 */
final class PartyStrip extends JPanel {
    /** The narrowest a slot may be before the row wraps to three by two. */
    static final int WIDE_SLOT = 160;
    /** A slot's least width and height, whatever the layout. */
    static final int MIN_SLOT_WIDTH = 120, SLOT_HEIGHT = 88;
    static final int SPRITE = 48;

    private final List<Gen3Pokemon> party;
    private final Arrangement arrangement;
    private final Consumer<Gen3Pokemon> onSelect;
    private final JButton[] slots = new JButton[Gen3Save.PARTY_LIMIT];
    private Gen3Pokemon selected;
    private Runnable toBoxes = () -> { };

    PartyStrip(List<Gen3Pokemon> party, Arrangement arrangement, Consumer<Gen3Pokemon> onSelect) {
        this.party = List.copyOf(party);
        this.arrangement = arrangement;
        this.onSelect = onSelect;
        setOpaque(false);
        setAlignmentX(0);
        setLayout(new Slots());
        getAccessibleContext().setAccessibleName("Your party");
        for (int i = 0; i < slots.length; i++) {
            slots[i] = buildSlot(i);
            add(slots[i]);
        }
        arrangement.listen(this::refresh);
        refresh();
    }

    JButton slot(int index) { return slots[index]; }

    /** How many slots sit in a row at the current width. */
    int columns() { return columnsFor(availableWidth(this)); }

    /** F6 from the party goes to the boxes. */
    void onSwitchArea(Runnable toBoxes) { this.toBoxes = toBoxes; }

    /** Marks the Pokémon the details show, whichever region it was chosen in. */
    void select(Gen3Pokemon mon) {
        selected = mon;
        refresh();
    }

    /** The party member in a slot, or null for an empty one. */
    Gen3Pokemon at(int index) { return index < party.size() ? party.get(index) : null; }

    private JButton buildSlot(int index) {
        var button = button("", () -> activate(index));
        button.setName("party.slot." + index);
        button.setLayout(new BorderLayout(SPACE_SM, 0));
        // The picture and the words are children, so the button keeps the
        // shared fill, border and ring rather than painting its own.
        var picture = new JLabel();
        picture.setName("party.picture." + index);
        picture.setPreferredSize(new Dimension(SPRITE, SPRITE));
        picture.setHorizontalAlignment(SwingConstants.CENTER);
        var words = new JPanel();
        words.setOpaque(false);
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        var name = label("", TYPE_LABEL, TEXT);
        name.setName("party.name." + index);
        var level = label("", TYPE_CAPTION, MUTED);
        level.setName("party.level." + index);
        var note = label("", TYPE_CAPTION, MUTED);
        note.setName("party.note." + index);
        words.add(Box.createVerticalGlue());
        words.add(name);
        words.add(level);
        words.add(note);
        words.add(Box.createVerticalGlue());
        button.add(picture, BorderLayout.WEST);
        button.add(words, BorderLayout.CENTER);
        keys(button, index);
        return button;
    }

    private void keys(JButton button, int index) {
        bind(button, "LEFT", "party.left", () -> focus(index - 1));
        bind(button, "RIGHT", "party.right", () -> focus(index + 1));
        bind(button, "UP", "party.up", () -> focus(index - columns()));
        bind(button, "DOWN", "party.down", () -> focus(index + columns()));
        bind(button, "F6", "party.switchArea", () -> toBoxes.run());
    }

    private static void bind(JComponent c, String key, String name, Runnable action) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private void focus(int index) {
        if (index < 0 || index >= slots.length || !slots[index].isEnabled()) return;
        slots[index].requestFocusInWindow();
    }

    private void activate(int index) {
        var mon = at(index);
        if (arrangement.on()) {
            arrangement.choose(new StorageEdit.Place(true, -1, index), mon);
            return;
        }
        if (mon == null) return;
        select(mon);
        onSelect.accept(mon);
    }

    private void refresh() {
        for (int i = 0; i < slots.length; i++) fill(i);
        repaint();
    }

    private void fill(int index) {
        var mon = at(index);
        var button = slots[index];
        var picture = (JLabel) find(button, "party.picture." + index);
        var name = (JLabel) find(button, "party.name." + index);
        var level = (JLabel) find(button, "party.level." + index);
        var note = (JLabel) find(button, "party.note." + index);
        boolean readable = GameView.readable(mon);
        var sprite = mon == null || !readable || mon.isEgg() ? null : GameView.sprite(mon.nationalDex(), mon.shiny());
        picture.setIcon(mon == null ? null : sprite != null
            ? new ImageIcon(sprite.getScaledInstance(SPRITE, SPRITE, Image.SCALE_REPLICATE))
            : new Silhouette(mon.isEgg()));
        name.setText(mon == null ? "Empty" : GameView.name(mon));
        name.setForeground(mon == null ? MUTED : mon.shiny() && readable ? GOLD_TEXT : TEXT);
        level.setText(mon == null || !readable || mon.isEgg() ? "" : "Lv " + mon.level());
        boolean moving = arrangement.picked() != null && arrangement.from().party() && arrangement.from().slot() == index;
        boolean shown = !arrangement.on() && Arrangement.same(selected, mon);
        String said = moving ? "Moving" : shown ? "Selected" : mon != null && readable && !mon.isEgg() && sprite == null
            ? "Artwork missing" : "";
        note.setText(said);
        note.setForeground(moving ? GOLD_TEXT : shown ? ACCENT_TEXT : MUTED);
        // Selection is said in words as well as the ring colour, never by colour alone.
        button.setBorder(controlBorder(moving ? GOLD_TEXT : shown ? CYAN : LINE));
        // An empty slot is only somewhere to put a Pokémon, so it takes part only while arranging.
        button.setEnabled(mon != null || arrangement.on());
        String who = mon == null ? "empty" : mon.isEgg() ? "Egg"
            : readable ? GameView.name(mon) + ", level " + mon.level() : "Cannot read Pokémon";
        button.getAccessibleContext().setAccessibleName("Party, slot " + (index + 1) + " of " + Gen3Save.PARTY_LIMIT + ": " + who);
        button.getAccessibleContext().setAccessibleDescription(said.isEmpty() ? null : said);
    }

    private static Component find(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }

    static int columnsFor(int width) {
        return width >= Gen3Save.PARTY_LIMIT * WIDE_SLOT + (Gen3Save.PARTY_LIMIT - 1) * SPACE_MD ? Gen3Save.PARTY_LIMIT : 3;
    }

    /**
     * The width this strip will be given: its own once laid out, before that its
     * container's inner width. BoxLayout asks for a preferred height before it
     * sets a width, and the height depends on whether the row wraps.
     */
    static int availableWidth(Component c) {
        if (c.getWidth() > 0) return c.getWidth();
        for (var parent = c.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getWidth() <= 0) continue;
            var in = parent.getInsets();
            return parent.getWidth() - in.left - in.right;
        }
        return 0;
    }

    /** Six across or three by two, each slot as wide as the row allows and never under the minimum. */
    private final class Slots implements LayoutManager {
        private int laidOutColumns;

        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }

        @Override public Dimension preferredLayoutSize(Container parent) {
            int columns = columnsFor(availableWidth(parent));
            int rows = (slots.length + columns - 1) / columns;
            return new Dimension(columns * MIN_SLOT_WIDTH + (columns - 1) * SPACE_MD,
                rows * SLOT_HEIGHT + (rows - 1) * SPACE_MD);
        }

        @Override public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(3 * MIN_SLOT_WIDTH + 2 * SPACE_MD, 2 * SLOT_HEIGHT + SPACE_MD);
        }

        @Override public void layoutContainer(Container parent) {
            int width = parent.getWidth(), columns = columnsFor(width);
            int cell = Math.max(MIN_SLOT_WIDTH, (width - (columns - 1) * SPACE_MD) / columns);
            for (int i = 0; i < slots.length; i++) {
                int column = i % columns, row = i / columns;
                slots[i].setBounds(column * (cell + SPACE_MD), row * (SLOT_HEIGHT + SPACE_MD), cell, SLOT_HEIGHT);
            }
            // A width that changed the row count changes the height asked for,
            // and the column above has already been laid out with the old one.
            if (laidOutColumns != 0 && laidOutColumns != columns) SwingUtilities.invokeLater(parent::revalidate);
            laidOutColumns = columns;
        }
    }

    /** A plain shape where a picture would be: never a number standing in for a name. */
    private static final class Silhouette implements Icon {
        private final boolean egg;
        Silhouette(boolean egg) { this.egg = egg; }
        @Override public int getIconWidth() { return SPRITE; }
        @Override public int getIconHeight() { return SPRITE; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(egg ? TEXT : MUTED);
            if (egg) {
                g.fillOval(x + SPRITE / 2 - 12, y + SPRITE / 2 - 16, 24, 32);
            } else {
                g.fillOval(x + SPRITE / 2 - 8, y + 8, 16, 16);
                g.fillRoundRect(x + SPRITE / 2 - 14, y + 26, 28, 16, 12, 12);
            }
            g.dispose();
        }
    }
}
