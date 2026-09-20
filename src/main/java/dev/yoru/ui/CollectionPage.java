package dev.yoru.ui;

import dev.yoru.application.Encounters;
import dev.yoru.domain.Model.State;
import dev.yoru.game.GameDelivery;
import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.SpeciesNames;
import dev.yoru.game.StorageEdit;
import dev.yoru.game.StudyEncounter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.*;
import static dev.yoru.ui.Theme.*;

/**
 * The Collection tab: the Pokémon your game holds, and the ones on their way (#44).
 *
 * Everything shown is read from the vault's save — the party and all fourteen
 * boxes, in the slots the game put them in. What you catch in the game appears
 * here the next time it saves; what study earns goes into the game the next
 * time it starts or closes. Yoru keeps no collection of its own to disagree
 * with the game's.
 *
 * Laid out party first (#8). Two reward cards used to fill the first screen,
 * so at the window's minimum the party began below the fold. Now the party
 * leads, study rewards take one row, and the PC boxes follow with the details
 * beside the grid when the page is wide enough and under it when not.
 */
final class CollectionPage {
    /** Content at least this wide puts the details beside the box rather than under it. */
    static final int DETAILS_BESIDE = 1040;
    static final int DETAILS_WIDTH = 240;
    /** Beside the box the details stop growing here, so a wide window reads as box and details, not a blank field. */
    static final int DETAILS_MAX = 360;

    private enum Region { NONE, PARTY, BOXES }

    private final Shell shell;
    /** The box on screen: the game's own open box at first, then whichever was turned to. */
    private int box = -1;
    /** Arranging survives the page rebuild every edit causes, like {@code box} does. */
    private boolean arranging;
    /** Where the Pokémon on show sits, kept across rebuilds so a save notification does not reset it. */
    private StorageEdit.Place shown;
    /** Where the keyboard was when a move rebuilt the page, and the place it went to. */
    private Region keyboard = Region.NONE;
    private StorageEdit.Place keyboardAt;

    CollectionPage(Shell shell) { this.shell = shell; }

    JPanel view() {
        var state = shell.tracker().state();
        var read = GameView.read(state);
        var p = stack();
        p.add(YoruApp.pageHeaderFor("Collection",
            "YOUR GAME'S POKÉMON · ONE ENCOUNTER FOR EVERY 30 MINUTES RECORDED"));
        var status = saveStatus(read);
        if (status != null) { p.add(status); gap(p, SPACE_LG); }
        if (shell.game().running()) {
            p.add(label("The game is running. This is its last save; what you catch now appears when it saves again.", TYPE_BODY, GOLD_TEXT));
            gap(p, SPACE_LG);
        }
        if (read.kind() != GameView.SaveKind.READABLE) {
            p.add(unavailable(read));
            gap(p, SPACE_XL);
            p.add(rewards(state));
            return p;
        }
        storage(p, read.save());
        return p;
    }

    /**
     * One line saying whether the Pokémon below are what the vault durably
     * holds, or null when there are none to vouch for: with no save, or one
     * that cannot be read, the card below already says so, and a line above it
     * only repeated the card. Saving and failed saves always show.
     */
    private JComponent saveStatus(GameView.SaveRead read) {
        var status = shell.game().saveStatus();
        JLabel line;
        if (status == GameController.SaveStatus.FAILED) {
            line = label("Could not save to your vault", TYPE_CAPTION, DANGER);
        } else if (status == GameController.SaveStatus.PENDING) {
            line = label("Saving to your vault…", TYPE_CAPTION, MUTED);
        } else if (read.kind() == GameView.SaveKind.READABLE) {
            line = label("Saved in your vault · " + Ago.describe(read.savedAt(), java.time.Instant.now(),
                java.time.ZoneId.systemDefault()), TYPE_CAPTION, MUTED);
        } else {
            return null;
        }
        line.setName("collection.saveStatus");
        if (status != GameController.SaveStatus.FAILED) return flushRow(line);
        var retry = button("Retry save", () -> { shell.game().retrySave(); shell.show("Collection"); });
        retry.setName("collection.retrySave");
        return flushRow(line, retry);
    }

    /** No save, or one Yoru cannot read: say which, and keep the way out in reach. */
    private JPanel unavailable(GameView.SaveRead read) {
        var c = card();
        c.setName("collection.unavailable");
        c.add(label(read.headline(), TYPE_HEADING, TEXT));
        gap(c, SPACE_SM);
        c.add(bodyLabel(read.detail()));
        gap(c, SPACE_LG);
        if (read.kind() == GameView.SaveKind.UNREADABLE) {
            var export = accentButton("Export a copy", () -> SaveExport.export(shell));
            export.setName("collection.export");
            var setup = button("Open Game setup", () -> shell.show("Game"));
            setup.setName("collection.setup");
            c.add(flushRow(export, setup));
        } else {
            var game = accentButton("Open the game", () -> shell.show("Game"));
            game.setName("collection.openGame");
            c.add(flushRow(game));
        }
        return c;
    }

    /** The party, the rewards row and the PC boxes, sharing one selection and one arrangement. */
    private void storage(JPanel page, Gen3Save save) {
        boolean gameOpen = shell.game().running();
        // The edits live here, and only while the game is closed: while it runs,
        // the controls say so rather than pretending to work.
        var arrangement = new Arrangement(gameOpen ? null : (from, to) -> {
            keyboard = focusRegion(page);
            keyboardAt = to;
            editSave(bytes -> StorageEdit.move(bytes, from, to));
        });
        arrangement.setOn(arranging);
        if (box < 0) box = Gen3Save.currentBox(save.storage());

        var details = stack();
        details.setName("collection.details");
        // A frame of its own, so the details read as the panel they are,
        // beside the box or under it.
        details.setBorder(new CompoundBorder(new LineBorder(LINE),
            new EmptyBorder(SPACE_LG, SPACE_LG, SPACE_LG, SPACE_LG)));
        var holder = new StorageScreen[1];
        var strip = new PartyStrip(save.party(), arrangement, mon -> {
            shown = new StorageEdit.Place(true, -1, save.party().indexOf(mon));
            holder[0].select(mon);
            describe(details, mon, shown);
        });

        var names = new String[Gen3Save.BOXES];
        for (int i = 0; i < names.length; i++) {
            String name = save.boxName(save.storage(), i).strip();
            names[i] = name.isEmpty() ? "BOX " + (i + 1) : name;
        }
        var selector = plainCombo(new JComboBox<>(names));
        selector.setName("collection.box");
        selector.getAccessibleContext().setAccessibleName("Box");
        var occupancy = label("", TYPE_LABEL, MUTED);
        occupancy.setName("collection.occupancy");
        var screen = new StorageScreen(save, box, mon -> {
            shown = mon == null ? null : new StorageEdit.Place(false, holder[0].box(), holder[0].cursor());
            strip.select(mon);
            describe(details, mon, shown);
        }, turned -> {
            box = turned;
            if (selector.getSelectedIndex() != turned) selector.setSelectedIndex(turned);
            occupancy.setText(holder[0].occupancy() + " / " + Gen3Save.PER_BOX);
        }, arrangement);
        holder[0] = screen;
        selector.setSelectedIndex(screen.box());
        selector.addActionListener(e -> screen.showBox(selector.getSelectedIndex()));
        occupancy.setText(screen.occupancy() + " / " + Gen3Save.PER_BOX);
        strip.onSwitchArea(screen::requestFocusInWindow);
        screen.onSwitchArea(() -> {
            for (int i = 0; i < Gen3Save.PARTY_LIMIT; i++)
                if (strip.slot(i).isEnabled()) { strip.slot(i).requestFocusInWindow(); return; }
        });

        var chosen = restoreSelection(save, screen);
        strip.select(chosen);
        screen.select(chosen);
        describe(details, chosen, shown);

        page.add(party(save, strip));
        gap(page, SPACE_XL);
        page.add(rewards(shell.tracker().state()));
        gap(page, SPACE_XL);
        page.add(boxes(save, screen, selector, occupancy, details, arrangement, gameOpen));
        returnKeyboard(strip, screen);
    }

    /** The Pokémon last on show if its place still holds one, else the party's lead. */
    private Gen3Pokemon restoreSelection(Gen3Save save, StorageScreen screen) {
        if (shown != null) {
            Gen3Pokemon there = shown.party()
                ? (shown.slot() >= 0 && shown.slot() < save.party().size() ? save.party().get(shown.slot()) : null)
                : save.boxed(save.storage(), shown.box(), shown.slot());
            if (there != null) return there;
        }
        shown = save.party().isEmpty() ? null : new StorageEdit.Place(true, -1, 0);
        return save.party().isEmpty() ? null : save.party().getFirst();
    }

    /** After a move rebuilt the page, the keyboard goes back to where it was, on the place it moved to. */
    private void returnKeyboard(PartyStrip strip, StorageScreen screen) {
        var region = keyboard;
        var at = keyboardAt;
        keyboard = Region.NONE;
        keyboardAt = null;
        if (region == Region.NONE || at == null) return;
        if (region == Region.BOXES && !at.party() && at.box() == screen.box()) screen.placeCursor(at.slot());
        SwingUtilities.invokeLater(() -> {
            if (region == Region.PARTY && at.party()) strip.slot(at.slot()).requestFocusInWindow();
            else screen.requestFocusInWindow();
        });
    }

    private static Region focusRegion(JPanel page) {
        var owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner instanceof StorageScreen) return Region.BOXES;
        if (owner != null && SwingUtilities.getAncestorOfClass(PartyStrip.class, owner) != null) return Region.PARTY;
        return Region.NONE;
    }

    private JPanel party(Gen3Save save, PartyStrip strip) {
        var c = card();
        c.setName("collection.party");
        c.add(sectionHeader("YOUR PARTY · " + save.trainer().name() + " · " + save.ownedCount() + " caught · "
            + Theme.plural(save.badges(), "badge")));
        gap(c, SPACE_MD);
        c.add(strip);
        return c;
    }

    /** Study rewards in one row: encounters waiting, Pokémon on their way, and the one action. */
    private JPanel rewards(State state) {
        var c = card();
        c.setName("collection.rewards");
        var summaries = new JPanel(new GridLayout(1, 2, SPACE_XL, 0));
        summaries.setOpaque(false);
        summaries.add(encounters(state));
        summaries.add(onTheirWay(state));
        var row = new JPanel(new BorderLayout(SPACE_LG, 0));
        row.setOpaque(false);
        row.setAlignmentX(0);
        row.add(summaries, BorderLayout.CENTER);
        row.add(flushRow(encounterButton(state)), BorderLayout.EAST);
        c.add(row);
        for (var outcome : shell.game().outcomes()) {
            boolean delivered = outcome.kind() == GameDelivery.Kind.DELIVERED;
            boolean trouble = switch (outcome.kind()) {
                case UNREADABLE, NOT_EDITABLE, FULL, FAILED -> true;
                default -> false;
            };
            if (!delivered && !trouble) continue;
            gap(c, SPACE_XS);
            c.add(label(outcome.message(), TYPE_CAPTION, delivered ? CYAN : DANGER));
        }
        return c;
    }

    private static JPanel encounters(State state) {
        var s = stack();
        long waiting = Encounters.available(state);
        var headline = wrapping(waiting == 0 ? "No study encounters waiting"
            : Theme.plural((int) waiting, "study encounter") + " waiting", TYPE_BODY, waiting > 0 ? TEXT : MUTED);
        headline.setName("collection.encounters");
        s.add(headline);
        s.add(caption(Encounters.towardNext(state) / 60 + " / 30 min toward the next · "
            + state.campaign().encountersUsed() + " opened"));
        if (GameFiles.rom() == null) s.add(caption("Choose your game file on the Game tab first."));
        return s;
    }

    private JPanel onTheirWay(State state) {
        var s = stack();
        var pending = state.pendingRewards();
        var headline = wrapping(pending.isEmpty() ? "None on their way to your game"
            : pending.size() + " Pokémon on their way to your game", TYPE_BODY, pending.isEmpty() ? MUTED : GOLD_TEXT);
        headline.setName("collection.onTheirWay");
        s.add(headline);
        if (pending.isEmpty()) {
            s.add(caption("Pokémon caught in study encounters are sent into your game."));
            return s;
        }
        s.add(caption(pending.stream().limit(3).map(r -> SpeciesNames.of(r.nationalDex()) + " Lv " + r.level())
            .collect(Collectors.joining(" · ")) + (pending.size() > 3 ? " · +" + (pending.size() - 3) + " more" : "")));
        var save = GameView.save(state);
        s.add(caption(save == null || !save.hasStarter() ? "They arrive once you have chosen your starter and saved."
            : shell.game().running() ? "They go in when you close the game."
            : "They go in when the game starts: party first, then PC."));
        return s;
    }

    private JButton encounterButton(State state) {
        long waiting = Encounters.available(state);
        boolean playable = GameFiles.rom() != null;
        var open = waiting > 0 && playable ? accentButton("Open encounter", this::openEncounter)
            : button("Open encounter", this::openEncounter);
        open.setName("collection.encounter");
        open.setEnabled(waiting > 0 && playable);
        // A disabled control that does not say why is a dead end; this one says
        // which of the two reasons applies.
        open.setToolTipText(!playable ? "Choose your game file on the Game tab first"
            : waiting == 0 ? "No encounter waiting yet: one arrives every 30 minutes recorded" : "Open the encounter");
        return open;
    }

    private static JLabel caption(String text) {
        var line = bodyLabel(text);
        line.setFont(sans(TYPE_CAPTION));
        return line;
    }

    private JPanel boxes(Gen3Save save, StorageScreen screen, JComboBox<String> selector, JLabel occupancy,
                         JPanel details, Arrangement arrangement, boolean gameOpen) {
        var c = card();
        c.setName("collection.boxes");
        c.add(sectionHeader("PC BOXES"));
        gap(c, SPACE_SM);
        var previous = button("‹ Previous", () -> screen.turn(-1));
        previous.setName("collection.previousBox");
        var next = button("Next ›", () -> screen.turn(1));
        next.setName("collection.nextBox");
        c.add(flushRow(previous, selector, next, occupancy));

        var arrange = button(gameOpen ? "The game is running" : "Arrange", () -> setArranging(arrangement, true));
        arrange.setName("collection.arrange");
        arrange.setEnabled(!gameOpen);
        var rename = button("Rename box…", this::renameBox);
        rename.setName("collection.rename");
        rename.setEnabled(!gameOpen);
        var paper = button("Wallpaper", this::nextWallpaper);
        paper.setName("collection.wallpaper");
        paper.setEnabled(!gameOpen);
        // Three controls that are disabled for one reason, said on each of them.
        if (gameOpen) for (var control : new JComponent[] {arrange, rename, paper})
            control.setToolTipText("Close the game first: while it runs it holds its own copy of the save");
        // Arrange last: it hides while arranging, and a hidden control first in
        // a flush row would leave its gap behind and push the row off the margin.
        c.add(flushRow(rename, paper, arrange));

        // Arranging is a mode with its instruction in words, a way to put a
        // Pokémon back, and a way out, rather than a hint painted on the box.
        var instruction = label("", TYPE_BODY, ACCENT_TEXT);
        instruction.setName("collection.instruction");
        var cancel = button("Cancel move", arrangement::cancel);
        cancel.setName("collection.cancelMove");
        var done = accentButton("Done", () -> setArranging(arrangement, false));
        done.setName("collection.doneArranging");
        var mode = flushRow(instruction, cancel, done);
        mode.setName("collection.arranging");
        c.add(mode);
        Runnable follow = () -> {
            instruction.setText(arrangement.instruction());
            cancel.setEnabled(arrangement.picked() != null);
            mode.setVisible(arrangement.on());
            arrange.setVisible(!arrangement.on());
            c.revalidate();
            c.repaint();
        };
        arrangement.listen(follow);
        follow.run();
        gap(c, SPACE_MD);

        var body = new JPanel(new Beside(screen, details));
        body.setName("collection.boxBody");
        body.setOpaque(false);
        body.setAlignmentX(0);
        body.add(screen);
        body.add(details);
        c.add(body);
        if (StorageScreen.wallpaper(0) == null) {
            gap(c, SPACE_SM);
            c.add(bodyLabel("Add your game file in Settings to load Pokémon pictures and box backgrounds."));
            c.add(flushRow(button("Manage artwork", () -> shell.show("Settings"))));
        }
        return c;
    }

    private void setArranging(Arrangement arrangement, boolean on) {
        arranging = on;
        arrangement.setOn(on);
    }

    private static void describe(JPanel details, Gen3Pokemon mon, StorageEdit.Place place) {
        details.removeAll();
        if (mon == null) {
            details.add(bodyLabel("Choose a Pokémon to see it."));
        } else {
            var portrait = new Portrait(mon);
            details.add(portrait); gap(details, SPACE_MD);
            details.add(label(GameView.name(mon), TYPE_HEADING, mon.shiny() && GameView.readable(mon) ? GOLD_TEXT : TEXT));
            gap(details, SPACE_XS);
            if (place != null) {
                String where = Arrangement.where(place);
                details.add(label(Character.toUpperCase(where.charAt(0)) + where.substring(1), TYPE_CAPTION, MUTED));
            }
            details.add(bodyLabel(GameView.detail(mon)));
            if (portrait.missingArtwork()) details.add(label("Artwork missing", TYPE_CAPTION, MUTED));
            if (GameView.readable(mon) && !mon.isEgg()) {
                gap(details, SPACE_MD);
                details.add(label(GameView.nature(mon) + " nature", TYPE_BODY, TEXT));
                if (mon.otName != null && !mon.otName.isBlank())
                    details.add(label("Trainer · " + mon.otName.strip(), TYPE_BODY, TEXT));
            }
        }
        details.revalidate();
        details.repaint();
    }

    /**
     * The next study encounter. Which Pokémon it is was fixed when the vault
     * was made, so closing this and coming back shows the same one.
     */
    private void openEncounter() {
        var state = shell.tracker().state();
        StudyEncounter.Found found;
        try {
            found = GameView.nextEncounter(state, shell.game().encounters());
        } catch (Exception e) {
            shell.error(e);
            return;
        }
        if (found == null) {
            Dialogs.info(shell.owner(), "Choose your game file on the Game tab first. Encounters come from its wild Pokémon.");
            return;
        }
        boolean shiny = GameView.shiny(state, found.reward(), found.national(), found.level());
        String title = (shiny ? "A shiny " : "A wild ") + SpeciesNames.of(found.national()) + " appeared!";
        boolean still = shell.reducedMotion();
        var name = label(still ? title : "Something stirs in the grass…", TYPE_HEADING, CYAN);
        var panel = stack();
        panel.add(new EncounterScene(found.national(), shiny, still, () -> name.setText(title)));
        gap(panel, SPACE_MD);
        panel.add(name); gap(panel, SPACE_SM);
        panel.add(label("Lv " + found.level() + " · " + found.area(), TYPE_BODY, TEXT)); gap(panel, SPACE_SM);
        panel.add(label("Catch it and it is sent into your game. Leave it and it waits here.", TYPE_CAPTION, MUTED));
        if (Dialogs.choose(shell.owner(), panel, "Encounter", "Catch", "Later") != 0) return;
        shell.perform(() -> {
            shell.tracker().catchEncounter(found.reward(), found.national(), found.level());
            shell.game().sync();
        });
    }

    /** Renames the open box, written through the same verified transaction as delivery. */
    private void renameBox() {
        var state = shell.tracker().state();
        var save = GameView.save(state);
        if (save == null) return;
        String current = save.boxName(save.storage(), box).strip();
        String name = Dialogs.input(shell.owner(), "Rename this box: 1 to 8 characters, the game's own limit.", "Rename box", current);
        if (name == null || name.strip().equals(current) || name.isBlank()) return;
        editSave(bytes -> StorageEdit.renameBox(bytes, box, name));
    }

    /** Cycles the open box's wallpaper to the next one the game ships, back to the first after the last. */
    private void nextWallpaper() {
        var state = shell.tracker().state();
        var save = GameView.save(state);
        if (save == null) return;
        int current = save.boxWallpaper(save.storage(), box);
        editSave(bytes -> StorageEdit.wallpaper(bytes, box, current + 1));
    }

    /**
     * One edit to the vault's save, made only while the game is closed.
     *
     * Checked when the edit is made, not when the page was drawn: the game may
     * have started since, and a running game holds its own copy of the save
     * that its next in-game save — or its last one on Close — writes over the
     * change, silently undoing it.
     */
    private void editSave(java.util.function.UnaryOperator<byte[]> edit) {
        shell.perform(() -> {
            if (shell.game().running())
                throw new IllegalStateException("Close the game first. While it runs it keeps its own copy of the save "
                    + "and would write over this change.");
            var before = shell.tracker().state().game().bytes();
            shell.tracker().editSave(before, edit.apply(before));
        });
    }

    /**
     * The box and its details side by side when the content is at least
     * {@link #DETAILS_BESIDE} wide, the details under the box when not (#8), so
     * a narrow window never squeezes the details into a sliver.
     */
    private static final class Beside implements LayoutManager {
        private final Component box, details;
        private Boolean laidOutBeside;

        Beside(Component box, Component details) { this.box = box; this.details = details; }

        /** Whether a box body this wide sits in content wide enough for the details beside it. */
        static boolean beside(int width) { return width + 2 * SPACE_XL >= DETAILS_BESIDE; }

        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }

        @Override public Dimension preferredLayoutSize(Container parent) {
            var boxSize = box.getPreferredSize();
            var detailSize = details.getPreferredSize();
            if (beside(PartyStrip.availableWidth(parent)))
                return new Dimension(boxSize.width + SPACE_XL + DETAILS_WIDTH, Math.max(boxSize.height, detailSize.height));
            return new Dimension(boxSize.width, boxSize.height + SPACE_LG + detailSize.height);
        }

        @Override public Dimension minimumLayoutSize(Container parent) { return box.getMinimumSize(); }

        @Override public void layoutContainer(Container parent) {
            int width = parent.getWidth();
            boolean side = beside(width);
            var boxSize = box.getPreferredSize();
            box.setBounds(0, 0, boxSize.width, boxSize.height);
            if (side) details.setBounds(boxSize.width + SPACE_XL, 0,
                Math.max(DETAILS_WIDTH, Math.min(DETAILS_MAX, width - boxSize.width - SPACE_XL)),
                Math.max(boxSize.height, details.getPreferredSize().height));
            else details.setBounds(0, boxSize.height + SPACE_LG, width, details.getPreferredSize().height);
            // Moving the details changes the height asked for, and the card
            // around this has already been laid out with the old one.
            if (laidOutBeside != null && laidOutBeside != side) SwingUtilities.invokeLater(parent::revalidate);
            laidOutBeside = side;
        }
    }

    /** A Pokémon's picture, drawn crisp at a whole-number scale, or a plain shape where there is none. */
    private static final class Portrait extends JPanel {
        private final BufferedImage sprite;
        private final Gen3Pokemon mon;

        Portrait(Gen3Pokemon mon) {
            this.mon = mon;
            setOpaque(false);
            sprite = !GameView.readable(mon) || mon.isEgg() ? null : GameView.sprite(mon.nationalDex(), mon.shiny());
            var size = new Dimension(128, 128);
            setPreferredSize(size);
            setMaximumSize(size);
            setAlignmentX(0);
        }

        boolean missingArtwork() { return sprite == null && GameView.readable(mon) && !mon.isEgg(); }

        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int cx = getWidth() / 2;
                if (sprite != null) {
                    int scale = Math.max(1, Math.min(getWidth() / sprite.getWidth(), getHeight() / sprite.getHeight()));
                    int w = sprite.getWidth() * scale, h = sprite.getHeight() * scale;
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(sprite, (getWidth() - w) / 2, getHeight() - h, w, h, null);
                } else if (mon.isEgg()) {
                    g.setColor(TEXT);
                    g.fillOval(cx - 30, getHeight() / 2 - 40, 60, 80);
                } else if (!GameView.readable(mon)) {
                    g.setColor(DANGER);
                    g.setFont(headingFont());
                    var metrics = g.getFontMetrics();
                    g.drawString("!", cx - metrics.stringWidth("!") / 2, getHeight() / 2 + metrics.getAscent() / 2);
                } else {
                    // Never the dex number: a number where a picture should be
                    // reads as the Pokémon's name, and "#252" is not one.
                    g.setColor(MUTED);
                    g.fillOval(cx - 22, 20, 44, 44);
                    g.fillRoundRect(cx - 40, 72, 80, 44, 32, 32);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
