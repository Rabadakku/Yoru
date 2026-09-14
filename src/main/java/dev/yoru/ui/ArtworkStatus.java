package dev.yoru.ui;

import dev.yoru.assets.ArtworkLibrary;
import dev.yoru.assets.ArtworkLibrary.Category;
import dev.yoru.assets.ArtworkLibrary.Readiness;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * Settings' artwork, one row per kind (#6): the game file, Pokémon pictures, box
 * backgrounds and study scenery, each Ready, Partial or Missing, with the one
 * action that repairs it beside anything that is not Ready.
 *
 * It replaced a single line of counts that made a partial import look like a
 * complete one and offered the same generic buttons whatever was missing.
 * Missing artwork never hides data: Pokémon keep their names and levels, and the
 * game plays without any of it.
 */
final class ArtworkStatus {
    private ArtworkStatus() { }

    /**
     * @param gameChosen      a game file is set in Game setup, wherever it lives
     * @param addArtwork      asks for a game, folder or zip
     * @param extractFromGame extracts again from the chosen game
     */
    static JPanel rows(ArtworkLibrary.Report report, boolean gameChosen, Runnable addArtwork, Runnable extractFromGame) {
        var table = ActivityManager.activityTable();
        int row = 0;
        for (var category : report.categories()) {
            boolean game = category.key().equals("game");
            boolean ready = category.readiness() == Readiness.READY || game && gameChosen;
            String said = game && gameChosen && category.readiness() != Readiness.READY ? "Ready · chosen in Game setup" : category.describe();
            var status = label(said, TYPE_BODY, ready ? TEXT : category.readiness() == Readiness.PARTIAL ? GOLD_TEXT : MUTED);
            status.setName("artwork.status." + category.key());
            var name = label(category.name(), TYPE_LABEL, TEXT);
            name.setLabelFor(status);
            JComponent[] controls = ready ? new JComponent[0]
                : new JComponent[]{repair(category, gameChosen, addArtwork, extractFromGame)};
            ActivityManager.activityRow(table, row++, name, status, controls);
        }
        return table;
    }

    /** Sprites and backgrounds come out of the game when there is one; everything else has to be supplied. */
    private static JButton repair(Category category, boolean gameChosen, Runnable addArtwork, Runnable extractFromGame) {
        boolean fromGame = gameChosen && (category.key().equals("pokemon") || category.key().equals("backgrounds"));
        String text = switch (category.key()) {
            case "game" -> "Choose game…";
            case "scenery" -> "Add scene artwork…";
            default -> fromGame ? "Extract from game" : "Add artwork…";
        };
        var repair = button(text, fromGame ? extractFromGame : addArtwork);
        repair.setName("artwork.repair." + category.key());
        repair.getAccessibleContext().setAccessibleName(text.replace("…", "") + " for " + category.name());
        return repair;
    }
}
