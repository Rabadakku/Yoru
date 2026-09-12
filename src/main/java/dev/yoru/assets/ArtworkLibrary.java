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

    private static Path base() {
        return Path.of(System.getProperty("user.home"), ".yoru", "art");
    }

    /** One atomic pointer selects a complete generation; old loose libraries still work. */
    public static Path root() {
        Path base = base();
        try {
            String generation = Files.readString(base.resolve("current")).strip();
            if (generation.matches("[a-f0-9-]{36}")) {
                Path active = base.resolve("generations").resolve(generation);
                if (Files.isRegularFile(active.resolve("manifest.properties"))) return active;
            }
        } catch (IOException ignored) { }
        return base;
    }

    public record Report(int species, int shiny, int sheets, int skipped, int games,
                         int wallpapers, int imported, List<String> warnings) {
        public Report { warnings = List.copyOf(warnings); }
        public Report(int species, int shiny, int sheets, int skipped, int games) {
            this(species, shiny, sheets, skipped, games, 0, 0, List.of());
        }
        public boolean empty() { return species == 0 && shiny == 0 && sheets == 0 && wallpapers == 0; }
        public String summary() {
            String summary = species + "/" + SPECIES + " Pokémon pictures · " + shiny + " shiny · "
                + wallpapers + "/16 box backgrounds · " + sheets + " scenery sheets";
            if (imported > 0) summary = imported + " files imported. " + summary;
            if (empty()) summary = "No artwork installed. Pokémon names, levels and dex numbers remain available.";
            if (skipped > 0) summary += " · " + skipped + " files skipped";
            if (!warnings.isEmpty()) summary += "\n" + String.join("\n", warnings);
            return summary;
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
        if (base.matches("wallpaper-(?:0[0-9]|1[0-5])")) return "pc/" + base + ".png";

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

    /** Counts only decodable files, never filenames that merely look like images. */
    public static Report survey() { return survey(root()); }

    private static Report survey(Path root) {
        int species = 0, shiny = 0, sheets = 0, wallpapers = 0;
        for (int i = 1; i <= SPECIES; i++) {
            if (validImage(root.resolve(i + ".png"))) species++;
            if (validImage(root.resolve("shiny/" + i + ".png"))) shiny++;
        }
        for (String sheet : SHEETS) if (validImage(root.resolve(sheet + ".png"))) sheets++;
        for (int i = 0; i < 16; i++)
            if (validImage(root.resolve(String.format(Locale.ROOT, "pc/wallpaper-%02d.png", i)))) wallpapers++;
        int games = Files.isRegularFile(root.resolve("games/emerald-national-dex.gba")) ? 1 : 0;
        return new Report(species, shiny, sheets, 0, games, wallpapers, 0, List.of());
    }

    /** Stage, validate, then publish. No failed import can replace the active generation. */
    public static synchronized Report install(Path source) throws IOException {
        if (!Files.exists(source)) throw new IOException("That file or folder no longer exists.");
        source = source.toAbsolutePath().normalize();
        if (Files.isDirectory(source) && base().toAbsolutePath().normalize().startsWith(source))
            throw new IOException("Choose a specific artwork folder outside Yoru's installed library.");
        Path previous = root();
        Path generations = base().resolve("generations");
        Files.createDirectories(generations);
        String generation = UUID.randomUUID().toString();
        Path stage = generations.resolve(generation);
        Files.createDirectories(stage.resolve("shiny"));
        Files.createDirectories(stage.resolve("pc"));
        Files.createDirectories(stage.resolve("games"));
        Budget budget = new Budget();
        int[] skipped = {0}, games = {0};
        boolean published = false;
        Path pointer = base().resolve("current-" + generation);
        try {
            copyPrevious(previous, stage);
            if (Files.isDirectory(source)) copyTree(source, stage, skipped, games, budget);
            else if (source.toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                unpack(source, stage, skipped, games, budget, 0);
            else if (isGameFile(source.toString())) {
                budget.add(Files.size(source)); copyGame(source, stage, games, budget);
            } else if (destinationFor(source.getFileName().toString()) != null)
                copyImage(source, stage, destinationFor(source.getFileName().toString()), skipped, budget);
            else throw new IOException("Choose an Emerald game file, a folder, or a zip of artwork.");
            if (budget.imported == 0) throw new IOException("No usable artwork or supported game was found. Your existing library is unchanged.");
            var found = survey(stage);
            Files.writeString(stage.resolve("manifest.properties"), "decoder=2\nspecies=" + found.species()
                + "\nshiny=" + found.shiny() + "\nwallpapers=" + found.wallpapers() + "\n");
            try (var files = Files.walk(stage)) {
                for (var file : files.filter(Files::isRegularFile).toList()) force(file);
            }
            Files.writeString(pointer, generation);
            force(pointer);
            Files.move(pointer, base().resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            return new Report(found.species(), found.shiny(), found.sheets(), skipped[0], found.games(),
                found.wallpapers(), budget.imported, budget.warnings);
        } finally {
            Files.deleteIfExists(pointer);
            if (!published) deleteTree(stage);
        }
    }

    private static void force(Path file) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (var file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private static void copyPrevious(Path previous, Path stage) throws IOException {
        var names = new ArrayList<String>();
        for (int i=1; i<=SPECIES; i++) { names.add(i+".png"); names.add("shiny/"+i+".png"); }
        for (String sheet : SHEETS) names.add(sheet+".png");
        for (int i=0; i<16; i++) names.add(String.format(Locale.ROOT,"pc/wallpaper-%02d.png",i));
        for (String name : names) if (validImage(previous.resolve(name))) Files.copy(previous.resolve(name),stage.resolve(name));
        var game = previous.resolve("games/emerald-national-dex.gba");
        if (Files.isRegularFile(game) && Files.size(game)<=MAX_GAME_BYTES) Files.copy(game,stage.resolve("games/emerald-national-dex.gba"));
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
        if (!EXPECTED_GAME_SHA256.equalsIgnoreCase(hash))
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
