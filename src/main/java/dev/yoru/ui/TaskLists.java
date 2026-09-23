package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import static dev.yoru.ui.Theme.*;

/**
 * Task lists on the Tasks page (#56): the places tasks are filed in, as Apple
 * Reminders and Things have them — Chores, Personal, School.
 *
 * A place is named by a key: {@link #ALL} for every task, {@link #INBOX} for
 * the tasks filed nowhere, {@link #HABITS} for today's daily habits, and a
 * list's own id for that list.
 */
final class TaskLists {
    private TaskLists() { }

    static final String INBOX_HELP = "Tasks without a list. Use “Move to” to organize them.";

    static final String ALL = "all", INBOX = "inbox", HABITS = "habits", REPEATING = "repeating";

    /** Marks a rail entry with the place it stands for, so a dragged task can be dropped on it. */
    static final String TARGET = "yoru.list.target";

    /** The list a place key names, or null for a built-in place or a list since deleted. */
    static UUID listOf(String key) {
        if (key == null || key.equals(ALL) || key.equals(INBOX) || key.equals(HABITS) || key.equals(REPEATING)) return null;
        try { return UUID.fromString(key); } catch (IllegalArgumentException notAList) { return null; }
    }

    /** Whether a task is in the place a key names. */
    static boolean holds(String key, Task task) {
        if (key.equals(ALL)) return true;
        if (key.equals(INBOX)) return task.listId() == null;
        if (key.equals(HABITS)) return false;
        if (key.equals(REPEATING)) return task.repeats();
        return Objects.equals(listOf(key), task.listId());
    }

    /** Whether a key still names somewhere: a list can be deleted from under the page. */
    static boolean exists(State state, String key) {
        var list = listOf(key);
        return list == null ? List.of(ALL, INBOX, HABITS, REPEATING).contains(key)
            : state.lists().stream().anyMatch(l -> l.id().equals(list));
    }

    /** The lists in the owner's order. */
    static List<TaskList> ordered(State state) {
        return state.lists().stream().sorted(Comparator.comparingInt(TaskList::order)).toList();
    }

    /** What a place is called. */
    static String name(State state, String key) {
        return switch (key) {
            case ALL -> "All tasks";
            case INBOX -> "Inbox";
            case HABITS -> "Daily habits";
            case REPEATING -> "Repeating";
            default -> state.lists().stream().filter(l -> l.id().equals(listOf(key))).map(TaskList::name)
                .findFirst().orElse("All tasks");
        };
    }

    // ------------------------------------------------------------------ rail

    /**
     * One place on the rail: a colour or a mark, its name, and how many open
     * tasks it holds. A button, so the keyboard reaches it like any other.
     */
    static JButton entry(String key, String name, Color colour, int open, boolean chosen, Runnable pick) {
        var entry = new Entry();
        entry.setName("tasks.place." + key);
        entry.putClientProperty(TARGET, key);
        entry.setBackground(chosen ? LINE : BG);
        entry.setBorder(new EmptyBorder(SPACE_XS, SPACE_SM, SPACE_XS, SPACE_SM));
        entry.setHorizontalAlignment(SwingConstants.LEFT);
        // Plain labels inside the button, with no listeners of their own, so a
        // click anywhere on the entry reaches the button.
        var dot = new JLabel(colour == null ? " " : "●");
        dot.setFont(captionFont());
        dot.setForeground(colour == null ? MUTED : colour);
        // The whole name as its tooltip when it is cut, without registering the
        // label for tooltips: a registered label takes the clicks the entry needs.
        var text = new JLabel(name) {
            @Override public String getToolTipText() {
                return getWidth() > 0 && getWidth() < getPreferredSize().width ? getText() : null;
            }
        };
        text.putClientProperty("html.disable", true);
        text.setFont(chosen ? labelFont().deriveFont(Font.BOLD) : labelFont());
        text.setForeground(chosen ? TEXT : MUTED);
        text.setMinimumSize(new Dimension(0, text.getPreferredSize().height));
        var count = new JLabel(open == 0 ? "" : String.valueOf(open));
        count.setFont(captionFont());
        count.setForeground(MUTED);
        entry.add(dot, BorderLayout.WEST);
        entry.add(text, BorderLayout.CENTER);
        entry.add(count, BorderLayout.EAST);
        entry.setToolTipText((key.equals(INBOX) ? INBOX_HELP : name) + (open == 0 ? "" : " · " + plural(open, "open task")));
        entry.getAccessibleContext().setAccessibleName(name + ", " + (open == 0 ? "nothing open" : plural(open, "open task"))
            + (chosen ? ", selected" : ""));
        if (key.equals(INBOX)) entry.getAccessibleContext().setAccessibleDescription(INBOX_HELP);
        entry.addActionListener(e -> pick.run());
        // Its own height, so a vertical rail does not stretch its entries.
        entry.setAlignmentX(0);
        return entry;
    }

    /**
     * A rail entry: a button laid out from the labels it holds, filled the way
     * the app's buttons are, and the width of the rail when it stands in one.
     */
    static final class Entry extends JButton {
        Entry() {
            super("");
            setLayout(new BorderLayout(SPACE_SM, 0));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setOpaque(false);
            getModel().addChangeListener(e -> repaint());
        }
        /** The labels decide its size; a button's own measure knows only its text, which is empty. */
        @Override public Dimension getPreferredSize() {
            var inside = getLayout().preferredLayoutSize(this);
            return new Dimension(inside.width, Math.max(inside.height, controlHeight()));
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var fill = getModel().isPressed() ? shade(getBackground(), DARK ? -24 : -30)
                : getModel().isRollover() || Boolean.TRUE.equals(getClientProperty(DROP)) ? shade(getBackground(), DARK ? 18 : -12)
                : getBackground();
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
            if (Boolean.TRUE.equals(getClientProperty(DROP))) {
                // Where a dragged task would land: ringed, like a focused control.
                g.setColor(ringFor(getBackground()));
                g.setStroke(new BasicStroke(RING));
                g.drawRoundRect(1, 1, getWidth() - RING - 1, getHeight() - RING - 1, RADIUS, RADIUS);
            }
            if (isFocusOwner()) {
                g.setColor(ringFor(getBackground()));
                g.setStroke(new BasicStroke(RING));
                g.drawRoundRect(1, 1, getWidth() - RING - 1, getHeight() - RING - 1, RADIUS, RADIUS);
            }
            g.dispose();
        }
    }

    /** Marks the entry a dragged task is over. */
    static final String DROP = "yoru.list.drop";

    /**
     * The rail's places and lists, with New list at the end. {@code entries}
     * receives every entry a task can be dropped on.
     */
    static void fill(JPanel rail, Tracker tracker, String current, Consumer<String> pick,
                     Runnable changed, List<JButton> entries) {
        rail.removeAll();
        entries.clear();
        var state = tracker.state();
        var open = new HashMap<String, Integer>();
        for (var task : state.tasks()) {
            if (task.status() == TaskStatus.DONE) continue;
            open.merge(ALL, 1, Integer::sum);
            open.merge(task.listId() == null ? INBOX : task.listId().toString(), 1, Integer::sum);
        }
        for (var key : List.of(ALL, INBOX)) {
            var entry = entry(key, name(state, key), null, open.getOrDefault(key, 0), key.equals(current), () -> pick.accept(key));
            rail.add(entry);
            entries.add(entry);
        }
        // Every repeating task in one place (#57), once there is one.
        if (state.tasks().stream().anyMatch(Task::repeats)) {
            int repeating = (int) state.tasks().stream().filter(t -> t.repeats() && t.status() != TaskStatus.DONE).count();
            rail.add(entry(REPEATING, name(state, REPEATING), null, repeating, REPEATING.equals(current), () -> pick.accept(REPEATING)));
        }
        long habits = state.habits().stream().filter(h -> h.kind() == HabitKind.DAILY).count();
        if (habits > 0) {
            int left = (int) state.habits().stream().filter(h -> h.kind() == HabitKind.DAILY)
                .filter(h -> !HabitChecklist.checkedToday(h, java.time.Clock.systemUTC())).count();
            rail.add(entry(HABITS, name(state, HABITS), null, left, HABITS.equals(current), () -> pick.accept(HABITS)));
        }
        for (var list : ordered(state)) {
            String key = list.id().toString();
            var entry = entry(key, list.name(), new Color(list.colour()), open.getOrDefault(key, 0), key.equals(current),
                () -> pick.accept(key));
            // The list's own actions, on the right-click and the context-menu key.
            entry.setComponentPopupMenu(menu(rail, tracker, list, pick, changed));
            rail.add(entry);
            entries.add(entry);
        }
        var add = ghost(button("+ New list", () -> create(rail, tracker, pick)));
        add.setName("tasks.list.new");
        add.setHorizontalAlignment(SwingConstants.LEFT);
        add.setForeground(MUTED);
        add.setAlignmentX(0);
        rail.add(add);
        rail.revalidate();
        rail.repaint();
    }

    /** A list's actions: rename, colour, move up and down, turn a tag in, delete. */
    static JPopupMenu menu(Component owner, Tracker tracker, TaskList list, Consumer<String> pick, Runnable changed) {
        var lists = ordered(tracker.state());
        int at = lists.indexOf(list);
        var menu = Menus.popup();
        String id = list.id().toString();
        menu.add(Menus.item("Rename…", "list.rename." + id, true, () -> rename(owner, tracker, list, changed), null));
        menu.add(Menus.item("Colour…", "list.colour." + id, true, () -> recolour(owner, tracker, list, changed), null));
        menu.addSeparator();
        menu.add(Menus.item("Move up", "list.up." + id, at > 0, () -> move(owner, tracker, list, -1, changed), "Already first"));
        menu.add(Menus.item("Move down", "list.down." + id, at >= 0 && at < lists.size() - 1,
            () -> move(owner, tracker, list, 1, changed), "Already last"));
        menu.addSeparator();
        menu.add(Menus.item("Delete…", "list.delete." + id, true, () -> delete(owner, tracker, list, pick), null));
        return menu;
    }

    // --------------------------------------------------------------- dialogs

    static void create(Component owner, Tracker tracker, Consumer<String> pick) {
        String name = Dialogs.input(owner, "List name: up to 40 characters, e.g. Chores, Personal or School.", "New list");
        if (name == null) return;
        try {
            var list = tracker.addList(name, TagEditor.paletteColour(tracker.state().lists().size()));
            pick.accept(list.id().toString());
        } catch (Exception e) { Dialogs.error(owner, e.getMessage()); }
    }

    static void rename(Component owner, Tracker tracker, TaskList list, Runnable changed) {
        String name = Dialogs.input(owner, "List name: up to 40 characters.", "Rename list", list.name());
        if (name == null) return;
        try { tracker.editList(list.id(), name, list.colour()); changed.run(); }
        catch (Exception e) { Dialogs.error(owner, e.getMessage()); }
    }

    static void recolour(Component owner, Tracker tracker, TaskList list, Runnable changed) {
        int[] chosen = {list.colour()};
        var panel = stack();
        panel.add(bodyLabel("Pick a colour for \"" + list.name() + "\"."));
        gap(panel, SPACE_MD);
        panel.add(TagEditor.palette(chosen));
        if (Dialogs.choose(owner, panel, "List colour", "Use this colour", "Cancel") != 0) return;
        try { tracker.editList(list.id(), list.name(), chosen[0]); changed.run(); }
        catch (Exception e) { Dialogs.error(owner, e.getMessage()); }
    }

    static void move(Component owner, Tracker tracker, TaskList list, int step, Runnable changed) {
        var order = new ArrayList<>(ordered(tracker.state()).stream().map(TaskList::id).toList());
        int at = order.indexOf(list.id());
        if (at < 0 || at + step < 0 || at + step >= order.size()) return;
        Collections.swap(order, at, at + step);
        try { tracker.reorderLists(order); changed.run(); }
        catch (Exception e) { Dialogs.error(owner, e.getMessage()); }
    }

    /**
     * Deleting a list asks what becomes of its tasks: they move to the Inbox
     * unless the owner says to delete them too. A backup is taken either way.
     */
    static void delete(Component owner, Tracker tracker, TaskList list, Consumer<String> pick) {
        long held = tracker.state().tasks().stream().filter(t -> list.id().equals(t.listId())).count();
        var message = stack();
        message.add(label("Delete the list \"" + list.name() + "\"?", TYPE_HEADING, TEXT));
        gap(message, SPACE_MD);
        var fate = new Segmented("list.delete.fate", 0, "Move its " + plural((int) held, "task") + " to the Inbox",
            "Delete its " + plural((int) held, "task") + " too");
        if (held == 0) message.add(bodyLabel("It holds no tasks."));
        else message.add(fate);
        gap(message, SPACE_SM);
        message.add(bodyLabel("A backup of the vault is kept first."));
        if (!Dialogs.confirmDestructive(owner, message, "Delete list", "Delete list")) return;
        try {
            tracker.deleteList(list.id(), fate.chosen() == 1);
            pick.accept(INBOX);
        } catch (Exception e) { Dialogs.error(owner, e.getMessage()); }
    }

    // ---------------------------------------------------------------- layout

    /**
     * The rail beside the board when there is room for both, and above it as
     * a row that wraps when there is not.
     *
     * The board needs its columns; at the window's minimum a rail beside it
     * would take the room the titles wrap into. Measured in the arrangement its
     * width calls for, as {@link Columns} is.
     */
    static final class Rail extends JPanel {
        /** The narrowest the pair stands side by side, at the designed text size. */
        private static final int BESIDE = SPACE_XXL * 25;
        /** The rail's width beside the board. */
        static int width() { return grow(SPACE_XXL * 6); }

        private final JPanel rail;
        private final JComponent board;
        private Boolean beside;

        Rail(JPanel rail, JComponent board) {
            this.rail = rail;
            this.board = board;
            setOpaque(false);
            setAlignmentX(0);
            rail.setOpaque(false);
            arrange(true);
        }

        boolean beside() { return beside != null && beside; }

        private boolean wants(int width) { return width <= 0 || width >= grow(BESIDE); }

        @Override public void doLayout() { arrange(wants(getWidth())); super.doLayout(); }

        @Override public Dimension getPreferredSize() {
            if (getWidth() > 0) arrange(wants(getWidth()));
            return super.getPreferredSize();
        }

        private void arrange(boolean side) {
            if (beside != null && beside == side) return;
            beside = side;
            removeAll();
            if (side) {
                rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
                var column = new JPanel(new BorderLayout());
                column.setOpaque(false);
                column.add(rail, BorderLayout.NORTH);
                column.setPreferredSize(new Dimension(width(), 0));
                setLayout(new BorderLayout(SPACE_LG, 0));
                add(column, BorderLayout.WEST);
                add(board, BorderLayout.CENTER);
            } else {
                rail.setLayout(new WrapFlowLayout(FlowLayout.LEFT, SPACE_XS, SPACE_XS));
                setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
                rail.setAlignmentX(0);
                board.setAlignmentX(0);
                add(rail);
                add(Box.createVerticalStrut(SPACE_MD));
                add(board);
            }
            for (Container holder = getParent(); holder != null; holder = holder.getParent())
                if (holder.getLayout() instanceof LayoutManager2 cached) cached.invalidateLayout(holder);
            revalidate();
            repaint();
        }
    }
}
