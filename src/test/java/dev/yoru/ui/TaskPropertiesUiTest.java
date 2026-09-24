package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.List;

/**
 * Tasks as a database on screen (#68): the status button stepping through the
 * owner's statuses, the priority and property columns, their cells, folding
 * under the title in a narrow window, search and sort by what properties
 * hold, the editors a form and a cell use, the manager, and bulk priority.
 * Modal dialogs are left out: what they save is driven through the same calls.
 */
public final class TaskPropertiesUiTest {
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
    private static JMenuItem item(JPopupMenu menu, String name) {
        for (var child : menu.getComponents()) if (child instanceof JMenuItem i && name.equals(i.getName())) return i;
        return null;
    }
    private static void layout(Container c) { for (int i = 0; i < 3; i++) Preview.layout(c); }
    private static Task task(Tracker t, UUID id) { return t.state().tasks().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(); }
    private static List<String> order(Container board) {
        var out = new ArrayList<String>();
        collect(board, out);
        return out;
    }
    private static void collect(Container root, List<String> into) {
        for (var child : root.getComponents()) {
            if (child instanceof JTextArea a && a.getName() != null && a.getName().startsWith("task.title.")) into.add(a.getText());
            if (child instanceof Container nested) collect(nested, into);
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> { try { run(); } catch (Exception e) { throw new RuntimeException(e); } });
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            var cause = wrapped.getCause();
            if (cause instanceof RuntimeException r && r.getCause() instanceof Exception inner) throw inner;
            if (cause instanceof Error error) throw error;
            throw wrapped;
        }
        System.out.println("PASS: " + checks + " task property UI checks (statuses, priority and property columns, cells, folding, search, sort, editors, manager, bulk)");
    }

    private static void run() throws Exception {
        Theme.apply(ThemeId.MIDNIGHT);
        var repo = new Memory();
        var t = new Tracker(repo, Clock.systemUTC());
        var props = t.properties();
        var list = t.addList("Invented school", 0x6E8FD6);
        var today = LocalDate.now();
        var essay = new Task(UUID.randomUUID(), null, List.of(), "Invented essay", "", today, TaskStatus.TODO, "Manual entry", Instant.now(), 0, null, List.of());
        var lab = new Task(UUID.randomUUID(), null, List.of(), "Invented lab", "", today.plusDays(1), TaskStatus.TODO, "Manual entry", Instant.now(), 1, null, List.of());
        var walk = new Task(UUID.randomUUID(), null, List.of(), "Invented walk", "", today.plusDays(2), TaskStatus.TODO, "Manual entry", Instant.now(), 2, null, List.of());
        t.addTasks(List.of(essay, lab, walk));
        t.moveTask(essay.id(), list.id());
        t.moveTask(lab.id(), list.id());

        var board = new TasksPanel(t, () -> { }, () -> false);
        board.setSize(1800, 900);
        layout(board);
        check(named(board, "task.priority." + essay.id()) == null, "No priority column while no task has a priority");
        check(named(board, "task.properties") instanceof JButton b && b.isEnabled(), "The Properties button is on the tools row");

        // Properties of each type, one kept for the list, one hidden.
        var effort = props.addProperty("Effort", PropertyType.NUMBER, null);
        var difficulty = props.addProperty("Difficulty", PropertyType.SELECT, null);
        var hard = props.addOption(difficulty.id(), "Hard");
        var graded = props.addProperty("Graded", PropertyType.CHECKBOX, list.id());
        var link = props.addProperty("Link", PropertyType.URL, null);
        var secret = props.addProperty("Private note", PropertyType.TEXT, null);
        props.hideProperty(secret.id(), true);
        props.setPriority(essay.id(), Priority.URGENT);
        props.setValue(essay.id(), effort.id(), new Value.Amount(new BigDecimal("2.5")));
        props.setValue(essay.id(), difficulty.id(), new Value.Choice(hard.id()));
        props.setValue(lab.id(), link.id(), new Value.Text("https://example.com/lab"));
        props.setValue(walk.id(), secret.id(), new Value.Text("Invented and private"));
        board = new TasksPanel(t, () -> { }, () -> false);
        board.setSize(1800, 900);
        layout(board);
        check(named(board, "task.header.property." + effort.id()) != null && named(board, "task.header.property." + link.id()) != null,
            "Every task's properties are columns in All");
        check(named(board, "task.header.property." + graded.id()) == null, "A list's own property is not a column in All");
        check(named(board, "task.header.property." + secret.id()) == null, "A hidden property is not a column");
        var pill = (JButton) named(board, "task.priority." + essay.id());
        check(pill != null && pill.getText().equals("Urgent") && pill.getAccessibleContext().getAccessibleName().contains("Urgent"),
            "Once a task has a priority there is a column, and its pill says it");
        var amount = (JButton) named(board, "task.value." + effort.id() + "." + essay.id());
        check(amount.getText().equals("2.5") && amount.getAccessibleContext().getAccessibleName().startsWith("Effort for Invented essay: 2.5"),
            "A number cell shows its value and names it aloud");
        var chip = (TagChips.Cell) named(board, "task.value." + difficulty.id() + "." + essay.id());
        check(chip.tags().size() == 1 && chip.tags().getFirst().name().equals("Hard"), "A select cell draws its option as a pill");
        var folding = (TaskTable.Folding) named(board, "task.value." + effort.id() + "." + essay.id()).getParent();
        check(!folding.isNamed() && folding.getWidth() > 0, "In a wide window property cells sit in their columns, unnamed");

        // In the list, its own property is a column, and its checkbox ticks straight from the cell.
        board.showPlace(list.id().toString());
        layout(board);
        check(named(board, "task.header.property." + graded.id()) != null, "In its list, the list's own property is a column");
        var tick = (JCheckBox) named(board, "task.value." + graded.id() + "." + lab.id());
        tick.doClick();
        check(task(t, lab.id()).values().get(graded.id()) instanceof Value.Tick, "Ticking a checkbox cell sets the value");
        tick = (JCheckBox) named(board, "task.value." + graded.id() + "." + lab.id());
        tick.doClick();
        check(!task(t, lab.id()).values().containsKey(graded.id()), "and unticking it clears it");
        board.showPlace(TaskLists.ALL);

        // A narrow window: the columns fold under the title, named, and empty ones are left out.
        board.setSize(700, 900);
        layout(board);
        folding = (TaskTable.Folding) named(board, "task.value." + effort.id() + "." + essay.id()).getParent();
        check(folding.isNamed() && folding.getWidth() > 0, "In a narrow window a property folds under the title and says its name");
        var title = named(board, "task.title." + essay.id());
        var first = named(board, "task.priority." + essay.id());
        while (!(first instanceof TaskTable.Folding)) first = first.getParent();
        check(first.getY() > title.getY() && Math.abs(first.getX() - title.getX()) < 2, "The first folded cell starts under the title's left edge");
        check(folding.getY() >= first.getY() && folding.getX() > first.getX(), "and the next follows it along the line");
        var empty = named(board, "task.value." + effort.id() + "." + lab.id()).getParent();
        check(empty.getWidth() == 0, "A folded property with no value is left out");
        var header = named(board, "task.header.property." + effort.id());
        check(header.getWidth() == 0, "and the header drops the columns that folded");
        board.setSize(1800, 900);
        layout(board);
        check(!((TaskTable.Folding) named(board, "task.value." + effort.id() + "." + essay.id()).getParent()).isNamed(),
            "Widened again, it goes back into its column");

        // Status: the owner's own, stepped through in order.
        var waiting = props.addStatus("Waiting", TaskStatus.TODO);
        board = new TasksPanel(t, () -> { }, () -> false);
        board.setSize(1800, 900);
        layout(board);
        ((JButton) named(board, "task.status." + walk.id())).doClick();
        check(waiting.id().equals(task(t, walk.id()).statusId()), "The status button steps to the owner's status after To do");
        var status = (JButton) named(board, "task.status." + walk.id());
        check(status.getText().equals("Waiting") && status.getAccessibleContext().getAccessibleName().startsWith("Status: Waiting"),
            "and says it, aloud too");
        status.doClick();
        check(task(t, walk.id()).status() == TaskStatus.DOING && task(t, walk.id()).statusId() == null, "then on to Doing");

        // Search reads property values; sort by priority puts the urgent first.
        var search = (JTextField) named(board, "task.search");
        search.setText("hard");
        layout(board);
        check(order(board).equals(List.of("Invented essay")), "Searching finds a task by its select option");
        search.setText("example.com/lab");
        layout(board);
        check(order(board).equals(List.of("Invented lab")), "and by a link it holds");
        search.setText("private");
        layout(board);
        check(order(board).equals(List.of("Invented walk")), "and by a hidden property's text");
        search.setText("");
        props.setPriority(walk.id(), Priority.HIGH);
        board = new TasksPanel(t, () -> { }, () -> false);
        board.setSize(1800, 900);
        @SuppressWarnings("unchecked") var sort = (JComboBox<Object>) named(board, "task.sort");
        for (int i = 0; i < sort.getItemCount(); i++) if (sort.getItemAt(i).toString().equals("Priority")) sort.setSelectedIndex(i);
        layout(board);
        check(order(board).equals(List.of("Invented essay", "Invented walk", "Invented lab")), "Sorting by priority: urgent, high, then none");

        // The row menu and the bulk menu set priorities.
        check(board.rowMenu(lab.id()).getComponents().length > 0 && item(board.rowMenu(lab.id()), "task.priority.choose." + lab.id()) != null,
            "Every row's menu offers its priority");
        var bulk = new TaskBulkActions(t, () -> { }, e -> { throw new AssertionError(e); }, () -> false);
        bulk.start();
        bulk.update(t.state().tasks(), true);
        bulk.toggle(lab.id());
        bulk.toggle(walk.id());
        item(bulk.menu(), "tasks.bulk.priority.LOW").doClick();
        check(task(t, lab.id()).priority() == Priority.LOW && task(t, walk.id()).priority() == Priority.LOW, "Bulk priority reaches every selected task");

        // The editors a form and a cell use.
        var number = PropertyEditors.of(effort, null);
        ((JTextField) number.component()).setText("1,250.75");
        check(number.entry().value().equals(new Value.Amount(new BigDecimal("1250.75"))), "A number reads with its thousands separator");
        ((JTextField) number.component()).setText("lots");
        try { number.entry(); throw new AssertionError("an unreadable number was accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().startsWith("Effort: "), "An unreadable number names its property"); }
        ((JTextField) number.component()).setText("  ");
        check(number.entry().value() == null && number.entry().chosen().isEmpty(), "A blank number clears the value");
        var url = PropertyEditors.of(link, null);
        ((JTextField) url.component()).setText("not a link");
        try { url.entry(); throw new AssertionError("a link with spaces was accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().startsWith("Link: "), "A link with spaces is refused, naming it"); }
        var select = PropertyEditors.of(props.property(difficulty.id()), null);
        ((JComboBox<?>) select.component()).getEditor().setItem("Easy");
        var entry = select.entry();
        check(entry.chosen().isEmpty() && entry.typed().equals(List.of("Easy")), "A select can be given an option it does not have yet");
        props.set(lab.id(), difficulty.id(), entry);
        var easy = props.property(difficulty.id()).options().stream().filter(o -> o.name().equals("Easy")).findFirst().orElseThrow();
        check(task(t, lab.id()).values().get(difficulty.id()).equals(new Value.Choice(easy.id())), "and it is made and chosen in one write");
        ((JComboBox<?>) select.component()).getEditor().setItem("hard");
        check(PropertyEditors.of(props.property(difficulty.id()), null).entry().chosen().isEmpty(), "(a fresh editor starts empty)");
        var multiProperty = props.addProperty("Skills", PropertyType.MULTI_SELECT, null);
        var reading = props.addOption(multiProperty.id(), "Reading");
        var multi = PropertyEditors.of(props.property(multiProperty.id()), null);
        ((JCheckBox) named(multi.component(), "property.option." + reading.id())).setSelected(true);
        ((JTextField) named(multi.component(), "property.editor.new." + multiProperty.id())).setText("Writing, reading, Listening");
        var multiEntry = multi.entry();
        check(multiEntry.chosen().equals(List.of(reading.id())) && multiEntry.typed().equals(List.of("Writing", "Listening")),
            "A multi-select keeps a typed name that matches an option as that option");

        // The form's properties section saves the task, its new options and its values in one write.
        var section = new PropertyEditors.Section(t.state().database(), task(t, walk.id()), null);
        check(named(section, "property.editor." + graded.id()) == null, "The form shows only the properties the task's list has");
        check(named(section, "property.editor." + secret.id()) != null, "a hidden property included");
        ((JTextField) named(section, "property.editor." + effort.id())).setText("4");
        ((JTextField) named(section, "property.editor.new." + multiProperty.id())).setText("Drawing");
        var before = t.state();
        repo.fail = true;
        try { props.save(task(t, walk.id()), List.of(), section.entries()); throw new AssertionError("a failed save was published"); }
        catch (IOException expected) { check(t.state().equals(before), "A failed form save leaves no option behind"); }
        repo.fail = false;
        props.save(task(t, walk.id()), List.of(), section.entries());
        var saved = task(t, walk.id());
        check(saved.values().get(effort.id()).equals(new Value.Amount(BigDecimal.valueOf(4))), "The form saves a number");
        var drawing = props.property(multiProperty.id()).options().stream().filter(o -> o.name().equals("Drawing")).findFirst().orElseThrow();
        check(saved.values().get(multiProperty.id()).equals(new Value.Choices(List.of(drawing.id()))), "and a new option with its value");
        check(saved.values().get(secret.id()).equals(new Value.Text("Invented and private")), "and keeps what it did not change");

        // The manager.
        var manager = new PropertyManager(t, () -> { });
        ((JTextField) named(manager, "property.name")).setText("Room");
        ((JComboBox<?>) named(manager, "property.type")).setSelectedItem(PropertyType.TEXT);
        ((JButton) named(manager, "property.add")).doClick();
        var room = t.state().database().properties().getLast();
        check(room.name().equals("Room") && room.type() == PropertyType.TEXT, "The manager adds a property of the chosen type");
        ((JButton) named(manager, "property.hide." + room.id())).doClick();
        check(props.property(room.id()).hidden(), "and hides it from the table");
        var menu = manager.menu(props.property(room.id()));
        for (var name : List.of("rename", "retype", "rescope", "up", "delete"))
            check(item(menu, "property." + name + "." + room.id()) != null && item(menu, "property." + name + "." + room.id()).isEnabled(),
                "A property's menu offers " + name);
        check(!item(menu, "property.options." + room.id()).isEnabled() && !item(menu, "property.down." + room.id()).isEnabled(),
            "Options are for selects only, and the last cannot move down");
        ((JTextField) named(manager, "status.name")).setText("Review");
        ((JComboBox<?>) named(manager, "status.group")).setSelectedItem(TaskStatus.DOING);
        ((JButton) named(manager, "status.add")).doClick();
        var review = t.state().database().statuses().getLast();
        check(review.name().equals("Review") && review.group() == TaskStatus.DOING, "The manager adds a status to the chosen group");
        var statusMenu = manager.statusMenu(review);
        for (var name : List.of("rename", "colour", "delete"))
            check(item(statusMenu, "status." + name + "." + review.id()) != null, "A status's menu offers " + name);
        var options = new PropertyManager.OptionEditor(t, difficulty.id(), () -> { });
        ((JTextField) named(options, "option.name")).setText("Medium");
        ((JButton) named(options, "option.add")).doClick();
        check(props.property(difficulty.id()).options().stream().anyMatch(o -> o.name().equals("Medium")), "The option editor adds an option");
        check(named(options, "option.more." + hard.id()) instanceof JButton, "and each option has its menu");

        // Every theme draws the manager and the options.
        for (var theme : ThemeId.values()) {
            Theme.apply(theme);
            for (Container panel : List.of(new PropertyManager(t, () -> { }), new PropertyManager.OptionEditor(t, difficulty.id(), () -> { }))) {
                panel.setSize(700, 1200);
                layout(panel);
                panel.paint(new BufferedImage(700, 1200, BufferedImage.TYPE_INT_RGB).getGraphics());
                check(panel.getPreferredSize().height > 0, "The manager lays out in " + theme);
            }
        }
        Theme.apply(ThemeId.MIDNIGHT);
    }
}
