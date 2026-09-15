package dev.yoru.assets;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Reads one import into a staging folder, and decides what in it is artwork (#12).
 *
 * An import's source can be a folder, a zip with zips inside it, an Emerald game
 * file or a single picture. Whatever it is, it is copied into a generation that
 * is not yet published, under one budget for bytes and files across all of it.
 * A file is kept only when its name maps to a place in the library and it
 * decodes as an image of a sensible size; anything else is skipped and counted.
 *
 * Split out of ArtworkLibrary, which plans an import and says what the library
 * holds; ArtworkGenerations publishes the stage.
 */
final class ArtworkStaging {
    private ArtworkStaging() { }

    static final long MAX_IMAGE_BYTES = 512 * 1024;
    static final long MAX_GAME_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    /** What one import put into its stage. */
    record Staged(int skipped, int imported, List<String> warnings) { }

    /** Copies the artwork in {@code source} into {@code stage}, or refuses a source that is none of the kinds accepted. */
    static Staged fill(Path source, Path stage) throws IOException {
        Budget budget = new Budget();
        int[] skipped = {0}, games = {0};
        if (Files.isDirectory(source)) copyTree(source, stage, skipped, games, budget);
        else if (source.toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
            unpack(source, stage, skipped, games, budget, 0);
        else if (isGameFile(source.toString())) {
            budget.add(Files.size(source)); copyGame(source, stage, games, budget);
        } else if (destinationFor(source.getFileName().toString()) != null)
            copyImage(source, stage, destinationFor(source.getFileName().toString()), skipped, budget);
        else throw new IOException("Choose an Emerald game file, a folder, or a zip of artwork.");
        return new Staged(skipped[0], budget.imported, List.copyOf(budget.warnings));
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

        if (ArtworkLibrary.SHEETS.contains(base)) return base + ".png";
        if (base.matches("wallpaper-(?:0[0-9]|1[0-5])")) return "pc/" + base + ".png";

        // Species files are numbered; tolerate zero padding and a "shiny" folder.
        String digits = base.replaceFirst("^0+(?=\\d)", "");
        if (!digits.matches("\\d{1,4}")) return null;
        int number;
        try { number = Integer.parseInt(digits); } catch (NumberFormatException e) { return null; }
        if (number < 1 || number > ArtworkLibrary.SPECIES) return null;

        boolean shiny = Arrays.stream(path.split("/"))
            .anyMatch(segment -> segment.equalsIgnoreCase("shiny"));
        return (shiny ? "shiny/" : "") + number + ".png";
    }

    static boolean validImage(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file)>MAX_IMAGE_BYTES) return false;
            try (var input=javax.imageio.ImageIO.createImageInputStream(file.toFile())) {
                var readers=javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return false;
                var reader=readers.next();
                try {
                    reader.setInput(input);
                    return reader.getWidth(0)>0 && reader.getHeight(0)>0
                        && reader.getWidth(0)<=256 && reader.getHeight(0)<=256 && reader.read(0)!=null;
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException e) { return false; }
    }

    private static void copyTree(Path source, Path root, int[] skipped, int[] games, Budget budget) throws IOException {
        try (var walk=Files.walk(source)) {
            var iterator=walk.iterator();
            while(iterator.hasNext()) {
                Path file=iterator.next();
                if (++budget.entries>MAX_ENTRIES) throw new IOException("That folder has too many entries.");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative=source.relativize(file).toString();
                if (relative.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    budget.add(Files.size(file)); unpack(file,root,skipped,games,budget,0);
                } else if (isGameFile(relative)) {
                    budget.add(Files.size(file)); copyGame(file,root,games,budget);
                } else {
                    String destination=destinationFor(relative);
                    if(destination==null) { budget.add(Files.size(file)); skipped[0]++; }
                    else copyImage(file,root,destination,skipped,budget);
                }
            }
        }
    }

    private static void copyImage(Path source, Path root, String destination, int[] skipped, Budget budget) throws IOException {
        budget.add(Files.size(source));
        if (!validImage(source)) { skipped[0]++; return; }
        Files.copy(source,root.resolve(destination),StandardCopyOption.REPLACE_EXISTING);
        budget.imported++;
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

    private static void copyGame(Path file, Path root, int[] games, Budget budget) throws IOException {
        if (Files.size(file) > MAX_GAME_BYTES) return;
        String hash = sha256(file);
        if (!ArtworkLibrary.EXPECTED_GAME_SHA256.equalsIgnoreCase(hash))
            throw new IOException("That game file is not the supported Emerald Hoenn + National Dex Edition.\n"
                + "Choose your supported Emerald game file or its matching zip.");
        Path gamesRoot = root.resolve("games");
        Files.createDirectories(gamesRoot);
        budget.warnings.addAll(EmeraldArtwork.extractAvailable(Files.readAllBytes(file), root));
        String name = "emerald-national-dex.gba";
        if (!file.toAbsolutePath().normalize().equals(gamesRoot.resolve(name).toAbsolutePath().normalize()))
            Files.copy(file, gamesRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        games[0]++;
        budget.imported++;
    }

    private static final class Budget {
        long bytes; int entries, imported;
        final List<String> warnings = new ArrayList<>();
        void add(long size) throws IOException {
            bytes += size;
            if (bytes > MAX_TOTAL_BYTES) throw new IOException("That import is too large.");
        }
    }

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
                    try { copyBounded(in, temp, MAX_GAME_BYTES, budget); copyGame(temp, root, games, budget); }
                    finally { Files.deleteIfExists(temp); }
                    continue;
                }
                String destination = destinationFor(entry.getName());
                if (destination == null) {
                    byte[] drain=new byte[8192]; int count;
                    while((count=in.read(drain))!=-1) budget.add(count);
                    skipped[0]++; continue;
                }
                Path image = Files.createTempFile("yoru-image-", ".png");
                try {
                    copyBounded(in,image,MAX_IMAGE_BYTES,budget);
                    if (!validImage(image)) { skipped[0]++; continue; }
                    Files.copy(image,root.resolve(destination),StandardCopyOption.REPLACE_EXISTING);
                    budget.imported++;
                } finally { Files.deleteIfExists(image); }
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
