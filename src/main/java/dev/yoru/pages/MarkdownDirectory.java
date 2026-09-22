package dev.yoru.pages;

import dev.yoru.domain.Model.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Plain Markdown interchange. Imports are prepared fully before one vault commit. */
public final class MarkdownDirectory {
    private MarkdownDirectory() { }
    private static final long MAX_BYTES = 25_000_000;
    public static Notes read(Path chosen) throws IOException {
        chosen = chosen.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(chosen)) throw new IOException("Choose a real Markdown file or folder, not a symbolic link.");
        Path base = Files.isDirectory(chosen) ? chosen : chosen.getParent();
        var folders = new ArrayList<Folder>(); var pages = new ArrayList<Page>();
        var ids = new HashMap<Path, UUID>();
        long total = 0; Instant now = Instant.now();
        List<Path> files;
        if (Files.isDirectory(chosen)) {
            try (var stream = Files.walk(chosen, 33)) { files = stream.filter(p -> !p.equals(base)).limit(20_001).sorted().toList(); }
        } else files = List.of(chosen);
        if (files.size() > 20_000) throw new IOException("Import at most 20,000 files and folders at once.");
        for (var file : files) {
            Path relative = base.relativize(file);
            if (relative.toString().split("[/\\\\]").length > 32) throw new IOException("This folder tree is too deep to import.");
            boolean hidden = false; for (Path part : relative) if (part.toString().startsWith(".")) hidden = true;
            if (hidden) continue;
            if (Files.isSymbolicLink(file)) throw new IOException("Symbolic links are not imported. Remove the link from the selected folder first.");
            UUID parent = ids.get(file.getParent());
            if (Files.isDirectory(file)) {
                UUID id = UUID.randomUUID(); ids.put(file, id);
                folders.add(new Folder(id, parent, file.getFileName().toString(), now, null));
            } else if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                long size = Files.size(file); total += size;
                if (total > MAX_BYTES || size > 4L * Page.MAX_BODY) throw new IOException("The selected Markdown is too large to import.");
                byte[] bytes; try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes((int) Math.min(MAX_BYTES - total + size, 4L * Page.MAX_BODY) + 1); }
                if (bytes.length > size) throw new IOException("A file changed during import. Try again.");
                String text;
                try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
                catch (CharacterCodingException e) { throw new IOException("Markdown files must contain valid UTF-8 text."); }
                String name = file.getFileName().toString();
                pages.add(new Page(UUID.randomUUID(), parent, name.substring(0, name.length() - 3), text, now, now, null));
            }
        }
        if (pages.isEmpty()) throw new IOException("No Markdown (.md) pages were found.");
        return new Notes(folders, pages);
    }
    /** Writes a new child directory and never overwrites an existing export. */
    public static Path write(Notes notes, Path parent) throws IOException {
        if (!Files.isDirectory(parent)) throw new IOException("Choose an existing export folder.");
        Path staged = Files.createTempDirectory(parent, ".yoru-export-");
        try {
            var resolver = new Links.Resolver(notes);
            for (var page : notes.pages()) if (!page.trashed()) {
                Path target = staged.resolve(resolver.path(page) + ".md").normalize();
                if (!target.startsWith(staged)) throw new IOException("Invalid page path.");
                Files.createDirectories(target.getParent());
                Files.writeString(target, page.body(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            // Keep empty folders too.
            var byId = new HashMap<UUID, Folder>(); for (var f : notes.folders()) byId.put(f.id(), f);
            for (var f : notes.folders()) if (!f.trashed()) {
                var parts = new ArrayDeque<String>();
                for (var at = f; at != null; at = byId.get(at.parentId())) parts.push(at.name());
                Files.createDirectories(staged.resolve(String.join("/", parts)));
            }
            String name = "Yoru Pages"; int n = 1;
            while (Files.exists(parent.resolve(name))) name = "Yoru Pages " + n++;
            return Files.move(staged, parent.resolve(name));
        } catch (Exception e) {
            try (var files = Files.walk(staged)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
            throw e;
        }
    }
}
