package dev.yoru.ui;

import dev.yoru.assets.ArtworkLibrary;
import dev.yoru.domain.Model.ThemeId;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

/**
 * Settings' artwork rows (#6): each kind says how complete it is, and only what
 * is not ready offers a repair, the right one for where that artwork comes from.
 */
public final class ArtworkStatusTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static String status(JPanel rows, String key) { return ((JLabel) named(rows, "artwork.status." + key)).getText(); }
    private static JButton repair(JPanel rows, String key) { return (JButton) named(rows, "artwork.repair." + key); }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.apply(ThemeId.MIDNIGHT);
            var added = new int[1];
            var extracted = new int[1];
            var partial = new ArtworkLibrary.Report(120, 0, 0, 0, 0, 16, 0, List.of());

            var noGame = ArtworkStatus.rows(partial, false, () -> added[0]++, () -> extracted[0]++);
            check(status(noGame, "game").equals("Missing"), "without a game the game file is missing");
            check(status(noGame, "pokemon").equals("Partial · 120 of 386 normal, 0 shiny"), "sprites say how partial they are");
            check(status(noGame, "backgrounds").equals("Ready · 16 of 16"), "all backgrounds are ready");
            check(status(noGame, "scenery").equals("Missing"), "no scenery is missing");
            check(repair(noGame, "backgrounds") == null, "a ready row offers no repair");
            check(repair(noGame, "game").getText().equals("Choose game…"), "a missing game asks for one");
            check(repair(noGame, "pokemon").getText().equals("Add artwork…"), "without a game, sprites have to be supplied");
            check(repair(noGame, "scenery").getText().equals("Add scene artwork…"), "scenery always has to be supplied");
            check(repair(noGame, "pokemon").getAccessibleContext().getAccessibleName().equals("Add artwork for Pokémon pictures"),
                "each repair says what it repairs");
            repair(noGame, "pokemon").doClick();
            check(added[0] == 1 && extracted[0] == 0, "and asks for artwork");

            var withGame = ArtworkStatus.rows(partial, true, () -> added[0]++, () -> extracted[0]++);
            check(status(withGame, "game").equals("Ready · chosen in Game setup"), "a game chosen outside the library counts as ready");
            check(repair(withGame, "game") == null, "and needs no repair");
            check(repair(withGame, "pokemon").getText().equals("Extract from game"), "with a game, sprites come out of it");
            repair(withGame, "pokemon").doClick();
            check(extracted[0] == 1 && added[0] == 1, "and the repair extracts rather than asking");
            check(repair(withGame, "scenery").getText().equals("Add scene artwork…"), "the game has no scenery to extract");

            var complete = ArtworkStatus.rows(new ArtworkLibrary.Report(386, 386, 10, 0, 1, 16, 0, List.of()), true, () -> { }, () -> { });
            for (String key : List.of("game", "pokemon", "backgrounds", "scenery"))
                check(repair(complete, key) == null && status(complete, key).startsWith("Ready"), "a complete library needs no repair: " + key);

            // A failed import (#6): what is still not ready says so, and why, while ready kinds are untouched.
            var failure = new ArtworkLibrary.Failure(java.time.Instant.now().minusSeconds(180),
                "No usable artwork or supported game was found. Your existing library is unchanged.");
            var failedRows = ArtworkStatus.rows(partial, failure, false, () -> added[0]++, () -> extracted[0]++);
            check(status(failedRows, "game").equals("Failed"), "after a failed import, a missing kind reads Failed");
            check(status(failedRows, "pokemon").equals("Failed · kept 120 of 386 normal, 0 shiny"),
                "a partial kind says what it kept: " + status(failedRows, "pokemon"));
            check(status(failedRows, "backgrounds").equals("Ready · 16 of 16"), "a ready kind stays ready, since nothing was changed");
            check(repair(failedRows, "pokemon") != null && repair(failedRows, "backgrounds") == null, "repairs are offered where they were");
            var note = (JLabel) named(failedRows, "artwork.failure");
            check(note != null && note.getText().contains(failure.reason()), "the reason is written under the rows");
            check(named(noGame, "artwork.failure") == null, "and there is no such note without a failure");
            check(status(ArtworkStatus.rows(partial, failure, true, () -> { }, () -> { }), "game").equals("Ready · chosen in Game setup"),
                "a chosen game stays ready whatever an import did");

            // Route visitors are extras (#6): seven scene sheets are a complete scene, not "7 of 10".
            var scene = ArtworkStatus.rows(new ArtworkLibrary.Report(386, 386, 7, 0, 1, 16, 0, List.of(), 0), true, () -> { }, () -> { });
            check(status(scene, "scenery").equals("Ready · 7 of 7 sheets") && repair(scene, "scenery") == null,
                "the scene's own seven sheets are ready: " + status(scene, "scenery"));

            String outcome = ArtworkImport.outcome(partial, new ArtworkLibrary.Report(121, 0, 0, 2, 0, 16, 1, List.of("One wallpaper could not be decoded.")));
            check(outcome.startsWith("Added 1 Pokémon picture."), "the import dialog leads with what was added: " + outcome);
            check(outcome.contains("One wallpaper could not be decoded.") && outcome.contains("2 files were not artwork and skipped."),
                "then warnings and skipped files");
            check(outcome.endsWith("Your library now has 121/386 Pokémon pictures · 0 shiny · 16/16 box backgrounds · 0 scenery sheets."),
                "and ends with the library it left");
        });
        System.out.println("PASS: " + checks + " artwork status checks (readiness per kind, the right repair, import outcome)");
    }
}
