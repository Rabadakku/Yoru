package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Fixture;
import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.StudyGift;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.swing.*;

/**
 * The Collection page's layout (#8): the party first and on screen at the
 * window's minimum, study rewards in one row, the boxes with real controls,
 * the details beside the box only when there is room, arranging said in words,
 * and the box, the selection and the scroll kept across a rebuild.
 *
 * No artwork is installed in the test home, so every Pokémon here is also a
 * missing-artwork case. Invented saves only.
 */
public final class CollectionLayoutTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final int BOXED_IN = 2, BOXED_AT = 7, BROKEN_AT = 9;

    private interface Action { void run() throws Exception; }

    private static void edt(Action action) throws Exception {
        var failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception exception) throw exception;
    }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    /** A party of three, a Pokémon in box 3, and an unreadable record two squares along. */
    private static byte[] save() {
        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 4), "TESTER", 0, 12345, 54321);
        raw = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 252, 12, 1),
            Gen3Fixture.member(raw, 255, 10, 2), Gen3Fixture.member(raw, 258, 8, 3)));
        raw = Gen3Fixture.withFlag(raw, Gen3Save.FLAG_SYS_POKEMON_GET);
        var save = Gen3Save.read(raw);
        var storage = save.storage();
        var boxed = StudyGift.build(UUID.nameUUIDFromBytes(new byte[]{7}), 280, 7, save.trainer(), null, 0).encode();
        var broken = StudyGift.build(UUID.nameUUIDFromBytes(new byte[]{9}), 283, 9, save.trainer(), null, 0).encode();
        // One flipped byte in the encrypted data, so the record's checksum no longer matches.
        broken[40] ^= (byte) 0xFF;
        System.arraycopy(boxed, 0, storage, Gen3Save.slotOffset(BOXED_IN, BOXED_AT), Gen3Pokemon.BOX_SIZE);
        System.arraycopy(broken, 0, storage, Gen3Save.slotOffset(BOXED_IN, BROKEN_AT), Gen3Pokemon.BOX_SIZE);
        save.storage(storage);
        return save.bytes();
    }

    private static YoruApp app(int width, int height) throws Exception {
        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());
        tracker.addActivity("Study", 0);
        tracker.gameSaved(save());
        var app = new YoruApp(tracker, repo);
        app.setSize(width, height);
        open(app);
        return app;
    }

    private static void open(YoruApp app) {
        button(app, "Collection").doClick();
        layout(app);
        layout(app);
    }

    public static void main(String[] args) throws Exception {
        edt(CollectionLayoutTest::thePartyLeadsAtTheMinimum);
        edt(CollectionLayoutTest::aWideWindowPutsTheDetailsBeside);
        edt(CollectionLayoutTest::missingArtEmptySlotsAndUnreadableRecords);
        edt(CollectionLayoutTest::arrangingIsAModeInWords);
        edt(CollectionLayoutTest::aRebuildKeepsTheBoxTheSelectionAndTheScroll);
        System.out.println("PASS: " + checks + " Collection layout checks (party first, details, missing data, arranging, rebuilds)");
        // The windows built here leave Swing's threads running, which would
        // keep this JVM, and ./test.sh after it, waiting forever.
        System.exit(0);
    }

    private static void thePartyLeadsAtTheMinimum() throws Exception {
        var app = app(900, 640);
        var party = named(app, "collection.party");
        var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, party);
        var onScreen = SwingUtilities.convertRectangle(party.getParent(), party.getBounds(), viewport);
        check(onScreen.y >= 0 && onScreen.y + onScreen.height <= viewport.getHeight(),
            "at 900x640 the whole party is on screen without scrolling: " + onScreen + " in " + viewport.getSize());
        var rewards = named(app, "collection.rewards");
        var boxes = named(app, "collection.boxes");
        check(party.getY() < rewards.getY() && rewards.getY() < boxes.getY(), "the party, then rewards, then the boxes");
        var strip = find(app, PartyStrip.class);
        check(strip.columns() == 3, "the party wraps to three by two at the minimum, got " + strip.columns());
        for (int i = 0; i < Gen3Save.PARTY_LIMIT; i++) {
            var slot = strip.slot(i);
            check(slot.getWidth() >= PartyStrip.MIN_SLOT_WIDTH && slot.getHeight() >= PartyStrip.SLOT_HEIGHT,
                "party slot " + i + " is at least 120x88, got " + slot.getSize());
        }
        var screen = find(app, StorageScreen.class);
        var details = named(app, "collection.details");
        check(details.getY() >= screen.getY() + screen.getHeight(), "below the content width for them, the details sit under the box");
        check(details.getWidth() > PartyStrip.MIN_SLOT_WIDTH * 3, "and take the card's width, not a sliver: " + details.getWidth());
        for (String name : List.of("collection.previousBox", "collection.nextBox", "collection.arrange", "collection.encounter"))
            check(button(app, name).getHeight() >= 32, name + " is a comfortable target, got " + button(app, name).getHeight());
    }

    private static void aWideWindowPutsTheDetailsBeside() throws Exception {
        var app = app(1280, 900);
        var strip = find(app, PartyStrip.class);
        check(strip.columns() == Gen3Save.PARTY_LIMIT, "six across when each slot has room, got " + strip.columns());
        var screen = find(app, StorageScreen.class);
        var details = named(app, "collection.details");
        check(details.getX() >= screen.getX() + screen.getWidth() + Theme.SPACE_XL && details.getY() == screen.getY(),
            "at 1280 the details sit beside the box: " + details.getBounds() + " beside " + screen.getBounds());
        check(details.getWidth() >= CollectionPage.DETAILS_WIDTH, "at least 240 wide, got " + details.getWidth());
        var occupancy = (JLabel) named(app, "collection.occupancy");
        check(occupancy.getText().equals("0 / 30"), "the open box says how full it is, got " + occupancy.getText());
        button(app, "collection.nextBox").doClick();
        button(app, "collection.nextBox").doClick();
        check(screen.box() == BOXED_IN && occupancy.getText().equals("2 / 30"), "and follows the box, got " + occupancy.getText());
        @SuppressWarnings("unchecked")
        var selector = (JComboBox<String>) named(app, "collection.box");
        check(selector.getSelectedIndex() == BOXED_IN, "the box selector follows the buttons");
        selector.setSelectedIndex(0);
        check(screen.box() == 0, "and the buttons follow the selector");
    }

    private static void missingArtEmptySlotsAndUnreadableRecords() throws Exception {
        var app = app(1280, 900);
        var strip = find(app, PartyStrip.class);
        check(text(strip, "party.name.0").equals("Treecko") && text(strip, "party.level.0").equals("Lv 12"),
            "a party slot gives the name and level: " + text(strip, "party.name.0") + " " + text(strip, "party.level.0"));
        check(text(strip, "party.note.0").equals("Selected"), "the lead is on show, and says so in words");
        check(text(strip, "party.note.1").equals("Artwork missing"), "a member without artwork says so");
        check(((JLabel) named(strip, "party.picture.1")).getIcon() != null, "with a plain shape where the picture would be");
        check(text(strip, "party.name.5").equals("Empty") && !strip.slot(5).isEnabled(), "an empty slot reads Empty");
        check(labels(app).stream().noneMatch(t -> t.matches(".*#\\d+.*")), "no dex number stands in for a name anywhere");
        var details = named(app, "collection.details");
        check(labels(details).contains("Artwork missing"), "the details say the artwork is missing too");
        check(labels(details).contains("Party slot 1"), "and where the Pokémon is");

        var screen = find(app, StorageScreen.class);
        screen.showBox(BOXED_IN);
        clickSquare(screen, BROKEN_AT);
        check(labels(details).contains("Cannot read Pokémon"), "an unreadable record is shown as one, got " + labels(details));
        check(screen.getAccessibleContext().getAccessibleChild(1).getAccessibleContext().getAccessibleName()
            .endsWith("Cannot read Pokémon"), "and a screen reader hears the same");
    }

    private static void arrangingIsAModeInWords() throws Exception {
        var app = app(900, 640);
        var arrange = button(app, "collection.arrange");
        var mode = named(app, "collection.arranging");
        check(!mode.isVisible() && arrange.isVisible(), "arranging is off to begin with");
        arrange.doClick();
        check(mode.isVisible() && !arrange.isVisible(), "Arrange turns the mode on, and Done takes its place");
        var instruction = (JLabel) named(app, "collection.instruction");
        check(instruction.getText().equals("Choose a Pokémon, then choose its destination."), "the mode says what to do");
        var cancel = button(app, "collection.cancelMove");
        check(!cancel.isEnabled(), "nothing to cancel before a pickup");
        find(app, PartyStrip.class).slot(0).doClick();
        check(instruction.getText().startsWith("Moving Treecko from party slot 1"), "a pickup says who and from where: " + instruction.getText());
        check(text(find(app, PartyStrip.class), "party.note.0").equals("Moving"), "the held Pokémon's slot says Moving");
        check(cancel.isEnabled(), "and it can be put back");
        cancel.doClick();
        check(find(app, StorageScreen.class).picked() == null && instruction.getText().startsWith("Choose"), "Cancel move puts it back");
        button(app, "collection.doneArranging").doClick();
        check(!mode.isVisible() && arrange.isVisible(), "Done leaves the mode");
    }

    private static void aRebuildKeepsTheBoxTheSelectionAndTheScroll() throws Exception {
        var app = app(900, 640);
        var screen = find(app, StorageScreen.class);
        screen.showBox(BOXED_IN);
        clickSquare(screen, BOXED_AT);
        var chosen = screen.selected();
        check(chosen != null && chosen.nationalDex() == 280, "a boxed Pokémon is on show");
        var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, screen);
        viewport.setViewPosition(new Point(0, 120));
        // What a save notification does: rebuild the page on screen.
        open(app);
        var rebuilt = find(app, StorageScreen.class);
        check(rebuilt != screen && rebuilt.box() == BOXED_IN, "the rebuilt page opens the same box, got " + rebuilt.box());
        check(Arrangement.same(rebuilt.selected(), chosen), "and shows the same Pokémon");
        check(labels(named(app, "collection.details")).contains("Box 3, slot 8"), "in the details too");
        var after = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, rebuilt);
        check(after.getViewPosition().y == 120, "and keeps its scroll position, got " + after.getViewPosition().y);
        button(app, "Today").doClick();
        layout(app);
        button(app, "Collection").doClick();
        layout(app);
        var elsewhere = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, find(app, StorageScreen.class));
        check(elsewhere.getViewPosition().y == 0, "coming from another page starts at the top");
    }

    private static void clickSquare(StorageScreen screen, int slot) {
        int x = StorageScreen.gridX() + (slot % StorageScreen.COLUMNS) * StorageScreen.CELL + 4;
        int y = StorageScreen.gridY() + (slot / StorageScreen.COLUMNS) * StorageScreen.CELL + 4;
        screen.dispatchEvent(new MouseEvent(screen, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false));
    }

    private static String text(Container root, String name) { return ((JLabel) named(root, name)).getText(); }

    private static List<String> labels(Component root) {
        var found = new ArrayList<String>();
        if (!(root instanceof Container container)) return found;
        for (var child : container.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null) found.add(label.getText());
            if (child instanceof Container nested) found.addAll(labels(nested));
        }
        return found;
    }

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static JButton button(Container root, String name) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton b && (name.equals(b.getName()) || name.equals(b.getText()))) return b;
            if (child instanceof Container nested) { var found = button(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (var child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { var found = find(nested, type); if (found != null) return found; }
        }
        return null;
    }

    private static void layout(Container c) {
        c.doLayout();
        for (var child : c.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
