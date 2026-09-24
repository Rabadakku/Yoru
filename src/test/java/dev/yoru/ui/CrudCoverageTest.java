package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.VaultStore;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * #59's table as a test: every kind of record Yoru keeps can be created,
 * viewed, edited and deleted, by the tracker and from the interface.
 *
 * Each row does two things. It drives the record through the tracker from
 * creation to deletion on an invented vault, checking that a delete which
 * loses data takes a backup first and that a failed save changes nothing. Then
 * it opens the page that owns the record in the real window and finds, by
 * name, the controls a person would use for each of the four — enabled,
 * reachable from the keyboard, and named for a screen reader. A record whose
 * only way to be changed is the vault file fails here.
 *
 * A new kind of record gets a row in {@link #ROWS} with the feature that adds it.
 */
public final class CrudCoverageTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private interface Action { void run() throws Exception; }
    private static void refuses(Action action, String why) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError("Expected a refusal: " + why);
    }

    /** A vault in memory that can refuse to save, and counts its backups. */
    static final class Memory implements Repository {
        State state = State.empty();
        boolean fail;
        int backups;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("Synthetic save failure"); state = next; }
        public void backup() { backups++; }
        public void close() { }
    }

    /**
     * The rows of #59's table, in its order, with the columns each must fill:
     * Create, View, Edit, Delete. Settings are never created, only changed and
     * reset, as the table says.
     */
    static final Map<String, String> ROWS = new LinkedHashMap<>();
    static {
        for (var row : List.of("Activities", "Sessions", "Schedule blocks", "Weekly repeats", "Tasks", "Repeating tasks",
                "Tags", "Lists", "Daily habits", "Time-since trackers")) ROWS.put(row, "CVED");
        ROWS.put("Settings", "VED");
        for (var row : List.of("Vaults", "Pages and folders", "Anki integration",
                "Task properties", "Property options", "Statuses")) ROWS.put(row, "CVED");
    }

    /** What the tracker did for each row, and what the interface offers for it. */
    private static final Map<String, String> byTracker = new LinkedHashMap<>(), byInterface = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        try { run(); }
        catch (Throwable t) {
            // The windows built here keep Swing's threads, and so the JVM, alive:
            // a failure has to end the run itself or the suite waits forever.
            t.printStackTrace();
            System.exit(1);
        }
        // The same threads, on success.
        System.exit(0);
    }

    private static void run() throws Exception {
        tracker();
        var failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { ui(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception exception) throw exception;
        vaults();
        for (var side : List.of(byTracker, byInterface)) {
            var name = side == byTracker ? "tracker" : "interface";
            check(side.keySet().equals(ROWS.keySet()), "The " + name + " covers every row of the table, and no other: " + side.keySet());
            for (var row : ROWS.entrySet())
                check(side.get(row.getKey()).equals(row.getValue()),
                    row.getKey() + " fills " + row.getValue() + " in the " + name + ", not " + side.get(row.getKey()));
        }
        System.out.println("PASS: " + checks + " CRUD coverage checks (" + ROWS.size()
            + " kinds of record, each created, viewed, edited and deleted by the tracker and from the interface)");
    }

    private static void mark(String row, String letters) { mark(byTracker, row, letters); }

    private static void mark(Map<String, String> side, String row, String letters) {
        var had = side.getOrDefault(row, "");
        var out = new StringBuilder();
        for (char c : "CVED".toCharArray()) if (had.indexOf(c) >= 0 || letters.indexOf(c) >= 0) out.append(c);
        side.put(row, out.toString());
    }

    /** The column an interface check fills, from the words that name it. */
    private static void offered(String row, String what) {
        String letter = what.startsWith("create") || what.equals("switch on") ? "C"
            : what.startsWith("view") ? "V"
            : what.startsWith("delete") || what.equals("reset") ? "D" : "E";
        mark(byInterface, row, letter);
    }

    // ------------------------------------------------------------------ tracker

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;

    /** The tracker's half of every row: made, read back, changed, and removed. */
    private static void tracker() throws Exception {
        var repo = new Memory();
        var t = new Tracker(repo, Clock.fixed(NOW, ZONE));

        // Activities.
        t.addActivity("Invented reading", 30);
        var reading = t.state().activities().getFirst().id();
        t.renameActivity(reading, "Invented study");
        t.retargetActivity(reading, 45);
        check(t.state().activities().getFirst().name().equals("Invented study")
            && t.state().activities().getFirst().targetMinutes() == 45, "An activity is renamed and retargeted");
        t.addActivity("Invented spare", 0);
        var spare = t.state().activities().get(1).id();
        t.removeActivity(spare, false);
        check(t.state().activities().size() == 1, "An activity is deleted");
        failed(repo, t, () -> t.renameActivity(reading, "Never saved"));

        // Sessions.
        t.log(reading, NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        var session = t.state().sessions().getFirst().id();
        t.editSession(session, reading, NOW.minusSeconds(7000), NOW.minusSeconds(3600));
        check(t.state().sessions().getFirst().start().equals(NOW.minusSeconds(7000)), "A session is corrected");
        t.log(reading, NOW.minusSeconds(3000), NOW.minusSeconds(2000));
        t.editSessions(List.of(t.state().sessions().get(1).id()), new SessionBatch.Shift(Duration.ofMinutes(-5)));
        failed(repo, t, () -> t.deleteSession(session));
        int backups = repo.backups;
        t.deleteSession(session);
        t.deleteSessions(t.state().sessions().stream().map(Session::id).toList());
        check(t.state().sessions().isEmpty() && repo.backups > backups, "Sessions are deleted, one and several, after a backup");
        mark("Sessions", "CVED");
        mark("Activities", "CVED");

        // Schedule blocks.
        t.plan(reading, NOW.plusSeconds(3600), NOW.plusSeconds(7200));
        var block = t.state().blocks().getFirst().id();
        t.editBlock(block, reading, NOW.plusSeconds(3600), NOW.plusSeconds(9000));
        check(t.state().blocks().getFirst().end().equals(NOW.plusSeconds(9000)), "A planned block is moved");
        failed(repo, t, () -> t.deleteBlock(block));
        t.deleteBlock(block);
        check(t.state().blocks().isEmpty(), "A planned block is deleted");
        mark("Schedule blocks", "CVED");

        // Weekly repeats, and one week of one.
        var rule = t.repeat(reading, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0));
        t.editRepeat(rule.id(), reading, DayOfWeek.MONDAY, LocalTime.of(9, 30), LocalTime.of(10, 30));
        var monday = LocalDate.of(2026, 10, 12);
        t.skipRepeatWeek(rule.id(), monday);
        t.restoreRepeatWeek(rule.id(), monday);
        t.changeRepeatWeek(rule.id(), monday, monday, LocalTime.of(11, 0), LocalTime.of(12, 0));
        check(Analytics.occurrences(t.state(), monday, ZONE).getFirst().changed(), "One week of a repeat is changed on its own");
        backups = repo.backups;
        failed(repo, t, () -> t.deleteRepeat(rule.id()));
        t.deleteRepeat(rule.id());
        check(t.state().recurring().isEmpty() && repo.backups > backups, "A weekly repeat is deleted after a backup");
        mark("Weekly repeats", "CVED");

        // Tags, then lists, which tasks point at.
        var tag = t.addTag("Invented course", 0x90D8DA);
        t.editTag(tag.id(), "Invented seminar", 0xE8B24C);
        check(t.state().tags().getFirst().name().equals("Invented seminar"), "A tag is renamed and recoloured");
        var list = t.addList("Invented list", 0xA98BD4);
        t.editList(list.id(), "Invented errands", 0x90D8DA);
        check(t.state().lists().getFirst().name().equals("Invented errands"), "A list is renamed and recoloured");

        // Tasks.
        var task = new Task(UUID.randomUUID(), reading, List.of(tag.id()), "Invented essay", "", LocalDate.of(2026, 10, 9),
            TaskStatus.TODO, "Manual entry", NOW, 0, null, List.of());
        t.addTask(task);
        var draft = t.state().tasks().getFirst();
        t.updateTask(new Task(draft.id(), draft.activityId(), draft.tagIds(), "Invented essay, second draft", draft.notes(),
            draft.due(), draft.status(), draft.source(), draft.createdAt(), draft.order(), draft.plannedFor(), draft.pageIds(),
            draft.listId(), draft.repeat(), draft.history()));
        t.moveTask(task.id(), list.id());
        t.taskStatus(task.id(), TaskStatus.DOING);
        check(t.state().tasks().getFirst().title().endsWith("second draft") && t.state().tasks().getFirst().listId().equals(list.id()),
            "A task is edited and filed");
        failed(repo, t, () -> t.deleteTask(task.id()));
        t.deleteTask(task.id());
        check(t.state().tasks().isEmpty(), "A task is deleted");
        mark("Tasks", "CVED");

        // Repeating tasks.
        var weekly = new Task(UUID.randomUUID(), null, List.of(), "Invented laundry", "", LocalDate.of(2026, 10, 9),
            TaskStatus.TODO, "Manual entry", NOW, 1, null, List.of()).withRepeat(Repeat.weekly(1, EnumSet.of(DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 9)));
        t.addTask(weekly);
        t.skipOccurrence(weekly.id(), ZONE);
        check(t.state().tasks().getFirst().due().equals(LocalDate.of(2026, 10, 16)), "A repeating task moves to its next date");
        t.updateTask(t.state().tasks().getFirst().withRepeat(null));
        check(!t.state().tasks().getFirst().repeats(), "A task stops repeating");
        t.deleteTasks(List.of(weekly.id()));
        mark("Repeating tasks", "CVED");

        backups = repo.backups;
        t.deleteTag(tag.id());
        t.deleteList(list.id(), false);
        check(t.state().tags().isEmpty() && t.state().lists().isEmpty() && repo.backups > backups,
            "A tag and a list are deleted after a backup");
        mark("Tags", "CVED");
        mark("Lists", "CVED");

        // Daily habits.
        t.addHabit("Invented stretch", HabitKind.DAILY, ZONE, null);
        var stretch = t.state().habits().getFirst().id();
        t.checkIn(stretch, LocalDate.of(2026, 10, 1), true);
        t.renameHabit(stretch, "Invented walk");
        t.habitZone(stretch, ZoneId.of("Asia/Tokyo"));
        check(t.state().habits().getFirst().name().equals("Invented walk")
            && t.state().habits().getFirst().checkIns().contains(LocalDate.of(2026, 10, 1)), "A daily habit is renamed, rezoned and checked off");
        failed(repo, t, () -> t.deleteHabit(stretch));
        t.deleteHabit(stretch);
        mark("Daily habits", "CVED");

        // Time-since trackers.
        t.addHabit("Invented reset", HabitKind.TIME_SINCE, ZONE, NOW.minusSeconds(86_400 * 3));
        var since = t.state().habits().getFirst().id();
        t.addHabitPeriod(since, NOW.minusSeconds(86_400 * 2));
        t.restartHabit(since);
        var period = t.state().habits().getFirst().starts().get(1);
        t.editHabitPeriod(since, period, period.plusSeconds(3600));
        t.deleteHabitPeriod(since, period.plusSeconds(3600));
        check(t.state().habits().getFirst().starts().size() == 2, "A time-since tracker's periods are added, edited and deleted");
        t.deleteHabit(since);
        check(t.state().habits().isEmpty(), "Both kinds of habit are deleted");
        mark("Time-since trackers", "CVED");

        // Settings.
        t.settings(new Settings(ThemeId.LINEN, 5, 600, DayOfWeek.SUNDAY));
        check(t.state().settings().theme() == ThemeId.LINEN, "Settings are changed");
        t.reset(EnumSet.of(Tracker.ResetPart.SETTINGS));
        check(t.state().settings().equals(Settings.defaults()), "Settings are reset to the defaults");
        mark("Settings", "VED");

        // Pages and folders.
        var folder = t.pages().createFolder(null, "Invented notes");
        var page = t.pages().createPage(folder.id(), "Invented page", "# Invented");
        t.pages().renamePage(page.id(), "Invented page, renamed");
        t.pages().updateBody(page.id(), "# Invented\n\nMore.");
        t.pages().renameFolder(folder.id(), "Invented folder");
        t.pages().trash(List.of(page.id()), List.of());
        t.pages().restorePage(page.id());
        check(t.pages().page(page.id()).title().equals("Invented page, renamed"), "A page is renamed, edited, trashed and restored");
        t.pages().trash(List.of(page.id()), List.of(folder.id()));
        t.pages().purge(List.of(page.id()), List.of(folder.id()));
        check(t.state().notes().pages().isEmpty() && t.state().notes().folders().isEmpty(), "A page and folder are deleted forever");
        mark("Pages and folders", "CVED");

        // Task properties (#68), their options, and the owner's statuses.
        var props = t.properties();
        var effort = props.addProperty("Invented effort", PropertyType.NUMBER, null);
        check(props.property(effort.id()).type() == PropertyType.NUMBER, "A property is made and read back");
        props.renameProperty(effort.id(), "Invented hours");
        props.hideProperty(effort.id(), true);
        props.changeType(effort.id(), PropertyType.TEXT);
        check(props.property(effort.id()).name().equals("Invented hours") && props.property(effort.id()).hidden(),
            "A property is renamed, hidden and retyped");
        var pick = props.addProperty("Invented pick", PropertyType.SELECT, null);
        var option = props.addOption(pick.id(), "Invented option");
        check(props.property(pick.id()).option(option.id()) != null, "An option is made and read back");
        props.renameOption(pick.id(), option.id(), "Invented choice");
        props.recolourOption(pick.id(), option.id(), 0x123456);
        check(props.property(pick.id()).option(option.id()).name().equals("Invented choice"), "An option is renamed and recoloured");
        failed(repo, t, () -> props.deleteOption(pick.id(), option.id()));
        props.deleteOption(pick.id(), option.id());
        check(props.property(pick.id()).options().isEmpty(), "An option is deleted");
        mark("Property options", "CVED");
        failed(repo, t, () -> props.deleteProperty(effort.id()));
        props.deleteProperty(effort.id());
        props.deleteProperty(pick.id());
        check(t.state().database().properties().isEmpty(), "A property is deleted");
        mark("Task properties", "CVED");
        var waiting = props.addStatus("Invented waiting", TaskStatus.TODO);
        check(props.statusChoices().stream().anyMatch(c -> waiting.equals(c.option())), "A status is made and offered");
        props.renameStatus(waiting.id(), "Invented pending");
        props.recolourStatus(waiting.id(), 0x654321);
        check(props.status(waiting.id()).name().equals("Invented pending"), "A status is renamed and recoloured");
        failed(repo, t, () -> props.deleteStatus(waiting.id()));
        props.deleteStatus(waiting.id());
        check(t.state().database().statuses().isEmpty(), "A status is deleted");
        mark("Statuses", "CVED");

        // The Anki integration and the counts it keeps.
        t.anki(t.state().anki().enabled(true));
        t.ankiSeen(new AnkiSnapshot("Invented profile", 12, new TreeMap<>(Map.of(LocalDate.of(2026, 10, 6), 40L)), NOW));
        check(t.state().anki().last().today() == 12, "Anki's counts are kept");
        t.ankiSeen(new AnkiSnapshot("Invented profile", 20, new TreeMap<>(), NOW.plusSeconds(60)));
        check(t.state().anki().last().today() == 20, "and replaced on the next read");
        t.reset(EnumSet.of(Tracker.ResetPart.ANKI));
        check(t.state().anki().equals(Anki.off()), "The integration and its counts are deleted");
        mark("Anki integration", "CVED");
    }

    /** A change whose save fails leaves everything as it was. */
    private static void failed(Memory repo, Tracker t, Action action) throws Exception {
        var before = t.state();
        repo.fail = true;
        try { refuses(action, "the save failed"); } finally { repo.fail = false; }
        check(t.state().equals(before), "A failed save changes nothing");
    }

    // ----------------------------------------------------------------------- UI

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static <T> T find(Container root, Class<T> type) {
        for (var child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { var found = find(nested, type); if (found != null) return found; }
        }
        return null;
    }

    /** A control a person can use: there, on, reachable from the keyboard, and named aloud. */
    private static void usable(Container root, String name, String row, String what) {
        var control = named(root, name);
        check(control != null, row + ": " + what + " has a control (" + name + ")");
        check(control.isEnabled(), row + ": " + what + " is enabled (" + name + ")");
        check(control.isFocusable(), row + ": " + what + " is reachable from the keyboard (" + name + ")");
        var spoken = control.getAccessibleContext().getAccessibleName();
        check(spoken != null && !spoken.isBlank(), row + ": " + what + " is named for a screen reader (" + name + ")");
        offered(row, what);
    }

    /** A record on screen: there, visible, and saying something a screen reader can read. */
    private static void shown(Container root, String name, String row) {
        var view = named(root, name);
        check(view != null && view.isVisible(), row + ": view shows it (" + name + ")");
        String said = view instanceof JLabel l ? l.getText() : view instanceof AbstractButton b ? b.getText()
            : view instanceof javax.swing.text.JTextComponent text ? text.getText() : null;
        var spoken = view.getAccessibleContext().getAccessibleName();
        check(said != null && !said.isBlank() || spoken != null && !spoken.isBlank(),
            row + ": view says what it shows (" + name + ")");
        offered(row, "view");
    }

    /** A menu item a person can choose: there, on, and saying what it does. */
    private static void item(JPopupMenu menu, String name, String row, String what) {
        JMenuItem found = null;
        for (var child : menu.getComponents()) if (child instanceof JMenuItem entry && name.equals(entry.getName())) found = entry;
        check(found != null, row + ": " + what + " is in the menu (" + name + ")");
        check(found.isEnabled() && !found.getText().isBlank(), row + ": " + what + " can be chosen (" + name + ")");
        offered(row, what);
    }

    private static void open(YoruApp app, String page) {
        Preview.button(app, page).doClick();
        Preview.layout(app);
    }

    /** The interface's half: every row's four controls, found where a person would look. */
    private static void ui() throws Exception {
        Theme.apply(ThemeId.MIDNIGHT);
        var ids = new HashMap<String, UUID>();
        var app = Preview.trackerApp(ThemeId.MIDNIGHT, 1280, 900, tracker -> {
            var list = tracker.addList("Invented errands", 0x90D8DA);
            ids.put("list", list.id());
            tracker.addHabit("Invented stretch", HabitKind.DAILY, ZoneId.systemDefault(), null);
            tracker.addHabit("Invented reset", HabitKind.TIME_SINCE, ZoneId.systemDefault(), Instant.now().minusSeconds(86_400));
            var folder = tracker.pages().createFolder(null, "Invented notes");
            ids.put("folder", folder.id());
            ids.put("page", tracker.pages().createPage(folder.id(), "Invented page", "# Invented").id());
        });
        var tracker = app.tracker();
        var state = tracker.state();
        // Not the one the fixture's timer is running on: that one cannot be deleted until it stops.
        var running = tracker.active();
        var activity = state.activities().stream()
            .filter(a -> running == null || !a.id().equals(running.activityId())).findFirst().orElseThrow().id();

        // Activities: made from Today, changed and deleted on Data.
        open(app, "Today");
        usable(app, "activity.new", "Activities", "create");
        open(app, "Data");
        shown(app, "activity.recorded." + activity, "Activities");
        usable(app, "activity.rename." + activity, "Activities", "edit");
        usable(app, "activity.target." + activity, "Activities", "edit");
        usable(app, "activity.remove." + activity, "Activities", "delete");

        // Sessions: the table on Data, one or several at a time.
        usable(app, "sessions.log", "Sessions", "create");
        check(named(app, "sessions.table") instanceof JTable table && table.getRowCount() > 0, "Sessions: the table lists them");
        offered("Sessions", "view");
        var sessions = (JTable) named(app, "sessions.table");
        // The oldest rows: the newest is the fixture's running timer, which is
        // stopped rather than edited or deleted.
        int last = sessions.getRowCount() - 1;
        sessions.setRowSelectionInterval(last, last);
        usable(app, "sessions.edit", "Sessions", "edit");
        usable(app, "sessions.delete", "Sessions", "delete");
        sessions.setRowSelectionInterval(last - 1, last);
        usable(app, "sessions.move", "Sessions", "edit several");
        usable(app, "sessions.shift", "Sessions", "edit several");

        // Schedule blocks, and weekly repeats with one week of one.
        open(app, "Schedule");
        var block = state.blocks().getFirst().id();
        usable(app, "schedule.plan", "Schedule blocks", "create");
        shown(app, "block.when." + block, "Schedule blocks");
        usable(app, "block.edit." + block, "Schedule blocks", "edit");
        usable(app, "block.delete." + block, "Schedule blocks", "delete");
        check(find(app, ScheduleGrid.class) != null, "Schedule blocks: the week grid shows them");
        usable(app, "schedule.template", "Weekly repeats", "view and edit the template");
        var moved = state.recurring().stream().filter(r -> !r.changes().isEmpty()).findFirst().orElseThrow();
        var week = moved.changes().getFirst().date();
        usable(app, "repeatWeek.restore." + moved.id() + "." + week, "Weekly repeats", "restore one week");
        usable(app, "repeatWeek.change." + moved.id() + "." + week, "Weekly repeats", "change one week");
        usable(app, "repeatWeek.skip." + moved.id() + "." + week, "Weekly repeats", "skip one week");
        var template = new WeeklyTemplate(tracker, () -> { });
        var rule = state.recurring().getFirst().id();
        usable(template, "repeat.add", "Weekly repeats", "create");
        usable(template, "repeat.edit." + rule, "Weekly repeats", "edit");
        usable(template, "repeat.remove." + rule, "Weekly repeats", "delete");

        // Tasks, repeating tasks, tags and lists: the Tasks page.
        open(app, "Tasks");
        var board = find(app, TasksPanel.class);
        check(board != null, "Tasks: the page holds the task table");
        usable(board, "task.new", "Tasks", "create");
        var task = state.tasks().stream().filter(x -> x.status() != TaskStatus.DONE).findFirst().orElseThrow().id();
        shown(board, "task.title." + task, "Tasks");
        var menu = board.rowMenu(task);
        check(menu != null, "Tasks: every row has a menu");
        item(menu, "task.edit." + task, "Tasks", "edit");
        item(menu, "task.delete." + task, "Tasks", "delete");
        usable(board, "tasks.select", "Tasks", "choose several");
        usable(board, "task.tags", "Tags", "view");
        var tags = new TagEditor(tracker, () -> { });
        var tag = state.tags().getFirst().id();
        usable(tags, "tag.add", "Tags", "create");
        usable(tags, "tag.rename." + tag, "Tags", "edit");
        usable(tags, "tag.colour." + tag, "Tags", "edit");
        usable(tags, "tag.delete." + tag, "Tags", "delete");
        usable(board, "tasks.list.new", "Lists", "create");
        usable(board, "tasks.place." + ids.get("list"), "Lists", "view");
        var invented = tracker.state().lists().stream().filter(l -> l.id().equals(ids.get("list"))).findFirst().orElseThrow();
        var lists = TaskLists.menu(board, tracker, invented, key -> { }, () -> { });
        item(lists, "list.rename." + ids.get("list"), "Lists", "edit");
        item(lists, "list.colour." + ids.get("list"), "Lists", "edit");
        item(lists, "list.delete." + ids.get("list"), "Lists", "delete");
        // Task properties, their options and statuses: the manager, and the table.
        usable(board, "task.properties", "Task properties", "view");
        var manager = new PropertyManager(tracker, () -> { });
        usable(manager, "property.add", "Task properties", "create");
        var difficulty = tracker.state().database().properties().stream().filter(p -> p.type() == PropertyType.SELECT).findFirst().orElseThrow();
        usable(manager, "property.hide." + difficulty.id(), "Task properties", "edit: hide");
        usable(manager, "property.more." + difficulty.id(), "Task properties", "edit: its menu");
        var propertyMenu = manager.menu(difficulty);
        item(propertyMenu, "property.rename." + difficulty.id(), "Task properties", "edit");
        item(propertyMenu, "property.retype." + difficulty.id(), "Task properties", "edit");
        item(propertyMenu, "property.delete." + difficulty.id(), "Task properties", "delete");
        item(propertyMenu, "property.options." + difficulty.id(), "Property options", "view");
        var options = new PropertyManager.OptionEditor(tracker, difficulty.id(), () -> { });
        usable(options, "option.add", "Property options", "create");
        var anOption = difficulty.options().getFirst();
        var optionMenu = options.menu(anOption);
        item(optionMenu, "option.rename." + anOption.id(), "Property options", "edit");
        item(optionMenu, "option.colour." + anOption.id(), "Property options", "edit");
        item(optionMenu, "option.delete." + anOption.id(), "Property options", "delete");
        var withValue = tracker.state().tasks().stream().filter(x -> x.values().containsKey(difficulty.id())).findFirst().orElseThrow();
        usable(board, "task.value." + difficulty.id() + "." + withValue.id(), "Task properties", "edit a value");
        usable(manager, "status.add", "Statuses", "create");
        var own = tracker.state().database().statuses().getFirst();
        var statusMenu = manager.statusMenu(own);
        item(statusMenu, "status.rename." + own.id(), "Statuses", "edit");
        item(statusMenu, "status.colour." + own.id(), "Statuses", "edit");
        item(statusMenu, "status.delete." + own.id(), "Statuses", "delete");
        var waitingTask = tracker.state().tasks().stream().filter(x -> own.id().equals(x.statusId())).findFirst().orElseThrow();
        usable(board, "task.status." + waitingTask.id(), "Statuses", "view and step through");

        tracker.addTask(new Task(UUID.randomUUID(), null, List.of(), "Invented laundry", "", LocalDate.now(),
            TaskStatus.TODO, "Manual entry", Instant.now(), 99, null, List.of()).withRepeat(Repeat.weekly(1, EnumSet.of(LocalDate.now().getDayOfWeek()), LocalDate.now())));
        var repeating = tracker.state().tasks().getLast().id();
        app.refresh();
        Preview.layout(app);
        board = find(app, TasksPanel.class);
        usable(board, "task.new", "Repeating tasks", "create, with a repeat in the form");
        menu = board.rowMenu(repeating);
        check(menu != null, "Repeating tasks: the new repeating task is on the board");
        shown(board, "task.title." + repeating, "Repeating tasks");
        item(menu, "task.edit." + repeating, "Repeating tasks", "edit the rule");
        item(menu, "task.skip." + repeating, "Repeating tasks", "skip one");
        item(menu, "task.stopRepeat." + repeating, "Repeating tasks", "stop repeating");
        item(menu, "task.delete." + repeating, "Repeating tasks", "delete");

        // Habits of both kinds.
        open(app, "Habits");
        var daily = tracker.state().habits().stream().filter(h -> h.kind() == HabitKind.DAILY).findFirst().orElseThrow();
        var since = tracker.state().habits().stream().filter(h -> h.kind() == HabitKind.TIME_SINCE).findFirst().orElseThrow();
        usable(app, "habit.new.daily", "Daily habits", "create");
        usable(app, "habit.more." + daily.id(), "Daily habits", "open its menu");
        shown(app, "habit.name." + daily.id(), "Daily habits");
        var dailyMenu = HabitsPanel.dailyMenu(tracker, () -> { }, new JPanel(), daily);
        item(dailyMenu, "habit.history." + daily.id(), "Daily habits", "view and correct history");
        item(dailyMenu, "habit.rename." + daily.id(), "Daily habits", "edit");
        item(dailyMenu, "habit.zone." + daily.id(), "Daily habits", "edit");
        item(dailyMenu, "habit.delete." + daily.id(), "Daily habits", "delete");
        usable(app, "habit.new.since", "Time-since trackers", "create");
        usable(app, "habit.restart." + since.id(), "Time-since trackers", "restart");
        shown(app, "habit.name." + since.id(), "Time-since trackers");
        shown(app, "habit.elapsed." + since.id(), "Time-since trackers");
        var sinceMenu = HabitsPanel.sinceMenu(tracker, () -> { }, new JPanel(), since, ZoneId.systemDefault());
        item(sinceMenu, "habit.periods." + since.id(), "Time-since trackers", "view its periods");
        item(sinceMenu, "habit.editStart." + since.id(), "Time-since trackers", "edit");
        item(sinceMenu, "habit.renameMenu." + since.id(), "Time-since trackers", "edit");
        item(sinceMenu, "habit.deleteMenu." + since.id(), "Time-since trackers", "delete");
        var periods = (Container) HabitsPanel.history(tracker, since.id(), ZoneId.systemDefault(), () -> { });
        usable(periods, "habit.period.add." + since.id(), "Time-since trackers", "add a missed period");
        var first = tracker.state().habits().stream().filter(h -> h.id().equals(since.id())).findFirst().orElseThrow().starts().getFirst();
        usable(periods, "habit.period.edit." + first.toEpochMilli(), "Time-since trackers", "edit a period");

        // Settings, the Anki integration and resetting.
        open(app, "Settings");
        usable(app, "settings.theme." + ThemeId.LINEN.name(), "Settings", "edit the theme");
        usable(app, "settings.weekStart", "Settings", "view the week start");
        usable(app, "settings.tracking.save", "Settings", "save");
        usable(app, "settings.reset", "Settings", "reset");
        usable(app, "anki.enabled", "Anki integration", "switch on");
        usable(app, "settings.reset", "Anki integration", "delete its counts");
        check(Arrays.stream(Tracker.ResetPart.values()).anyMatch(p -> p == Tracker.ResetPart.ANKI),
            "Anki integration: resetting offers its counts");
        tracker.anki(tracker.state().anki().enabled(true));
        app.refresh();
        Preview.layout(app);
        usable(app, "anki.key", "Anki integration", "edit its key");
        usable(app, "anki.test", "Anki integration", "read Anki now");
        tracker.ankiSeen(new AnkiSnapshot("Invented profile", 12, new TreeMap<>(), Instant.now()));
        open(app, "Today");
        shown(app, "today.anki.status", "Anki integration");

        // Pages and folders.
        open(app, "Pages");
        var explorer = find(app, PageExplorer.class);
        check(explorer != null, "Pages and folders: the explorer is there");
        usable(app, "pages.tree", "Pages and folders", "view");
        var pageMenu = explorer.menuFor(List.of(new PageExplorer.Item(PageExplorer.Kind.PAGE, ids.get("page"), "Invented page", false)));
        item(pageMenu, "pages.menu.open", "Pages and folders", "view");
        item(pageMenu, "pages.menu.rename", "Pages and folders", "edit");
        item(pageMenu, "pages.menu.trash", "Pages and folders", "delete");
        var folderMenu = explorer.menuFor(List.of(new PageExplorer.Item(PageExplorer.Kind.FOLDER, ids.get("folder"), "Invented notes", false)));
        item(folderMenu, "pages.menu.newPage", "Pages and folders", "create a page");
        item(folderMenu, "pages.menu.newFolder", "Pages and folders", "create a folder");
        item(folderMenu, "pages.menu.rename", "Pages and folders", "edit");
        var trashed = explorer.menuFor(List.of(new PageExplorer.Item(PageExplorer.Kind.PAGE, ids.get("page"), "Invented page", true)));
        item(trashed, "pages.menu.restore", "Pages and folders", "restore");
        item(trashed, "pages.menu.purge", "Pages and folders", "delete forever");
    }

    /** Vaults: made, renamed, unlocked without a password and deleted, from a store of its own. */
    private static void vaults() throws Exception {
        var dir = Files.createTempDirectory("yoru-crud-vaults");
        try {
            var store = new VaultStore(dir);
            try (var vault = store.create("Invented", "invented-password-1".toCharArray())) { vault.save(State.empty()); }
            check(store.names().contains("Invented"), "Vaults: one is created and listed");
            store.rename("Invented", "Renamed");
            check(store.exists("Renamed") && !store.exists("Invented"), "Vaults: one is renamed");
            try (var vault = store.open("Renamed", "invented-password-1".toCharArray())) {
                var fresh = store.removePassword("Renamed", vault, State.empty());
                check(fresh != null && store.passwordless("Renamed"), "Vaults: its password is taken off");
                java.util.Arrays.fill(fresh, '\0');
            }
            store.delete("Renamed");
            check(!store.exists("Renamed"), "Vaults: one is deleted");
            mark("Vaults", "CVED");

            // The window's vault card, for a vault the store manages.
            try (var vault = store.create("Windowed", "invented-password-2".toCharArray())) {
                vault.save(State.empty());
                var failure = new Throwable[1];
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        var constructor = YoruApp.class.getDeclaredConstructor(Tracker.class, Repository.class,
                            VaultStore.class, String.class, char[].class);
                        constructor.setAccessible(true);
                        var app = constructor.newInstance(new Tracker(vault, Clock.systemUTC()), vault, store, "Windowed",
                            "invented-password-2".toCharArray());
                        var card = app.vaultCard();
                        usable(card, "vault.new", "Vaults", "create");
                        usable(card, "vault.switch", "Vaults", "view the others");
                        usable(card, "vault.rename", "Vaults", "edit");
                        usable(card, "vault.removePassword", "Vaults", "edit the password");
                        usable(card, "vault.delete", "Vaults", "delete");
                    } catch (Throwable t) { failure[0] = t; }
                });
                if (failure[0] instanceof Error error) throw error;
                if (failure[0] instanceof Exception exception) throw exception;
            }
        } finally {
            try (var files = Files.walk(dir)) {
                for (var f : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
            }
        }
    }
}
