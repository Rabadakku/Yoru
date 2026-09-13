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
 */
final class CollectionPage {
    private final Shell shell;
    /** The box on screen: the game's own open box at first, then whichever was turned to. */
    private int box = -1;
    /** Arranging survives the page rebuild every edit causes, like {@code box} does. */
    private boolean arranging;

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
        p.add(read.kind() == GameView.SaveKind.READABLE ? storage(read.save()) : unavailable(read));
        gap(p, SPACE_XL);
        var top = new JPanel(new GridLayout(1, 2, SPACE_LG, 0));
        top.setOpaque(false);
        top.setAlignmentX(0);
        top.add(encounters(state));
        top.add(onTheirWay(shell));
        p.add(top);
        return p;
    }

    private JPanel encounters(State state) {
        var c = card();
        c.add(sectionHeader("STUDY ENCOUNTERS")); gap(c, SPACE_MD);
        long waiting = Encounters.available(state);
        c.add(label(waiting == 0 ? "None waiting" : waiting + " waiting", TYPE_FIGURE, waiting > 0 ? TEXT : MUTED)); gap(c, SPACE_SM);
        c.add(label(Encounters.towardNext(state) / 60 + " / 30m toward the next · "
            + state.campaign().encountersUsed() + " opened", TYPE_CAPTION, MUTED));
        gap(c, SPACE_MD);
        boolean playable = GameFiles.rom() != null;
        var open = button("Open encounter", this::openEncounter);
        open.setName("collection.encounter");
        open.setEnabled(waiting > 0 && playable);
        // A disabled control that does not say why is a dead end; this one says
        // which of the two reasons applies.
        open.setToolTipText(!playable ? "Choose your game file on the Game tab first"
            : waiting == 0 ? "No encounter waiting yet: one arrives every 30 minutes recorded" : "Open the encounter");
        c.add(open);
        // One explainer, not two: the line at :208 said the same thing.
        if (!playable) {
            gap(c, SPACE_SM);
            c.add(bodyLabel("Choose your game file on the Game tab. Encounters come from its wild Pokémon."));
        }
        return c;
    }

    /** Rewards not yet in the game, and what happened the last time any were sent in. */
    static JPanel onTheirWay(Shell shell) {
        var state = shell.tracker().state();
        var pending = state.pendingRewards();
        var c = card();
        c.add(sectionHeader("ON THEIR WAY TO YOUR GAME", GOLD_TEXT)); gap(c, SPACE_MD);
        if (pending.isEmpty()) {
            // Not the words of the card beside it: with an empty vault both
            // read "None waiting", and two headlines that match word for word
            // in adjacent cards look like one of them was copied by mistake.
            // Each card names its own queue instead.
            c.add(label("None on their way", TYPE_FIGURE, MUTED)); gap(c, SPACE_SM);
            c.add(bodyLabel("Pokémon caught in study encounters are sent into your game."));
        } else {
            c.add(label(pending.size() + " Pokémon", TYPE_FIGURE, TEXT)); gap(c, SPACE_SM);
            c.add(label(pending.stream().limit(4).map(r -> SpeciesNames.of(r.nationalDex()) + " Lv " + r.level())
                .collect(Collectors.joining("  ·  ")) + (pending.size() > 4 ? "  ·  +" + (pending.size() - 4) + " more" : ""), TYPE_BODY, TEXT));
            gap(c, SPACE_SM);
            var save = GameView.save(state);
            c.add(label(save == null || !save.hasStarter() ? "They arrive once you have chosen your starter and saved in the game."
                : shell.game().running() ? "They go in when you close the game."
                : "They go in when the game starts: your party if there is room, then your PC.", TYPE_CAPTION, MUTED));
        }
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

    private JPanel storage(Gen3Save save) {
        var c = card();
        var trainer = save.trainer();
        int badges = save.badges();
        c.add(sectionHeader("POKÉMON STORAGE · " + trainer.name() + " · " + save.ownedCount() + " caught · "
            + Theme.plural(badges, "badge")));
        gap(c, SPACE_MD);
        // The edits live here, and only while the game is closed: while it
        // runs, the buttons say so rather than pretending to work.
        boolean gameOpen = shell.game().running();
        var controls = tightRow();
        var arrange = button(gameOpen ? "The game is running" : arranging ? "Done arranging" : "Arrange", () -> { });
        arrange.setName("collection.arrange");
        arrange.setEnabled(!gameOpen);
        controls.add(arrange);
        var rename = button("Rename box…", this::renameBox);
        rename.setName("collection.rename");
        rename.setEnabled(!gameOpen);
        controls.add(rename);
        var paper = button("Wallpaper", this::nextWallpaper);
        paper.setName("collection.wallpaper");
        paper.setEnabled(!gameOpen);
        controls.add(paper);
        // Three controls that are disabled for one reason, said on each of them.
        if (gameOpen) for (var control : new JComponent[] {arrange, rename, paper})
            control.setToolTipText("Close the game first: while it runs it holds its own copy of the save");
        gap(c, SPACE_SM);
        c.add(controls);

        // A minimum, not a fixed width: the storage screen beside it needs a set
        // width, and a fixed one here overflowed the page at the window minimum.
        var details = stack();
        details.setMinimumSize(new Dimension(0, StorageScreen.boxHeight()));
        // A frame of its own. Without one the empty-state hint floated over the
        // card above the box's header row, belonging to nothing; inside a
        // hairline it is anchored, and reads as the panel it is.
        details.setBorder(new CompoundBorder(new LineBorder(LINE),
            new EmptyBorder(SPACE_LG, SPACE_LG, SPACE_LG, SPACE_LG)));
        if (box < 0) box = Gen3Save.currentBox(save.storage());
        var screen = new StorageScreen(save, box, mon -> describe(details, mon), turned -> box = turned,
            gameOpen ? null : (from, to) -> editSave(bytes -> StorageEdit.move(bytes, from, to)));
        var body = new JPanel(new BorderLayout(SPACE_XL, 0));
        body.setOpaque(false);
        body.setAlignmentX(0);
        body.add(screen, BorderLayout.WEST);
        body.add(details, BorderLayout.CENTER);
        describe(details, null);
        screen.showPartyLead();
        c.add(body);

        // The flag outlives the screen: an edit rebuilds the page, and the
        // rebuild must come back in the mode it left.
        screen.setArranging(arranging && !gameOpen);
        final var arrangeToggle = arrange;
        arrangeToggle.addActionListener(e -> {
            arranging = !arranging;
            screen.setArranging(arranging);
            arrangeToggle.setText(arranging ? "Done arranging" : "Arrange");
        });
        if (StorageScreen.wallpaper(0) == null) {
            gap(c, SPACE_SM);
            c.add(bodyLabel("Add your game file in Settings to load Pokémon pictures and box backgrounds."));
            c.add(button("Manage artwork", () -> shell.show("Settings")));
        }
        return c;
    }

    private static void describe(JPanel details, Gen3Pokemon mon) {
        details.removeAll();
        if (mon == null) {
            details.add(bodyLabel("Click a Pokémon to see it."));
        } else {
            details.add(new Portrait(mon)); gap(details, SPACE_MD);
            details.add(label(GameView.name(mon), TYPE_HEADING, mon.shiny() ? GOLD_TEXT : TEXT)); gap(details, SPACE_XS);
            details.add(bodyLabel(GameView.detail(mon)));
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
            found = GameView.nextEncounter(state);
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

    /** Cycles the open box's wallpaper to the next one the game ships. */
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

    /** A Pokémon's picture, drawn crisp at a whole-number scale. */
    private static final class Portrait extends JPanel {
        private final BufferedImage sprite;
        private final String fallback;

        Portrait(Gen3Pokemon mon) {
            setOpaque(false);
            sprite = !GameView.readable(mon) || mon.isEgg() ? null : GameView.sprite(mon.nationalDex(), mon.shiny());
            // Never the dex number: a number where a picture should be reads as
            // the Pokémon's name, and "#252" is not one.
            fallback = mon.isEgg() ? "Egg" : "?";
            var size = new Dimension(128, 128);
            setPreferredSize(size);
            setMaximumSize(size);
            setAlignmentX(0);
        }

        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            try {
                if (sprite != null) {
                    int scale = Math.max(1, Math.min(getWidth() / sprite.getWidth(), getHeight() / sprite.getHeight()));
                    int w = sprite.getWidth() * scale, h = sprite.getHeight() * scale;
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(sprite, (getWidth() - w) / 2, getHeight() - h, w, h, null);
                } else {
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setColor(MUTED);
                    g.setFont(headingFont());
                    var metrics = g.getFontMetrics();
                    g.drawString(fallback, (getWidth() - metrics.stringWidth(fallback)) / 2,
                        getHeight() / 2 + metrics.getAscent() / 2);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
