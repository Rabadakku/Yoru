package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Drives the real selection controls and the exact forms used by batch editing. */
public final class TaskBulkActionsTest {
    private static int checks;
    private static final class Memory implements Repository {
        State saved = State.empty();
        int writes;
        boolean fail;
        public State load() { return saved; }
        public void save(State state) throws IOException {
            if (fail) throw new IOException("Synthetic disk failure");
            saved = state; writes++;
        }
        public void backup() { }
        public void close() { }
    }
    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }
    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container inner) {
                var found = named(inner, name);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void click(Container root, String name) {
        var control = (AbstractButton) named(root, name);
        check(control != null && control.isEnabled(), "Reachable control: " + name);
        control.doClick();
    }
    private static Task task(Tracker tracker, UUID id) {
        return tracker.state().tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }
    private static Task sample(String title, int order) {
        return new Task(UUID.randomUUID(), null, null, title, "", LocalDate.of(2026, 9, 23),
            TaskStatus.TODO, "", Instant.parse("2026-09-20T09:00:00Z"), order);
    }
    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { run(); } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            System.out.println("PASS: " + checks + " task bulk UI checks (selection, filters, keyboard controls, forms, failure recovery)");
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
    private static void run() throws Exception {
        Theme.install();
        var memory = new Memory();
        var tracker = new Tracker(memory, Clock.fixed(Instant.parse("2026-09-23T09:00:00Z"), ZoneOffset.UTC));
        var first = sample("Read one chapter", 0);
        var second = sample("Write an outline", 1);
        var third = sample("Review notes", 2);
        tracker.addTasks(List.of(first, second, third));
        var list = tracker.addList("Reading", 0x446688);
        var board = new TasksPanel(tracker, () -> { }, () -> false);
        var bulk = (TaskBulkActions) named(board, "tasks.bulk");
        check(!bulk.isVisible(), "Batch controls stay hidden until selection is requested");
        click(board, "tasks.select");
        check(bulk.isVisible(), "Selection makes the bulk controls visible");
        check(!((JButton) named(board, "tasks.bulk.delete")).isEnabled(), "Deleting needs a nonempty selection");
        var checkbox = (JCheckBox) named(board, "task.select." + first.id());
        check(checkbox.isFocusable() && checkbox.getInputMap().get(KeyStroke.getKeyStroke("pressed SPACE")) != null,
            "Selection is a keyboard-operable checkbox");
        check(checkbox.getAccessibleContext().getAccessibleName().contains(first.title()), "A selection checkbox names its task");
        click(board, "task.select." + first.id());
        click(board, "task.select." + second.id());
        check(bulk.selection().equals(Set.of(first.id(), second.id())), "The selection matches the checked tasks");
        check(task(tracker, first.id()).status() == TaskStatus.TODO, "Selecting is not completing");
        click(bulk.menu(), "tasks.bulk.status.DOING");
        check(task(tracker, first.id()).status() == TaskStatus.DOING && task(tracker, second.id()).status() == TaskStatus.DOING,
            "The batch status action edits both selected tasks");
        check(task(tracker, third.id()).status() == TaskStatus.TODO, "Unselected tasks stay unchanged");
        check(!bulk.active() && bulk.selection().isEmpty(), "Success exits selection to avoid repeating a batch by mistake");

        click(board, "tasks.select");
        click(board, "tasks.bulk.all");
        var search = (JTextField) named(board, "task.search");
        search.setText("Read");
        check(bulk.selection().equals(Set.of(first.id())), "Filtering removes hidden tasks from the selection");
        click(board, "tasks.bulk.clear");
        click(board, "tasks.bulk.all");
        check(bulk.selection().equals(Set.of(first.id())), "Select shown respects search");
        search.setText("");
        check(bulk.selection().equals(Set.of(first.id())), "Clearing a filter does not silently select more tasks");
        click(board, "tasks.place." + list.id());
        check(!bulk.active() && bulk.selection().isEmpty(), "Switching lists clears the previous selection");
        click(board, "tasks.place.all");
        click(board, "tasks.select");
        click(board, "tasks.bulk.all");
        click(board, "view.calendar");
        check(!bulk.active() && !((JButton) named(board, "tasks.select")).isEnabled(), "Calendar never acts on hidden table selection");
        click(board, "view.all");
        click(board.rowMenu(first.id()), "task.selectMenu." + first.id());
        check(bulk.active() && bulk.selected(first.id()), "The row menu also enters selection");
        click(board, "tasks.bulk.all");
        var move = new TaskBulkActions.MoveForm(tracker.state());
        move.lists.setSelectedItem(list);
        check(bulk.apply(move.change()), "The same destination form applies a bulk move");
        check(tracker.state().tasks().stream().allMatch(t -> list.id().equals(t.listId())), "Every selected task moves");

        click(board, "tasks.select");
        click(board, "tasks.bulk.all");
        var date = new TaskBulkActions.DateForm(false);
        date.date.set(LocalDate.of(2026, 10, 2));
        check(bulk.apply(date.change()), "The due-date form applies");
        check(tracker.state().tasks().stream().allMatch(t -> t.due().equals(LocalDate.of(2026, 10, 2))), "The selected deadline is used");
        click(board, "tasks.select");
        click(board, "tasks.bulk.all");
        var plan = new TaskBulkActions.DateForm(true);
        plan.date.set(LocalDate.of(2026, 10, 1));
        check(bulk.apply(plan.change()), "The planned-day form applies");
        check(tracker.state().tasks().stream().allMatch(t -> t.plannedFor().equals(LocalDate.of(2026, 10, 1))), "The selected planned day is used");
        click(board, "tasks.select");
        click(board, "tasks.bulk.all");
        var tags = new TaskBulkActions.TagsForm(tracker.state());
        tags.tags.input().setText("Reading");
        tags.tags.input().getActionMap().get("tags.take").actionPerformed(null);
        check(bulk.apply(tags.change()), "The tag form creates and applies a tag atomically");
        check(tracker.state().tasks().stream().allMatch(t -> t.tagIds().size() == 1), "Every selected task carries the tag");
        var remove = new TaskBulkActions.TagsForm(tracker.state());
        remove.mode.setSelectedItem(TaskBatch.TagMode.REPLACE);
        check(((TaskBatch.Tags) remove.change()).ids().isEmpty(), "Replace with an empty field explicitly clears tags");

        // Error UI is injected to test the real failure path without opening a modal window.
        var errors = new ArrayList<Exception>();
        var isolated = new TaskBulkActions(tracker, () -> { }, errors::add, () -> false);
        isolated.update(tracker.state().tasks(), true);
        isolated.toggle(first.id());
        memory.fail = true;
        check(!isolated.apply(new TaskBatch.Status(TaskStatus.DONE)), "Write failure is reported by the batch controls");
        check(errors.size() == 1 && isolated.selected(first.id()) && isolated.active(), "Failure keeps the selection for retry");
        memory.fail = false;
        check(isolated.apply(new TaskBatch.Status(TaskStatus.DONE)), "Retry succeeds with the same selected task");
        check(!isolated.active(), "Retry success also exits selection");
        click(board, "tasks.select");
        click(board, "tasks.bulk.all");
        board.getActionMap().get("tasks.cancelSelection").actionPerformed(null);
        check(!bulk.active() && bulk.selection().isEmpty(), "Escape cancels selection without changing tasks");
        var locked = new TaskBulkActions(tracker, () -> { }, errors::add, () -> true);
        locked.toggle(second.id());
        int writes = memory.writes;
        check(!locked.apply(new TaskBatch.Status(TaskStatus.DONE)) && memory.writes == writes, "A closed vault blocks batch actions");
    }
}
