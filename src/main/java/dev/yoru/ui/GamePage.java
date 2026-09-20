package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.game.GameDelivery;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.LibretroCore;
import dev.yoru.game.RetroArchSetup;
import dev.yoru.game.SpeciesNames;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import static dev.yoru.ui.Theme.*;

/**
 * The Game tab: the user's own copy of the game, played inside Yoru (#42).
 *
 * Nothing runs until Play is pressed, and the game keeps running whichever tab
 * is on screen until it is closed — here, or by quitting Yoru. While it is
 * stopped the page is about the save the vault holds: whose game it is, how
 * far it has got, and what is on its way in.
 *
 * A missing core or game file never gets in the way of the study workspace:
 * the page says what it needs, and everything else works regardless.
 */
final class GamePage {
    private static final DateTimeFormatter SAVED = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");
    private final Shell shell;
    private GameScreen screen;
    private Timer notices;

    GamePage(Shell shell) { this.shell = shell; }

    /** Stops what the page itself runs. The game keeps going. */
    void leave() {
        if (screen != null) { screen.stop(); screen = null; }
        if (notices != null) { notices.stop(); notices = null; }
    }

    JPanel view() {
        leave();
        var p = stack();
        p.add(YoruApp.pageHeaderFor("Game","THE ORIGINAL GAME · YOUR FILES · NEVER BUNDLED"));
        switch (shell.game().phase()) {
            case STARTING -> waiting(p, "Starting the game…", "Loading your save from the vault.");
            case CLOSING -> waiting(p, "Closing the game…", "Keeping its last save in your vault.");
            case STUCK -> stuck(p);
            case RUNNING -> running(p);
            case IDLE -> idle(p);
        }
        return p;
    }

    // ---- stopped -----------------------------------------------------------------

    private void idle(JPanel p) {
        var core = GameFiles.core();
        var rom = GameFiles.rom();
        var game = shell.game();
        if (game.problem() != null) { p.add(label(game.problem(), TYPE_BODY, DANGER)); gap(p, SPACE_LG); }

        var state = shell.tracker().state();
        var read = GameView.read(state);
        var save = read.save();
        var hero = card();
        // The save's own file actions sit on the card's title line; Play stays
        // in the body, where the save it continues is described.
        var manage = wrappingRow();
        hero.add(cardHead(sectionHeader("SAVED GAME"), manage)); gap(hero, SPACE_MD);
        // 18, not 22: the rule is that a figure earns 22 and a headline does
        // not, and none of these five lines is a number.
        JLabel headline;
        if (read.kind() == GameView.SaveKind.ABSENT) {
            headline = label("No save yet", TYPE_HEADING, TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(bodyLabel("Press Play to begin. Once you choose your starter and save in the game, your vault keeps every save it makes."));
        } else if (read.kind() == GameView.SaveKind.UNREADABLE) {
            headline = label(read.headline(), TYPE_HEADING, GOLD_TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(bodyLabel(read.detail()));
        } else if (!save.hasStarter() && read.partyCount() == 0) {
            // The flag alone is not the test: a save with Pokémon in its party
            // is a game in progress whatever the flag says.
            headline = label("A new adventure", TYPE_HEADING, TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(bodyLabel("Choose your starter and save in the game. It becomes your study companion."));
        } else {
            var trainer = save.trainer();
            int badges = save.badges(), minutes = trainer.playTimeMinutes();
            headline = label(trainer.name(), TYPE_HEADING, TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(label(Theme.plural(badges, "badge") + " · " + save.ownedCount() + " caught · "
                + Analytics.report(minutes * 60L) + " played" + (save.gameClear() ? " · Hall of Fame" : ""), TYPE_BODY, TEXT));
        }
        headline.setName("game.saveHeadline");
        if (state.game() != null) {
            gap(hero, SPACE_XS);
            hero.add(label("Saved " + state.game().updatedAt().atZone(ZoneId.systemDefault()).format(SAVED), TYPE_CAPTION, MUTED));
        }
        gap(hero, SPACE_LG);
        var play = accentButton("▶  Play", () -> game.play(core, rom));
        play.setName("game.play");
        play.setEnabled(LibretroCore.present(core) && rom != null);
        play.setToolTipText(!LibretroCore.present(core) ? "Choose an emulator core below to play"
            : rom == null ? "Choose a game file below to play" : "Continue from your saved game");
        var export = button("Export save…", () -> SaveExport.export(shell));
        export.setName("game.export");
        export.setEnabled(state.game() != null);
        // Disabled with the reason on it rather than a button that does nothing.
        export.setToolTipText(state.game() == null ? "There is no save in this vault yet" : "Write a copy of this save somewhere else");
        manage.add(ghost(button("Import save…", this::chooseSave)));
        manage.add(ghost(export));
        manage.add(ghost(button("Choose game file…", this::chooseRom)));
        hero.add(flushRow(play));
        gap(hero, SPACE_MD);
        hero.add(bodyLabel("As on the cartridge, only saves made in the game are kept, so save before you close it."));
        p.add(hero);
        legacyGifts(p, read);

        if (!LibretroCore.present(core)) { gap(p, SPACE_LG); needCore(p, core); }
        if (rom == null) { gap(p, SPACE_LG); needRom(p); }
        if (state.game() == null) found(p, rom);
        gap(p, SPACE_XL);
        p.add(rewards());
        gap(p, SPACE_XL);
        p.add(whenYouPlay());
    }

    /**
     * Study gifts an older Yoru wrote in the wrong order (#11). Shown only when
     * there is something to say; the repair goes through the same verified,
     * backed-up edit as the Collection page, and only while the game is closed.
     */
    private void legacyGifts(JPanel p, GameView.SaveRead read) {
        if (read.kind() != GameView.SaveKind.READABLE) return;
        var state = shell.tracker().state();
        var assessment = dev.yoru.game.LegacyGifts.assess(read.game().bytes(), state.rewards());
        var fixable = assessment.repairable();
        var left = assessment.leftAlone();
        if (fixable.isEmpty() && left.isEmpty()) return;
        gap(p, SPACE_LG);
        var c = card();
        c.setName("game.legacyGifts");
        c.add(sectionHeader("STUDY GIFTS FROM AN OLDER YORU", GOLD_TEXT)); gap(c, SPACE_MD);
        if (!fixable.isEmpty()) {
            c.add(bodyLabel(Theme.plural(fixable.size(), "study gift") + " went into your game in a layout it reads "
                + "wrongly, so the game shows the wrong Pokémon. Yoru can rewrite "
                + (fixable.size() == 1 ? "it" : "them") + " exactly as it writes gifts now. Your vault is backed up first."));
            gap(c, SPACE_SM);
            for (var f : fixable)
                c.add(label(SpeciesNames.of(f.reward().nationalDex()) + "  ·  Lv " + f.reward().level()
                    + "  ·  " + f.where().getFirst().describe(), TYPE_BODY, TEXT));
            gap(c, SPACE_MD);
            var repair = accentButton("Repair " + Theme.plural(fixable.size(), "study gift"), this::repairLegacyGifts);
            repair.setName("game.repairGifts");
            String blocked = read.editBlock(shell.game().running());
            repair.setEnabled(blocked == null);
            if (blocked != null) repair.setToolTipText(blocked);
            c.add(flushRow(repair));
            if (blocked != null) { gap(c, SPACE_SM); c.add(bodyLabel(blocked)); }
        }
        if (!left.isEmpty()) {
            if (!fixable.isEmpty()) gap(c, SPACE_MD);
            var note = bodyLabel(Theme.plural(left.size(), "other gift") + " may have been written the same way but "
                + (left.size() == 1 ? "has" : "have") + " changed in the game since, so Yoru cannot prove what "
                + (left.size() == 1 ? "it was and has left it" : "they were and has left them") + " exactly as the game has "
                + (left.size() == 1 ? "it." : "them."));
            note.setName("game.legacyLeftAlone");
            c.add(note);
        }
        p.add(c);
    }

    private void repairLegacyGifts() {
        if (!Dialogs.confirm(shell.owner(), "Rewrite these study gifts in the order the game reads?\n\n"
                + "Only gifts Yoru can prove are untouched are changed. Your vault is backed up first.",
                "Repair study gifts", "Repair"))
            return;
        shell.perform(() -> {
            if (shell.game().running())
                throw new IllegalStateException("Close the game first. While it runs it keeps its own copy of the save.");
            var state = shell.tracker().state();
            var before = state.game().bytes();
            shell.tracker().editSave(before, dev.yoru.game.LegacyGifts.repair(before, state.rewards()));
        });
    }

    /**
     * What study has earned for this game, from the game's side of it.
     *
     * The Collection tab shows the same ledger as a one-line summary beside the
     * storage view. This page owns the delivery, so here the Pokémon are a
     * manifest — each on its own line, with the level it arrives at — rather
     * than a second copy of that card on a tab that cannot deliver anything.
     */
    private JPanel rewards() {
        var state = shell.tracker().state();
        var pending = state.pendingRewards();
        var c = card();
        c.add(sectionHeader("STUDY REWARDS · SENT WHEN YOU PLAY", GOLD)); gap(c, SPACE_MD);
        if (pending.isEmpty()) {
            c.add(label("None waiting", TYPE_FIGURE, MUTED)); gap(c, SPACE_SM);
            c.add(bodyLabel("One arrives for every 30 minutes you record, and is caught on the Collection tab."));
        } else {
            c.add(label(pending.size() + " Pokémon", TYPE_FIGURE, TEXT)); gap(c, SPACE_MD);
            // One line each, so a backlog reads as a list rather than a
            // sentence, and is summarised rather than allowed to run away.
            int rows = Math.min(pending.size(), 6);
            for (var reward : pending.subList(0, rows))
                c.add(label(SpeciesNames.of(reward.nationalDex()) + "  ·  Lv " + reward.level(), TYPE_BODY, TEXT));
            if (pending.size() > rows) c.add(bodyLabel("and " + (pending.size() - rows) + " more"));
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
     * What Play does, in the order it happens.
     *
     * The page used to stop at the rewards card, 576 px down a 1000 px frame,
     * and the lower two-fifths was dead space. This is the one thing a reader
     * on this tab is deciding, so it is what fills it — in the same card every
     * other block on every other page is built from.
     */
    private static JPanel whenYouPlay() {
        var c = card();
        c.add(sectionHeader("WHEN YOU PRESS PLAY")); gap(c, SPACE_MD);
        c.add(bodyLabel("The game starts from the last save in your vault, and keeps running on the other tabs until you close it."));
        gap(c, SPACE_SM);
        c.add(bodyLabel("It runs your own game file through your own emulator core. Nothing is copied, and nothing is bundled."));
        gap(c, SPACE_SM);
        c.add(bodyLabel("Catch a Pokémon in the game and it appears on the Collection tab the next time the game saves."));
        return c;
    }

    /** Saves for this game already on this computer, offered while the vault has none of its own. */
    private void found(JPanel p, Path rom) {
        var saves = GameFiles.existingSaves(rom);
        if (saves.isEmpty()) return;
        gap(p, 16);
        var box = card();
        box.add(sectionHeader("A GAME IN PROGRESS", GOLD_TEXT)); gap(box, SPACE_SM);
        box.add(bodyLabel("This computer already has a save for this game. Bring it into your vault to carry on from it."));
        gap(box, SPACE_MD);
        for (var file : saves) {
            // The description takes the room and the button keeps a column of
            // its own, so the action lands in the same place on every row.
            var line = new JPanel(new BorderLayout(SPACE_LG, 0));
            line.setOpaque(false);
            line.add(label(GameFiles.describe(file), TYPE_BODY, TEXT), BorderLayout.CENTER);
            line.add(button("Continue this game", () -> importSave(file)), BorderLayout.EAST);
            box.add(line);
        }
        p.add(box);
    }

    private void chooseSave() {
        var file = Dialogs.chooseFile(shell.owner(), "Choose a game save", "Game Boy Advance save", "sav", "srm");
        if (file != null) importSave(file);
    }

    /**
     * Brings a save into the vault. Refused unless it reads as a save, so the
     * wrong file can never replace a game; the vault is backed up first when
     * it already holds one.
     */
    private void importSave(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
            // Some emulators keep the cartridge's clock after the save itself.
            if (bytes.length > Gen3Save.SIZE) bytes = Arrays.copyOf(bytes, Gen3Save.SIZE);
            Gen3Save.read(bytes);
        } catch (Exception e) {
            Dialogs.error(shell.owner(), "Not a save Yoru can use",
                file.getFileName() + " is not a Pokémon Emerald save Yoru can read.\n\n" + e.getMessage());
            return;
        }
        if (shell.tracker().state().game() != null && !Dialogs.confirm(shell.owner(),
                "Replace the game in your vault with " + file.getFileName() + "?\n\n"
                    + "Your vault is backed up first, so the game it holds now can be recovered.",
                "Replace game save", "Replace"))
            return;
        final byte[] save = bytes;
        shell.perform(() -> { shell.tracker().replaceGameSave(save); shell.game().sync(); });
    }

    private void chooseRom() {
        var picked = Dialogs.chooseFile(shell.owner(), "Choose your game file", "Emerald game or archive", "gba", "zip");
        if (picked != null) ArtworkImport.start(shell.owner(),picked,()-> {
            Path game=picked.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gba")?picked:
                dev.yoru.assets.ArtworkLibrary.root().resolve("games/emerald-national-dex.gba");
            GameFiles.rememberRom(game);shell.show("Game");
        },shell::error);
    }

    /**
     * A missing core, in a card like every other empty state.
     *
     * This was a bare heading and two paragraphs of prose floating on the page
     * background — the only surface in the app not in a card. The path it
     * looked in is still worth saying, and so is where a core comes from, so
     * both are a caption under the action rather than a tooltip only the mouse
     * can find.
     */
    private void needCore(JPanel p, Path looked) {
        var box = card();
        var choose = button("Choose core…", () -> {
            var picked = Dialogs.chooseFile(shell.owner(), "Choose the emulator core", "libretro core", "dylib", "so", "dll");
            if (picked != null) { GameFiles.rememberCore(picked); shell.show("Game"); }
        });
        // The full path, for when the caption is clipped in a narrow window.
        choose.setToolTipText("Yoru looked in " + looked);
        // The control belongs to the empty state, as it does on every other one:
        // added after it, the card carried a band of nothing between the two.
        box.add(emptyState("No emulator core yet.",
            "Yoru plays the game through mGBA's libretro core. RetroArch's core downloader installs it,"
                + " or point Yoru at one you have.", choose));
        gap(box, SPACE_MD);
        box.add(bodyLabel("Choose the mGBA core installed by RetroArch. Your existing save remains available above."));
        p.add(box);
    }

    private void needRom(JPanel p) {
        var box = card();
        box.add(emptyState("No game file yet.",
            "Your own copy of Pokémon Emerald. It stays where it is: Yoru remembers where, on this computer only.",
            button("Choose game file…", this::chooseRom)));
        p.add(box);
    }

    // ---- running -----------------------------------------------------------------

    private void running(JPanel p) {
        var session = shell.game().session();
        if (session == null) { waiting(p, "Starting the game…", ""); return; }
        var core = session.core();
        // The name of the core is what a person reads, and it is read from the
        // core, not assumed: a person may be running something that is not
        // mGBA. The version, the resolution and the frame rate are diagnostics,
        // so they are on the label for a screen reader and on the tooltip for
        // the mouse rather than shouted on the line.
        var name = label(core.coreName(), TYPE_CAPTION, MUTED);
        String diagnostics = core.coreVersion()
            + String.format(" · %dx%d · %.2f fps", core.width(), core.height(), core.fps());
        name.getAccessibleContext().setAccessibleDescription(name.getText() + " " + diagnostics);
        var playing = sectionHeader("● PLAYING");
        playing.setToolTipText(name.getText() + " " + diagnostics);
        p.add(flushRow(playing, name, label("· " + RetroArchSetup.describe(core.coreName()), TYPE_CAPTION, MUTED)));
        gap(p, SPACE_XS);
        // Read live: "your game is not being saved" must not wait for a rebuild.
        var trouble = label(" ", TYPE_CAPTION, DANGER);
        trouble.setVisible(false);
        p.add(trouble);
        var durability = label(" ", TYPE_CAPTION, MUTED);
        var retrySave = button("Retry save", () -> shell.game().retrySave());
        retrySave.setVisible(false);
        p.add(flushRow(durability, retrySave));
        Runnable refreshSaveStatus = () -> {
            var status = shell.game().saveStatus();
            durability.setText(switch (status) {
                case FAILED -> "Could not save to your vault";
                case PENDING -> "Saving to your vault…";
                case SAVED -> shell.tracker().state().game() == null
                    ? "No in-game save yet" : "Saved in your vault";
            });
            durability.setForeground(status == GameController.SaveStatus.FAILED ? DANGER : MUTED);
            retrySave.setVisible(status == GameController.SaveStatus.FAILED);
        };
        refreshSaveStatus.run();
        notices = new Timer(1000, e -> {
            var live = shell.game().session();
            String said = live == null ? null : live.trouble();
            trouble.setText(said == null ? " " : said);
            trouble.setVisible(said != null);
            refreshSaveStatus.run();
        });
        notices.start();
        gap(p, SPACE_SM);
        screen = new GameScreen(session);
        p.add(screen);
        // The keys are worth saying once, over the picture, until the first one
        // is pressed. As a footer paragraph it was read after the fact, if at
        // all; cleared the moment the picture took the keyboard it was never
        // read at all, so it now waits for a key.
        screen.setHint("Arrow keys move · Z is B, X is A · Enter starts\n"
            + "Shift selects · click the picture to give it the keyboard");
        SwingUtilities.invokeLater(screen::requestFocusInWindow);
        gap(p, SPACE_MD);
        var pause = new JButton[1];
        pause[0] = button(session.paused() ? "▶  Resume" : "Pause", () -> {
            if (session.paused()) session.resume(); else session.pause();
            pause[0].setText(session.paused() ? "▶  Resume" : "Pause");
        });
        var close = button("■  Close game", () -> shell.game().close(null));
        close.setName("game.close");
        p.add(flushRow(pause[0], close));
        gap(p, SPACE_MD);
        p.add(bodyLabel("Save in the game before you close it. It keeps running on the other tabs until you do."));
    }

    private static void waiting(JPanel p, String title, String detail) {
        p.add(label(title, TYPE_HEADING, TEXT)); gap(p, SPACE_SM);
        if (!detail.isEmpty()) p.add(bodyLabel(detail));
    }

    /**
     * The game would not stop. What a person needs is the sentence; the raw
     * failure and the retry count are for whoever is working out why, so they
     * live in one tooltip rather than two lines of monospace on the page.
     */
    private void stuck(JPanel p) {
        var game = shell.game();
        p.add(label("The game could not finish closing.", TYPE_HEADING, DANGER)); gap(p, SPACE_SM);
        p.add(bodyLabel("Yoru is keeping this session until the emulator stops and its latest save is safely in your vault. Fix the reported problem, then retry."));
        if(game.problem()!=null) { gap(p, SPACE_SM);p.add(bodyLabel(game.problem())); }
        gap(p, SPACE_LG);
        var again = button("Try closing it again", () -> game.close(null));
        again.setToolTipText("Attempts: " + game.attempts() + " · " + game.failure());
        p.add(again);
    }
}
