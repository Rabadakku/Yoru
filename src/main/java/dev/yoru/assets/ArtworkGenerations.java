package dev.yoru.assets;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/**
 * Where the artwork library lives on disk, and how a staged import becomes the
 * library (#12).
 *
 * The library is a folder of generations with one pointer, {@code current},
 * naming the complete generation in use. An import builds a new generation
 * beside it and the pointer moves in one atomic rename, so a failure at any
 * point leaves the previous library in place. After a publish only the new
 * generation and the one it replaced are kept.
 *
 * Split out of ArtworkLibrary; ArtworkStaging fills the stage this creates.
 */
final class ArtworkGenerations {
    private ArtworkGenerations() { }

    /** A generation being built: its folder, its name, where generations live, and the library it will replace. */
    record Stage(Path path, String generation, Path generations, Path previous) { }

    static Path base() {
        return Path.of(System.getProperty("user.home"), ".yoru", "art");
    }

    /** One atomic pointer selects a complete generation; old loose libraries still work. */
    static Path root() {
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

    /** A new, empty generation beside the library in use. */
    static Stage stage() throws IOException {
        Path previous = root();
        Path generations = base().resolve("generations");
        Files.createDirectories(generations);
        String generation = UUID.randomUUID().toString();
        Path stage = generations.resolve(generation);
        Files.createDirectories(stage.resolve("shiny"));
        Files.createDirectories(stage.resolve("pc"));
        Files.createDirectories(stage.resolve("games"));
        return new Stage(stage, generation, generations, previous);
    }

    /** Copies the library in use into a new generation, so an import adds to it rather than replacing it. */
    static void copyPrevious(Path previous, Path stage) throws IOException {
        var names = new ArrayList<String>();
        for (int i=1; i<=ArtworkLibrary.SPECIES; i++) { names.add(i+".png"); names.add("shiny/"+i+".png"); }
        for (String sheet : ArtworkLibrary.SHEETS) names.add(sheet+".png");
        for (int i=0; i<16; i++) names.add(String.format(Locale.ROOT,"pc/wallpaper-%02d.png",i));
        for (String name : names) if (ArtworkStaging.validImage(previous.resolve(name))) Files.copy(previous.resolve(name),stage.resolve(name));
        var game = previous.resolve("games/emerald-national-dex.gba");
        if (Files.isRegularFile(game) && Files.size(game)<=ArtworkStaging.MAX_GAME_BYTES) Files.copy(game,stage.resolve("games/emerald-national-dex.gba"));
    }

    /**
     * Makes a staged generation the library: its manifest and every file forced
     * to disk, then the pointer moved onto it in one rename.
     */
    static void publish(Stage stage, String manifest) throws IOException {
        Path pointer = base().resolve("current-" + stage.generation());
        try {
            Files.writeString(stage.path().resolve("manifest.properties"), manifest);
            try (var files = Files.walk(stage.path())) {
                for (var file : files.filter(Files::isRegularFile).toList()) force(file);
            }
            Files.writeString(pointer, stage.generation());
            force(pointer);
            Files.move(pointer, base().resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pointer);
        }
    }

    /** Removes a generation that will not be published. */
    static void discard(Path stage) throws IOException { deleteTree(stage); }

    /**
     * Keeps the generation just published and the one it replaced; removes the rest (#6).
     *
     * Every import copies the whole library forward, the game file included, so
     * each one used to leave another full copy behind for good. The replaced
     * generation stays as the one-step rollback. Anything older goes, and so does
     * a staging folder a crashed import left. Only folders named like a
     * generation are touched, and one that cannot be removed now is left for the
     * next import rather than failing this one, which is already published.
     */
    static void prune(Path generations, String active, Path previous) {
        String replaced = previous != null && generations.equals(previous.getParent())
            ? previous.getFileName().toString() : null;
        try (var entries = Files.list(generations)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.matches("[a-f0-9-]{36}") || name.equals(active) || name.equals(replaced)) continue;
                try { deleteTree(entry); } catch (IOException leftForNextTime) { }
            }
        } catch (IOException leftForNextTime) { }
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
}
