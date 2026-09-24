package dev.yoru.ui;

import dev.yoru.application.TaskProperties;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import static dev.yoru.ui.Theme.*;

/**
 * The controls that edit a task's property values (#68), one kind per
 * property type: the task form uses one of each for the task's properties,
 * and a table cell opens the one for its property on its own.
 *
 * A select's options can be typed as well as picked, as in Notion: a name no
 * option has yet becomes a new option, made in the same write as the value.
 */
final class PropertyEditors {
    private PropertyEditors() { }

    /** One property's control, and what it says the value is now. */
    interface Editor {
        JComponent component();
        /** What the form says; an unreadable entry is refused, naming the property. */
        TaskProperties.Entry entry();
    }

    /** The editor for one property, starting from this value (or none). */
    static Editor of(Property property, Value value) {
        return switch (property.type()) {
            case TEXT -> text(property, value, true);
            case URL -> text(property, value, false);
            case NUMBER -> number(property, value);
            case SELECT -> select(property, value);
            case MULTI_SELECT -> multi(property, value);
            case DATE -> date(property, value);
            case CHECKBOX -> checkbox(property, value);
            case CREATED, EDITED -> readOnly(property);
        };
    }

    private static Editor text(Property property, Value value, boolean lines) {
        String now = value instanceof Value.Text t ? t.text() : "";
        if (lines) {
            var area = new JTextArea(now, 3, 32);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setName("property.editor." + property.id());
            area.getAccessibleContext().setAccessibleName(property.name());
            var scroll = new JScrollPane(area);
            return new Editor() {
                public JComponent component() { return scroll; }
                public TaskProperties.Entry entry() {
                    return area.getText().isBlank() ? TaskProperties.Entry.none() : TaskProperties.Entry.of(new Value.Text(area.getText()));
                }
            };
        }
        var field = styleInput(new JTextField(now, 32));
        field.setName("property.editor." + property.id());
        field.getAccessibleContext().setAccessibleName(property.name());
        return new Editor() {
            public JComponent component() { return field; }
            public TaskProperties.Entry entry() {
                String typed = field.getText().strip();
                if (typed.isEmpty()) return TaskProperties.Entry.none();
                if (typed.chars().anyMatch(Character::isWhitespace))
                    throw new IllegalArgumentException(property.name() + ": a link has no spaces in it.");
                return TaskProperties.Entry.of(new Value.Text(typed));
            }
        };
    }

    private static Editor number(Property property, Value value) {
        var field = styleInput(new JTextField(value instanceof Value.Amount a ? a.amount().toPlainString() : "", 12));
        field.setName("property.editor." + property.id());
        field.getAccessibleContext().setAccessibleName(property.name());
        return new Editor() {
            public JComponent component() { return field; }
            public TaskProperties.Entry entry() {
                String typed = field.getText().strip().replace(",", "").replace(" ", "");
                if (typed.isEmpty()) return TaskProperties.Entry.none();
                try { return TaskProperties.Entry.of(new Value.Amount(new BigDecimal(typed))); }
                catch (NumberFormatException e) { throw new IllegalArgumentException(property.name() + ": \"" + field.getText().strip() + "\" is not a number."); }
            }
        };
    }

    /** A select: pick an option, or type a new one. */
    private static Editor select(Property property, Value value) {
        var combo = plainCombo(new JComboBox<Object>());
        combo.setEditable(true);
        combo.addItem("");
        property.options().forEach(o -> combo.addItem(o.name()));
        combo.setSelectedItem(value instanceof Value.Choice c && property.option(c.option()) != null ? property.option(c.option()).name() : "");
        combo.setName("property.editor." + property.id());
        combo.getAccessibleContext().setAccessibleName(property.name() + ": choose or type an option");
        return new Editor() {
            public JComponent component() { return combo; }
            public TaskProperties.Entry entry() {
                var editor = combo.getEditor().getItem();
                String typed = String.valueOf(editor == null ? combo.getSelectedItem() : editor).strip();
                if (typed.isEmpty()) return TaskProperties.Entry.none();
                for (var o : property.options()) if (o.name().equalsIgnoreCase(typed)) return TaskProperties.Entry.options(List.of(o.id()), List.of());
                return TaskProperties.Entry.options(List.of(), List.of(typed));
            }
        };
    }

    /** A multi-select: tick any of its options, and type new ones, separated by commas. */
    private static Editor multi(Property property, Value value) {
        var chosen = value instanceof Value.Choices cs ? cs.options() : List.<UUID>of();
        var panel = stack();
        panel.setName("property.editor." + property.id());
        var boxes = new LinkedHashMap<UUID, JCheckBox>();
        var grid = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        grid.setOpaque(false);
        for (var o : property.options()) {
            var box = new JCheckBox(o.name(), chosen.contains(o.id()));
            box.setOpaque(false);
            box.setForeground(TEXT);
            box.setFont(labelFont());
            box.setName("property.option." + o.id());
            boxes.put(o.id(), box);
            grid.add(box);
        }
        if (!boxes.isEmpty()) panel.add(grid);
        var more = styleInput(new JTextField(24));
        more.setName("property.editor.new." + property.id());
        more.getAccessibleContext().setAccessibleName("New options for " + property.name() + ", separated by commas");
        var caption = label(boxes.isEmpty() ? "Options, separated by commas" : "New options, separated by commas", TYPE_CAPTION, MUTED);
        caption.setLabelFor(more);
        panel.add(caption);
        panel.add(more);
        return new Editor() {
            public JComponent component() { return panel; }
            public TaskProperties.Entry entry() {
                // In the order chosen: the ticked ones as the task had them, then any new.
                var ids = new ArrayList<UUID>();
                for (var id : chosen) if (boxes.containsKey(id) && boxes.get(id).isSelected()) ids.add(id);
                for (var e : boxes.entrySet()) if (e.getValue().isSelected() && !ids.contains(e.getKey())) ids.add(e.getKey());
                var typed = new ArrayList<String>();
                for (var word : more.getText().split(",")) {
                    var name = word.strip();
                    if (name.isEmpty()) continue;
                    var known = property.options().stream().filter(o -> o.name().equalsIgnoreCase(name)).findFirst();
                    if (known.isPresent()) { if (!ids.contains(known.get().id())) ids.add(known.get().id()); }
                    else if (typed.stream().noneMatch(t -> t.equalsIgnoreCase(name))) typed.add(name);
                }
                return ids.isEmpty() && typed.isEmpty() ? TaskProperties.Entry.none() : TaskProperties.Entry.options(ids, typed);
            }
        };
    }

    private static Editor date(Property property, Value value) {
        var field = new DateField(value instanceof Value.Day d ? d.date() : null, property.name(), true);
        field.setName("property.editor." + property.id());
        return new Editor() {
            public JComponent component() { return field; }
            public TaskProperties.Entry entry() {
                var day = field.value();
                return day == null ? TaskProperties.Entry.none() : TaskProperties.Entry.of(new Value.Day(day));
            }
        };
    }

    private static Editor checkbox(Property property, Value value) {
        var box = new JCheckBox(property.name(), value instanceof Value.Tick);
        box.setOpaque(false);
        box.setForeground(TEXT);
        box.setFont(labelFont());
        box.setName("property.editor." + property.id());
        return new Editor() {
            public JComponent component() { return box; }
            public TaskProperties.Entry entry() { return box.isSelected() ? TaskProperties.Entry.of(new Value.Tick()) : TaskProperties.Entry.none(); }
        };
    }

    private static Editor readOnly(Property property) {
        var note = label(property.type() == PropertyType.CREATED ? "Set when the task is made." : "Set whenever the task changes.",
            TYPE_CAPTION, MUTED);
        return new Editor() {
            public JComponent component() { return note; }
            public TaskProperties.Entry entry() { return null; }
        };
    }

    /**
     * The task form's properties: a caption and a control for each that
     * applies to the task's list, in the order the owner keeps them.
     */
    static final class Section extends Theme.VerticalPanel {
        private final Map<UUID, Editor> editors = new LinkedHashMap<>();

        Section(TaskDatabase database, Task task, UUID list) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(0);
            setOpaque(false);
            for (var property : database.properties()) {
                if (!property.appliesTo(list) || !property.type().stored()) continue;
                var editor = of(property, task == null ? null : task.values().get(property.id()));
                editors.put(property.id(), editor);
                if (property.type() != PropertyType.CHECKBOX) add(label(property.name(), TYPE_LABEL, TEXT));
                add(editor.component());
                gap(this, SPACE_SM);
            }
        }

        boolean isEmpty() { return editors.isEmpty(); }

        /** Every property's entry, or an error naming the one that cannot be read. */
        Map<UUID, TaskProperties.Entry> entries() {
            var out = new LinkedHashMap<UUID, TaskProperties.Entry>();
            editors.forEach((id, editor) -> { var e = editor.entry(); if (e != null) out.put(id, e); });
            return out;
        }
    }
}
