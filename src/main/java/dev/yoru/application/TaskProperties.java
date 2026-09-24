package dev.yoru.application;

import dev.yoru.domain.Model.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Tasks as a database (#68): priority, the owner's own statuses, and the
 * properties they define, each change one whole-vault write.
 *
 * A change that loses what a task holds — deleting a property, an option or
 * a status some task has, or a type change that cannot carry every value
 * across — backs the vault up first, like every other deletion. Adding,
 * renaming, reordering and hiding lose nothing and need no backup.
 */
public final class TaskProperties {
    /**
     * The colours new options and statuses take in turn: the tag palette, so a
     * property's options and a task's tags are drawn from the same eight.
     */
    public static final int[] COLOURS = {0x90D8DA, 0xE8B24C, 0xD9736A, 0xA98BD4, 0x6E8FD6, 0x6FBF8B, 0xD98CB4, 0xC9C273};

    private final Tracker tracker;

    TaskProperties(Tracker tracker) { this.tracker = tracker; }

    private State state() { return tracker.state(); }
    private TaskDatabase database() { return state().database(); }

    /** The palette's colours in turn, so each new option starts on a different one. */
    public static int colour(int n) { return COLOURS[Math.floorMod(n, COLOURS.length)]; }

    // ------------------------------------------------------------ reading

    public Property property(UUID id) {
        var found = database().property(id);
        if (found == null) throw new IllegalArgumentException("That property no longer exists.");
        return found;
    }

    public StatusOption status(UUID id) {
        var found = database().status(id);
        if (found == null) throw new IllegalArgumentException("That status no longer exists.");
        return found;
    }

    /** How many tasks hold a value for this property. */
    public int uses(UUID property) {
        return (int) state().tasks().stream().filter(t -> t.values().containsKey(property)).count();
    }

    /** How many tasks hold this option of this property. */
    public int uses(UUID property, UUID option) {
        return (int) state().tasks().stream().filter(t -> holds(t.values().get(property), option)).count();
    }

    /** How many tasks have this status of the owner's. */
    public int usesStatus(UUID status) {
        return (int) state().tasks().stream().filter(t -> status.equals(t.statusId())).count();
    }

    private static boolean holds(Value value, UUID option) {
        return value instanceof Value.Choice c && c.option().equals(option)
            || value instanceof Value.Choices cs && cs.options().contains(option);
    }

    /**
     * Every status a task can have, in the order the status button steps
     * through them: each group's own status, then the owner's in that group.
     */
    public List<StatusChoice> statusChoices() {
        var out = new ArrayList<StatusChoice>();
        for (var group : TaskStatus.values()) {
            out.add(new StatusChoice(group, null));
            for (var own : database().statusesIn(group)) out.add(new StatusChoice(group, own));
        }
        return out;
    }

    /** A status as a task has it: its group, and the owner's status inside it or null for the group's own. */
    public record StatusChoice(TaskStatus group, StatusOption option) {
        public StatusChoice { Objects.requireNonNull(group); }
        public UUID id() { return option == null ? null : option.id(); }
        public String label() { return option == null ? group.label : option.name(); }
        @Override public String toString() { return label(); }
    }

    /** The status a task has, as a choice. */
    public StatusChoice statusOf(Task task) {
        var own = task.statusId() == null ? null : database().status(task.statusId());
        return new StatusChoice(task.status(), own);
    }

    /** The status after this task's in the order {@link #statusChoices} gives, wrapping round. */
    public StatusChoice nextStatus(Task task) {
        var all = statusChoices();
        var at = all.indexOf(statusOf(task));
        return all.get((at + 1) % all.size());
    }

    // ------------------------------------------------------------ properties

    public Property addProperty(String name, PropertyType type, UUID listId) throws IOException {
        if (listId != null && state().lists().stream().noneMatch(l -> l.id().equals(listId)))
            throw new IllegalArgumentException("That list no longer exists.");
        var created = new Property(UUID.randomUUID(), name, type, List.of(), listId, false);
        var next = new ArrayList<>(database().properties());
        next.add(created);
        tracker.commit(state().withDatabase(database().withProperties(next)));
        return created;
    }

    public void renameProperty(UUID id, String name) throws IOException {
        replace(property(id).withName(name));
    }

    /** Keeps a property for one list's tasks, or for every task with null. Its values stay either way. */
    public void scopeProperty(UUID id, UUID listId) throws IOException {
        if (listId != null && state().lists().stream().noneMatch(l -> l.id().equals(listId)))
            throw new IllegalArgumentException("That list no longer exists.");
        var property = property(id);
        if (Objects.equals(property.listId(), listId)) return;
        replace(property.withList(listId));
    }

    /** Keeps a property out of the table's columns, or puts it back; the task still has it. */
    public void hideProperty(UUID id, boolean hidden) throws IOException {
        var property = property(id);
        if (property.hidden() == hidden) return;
        replace(property.withHidden(hidden));
    }

    /** Swaps a property with its neighbour; at either end nothing is written. */
    public void moveProperty(UUID id, int direction) throws IOException {
        var next = new ArrayList<>(database().properties());
        int at = indexOf(next, property(id));
        int to = at + Integer.signum(requireDirection(direction));
        if (to < 0 || to >= next.size()) return;
        Collections.swap(next, at, to);
        tracker.commit(state().withDatabase(database().withProperties(next)));
    }

    /** Deletes a property and every value tasks hold for it, after a backup when there are any. */
    public void deleteProperty(UUID id) throws IOException {
        var property = property(id);
        var tasks = state().tasks().stream()
            .map(t -> t.values().containsKey(id) ? t.withValue(id, null) : t).toList();
        if (uses(id) > 0) tracker.backup();
        var properties = database().properties().stream().filter(p -> !p.id().equals(property.id())).toList();
        tracker.commit(state().withTasks(tasks).withDatabase(database().withProperties(properties)));
    }

    /** How many tasks would lose their value if this property became {@code next}. */
    public int lostByChangingType(UUID id, PropertyType next) {
        var property = property(id);
        var converter = new Converter(property, next);
        int lost = 0;
        for (var task : state().tasks()) {
            var value = task.values().get(id);
            if (value != null && converter.convert(value) == null) lost++;
        }
        return lost;
    }

    /**
     * Changes a property's type, carrying every value across that can be:
     * text into numbers where it reads as one, into select options named by
     * it, into dates where it is one; anything into text. What cannot be
     * carried is dropped, after a backup, and {@link #lostByChangingType}
     * says how much that is before anyone chooses.
     */
    public void changeType(UUID id, PropertyType next) throws IOException {
        var property = property(id);
        if (property.type() == Objects.requireNonNull(next)) return;
        var converter = new Converter(property, next);
        int lost = 0;
        var tasks = new ArrayList<Task>();
        for (var task : state().tasks()) {
            var value = task.values().get(id);
            if (value == null) { tasks.add(task); continue; }
            var carried = converter.convert(value);
            if (carried == null) lost++;
            tasks.add(task.withValue(id, carried));
        }
        var changed = property.withType(next, converter.options());
        var properties = database().properties().stream().map(p -> p.id().equals(id) ? changed : p).toList();
        if (lost > 0) tracker.backup();
        tracker.commit(state().withTasks(tasks, database().withProperties(properties)));
    }

    /** What one type's values become in another. */
    private static final class Converter {
        private final Property from;
        private final PropertyType to;
        private final List<PropertyOption> options = new ArrayList<>();

        Converter(Property from, PropertyType to) {
            this.from = from;
            this.to = to;
            // A select keeping to select, or multi-select, keeps its options.
            if (to.hasOptions() && from.type().hasOptions()) options.addAll(from.options());
        }

        List<PropertyOption> options() { return to.hasOptions() ? List.copyOf(options) : List.of(); }

        /** The words a value would be written as, or several for a multi-select. */
        private List<String> words(Value value) {
            return switch (value) {
                case Value.Text t -> List.of(t.text());
                case Value.Amount a -> List.of(a.amount().toPlainString());
                case Value.Choice c -> List.of(name(c.option()));
                case Value.Choices cs -> cs.options().stream().map(this::name).toList();
                case Value.Day d -> List.of(d.date().toString());
                case Value.Tick ignored -> List.of("Yes");
            };
        }

        private String name(UUID option) {
            var found = from.option(option);
            return found == null ? "" : found.name();
        }

        private UUID option(String name) {
            String clean = name.strip();
            if (clean.isEmpty()) return null;
            if (clean.length() > 60) clean = clean.substring(0, 60).strip();
            for (var o : options) if (o.name().equalsIgnoreCase(clean)) return o.id();
            if (options.size() >= Property.MAX_OPTIONS) return null;
            var made = new PropertyOption(UUID.randomUUID(), clean, colour(options.size()));
            options.add(made);
            return made.id();
        }

        Value convert(Value value) {
            if (from.type() == to && !(value instanceof Value.Choice || value instanceof Value.Choices)) return value;
            var words = words(value).stream().filter(w -> !w.isBlank()).toList();
            if (words.isEmpty()) return null;
            String joined = String.join(", ", words);
            try {
                return switch (to) {
                    case TEXT -> new Value.Text(joined.length() > Value.Text.MAX ? joined.substring(0, Value.Text.MAX) : joined);
                    case URL -> joined.chars().anyMatch(Character::isWhitespace) ? null : new Value.Text(joined);
                    case NUMBER -> value instanceof Value.Tick ? new Value.Amount(BigDecimal.ONE)
                        : new Value.Amount(new BigDecimal(joined.replace(",", "").replace("_", "")));
                    case SELECT -> {
                        var id = option(words.getFirst());
                        yield id == null ? null : new Value.Choice(id);
                    }
                    case MULTI_SELECT -> {
                        var ids = words.stream().map(this::option).filter(Objects::nonNull).toList();
                        yield ids.isEmpty() ? null : new Value.Choices(ids);
                    }
                    case DATE -> value instanceof Value.Day day ? day : new Value.Day(LocalDate.parse(words.getFirst()));
                    case CHECKBOX -> {
                        if (value instanceof Value.Tick) yield value;
                        if (value instanceof Value.Amount a) yield a.amount().signum() != 0 ? new Value.Tick() : null;
                        var word = words.getFirst().toLowerCase(Locale.ROOT);
                        yield Set.of("yes", "true", "x", "✓", "done", "1").contains(word) ? new Value.Tick() : null;
                    }
                    case CREATED, EDITED -> null;
                };
            } catch (RuntimeException unreadable) {
                // A word that is not a number or a date, or one too long for
                // its new home: the value cannot be carried, and is counted.
                return null;
            }
        }
    }

    // ------------------------------------------------------------ options

    public PropertyOption addOption(UUID property, String name) throws IOException {
        var p = property(property);
        if (!p.type().hasOptions()) throw new IllegalArgumentException("Only a select property has options.");
        var made = new PropertyOption(UUID.randomUUID(), name, colour(p.options().size()));
        var next = new ArrayList<>(p.options());
        next.add(made);
        replace(p.withOptions(next));
        return made;
    }

    public void renameOption(UUID property, UUID option, String name) throws IOException {
        var p = property(property);
        var o = option(p, option);
        replace(p.withOptions(p.options().stream().map(x -> x.equals(o) ? new PropertyOption(o.id(), name, o.colour()) : x).toList()));
    }

    public void recolourOption(UUID property, UUID option, int colour) throws IOException {
        var p = property(property);
        var o = option(p, option);
        replace(p.withOptions(p.options().stream().map(x -> x.equals(o) ? new PropertyOption(o.id(), o.name(), colour) : x).toList()));
    }

    public void moveOption(UUID property, UUID option, int direction) throws IOException {
        var p = property(property);
        var next = new ArrayList<>(p.options());
        int at = next.indexOf(option(p, option));
        int to = at + Integer.signum(requireDirection(direction));
        if (to < 0 || to >= next.size()) return;
        Collections.swap(next, at, to);
        replace(p.withOptions(next));
    }

    /** Deletes an option, taking it off every task that has it; backed up first when any does. */
    public void deleteOption(UUID property, UUID option) throws IOException {
        var p = property(property);
        var o = option(p, option);
        var tasks = state().tasks().stream().map(t -> {
            var value = t.values().get(property);
            if (value instanceof Value.Choice c && c.option().equals(option)) return t.withValue(property, null);
            if (value instanceof Value.Choices cs && cs.options().contains(option)) {
                var kept = cs.options().stream().filter(x -> !x.equals(option)).toList();
                return t.withValue(property, kept.isEmpty() ? null : new Value.Choices(kept));
            }
            return t;
        }).toList();
        if (uses(property, option) > 0) tracker.backup();
        var changed = p.withOptions(p.options().stream().filter(x -> !x.equals(o)).toList());
        tracker.commit(state().withTasks(tasks)
            .withDatabase(database().withProperties(database().properties().stream().map(x -> x.id().equals(property) ? changed : x).toList())));
    }

    private static PropertyOption option(Property p, UUID option) {
        var found = p.option(option);
        if (found == null) throw new IllegalArgumentException("That option no longer exists.");
        return found;
    }

    // ------------------------------------------------------------ values

    /** One property's value on one task, or none for null. */
    public void setValue(UUID task, UUID property, Value value) throws IOException {
        var t = task(task);
        var p = property(property);
        if (!p.type().stored()) throw new IllegalArgumentException("\"" + p.name() + "\" is read from the task, not typed in.");
        if (value != null && !p.accepts(value)) throw new IllegalArgumentException("\"" + p.name() + "\" cannot hold that value.");
        if (Objects.equals(t.values().get(property), value)) return;
        tracker.updateTask(t.withValue(property, value));
    }

    public void setPriority(UUID task, Priority priority) throws IOException {
        var t = task(task);
        if (t.priority() == Objects.requireNonNull(priority)) return;
        tracker.updateTask(t.withPriority(priority));
    }

    /**
     * Gives a task one of the statuses {@link #statusChoices} lists. Choosing a
     * Done status finishes it, which moves a repeating task on to its next
     * date as the Done checkbox does (#57), at its group's own status.
     */
    public void setStatus(UUID task, StatusChoice choice, ZoneId zone) throws IOException {
        Objects.requireNonNull(choice);
        if (choice.option() != null) {
            var own = status(choice.option().id());
            if (own.group() != choice.group()) throw new IllegalArgumentException("\"" + own.name() + "\" is not a " + choice.group().label + " status.");
        }
        var t = task(task);
        var next = TaskBatch.status(t, choice.group(), state(), tracker.now(), zone);
        // A repeating task that moved on is at its next occurrence's own status.
        if (next.status() == choice.group() && next.history().size() == t.history().size())
            next = next.withDetails(next.details().withStatus(choice.id()));
        if (next.equals(t)) return;
        tracker.updateTask(next);
    }

    private Task task(UUID id) {
        return state().tasks().stream().filter(t -> t.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Task no longer exists."));
    }

    // ------------------------------------------------------------ statuses

    public StatusOption addStatus(String name, TaskStatus group) throws IOException {
        var made = new StatusOption(UUID.randomUUID(), name, group, colour(database().statuses().size() + 3));
        var next = new ArrayList<>(database().statuses());
        next.add(made);
        tracker.commit(state().withDatabase(database().withStatuses(next)));
        return made;
    }

    public void renameStatus(UUID id, String name) throws IOException {
        replace(status(id).withName(name));
    }

    public void recolourStatus(UUID id, int colour) throws IOException {
        replace(status(id).withColour(colour));
    }

    /** Swaps a status with its neighbour in the same group; at either end of the group nothing is written. */
    public void moveStatus(UUID id, int direction) throws IOException {
        var own = status(id);
        var all = new ArrayList<>(database().statuses());
        var group = all.stream().filter(s -> s.group() == own.group()).toList();
        int at = group.indexOf(own);
        int to = at + Integer.signum(requireDirection(direction));
        if (to < 0 || to >= group.size()) return;
        Collections.swap(all, all.indexOf(own), all.indexOf(group.get(to)));
        tracker.commit(state().withDatabase(database().withStatuses(all)));
    }

    /** Deletes a status; its tasks keep their group, at the group's own status. Backed up first when any has it. */
    public void deleteStatus(UUID id) throws IOException {
        var own = status(id);
        var tasks = state().tasks().stream()
            .map(t -> id.equals(t.statusId()) ? t.withDetails(t.details().withStatus(null)) : t).toList();
        if (usesStatus(id) > 0) tracker.backup();
        tracker.commit(state().withTasks(tasks)
            .withDatabase(database().withStatuses(database().statuses().stream().filter(s -> !s.equals(own)).toList())));
    }

    // ------------------------------------------------------------ plumbing

    private void replace(Property changed) throws IOException {
        var properties = database().properties().stream().map(p -> p.id().equals(changed.id()) ? changed : p).toList();
        var next = database().withProperties(properties);
        if (next.equals(database())) return;
        tracker.commit(state().withDatabase(next));
    }

    private void replace(StatusOption changed) throws IOException {
        var statuses = database().statuses().stream().map(s -> s.id().equals(changed.id()) ? changed : s).toList();
        var next = database().withStatuses(statuses);
        if (next.equals(database())) return;
        tracker.commit(state().withDatabase(next));
    }

    private static int indexOf(List<Property> properties, Property property) {
        for (int i = 0; i < properties.size(); i++) if (properties.get(i).id().equals(property.id())) return i;
        return -1;
    }

    private static int requireDirection(int direction) {
        if (direction == 0) throw new IllegalArgumentException("Choose a direction to move.");
        return direction;
    }
}
