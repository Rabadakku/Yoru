package dev.yoru.application;

import dev.yoru.domain.Model.*;
import static dev.yoru.domain.Model.requirePageName;
import dev.yoru.pages.Links;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Every change to pages and folders, each one a single whole-vault write.
 *
 * Renames, moves and new pages rewrite links in the same write, so no link
 * ever changes which page it means (Links.keepMeaning): the vault is never
 * saved with links half updated. Deleting moves things to the trash, which
 * needs no backup because nothing is lost; emptying the trash is the one
 * permanent step and is backed up first, like every other deletion.
 */
public final class Pages {
    private final Tracker tracker;

    Pages(Tracker tracker) { this.tracker = tracker; }

    private State state() { return tracker.state(); }
    private Notes notes() { return state().notes(); }
    private Instant now() { return tracker.now(); }

    // ---------------------------------------------------------------- reading

    public Page page(UUID id) {
        return notes().page(id).orElseThrow(() -> new IllegalArgumentException("That page no longer exists."));
    }

    public Folder folder(UUID id) {
        return notes().folder(id).orElseThrow(() -> new IllegalArgumentException("That folder no longer exists."));
    }

    /**
     * A title not yet used in this folder: the base itself, or "base 1",
     * "base 2" and so on, as Obsidian names a second "Untitled".
     */
    public String freeTitle(UUID folderId, String base) {
        var taken = new HashSet<String>();
        for (var p : notes().pages()) if (!p.trashed() && Objects.equals(p.folderId(), folderId)) taken.add(p.title().toLowerCase(Locale.ROOT));
        String name = requirePageName(base, "page title");
        for (int n = 1; taken.contains(name.toLowerCase(Locale.ROOT)); n++) name = base + " " + n;
        return name;
    }

    /** The same for a folder name. */
    public String freeFolderName(UUID parentId, String base) {
        var taken = new HashSet<String>();
        for (var f : notes().folders()) if (!f.trashed() && Objects.equals(f.parentId(), parentId)) taken.add(f.name().toLowerCase(Locale.ROOT));
        String name = requirePageName(base, "folder name");
        for (int n = 1; taken.contains(name.toLowerCase(Locale.ROOT)); n++) name = base + " " + n;
        return name;
    }

    /** Import a complete Markdown tree into a new folder, in one atomic vault save. */
    public void importNotes(Notes imported) throws IOException {
        UUID root = UUID.randomUUID();
        var folders = new ArrayList<>(notes().folders());
        folders.add(new Folder(root, null, freeFolderName(null, "Imported notes"), now(), null));
        for (var f : imported.folders()) folders.add(new Folder(f.id(), f.parentId() == null ? root : f.parentId(), f.name(), f.createdAt(), null));
        var pages = new ArrayList<>(notes().pages());
        for (var p : imported.pages()) pages.add(p.withFolder(p.folderId() == null ? root : p.folderId(), now()));
        var next = new Notes(folders, pages);
        var rewrites = Links.keepMeaning(imported, next);
        if (!rewrites.isEmpty()) next = new Notes(folders, pages.stream().map(p -> rewrites.containsKey(p.id()) ? p.withBody(rewrites.get(p.id()), now()) : p).toList());
        write(next, true);
    }

    // ---------------------------------------------------------------- folders

    public Folder createFolder(UUID parentId, String name) throws IOException {
        if (parentId != null && folder(parentId).trashed()) throw new IllegalArgumentException("That folder is in the trash.");
        var folder = new Folder(UUID.randomUUID(), parentId, name, now(), null);
        var folders = new ArrayList<>(notes().folders());
        folders.add(folder);
        write(new Notes(folders, notes().pages()), true);
        return folder;
    }

    public void renameFolder(UUID id, String name) throws IOException {
        var old = folder(id);
        if (old.name().equals(requirePageName(name, "folder name"))) return;
        write(replaceFolder(new Folder(old.id(), old.parentId(), name, old.createdAt(), old.deletedAt())), true);
    }

    public void moveFolder(UUID id, UUID parentId) throws IOException {
        var old = folder(id);
        if (Objects.equals(old.parentId(), parentId)) return;
        if (parentId != null) {
            for (var at = folder(parentId); at != null; at = at.parentId() == null ? null : folder(at.parentId()))
                if (at.id().equals(id)) throw new IllegalArgumentException("A folder cannot move inside itself.");
            if (folder(parentId).trashed()) throw new IllegalArgumentException("That folder is in the trash.");
        }
        write(replaceFolder(new Folder(old.id(), parentId, old.name(), old.createdAt(), old.deletedAt())), true);
    }

    private Notes replaceFolder(Folder next) {
        return new Notes(notes().folders().stream().map(f -> f.id().equals(next.id()) ? next : f).toList(), notes().pages());
    }

    // ---------------------------------------------------------------- pages

    public Page createPage(UUID folderId, String title, String body) throws IOException {
        if (folderId != null && folder(folderId).trashed()) throw new IllegalArgumentException("That folder is in the trash.");
        var when = now();
        var page = new Page(UUID.randomUUID(), folderId, title, body, when, when, null);
        var pages = new ArrayList<>(notes().pages());
        pages.add(page);
        write(new Notes(notes().folders(), pages), true);
        return page(page.id());
    }

    /**
     * Saves what the editor holds. Nothing is written when the text is what the
     * vault already has, so an idle editor costs nothing; no backup is taken,
     * because typing is not a deletion.
     */
    public void updateBody(UUID id, String body) throws IOException {
        var old = page(id);
        if (old.body().equals(body)) return;
        if (old.trashed()) throw new IllegalArgumentException("That page is in the trash.");
        write(replacePage(old.withBody(body, now())), false);
    }

    public void renamePage(UUID id, String title) throws IOException {
        var old = page(id);
        String next = requirePageName(title, "page title");
        if (old.title().equals(next)) return;
        write(replacePage(old.withTitle(next, now())), true);
    }

    public void movePage(UUID id, UUID folderId) throws IOException {
        move(List.of(id), List.of(), folderId);
    }

    /** Moves pages and folders together, as a drag of a mixed selection does, in one write. */
    public void move(Collection<UUID> pageIds, Collection<UUID> folderIds, UUID target) throws IOException {
        if (target != null && folder(target).trashed()) throw new IllegalArgumentException("That folder is in the trash.");
        var folders = new ArrayList<>(notes().folders());
        for (var id : folderIds) {
            if (target != null) for (var at = folder(target); at != null; at = at.parentId() == null ? null : folder(at.parentId()))
                if (at.id().equals(id)) throw new IllegalArgumentException("A folder cannot move inside itself.");
            var old = folder(id);
            folders.replaceAll(f -> f.id().equals(id) ? new Folder(f.id(), target, f.name(), f.createdAt(), f.deletedAt()) : f);
            if (old.trashed()) throw new IllegalArgumentException("Restore it from the trash first.");
        }
        var when = now();
        var moving = new HashSet<>(pageIds);
        var pages = notes().pages().stream().map(p -> moving.contains(p.id()) ? p.withFolder(target, when) : p).toList();
        for (var id : pageIds) page(id);
        write(new Notes(folders, pages), true);
    }

    private Notes replacePage(Page next) {
        return new Notes(notes().folders(), notes().pages().stream().map(p -> p.id().equals(next.id()) ? next : p).toList());
    }

    // ---------------------------------------------------------------- trash

    /**
     * Moves pages and folders to the trash; a folder takes everything under it.
     * Everything trashed together shares one moment, which is how restoring the
     * folder later brings back exactly what went with it.
     */
    public void trash(Collection<UUID> pageIds, Collection<UUID> folderIds) throws IOException {
        var when = now();
        var trashedFolders = new HashSet<UUID>();
        for (var id : folderIds) { folder(id); trashedFolders.addAll(subtree(id)); }
        var folders = notes().folders().stream()
            .map(f -> trashedFolders.contains(f.id()) && !f.trashed() ? new Folder(f.id(), f.parentId(), f.name(), f.createdAt(), when) : f)
            .toList();
        var pagesToTrash = new HashSet<>(pageIds);
        for (var id : pageIds) page(id);
        var pages = notes().pages().stream()
            .map(p -> !p.trashed() && (pagesToTrash.contains(p.id()) || p.folderId() != null && trashedFolders.contains(p.folderId()))
                ? p.withDeletedAt(when) : p)
            .toList();
        write(new Notes(folders, pages), false);
    }

    /** Brings a page back, and any trashed folders above it, under a free name if its own is taken. */
    public Page restorePage(UUID id) throws IOException {
        var page = page(id);
        if (!page.trashed()) return page;
        var folders = restoreAncestors(notes().folders(), page.folderId());
        var restored = new Page(page.id(), page.folderId(), page.title(), page.body(), page.createdAt(), page.updatedAt(), null);
        var pages = new ArrayList<>(notes().pages().stream().map(p -> p.id().equals(id) ? restored : p).toList());
        String title = freeTitleAmong(pages, restored);
        if (!title.equals(restored.title())) pages.replaceAll(p -> p.id().equals(id) ? p.withTitle(title, p.updatedAt()) : p);
        write(new Notes(folders, pages), false);
        return page(id);
    }

    /** Brings a folder back with everything that was trashed along with it. */
    public void restoreFolder(UUID id) throws IOException {
        var folder = folder(id);
        if (!folder.trashed()) return;
        var when = folder.deletedAt();
        var subtree = subtree(id);
        var folders = new ArrayList<>(restoreAncestors(notes().folders(), folder.parentId()).stream()
            .map(f -> subtree.contains(f.id()) && when.equals(f.deletedAt()) ? new Folder(f.id(), f.parentId(), f.name(), f.createdAt(), null) : f)
            .toList());
        var pages = notes().pages().stream()
            .map(p -> p.folderId() != null && subtree.contains(p.folderId()) && when.equals(p.deletedAt())
                ? new Page(p.id(), p.folderId(), p.title(), p.body(), p.createdAt(), p.updatedAt(), null) : p)
            .toList();
        // Its name may have been taken while it was away.
        var restored = folders.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
        var taken = new HashSet<String>();
        for (var f : folders) if (!f.trashed() && !f.id().equals(id) && Objects.equals(f.parentId(), restored.parentId()))
            taken.add(f.name().toLowerCase(Locale.ROOT));
        String name = restored.name();
        for (int n = 1; taken.contains(name.toLowerCase(Locale.ROOT)); n++) name = restored.name() + " " + n;
        if (!name.equals(restored.name())) {
            String free = name;
            folders.replaceAll(f -> f.id().equals(id) ? new Folder(f.id(), f.parentId(), free, f.createdAt(), null) : f);
        }
        write(new Notes(folders, pages), false);
    }

    /**
     * Deletes trashed pages and folders for good, and unlinks tasks from the
     * pages. Only what is already in the trash can go, and the vault is backed
     * up first.
     */
    public void purge(Collection<UUID> pageIds, Collection<UUID> folderIds) throws IOException {
        var goneFolders = new HashSet<UUID>();
        for (var id : folderIds) {
            if (!folder(id).trashed()) throw new IllegalArgumentException("Move it to the trash first.");
            goneFolders.addAll(subtree(id));
        }
        var gonePages = new HashSet<UUID>();
        for (var id : pageIds) {
            if (!page(id).trashed()) throw new IllegalArgumentException("Move it to the trash first.");
            gonePages.add(id);
        }
        for (var p : notes().pages()) if (p.folderId() != null && goneFolders.contains(p.folderId())) {
            // A live page never sits in a trashed folder, so this only ever
            // takes pages that went to the trash with it.
            gonePages.add(p.id());
        }
        var notes = new Notes(notes().folders().stream().filter(f -> !goneFolders.contains(f.id())).toList(),
            notes().pages().stream().filter(p -> !gonePages.contains(p.id())).toList());
        var tasks = state().tasks().stream().map(t -> t.pageIds().stream().anyMatch(gonePages::contains)
            ? t.withPages(t.pageIds().stream().filter(p -> !gonePages.contains(p)).toList()) : t).toList();
        tracker.backup();
        tracker.commit(state().withTasks(tasks).withNotes(notes));
    }

    public void emptyTrash() throws IOException {
        var folders = notes().folders().stream().filter(f -> f.trashed()
            && (f.parentId() == null || !folder(f.parentId()).trashed())).map(Folder::id).toList();
        var pages = notes().pages().stream().filter(Page::trashed).map(Page::id).toList();
        if (folders.isEmpty() && pages.isEmpty()) return;
        purge(pages, folders);
    }

    // ---------------------------------------------------------------- tasks

    /** Links a task to a page; linking it again changes nothing. */
    public void linkTask(UUID taskId, UUID pageId) throws IOException {
        var task = task(taskId);
        page(pageId);
        if (task.pageIds().contains(pageId)) return;
        var next = new ArrayList<>(task.pageIds());
        next.add(pageId);
        replaceTask(task.withPages(next));
    }

    public void unlinkTask(UUID taskId, UUID pageId) throws IOException {
        var task = task(taskId);
        if (!task.pageIds().contains(pageId)) return;
        replaceTask(task.withPages(task.pageIds().stream().filter(p -> !p.equals(pageId)).toList()));
    }

    /** The tasks that link to a page, in the order the tasks are kept. */
    public List<Task> tasksLinkedTo(UUID pageId) {
        return state().tasks().stream().filter(t -> t.pageIds().contains(pageId)).toList();
    }

    /** A new page named after a task, linked to it, in one write. */
    public Page createPageForTask(UUID taskId, UUID folderId) throws IOException {
        var task = task(taskId);
        if (folderId != null && folder(folderId).trashed()) throw new IllegalArgumentException("That folder is in the trash.");
        var when = now();
        var page = new Page(UUID.randomUUID(), folderId, freeTitle(folderId, titleFrom(task.title())), "", when, when, null);
        var pages = new ArrayList<>(notes().pages());
        pages.add(page);
        var linked = new ArrayList<>(task.pageIds());
        linked.add(page.id());
        var tasks = state().tasks().stream().map(t -> t.id().equals(taskId) ? t.withPages(linked) : t).toList();
        tracker.commit(state().withNotes(new Notes(notes().folders(), pages)).withTasks(tasks));
        return page(page.id());
    }

    /** A task title made fit to be a page title: the reserved characters become spaces. */
    static String titleFrom(String text) {
        var b = new StringBuilder();
        for (char c : text.toCharArray()) b.append(Character.isISOControl(c) || "/\\:#^[]|".indexOf(c) >= 0 ? ' ' : c);
        String t = b.toString().replaceAll("\\s+", " ").strip().replaceFirst("^\\.+", "").strip();
        if (t.length() > 200) t = t.substring(0, 200).strip();
        return t.isEmpty() ? "Untitled" : t;
    }

    private Task task(UUID id) {
        return state().tasks().stream().filter(t -> t.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("That task no longer exists."));
    }

    private void replaceTask(Task next) throws IOException {
        tracker.commit(state().withTasks(state().tasks().stream().map(t -> t.id().equals(next.id()) ? next : t).toList()));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * One write of a new tree. When keepLinks, every link that would change
     * which page it means is rewritten in the same write.
     */
    private void write(Notes next, boolean keepLinks) throws IOException {
        if (keepLinks) {
            var rewritten = Links.keepMeaning(notes(), next);
            if (!rewritten.isEmpty()) {
                var when = now();
                next = new Notes(next.folders(), next.pages().stream()
                    .map(p -> rewritten.containsKey(p.id()) ? p.withBody(rewritten.get(p.id()), when) : p).toList());
            }
        }
        tracker.commit(state().withNotes(next));
    }

    /** A folder and every folder under it. */
    private Set<UUID> subtree(UUID id) {
        var out = new HashSet<UUID>();
        out.add(id);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var f : notes().folders()) if (f.parentId() != null && out.contains(f.parentId()) && out.add(f.id())) grew = true;
        }
        return out;
    }

    private List<Folder> restoreAncestors(List<Folder> folders, UUID from) {
        var byId = new HashMap<UUID, Folder>();
        for (var f : folders) byId.put(f.id(), f);
        var restore = new HashSet<UUID>();
        for (var at = from == null ? null : byId.get(from); at != null; at = at.parentId() == null ? null : byId.get(at.parentId()))
            if (at.trashed()) restore.add(at.id());
        return folders.stream().map(f -> restore.contains(f.id()) ? new Folder(f.id(), f.parentId(), f.name(), f.createdAt(), null) : f).toList();
    }

    private static String freeTitleAmong(List<Page> pages, Page page) {
        var taken = new HashSet<String>();
        for (var p : pages) if (!p.trashed() && !p.id().equals(page.id()) && Objects.equals(p.folderId(), page.folderId()))
            taken.add(p.title().toLowerCase(Locale.ROOT));
        String name = page.title();
        for (int n = 1; taken.contains(name.toLowerCase(Locale.ROOT)); n++) name = page.title() + " " + n;
        return name;
    }
}
