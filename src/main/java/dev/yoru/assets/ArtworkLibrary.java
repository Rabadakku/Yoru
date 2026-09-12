package dev.yoru.assets;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;

/**
 * Imports user-supplied artwork into a folder the app owns.
 *
 * Yoru ships no game assets, so every install starts empty and the user brings
 * their own. That has to be one drop of a folder or a zip, not a naming
 * exercise, so this accepts whatever shape the files arrive in — nested
 * directories, zero-padded numbers, mixed case — and normalises them.
 *
 * Anything it does not recognise is skipped and counted rather than guessed at.
 */
public final class ArtworkLibrary {
    private ArtworkLibrary() { }

    public static final int SPECIES = 386;
    private static final long MAX_IMAGE_BYTES = 512 * 1024;
    private static final long MAX_GAME_BYTES = 32L * 1024 * 1024;
    /** SHA-256 of the Emerald Hoenn + National Dex ROM this build is verified against. */
    public static final String EXPECTED_GAME_SHA256 =
        "8a80807472ab4bc95f1e9e577873866849971cbe3d64b1d86475205b5be29836";
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    /** Overworld and scenery sheets, kept under their own names. */
    private static final Set<String> SHEETS = Set.of(
        "brendan", "brendan-running", "may", "may-running", "tree", "rock", "grass",
        "route-trainer", "route-cyclist", "team-rocket");

    public static Path root() {
        return Path.of(System.getProperty("user.home"), ".yoru", "art");
    }

    /** What an import found, or what the library currently holds. */
    public record Report(int species, int shiny, int sheets, int skipped, int games) {
        public boolean empty() { return species == 0 && shiny == 0 && sheets == 0; }
        public String summary() {
            if (empty() && games > 0) return games + " game file" + (games == 1 ? "" : "s")
                + " recognized. No PNG artwork was found; the collection will show dex numbers.";
            if (empty()) return "No artwork installed — the collection shows dex numbers.";
            return species + "/" + SPECIES + " species  ·  " + shiny + " shiny  ·  "
                 + sheets + "/" + SHEETS.size() + " overworld sheets"
                 + (games > 0 ? "  ·  " + games + " game file" + (games == 1 ? "" : "s") : "")
                 + (skipped > 0 ? "  ·  " + skipped + " files skipped" : "");
        }
    }

    /**
     * Where a source file belongs in the library, or null if it is not artwork.
     * Path segments matter: a file under any folder named "shiny" is a shiny.
     */
    static String destinationFor(String rawPath) {
        String path = rawPath.replace('\\', '/');
        if (path.contains("..")) return null;                       // never trust an archive path
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) return null;
        String base = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT).strip();
        if (base.isEmpty()) return null;

        if (SHEETS.contains(base)) return base + ".png";

        // Species files are numbered; tolerate zero padding and a "shiny" folder.
        String digits = base.replaceFirst("^0+(?=\\d)", "");
        if (!digits.matches("\\d{1,4}")) return null;
        int number;
        try { number = Integer.parseInt(digits); } catch (NumberFormatException e) { return null; }
        if (number < 1 || number > SPECIES) return null;

        boolean shiny = Arrays.stream(path.split("/"))
            .anyMatch(segment -> segment.equalsIgnoreCase("shiny"));
        return (shiny ? "shiny/" : "") + number + ".png";
    }

    /** Counts what is already installed. */
    public static Report survey() {
        Path root = root();
        if (!Files.isDirectory(root)) return new Report(0, 0, 0, 0, 0);
        int species = 0, shiny = 0, sheets = 0;
        for (int i = 1; i <= SPECIES; i++) {
            if (Files.isRegularFile(root.resolve(i + ".png"))) species++;
            if (Files.isRegularFile(root.resolve("shiny").resolve(i + ".png"))) shiny++;
        }
        for (String sheet : SHEETS) if (Files.isRegularFile(root.resolve(sheet + ".png"))) sheets++;
        int games = 0;
        Path gameRoot = root.resolve("games");
        if (Files.isDirectory(gameRoot)) try (var files = Files.list(gameRoot)) {
            games = (int) files.filter(Files::isRegularFile).count();
        } catch (IOException ignored) { }
        return new Report(species, shiny, sheets, 0, games);
    }

    /** Imports a folder or a .zip. Existing files are replaced. */
    public static Report install(Path source) throws IOException {
        if (!Files.exists(source)) throw new IOException("That file or folder no longer exists.");
        Path root = root();
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("shiny"));
        int[] skipped = {0};
        int[] games = {0};
        if (Files.isDirectory(source)) copyTree(source, root, skipped, games);
        else if (source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
            unpack(source, root, skipped, games, new Budget(), 0);
        else if (isGameFile(source.getFileName().toString())) {
            copyGame(source, root, games);
        }
        else if (destinationFor(source.getFileName().toString()) != null)
            copyOne(source, root, skipped);
        else throw new IOException("Choose an Emerald .gba, a folder, or a .zip of artwork.");

        var found = survey();
        var report = new Report(found.species(), found.shiny(), found.sheets(), skipped[0],
            Math.max(found.games(), games[0]));
        if (report.empty() && report.games() == 0) throw new IOException(
            "No usable artwork found. Expected PNGs named 1.png to " + SPECIES + ".png, "
            + "optionally a shiny folder, and the overworld sheets.");
        return report;
    }

    private static void copyTree(Path source, Path root, int[] skipped, int[] games) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = source.relativize(file).toString();
                if (relative.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    unpack(file, root, skipped, games, new Budget(), 0); continue;
                }
                if (isGameFile(relative)) { copyGame(file, root, games); continue; }
                String destination = destinationFor(relative);
                if (destination == null || Files.size(file) > MAX_IMAGE_BYTES) { skipped[0]++; continue; }
                Files.copy(file, root.resolve(destination), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyOne(Path file, Path root, int[] skipped) throws IOException {
        if (Files.size(file) > MAX_IMAGE_BYTES) { skipped[0]++; return; }
        Files.copy(file, root.resolve(destinationFor(file.getFileName().toString())),
            StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Entry names are only ever used to decide a destination we compute
     * ourselves, so a crafted path cannot escape the library.
     */
    private static boolean isGameFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gba") || lower.endsWith(".gbc") || lower.endsWith(".gb")
            || lower.endsWith(".nds");
    }

    private static void copyGame(Path file, Path root, int[] games) throws IOException {
        if (Files.size(file) > MAX_GAME_BYTES) return;
        String hash = sha256(file);
        if (!EXPECTED_GAME_SHA256.equalsIgnoreCase(hash))
            throw new IOException("That game file is not the supported Emerald Hoenn + National Dex Edition.\n"
                + "Choose your supported Emerald game file or its matching zip.");
        Path gamesRoot = root.resolve("games");
        Files.createDirectories(gamesRoot);
        EmeraldArtwork.extract(Files.readAllBytes(file), root);
        String name = "emerald-national-dex.gba";
        if (!file.toAbsolutePath().normalize().equals(gamesRoot.resolve(name).toAbsolutePath().normalize()))
            Files.copy(file, gamesRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        games[0]++;
    }

    private static final class Budget { long bytes; int entries; }

    private static void copyBounded(InputStream in, Path target, long limit, Budget budget) throws IOException {
        try (var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192]; long count = 0; int read;
            while ((read = in.read(buffer)) != -1) {
                count += read; budget.bytes += read;
                if (count > limit || budget.bytes > MAX_TOTAL_BYTES)
                    throw new IOException("That archive is too large.");
                out.write(buffer, 0, read);
            }
        }
    }

    private static void unpack(Path zip, Path root, int[] skipped, int[] games, Budget budget, int depth) throws IOException {
        if (depth > 4) throw new IOException("That archive contains too many nested archives.");
        int entries = 0;
        try (var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries++;
                if (++budget.entries > MAX_ENTRIES) throw new IOException("That archive has too many files.");
                if (entry.isDirectory()) continue;
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    Path nested = Files.createTempFile("yoru-art-", ".zip");
                    try { copyBounded(in, nested, MAX_TOTAL_BYTES, budget); unpack(nested, root, skipped, games, budget, depth + 1); }
                    finally { Files.deleteIfExists(nested); }
                    continue;
                }
                if (isGameFile(entry.getName())) {
                    Path temp = Files.createTempFile("yoru-game-", ".bin");
                    try { copyBounded(in, temp, MAX_GAME_BYTES, budget); copyGame(temp, root, games); }
                    finally { Files.deleteIfExists(temp); }
                    continue;
                }
                String destination = destinationFor(entry.getName());
                if (destination == null) { skipped[0]++; continue; }
                var bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES || bytes.length < 8) { skipped[0]++; continue; }
                // A PNG signature, so a renamed executable does not land in the library.
                if (!(bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')) {
                    skipped[0]++;
                    continue;
                }
                budget.bytes += bytes.length;
                if (budget.bytes > MAX_TOTAL_BYTES) throw new IOException("That archive is too large.");
                Files.write(root.resolve(destination), bytes);
            }
        }
        if (entries == 0) throw new IOException("That archive is empty.");
    }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            var out = new StringBuilder(64);
            for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 is unavailable.", e); }
    }
}
