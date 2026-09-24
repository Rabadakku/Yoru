package dev.yoru.application;

import dev.yoru.domain.Model.*;
import java.time.*;
import java.util.*;

/** Builds a complete batch before Tracker backs it up and commits it (#59). */
public final class TaskBatch {
    private TaskBatch() { }

    public sealed interface Change permits Status, Move, Due, Planned, Tags, Prioritize { }
    /** One priority for the whole selection (#68). */
    public record Prioritize(Priority value) implements Change {
        public Prioritize { Objects.requireNonNull(value); }
    }
    public record Status(TaskStatus value) implements Change {
        public Status { Objects.requireNonNull(value); }
    }
    /** Null files the selection in the Inbox. */
    public record Move(UUID listId) implements Change { }
    /** Null clears the deadline, unless one of the tasks repeats. */
    public record Due(LocalDate value) implements Change { }
    /** Null clears the planned day. */
    public record Planned(LocalDate value) implements Change { }
    public enum TagMode {
        ADD("Add tags"), REMOVE("Remove tags"), REPLACE("Replace all tags");
        private final String label;
        TagMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record Tags(TagMode mode, List<UUID> ids, List<Tag> created) implements Change {
        public Tags {
            Objects.requireNonNull(mode);
            ids = List.copyOf(new LinkedHashSet<>(ids));
            created = List.copyOf(created);
            if (mode == TagMode.REMOVE && !created.isEmpty())
                throw new IllegalArgumentException("Choose existing tags to remove.");
            for (var tag : created) if (!ids.contains(tag.id()))
                throw new IllegalArgumentException("New tags must be selected.");
        }
    }

    static State edit(State state, Collection<UUID> selected, Change change, Instant now, ZoneId zone) {
        var ids = selected(state, selected);
        Objects.requireNonNull(change);
        var tags = new ArrayList<>(state.tags());
        if (change instanceof Tags edit) {
            for (var tag : edit.created()) {
                if (tags.stream().anyMatch(t -> t.id().equals(tag.id()) || t.name().equalsIgnoreCase(tag.name())))
                    throw new IllegalArgumentException("The tag “" + tag.name() + "” already exists.");
                tags.add(tag);
            }
            var known = new HashSet<>(tags.stream().map(Tag::id).toList());
            if (!known.containsAll(edit.ids())) throw new IllegalArgumentException("A selected tag no longer exists.");
        }
        if (change instanceof Move move && move.listId() != null
            && state.lists().stream().noneMatch(list -> list.id().equals(move.listId())))
            throw new IllegalArgumentException("The selected list no longer exists.");
        var tasks = state.tasks().stream().map(task -> {
            if (!ids.contains(task.id())) return task;
            return switch (change) {
                case Status edit -> status(task, edit.value(), state, now, zone);
                case Move edit -> task.withList(edit.listId());
                case Due edit -> task.withDates(edit.value(), task.plannedFor());
                case Planned edit -> task.withDates(task.due(), edit.value());
                case Prioritize edit -> task.withPriority(edit.value());
                case Tags edit -> {
                    var values = new LinkedHashSet<>(task.tagIds());
                    switch (edit.mode()) {
                        case ADD -> values.addAll(edit.ids());
                        case REMOVE -> values.removeAll(edit.ids());
                        case REPLACE -> { values.clear(); values.addAll(edit.ids()); }
                    }
                    yield task.withTags(List.copyOf(values));
                }
            };
        }).toList();
        return state.withTags(tags).withTasks(tasks);
    }

    static State delete(State state, Collection<UUID> selected) {
        var ids = selected(state, selected);
        return state.withTasks(state.tasks().stream().filter(task -> !ids.contains(task.id())).toList());
    }

    /** Refuse stale or empty selections before either a backup or a write. */
    private static Set<UUID> selected(State state, Collection<UUID> selected) {
        var ids = Set.copyOf(selected);
        if (ids.isEmpty()) throw new IllegalArgumentException("Select at least one task.");
        var known = new HashSet<>(state.tasks().stream().map(Task::id).toList());
        if (!known.containsAll(ids)) throw new IllegalArgumentException("A selected task no longer exists. Select the tasks again.");
        return ids;
    }

    static Task status(Task task, TaskStatus next, State state, Instant now, ZoneId zone) {
        if (next == TaskStatus.DONE && task.repeats() && !task.done())
            return advance(task, false, state, now, zone);
        return task.withStatus(next);
    }

    /** Shared with single-task completion/skip so a batch cannot bypass recurrence. */
    static Task advance(Task task, boolean skipped, State state, Instant now, ZoneId zone) {
        var today = LocalDate.ofInstant(now, zone);
        var behind = new Occurrence(task.due(), now, skipped);
        var next = Repeats.following(task.repeat(), task.due(), today, state.settings().weekStartsOn(), task.history().size() + 1);
        return next == null ? task.advanced(behind, task.due(), TaskStatus.DONE)
            : task.advanced(behind, next, TaskStatus.TODO);
    }
}
