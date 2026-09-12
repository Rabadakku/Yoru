package dev.yoru.ui;

import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.StorageEdit;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;

/**
 * The game's own Pokémon Storage System, read from the save (#44).
 *
 * Fourteen boxes of thirty, six across and five down, on the game's own
 * wallpapers, with the party beside them — exactly what the save holds, in the
 * boxes and slots the game put them in. Click a Pokémon to see it, or walk the
 * occupied squares with the arrow keys and press Enter on the one to show.
 *
 * The wallpapers are real: tools/extract-storage-graphics.py decodes them out
 * of the user's own ROM into art/pc/, which is gitignored and never ships.
 * Without that artwork the boxes still work, drawn on a themed panel.
 */
final class StorageScreen extends JComponent {

    /**
     * The original's own measurements, scaled. A wallpaper is 160x144; the
     * thirty squares are 24px cells from (8, 24), below the band across the top.
     */
    static final int PAPER_WIDTH = 160, PAPER_HEIGHT = 144, SCALE = 3;
    static final int CELL = 24 * SCALE, GRID_X = 8 * SCALE, GRID_Y = 24 * SCALE;
    static final int PAD = 14, HEADER = 40, PARTY_GAP = 24;
    static final int COLUMNS = 6, ROWS = 5;

    private final Gen3Save save;
    private final byte[] storage;
    private final List<Gen3Pokemon> party;
    private final Consumer<Gen3Pokemon> onSelect;
    private final IntConsumer onBox;
    private final BiConsumer<StorageEdit.Place, StorageEdit.Place> onArrange;
    private int box;
    private Gen3Pokemon selected;
    /** The slot the keyboard cursor stands on in the open box, or -1 for an empty box. */
    private int cursor = -1;
    private boolean arranging;
    private Gen3Pokemon picked;
    private StorageEdit.Place pickedPlace;

    StorageScreen(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox) {
        this(save, box, onSelect, onBox, null);
    }

    StorageScreen(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox,
                  BiConsumer<StorageEdit.Place, StorageEdit.Place> onArrange) {
        this.save = save;
        this.storage = save.storage();
        this.party = save.party();
        this.onSelect = onSelect;
        this.onBox = onBox;
        this.onArrange = onArrange;
        this.box = Math.floorMod(box, Gen3Save.BOXES);
        setOpaque(false);
        setPreferredSize(new Dimension(boxWidth() + PARTY_GAP + CELL + PAD * 2, HEADER + boxHeight() + PAD * 2));
        setAlignmentX(LEFT_ALIGNMENT);
        // The grid is one drawing rather than thirty controls, so the keyboard
        // cursor is a slot index and the ring on it is painted below. Seeding it
        // here, and again whenever the box turns or the grid takes the keyboard,
        // is what puts the cue on screen before the first arrow key is pressed.
        setFocusable(true);
        accessibleContext = new GridAccessible();
        seedCursor();
        announce();
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { seedCursor(); announce(); repaint(); }
        });
        // Clicking is still how the mouse reads a square; it also hands the grid
        // the keyboard. The click comes first so that the cursor seeds from the
        // Pokémon the click just showed, should focus arrive while it is running.
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { click(e.getX(), e.getY()); requestFocusInWindow(); }
        });
        bind("LEFT", "storage.left", () -> moveCursor(-1, 0));
        bind("RIGHT", "storage.right", () -> moveCursor(1, 0));
        bind("UP", "storage.up", () -> moveCursor(0, -1));
        bind("DOWN", "storage.down", () -> moveCursor(0, 1));
        bind("ENTER", "storage.activate", this::activate);
        bind("SPACE", "storage.activate", this::activate);
        // The corner arrows turn boxes for the mouse; the keyboard turns them
        // through the same turn(), so both paths keep the same wrapping.
        bind("PAGE_UP", "storage.previousBox", () -> turn(-1));
        bind("PAGE_DOWN", "storage.nextBox", () -> turn(1));
        // Escape drops whatever was picked up, whatever has focus.
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("ESCAPE"), "storage.cancel");
        getActionMap().put("storage.cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancelPickup(); }
        });
    }

    int box() { return box; }
    Gen3Pokemon selected() { return selected; }

    /** The slot the keyboard cursor stands on in the open box, or -1. */
    int cursor() { return cursor; }

    void setArranging(boolean on) {
        arranging = on;
        picked = null;
        pickedPlace = null;
        repaint();
    }

    boolean arranging() { return arranging; }

    Gen3Pokemon picked() { return picked; }

    /** Drops whatever was picked up without moving anything. */
    void cancelPickup() {
        picked = null;
        pickedPlace = null;
        repaint();
    }

    static int boxWidth() { return PAPER_WIDTH * SCALE; }
    static int boxHeight() { return PAPER_HEIGHT * SCALE; }
    static int gridX() { return PAD + GRID_X; }
    static int gridY() { return HEADER + PAD + GRID_Y; }
    static int partyY() { return HEADER + PAD; }

    /** The slot within the open box under a point, or -1. */
    int slotAt(int x, int y) {
        int gx = x - gridX(), gy = y - gridY();
        if (gx < 0 || gy < 0 || gx >= COLUMNS * CELL || gy >= ROWS * CELL) return -1;
        return (gy / CELL) * COLUMNS + gx / CELL;
    }

    /** The party position under a point in the right-hand column, or -1. */
    int partyAt(int x, int y) {
        int px = x - PAD - boxWidth() - PARTY_GAP, py = y - partyY();
        if (px < 0 || py < 0 || px >= CELL) return -1;
        int index = py / CELL;
        return index < Gen3Save.PARTY_LIMIT ? index : -1;
    }

    Rectangle previousArrow() { return new Rectangle(PAD, PAD, 28, 24); }
    Rectangle nextArrow() { return new Rectangle(PAD + boxWidth() - 28, PAD, 28, 24); }

    // ------------------------------------------------------------------ keyboard

    /** One key, one action on the cursor, bound while the grid itself has the keyboard. */
    private void bind(String key, String name, Runnable action) {
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /**
     * Moves the cursor one step, landing on the next occupied square rather than
     * the next cell: a box of thirty is often a handful full, and stepping cell
     * by cell would spend most presses on nothing. It stops at the wall when the
     * row or column ahead is empty, so the cursor never leaves the grid.
     */
    private void moveCursor(int dCol, int dRow) {
        if (cursor < 0) {
            seedCursor();
        } else {
            for (int col = cursor % COLUMNS + dCol, row = cursor / COLUMNS + dRow;
                 col >= 0 && col < COLUMNS && row >= 0 && row < ROWS; col += dCol, row += dRow) {
                int slot = row * COLUMNS + col;
                if (save.boxed(storage, box, slot) != null) { cursor = slot; break; }
            }
        }
        announce();
        repaint();
    }

    /**
     * Enter or Space: the operation a click on the cursor's square performs,
     * which is to show the Pokémon there and tell the page.
     *
     * In arranging mode the keyboard is deliberately read-only. A keyed pickup
     * would have to reproduce the mouse's pick, place and cancel rules, and a
     * half-copy of them is worse than leaving the moves to the mouse until it
     * can be done properly.
     */
    private void activate() {
        if (arranging || cursor < 0) return;
        var mon = save.boxed(storage, box, cursor);
        if (mon == null) return;
        selected = mon;
        repaint();
        if (onSelect != null) onSelect.accept(mon);
    }

    /** Puts the cursor on the Pokémon already being shown, or the first the box holds. */
    private void seedCursor() {
        cursor = slotOf(selected);
        for (int slot = 0; cursor < 0 && slot < Gen3Save.PER_BOX; slot++)
            if (save.boxed(storage, box, slot) != null) cursor = slot;
    }

    /** The open box's slot holding this Pokémon, or -1 — the identity a redraw rings. */
    private int slotOf(Gen3Pokemon mon) {
        if (mon == null) return -1;
        for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
            var here = save.boxed(storage, box, slot);
            if (here != null && here.personality == mon.personality && here.otId == mon.otId) return slot;
        }
        return -1;
    }

    /** The occupied slots of the open box, in order: what the cursor moves between. */
    private List<Integer> occupied() {
        var slots = new ArrayList<Integer>();
        for (int slot = 0; slot < Gen3Save.PER_BOX; slot++)
            if (save.boxed(storage, box, slot) != null) slots.add(slot);
        return slots;
    }

    /** Keeps the accessible name and description on the box and the focused square. */
    private void announce() {
        getAccessibleContext().setAccessibleName("Pokémon storage, " + boxTitle());
        getAccessibleContext().setAccessibleDescription(readout());
    }

    /** The focused square written out: the box, the slot, and who is on it. */
    private String readout() {
        if (cursor < 0) return boxTitle() + ": no Pokémon";
        var mon = save.boxed(storage, box, cursor);
        String where = boxTitle() + ", slot " + (cursor + 1) + " of " + Gen3Save.PER_BOX;
        if (mon == null) return where + ": empty";
        return where + ": " + (mon.isEgg() ? "Egg" : GameView.name(mon) + ", level " + mon.level());
    }

    /** The box as both the header and a screen reader name it: its own name, or its number. */
    private String boxTitle() {
        String name = save.boxName(storage, box).strip();
        return name.isEmpty() ? "BOX " + (box + 1) : name;
    }

    private void click(int x, int y) {
        if (previousArrow().contains(x, y)) { turn(-1); return; }
        if (nextArrow().contains(x, y)) { turn(1); return; }
        if (arranging) { arrangeAt(x, y); return; }
        Gen3Pokemon found = null;
        int slot = slotAt(x, y);
        if (slot >= 0) found = save.boxed(storage, box, slot);
        else {
            int index = partyAt(x, y);
            if (index >= 0 && index < party.size()) found = party.get(index);
            else return;
        }
        selected = found;
        repaint();
        if (onSelect != null) onSelect.accept(found);
    }

    private void arrangeAt(int x, int y) {
        StorageEdit.Place place = null;
        boolean occupied = false;
        int slot = slotAt(x, y);
        if (slot >= 0) {
            place = new StorageEdit.Place(false, box, slot);
            occupied = save.boxed(storage, box, slot) != null;
        } else {
            int index = partyAt(x, y);
            if (index >= 0) {
                place = new StorageEdit.Place(true, -1, index);
                occupied = index < party.size();
            }
        }
        if (place == null) { cancelPickup(); return; }
        if (picked == null) {
            if (!occupied) return;                       // nothing there to pick up
            picked = occupiedAt(place);
            pickedPlace = place;
            repaint();
            return;
        }
        if (pickedPlace.equals(place)) { cancelPickup(); return; }   // same spot = change of mind
        var from = pickedPlace;
        cancelPickup();
        if (onArrange != null) onArrange.accept(from, place);
    }

    private Gen3Pokemon occupiedAt(StorageEdit.Place place) {
        if (place.party()) return place.slot() < party.size() ? party.get(place.slot()) : null;
        return save.boxed(storage, place.box(), place.slot());
    }

    private void turn(int by) {
        box = Math.floorMod(box + by, Gen3Save.BOXES);
        seedCursor();                 // the new box's squares, not the last box's slot number
        announce();
        repaint();
        if (onBox != null) onBox.accept(box);
    }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int gx = PAD, gy = HEADER + PAD;
        var paper = wallpaper(save.boxWallpaper(storage, box));
        if (paper != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(paper, gx, gy, boxWidth(), boxHeight(), null);
        } else {
            g.setColor(Theme.PANEL);
            g.fillRoundRect(gx, gy, boxWidth(), boxHeight(), Theme.RADIUS, Theme.RADIUS);
        }
        g.setColor(Theme.LINE);
        g.drawRoundRect(gx, gy, boxWidth(), boxHeight(), Theme.RADIUS, Theme.RADIUS);

        g.setFont(getFont().deriveFont(Font.BOLD, 15f));
        g.setColor(Theme.TEXT);
        String name = boxTitle();
        var metrics = g.getFontMetrics();
        g.drawString(name, gx + (boxWidth() - metrics.stringWidth(name)) / 2, PAD + 18);
        drawArrow(g, previousArrow(), true);
        drawArrow(g, nextArrow(), false);

        if (arranging) {
            g.setFont(getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(Theme.CYAN);
            // On the box's own header line, past the "‹" arrow, rather than in
            // the strip below it: down there the label sat on the box's top
            // edge, under the chevron and in the same band as the box name, so
            // it read as a broken piece of the header.
            g.drawString(picked == null ? "PICK A POKÉMON" : "CHOOSE A SPOT",
                previousArrow().x + previousArrow().width + Theme.SPACE_XS, PAD + 18);
        }
        if (picked != null) {
            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            // The readable gold: this is a line of type, and GOLD is 3.0:1 on
            // these grounds. Gold fills stay gold; gold letters use the pair.
            g.setColor(Theme.GOLD_TEXT);
            String who = picked.isEgg() ? "Egg" : GameView.name(picked);
            int w = g.getFontMetrics().stringWidth(who);
            g.drawString("MOVING  " + who, gx + boxWidth() - w - 46, PAD + 18);
        }

        for (int square = 0; square < Gen3Save.PER_BOX; square++) {
            int cx = gridX() + (square % COLUMNS) * CELL, cy = gridY() + (square / COLUMNS) * CELL;
            // The square's own outline, drawn whether or not a wallpaper is
            // installed: the party column beside it always draws its five, and
            // with no artwork an empty box was a bare filled rectangle that
            // read as a panel which had failed to render.
            g.setColor(Theme.LINE);
            g.drawRoundRect(cx, cy, CELL, CELL, Theme.RADIUS, Theme.RADIUS);
            drawMon(g, save.boxed(storage, box, square), cx, cy);
        }

        // The keyboard cursor, ringed on the square the arrows and Enter act on,
        // over the squares and in the accent the rest of the app uses for "the
        // keyboard is here". Drawn whenever the cursor stands somewhere, so the
        // cue is there when the grid takes the keyboard, not after the first key.
        if (cursor >= 0) {
            var ring = (Graphics2D) g.create();
            ring.setColor(Theme.CYAN);
            ring.setStroke(new java.awt.BasicStroke(Theme.RING));
            ring.drawRoundRect(gridX() + (cursor % COLUMNS) * CELL, gridY() + (cursor / COLUMNS) * CELL,
                CELL - 1, CELL - 1, Theme.RADIUS, Theme.RADIUS);
            ring.dispose();
        }

        int px = gx + boxWidth() + PARTY_GAP;
        g.setFont(getFont().deriveFont(Font.BOLD, 11f));
        g.setColor(Theme.CYAN);
        g.drawString("PARTY", px, PAD + 18);
        for (int i = 0; i < Gen3Save.PARTY_LIMIT; i++) {
            int cy = partyY() + i * CELL;
            g.setColor(Theme.LINE);
            g.drawRoundRect(px, cy, CELL, CELL, Theme.RADIUS, Theme.RADIUS);
            if (i < party.size()) drawMon(g, party.get(i), px, cy);
        }
        g.dispose();
    }

    private void drawArrow(Graphics2D g, Rectangle at, boolean left) {
        g.setColor(Theme.MUTED);
        int mid = at.y + at.height / 2, x = left ? at.x + at.width - 8 : at.x + 8;
        int tip = left ? at.x + 8 : at.x + at.width - 8;
        g.drawLine(x, at.y + 6, tip, mid);
        g.drawLine(x, at.y + at.height - 6, tip, mid);
    }

    private void drawMon(Graphics2D g, Gen3Pokemon mon, int x, int y) {
        if (mon == null) return;
        boolean picked = selected != null && selected.personality == mon.personality && selected.otId == mon.otId;
        if (picked) {
            // The same cue the rest of the app uses for "this one": the shared
            // ring thickness, in the ring colour that reads on this fill.
            g.setColor(Theme.ringFor(Theme.PANEL));
            g.setStroke(new java.awt.BasicStroke(Theme.RING));
            g.drawRoundRect(x + Theme.RING, y + Theme.RING, CELL - 2 * Theme.RING, CELL - 2 * Theme.RING,
                Theme.RADIUS, Theme.RADIUS);
            g.setStroke(new java.awt.BasicStroke(Theme.HAIRLINE));
        }
        int inset = 8, size = CELL - inset * 2;
        if (mon.isEgg()) {
            g.setColor(Theme.TEXT);
            g.fillOval(x + CELL / 2 - 12, y + CELL / 2 - 16, 24, 32);
            return;
        }
        var sprite = GameView.sprite(mon.nationalDex(), mon.shiny());
        if (sprite != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(sprite, x + inset, y + inset, size, size, null);
        } else {
            g.setColor(Theme.MUTED);
            g.setFont(getFont().deriveFont(10f));
            g.drawString(mon.nationalDex() > 0 ? "#" + mon.nationalDex() : "?", x + inset, y + CELL / 2);
        }
    }

    /** The extracted wallpaper, or null when the ROM artwork is not installed. */
    static BufferedImage wallpaper(int index) {
        return SpriteAssets.load(String.format("pc/wallpaper-%02d.png", Math.floorMod(index, Gen3Save.WALLPAPERS)));
    }

    /**
     * The grid's accessible surface.
     *
     * A bare JComponent carries no accessible context at all — it is the panels
     * that make one — so the grid makes its own, and fills it: one child per
     * occupied square and then one per party member, each named the way the
     * header names things. That is what lets a screen reader walk the box rather
     * than being told only about the one square the keyboard happens to be on.
     */
    private final class GridAccessible extends AccessibleJComponent {
        @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PANEL; }

        @Override public int getAccessibleChildrenCount() {
            return occupied().size() + party.size();
        }

        @Override public Accessible getAccessibleChild(int index) {
            var slots = occupied();
            if (index < slots.size()) {
                int slot = slots.get(index);
                return leaf(spoken(save.boxed(storage, box, slot), "slot " + (slot + 1)),
                    boxTitle() + ", slot " + (slot + 1) + " of " + Gen3Save.PER_BOX);
            }
            int member = index - slots.size();
            if (member < party.size())
                return leaf(spoken(party.get(member), "party slot " + (member + 1)),
                    "Party, slot " + (member + 1) + " of " + Gen3Save.PARTY_LIMIT);
            return null;
        }
    }

    /**
     * One square as a screen reader reads it, built on the label Swing already
     * gives a name and a role, rather than on a hand-rolled AccessibleContext.
     */
    private static Accessible leaf(String name, String description) {
        var label = new JLabel();
        label.getAccessibleContext().setAccessibleName(name);
        label.getAccessibleContext().setAccessibleDescription(description);
        return label;
    }

    /** One Pokémon written the way a screen reader should hear it. */
    private static String spoken(Gen3Pokemon mon, String where) {
        String who = mon.isEgg() ? "Egg" : GameView.name(mon) + ", level " + mon.level();
        return Character.toUpperCase(where.charAt(0)) + where.substring(1) + ": " + who;
    }
}
