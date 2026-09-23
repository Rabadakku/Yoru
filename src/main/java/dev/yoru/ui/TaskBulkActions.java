package dev.yoru.ui;

import dev.yoru.application.TaskBatch;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/** Selection and reviewed batch actions for the task table (#59). */
final class TaskBulkActions extends JPanel {
    private final Tracker tracker;
    private final Runnable changed;
    private final Consumer<Exception> error;
    private final BooleanSupplier closed;
    private final Set<UUID> selected = new LinkedHashSet<>();
    private List<Task> visible = List.of();
    private boolean active;
    private final JLabel count = label("", TYPE_LABEL, TEXT);
    private final JButton all, clear, edit, delete;

    TaskBulkActions(Tracker tracker, Runnable changed, Consumer<Exception> error, BooleanSupplier closed) {
        super(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        this.tracker = tracker;
        this.changed = changed;
        this.error = error;
        this.closed = closed;
        setOpaque(false);
        setName("tasks.bulk");
        count.setName("tasks.bulk.count");
        add(count);
        all = button("Select shown", () -> {
            visible.forEach(task -> selected.add(task.id()));
            changed.run();
        });
        all.setName("tasks.bulk.all");
        all.setToolTipText("Select only tasks shown by the current list and filters");
        add(all);
        clear = button("Clear", () -> { selected.clear(); changed.run(); });
        clear.setName("tasks.bulk.clear");
        clear.getAccessibleContext().setAccessibleName("Clear task selection");
        add(clear);
        edit = button("Edit ▾", () -> { });
        edit.setName("tasks.bulk.edit");
        edit.getAccessibleContext().setAccessibleName("Edit selected tasks");
        edit.addActionListener(e -> menu().show(edit, 0, edit.getHeight()));
        add(edit);
        delete = button("Delete…", this::delete);
        delete.setName("tasks.bulk.delete");
        delete.getAccessibleContext().setAccessibleName("Delete selected tasks");
        add(delete);
        setVisible(false);
    }

    boolean active() { return active; }
    boolean selected(UUID id) { return selected.contains(id); }
    Set<UUID> selection() { return Set.copyOf(selected); }
    void start() { active = true; }
    void stop() { active = false; selected.clear(); }
    void toggle(UUID id) {
        active = true;
        if (!selected.remove(id)) selected.add(id);
        changed.run();
    }

    /** Hidden or deleted tasks must never remain in the batch. */
    void update(List<Task> shown, boolean allowed) {
        visible = List.copyOf(shown);
        if (!allowed) stop();
        selected.retainAll(shown.stream().map(Task::id).toList());
        setVisible(active);
        // Unused selection controls do not need layout or keyboard targets.
        if (!active) removeAll();
        else if (getComponentCount() == 0) {
            add(count); add(all); add(clear); add(edit); add(delete);
        }
        count.setText(selected.isEmpty() ? "Select tasks to edit together" : selected.size() + " selected");
        all.setEnabled(!shown.isEmpty() && selected.size() < shown.size());
        clear.setEnabled(!selected.isEmpty());
        edit.setEnabled(!selected.isEmpty());
        delete.setEnabled(!selected.isEmpty());
    }

    JPopupMenu menu() {
        var menu = Menus.popup();
        for (var status : TaskStatus.values()) {
            var item = Menus.item("Mark as " + status.label, "tasks.bulk.status." + status.name(),
                !selected.isEmpty(), () -> apply(new TaskBatch.Status(status)), "Select tasks first");
            if (status == TaskStatus.DONE && !selected.isEmpty())
                item.setToolTipText("Repeating tasks record this occurrence and move to their next date.");
            menu.add(item);
        }
        menu.addSeparator();
        menu.add(Menus.item("Move to list…", "tasks.bulk.move", !selected.isEmpty(),
            () -> edit(new MoveForm(tracker.state()), "Move selected tasks", "Move"), "Select tasks first"));
        menu.add(Menus.item("Change due date…", "tasks.bulk.due", !selected.isEmpty(),
            () -> edit(new DateForm(false), "Set deadline for selected tasks", "Apply"), "Select tasks first"));
        menu.add(Menus.item("Change planned day…", "tasks.bulk.planned", !selected.isEmpty(),
            () -> edit(new DateForm(true), "Plan selected tasks", "Apply"), "Select tasks first"));
        menu.add(Menus.item("Change tags…", "tasks.bulk.tags", !selected.isEmpty(),
            () -> edit(new TagsForm(tracker.state()), "Tag selected tasks", "Apply"), "Select tasks first"));
        return menu;
    }

    /** On failure keep the selection so a retry applies to the same tasks. */
    boolean apply(TaskBatch.Change change) {
        if (closed.getAsBoolean()) return false;
        try {
            tracker.editTasks(selection(), change, ZoneId.systemDefault());
            stop();
            changed.run();
            return true;
        } catch (Exception failure) {
            error.accept(failure);
            return false;
        }
    }

    private void edit(EditForm form, String title, String action) {
        while (!closed.getAsBoolean() && Dialogs.confirm(this, form, title, action)) {
            if (closed.getAsBoolean()) return;
            try { if (apply(form.change())) return; }
            catch (IllegalArgumentException failure) { error.accept(failure); }
        }
    }

    private void delete() {
        var ids = selection();
        if (ids.isEmpty() || closed.getAsBoolean()) return;
        String message = "Delete " + ids.size() + " selected tasks?\n\n"
            + "Their notes, dates and repeat history go with them. Tracked time and linked pages are kept. "
            + "A vault backup is taken before deletion.";
        if (!Dialogs.confirmDestructive(this, message, "Delete selected tasks", "Delete")) return;
        if (closed.getAsBoolean()) return;
        try {
            tracker.deleteTasks(ids);
            stop();
            changed.run();
        } catch (Exception failure) { error.accept(failure); }
    }

    abstract static class EditForm extends JPanel {
        EditForm() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }
        @Override protected void addImpl(java.awt.Component component, Object constraints, int index) {
            if (component instanceof JComponent child) child.setAlignmentX(0);
            super.addImpl(component, constraints, index);
        }
        abstract TaskBatch.Change change();
    }

    static final class MoveForm extends EditForm {
        final JComboBox<Object> lists = plainCombo(new JComboBox<>());
        MoveForm(State state) {
            add(bodyLabel("Move every selected task to this list. Other task details stay the same."));
            lists.addItem("Inbox · no list");
            TaskLists.ordered(state).forEach(lists::addItem);
            lists.setName("tasks.bulk.list");
            lists.getAccessibleContext().setAccessibleName("Destination list");
            add(lists);
        }
        @Override TaskBatch.Change change() {
            return new TaskBatch.Move(lists.getSelectedItem() instanceof TaskList list ? list.id() : null);
        }
    }

    static final class DateForm extends EditForm {
        final DateField date;
        private final boolean planned;
        DateForm(boolean planned) {
            this.planned = planned;
            date = new DateField(null, planned ? "Plan for" : "Due", true);
            date.setName("tasks.bulk.date");
            add(bodyLabel(planned ? "Set the day to work on these tasks. Blank clears the planned day."
                : "Set their deadline. Blank clears it; repeating tasks must keep a deadline."));
            gap(this, SPACE_SM);
            add(date);
        }
        @Override TaskBatch.Change change() {
            return planned ? new TaskBatch.Planned(date.value()) : new TaskBatch.Due(date.value());
        }
    }

    static final class TagsForm extends EditForm {
        final JComboBox<TaskBatch.TagMode> mode = plainCombo(new JComboBox<>(TaskBatch.TagMode.values()));
        final TagField tags;
        TagsForm(State state) {
            tags = new TagField(state.tags(), List.of());
            mode.setName("tasks.bulk.tagMode");
            mode.getAccessibleContext().setAccessibleName("How to change tags");
            add(bodyLabel("Add or remove the chosen tags, or replace all tags on every selected task. "
                + "Choose Replace all tags with no tags selected to clear them."));
            gap(this, SPACE_SM);
            add(mode);
            gap(this, SPACE_SM);
            add(tags);
        }
        @Override TaskBatch.Change change() {
            return new TaskBatch.Tags((TaskBatch.TagMode) mode.getSelectedItem(), tags.tagIds(), tags.newTags());
        }
    }
}
