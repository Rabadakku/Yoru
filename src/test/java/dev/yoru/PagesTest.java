package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * The Pages service (#46): every change one write, links that never change
 * meaning, a trash that loses nothing, and page links that survive everything
 * else done to tasks. Invented pages and tasks only.
 */
public final class PagesTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    interface Action { void run() throws Exception; }
    private static void rejects(Action action, String why) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError(why);
    }

    private static final class Memory implements Repository {
        State state = State.empty(); boolean fail; int saves, backups;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("Disk full"); state = next; saves++; }
        public void backup() { backups++; }
        public void close() { }
    }
    private static final class Hand extends Clock {
        Instant now = Instant.parse("2026-09-22T09:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private static void foldersAndPages() throws Exception {
        var repo = new Memory(); var clock = new Hand(); var t = new Tracker(repo, clock); var pages = t.pages();
        var classes = pages.createFolder(null, "Classes");
        var bio = pages.createFolder(classes.id(), "Biology");
        var cells = pages.createPage(bio.id(), "Cells", "# Cells\nThe unit of life.");
        check(t.state().notes().pages().size() == 1 && cells.folderId().equals(bio.id()), "A page is created inside a folder");
        rejects(() -> pages.createPage(bio.id(), "cells", ""), "Two live pages in one folder cannot share a name, ignoring case");
        rejects(() -> pages.createFolder(null, "a/b"), "A name cannot hold a path separator");
        rejects(() -> pages.createPage(null, "[[x]]", ""), "nor link brackets");
        check(pages.freeTitle(bio.id(), "Cells").equals("Cells 1"), "A free title adds a number, as Obsidian does");
        check(pages.freeTitle(null, "Cells").equals("Cells"), "Another folder's names do not count");

        int saves = repo.saves;
        clock.now = clock.now.plusSeconds(60);
        pages.updateBody(cells.id(), "# Cells\nThe unit of life, updated.");
        check(repo.saves == saves + 1 && pages.page(cells.id()).updatedAt().equals(clock.now), "Saving text writes once and stamps the time");
        pages.updateBody(cells.id(), "# Cells\nThe unit of life, updated.");
        check(repo.saves == saves + 1, "Saving the same text writes nothing");
        check(repo.backups == 0, "Typing never takes a backup");

        rejects(() -> pages.moveFolder(classes.id(), bio.id()), "A folder cannot move inside its own child");
        pages.moveFolder(bio.id(), null);
        check(pages.folder(bio.id()).parentId() == null, "A folder moves to the top");
    }

    private static void renamesKeepLinks() throws Exception {
        var repo = new Memory(); var t = new Tracker(repo, new Hand()); var pages = t.pages();
        var cells = pages.createPage(null, "Cells", "");
        var linking = pages.createPage(null, "Linking", "See [[Cells#Membrane|membranes]], ![[Cells]] and [c](Cells.md).");
        int saves = repo.saves;
        pages.renamePage(cells.id(), "Cell biology");
        check(repo.saves == saves + 1, "A rename and every link it rewrites are one write");
        check(pages.page(linking.id()).body().equals("See [[Cell biology#Membrane|membranes]], ![[Cell biology]] and [c](Cell%20biology.md)."),
            "Renaming a page rewrites links to it, keeping heading, alias and embed: " + pages.page(linking.id()).body());

        var folder = pages.createFolder(null, "Archive");
        pages.createPage(folder.id(), "Cell biology", "");
        // Moving the renamed page next to a namesake would change what links mean;
        // they follow it instead.
        pages.movePage(cells.id(), pages.createFolder(null, "Deep").id());
        var deep = pages.createFolder(pages.page(cells.id()).folderId(), "Deeper");
        pages.movePage(cells.id(), deep.id());
        check(pages.page(linking.id()).body().contains("[[Deeper/Cell biology#Membrane|membranes]]"),
            "Moving a page deeper than a namesake rewrites links to follow it: " + pages.page(linking.id()).body());

        repo.fail = true;
        var before = t.state();
        rejects2(() -> pages.renamePage(cells.id(), "Renamed again"));
        check(t.state() == before, "A failed write changes nothing: not the title, not a single link");
        repo.fail = false;
    }

    private static void rejects2(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("The write should have failed");
    }

    private static void trashAndRestore() throws Exception {
        var repo = new Memory(); var clock = new Hand(); var t = new Tracker(repo, clock); var pages = t.pages();
        var term = pages.createFolder(null, "Term");
        var week = pages.createFolder(term.id(), "Week 1");
        var lecture = pages.createPage(week.id(), "Lecture", "text");
        var loose = pages.createPage(term.id(), "Loose", "");
        var earlier = pages.createPage(term.id(), "Earlier", "");

        clock.now = clock.now.plusSeconds(60);
        pages.trash(List.of(earlier.id()), List.of());
        clock.now = clock.now.plusSeconds(60);
        pages.trash(List.of(), List.of(term.id()));
        check(pages.folder(week.id()).trashed() && pages.page(lecture.id()).trashed() && pages.page(loose.id()).trashed(),
            "Trashing a folder takes everything under it");
        check(repo.backups == 0, "The trash needs no backup: nothing is lost");
        pages.createFolder(null, "Term");
        checks++;   // a trashed name is free for something new

        pages.restoreFolder(term.id());
        check(!pages.folder(term.id()).trashed() && !pages.page(lecture.id()).trashed() && !pages.page(loose.id()).trashed(),
            "Restoring a folder brings back what went with it");
        check(pages.page(earlier.id()).trashed(), "but not what was trashed on its own before");
        check(pages.folder(term.id()).name().equals("Term 1"), "A restored folder whose name was taken gets a free one");

        var restored = pages.restorePage(earlier.id());
        check(!restored.trashed(), "A page comes back from the trash");

        // A page trashed inside a folder that is trashed later: restoring the page restores the folder chain.
        var a = pages.createFolder(null, "A");
        var b = pages.createFolder(a.id(), "B");
        var inner = pages.createPage(b.id(), "Inner", "");
        pages.trash(List.of(), List.of(a.id()));
        pages.restorePage(inner.id());
        check(!pages.folder(a.id()).trashed() && !pages.folder(b.id()).trashed() && !pages.page(inner.id()).trashed(),
            "Restoring one page brings back the folders above it");

        // Name taken while away.
        var draft = pages.createPage(null, "Draft", "old");
        pages.trash(List.of(draft.id()), List.of());
        pages.createPage(null, "Draft", "new");
        check(pages.restorePage(draft.id()).title().equals("Draft 1"), "A restored page whose name was taken gets a free one");
    }

    private static void purgeAndTasks() throws Exception {
        var repo = new Memory(); var t = new Tracker(repo, new Hand()); var pages = t.pages();
        t.addActivity("Study", 0);
        var activity = t.state().activities().getFirst().id();
        var tag = t.addTag("Reading", 0x90D8DA);
        t.addTasks(List.of(new Task(UUID.randomUUID(), activity, tag.id(), "Finish lab 3: part 1/2 #cs", "", null,
            TaskStatus.TODO, "", t.now(), 0)));
        var task = t.state().tasks().getFirst();

        var lab = pages.createPageForTask(task.id(), null);
        check(lab.title().equals("Finish lab 3 part 1 2 cs"), "A page named after a task, reserved characters removed: " + lab.title());
        check(t.state().tasks().getFirst().pageIds().equals(List.of(lab.id())), "and linked to it in the same write");
        var notesPage = pages.createPage(null, "Lab notes", "");
        pages.linkTask(task.id(), notesPage.id());
        pages.linkTask(task.id(), notesPage.id());
        check(t.state().tasks().getFirst().pageIds().size() == 2, "Linking twice links once");
        check(pages.tasksLinkedTo(notesPage.id()).size() == 1, "A page lists the tasks linking to it");

        // Page links survive everything else done to tasks.
        t.taskStatus(task.id(), TaskStatus.DOING);
        check(t.state().tasks().getFirst().pageIds().size() == 2, "A status change keeps page links");
        t.reorderTasks(List.of(task.id()));
        check(t.state().tasks().getFirst().pageIds().size() == 2, "Reordering keeps page links");
        t.deleteTag(tag.id());
        check(t.state().tasks().getFirst().pageIds().size() == 2, "Deleting the task's tag keeps page links");
        t.reset(EnumSet.of(Tracker.ResetPart.ACTIVITIES));
        check(t.state().tasks().getFirst().pageIds().size() == 2, "Resetting activities keeps page links");

        // Trash keeps links; emptying the trash removes them, after a backup.
        pages.trash(List.of(notesPage.id()), List.of());
        check(t.state().tasks().getFirst().pageIds().contains(notesPage.id()), "A task keeps its link to a trashed page");
        rejects(() -> pages.purge(List.of(lab.id()), List.of()), "Only what is in the trash can be deleted for good");
        int backups = repo.backups;
        pages.emptyTrash();
        check(repo.backups == backups + 1, "Emptying the trash is backed up first");
        check(t.state().notes().page(notesPage.id()).isEmpty(), "The page is gone for good");
        check(t.state().tasks().getFirst().pageIds().equals(List.of(lab.id())), "and so is the task's link to it, the other kept");

        pages.unlinkTask(task.id(), lab.id());
        check(t.state().tasks().getFirst().pageIds().isEmpty(), "A task can be unlinked");

        t.reset(EnumSet.of(Tracker.ResetPart.PAGES));
        check(t.state().notes().pages().isEmpty() && t.state().tasks().size() == 1, "Resetting pages keeps tasks");
    }

    public static void main(String[] args) throws Exception {
        foldersAndPages();
        renamesKeepLinks();
        trashAndRestore();
        purgeAndTasks();
        System.out.println("PASS: " + checks + " Pages service checks (tree, links kept on rename and move, trash, task links)");
    }
}
