package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Atomic batches, recurrence and failure recovery use invented records only. */
public final class TaskBatchTest {
    private static int checks;
    private static final Instant NOW = Instant.parse("2026-09-23T00:30:00Z");
    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final class Memory implements Repository {
        State saved = State.empty();
        int writes, backups;
        boolean failSave, failBackup;
        public State load() { return saved; }
        public void save(State state) throws IOException {
            writes++;
            if (failSave) throw new IOException("Synthetic write failure");
            saved = state;
        }
        public void backup() throws IOException {
            backups++;
            if (failBackup) throw new IOException("Synthetic backup failure");
        }
        public void close() { }
    }
    private interface Work { void run() throws Exception; }
    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }
    private static void rejects(Work work, String reason) throws Exception {
        try { work.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError(reason);
    }
    private static Task task(Tracker tracker, UUID id) {
        return tracker.state().tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }
    private static Task sample(String title, int order) {
        return new Task(UUID.randomUUID(), null, List.of(), title, "Keep these notes", LocalDate.of(2026, 9, 20),
            TaskStatus.TODO, "Synthetic fixture", NOW.minusSeconds(600), order, LocalDate.of(2026, 9, 21), List.of());
    }
    public static void main(String[] args) throws Exception {
        var memory = new Memory();
        var tracker = new Tracker(memory, Clock.fixed(NOW, ZoneOffset.UTC));
        var first = sample("Read a chapter", 0);
        var second = sample("Outline a draft", 1);
        var other = sample("Hidden task", 2);
        tracker.addTasks(List.of(first, second, other));
        var ids = List.of(first.id(), second.id());
        int writes = memory.writes, backups = memory.backups;
        tracker.editTasks(ids, new TaskBatch.Status(TaskStatus.DOING), ZONE);
        check(memory.writes == writes + 1 && memory.backups == backups + 1, "A whole batch saves once after one backup");
        check(task(tracker, first.id()).equals(first.withStatus(TaskStatus.DOING)), "Status keeps every other property");
        check(task(tracker, other.id()).equals(other), "Unselected tasks stay byte-for-byte equivalent");
        var list = tracker.addList("Reading", 0x445566);
        tracker.editTasks(ids, new TaskBatch.Move(list.id()), ZONE);
        check(ids.stream().allMatch(id -> list.id().equals(task(tracker, id).listId())), "Both tasks move to the list");
        tracker.editTasks(ids, new TaskBatch.Move(null), ZONE);
        check(ids.stream().allMatch(id -> task(tracker, id).listId() == null), "Both tasks can return to Inbox");
        var deadline = LocalDate.of(2026, 10, 1);
        tracker.editTasks(ids, new TaskBatch.Due(deadline), ZONE);
        check(ids.stream().allMatch(id -> deadline.equals(task(tracker, id).due())), "The same due date reaches every selected task");
        check(task(tracker, first.id()).plannedFor().equals(first.plannedFor()), "Due changes keep the planned day");
        tracker.editTasks(ids, new TaskBatch.Planned(null), ZONE);
        check(ids.stream().allMatch(id -> task(tracker, id).plannedFor() == null), "Planned dates can be cleared");
        tracker.editTasks(ids, new TaskBatch.Planned(deadline.minusDays(1)), ZONE);
        check(task(tracker, second.id()).plannedFor().equals(deadline.minusDays(1)), "Planned dates can be set");
        tracker.editTasks(ids, new TaskBatch.Due(null), ZONE);
        check(ids.stream().allMatch(id -> task(tracker, id).due() == null), "Non-repeating deadlines can be cleared");

        var oldTag = tracker.addTag("Existing", 0x223344);
        tracker.updateTask(task(tracker, first.id()).withTags(List.of(oldTag.id())));
        var fresh = new Tag(UUID.randomUUID(), "Fresh", 0x556677);
        writes = memory.writes;
        tracker.editTasks(ids, new TaskBatch.Tags(TaskBatch.TagMode.ADD, List.of(fresh.id()), List.of(fresh)), ZONE);
        check(memory.writes == writes + 1 && tracker.state().tags().contains(fresh), "New tags and all tasks save together");
        check(task(tracker, first.id()).tagIds().equals(List.of(oldTag.id(), fresh.id())), "Add tags preserves existing tags and their order");
        check(task(tracker, second.id()).tagIds().equals(List.of(fresh.id())), "Tags also reach an untagged task");
        writes = memory.writes; backups = memory.backups;
        tracker.editTasks(ids, new TaskBatch.Tags(TaskBatch.TagMode.ADD, List.of(fresh.id()), List.of()), ZONE);
        check(memory.writes == writes && memory.backups == backups, "A no-op makes neither a backup nor a write");
        tracker.editTasks(ids, new TaskBatch.Tags(TaskBatch.TagMode.REMOVE, List.of(fresh.id()), List.of()), ZONE);
        check(task(tracker, first.id()).tagIds().equals(List.of(oldTag.id())), "Removing one tag keeps unrelated tags");
        tracker.editTasks(ids, new TaskBatch.Tags(TaskBatch.TagMode.REPLACE, List.of(), List.of()), ZONE);
        check(ids.stream().allMatch(id -> task(tracker, id).tagIds().isEmpty()), "Replace with none clears all selected tags");

        State stable = tracker.state();
        writes = memory.writes; backups = memory.backups;
        rejects(() -> tracker.editTasks(List.of(first.id(), UUID.randomUUID()), new TaskBatch.Status(TaskStatus.DONE), ZONE), "Stale selections are refused");
        rejects(() -> tracker.deleteTasks(List.of(first.id(), UUID.randomUUID())), "Stale deletes are refused");
        rejects(() -> tracker.editTasks(List.of(), new TaskBatch.Status(TaskStatus.DONE), ZONE), "An empty selection is refused");
        rejects(() -> tracker.editTasks(ids, new TaskBatch.Move(UUID.randomUUID()), ZONE), "A missing destination is refused");
        rejects(() -> tracker.editTasks(ids, new TaskBatch.Tags(TaskBatch.TagMode.REMOVE, List.of(UUID.randomUUID()), List.of()), ZONE), "Missing tags are refused even when removing");
        check(tracker.state().equals(stable) && memory.writes == writes && memory.backups == backups, "Invalid batches never touch storage or memory");

        memory.failSave = true;
        var failedTag = new Tag(UUID.randomUUID(), "Not saved", 0x8899AA);
        rejects(() -> tracker.editTasks(ids, new TaskBatch.Tags(TaskBatch.TagMode.ADD, List.of(failedTag.id()), List.of(failedTag)), ZONE), "A failed save is reported");
        check(tracker.state().equals(stable) && memory.saved.equals(stable), "A failed write changes neither tasks nor new tags");
        memory.failSave = false; memory.failBackup = true;
        writes = memory.writes;
        rejects(() -> tracker.editTasks(ids, new TaskBatch.Status(TaskStatus.DONE), ZONE), "Backup failures block edits");
        rejects(() -> tracker.deleteTasks(ids), "Backup failures block deletion");
        check(memory.writes == writes && tracker.state().equals(stable), "A failed backup never attempts the write");
        memory.failBackup = false;

        // One exhausted tag limit must refuse all tasks, even after an earlier valid row.
        var many = new ArrayList<UUID>();
        for (int i = 0; i < Task.MAX_TAGS; i++) many.add(tracker.addTag("Tag " + i, 0x112233).id());
        tracker.updateTask(task(tracker, second.id()).withTags(many));
        stable = tracker.state(); writes = memory.writes;
        rejects(() -> tracker.editTasks(ids, new TaskBatch.Tags(TaskBatch.TagMode.ADD, List.of(fresh.id()), List.of()), ZONE), "Overflow on the last task rejects the whole batch");
        check(tracker.state().equals(stable) && memory.writes == writes, "No earlier task is partly updated");

        // Completing after-done repeats uses the supplied zone and one instant for the batch.
        var repeat = new Repeat(RepeatUnit.DAY, 1, Set.of(), 0, 0, LocalDate.of(2026, 9, 20), true, null, 0);
        tracker.updateTask(task(tracker, second.id()).withDates(LocalDate.of(2026, 9, 20), null).withRepeat(repeat));
        stable = tracker.state(); writes = memory.writes;
        rejects(() -> tracker.editTasks(ids, new TaskBatch.Due(null), ZONE), "A batch cannot clear a repeating task's deadline");
        check(tracker.state().equals(stable) && memory.writes == writes, "A mixed batch of repeating and ordinary tasks is atomic");
        tracker.editTasks(List.of(first.id(), second.id(), second.id()), new TaskBatch.Status(TaskStatus.DONE), ZONE);
        check(task(tracker, first.id()).done(), "Ordinary tasks complete");
        var repeated = task(tracker, second.id());
        check(!repeated.done() && repeated.due().equals(LocalDate.of(2026, 9, 23)), "Repeats advance from the completion day in the selected zone");
        check(repeated.history().size() == 1 && repeated.history().getFirst().at().equals(NOW), "Duplicate selection IDs cannot complete an occurrence twice");
        check(repeated.plannedFor() == null && repeated.repeat().equals(repeat), "Completion keeps the rule and clears the prior plan");

        // Deletion saves once and leaves unrelated records untouched.
        var page = tracker.pages().createPage(null, "Linked note", "Keep me");
        tracker.updateTask(task(tracker, first.id()).withPages(List.of(page.id())));
        var beforeDelete = tracker.state();
        memory.failSave = true;
        rejects(() -> tracker.deleteTasks(ids), "A failed delete can be retried");
        check(tracker.state().equals(beforeDelete), "A failed delete publishes nothing");
        memory.failSave = false; writes = memory.writes; backups = memory.backups;
        tracker.deleteTasks(ids);
        check(memory.writes == writes + 1 && memory.backups == backups + 1, "A delete takes one backup and one write");
        check(tracker.state().tasks().equals(List.of(other)), "Only selected tasks are removed");
        check(tracker.state().notes().equals(beforeDelete.notes()) && tracker.state().tags().equals(beforeDelete.tags()), "Linked pages and tags survive task deletion");
        System.out.println("PASS: " + checks + " task batch checks (atomic edits, backup/write failures, tags, dates, recurrence, deletion)");
    }
}
