package dev.yoru.assets;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Imports user-supplied artwork into a folder the app owns.
 *
 * Yoru ships no game assets, so every install starts empty and the user brings
 * their own. That has to be one drop of a folder or a zip, not a naming
 * exercise, so this accepts whatever shape the files arrive in — nested
 * directories, zero-padded numbers, mixed case — and normalises them.
 *
 * Anything it does not recognise is skipped and counted rather than guessed at.
 *
 * This is the library's front: planning an import, what the library holds and
 * what counts as complete, and the last import that failed. Reading an import
 * into a stage belongs to ArtworkStaging, and publishing it and keeping the
 * library's generations to ArtworkGenerations (#12).
 */
public final class ArtworkLibrary {
    private ArtworkLibrary() { }

    public static final int SPECIES = 386;
    /** SHA-256 of the Emerald Hoenn + National Dex ROM this build is verified against. */
    public static final String EXPECTED_GAME_SHA256 =
        "8a80807472ab4bc95f1e9e577873866849971cbe3d64b1d86475205b5be29836";

    /** Overworld and scenery sheets, kept under their own names. */
    static final Set<String> SHEETS = Set.of(
        "brendan", "brendan-running", "may", "may-running", "tree", "rock", "grass",
        "route-trainer", "route-cyclist", "team-rocket");
    /**
     * The sheets only route visitors use. They are extras: the scene walks and
     * runs without them, borrowing the other trainer as a passer-by, and no
     * extractor or scene pack is expected to supply them. Counting them as
     * required made a complete scene read "Partial · 7 of 10 sheets".
     */
    static final Set<String> CAMEOS = Set.of("route-trainer", "route-cyclist", "team-rocket");
    /** The sheets a complete walking scene needs. */
    static final int SCENE_SHEETS = SHEETS.size() - CAMEOS.size();

    /** One atomic pointer selects a complete generation; old loose libraries still work. */
    public static Path root() { return ArtworkGenerations.root(); }

    /** @param cameos how many of {@code sheets} are optional route visitors rather than the scene's own */
    public record Report(int species, int shiny, int sheets, int skipped, int games,
                         int wallpapers, int imported, List<String> warnings, int cameos) {
        public Report { warnings = List.copyOf(warnings); }
        public Report(int species, int shiny, int sheets, int skipped, int games,
                      int wallpapers, int imported, List<String> warnings) {
            this(species, shiny, sheets, skipped, games, wallpapers, imported, warnings, 0);
        }
        public Report(int species, int shiny, int sheets, int skipped, int games) {
            this(species, shiny, sheets, skipped, games, 0, 0, List.of());
        }
        /** The walking scene's own sheets, without the route visitors. */
        public int scene() { return Math.max(0, Math.min(SCENE_SHEETS, sheets - cameos)); }
        public boolean empty() { return species == 0 && shiny == 0 && sheets == 0 && wallpapers == 0; }
        /** The library's counts, and nothing about any one import. */
        public String totals() {
            return species + "/" + SPECIES + " Pokémon pictures · " + shiny + " shiny · "
                + wallpapers + "/16 box backgrounds · " + sheets + " scenery sheets";
        }
        public String summary() {
            String summary = totals();
            if (imported > 0) summary = imported + " files imported. " + summary;
            if (empty()) summary = "No artwork installed. Pokémon names, levels and dex numbers remain available.";
            if (skipped > 0) summary += " · " + skipped + " files skipped";
            if (!warnings.isEmpty()) summary += "\n" + String.join("\n", warnings);
            return summary;
        }

        /**
         * What an import added, against the library before it (#6).
         *
         * The old confirmation counted the whole library as if this import had
         * brought all of it, so adding one sprite read the same as adding 772.
         */
        public String changesSince(Report before) {
            var added = new java.util.ArrayList<String>();
            if (games > before.games) added.add("the game file");
            if (species > before.species) added.add(count(species - before.species, "Pokémon picture"));
            if (shiny > before.shiny) added.add(count(shiny - before.shiny, "shiny picture"));
            if (wallpapers > before.wallpapers) added.add(count(wallpapers - before.wallpapers, "box background"));
            if (sheets > before.sheets) added.add(count(sheets - before.sheets, "scenery sheet"));
            if (!added.isEmpty()) return "Added " + String.join(", ", added) + ".";
            return imported > 0 ? "Updated " + count(imported, "file") + "; nothing new was added."
                : "Nothing new was added: your library already had everything in that import.";
        }

        /** Settings' four rows, in the order a player needs them. */
        public List<Category> categories() {
            return List.of(
                new Category("game", "Game file", Math.min(games, 1), 1, games > 0 ? "in your library" : ""),
                new Category("pokemon", "Pokémon pictures", species + shiny, SPECIES * 2,
                    species + " of " + SPECIES + " normal, " + shiny + " shiny"),
                new Category("backgrounds", "Box backgrounds", wallpapers, WALLPAPERS, wallpapers + " of " + WALLPAPERS),
                new Category("scenery", "Study scenery", scene(), SCENE_SHEETS, scene() + " of " + SCENE_SHEETS + " sheets"
                    + (cameos > 0 ? " · " + count(cameos, "route visitor") : "")));
        }

        private static String count(int n, String noun) { return n + " " + noun + (n == 1 ? "" : "s"); }
    }

    static final int WALLPAPERS = 16;

    /** How complete one kind of artwork is. */
    public enum Readiness { READY, PARTIAL, MISSING }

    /** One kind of artwork: how much of it is installed, out of how much there is. */
    public record Category(String key, String name, int found, int expected, String detail) {
        public Readiness readiness() {
            return found >= expected ? Readiness.READY : found == 0 ? Readiness.MISSING : Readiness.PARTIAL;
        }
        public String describe() {
            return switch (readiness()) {
                case READY -> detail.isEmpty() ? "Ready" : "Ready · " + detail;
                case PARTIAL -> "Partial · " + detail;
                case MISSING -> "Missing";
            };
        }
    }

    /**
     * Where a source file belongs in the library, or null if it is not artwork.
     * Path segments matter: a file under any folder named "shiny" is a shiny.
     */
    static String destinationFor(String rawPath) { return ArtworkStaging.destinationFor(rawPath); }

    /** Counts only decodable files, never filenames that merely look like images. */
    public static Report survey() { return survey(root()); }

    private static Report survey(Path root) {
        int species = 0, shiny = 0, sheets = 0, wallpapers = 0, cameos = 0;
        for (int i = 1; i <= SPECIES; i++) {
            if (ArtworkStaging.validImage(root.resolve(i + ".png"))) species++;
            if (ArtworkStaging.validImage(root.resolve("shiny/" + i + ".png"))) shiny++;
        }
        for (String sheet : SHEETS) {
            if (!ArtworkStaging.validImage(root.resolve(sheet + ".png"))) continue;
            sheets++;
            if (CAMEOS.contains(sheet)) cameos++;
        }
        for (int i = 0; i < 16; i++)
            if (ArtworkStaging.validImage(root.resolve(String.format(Locale.ROOT, "pc/wallpaper-%02d.png", i)))) wallpapers++;
        int games = Files.isRegularFile(root.resolve("games/emerald-national-dex.gba")) ? 1 : 0;
        return new Report(species, shiny, sheets, 0, games, wallpapers, 0, List.of(), cameos);
    }

    /** The last import that could not finish, kept on disk until one does (#6). */
    public record Failure(java.time.Instant when, String reason) { }

    private static Path failureFile() { return ArtworkGenerations.base().resolve("last-failure.properties"); }

    /**
     * The last import that failed, or null once an import has published since.
     *
     * A failed import changes nothing in the library, so the counts alone
     * cannot say one was tried, and a restart erased the only sign of it, the
     * error dialog. Settings reads this to mark what is still missing as
     * Failed rather than as never attempted.
     */
    public static Failure lastFailure() {
        try (var in = Files.newBufferedReader(failureFile())) {
            var saved = new Properties();
            saved.load(in);
            return new Failure(java.time.Instant.parse(saved.getProperty("when")), saved.getProperty("reason", ""));
        } catch (IOException | RuntimeException none) {
            return null;
        }
    }

    /** Stage, validate, then publish. No failed import can replace the active generation. */
    public static synchronized Report install(Path source) throws IOException {
        Report report;
        try {
            report = stageAndPublish(source);
        } catch (IOException | RuntimeException e) {
            remember(e);
            throw e;
        }
        try { Files.deleteIfExists(failureFile()); }
        catch (IOException leftForNextTime) { }
        return report;
    }

    private static void remember(Exception e) {
        var saved = new Properties();
        saved.setProperty("when", java.time.Instant.now().toString());
        saved.setProperty("reason", reason(e));
        try {
            Files.createDirectories(ArtworkGenerations.base());
            try (var out = Files.newBufferedWriter(failureFile())) { saved.store(out, null); }
        } catch (IOException ignored) {
            // The import's own error is already on its way to the player.
        }
    }

    /**
     * An import's error as product copy: the library's own messages are written
     * for players, but the file system's name a path on this machine instead of
     * saying what went wrong.
     */
    static String reason(Exception e) {
        String message = e.getMessage();
        if (e instanceof FileSystemException || message == null || message.isBlank()
                || message.contains("/") || message.contains("\\"))
            return "Yoru could not read or write part of the artwork library.";
        message = message.strip();
        return message.length() <= 240 ? message : message.substring(0, 239) + "…";
    }

    private static Report stageAndPublish(Path source) throws IOException {
        if (!Files.exists(source)) throw new IOException("That file or folder no longer exists.");
        source = source.toAbsolutePath().normalize();
        if (Files.isDirectory(source) && ArtworkGenerations.base().toAbsolutePath().normalize().startsWith(source))
            throw new IOException("Choose a specific artwork folder outside Yoru's installed library.");
        var stage = ArtworkGenerations.stage();
        boolean published = false;
        try {
            ArtworkGenerations.copyPrevious(stage.previous(), stage.path());
            var staged = ArtworkStaging.fill(source, stage.path());
            if (staged.imported() == 0) throw new IOException("No usable artwork or supported game was found. Your existing library is unchanged.");
            var found = survey(stage.path());
            ArtworkGenerations.publish(stage, "decoder=2\nspecies=" + found.species()
                + "\nshiny=" + found.shiny() + "\nwallpapers=" + found.wallpapers() + "\n");
            published = true;
            ArtworkGenerations.prune(stage.generations(), stage.generation(), stage.previous());
            return new Report(found.species(), found.shiny(), found.sheets(), staged.skipped(), found.games(),
                found.wallpapers(), staged.imported(), staged.warnings(), found.cameos());
        } finally {
            if (!published) ArtworkGenerations.discard(stage.path());
        }
    }
}
