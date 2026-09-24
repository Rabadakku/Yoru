package dev.yoru.ui;

import dev.yoru.domain.Model.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import static dev.yoru.ui.Theme.*;

/**
 * The task table's columns (#25), with the owner's own properties among them (#68).
 *
 * The fixed columns — done, status, title, tags, due and the ⋯ menu — are
 * where they always were. After them come the priority, while any task on the
 * board has one, and every property shown for this place. Nothing here scrolls
 * sideways: when the window is too narrow for those columns and a readable
 * title, they fold onto a line of their own under the title, each saying which
 * property it is, as the Habits page moves a row's figures under its name.
 */
final class TaskTable {
    private TaskTable() { }

    enum Kind { DONE, STATUS, TITLE, PRIORITY, TAGS, DUE, PROPERTY, MENU }

    /** One column: what it holds, and its designed width at 100% text (the title's 0 takes the rest). */
    record Column(Kind kind, Property property, int base) {
        /** Priority and properties fold under the title when there is no room beside it. */
        boolean folds() { return kind == Kind.PRIORITY || kind == Kind.PROPERTY; }
        String heading() {
            return switch (kind) {
                case DONE, MENU -> "";
                case STATUS -> "Status";
                case TITLE -> "Task";
                case PRIORITY -> "Priority";
                case TAGS -> "Tags";
                case DUE -> "Due";
                case PROPERTY -> property.name();
            };
        }
    }

    /**
     * The columns a board of these tasks has in this place. A place's
     * properties are those kept for every task and, in a list, the list's own;
     * hidden ones are left out.
     */
    static List<Column> columns(TaskDatabase database, List<Task> tasks, UUID list) {
        var out = new ArrayList<Column>();
        out.add(new Column(Kind.DONE, null, SPACE_XL));
        out.add(new Column(Kind.STATUS, null, SPACE_XXL * 2 + SPACE_MD));
        out.add(new Column(Kind.TITLE, null, 0));
        out.add(new Column(Kind.TAGS, null, SPACE_XXL * 4 + SPACE_LG));
        out.add(new Column(Kind.DUE, null, SPACE_XXL * 5));
        if (tasks.stream().anyMatch(t -> t.priority() != Priority.NONE)) out.add(new Column(Kind.PRIORITY, null, SPACE_XXL * 2 + SPACE_SM));
        for (var property : shown(database, list)) out.add(new Column(Kind.PROPERTY, property, base(property.type())));
        out.add(new Column(Kind.MENU, null, SPACE_XXL));
        return List.copyOf(out);
    }

    /** The properties shown as columns in a place: every task's, and a list's own, less the hidden. */
    static List<Property> shown(TaskDatabase database, UUID list) {
        return database.properties().stream().filter(p -> !p.hidden() && (p.listId() == null || p.listId().equals(list))).toList();
    }

    /** Room for a value of this type that is usually enough; a longer one is shortened, with all of it in its tooltip. */
    static int base(PropertyType type) {
        return switch (type) {
            case CHECKBOX -> SPACE_XXL * 2;
            case NUMBER -> SPACE_XXL * 2 + SPACE_LG;
            case DATE, SELECT -> SPACE_XXL * 3 + SPACE_SM;
            case CREATED, EDITED -> SPACE_XXL * 4 + SPACE_SM;
            case TEXT, URL, MULTI_SELECT -> SPACE_XXL * 5;
        };
    }

    // ------------------------------------------------------------------ layout

    /** A row of the table: laid out in its table's columns, never taller than its cells need. */
    static JPanel row(List<Column> columns) {
        var line = new JPanel(new Columns(columns)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        line.setOpaque(false);
        line.setAlignmentX(0);
        return line;
    }

    /** The header: each column's name, a property's shortened with the whole of it in its tooltip. */
    static JPanel header(List<Column> columns) {
        var header = row(columns);
        header.setName("task.header");
        header.setBorder(listRow());
        for (var column : columns) {
            var heading = column.kind() == Kind.PROPERTY
                ? shortenable(column.heading(), TYPE_CAPTION, MUTED) : label(column.heading(), TYPE_CAPTION, MUTED);
            if (column.kind() == Kind.PROPERTY) heading.setName("task.header.property." + column.property().id());
            header.add(heading);
        }
        return header;
    }

    /**
     * Lays a row's cells into its table's column widths; the title takes what
     * is left. When the folding columns and a readable title will not share a
     * line, they move under the title, flowing from its left edge and wrapping.
     */
    static final class Columns implements LayoutManager {
        /** The width assumed before a row has one, so a first measurement is a sensible one. */
        private static final int UNSIZED = SPACE_XXL * 30;
        /** The narrowest a title may be, sharing its line with folding columns, before they fold. */
        private static final int TITLE_FLOOR = SPACE_XXL * 6;
        private final List<Column> columns;

        Columns(List<Column> columns) { this.columns = List.copyOf(columns); }

        List<Column> columns() { return columns; }

        private int title() {
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).kind() == Kind.TITLE) return i;
            throw new IllegalStateException("A task table has a title column.");
        }

        /** Whether the folding columns go under the title at this inner width. */
        boolean folded(int inner) {
            if (columns.stream().noneMatch(Column::folds)) return false;
            int all = 0;
            for (var c : columns) all += grow(c.base());
            return inner - all - SPACE_MD * (columns.size() - 1) < grow(TITLE_FLOOR);
        }

        /** Each column's width: its own grown with the text, the title's what is left of the line. */
        int[] widths(int inner) {
            boolean folded = folded(inner);
            var out = new int[columns.size()];
            int fixed = 0, count = 0;
            for (int i = 0; i < out.length; i++) {
                var c = columns.get(i);
                if (folded && c.folds()) continue;
                out[i] = grow(c.base());
                fixed += out[i];
                count++;
            }
            int t = title();
            out[t] = Math.max(grow(48), inner - fixed - SPACE_MD * (count - 1));
            return out;
        }

        private static int inner(Container row) {
            var insets = row.getInsets();
            return (row.getWidth() > 0 ? row.getWidth() : UNSIZED) - insets.left - insets.right;
        }

        /** A wrapping title is as tall as its text at this width; anything else is its preferred height. */
        private static int height(Component cell, int width) {
            if (cell instanceof JTextArea wrapping) {
                wrapping.setSize(width, Short.MAX_VALUE);
                return wrapping.getPreferredSize().height;
            }
            return cell.getPreferredSize().height;
        }

        private static boolean fills(Component cell) {
            return cell instanceof JTextArea || cell instanceof Folding
                || cell instanceof JLabel l && l.getClientProperty(PILL) == null;
        }

        /** Where the folded cells start, and the width they may flow across. */
        private int foldedLeft(int[] w) {
            int x = 0;
            for (int i = 0; i < title(); i++) if (w[i] > 0 || !columns.get(i).folds()) x += w[i] + SPACE_MD;
            return x;
        }

        /**
         * Places the folded cells line by line, each line's cells centred on
         * it; returns the height they take. A folded cell with nothing in it is
         * left out, as Notion's list leaves out a property a page does not
         * have: under the title, "Effort:" with nothing after it is noise, and
         * the value is set from the task itself.
         */
        private int flow(Container row, int[] w, int left, int right, int top, boolean place) {
            var lines = new ArrayList<List<Component>>();
            var current = new ArrayList<Component>();
            int x = left;
            for (int i = 0; i < Math.min(columns.size(), row.getComponentCount()); i++) {
                if (!columns.get(i).folds()) continue;
                var cell = row.getComponent(i);
                if (!(cell instanceof Folding f) || f.empty()) { if (place) cell.setBounds(0, 0, 0, 0); continue; }
                f.named(true);
                int width = Math.min(cell.getPreferredSize().width, right - left);
                if (!current.isEmpty() && x + width > right) { lines.add(current); current = new ArrayList<>(); x = left; }
                current.add(cell);
                x += width + SPACE_MD;
            }
            if (!current.isEmpty()) lines.add(current);
            int y = top;
            for (var line : lines) {
                int tallest = 0;
                for (var cell : line) tallest = Math.max(tallest, cell.getPreferredSize().height);
                x = left;
                for (var cell : line) {
                    var size = cell.getPreferredSize();
                    int width = Math.min(size.width, right - left);
                    if (place) cell.setBounds(x, y + (tallest - size.height) / 2, width, size.height);
                    x += width + SPACE_MD;
                }
                y += tallest + SPACE_XS;
            }
            return lines.isEmpty() ? 0 : y - SPACE_XS - top;
        }

        @Override public void addLayoutComponent(String name, Component cell) { }
        @Override public void removeLayoutComponent(Component cell) { }

        @Override public Dimension preferredLayoutSize(Container row) {
            var insets = row.getInsets();
            int inner = inner(row);
            int[] w = widths(inner);
            boolean folded = folded(inner);
            int tallest = 0;
            for (int i = 0; i < Math.min(w.length, row.getComponentCount()); i++) {
                if (folded && columns.get(i).folds()) continue;
                var cell = row.getComponent(i);
                if (cell instanceof Folding f) f.named(false);
                tallest = Math.max(tallest, height(cell, w[i]));
            }
            int under = folded ? flow(row, w, foldedLeft(w), inner, 0, false) : 0;
            int height = tallest + (under > 0 ? SPACE_XS + under : 0);
            int width = Arrays.stream(w).sum() + SPACE_MD * (w.length - 1);
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container row) { return new Dimension(0, preferredLayoutSize(row).height); }

        @Override public void layoutContainer(Container row) {
            var insets = row.getInsets();
            int inner = inner(row);
            int[] w = widths(inner);
            boolean folded = folded(inner);
            // The first line is as tall as its tallest cell; folded cells go under it.
            int first = 0;
            for (int i = 0; i < Math.min(w.length, row.getComponentCount()); i++) {
                if (folded && columns.get(i).folds()) continue;
                var cell = row.getComponent(i);
                if (cell instanceof Folding f) f.named(false);
                first = Math.max(first, height(cell, w[i]));
            }
            int x = insets.left;
            for (int i = 0; i < Math.min(w.length, row.getComponentCount()); i++) {
                if (folded && columns.get(i).folds()) continue;
                var cell = row.getComponent(i);
                int width = fills(cell) ? w[i] : Math.min(cell.getPreferredSize().width, w[i]);
                int height = Math.min(first, height(cell, w[i]));
                cell.setBounds(x, insets.top + Math.max(0, (first - height) / 2), width, height);
                x += w[i] + SPACE_MD;
            }
            if (folded) flow(row, w, insets.left + foldedLeft(w), insets.left + inner, insets.top + first + SPACE_XS, true);
        }
    }

    /** Marks a label that is a pill, which keeps its own width instead of filling its column. */
    static final String PILL = "yoru.pill";

    // ------------------------------------------------------------------ cells

    /**
     * A folding column's cell: its value, and its property's name before it
     * once it has folded under the title, where no header says what it is.
     */
    static final class Folding extends JPanel {
        private final JLabel name;
        private final JComponent value;
        private final boolean empty;
        private boolean named;

        Folding(String name, JComponent value, boolean empty) {
            super(new BorderLayout(SPACE_XS, 0));
            this.empty = empty;
            setOpaque(false);
            this.name = label(name + ":", TYPE_CAPTION, MUTED);
            this.value = value;
            this.name.setVisible(false);
            add(this.name, BorderLayout.WEST);
            add(value, BorderLayout.CENTER);
        }

        JComponent value() { return value; }
        /** Whether it holds nothing, and so is left out once folded. */
        boolean empty() { return empty; }

        /** Shows the name or hides it; changing it asks the row to be measured again. */
        void named(boolean on) {
            if (on == named) return;
            named = on;
            name.setVisible(on);
        }

        boolean isNamed() { return named; }

        @Override public Dimension getPreferredSize() {
            var v = value.getPreferredSize();
            if (!named) return v;
            var n = name.getPreferredSize();
            return new Dimension(n.width + SPACE_XS + v.width, Math.max(n.height, v.height));
        }
    }

    /** What a cell asks of the page when it is used. */
    interface Edits {
        /** Choose this task's priority. */
        void priority(Task task);
        /** Edit this task's value for this property. */
        void value(Task task, Property property);
        /** Tick or untick a checkbox property, straight from its cell. */
        void tick(Task task, Property property, boolean on);
        /** Open a link a URL property holds. */
        void open(String link);
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH);

    /** The priority cell: a pill in the priority's colour, or an empty one to set it with. */
    static Folding priority(Task task, Edits edits) {
        var p = task.priority();
        var pill = button(p == Priority.NONE ? "" : p.label, () -> edits.priority(task));
        pill.setName("task.priority." + task.id());
        pill.setFont(captionFont());
        pill.setBorder(new EmptyBorder(RING, SPACE_SM, RING, SPACE_SM));
        pill.setBackground(p == Priority.NONE ? PANEL : TagChips.wash(priorityColour(p)));
        pill.setForeground(TEXT);
        pill.setHorizontalAlignment(SwingConstants.LEFT);
        pill.setToolTipText(p == Priority.NONE ? "Set a priority" : "Priority: " + p.label + " · click to change");
        pill.getAccessibleContext().setAccessibleName("Priority for " + task.title() + ": "
            + (p == Priority.NONE ? "none" : p.label) + ". Activate to change it");
        // A set priority is a pill of its own width; an empty one fills the cell so it can be clicked.
        return new Folding("Priority", p == Priority.NONE ? pill : ownWidth(pill), p == Priority.NONE);
    }

    /** A priority's colour: urgent in the danger ink, down to low in the muted one. */
    static Color priorityColour(Priority p) {
        return switch (p) {
            case URGENT -> DANGER;
            case HIGH -> GOLD;
            case MEDIUM -> CYAN;
            case LOW, NONE -> MUTED;
        };
    }

    /** One property's cell on one task. */
    static Folding cell(Task task, Property property, Edits edits, ZoneId zone) {
        var value = task.values().get(property.id());
        String spoken = property.name() + " for " + task.title();
        JComponent shown = switch (property.type()) {
            case CHECKBOX -> {
                var box = new JCheckBox();
                box.setOpaque(false);
                box.setSelected(value instanceof Value.Tick);
                box.getAccessibleContext().setAccessibleName(spoken);
                box.addActionListener(e -> edits.tick(task, property, box.isSelected()));
                yield box;
            }
            case CREATED, EDITED -> {
                var when = property.type() == PropertyType.CREATED ? task.createdAt() : task.edited();
                var text = shortenable(STAMP.format(when.atZone(zone)), TYPE_CAPTION, MUTED);
                text.getAccessibleContext().setAccessibleName(spoken + ": " + STAMP.format(when.atZone(zone)));
                yield text;
            }
            case SELECT, MULTI_SELECT -> {
                var pills = new ArrayList<Tag>();
                if (value instanceof Value.Choice c) pills.add(pill(property, c.option()));
                if (value instanceof Value.Choices cs) for (var o : cs.options()) pills.add(pill(property, o));
                pills.removeIf(Objects::isNull);
                yield new TagChips.Cell(pills, () -> edits.value(task, property), property.name(), "+ choose");
            }
            case URL -> {
                if (!(value instanceof Value.Text t)) {
                    var set = new ShortButton("", () -> edits.value(task, property));
                    set.getAccessibleContext().setAccessibleName(spoken + ": empty. Activate to set it");
                    yield set;
                }
                var link = t.text();
                var box = new JPanel(new LinkLine());
                box.setOpaque(false);
                var open = linkButton(link, () -> edits.open(link));
                open.getAccessibleContext().setAccessibleName("Open " + link);
                var change = ghost(button("✎", () -> edits.value(task, property)));
                change.setFont(captionFont());
                change.setBorder(new EmptyBorder(RING, SPACE_XS, RING, SPACE_XS));
                change.setToolTipText("Change " + property.name());
                change.getAccessibleContext().setAccessibleName("Change " + spoken);
                change.setName("task.value.edit." + property.id() + "." + task.id());
                box.add(open);
                box.add(change);
                yield box;
            }
            case TEXT, NUMBER, DATE -> {
                String text = value == null ? "" : text(value);
                var edit = new ShortButton(text, () -> edits.value(task, property));
                edit.setHorizontalAlignment(property.type() == PropertyType.NUMBER ? SwingConstants.RIGHT : SwingConstants.LEFT);
                edit.getAccessibleContext().setAccessibleName(spoken + ": " + (text.isEmpty() ? "empty" : text) + ". Activate to change it");
                yield edit;
            }
        };
        shown.setName("task.value." + property.id() + "." + task.id());
        return new Folding(property.name(), shown, property.type().stored() && value == null);
    }

    /** A property value as a line of text, as a cell, a tooltip or a search reads it. */
    static String text(Value value) {
        return switch (value) {
            case Value.Text t -> t.text().lines().findFirst().orElse("");
            case Value.Amount a -> a.amount().toPlainString();
            case Value.Day d -> DateText.date(d.date());
            case Value.Tick ignored -> "Yes";
            case Value.Choice ignored -> "";
            case Value.Choices ignored -> "";
        };
    }

    /** An option drawn as a tag pill, or null when it is gone. */
    private static Tag pill(Property property, UUID option) {
        var o = property.option(option);
        return o == null ? null : new Tag(o.id(), o.name(), o.colour());
    }

    /**
     * A link and its pencil: the link as wide as it needs, shortened when the
     * cell is narrower, and the pencil straight after it rather than at the
     * column's far edge.
     */
    private static final class LinkLine implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component c) { }
        @Override public void removeLayoutComponent(Component c) { }
        @Override public Dimension preferredLayoutSize(Container box) {
            var link = box.getComponent(0).getPreferredSize();
            var pencil = box.getComponent(1).getPreferredSize();
            return new Dimension(link.width + SPACE_XS + pencil.width, Math.max(link.height, pencil.height));
        }
        @Override public Dimension minimumLayoutSize(Container box) {
            var pencil = box.getComponent(1).getPreferredSize();
            return new Dimension(pencil.width, preferredLayoutSize(box).height);
        }
        @Override public void layoutContainer(Container box) {
            var link = box.getComponent(0);
            var pencil = box.getComponent(1);
            int height = box.getHeight(), p = pencil.getPreferredSize().width;
            int room = Math.max(0, box.getWidth() - p - SPACE_XS);
            int width = Math.min(link.getPreferredSize().width, room);
            link.setBounds(0, 0, width, height);
            pencil.setBounds(width + SPACE_XS, 0, p, height);
        }
    }

    /** A control that keeps its own width at the start of its cell rather than filling it. */
    private static JComponent ownWidth(JComponent control) {
        var holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        holder.setOpaque(false);
        holder.add(control);
        return holder;
    }

    /** A flat, left-aligned button that shortens its text to fit and says the whole of it in its tooltip. */
    static final class ShortButton extends JButton {
        private final String whole;
        ShortButton(String text, Runnable action) {
            super(text);
            this.whole = text;
            setFont(captionFont());
            setForeground(TEXT);
            setBackground(PANEL);
            setBorder(new EmptyBorder(RING, SPACE_XS, RING, SPACE_XS));
            setHorizontalAlignment(SwingConstants.LEFT);
            addActionListener(e -> action.run());
            // Registered by hand: the tooltip comes from the override, never from setToolTipText.
            ToolTipManager.sharedInstance().registerComponent(this);
        }
        @Override public String getToolTipText() {
            return whole.isEmpty() ? "Click to set" : getWidth() > 0 && getWidth() < getPreferredSize().width ? whole : null;
        }
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
    }

    /** A link in the accent ink that opens it; shortened with the whole of it in its tooltip. */
    private static JButton linkButton(String link, Runnable open) {
        var b = new ShortButton(link, open);
        b.setForeground(ACCENT_TEXT);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
}
