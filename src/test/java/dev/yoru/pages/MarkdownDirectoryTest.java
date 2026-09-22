package dev.yoru.pages;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.nio.file.*;
import java.time.Clock;
import java.io.IOException;
import java.util.Comparator;

public final class MarkdownDirectoryTest {
    static class Memory implements Repository {
        State state = State.empty(); boolean fail; int saves;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("Synthetic failure"); state = next; saves++; }
        public void close() { }
    }
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("yoru-markdown-");
        try {
            var source = Files.createDirectories(base.resolve("source/Folder"));
            String original = "---\naliases: [Study]\n---\n# Résumé\r\n[[Other]]\n**Unicode 文**\n";
            Files.writeString(source.resolve("Notes.md"), original);
            Files.writeString(source.resolve("Other.md"), "[[Notes#Résumé]]\n");
            var notes = MarkdownDirectory.read(base.resolve("source"));
            assert notes.pages().size() == 2 && notes.folders().size() == 1;
            var output = MarkdownDirectory.write(notes, base);
            assert Files.readString(output.resolve("Folder/Notes.md")).equals(original);
            assert MarkdownDirectory.read(output).pages().size() == 2;
            var second = MarkdownDirectory.write(notes, base); assert !second.equals(output);
            var repo = new Memory(); var tracker = new Tracker(repo, Clock.systemUTC());
            var before = tracker.state(); repo.fail = true;
            try { tracker.pages().importNotes(notes); throw new AssertionError("failed import accepted"); }
            catch (IOException expected) { assert tracker.state().equals(before); }
            repo.fail = false; tracker.pages().importNotes(notes);
            assert repo.saves == 1 && tracker.state().notes().pages().size() == 2;
            var resolver = new Links.Resolver(tracker.state().notes());
            var page = tracker.state().notes().pages().getFirst();
            assert resolver.resolve("Other", page).isPresent();
            Files.write(base.resolve("bad.md"), new byte[]{(byte)0xff});
            try { MarkdownDirectory.read(base.resolve("bad.md")); throw new AssertionError("invalid UTF-8 accepted"); }
            catch (IOException expected) { }
            try {
                Files.createSymbolicLink(base.resolve("linked.md"), source.resolve("Notes.md"));
                try { MarkdownDirectory.read(base.resolve("linked.md")); throw new AssertionError("symlink accepted"); }
                catch (IOException expected) { }
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) { }
            System.out.println("PASS: Markdown folder roundtrip, Unicode preservation, no overwrites, atomic import and unsafe-file refusal");
        } finally {
            try (var all = Files.walk(base)) { for (var path : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
