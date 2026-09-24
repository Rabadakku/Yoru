package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import dev.yoru.persistence.PortableVault;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Tasks as a database (#68): priority, the owner's own statuses inside the
 * three groups, and properties of every type — made, set, changed, converted
 * and deleted, with the vault and the export keeping all of it.
 */
public final class TaskPropertiesTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private interface Action { void run() throws Exception; }
    private static void refuses(Action action, String why) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IOException expected) { checks++; return; }
        throw new AssertionError("Expected a refusal: " + why);
    }
    private static final class Memory implements Repository {
        State state = State.empty(); boolean failSave, failBackup; int saves, backups;
        public State load() { return state; }
        public void save(State next) throws IOException { if (failSave) throw new IOException("Synthetic save failure"); state = next; saves++; }
        public void backup() throws IOException { if (failBackup) throw new IOException("Synthetic backup failure"); backups++; }
        public void close() { }
    }

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;

    private static Task task(Tracker t, UUID id) { return t.state().tasks().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(); }
    private static Task invented(String title, int order) {
        return new Task(UUID.randomUUID(), null, List.of(), title, "", LocalDate.of(2026, 10, 9), TaskStatus.TODO,
            "Manual entry", NOW.minusSeconds(3600), order, null, List.of());
    }

    public static void main(String[] args) throws Exception {
        domain();
        var clock = new MutableClock(NOW);
        var repo = new Memory();
        var t = new Tracker(repo, clock);
        var props = t.properties();
        var essay = invented("Invented essay", 0);
        var reading = invented("Invented reading", 1);
        t.addTasks(List.of(essay, reading));
        check(task(t, essay.id()).details().equals(Details.NONE), "A new task has no database details, and is not stamped");
        check(task(t, essay.id()).edited().equals(essay.createdAt()), "Until it changes, it was last edited when it was made");

        // Priority.
        clock.now = NOW.plusSeconds(60);
        props.setPriority(essay.id(), Priority.HIGH);
        check(task(t, essay.id()).priority() == Priority.HIGH, "A task has a priority");
        check(task(t, essay.id()).edited().equals(NOW.plusSeconds(60)), "and changing it stamps the task as edited");
        int saves = repo.saves;
        props.setPriority(essay.id(), Priority.HIGH);
        check(repo.saves == saves, "Setting the priority it has writes nothing");
        t.reorderTasks(List.of(reading.id(), essay.id()));
        check(task(t, essay.id()).edited().equals(NOW.plusSeconds(60)), "Reordering is not an edit");
        t.editTasks(List.of(essay.id(), reading.id()), new TaskBatch.Prioritize(Priority.LOW), ZONE);
        check(task(t, essay.id()).priority() == Priority.LOW && task(t, reading.id()).priority() == Priority.LOW,
            "A selection is given one priority at once");

        // Properties of every type.
        var summary = props.addProperty("Summary", PropertyType.TEXT, null);
        var effort = props.addProperty("Effort", PropertyType.NUMBER, null);
        var difficulty = props.addProperty("Difficulty", PropertyType.SELECT, null);
        var skills = props.addProperty("Skills", PropertyType.MULTI_SELECT, null);
        var handedIn = props.addProperty("Handed in", PropertyType.DATE, null);
        var printed = props.addProperty("Printed", PropertyType.CHECKBOX, null);
        var link = props.addProperty("Link", PropertyType.URL, null);
        var made = props.addProperty("Made", PropertyType.CREATED, null);
        check(t.state().database().properties().size() == 8, "Every type of property can be made");
        refuses(() -> props.addProperty("effort", PropertyType.TEXT, null), "a second property with the same name, whatever its case");
        refuses(() -> props.addProperty("Orphan", PropertyType.TEXT, UUID.randomUUID()), "a property for a list that does not exist");
        var easy = props.addOption(difficulty.id(), "Easy");
        var hard = props.addOption(difficulty.id(), "Hard");
        var read = props.addOption(skills.id(), "Reading");
        var write = props.addOption(skills.id(), "Writing");
        refuses(() -> props.addOption(effort.id(), "Many"), "an option on a number");
        refuses(() -> props.addOption(difficulty.id(), "easy"), "an option named like another");
        check(easy.colour() != hard.colour(), "Each new option takes the next colour");

        props.setValue(essay.id(), summary.id(), new Value.Text("  Two sections  "));
        props.setValue(essay.id(), effort.id(), new Value.Amount(new BigDecimal("2.50")));
        props.setValue(essay.id(), difficulty.id(), new Value.Choice(hard.id()));
        props.setValue(essay.id(), skills.id(), new Value.Choices(List.of(write.id(), read.id(), write.id())));
        props.setValue(essay.id(), handedIn.id(), new Value.Day(LocalDate.of(2026, 10, 12)));
        props.setValue(essay.id(), printed.id(), new Value.Tick());
        props.setValue(essay.id(), link.id(), new Value.Text("https://example.com/brief"));
        var values = task(t, essay.id()).values();
        check(values.size() == 7, "A task holds a value for each stored property");
        check(values.get(summary.id()).equals(new Value.Text("Two sections")), "Text is kept without its surrounding space");
        check(values.get(effort.id()).equals(new Value.Amount(new BigDecimal("2.5"))), "2.50 and 2.5 are the same number");
        check(((Value.Choices) values.get(skills.id())).options().equals(List.of(write.id(), read.id())),
            "A multi-select keeps its options once each, in the order chosen");
        refuses(() -> props.setValue(essay.id(), effort.id(), new Value.Text("three")), "text in a number");
        refuses(() -> props.setValue(essay.id(), difficulty.id(), new Value.Choice(read.id())), "another property's option");
        refuses(() -> props.setValue(essay.id(), link.id(), new Value.Text("not a link")), "a link with spaces in it");
        refuses(() -> props.setValue(essay.id(), made.id(), new Value.Day(LocalDate.of(2026, 1, 1))), "a value for the created time");
        refuses(() -> props.setValue(UUID.randomUUID(), summary.id(), new Value.Text("x")), "a task that does not exist");
        props.setValue(essay.id(), printed.id(), null);
        check(!task(t, essay.id()).values().containsKey(printed.id()), "A value is cleared");

        // Renaming, scoping, hiding and ordering lose nothing.
        var list = t.addList("Invented school", 0x6E8FD6);
        props.renameProperty(effort.id(), "Hours");
        props.scopeProperty(handedIn.id(), list.id());
        props.hideProperty(summary.id(), true);
        props.moveProperty(link.id(), -1);
        var names = t.state().database().properties().stream().map(Property::name).toList();
        check(names.equals(List.of("Summary", "Hours", "Difficulty", "Skills", "Handed in", "Link", "Printed", "Made")),
            "A property is renamed and moved: " + names);
        check(props.property(handedIn.id()).listId().equals(list.id()) && props.property(summary.id()).hidden(),
            "A property is kept for one list, and hidden from the table");
        check(task(t, essay.id()).values().size() == 6, "and no value is lost by any of it");
        saves = repo.saves;
        props.moveProperty(summary.id(), -1);
        props.hideProperty(summary.id(), true);
        props.renameProperty(effort.id(), "Hours");
        check(repo.saves == saves, "Moving the first up, hiding the hidden and renaming to its own name write nothing");

        // A list deleted takes nothing with it: its property belongs to every task.
        t.deleteList(list.id(), false);
        check(props.property(handedIn.id()).listId() == null, "Deleting a list keeps its properties, for every task");

        // Options.
        props.renameOption(difficulty.id(), hard.id(), "Tough");
        props.moveOption(difficulty.id(), hard.id(), -1);
        props.recolourOption(difficulty.id(), easy.id(), 0x123456);
        var p = props.property(difficulty.id());
        check(p.options().getFirst().name().equals("Tough") && p.option(easy.id()).colour() == 0x123456, "An option is renamed, moved and recoloured");
        props.setValue(reading.id(), skills.id(), new Value.Choices(List.of(read.id())));
        int backups = repo.backups;
        props.deleteOption(skills.id(), read.id());
        check(repo.backups == backups + 1, "Deleting an option some task has is backed up first");
        check(((Value.Choices) task(t, essay.id()).values().get(skills.id())).options().equals(List.of(write.id())),
            "It comes off a multi-select that had others");
        check(!task(t, reading.id()).values().containsKey(skills.id()), "and a multi-select left with none has no value");
        backups = repo.backups;
        props.deleteOption(difficulty.id(), easy.id());
        check(repo.backups == backups, "Deleting an option nobody has needs no backup");

        // Type changes, carrying across what can be carried.
        props.setValue(reading.id(), summary.id(), new Value.Text("3"));
        check(props.lostByChangingType(summary.id(), PropertyType.NUMBER) == 1, "One of two texts reads as a number");
        backups = repo.backups;
        props.changeType(summary.id(), PropertyType.NUMBER);
        check(repo.backups == backups + 1, "A type change that loses a value is backed up first");
        check(task(t, reading.id()).values().get(summary.id()).equals(new Value.Amount(BigDecimal.valueOf(3)))
            && !task(t, essay.id()).values().containsKey(summary.id()), "\"3\" becomes 3, and \"Two sections\" is dropped");
        props.changeType(summary.id(), PropertyType.TEXT);
        check(task(t, reading.id()).values().get(summary.id()).equals(new Value.Text("3")), "and a number becomes text again");
        props.changeType(summary.id(), PropertyType.SELECT);
        var three = props.property(summary.id()).options();
        check(three.size() == 1 && three.getFirst().name().equals("3")
            && task(t, reading.id()).values().get(summary.id()).equals(new Value.Choice(three.getFirst().id())),
            "Text becomes a select whose options are named by it");
        props.changeType(summary.id(), PropertyType.MULTI_SELECT);
        check(props.property(summary.id()).options().equals(three)
            && task(t, reading.id()).values().get(summary.id()).equals(new Value.Choices(List.of(three.getFirst().id()))),
            "A select becomes a multi-select, keeping its options");
        props.changeType(skills.id(), PropertyType.TEXT);
        check(task(t, essay.id()).values().get(skills.id()).equals(new Value.Text("Writing")), "Options become their names as text");
        props.changeType(effort.id(), PropertyType.CHECKBOX);
        check(task(t, essay.id()).values().get(effort.id()).equals(new Value.Tick()), "A number that is not zero ticks a checkbox");
        props.changeType(handedIn.id(), PropertyType.TEXT);
        props.changeType(handedIn.id(), PropertyType.DATE);
        check(task(t, essay.id()).values().get(handedIn.id()).equals(new Value.Day(LocalDate.of(2026, 10, 12))),
            "A date written as text reads back as the date");
        check(props.lostByChangingType(link.id(), PropertyType.CREATED) == 1, "A read-only type keeps no values");

        // Deleting a property.
        backups = repo.backups;
        props.deleteProperty(made.id());
        check(repo.backups == backups, "Deleting a property nobody has a value for needs no backup");
        props.deleteProperty(link.id());
        check(repo.backups == backups + 1 && !task(t, essay.id()).values().containsKey(link.id()),
            "Deleting one with values backs up first and takes its values off every task");

        // Statuses of the owner's.
        var waiting = props.addStatus("Waiting", TaskStatus.TODO);
        var review = props.addStatus("Review", TaskStatus.DOING);
        var archived = props.addStatus("Archived", TaskStatus.DONE);
        refuses(() -> props.addStatus("to do", TaskStatus.DOING), "a status named like a group's own");
        refuses(() -> props.addStatus("review", TaskStatus.TODO), "a status named like another");
        var order = props.statusChoices().stream().map(TaskProperties.StatusChoice::label).toList();
        check(order.equals(List.of("To do", "Waiting", "Doing", "Review", "Done", "Archived")),
            "Statuses step group by group, each group's own first: " + order);
        props.setStatus(essay.id(), props.statusChoices().get(1), ZONE);
        check(task(t, essay.id()).status() == TaskStatus.TODO && waiting.id().equals(task(t, essay.id()).statusId()),
            "A task is given a status of the owner's, inside its group");
        check(props.nextStatus(task(t, essay.id())).label().equals("Doing"), "and its next status is the next in order");
        props.setStatus(essay.id(), props.statusChoices().get(3), ZONE);
        check(task(t, essay.id()).status() == TaskStatus.DOING && review.id().equals(task(t, essay.id()).statusId()),
            "Another group's status changes the group too");
        t.taskStatus(essay.id(), TaskStatus.DONE, ZONE);
        check(task(t, essay.id()).statusId() == null, "Ticking it done leaves the owner's Doing status behind");
        check(props.nextStatus(task(t, essay.id())).label().equals("Archived"), "Done steps on to the owner's Done status");
        props.setStatus(essay.id(), props.nextStatus(task(t, essay.id())), ZONE);
        check(props.nextStatus(task(t, essay.id())).label().equals("To do"), "and the last wraps round to the first");
        refuses(() -> props.setStatus(essay.id(), new TaskProperties.StatusChoice(TaskStatus.TODO, review), ZONE),
            "a status in a group it does not belong to");
        props.renameStatus(waiting.id(), "Waiting on reply");
        props.moveStatus(archived.id(), -1);
        var another = props.addStatus("Blocked", TaskStatus.TODO);
        props.moveStatus(another.id(), -1);
        check(props.statusChoices().stream().map(TaskProperties.StatusChoice::label).toList()
            .equals(List.of("To do", "Blocked", "Waiting on reply", "Doing", "Review", "Done", "Archived")),
            "A status is renamed, and moved only among its own group");
        backups = repo.backups;
        props.deleteStatus(archived.id());
        check(repo.backups == backups + 1 && task(t, essay.id()).statusId() == null && task(t, essay.id()).done(),
            "Deleting a status in use backs up first, and its tasks keep their group");

        // A repeating task finished by a Done status moves on to its next date.
        var laundry = invented("Invented laundry", 2).withRepeat(Repeat.weekly(1, EnumSet.of(DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 9)));
        t.addTask(laundry);
        var shelved = props.addStatus("Shelved", TaskStatus.DONE);
        props.setStatus(laundry.id(), new TaskProperties.StatusChoice(TaskStatus.DONE, shelved), ZONE);
        check(task(t, laundry.id()).due().equals(LocalDate.of(2026, 10, 16)) && task(t, laundry.id()).status() == TaskStatus.TODO
            && task(t, laundry.id()).statusId() == null, "A Done status finishes this occurrence of a repeating task, as the checkbox does");

        // Failures.
        var before = t.state();
        repo.failSave = true;
        refuses(() -> props.setPriority(reading.id(), Priority.URGENT), "the save failed");
        refuses(() -> props.addProperty("Never", PropertyType.TEXT, null), "the save failed");
        refuses(() -> props.deleteProperty(effort.id()), "the save failed");
        repo.failSave = false;
        repo.failBackup = true;
        refuses(() -> props.deleteProperty(effort.id()), "the backup failed");
        repo.failBackup = false;
        check(t.state().equals(before), "Failed saves and backups change nothing");

        // Reset: properties and statuses go, priorities stay; lists going keeps their properties.
        roundTrips(t.state());
        t.reset(EnumSet.of(Tracker.ResetPart.PROPERTIES));
        check(t.state().database().equals(TaskDatabase.EMPTY) && t.state().tasks().stream().allMatch(x -> x.values().isEmpty() && x.statusId() == null),
            "Resetting properties clears them and their values");
        check(task(t, reading.id()).priority() == Priority.LOW, "and priorities, which are the task's own, stay");

        // Restoring an export keeps the edited times it carries.
        var exported = PortableVault.parse(PortableVault.export(before, NOW));
        clock.now = NOW.plusSeconds(86_400);
        t.restore(exported);
        check(task(t, essay.id()).edited().equals(task(exported, essay.id()).edited()), "An import is not an edit");

        System.out.println("PASS: " + checks + " task property checks (priority, statuses, every property type, options, type changes, deletion, reset, vault and export)");
    }

    private static Task task(State state, UUID id) { return state.tasks().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(); }

    private static void domain() throws Exception {
        refuses(() -> new Value.Text("   "), "blank text");
        refuses(() -> new Value.Text("x".repeat(Value.Text.MAX + 1)), "text too long");
        refuses(() -> new Value.Amount(new BigDecimal("1e15")), "a number too large");
        refuses(() -> new Value.Amount(new BigDecimal("0.00000000001")), "a number too fine");
        check(new Value.Amount(new BigDecimal("0.00")).equals(new Value.Amount(BigDecimal.ZERO)), "Zero is zero however it is written");
        refuses(() -> new Value.Choices(List.of()), "a multi-select with nothing chosen");
        refuses(() -> new Value.Day(LocalDate.of(1850, 1, 1)), "a date outside the years a vault keeps");
        var option = new PropertyOption(UUID.randomUUID(), "One", 0x112233);
        refuses(() -> new Property(UUID.randomUUID(), "Count", PropertyType.NUMBER, List.of(option), null, false), "options on a number");
        refuses(() -> new Property(UUID.randomUUID(), "Pick", PropertyType.SELECT,
            List.of(option, new PropertyOption(UUID.randomUUID(), "one", 0)), null, false), "two options named alike");
        refuses(() -> new PropertyOption(UUID.randomUUID(), "One", 0x1000000), "a colour that is not 24-bit");
        var select = new Property(UUID.randomUUID(), "Pick", PropertyType.SELECT, List.of(option), null, false);
        check(select.accepts(new Value.Choice(option.id())) && !select.accepts(new Value.Choice(UUID.randomUUID()))
            && !select.accepts(new Value.Text("One")), "A select accepts only its own options");
        refuses(() -> new TaskDatabase(List.of(new StatusOption(UUID.randomUUID(), "Done", TaskStatus.TODO, 0)), List.of()),
            "a status named like a group's own");
        var lonely = new Property(UUID.randomUUID(), "Lonely", PropertyType.TEXT, List.of(), UUID.randomUUID(), false);
        refuses(() -> State.empty().withDatabase(new TaskDatabase(List.of(), List.of(lonely))), "a property for a list that is not there");
        var task = invented("Invented", 0);
        refuses(() -> State.empty().withTasks(List.of(task.withValue(UUID.randomUUID(), new Value.Tick()))), "a value for no property");
        var review = new StatusOption(UUID.randomUUID(), "Review", TaskStatus.DOING, 0);
        refuses(() -> State.empty().withDatabase(new TaskDatabase(List.of(review), List.of()))
            .withTasks(List.of(task.withDetails(Details.NONE.withStatus(review.id())))), "a Doing status on a To do task");
    }

    private static void roundTrips(State state) throws Exception {
        check(!state.database().properties().isEmpty() && state.tasks().stream().anyMatch(x -> !x.values().isEmpty()),
            "The state carries properties and values to round-trip");
        check(PortableVault.parse(PortableVault.export(state, NOW)).equals(state), "Properties, statuses and values survive the portable export");
        var dir = Files.createTempDirectory("yoru-task-properties");
        try {
            var file = dir.resolve("synthetic.vault");
            try (var vault = new EncryptedVault(file, "fixture-password".toCharArray())) { vault.save(state); }
            try (var vault = new EncryptedVault(file, "fixture-password".toCharArray())) {
                check(vault.load().equals(state), "and reopening the vault");
            }
        } finally {
            try (var files = Files.walk(dir)) { for (var f : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(f); }
        }
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
