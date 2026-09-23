package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * Task lists (#56): the places tasks are filed in, what the tracker lets you
 * do with them, and the board the rail puts on screen.
 */
public final class TaskListsTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String why) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError(why);
    }

    private static final class Memory implements Repository {
        State state = State.empty();
        int backups;
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void backup() { backups++; }
        public void close() { }
    }

    private static Component named(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static void shown(Container root, List<String> into) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton b && b.getName() != null && b.getName().startsWith("task.status."))
                into.add(b.getName().substring("task.status.".length()));
            if (child instanceof Container nested) shown(nested, into);
        }
    }
    private static List<String> shown(Container root) { var ids = new ArrayList<String>(); shown(root, ids); return ids; }
    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static Task task(Tracker tracker, UUID id) {
        return tracker.state().tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }
    private static JMenuItem item(TasksPanel board, String name) {
        for (var id : shown(board)) {
            var menu = board.rowMenu(UUID.fromString(id));
            if (menu == null) continue;
            for (Component child : menu.getComponents())
                if (child instanceof JMenuItem entry && name.equals(entry.getName())) return entry;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { run(); } catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            var cause = wrapped.getCause();
            if (cause instanceof RuntimeException r && r.getCause() instanceof Exception inner) throw inner;
            if (cause instanceof Error error) throw error;
            throw wrapped;
        }
        System.out.println("PASS: " + checks + " task list checks (create, rename, recolour, reorder, delete, move, the rail, per-list views)");
    }

    private static void run() throws Exception {
        Theme.apply(ThemeId.MIDNIGHT);
        var memory = new Memory();
        var tracker = new Tracker(memory, Clock.systemUTC());
        var now = Instant.parse("2026-09-20T09:00:00Z");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        tracker.addTasks(List.of(
            new Task(a, null, null, "Wash dishes", "", null, TaskStatus.TODO, "", now, 0),
            new Task(b, null, null, "Read chapter 4", "", null, TaskStatus.TODO, "", now, 1),
            new Task(c, null, null, "Vacuum", "", null, TaskStatus.TODO, "", now, 2),
            new Task(d, null, null, "Essay draft", "", null, TaskStatus.DONE, "", now, 3)));

        // ---- the tracker
        var chores = tracker.addList("Chores", 0xE8B24C);
        var school = tracker.addList("School", 0x6E8FD6);
        check(tracker.state().lists().size() == 2 && school.order() == 1, "Lists are made in order, each at the end");
        rejects(() -> tracker.addList("chores", 0x123456), "A second list with the same name, in any case, is refused");
        rejects(() -> tracker.addList(" ", 0x123456), "A list needs a name");
        rejects(() -> tracker.addList("x".repeat(41), 0x123456), "and one of at most 40 characters");
        tracker.editList(school.id(), "Classes", 0x6FBF8B);
        var renamed = tracker.state().lists().stream().filter(l -> l.id().equals(school.id())).findFirst().orElseThrow();
        check(renamed.name().equals("Classes") && renamed.colour() == 0x6FBF8B && renamed.order() == 1,
            "Renaming and recolouring keep the list's place");
        rejects(() -> tracker.editList(school.id(), "CHORES", 0), "Renaming onto another list's name is refused");
        check(tracker.state().tasks().stream().allMatch(t -> t.listId() == null), "Every task starts in the Inbox");
        tracker.moveTask(a, chores.id());
        tracker.moveTask(c, chores.id());
        tracker.moveTask(b, school.id());
        check(task(tracker, a).listId().equals(chores.id()) && task(tracker, b).listId().equals(school.id()),
            "A task can be filed in a list");
        check(task(tracker, a).title().equals("Wash dishes") && task(tracker, a).order() == 0,
            "and filing it changes nothing else about it");
        rejects(() -> tracker.moveTask(a, UUID.randomUUID()), "A task cannot be filed in a list that does not exist");
        tracker.reorderLists(List.of(school.id(), chores.id()));
        check(TaskLists.ordered(tracker.state()).getFirst().id().equals(school.id()), "Lists can be reordered");
        rejects(() -> tracker.reorderLists(List.of(school.id())), "A reorder that leaves a list out is refused");

        // A tag becomes a list holding every task with the tag.
        var urgent = tracker.addTag("Errands", 0xD9736A);
        tracker.updateTask(task(tracker, d).withTags(List.of(urgent.id())));
        var errands = tracker.tagToList(urgent.id());
        check(errands.name().equals("Errands") && errands.colour() == 0xD9736A, "A tag's list takes its name and colour");
        check(errands.id().equals(task(tracker, d).listId()), "and holds the tasks that carried the tag");
        check(task(tracker, d).tagIds().equals(List.of(urgent.id())), "which keep the tag");
        rejects(() -> tracker.tagToList(urgent.id()), "A tag cannot become a list twice under the same name");

        // Deleting a list: to the Inbox, or with its tasks, a backup first either way.
        int before = memory.backups;
        tracker.deleteList(errands.id(), false);
        check(tracker.state().lists().stream().noneMatch(l -> l.id().equals(errands.id())), "A list can be deleted");
        check(task(tracker, d).listId() == null, "and its tasks move to the Inbox");
        check(memory.backups == before + 1, "after a backup");
        var scratch = tracker.addList("Scratch", 0xC9C273);
        var gone = UUID.randomUUID();
        tracker.addTask(new Task(gone, null, null, "Throwaway", "", null, TaskStatus.TODO, "", now, 9).withList(scratch.id()));
        tracker.deleteList(scratch.id(), true);
        check(tracker.state().tasks().stream().noneMatch(t -> t.id().equals(gone)), "or they go with it when asked");
        check(tracker.state().tasks().size() == 4, "and no other task goes");

        // The vault holds a task in a list that exists, nothing else.
        rejects(() -> new State(List.of(), List.of(), List.of(), List.of(),
            List.of(new Task(UUID.randomUUID(), null, List.of(), "Orphan", "", null, TaskStatus.TODO, "", now, 0,
                null, List.of(), UUID.randomUUID())), List.of(), List.of(), Settings.defaults(), Notes.empty(),
            Anki.off(), List.of()), "A task in a list that does not exist is not a valid vault");

        // ---- the board
        var state = new TasksPanel.ViewState();
        var board = new TasksPanel(tracker, () -> { }, () -> false, state);
        board.setSize(1280, 900);
        layout(board);
        check(named(board, "tasks.place." + TaskLists.ALL) != null && named(board, "tasks.place." + TaskLists.INBOX) != null,
            "The rail has All tasks and the Inbox");
        check(named(board, "tasks.place." + school.id()) != null && named(board, "tasks.place." + chores.id()) != null,
            "and every list");
        check(shown(board).size() == 4, "All tasks shows every task");
        ((JButton) named(board, "tasks.place." + chores.id())).doClick();
        check(new HashSet<>(shown(board)).equals(Set.of(a.toString(), c.toString())), "A list shows only its own tasks");
        ((JButton) named(board, "tasks.place." + TaskLists.INBOX)).doClick();
        check(shown(board).equals(List.of(d.toString())), "The Inbox shows the tasks filed nowhere");

        check(named(board, "tasks.inbox.help").isVisible(), "Inbox explains where unfiled tasks live");
        var explainedInbox = (JButton) named(board, "tasks.place." + TaskLists.INBOX);
        check(explainedInbox.getToolTipText().contains("Tasks without a list"), "Inbox explains itself before it is opened");
        check(explainedInbox.getAccessibleContext().getAccessibleDescription().contains("Move to"), "The Inbox explanation reaches screen readers");

        // Each place keeps its own view.
        ((JButton) named(board, "tasks.place." + chores.id())).doClick();
        ((JButton) named(board, "view.done")).doClick();
        check(shown(board).isEmpty(), "Completed in Chores is empty");
        check(!named(board, "tasks.inbox.help").isVisible(), "Named lists do not show Inbox guidance");
        ((JButton) named(board, "tasks.place." + TaskLists.ALL)).doClick();
        check(shown(board).size() == 4, "All tasks is still on its own view");
        ((JButton) named(board, "tasks.place." + chores.id())).doClick();
        check(shown(board).isEmpty() && String.valueOf(state.view).equals("DONE"), "and Chores comes back on Completed");
        ((JButton) named(board, "view.all")).doClick();

        // Reordering inside a list moves only its own tasks, among the places they hold.
        var reordered = board.everywhere(List.of(c, a));
        check(reordered.equals(List.of(c, b, a, d)), "A list's reorder swaps its tasks among their own places: " + reordered);
        var down = item(board, "task.down." + a);
        check(down != null && down.isEnabled(), "Move down works inside a list");
        down.doClick();
        var order = tracker.state().tasks().stream().sorted(Comparator.comparingInt(Task::order)).map(Task::id).toList();
        check(order.equals(List.of(c, b, a, d)), "and leaves the tasks of other lists where they were: " + order);

        // Move to, from the row's menu.
        var move = item(board, "task.moveTo." + school.id() + "." + c);
        check(move != null && move.isEnabled(), "A row's menu can file it in another list");
        check(!item(board, "task.moveTo." + chores.id() + "." + c).isEnabled(), "but not in the one it is in");
        move.doClick();
        check(school.id().equals(task(tracker, c).listId()), "Move to files the task there");
        check(shown(board).equals(List.of(a.toString())), "and it leaves the list on screen");

        // Dragging a row onto a list on the rail files it there.
        layout(board);
        var inbox = (JButton) named(board, "tasks.place." + TaskLists.INBOX);
        var row = named(board, "task.title." + a);
        var target = javax.swing.SwingUtilities.convertPoint(inbox, inbox.getWidth() / 2, inbox.getHeight() / 2, row);
        row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false));
        row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_DRAGGED, 0, 0, target.x, target.y, 0, false));
        check(Boolean.TRUE.equals(((JButton) named(board, "tasks.place." + TaskLists.INBOX)).getClientProperty(TaskLists.DROP)),
            "The list under a dragged task is ringed");
        row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_RELEASED, 0, 0, target.x, target.y, 1, false));
        check(task(tracker, a).listId() == null, "and dropping the task there files it in that list");

        // A new task goes into the list on screen.
        ((JButton) named(board, "tasks.place." + school.id())).doClick();
        var made = TasksPanel.merged(null, null, List.of(), "Lab report", "", null, TaskStatus.TODO, 10, null,
            TaskLists.listOf(state.scope));
        check(school.id().equals(made.listId()), "A task made in a list is filed in that list");
        var edited = TasksPanel.merged(task(tracker, b), null, List.of(), "Read chapter 5", "", null, TaskStatus.TODO, 0, null, chores.id());
        check(school.id().equals(edited.listId()), "and editing a task never moves it");

        // Daily habits are a place of their own.
        tracker.addHabit("Stretch", HabitKind.DAILY, ZoneId.of("UTC"), null);
        board = new TasksPanel(tracker, () -> { }, () -> false, state);
        board.setSize(1280, 900);
        layout(board);
        var habits = (JButton) named(board, "tasks.place." + TaskLists.HABITS);
        check(habits != null, "Daily habits have a place on the rail once there is one");
        habits.doClick();
        check(shown(board).isEmpty(), "Choosing it shows no tasks");
        check(named(board, "habit.today." + tracker.state().habits().getFirst().id()) != null, "but today's habits to tick off");

        // A list deleted from under the page sends the page back to All.
        state.enter(chores.id().toString());
        tracker.deleteList(chores.id(), false);
        board = new TasksPanel(tracker, () -> { }, () -> false, state);
        check(state.scope.equals(TaskLists.ALL), "A deleted list's page falls back to All tasks");

        // Reset clears lists and keeps their tasks.
        tracker.reset(EnumSet.of(Tracker.ResetPart.LISTS));
        check(tracker.state().lists().isEmpty() && tracker.state().tasks().size() == 4
            && tracker.state().tasks().stream().allMatch(t -> t.listId() == null), "Resetting lists files every task in the Inbox");
    }
}
