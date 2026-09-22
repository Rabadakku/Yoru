package dev.yoru.pages;

import dev.yoru.domain.Model.*;
import java.time.Instant;
import java.util.*;

/** Which page a link means, how links are written, and that changes never alter what a link means. Invented pages. */
public final class LinksTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final Instant T = Instant.parse("2026-09-22T09:00:00Z");
    private static Folder folder(String name, Folder parent) {
        return new Folder(UUID.nameUUIDFromBytes(("f:" + name).getBytes()), parent == null ? null : parent.id(), name, T, null);
    }
    private static Page page(String title, Folder in, String body) {
        return new Page(UUID.nameUUIDFromBytes(("p:" + (in == null ? "" : in.name()) + "/" + title).getBytes()),
            in == null ? null : in.id(), title, body, T, T, null);
    }

    public static void main(String[] args) {
        var classes = folder("Classes", null);
        var bio = folder("Biology", classes);
        var chem = folder("Chemistry", classes);
        var bioNotes = page("Notes", bio, "");
        var chemNotes = page("Notes", chem, "");
        var cells = page("Cells", bio, "");
        var inbox = page("Inbox", null, "");
        var gone = page("Gone", null, "").withDeletedAt(T);
        var notes = new Notes(List.of(classes, bio, chem), List.of(bioNotes, chemNotes, cells, inbox, gone));
        var r = new Links.Resolver(notes);

        // Resolving.
        check(r.path(bioNotes).equals("Classes/Biology/Notes"), "A page's path runs from the top");
        check(r.resolve("cells", inbox).orElseThrow().id().equals(cells.id()), "A title matches ignoring case");
        check(r.resolve("Cells.md", inbox).orElseThrow().id().equals(cells.id()), "A .md suffix is ignored");
        check(r.resolve("Notes", cells).orElseThrow().id().equals(bioNotes.id()), "A shared title prefers the linking page's folder");
        check(r.resolve("Chemistry/Notes", cells).orElseThrow().id().equals(chemNotes.id()), "A path picks the other one");
        check(r.resolve("Classes/Chemistry/Notes", inbox).orElseThrow().id().equals(chemNotes.id()), "and so does a full path");
        check(r.resolve("Notes", inbox).orElseThrow().id().equals(bioNotes.id()),
            "From elsewhere, ties go to the shorter path then the first by path");
        check(r.resolve("Gone", inbox).isEmpty(), "A page in the trash is never a link's answer");
        check(r.resolve("Nowhere", inbox).isEmpty(), "A link to no page is unresolved");
        check(r.resolve("", cells).orElseThrow().id().equals(cells.id()), "[[#Heading]] means the page it is in");

        // Writing links.
        check(r.linkText(cells, inbox).equals("Cells"), "A unique title is enough");
        check(r.linkText(chemNotes, inbox).equals("Chemistry/Notes"), "A shared title takes as much path as it needs");
        check(r.linkText(bioNotes, cells).equals("Notes"), "From its own folder, the title alone is enough");

        // Reading links out of text.
        var text = "[[Cells#Membrane|the membrane]], ![[Cells]], [x](Chemistry/Notes.md#Top), [web](https://x.org), "
            + "![[diagram.png]], [[photo.jpg]], [[Report.v2]] and `[[in code]]`.";
        var refs = Links.refs(text);
        check(refs.size() == 4, "Page links only: not web addresses, not files, not code: " + refs.size());
        var first = refs.getFirst();
        check(text.substring(first.targetStart(), first.targetEnd()).equals("Cells") && first.anchor().equals("Membrane"),
            "A link's target range is its name, apart from heading and alias");
        check(refs.get(2).markdown() && refs.get(2).target().equals("Chemistry/Notes"), "A Markdown link names a page by its path");
        check(text.substring(refs.get(2).targetStart(), refs.get(2).targetEnd()).equals("Chemistry/Notes.md"), "and its range is the address");
        check(refs.get(3).target().equals("Report.v2"), "A dot followed by something longer than an extension is part of a name");

        // Keeping meaning: a rename.
        var linking = page("Linking", null, "See [[Cells#Membrane|the membrane]] and [x](Cells.md) and [[cells]].");
        var before = new Notes(notes.folders(), List.of(bioNotes, chemNotes, cells, inbox, gone, linking));
        var renamedCells = cells.withTitle("Cell biology", T);
        var after = new Notes(notes.folders(), List.of(bioNotes, chemNotes, renamedCells, inbox, gone, linking));
        var rewritten = Links.keepMeaning(before, after);
        check(rewritten.get(linking.id()).equals("See [[Cell biology#Membrane|the membrane]] and [x](Cell%20biology.md) and [[Cell biology]]."),
            "A rename rewrites every link to the page, keeping heading and alias: " + rewritten.get(linking.id()));

        // Keeping meaning: a new page that would steal a link.
        var fromInbox = page("From inbox", null, "[[Notes]] means Biology's.");
        var base = new Notes(notes.folders(), List.of(bioNotes, chemNotes, cells, inbox, fromInbox));
        var stealing = page("Notes", null, "");
        var withNew = new Notes(notes.folders(), List.of(bioNotes, chemNotes, cells, inbox, fromInbox, stealing));
        check(Links.keepMeaning(base, withNew).get(fromInbox.id()).equals("[[Biology/Notes]] means Biology's."),
            "A new page closer to a link does not take the link from the page it meant");

        // Unresolved links resolve when their page arrives, untouched.
        var dangling = page("Dangling", null, "[[Future]]");
        var plan = new Notes(List.of(), List.of(dangling));
        var arrived = new Notes(List.of(), List.of(dangling, page("Future", null, "")));
        check(Links.keepMeaning(plan, arrived).isEmpty(), "Creating a page resolves links to it without rewriting them");

        // Moving a page deeper than a namesake keeps links pointing at it.
        var top = page("Notes", null, "");
        var pointer = page("Pointer", null, "[[Notes]]");
        var namesake = page("Notes", classes, "");
        var start = new Notes(List.of(classes, bio), List.of(top, pointer, namesake));
        var moved = new Notes(List.of(classes, bio), List.of(top.withFolder(bio.id(), T), pointer, namesake));
        check(Links.keepMeaning(start, moved).get(pointer.id()).equals("[[Biology/Notes]]"),
            "Moving a page where a namesake would take its links rewrites them to follow it");
        var shallower = new Notes(List.of(classes, bio), List.of(top, pointer, page("Notes", bio, "")));
        var stillWins = new Notes(List.of(classes, bio), List.of(top.withFolder(classes.id(), T), pointer, page("Notes", bio, "")));
        check(!Links.keepMeaning(shallower, stillWins).containsKey(pointer.id()),
            "A move that leaves a link meaning the same page rewrites nothing");

        var unicode = page("Unicode", null, "İstanbul\nFind target here.");
        var index = new PageIndex(new Notes(List.of(), List.of(unicode)));
        var hit = index.search("target").getFirst().lines().getFirst();
        check(unicode.body().substring(hit.start(), hit.end()).equals("target"), "Unicode search keeps original source offsets");

        System.out.println("PASS: " + checks + " link checks (resolution, link text, reading links, keeping meaning)");
    }
}
