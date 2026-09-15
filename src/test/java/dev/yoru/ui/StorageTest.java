package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import dev.yoru.game.Gen3Fixture;
import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.StudyGift;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * The storage system, read from the game's own save (#44).
 *
 * Three things have to hold and only the first is visible in a diff: a click
 * lands on the Pokémon under it, the arrows turn boxes the way the game does,
 * and each box actually draws its own wallpaper rather than merely being handed
 * one. The last is checked by rendering against a wallpaper of a colour that
 * appears nowhere in any theme, then looking for that colour inside the grid.
 *
 * The save is invented, built by Gen3Fixture; a real one is somebody's game.
 */
public final class StorageTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** A colour no palette uses, so finding it proves the wallpaper was drawn. */
    private static final Color PAPER = new Color(0xFF00FF);
    private static final int BOX = 3, SLOT = 8, BOXED = 280;

    /** Two in the party, one Pokémon in box 4, every box on its own wallpaper, starter flag set. */
    private static Gen3Save save() {
        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 4), "TESTER", 0, 12345, 54321);
        raw = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 255, 5, 1), Gen3Fixture.member(raw, 25, 9, 2)));
        raw = Gen3Fixture.withFlag(raw, Gen3Save.FLAG_SYS_POKEMON_GET);
        var save = Gen3Save.read(raw);
        var storage = save.storage();
        var boxed = StudyGift.build(UUID.nameUUIDFromBytes(new byte[]{1}), BOXED, 7, save.trainer(), null, 0).encode();
        System.arraycopy(boxed, 0, storage, Gen3Save.slotOffset(BOX, SLOT), Gen3Pokemon.BOX_SIZE);
        for (int box = 0; box < Gen3Save.BOXES; box++) Gen3Save.boxWallpaper(storage, box, box % Gen3Save.WALLPAPERS);
        save.storage(storage);
        return Gen3Save.read(save.bytes());
    }

    /** The usual save with a second Pokémon two squares along in the same box. */
    private static Gen3Save saveWithTwoBoxed() {
        var save = save();
        var storage = save.storage();
        var second = StudyGift.build(UUID.nameUUIDFromBytes(new byte[]{2}), 25, 12, save.trainer(), null, 0).encode();
        System.arraycopy(second, 0, storage, Gen3Save.slotOffset(BOX, SLOT + 2), Gen3Pokemon.BOX_SIZE);
        save.storage(storage);
        return Gen3Save.read(save.bytes());
    }

    private static void click(StorageScreen screen, int x, int y) {
        screen.dispatchEvent(new MouseEvent(screen, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false));
    }

    /** Presses one of the grid's own keys, through the action the binding names. */
    private static void press(StorageScreen screen, String name) {
        var action = screen.getActionMap().get(name);
        check(action != null, "the grid binds " + name);
        action.actionPerformed(new java.awt.event.ActionEvent(screen, java.awt.event.ActionEvent.ACTION_PERFORMED, name));
    }

    private static boolean bound(StorageScreen screen, String key) {
        return screen.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
            .get(javax.swing.KeyStroke.getKeyStroke(key)) != null;
    }

    /** A click lands on the square under it, and outside the grid on nothing. */
    private static void hitTestingFindsTheSquare() {
        var screen = new StorageScreen(save(), 2, mon -> { }, box -> { });
        int gx = StorageScreen.gridX(), gy = StorageScreen.gridY(), cell = StorageScreen.CELL;
        check(screen.slotAt(gx + 4, gy + 4) == 0, "the top-left square is the box's first slot");
        check(screen.slotAt(gx + cell + 4, gy + 4) == 1, "one cell right is the next square");
        check(screen.slotAt(gx + 4, gy + cell + 4) == StorageScreen.COLUMNS, "one cell down is a row on");
        // The corner of the last square, not one pixel past it.
        check(screen.slotAt(gx + StorageScreen.COLUMNS * cell - 1, gy + StorageScreen.ROWS * cell - 1) == Gen3Save.PER_BOX - 1,
            "the bottom-right square is the box's last slot");
        check(screen.slotAt(gx - 1, gy + 4) < 0 && screen.slotAt(gx + 4, gy - 1) < 0, "outside the grid is no square");
        check(screen.slotAt(gx + StorageScreen.COLUMNS * cell + 1, gy + 4) < 0, "and so is the margin beside it");
        // The grid starts below the wallpaper's band, where the box's name sits, as in the game.
        check(screen.slotAt(gx + 4, StorageScreen.PAD + 4) < 0, "the band across the top of the wallpaper is not a square");
        check(StorageScreen.gridY() + StorageScreen.ROWS * cell <= screen.getPreferredSize().height && cell >= 44,
            "the grid fits inside the screen, in squares at least 44 px across (#8)");
    }

    /** Clicking shows what the save holds there: a boxed Pokémon, a party member, or nothing. */
    private static void clickingShowsWhatIsThere() {
        var picked = new ArrayList<Gen3Pokemon>();
        var screen = new StorageScreen(save(), BOX, picked::add, box -> { });
        int cell = StorageScreen.CELL;
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4);
        check(picked.size() == 1 && picked.get(0) != null && picked.get(0).nationalDex() == BOXED,
            "clicking the boxed Pokémon selects it");
        check(screen.selected() == picked.get(0), "and the screen marks it");
        click(screen, StorageScreen.gridX() + 4, StorageScreen.gridY() + 4);
        check(picked.size() == 2 && picked.get(1) == null && screen.selected() == null, "an empty square selects nothing");

        // The party is its own row of buttons now (#8).
        var party = new PartyStrip(save().party(), new Arrangement((from, to) -> { }), picked::add);
        party.slot(1).doClick();
        check(picked.size() == 3 && picked.get(2).nationalDex() == 25, "the party's second button is its second member");
        check(!party.slot(5).isEnabled(), "an empty party slot is not a control while not arranging");
        party.slot(5).doClick();
        check(picked.size() == 3, "and clicking it does nothing");
    }

    /** The arrows change box and wrap rather than stopping at the ends, telling the page each time. */
    private static void arrowsTurnTheBox() {
        var turned = new ArrayList<Integer>();
        var screen = new StorageScreen(save(), 0, mon -> { }, turned::add);
        screen.turn(-1);
        check(screen.box() == Gen3Save.BOXES - 1, "going back from the first box wraps to the last, got " + screen.box());
        screen.turn(1);
        check(screen.box() == 0, "and forward wraps back round");
        check(turned.equals(List.of(Gen3Save.BOXES - 1, 0)), "and the page hears which box is open, got " + turned);
        screen.showBox(5);
        check(screen.box() == 5 && turned.getLast() == 5, "the box selector opens a box directly");
        screen.showBox(5);
        check(turned.size() == 3, "and choosing the open box again turns nothing");
    }

    private static BufferedImage render(StorageScreen screen) {
        screen.setSize(screen.getPreferredSize());
        screen.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12));
        var image = new BufferedImage(screen.getWidth(), screen.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        screen.paint(g);
        g.dispose();
        return image;
    }

    private static int countOf(BufferedImage image, Color colour, int x0, int y0, int x1, int y1) {
        int found = 0;
        for (int y = y0; y < Math.min(y1, image.getHeight()); y++)
            for (int x = x0; x < Math.min(x1, image.getWidth()); x++)
                if ((image.getRGB(x, y) & 0xFFFFFF) == (colour.getRGB() & 0xFFFFFF)) found++;
        return found;
    }

    /**
     * The box is drawn on its wallpaper, and works without one.
     *
     * "Renders the wallpaper" is exactly the sort of claim that stays true in
     * source while being false on screen, so a wallpaper of a colour no theme
     * contains is installed and then looked for inside the grid.
     */
    private static void drawsTheWallpaperAndSurvivesWithout() throws IOException {
        Theme.apply(ThemeId.MIDNIGHT);
        var save = save();

        // Without artwork the grid still draws, on the themed panel instead.
        System.clearProperty("yoru.art.dir");
        SpriteAssets.refresh();
        var bare = render(new StorageScreen(save, 0, mon -> { }, box -> { }));
        int gx = StorageScreen.PAD, gy = StorageScreen.PAD;
        check(countOf(bare, Theme.PANEL, gx + 4, gy + 4, gx + StorageScreen.boxWidth(), gy + StorageScreen.boxHeight()) > 1000,
            "with no wallpaper the box falls back to the themed panel");

        var dir = Files.createTempDirectory("yoru-pc");
        try {
            var paper = new BufferedImage(StorageScreen.PAPER_WIDTH, StorageScreen.PAPER_HEIGHT, BufferedImage.TYPE_INT_RGB);
            var pg = paper.createGraphics();
            pg.setColor(PAPER);
            pg.fillRect(0, 0, paper.getWidth(), paper.getHeight());
            pg.dispose();
            Files.createDirectories(dir.resolve("pc"));
            ImageIO.write(paper, "png", dir.resolve("pc/wallpaper-00.png").toFile());
            System.setProperty("yoru.art.dir", dir.toString());
            SpriteAssets.refresh();

            check(StorageScreen.wallpaper(0) != null, "the wallpaper resolves through the artwork path");
            var papered = render(new StorageScreen(save, 0, mon -> { }, box -> { }));
            int inside = countOf(papered, PAPER, gx + 4, gy + 4, gx + StorageScreen.boxWidth() - 4, gy + StorageScreen.boxHeight() - 4);
            check(inside > StorageScreen.boxWidth() * StorageScreen.boxHeight() / 2, "the wallpaper fills the box, found " + inside + " px");
            check(countOf(papered, PAPER, 0, 0, StorageScreen.PAD, gy) == 0, "and does not spill outside the grid");

            // Box 1 is on wallpaper 1, which is not installed here, so it must
            // fall back rather than reusing box 0's paper.
            var other = render(new StorageScreen(save, 1, mon -> { }, box -> { }));
            check(countOf(other, PAPER, gx, gy, gx + StorageScreen.boxWidth(), gy + StorageScreen.boxHeight()) == 0,
                "each box draws its own wallpaper, not the first one");
        } finally {
            System.clearProperty("yoru.art.dir");
            SpriteAssets.refresh();
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            }
        }
    }

    /** In arranging mode, pick up and place, through the clicks a player makes. */
    private static void arrangingPicksUpAndPlaces() {
        var requests = new ArrayList<String>();
        var screen = new StorageScreen(save(), BOX, mon -> { }, box -> { }, (from, to) ->
            requests.add(from + " -> " + to));
        screen.setArranging(true);
        int cell = StorageScreen.CELL;
        int srcX = StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4;
        int srcY = StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4;

        // Pick up the boxed Pokémon, then place it in the party's first empty cell.
        click(screen, srcX, srcY);
        check(screen.picked() != null && screen.picked().nationalDex() == BOXED, "the boxed Pokémon is picked up");
        // Set it down in the party's first empty slot: another component, the same arrangement.
        var party = new PartyStrip(save().party(), screen.arrangement(), mon -> { });
        check(party.slot(2).isEnabled(), "an empty party slot is a destination while arranging");
        party.slot(2).doClick();
        check(requests.size() == 1 && requests.get(0).equals("Place[party=false, box=" + BOX + ", slot=" + SLOT
            + "] -> Place[party=true, box=-1, slot=2]"), "the page hears from and to, got " + requests);
        check(screen.picked() == null, "and the pickup is cleared after placing");

        // Clicking the picked Pokémon again cancels instead of reporting.
        click(screen, srcX, srcY);
        click(screen, srcX, srcY);
        check(requests.size() == 1, "clicking the picked Pokémon again cancels, got " + requests);

        // Turning arranging off drops the pickup.
        click(screen, srcX, srcY);
        screen.setArranging(false);
        check(screen.picked() == null, "leaving arranging mode drops the pickup");
    }

    /** The full trip: clicks pick up and place, the vault's save changes, nothing else does. */
    private static void aMoveEditsTheSave() throws IOException {
        var repo = new MemoryRepo();
        var tracker = new dev.yoru.application.Tracker(repo, java.time.Clock.systemUTC());
        tracker.gameSaved(save().bytes());
        var screen = new StorageScreen(dev.yoru.game.Gen3Save.read(tracker.state().game().bytes()), BOX,
            mon -> { }, box -> { }, (from, to) -> {
                try {
                    var before = tracker.state().game().bytes();
                    tracker.editSave(before, dev.yoru.game.StorageEdit.move(before, from, to));
                } catch (IOException e) { throw new RuntimeException(e); }
            });
        screen.setArranging(true);
        int cell = StorageScreen.CELL;
        int srcX = StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4;
        int srcY = StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4;
        int toSlot = SLOT + 1;
        click(screen, srcX, srcY);
        click(screen, StorageScreen.gridX() + (toSlot % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (toSlot / StorageScreen.COLUMNS) * cell + 4);
        var after = dev.yoru.game.Gen3Save.read(tracker.state().game().bytes());
        check(after.boxed(after.storage(), BOX, SLOT) == null, "the source slot emptied in the vault's save");
        check(after.boxed(after.storage(), BOX, toSlot) != null, "and the neighbour slot holds it");
    }

    /**
     * Arranging says what to do in words, and rings where a held Pokémon came
     * from in the readable gold (#8). The hint used to be painted into the box's
     * header in small capitals, which a screen reader never heard.
     */
    private static void arrangingStateIsVisible() {
        Theme.apply(ThemeId.MIDNIGHT);
        var screen = new StorageScreen(saveWithTwoBoxed(), BOX, mon -> { }, box -> { }, (from, to) -> { });
        var arrangement = screen.arrangement();
        check(arrangement.instruction().isEmpty(), "no instruction while not arranging");
        screen.setArranging(true);
        check(arrangement.instruction().equals("Choose a Pokémon, then choose its destination."),
            "arranging starts by saying what to do: " + arrangement.instruction());
        check(screen.getAccessibleContext().getAccessibleDescription().contains("Choose a Pokémon"),
            "and a screen reader hears it with the square");
        int cell = StorageScreen.CELL;
        int sx = StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell;
        int sy = StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell;
        click(screen, sx + 4, sy + 4);
        check(arrangement.instruction().startsWith("Moving ") && arrangement.instruction().contains("box " + (BOX + 1) + ", slot " + (SLOT + 1)),
            "a pickup names who is moving and from where: " + arrangement.instruction());
        // The cursor's accent ring sits on the same square until the arrows move it off.
        press(screen, "storage.right");
        check(countOf(render(screen), Theme.GOLD_TEXT, sx, sy, sx + cell, sy + cell) > 0, "the source square is ringed in gold");
        // Escape drops the pickup through the window-level binding.
        screen.getActionMap().get("storage.cancel").actionPerformed(null);
        check(screen.picked() == null && arrangement.instruction().startsWith("Choose"),
            "Escape drops the pickup, and the instruction starts again");
        check(countOf(render(screen), Theme.GOLD_TEXT, sx, sy, sx + cell, sy + cell) == 0, "and the gold ring goes with it");
    }

    /**
     * The keyboard's way round the box: a cursor on a square, arrows that skip
     * the empties between, Enter for what a click shows, and a screen reader
     * told where it stands.
     */
    private static void keyboardWalksTheBox() {
        var save = saveWithTwoBoxed();
        var picked = new ArrayList<Gen3Pokemon>();
        var screen = new StorageScreen(save, BOX, picked::add, box -> { });
        check(screen.isFocusable(), "the grid takes the keyboard");
        check(bound(screen, "LEFT") && bound(screen, "RIGHT") && bound(screen, "UP") && bound(screen, "DOWN"),
            "the arrows are bound on the grid itself");
        check(bound(screen, "PAGE_UP") && bound(screen, "PAGE_DOWN"), "and so is a way to turn the box");

        // The cursor stands on a square from the start, and a screen reader is
        // told which — no arrow key has been pressed yet.
        check(screen.cursor() == SLOT, "the cursor starts on the box's first Pokémon, got " + screen.cursor());
        String told = screen.getAccessibleContext().getAccessibleDescription();
        check(told.contains("slot " + (SLOT + 1) + " of " + Gen3Save.PER_BOX),
            "the announcement names the square, got " + told);
        check(told.contains(GameView.name(save.boxed(save.storage(), BOX, SLOT))),
            "and the Pokémon on it, got " + told);
        check(screen.getAccessibleContext().getAccessibleName().contains("BOX " + (BOX + 1)),
            "and the name says which box, got " + screen.getAccessibleContext().getAccessibleName());

        // Occupied squares are children of the grid: what a screen reader can
        // walk, rather than only the square the keyboard is on. The party has
        // buttons of its own now (#8), so it is not among them.
        var context = screen.getAccessibleContext();
        check(context.getAccessibleChildrenCount() == 2,
            "the two boxed Pokémon are children, got " + context.getAccessibleChildrenCount());
        check(context.getAccessibleChild(0).getAccessibleContext().getAccessibleName().contains("Slot " + (SLOT + 1)),
            "the first child is the first occupied square, got "
                + context.getAccessibleChild(0).getAccessibleContext().getAccessibleName());
        check(context.getAccessibleName().contains("2 of " + Gen3Save.PER_BOX + " occupied"),
            "and the box's name says how full it is, got " + context.getAccessibleName());

        // Right steps over the empty square between the two and lands on the next.
        press(screen, "storage.right");
        check(screen.cursor() == SLOT + 2, "right lands on the next occupied square, got " + screen.cursor());
        check(screen.getAccessibleContext().getAccessibleDescription().contains("slot " + (SLOT + 3) + " of"),
            "and the announcement follows it");
        press(screen, "storage.left");
        check(screen.cursor() == SLOT, "left comes back to the one it passed");
        press(screen, "storage.left");
        check(screen.cursor() == SLOT, "and stops at the wall rather than leaving the box");
        press(screen, "storage.down");
        check(screen.cursor() == SLOT, "as does down, on an empty column");

        // Enter, and Space, show the Pokémon under the cursor: what a click on
        // that square does, through the page's own callback.
        press(screen, "storage.activate");
        check(picked.size() == 1 && picked.get(0) != null && picked.get(0).nationalDex() == BOXED,
            "Enter shows the Pokémon under the cursor");
        check(screen.selected() == picked.get(0), "and the screen marks it");
        press(screen, "storage.right");
        press(screen, "storage.activate");
        check(picked.size() == 2 && picked.get(1).nationalDex() == 25, "and the next square's after another arrow");
        check(screen.selected() == picked.get(1), "with the screen marking that one too");

        // Focus arrival re-reads the box and re-seeds the cursor, rather than
        // waiting for the first arrow. A component that was never shown never
        // receives a dispatched FocusEvent, so the listener is called the way
        // the focus manager calls it.
        check(screen.getFocusListeners().length > 0, "the grid listens for the keyboard arriving");
        press(screen, "storage.left");
        check(screen.cursor() == SLOT, "the cursor can leave the Pokémon being shown");
        for (var listener : screen.getFocusListeners())
            listener.focusGained(new java.awt.event.FocusEvent(screen, java.awt.event.FocusEvent.FOCUS_GAINED));
        check(screen.cursor() == SLOT + 2, "arriving on the grid puts the cursor back on the Pokémon it shows");

        // A box with nothing in it has nowhere to stand, and Enter there is safe.
        press(screen, "storage.nextBox");
        check(screen.box() == BOX + 1, "Page Down turns to the next box, got " + screen.box());
        check(screen.cursor() < 0, "and an empty box has no square to stand on");
        int shown = picked.size();
        press(screen, "storage.activate");
        check(picked.size() == shown, "Enter on an empty box shows nothing");
        press(screen, "storage.previousBox");
        check(screen.box() == BOX, "Page Up turns back");

        // The keyboard shares the mouse pickup rules.
        screen.setArranging(true);
        shown = picked.size();
        press(screen, "storage.activate");
        check(picked.size() == shown && screen.picked() != null, "Enter picks up without changing the details selection");
        press(screen, "storage.cancel");
        check(screen.picked() == null, "Escape cancels keyboard pickup");

        // A click and the keyboard agree: the cursor stands where the click
        // landed, so the arrows carry on from the Pokémon just shown.
        screen.setArranging(false);
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * StorageScreen.CELL + 4,
            StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * StorageScreen.CELL + 4);
        check(screen.selected() != null && screen.selected().nationalDex() == BOXED, "the click showed the first Pokémon");
        for (var listener : screen.getFocusListeners())
            listener.focusGained(new java.awt.event.FocusEvent(screen, java.awt.event.FocusEvent.FOCUS_GAINED));
        check(screen.cursor() == SLOT, "and taking the keyboard puts the cursor on it, got " + screen.cursor());
    }

    /** The keyboard arranges across the party row and the boxes, and reaches empty destinations in both. */
    private static void keyboardArrangesPartyAndEmptyBoxes() {
        var moves = new ArrayList<dev.yoru.game.StorageEdit.Place>();
        var shown = new ArrayList<Gen3Pokemon>();
        var save = save();
        var screen = new StorageScreen(save, BOX, shown::add, box -> { }, (from, to) -> {
            moves.add(from); moves.add(to);
        });
        var party = new PartyStrip(save.party(), screen.arrangement(), shown::add);
        var reached = new ArrayList<String>();
        screen.onSwitchArea(() -> reached.add("party"));
        party.onSwitchArea(() -> reached.add("boxes"));
        check(bound(screen, "F6"), "F6 on the box goes to the party");
        press(screen, "storage.switchArea");
        var slotKeys = party.slot(1).getInputMap(javax.swing.JComponent.WHEN_FOCUSED);
        check(slotKeys.get(javax.swing.KeyStroke.getKeyStroke("F6")) != null
            && slotKeys.get(javax.swing.KeyStroke.getKeyStroke("RIGHT")) != null, "F6 and the arrows are bound on each party slot");
        party.slot(1).getActionMap().get("party.switchArea").actionPerformed(null);
        check(reached.equals(List.of("party", "boxes")), "F6 goes both ways, got " + reached);

        party.slot(1).doClick();
        check(shown.getLast().nationalDex() == 25, "a party slot selects its member");
        check(party.slot(1).getAccessibleContext().getAccessibleName().startsWith("Party, slot 2 of 6: "),
            "and names itself, got " + party.slot(1).getAccessibleContext().getAccessibleName());
        check(party.slot(4).getAccessibleContext().getAccessibleName().endsWith(": empty"), "an empty slot says so");

        screen.setArranging(true);
        party.slot(1).doClick();
        check(screen.picked() != null && screen.picked().nationalDex() == 25, "a party member is picked up");
        press(screen, "storage.nextBox");
        press(screen, "storage.right");
        int destination = screen.cursor();
        press(screen, "storage.activate");
        check(moves.size() == 2, "one keyboard move submitted");
        check(moves.get(0).party() && moves.get(0).slot() == 1, "move starts in second party slot");
        check(!moves.get(1).party() && moves.get(1).box() == BOX + 1 && moves.get(1).slot() == destination,
            "move reaches an empty square in another box");
        check(screen.picked() == null, "placement clears pickup");

        party.slot(0).doClick();
        check(party.slot(4).isEnabled(), "empty party slots are destinations while arranging");
        party.slot(4).doClick();
        check(moves.size() == 4 && moves.get(3).party() && moves.get(3).slot() == 4,
            "empty party destinations are reachable, got " + moves);
    }

    /** The cursor is drawn where it stands, from the first frame, and follows the arrows. */
    private static void theCursorIsDrawn() {
        Theme.apply(ThemeId.MIDNIGHT);
        var screen = new StorageScreen(saveWithTwoBoxed(), BOX, mon -> { }, box -> { });
        int cell = StorageScreen.CELL, gy = StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell;
        int hereX = StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell;
        int nextX = StorageScreen.gridX() + ((SLOT + 2) % StorageScreen.COLUMNS) * cell;
        check(countOf(render(screen), Theme.CYAN, hereX, gy, hereX + cell, gy + cell) > 0,
            "the cursor's square is ringed with no key pressed");
        check(countOf(render(screen), Theme.CYAN, nextX, gy, nextX + cell, gy + cell) == 0,
            "and the square it is not on is not");
        press(screen, "storage.right");
        check(screen.cursor() == SLOT + 2, "the arrow moved the cursor");
        check(countOf(render(screen), Theme.CYAN, nextX, gy, nextX + cell, gy + cell) > 0,
            "and the ring moved with it");
    }

    /** The page's own wiring: Arrange, two clicks, and the vault's save changed. */
    private static void thePageMovesThroughItsOwnWiring() throws Exception {
        var repo = new MemoryRepo();
        var tracker = new dev.yoru.application.Tracker(repo, java.time.Clock.systemUTC());
        tracker.addActivity("Study", 0);
        // The page opens the game's current box (0), so the moved Pokémon must
        // sit there, not in the fixture's usual box 4.
        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 4), "TESTER", 0, 12345, 54321);
        raw = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 255, 5, 1), Gen3Fixture.member(raw, 25, 9, 2)));
        raw = Gen3Fixture.withFlag(raw, Gen3Save.FLAG_SYS_POKEMON_GET);
        var open = Gen3Save.read(raw);
        var storage = open.storage();
        var boxed = StudyGift.build(UUID.nameUUIDFromBytes(new byte[]{1}), BOXED, 7, open.trainer(), null, 0).encode();
        System.arraycopy(boxed, 0, storage, Gen3Save.slotOffset(0, SLOT), Gen3Pokemon.BOX_SIZE);
        open.storage(storage);
        tracker.gameSaved(open.bytes());
        var app = new dev.yoru.ui.YoruApp(tracker, repo);
        app.setSize(1280, 900);

        var collectionTab = button(app, "Collection");
        check(collectionTab != null, "the Collection tab exists");
        collectionTab.doClick();
        layout(app);
        var arrange = button(app, "Arrange");
        check(arrange != null, "the Arrange control exists");
        arrange.doClick();
        layout(app);
        var screen = find(app, StorageScreen.class);
        check(screen != null, "the storage screen is on the page");
        check(screen.arranging(), "and the toggle put it into arranging mode");

        int cell = StorageScreen.CELL;
        int srcX = StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4;
        int srcY = StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4;
        int toSlot = SLOT + 1;
        screen.dispatchEvent(new MouseEvent(screen, MouseEvent.MOUSE_PRESSED, 0, 0, srcX, srcY, 1, false));
        screen.dispatchEvent(new MouseEvent(screen, MouseEvent.MOUSE_PRESSED, 0, 0,
            StorageScreen.gridX() + (toSlot % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (toSlot / StorageScreen.COLUMNS) * cell + 4, 1, false));

        var after = dev.yoru.game.Gen3Save.read(tracker.state().game().bytes());
        check(after.boxed(after.storage(), 0, SLOT) == null, "the page's own wiring moved the Pokémon out");
        check(after.boxed(after.storage(), 0, toSlot) != null, "and into the neighbour slot");

        // The rebuild keeps arranging on, so the next move needs no extra click.
        var rebuilt = find(app, StorageScreen.class);
        check(rebuilt != null && rebuilt.arranging(), "the rebuilt page is still arranging");

        var lock = button(app, "Lock & close");
        if (lock != null) lock.doClick();
    }

    /** The first component of a type anywhere under the tree, like Preview's button finder. */
    private static javax.swing.JButton button(java.awt.Container root, String name) {
        for (var child : root.getComponents()) {
            if (child instanceof javax.swing.JButton b && (name.equals(b.getName()) || name.equals(b.getText()))) return b;
            if (child instanceof java.awt.Container nested) { var found = button(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static <T extends java.awt.Component> T find(java.awt.Container root, Class<T> type) {
        for (var child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container nested) { var found = find(nested, type); if (found != null) return found; }
        }
        return null;
    }

    private static void layout(java.awt.Container c) {
        c.doLayout();
        for (var child : c.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }

    private static final class MemoryRepo implements dev.yoru.application.Repository {
        dev.yoru.domain.Model.State state = dev.yoru.domain.Model.State.empty();
        public dev.yoru.domain.Model.State load() { return state; }
        public void save(dev.yoru.domain.Model.State next) { state = next; }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        hitTestingFindsTheSquare();
        clickingShowsWhatIsThere();
        arrowsTurnTheBox();
        drawsTheWallpaperAndSurvivesWithout();
        arrangingPicksUpAndPlaces();
        aMoveEditsTheSave();
        arrangingStateIsVisible();
        keyboardWalksTheBox();
        keyboardArrangesPartyAndEmptyBoxes();
        theCursorIsDrawn();
        thePageMovesThroughItsOwnWiring();
        System.out.println("StorageTest ok (" + checks + " checks)");
    }
}
