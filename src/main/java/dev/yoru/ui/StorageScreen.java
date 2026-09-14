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
 * One box of the game's own Pokémon Storage System, read from the save (#44).
 *
 * Thirty squares, six across and five down, on the game's own wallpaper with
 * the box's name in its top band — exactly what the save holds, in the slots
 * the game put them in. Click a Pokémon to see it, or walk the occupied
 * squares with the arrow keys and press Enter on the one to show.
 *
 * The party, and the controls that turn boxes, are real components on the
 * Collection page (#8). This draws only the box, and shares arranging with the
 * party through an {@link Arrangement}, so a move can cross between them.
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
    static final int PAPER_WIDTH = 160, PAPER_HEIGHT = 144, SCALE = 2;
    static final int CELL = 24 * SCALE, GRID_X = 8 * SCALE, GRID_Y = 24 * SCALE;
    static final int PAD = 14;
    static final int COLUMNS = 6, ROWS = 5;

    private final Gen3Save save;
    private final byte[] storage;
    private final Consumer<Gen3Pokemon> onSelect;
    private final IntConsumer onBox;
    private final Arrangement arrangement;
    private int box;
    private Gen3Pokemon selected;
    /** The slot the keyboard cursor stands on in the open box, or -1 for an empty box. */
    private int cursor = -1;
    private Runnable toParty = () -> { };

    StorageScreen(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox) {
        this(save, box, onSelect, onBox, (from, to) -> { });
    }

    StorageScreen(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox,
                  BiConsumer<StorageEdit.Place, StorageEdit.Place> onArrange) {
        this(save, box, onSelect, onBox, new Arrangement(onArrange));
    }

    StorageScreen(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox, Arrangement arrangement) {
        this.save = save;
        this.storage = save.storage();
        this.onSelect = onSelect;
        this.onBox = onBox;
        this.arrangement = arrangement;
        this.box = Math.floorMod(box, Gen3Save.BOXES);
        setOpaque(false);
        var size = new Dimension(boxWidth() + PAD * 2, boxHeight() + PAD * 2);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
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
        bind("F6", "storage.switchArea", () -> toParty.run());
        setToolTipText("Arrow keys: move · Enter: select or place · F6: party · Page Up/Down: boxes · Escape: cancel");
        bind("PAGE_UP", "storage.previousBox", () -> turn(-1));
        bind("PAGE_DOWN", "storage.nextBox", () -> turn(1));
        // Escape puts back whatever was picked up, whatever has focus.
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("ESCAPE"), "storage.cancel");
        getActionMap().put("storage.cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { arrangement.cancel(); }
        });
        arrangement.listen(() -> { seedCursor(); announce(); repaint(); });
    }

    int box() { return box; }
    Gen3Pokemon selected() { return selected; }
    /** The slot the keyboard cursor stands on in the open box, or -1. */
    int cursor() { return cursor; }
    Arrangement arrangement() { return arrangement; }

    void setArranging(boolean on) { arrangement.setOn(on); }
    boolean arranging() { return arrangement.on(); }
    Gen3Pokemon picked() { return arrangement.picked(); }
    /** Puts back whatever was picked up without moving anything. */
    void cancelPickup() { arrangement.cancel(); }

    /** F6 from the boxes goes to the party. */
    void onSwitchArea(Runnable toParty) { this.toParty = toParty; }

    /** Marks the Pokémon the details show, wherever it was chosen; the cursor follows it into this box. */
    void select(Gen3Pokemon mon) {
        selected = mon;
        int slot = slotOf(mon);
        if (slot >= 0) cursor = slot;
        announce();
        repaint();
    }

    /** Puts the cursor on a square: how the page carries the keyboard across the rebuild a move causes. */
    void placeCursor(int slot) {
        if (slot < 0 || slot >= Gen3Save.PER_BOX) return;
        cursor = slot;
        announce();
        repaint();
    }

    /** Opens a box by index, as the page's box selector does. */
    void showBox(int index) {
        int target = Math.floorMod(index, Gen3Save.BOXES);
        if (target != box) turn(target - box);
    }

    /** Turns boxes, wrapping as the game does; Page Up and Down and the page's buttons all come here. */
    void turn(int by) {
        box = Math.floorMod(box + by, Gen3Save.BOXES);
        cursor = -1;
        seedCursor();                 // the new box's squares, not the last box's slot number
        announce();
        repaint();
        if (onBox != null) onBox.accept(box);
    }

    /** How many of the open box's thirty squares hold a Pokémon. */
    int occupancy() { return occupied().size(); }

    static int boxWidth() { return PAPER_WIDTH * SCALE; }
    static int boxHeight() { return PAPER_HEIGHT * SCALE; }
    static int gridX() { return PAD + GRID_X; }
    static int gridY() { return PAD + GRID_Y; }

    /** The slot within the open box under a point, or -1. */
    int slotAt(int x, int y) {
        int gx = x - gridX(), gy = y - gridY();
        if (gx < 0 || gy < 0 || gx >= COLUMNS * CELL || gy >= ROWS * CELL) return -1;
        return (gy / CELL) * COLUMNS + gx / CELL;
    }

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
     * row or column ahead is empty, so the cursor never leaves the grid. While
     * arranging every square is a destination, so it steps cell by cell.
     */
    private void moveCursor(int dCol, int dRow) {
        if (cursor < 0) {
            seedCursor();
        } else {
            for (int col = cursor % COLUMNS + dCol, row = cursor / COLUMNS + dRow;
                 col >= 0 && col < COLUMNS && row >= 0 && row < ROWS; col += dCol, row += dRow) {
                int slot = row * COLUMNS + col;
                if (arrangement.on() || save.boxed(storage, box, slot) != null) { cursor = slot; break; }
            }
        }
        announce();
        repaint();
    }

    /**
     * Enter or Space: what a click on the cursor's square does — show the
     * Pokémon there, or, while arranging, pick it up or set one down.
     */
    private void activate() {
        if (cursor < 0) return;
        var mon = save.boxed(storage, box, cursor);
        if (arrangement.on()) {
            arrangement.choose(new StorageEdit.Place(false, box, cursor), mon);
            return;
        }
        if (mon == null) return;
        show(mon);
    }

    private void show(Gen3Pokemon mon) {
        selected = mon;
        repaint();
        if (onSelect != null) onSelect.accept(mon);
    }

    /** Puts the cursor on the Pokémon already being shown, or the first the box holds. */
    private void seedCursor() {
        if (arrangement.on()) { if (cursor < 0) cursor = 0; return; }
        int shown = slotOf(selected);
        if (shown >= 0) { cursor = shown; return; }
        if (cursor >= 0 && save.boxed(storage, box, cursor) != null) return;
        cursor = -1;
        for (int slot = 0; cursor < 0 && slot < Gen3Save.PER_BOX; slot++)
            if (save.boxed(storage, box, slot) != null) cursor = slot;
    }

    /** The open box's slot holding this Pokémon, or -1 — the identity a redraw rings. */
    private int slotOf(Gen3Pokemon mon) {
        if (mon == null) return -1;
        for (int slot = 0; slot < Gen3Save.PER_BOX; slot++)
            if (Arrangement.same(save.boxed(storage, box, slot), mon)) return slot;
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
        getAccessibleContext().setAccessibleName("Pokémon storage, " + boxTitle() + ", "
            + occupancy() + " of " + Gen3Save.PER_BOX + " occupied");
        getAccessibleContext().setAccessibleDescription(readout());
    }

    /** The focused square written out: the box, the slot, and who is on it. */
    private String readout() {
        if (cursor < 0) return boxTitle() + ": no Pokémon";
        var mon = save.boxed(storage, box, cursor);
        String where = boxTitle() + ", slot " + (cursor + 1) + " of " + Gen3Save.PER_BOX;
        String said = where + (mon == null ? ": empty" : ": " + (mon.isEgg() ? "Egg" : GameView.name(mon) + ", level " + mon.level()));
        return arrangement.on() ? said + ". " + arrangement.instruction() : said;
    }

    /** The box as both the band and a screen reader name it: its own name, or its number. */
    String boxTitle() {
        String name = save.boxName(storage, box).strip();
        return name.isEmpty() ? "BOX " + (box + 1) : name;
    }

    private void click(int x, int y) {
        int hit = slotAt(x, y);
        if (hit < 0) return;
        cursor = hit;
        announce();
        var found = save.boxed(storage, box, hit);
        if (arrangement.on()) {
            arrangement.choose(new StorageEdit.Place(false, box, hit), found);
            return;
        }
        show(found);
    }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int gx = PAD, gy = PAD;
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

        // The name sits in the wallpaper's own band, where the game writes it,
        // on a plate of the panel so it reads on every wallpaper.
        g.setFont(getFont().deriveFont(Font.BOLD, 15f));
        String name = boxTitle();
        var metrics = g.getFontMetrics();
        int nameWidth = metrics.stringWidth(name), plateX = gx + (boxWidth() - nameWidth) / 2 - Theme.SPACE_SM;
        int plateY = gy + (GRID_Y - metrics.getHeight()) / 2 - Theme.SPACE_XS;
        g.setColor(Theme.PANEL);
        g.fillRoundRect(plateX, plateY, nameWidth + 2 * Theme.SPACE_SM, metrics.getHeight() + 2 * Theme.SPACE_XS,
            Theme.RADIUS, Theme.RADIUS);
        g.setColor(Theme.TEXT);
        g.drawString(name, gx + (boxWidth() - nameWidth) / 2, plateY + Theme.SPACE_XS + metrics.getAscent());

        for (int square = 0; square < Gen3Save.PER_BOX; square++) {
            int cx = gridX() + (square % COLUMNS) * CELL, cy = gridY() + (square / COLUMNS) * CELL;
            // The square's own outline, drawn whether or not a wallpaper is
            // installed: with no artwork an empty box was a bare filled
            // rectangle that read as a panel which had failed to render.
            g.setColor(Theme.LINE);
            g.drawRoundRect(cx, cy, CELL, CELL, Theme.RADIUS, Theme.RADIUS);
            drawMon(g, save.boxed(storage, box, square), cx, cy);
        }

        // Where a held Pokémon came from, in the readable gold, so the source
        // stays visible while its destination is chosen.
        var from = arrangement.from();
        if (from != null && !from.party() && from.box() == box) {
            var ring = (Graphics2D) g.create();
            ring.setColor(Theme.GOLD_TEXT);
            ring.setStroke(new BasicStroke(Theme.RING));
            ring.drawRoundRect(gridX() + (from.slot() % COLUMNS) * CELL + Theme.RING,
                gridY() + (from.slot() / COLUMNS) * CELL + Theme.RING, CELL - 2 * Theme.RING, CELL - 2 * Theme.RING,
                Theme.RADIUS, Theme.RADIUS);
            ring.dispose();
        }

        // The keyboard cursor, ringed on the square the arrows and Enter act on,
        // over the squares and in the accent the rest of the app uses for "the
        // keyboard is here". Drawn whenever the cursor stands somewhere, so the
        // cue is there when the grid takes the keyboard, not after the first key.
        if (cursor >= 0) {
            var ring = (Graphics2D) g.create();
            ring.setColor(Theme.CYAN);
            ring.setStroke(new BasicStroke(Theme.RING));
            ring.drawRoundRect(gridX() + (cursor % COLUMNS) * CELL, gridY() + (cursor / COLUMNS) * CELL,
                CELL - 1, CELL - 1, Theme.RADIUS, Theme.RADIUS);
            ring.dispose();
        }
        g.dispose();
    }

    private void drawMon(Graphics2D g, Gen3Pokemon mon, int x, int y) {
        if (mon == null) return;
        if (Arrangement.same(selected, mon) && !arrangement.on()) {
            // The same cue the rest of the app uses for "this one": the shared
            // ring thickness, in the ring colour that reads on this fill.
            g.setColor(Theme.ringFor(Theme.PANEL));
            g.setStroke(new BasicStroke(Theme.RING));
            g.drawRoundRect(x + Theme.RING, y + Theme.RING, CELL - 2 * Theme.RING, CELL - 2 * Theme.RING,
                Theme.RADIUS, Theme.RADIUS);
            g.setStroke(new BasicStroke(Theme.HAIRLINE));
        }
        int inset = 8, size = CELL - inset * 2;
        if (mon.isEgg()) {
            g.setColor(Theme.TEXT);
            g.fillOval(x + CELL / 2 - 12, y + CELL / 2 - 16, 24, 32);
            return;
        }
        if (!GameView.readable(mon)) {
            // Occupied but unreadable: kept in its slot, marked, never edited.
            g.setColor(Theme.DANGER);
            g.setFont(getFont().deriveFont(Font.BOLD, 18f));
            var metrics = g.getFontMetrics();
            g.drawString("!", x + (CELL - metrics.stringWidth("!")) / 2, y + (CELL + metrics.getAscent()) / 2 - 2);
            return;
        }
        var sprite = GameView.sprite(mon.nationalDex(), mon.shiny());
        if (sprite != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(sprite, x + inset, y + inset, size, size, null);
        } else {
            // A plain shape where the picture would be. Never the dex number:
            // a number where a picture should be reads as the Pokémon's name.
            g.setColor(Theme.MUTED);
            g.fillOval(x + CELL / 2 - 6, y + 10, 12, 12);
            g.fillRoundRect(x + CELL / 2 - 11, y + 24, 22, 14, 10, 10);
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
     * occupied square, each named the way the band names the box. That lets a
     * screen reader walk the box rather than hear only the square the keyboard
     * is on. The party is its own row of buttons, with names of their own.
     */
    private final class GridAccessible extends AccessibleJComponent {
        @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PANEL; }

        @Override public int getAccessibleChildrenCount() { return occupied().size(); }

        @Override public Accessible getAccessibleChild(int index) {
            var slots = occupied();
            if (index < 0 || index >= slots.size()) return null;
            int slot = slots.get(index);
            return leaf(spoken(save.boxed(storage, box, slot), "slot " + (slot + 1)),
                boxTitle() + ", slot " + (slot + 1) + " of " + Gen3Save.PER_BOX);
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
        String who = mon.isEgg() ? "Egg" : !GameView.readable(mon) ? "Cannot read Pokémon" : GameView.name(mon) + ", level " + mon.level();
        return Character.toUpperCase(where.charAt(0)) + where.substring(1) + ": " + who;
    }
}
