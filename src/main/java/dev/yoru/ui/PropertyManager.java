package dev.yoru.ui;

import dev.yoru.application.TaskProperties;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import static dev.yoru.ui.Theme.*;

/**
 * The owner's task properties and statuses (#68), made, changed and deleted
 * in one place, the way Notion's database settings keep them.
 *
 * Like the tag editor this is not a draft: each change is its own write, and a
 * refusal arrives while the dialog is still open. A row's rare actions are in
 * its ⋯ menu, which the keyboard reaches like any button; showing or hiding a
 * property from the table is the one action on the row itself.
 */
final class PropertyManager extends Theme.VerticalPanel {
    private final Tracker tracker;
    private final Runnable changed;
    private final JTextField name = styleInput(new JTextField(18));
    private final JComboBox<PropertyType> type = plainCombo(new JComboBox<>(PropertyType.values()));
    private final JComboBox<Object> scope = plainCombo(new JComboBox<>());
    private final JTextField statusName = styleInput(new JTextField(18));
    private final JComboBox<TaskStatus> group = plainCombo(new JComboBox<>(TaskStatus.values()));

    PropertyManager(Tracker tracker, Runnable changed) {
        this.tracker = tracker;
        this.changed = changed;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(0);
        setOpaque(false);
        name.setName("property.name");
        name.getAccessibleContext().setAccessibleName("New property name");
        type.setName("property.type");
        type.getAccessibleContext().setAccessibleName("New property type");
        scope.setName("property.scope");
        scope.getAccessibleContext().setAccessibleName("Which tasks have it");
        statusName.setName("status.name");
        statusName.getAccessibleContext().setAccessibleName("New status name");
        group.setName("status.group");
        group.getAccessibleContext().setAccessibleName("New status group");
        rebuild();
    }

    private TaskProperties props() { return tracker.properties(); }

    private void rebuild() {
        removeAll();
        var database = tracker.state().database();
        add(sectionHeader("PROPERTIES · " + database.properties().size()));
        gap(this, SPACE_SM);
        add(bodyLabel("What every task, or one list's tasks, can hold beside its title. Shown as columns in the table."));
        gap(this, SPACE_MD);
        if (database.properties().isEmpty())
            add(emptyState("No properties yet.", "Add one below: a number, a select, a date, a link…", null));
        for (var property : database.properties()) add(propertyRow(property));
        gap(this, SPACE_SM);
        add(label("ADD A PROPERTY", TYPE_CAPTION, MUTED));
        gap(this, SPACE_XS);
        var form = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        form.setOpaque(false);
        form.setAlignmentX(0);
        scope.removeAllItems();
        scope.addItem("Every task");
        TaskLists.ordered(tracker.state()).forEach(scope::addItem);
        var add = button("Add property", this::createProperty);
        add.setName("property.add");
        form.add(name); form.add(type); form.add(scope); form.add(add);
        add(form);

        gap(this, SPACE_XL);
        add(sectionHeader("STATUSES"));
        gap(this, SPACE_SM);
        add(bodyLabel("Your own statuses sit inside To do, Doing and Done, which decide what counts as finished."));
        gap(this, SPACE_MD);
        for (var g : TaskStatus.values()) {
            add(label(g.label.toUpperCase(Locale.ENGLISH), TYPE_CAPTION, MUTED));
            gap(this, SPACE_XS);
            add(line(label(g.label + " · built in", TYPE_LABEL, MUTED), new JPanel()));
            for (var own : database.statusesIn(g)) add(statusRow(own));
            gap(this, SPACE_SM);
        }
        add(label("ADD A STATUS", TYPE_CAPTION, MUTED));
        gap(this, SPACE_XS);
        var statusForm = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        statusForm.setOpaque(false);
        statusForm.setAlignmentX(0);
        var addStatus = button("Add status", this::createStatus);
        addStatus.setName("status.add");
        statusForm.add(statusName); statusForm.add(label("in", TYPE_BODY, MUTED)); statusForm.add(group); statusForm.add(addStatus);
        add(statusForm);
        revalidate();
        repaint();
    }

    /** A row: its name growing and shortening, its controls at the end. */
    private static JPanel line(JComponent what, JComponent actions) {
        var line = new JPanel(new BorderLayout(SPACE_MD, 0));
        line.setOpaque(false);
        line.setAlignmentX(0);
        line.setBorder(listRow());
        line.add(what, BorderLayout.CENTER);
        actions.setOpaque(false);
        line.add(actions, BorderLayout.EAST);
        return line;
    }

    private JPanel propertyRow(Property property) {
        var id = property.id();
        var what = stack();
        what.add(shortenable(property.name(), TYPE_LABEL, TEXT));
        String where = property.listId() == null ? "every task" : TaskLists.name(tracker.state(), property.listId().toString());
        String options = property.type().hasOptions() ? " · " + plural(property.options().size(), "option") : "";
        what.add(shortenable(property.type().label + options + " · " + where + (property.hidden() ? " · hidden" : ""), TYPE_CAPTION, MUTED));
        var actions = tightRow();
        var shown = ghost(button(property.hidden() ? "Show" : "Hide", () -> act(() -> props().hideProperty(id, !property.hidden()))));
        shown.setName("property.hide." + id);
        shown.getAccessibleContext().setAccessibleName((property.hidden() ? "Show " : "Hide ") + property.name() + " in the table");
        var more = ghost(button("⋯", () -> { }));
        more.setName("property.more." + id);
        more.getAccessibleContext().setAccessibleName("Actions for " + property.name());
        more.addActionListener(e -> menu(property).show(more, 0, more.getHeight()));
        actions.add(shown);
        actions.add(more);
        return line(what, actions);
    }

    /** A property's ⋯ menu. Package-private for the tests. */
    JPopupMenu menu(Property property) {
        var id = property.id();
        var all = tracker.state().database().properties();
        int at = all.indexOf(property);
        var menu = Menus.popup();
        menu.add(Menus.item("Rename…", "property.rename." + id, true, () -> rename(property), null));
        menu.add(Menus.item("Change type…", "property.retype." + id, true, () -> retype(property), null));
        menu.add(Menus.item("Options…", "property.options." + id, property.type().hasOptions(),
            () -> OptionEditor.open(this, tracker, id, this::done), "Only a select has options"));
        menu.add(Menus.item("Which tasks…", "property.rescope." + id, true, () -> rescope(property), null));
        menu.addSeparator();
        menu.add(Menus.item("Move up", "property.up." + id, at > 0, () -> act(() -> props().moveProperty(id, -1)), "Already first"));
        menu.add(Menus.item("Move down", "property.down." + id, at >= 0 && at < all.size() - 1,
            () -> act(() -> props().moveProperty(id, 1)), "Already last"));
        menu.addSeparator();
        menu.add(Menus.item("Delete…", "property.delete." + id, true, () -> delete(property), null));
        return menu;
    }

    private JPanel statusRow(StatusOption own) {
        var id = own.id();
        var what = new JPanel(new BorderLayout(SPACE_SM, 0));
        what.setOpaque(false);
        var dot = new JLabel("●");
        dot.setForeground(new Color(own.colour()));
        dot.getAccessibleContext().setAccessibleName("Colour");
        what.add(dot, BorderLayout.WEST);
        what.add(shortenable(own.name(), TYPE_LABEL, TEXT), BorderLayout.CENTER);
        var more = ghost(button("⋯", () -> { }));
        more.setName("status.more." + id);
        more.getAccessibleContext().setAccessibleName("Actions for the status " + own.name());
        more.addActionListener(e -> statusMenu(own).show(more, 0, more.getHeight()));
        var actions = tightRow();
        actions.add(more);
        return line(what, actions);
    }

    /** A status's ⋯ menu. Package-private for the tests. */
    JPopupMenu statusMenu(StatusOption own) {
        var id = own.id();
        var inGroup = tracker.state().database().statusesIn(own.group());
        int at = inGroup.indexOf(own);
        var menu = Menus.popup();
        menu.add(Menus.item("Rename…", "status.rename." + id, true, () -> {
            var next = Dialogs.input(this, "Status name: up to 40 characters.", "Rename status", own.name());
            if (next != null) act(() -> props().renameStatus(id, next));
        }, null));
        menu.add(Menus.item("Colour…", "status.colour." + id, true, () -> {
            var chosen = colour(own.name(), own.colour());
            if (chosen != null) act(() -> props().recolourStatus(id, chosen));
        }, null));
        menu.addSeparator();
        menu.add(Menus.item("Move up", "status.up." + id, at > 0, () -> act(() -> props().moveStatus(id, -1)), "Already first in " + own.group().label));
        menu.add(Menus.item("Move down", "status.down." + id, at >= 0 && at < inGroup.size() - 1,
            () -> act(() -> props().moveStatus(id, 1)), "Already last in " + own.group().label));
        menu.addSeparator();
        menu.add(Menus.item("Delete…", "status.delete." + id, true, () -> {
            int uses = props().usesStatus(id);
            var message = uses == 0 ? "Delete the status \"" + own.name() + "\"? No task has it."
                : "Delete the status \"" + own.name() + "\"? " + plural(uses, "task") + " with it go back to "
                + own.group().label + ". A backup is taken first.";
            if (Dialogs.confirmDestructive(this, message, "Delete status", "Delete")) act(() -> props().deleteStatus(id));
        }, null));
        return menu;
    }

    /** Picks a colour from the tag palette; null when dismissed. */
    Integer colour(String what, int current) {
        int[] chosen = {current};
        var panel = stack();
        panel.add(bodyLabel("Pick a colour for \"" + what + "\"."));
        gap(panel, SPACE_MD);
        panel.add(TagEditor.palette(chosen));
        return Dialogs.choose(this, panel, "Colour", "Use this colour", "Cancel") == 0 ? chosen[0] : null;
    }

    void createProperty() {
        act(() -> {
            props().addProperty(name.getText(), (PropertyType) type.getSelectedItem(),
                scope.getSelectedItem() instanceof TaskList list ? list.id() : null);
            name.setText("");
        });
    }

    void createStatus() {
        act(() -> {
            props().addStatus(statusName.getText(), (TaskStatus) group.getSelectedItem());
            statusName.setText("");
        });
    }

    private void rename(Property property) {
        var next = Dialogs.input(this, "Property name: up to 60 characters.", "Rename property", property.name());
        if (next != null) act(() -> props().renameProperty(property.id(), next));
    }

    /** Changing a property's type says first how many values cannot come along. */
    private void retype(Property property) {
        var choice = plainCombo(new JComboBox<>(PropertyType.values()));
        choice.setSelectedItem(property.type());
        choice.getAccessibleContext().setAccessibleName("New type");
        var form = stack();
        form.add(bodyLabel("Values that read as the new type come along: \"3\" becomes 3, text becomes options named by it."));
        gap(form, SPACE_SM);
        form.add(choice);
        if (!Dialogs.confirm(this, form, "Change the type of " + property.name(), "Continue")) return;
        var next = (PropertyType) choice.getSelectedItem();
        if (next == property.type()) return;
        int lost = props().lostByChangingType(property.id(), next);
        if (lost > 0 && !Dialogs.confirmDestructive(this, plural(lost, "task") + " will lose "
            + (lost == 1 ? "its value" : "their values") + " for \"" + property.name() + "\", which cannot be read as "
            + next.label.toLowerCase(Locale.ENGLISH) + ". A backup is taken first.", "Change type", "Change type")) return;
        act(() -> props().changeType(property.id(), next));
    }

    private void rescope(Property property) {
        var where = plainCombo(new JComboBox<>());
        where.addItem("Every task");
        TaskLists.ordered(tracker.state()).forEach(where::addItem);
        if (property.listId() != null)
            for (int i = 1; i < where.getItemCount(); i++)
                if (((TaskList) where.getItemAt(i)).id().equals(property.listId())) where.setSelectedIndex(i);
        where.getAccessibleContext().setAccessibleName("Which tasks have it");
        var form = stack();
        form.add(bodyLabel("Kept for one list, it shows on that list's tasks. Values on other tasks stay."));
        gap(form, SPACE_SM);
        form.add(where);
        if (Dialogs.confirm(this, form, "Which tasks have " + property.name(), "Save"))
            act(() -> props().scopeProperty(property.id(), where.getSelectedItem() instanceof TaskList list ? list.id() : null));
    }

    private void delete(Property property) {
        int uses = props().uses(property.id());
        var message = uses == 0 ? "Delete \"" + property.name() + "\"? No task has a value for it."
            : "Delete \"" + property.name() + "\"? " + plural(uses, "task") + " lose "
            + (uses == 1 ? "its value" : "their values") + " for it. A backup is taken first.";
        if (Dialogs.confirmDestructive(this, message, "Delete property", "Delete")) act(() -> props().deleteProperty(property.id()));
    }

    private interface Work { void run() throws Exception; }

    private void act(Work work) {
        try { work.run(); done(); }
        catch (Exception e) { Dialogs.error(this, e.getMessage()); }
    }

    private void done() {
        rebuild();
        changed.run();
        var window = SwingUtilities.getWindowAncestor(this);
        if (window != null) window.pack();
    }

    static void open(Component parent, Tracker tracker, Runnable changed) {
        var manager = new PropertyManager(tracker, changed);
        var scroll = new JScrollPane(manager);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.setPreferredSize(new Dimension(grow(SPACE_XXL * 20), Math.min(grow(SPACE_XXL * 20), manager.getPreferredSize().height + SPACE_LG)));
        Dialogs.choose(parent, scroll, "Properties and statuses", "Done");
    }

    /** One select property's options: renamed, recoloured, reordered, deleted and added. */
    static final class OptionEditor extends Theme.VerticalPanel {
        private final Tracker tracker;
        private final UUID property;
        private final Runnable changed;
        private final JTextField name = styleInput(new JTextField(18));

        OptionEditor(Tracker tracker, UUID property, Runnable changed) {
            this.tracker = tracker;
            this.property = property;
            this.changed = changed;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(0);
            setOpaque(false);
            name.setName("option.name");
            name.getAccessibleContext().setAccessibleName("New option name");
            rebuild();
        }

        private void rebuild() {
            removeAll();
            var p = tracker.properties().property(property);
            add(sectionHeader(p.name().toUpperCase(Locale.ENGLISH) + " · " + plural(p.options().size(), "option")));
            gap(this, SPACE_MD);
            for (int i = 0; i < p.options().size(); i++) {
                var o = p.options().get(i);
                var what = new TagChips.Cell(List.of(new Tag(o.id(), o.name(), o.colour())), () -> { }, "Option", "");
                what.setFocusable(false);
                var more = ghost(button("⋯", () -> { }));
                more.setName("option.more." + o.id());
                more.getAccessibleContext().setAccessibleName("Actions for the option " + o.name());
                more.addActionListener(e -> menu(o).show(more, 0, more.getHeight()));
                var actions = tightRow();
                actions.add(more);
                add(line(what, actions));
            }
            gap(this, SPACE_SM);
            var form = tightRow();
            var add = button("Add option", () -> act(() -> { tracker.properties().addOption(property, name.getText()); name.setText(""); }));
            add.setName("option.add");
            form.add(name);
            form.add(add);
            add(form);
            revalidate();
            repaint();
        }

        /** One option's ⋯ menu. Package-private for the tests. */
        JPopupMenu menu(PropertyOption o) {
            var p = tracker.properties().property(property);
            int at = p.options().indexOf(o);
            var menu = Menus.popup();
            menu.add(Menus.item("Rename…", "option.rename." + o.id(), true, () -> {
                var next = Dialogs.input(this, "Option name: up to 40 characters.", "Rename option", o.name());
                if (next != null) act(() -> tracker.properties().renameOption(property, o.id(), next));
            }, null));
            menu.add(Menus.item("Colour…", "option.colour." + o.id(), true, () -> {
                int[] chosen = {o.colour()};
                var panel = stack();
                panel.add(bodyLabel("Pick a colour for \"" + o.name() + "\"."));
                gap(panel, SPACE_MD);
                panel.add(TagEditor.palette(chosen));
                if (Dialogs.choose(this, panel, "Colour", "Use this colour", "Cancel") == 0)
                    act(() -> tracker.properties().recolourOption(property, o.id(), chosen[0]));
            }, null));
            menu.add(Menus.item("Move up", "option.up." + o.id(), at > 0, () -> act(() -> tracker.properties().moveOption(property, o.id(), -1)), "Already first"));
            menu.add(Menus.item("Move down", "option.down." + o.id(), at < p.options().size() - 1,
                () -> act(() -> tracker.properties().moveOption(property, o.id(), 1)), "Already last"));
            menu.addSeparator();
            menu.add(Menus.item("Delete…", "option.delete." + o.id(), true, () -> {
                int uses = tracker.properties().uses(property, o.id());
                var message = uses == 0 ? "Delete the option \"" + o.name() + "\"? No task has it."
                    : "Delete the option \"" + o.name() + "\"? It comes off " + plural(uses, "task") + ". A backup is taken first.";
                if (Dialogs.confirmDestructive(this, message, "Delete option", "Delete"))
                    act(() -> tracker.properties().deleteOption(property, o.id()));
            }, null));
            return menu;
        }

        private void act(Work work) {
            try { work.run(); rebuild(); changed.run(); var w = SwingUtilities.getWindowAncestor(this); if (w != null) w.pack(); }
            catch (Exception e) { Dialogs.error(this, e.getMessage()); }
        }

        static void open(Component parent, Tracker tracker, UUID property, Runnable changed) {
            Dialogs.choose(parent, new OptionEditor(tracker, property, changed), "Options", "Done");
        }
    }
}
