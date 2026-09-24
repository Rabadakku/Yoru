package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * Quick add on screen (#74): the Tasks page's line highlighting and listing
 * what it read, each part kept as text on request, Enter making the whole
 * task in the place on screen, the Settings switch, the due time in the due
 * column, and the command palette going places and adding tasks from anywhere.
 */
public final class QuickAddUiTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static final class Memory implements Repository {
        State state = State.empty(); boolean fail;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("Synthetic save failure"); state = next; }
        public void close() { }
    }
    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static int count(Container root, String prefix) {
        int n = 0;
        for (var child : root.getComponents()) {
            if (child.getName() != null && child.getName().startsWith(prefix) && child.isVisible()) n++;
            if (child instanceof Container nested) n += count(nested, prefix);
        }
        return n;
    }
    private static <T> T find(Container root, Class<T> type) {
        for (var child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) { var found = find(nested, type); if (found != null) return found; }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        var failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> { try { run(); } catch (Throwable t) { failure[0] = t; } });
        QuickAddSetting.enabled(true);
        if (failure[0] != null) { failure[0].printStackTrace(); System.exit(1); }
        System.out.println("PASS: " + checks + " quick-add UI checks (the line, its parts, keep as text, Enter, the switch, due times, the palette)");
        System.exit(0);
    }

    private static void run() throws Exception {
        Theme.apply(ThemeId.MIDNIGHT);
        QuickAddSetting.enabled(true);
        var repo = new Memory();
        var t = new Tracker(repo, Clock.systemUTC());
        t.addTag("school", 0x90D8DA);
        var home = t.addList("Home", 0x6FBF8B);
        var chores = t.addList("Chores", 0xE8B24C);
        var board = new TasksPanel(t, () -> { }, () -> false);
        board.setSize(1400, 900);
        Preview.layout(board);
        var line = board.quickAddField();
        check(named(board, "task.quickAdd") == line.field(), "The table ends in the quick-add line");
        check(named(board, "task.newRow") instanceof JButton, "with the full form a button away");

        // Typing: parts highlighted and listed, each with its ×.
        line.field().setText("Essay draft tomorrow 5pm #school !high every monday");
        check(line.result().title().equals("Essay draft"), "The line reads its title: " + line.result().title());
        check(line.field().getHighlighter().getHighlights().length == 5, "Each part is highlighted as it is typed");
        check(count(line, "task.quickAdd.part.") == 5 && count(line, "task.quickAdd.keep.") == 5, "and listed under the line, each with a ×");
        var date = (JButton) named(line, "task.quickAdd.keep.DATE");
        check(date.getAccessibleContext().getAccessibleName().equals("Keep “tomorrow” as text, not as the due"),
            "The × says what it keeps: " + date.getAccessibleContext().getAccessibleName());
        date.doClick();
        check(line.result().title().equals("Essay draft tomorrow") && line.result().parts().stream().noneMatch(p -> p.kind() == QuickAdd.Kind.DATE),
            "Keeping the date as text puts it back in the title");
        check(LocalDate.now().equals(line.result().due()), "and the 5 pm left on its own is today's, as the grammar says");
        check(line.result().time().equals(LocalTime.of(17, 0)) && line.result().priority() == Priority.HIGH, "and the rest is still read");
        check(line.field().getHighlighter().getHighlights().length == 4, "and it is no longer highlighted");
        line.field().setText(line.field().getText() + " ");
        check(line.result().title().equals("Essay draft tomorrow"), "A part kept as text stays so while typing continues");

        // Escape clears, and forgets what was kept.
        line.field().getActionMap().get("quickAdd.clear").actionPerformed(null);
        check(line.field().getText().isEmpty() && line.result() == null, "Escape clears the line");
        line.field().setText("Buy milk tomorrow");
        check(line.result().due() != null, "and what was kept as text is forgotten with it");

        // Enter makes the task, in the list on screen unless the line names another.
        board.showPlace(home.id().toString());
        line = board.quickAddField();
        line.field().setText("Essay draft tomorrow 5pm #school #seminar !high every monday");
        line.field().postActionEvent();
        var essay = t.state().tasks().stream().filter(x -> x.title().equals("Essay draft")).findFirst().orElseThrow();
        var tomorrow = LocalDate.now().plusDays(1);
        check(essay.due().equals(tomorrow) && essay.dueTime().equals(LocalTime.of(17, 0)), "Enter makes the task, due tomorrow at 5 pm");
        check(essay.priority() == Priority.HIGH && essay.repeat().days().equals(Set.of(DayOfWeek.MONDAY)), "high priority, every Monday");
        var seminar = t.state().tags().stream().filter(x -> x.name().equals("seminar")).findFirst().orElseThrow();
        var school = t.state().tags().stream().filter(x -> x.name().equals("school")).findFirst().orElseThrow();
        check(essay.tagIds().equals(List.of(school.id(), seminar.id())), "an existing tag matched and a new one made in the same write");
        check(home.id().equals(essay.listId()), "filed in the list on screen");
        check(line.field().getText().isEmpty(), "and the line is cleared for the next one");
        line.field().setText("Vacuum /Chores");
        line.field().postActionEvent();
        var vacuum = t.state().tasks().stream().filter(x -> x.title().equals("Vacuum")).findFirst().orElseThrow();
        check(chores.id().equals(vacuum.listId()) && vacuum.due().equals(LocalDate.now()), "A list the line names wins; with no date it is due today");

        // A failed save keeps the line, and makes no tag.
        repo.fail = true;
        var before = t.state();
        line.field().setText("Never saved #lost");
        try { board.quickAdd(line.result()); } catch (Throwable ignored) { }
        check(t.state().equals(before) && line.field().getText().equals("Never saved #lost"), "A failed save keeps the line and makes no tag");
        repo.fail = false;
        line.clear();

        // The due column says the time.
        board.showPlace(TaskLists.ALL);
        Preview.layout(board);
        var due = (JLabel) named(board, "task.due." + essay.id());
        check(due.getText().endsWith("5:00 PM") && due.getToolTipText().contains("at 5:00 PM"), "The due column shows the time: " + due.getText());

        // The switch in Settings: off, the line is all title.
        QuickAddSetting.enabled(false);
        line.field().setText("Essay draft tomorrow #school");
        check(line.result().title().equals("Essay draft tomorrow #school") && line.result().parts().isEmpty()
            && line.field().getHighlighter().getHighlights().length == 0, "With reading switched off the whole line is the title");
        QuickAddSetting.enabled(true);
        line.clear();

        // A due time goes with its date.
        var once = essay.withRepeat(null);
        check(once.withDates(null, null).dueTime() == null, "Clearing the due date clears the time on it");
        var undated = once.withDates(null, null);
        try { undated.withDetails(undated.details().withDueTime(LocalTime.NOON)); throw new AssertionError("A time with no date was kept"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("due date"), "A due time with no due date is refused"); }

        // The command palette, in the whole window.
        var app = Preview.trackerApp(ThemeId.MIDNIGHT, 1280, 900);
        var all = CommandPalette.commands(app, "");
        check(all.stream().anyMatch(c -> c.name().equals("Go to Schedule")) && all.stream().anyMatch(c -> c.name().equals("New page")),
            "With nothing typed the palette lists what it can do");
        var tasks = CommandPalette.commands(app, "tas");
        check(tasks.getFirst().name().equals("Go to Tasks"), "A command's words list it first: " + tasks.getFirst().name());
        check(tasks.stream().anyMatch(c -> c.id().equals("task.add")), "and the line is still offered as a task");
        CommandPalette.commands(app, "go sched").getFirst().run().run();
        check(app.page().equals("Schedule"), "Taking a command does it");
        var add = CommandPalette.commands(app, "Return books friday #errands !low").getFirst();
        check(add.id().equals("task.add") && add.name().equals("Add task “Return books”"), "Words that are not a command are a task: " + add.name());
        check(add.detail().contains("#errands") && add.detail().contains("Low") && add.detail().endsWith("Inbox"),
            "The palette says what the task will be: " + add.detail());
        var weekly = CommandPalette.commands(app, "Laundry every monday").getFirst();
        check(weekly.detail().contains("every week on Mon"), "A repeat reads mid-sentence with the day's capital kept: " + weekly.detail());
        int before2 = app.tracker().state().tasks().size();
        add.run().run();
        var returned = app.tracker().state().tasks().stream().filter(x -> x.title().equals("Return books")).findFirst().orElseThrow();
        check(app.tracker().state().tasks().size() == before2 + 1 && returned.listId() == null && returned.priority() == Priority.LOW,
            "Taking it adds the task to the Inbox, from any page");
        check(app.page().equals("Schedule"), "and leaves the page where it was");

        // Every theme draws the line with its parts.
        for (var theme : ThemeId.values()) {
            Theme.apply(theme);
            var field = new QuickAddField(() -> new QuickAdd.Context(LocalDate.now(), DayOfWeek.MONDAY, List.of(), List.of()), r -> { }, () -> { });
            field.field().setText("Essay tomorrow 5pm #school !high every monday");
            field.setSize(900, 120);
            Preview.layout(field);
            field.paint(new java.awt.image.BufferedImage(900, 120, java.awt.image.BufferedImage.TYPE_INT_RGB).getGraphics());
            check(field.getPreferredSize().height > 0, "The line lays out in " + theme);
        }
        Theme.apply(ThemeId.MIDNIGHT);
    }
}
