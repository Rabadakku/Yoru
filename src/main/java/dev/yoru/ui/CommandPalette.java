package dev.yoru.ui;

import dev.yoru.application.QuickAdd;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import static dev.yoru.ui.Theme.*;

/**
 * Cmd/Ctrl-K: one line to go anywhere, or to write a task down from anywhere
 * (#74).
 *
 * Typed words that name a command — "tasks", "go to sched", "new page" — list
 * that command first. Anything else is offered as a task, read the same way
 * the Tasks page's line reads it, with what it will be due, tagged and filed
 * under shown beneath it; Enter adds it to the Inbox, or to the list it names.
 * Arrows move, Enter takes, Escape leaves, as the page switcher does.
 */
final class CommandPalette {
    private CommandPalette() { }

    /** One answer: what it says, what it will do in a few words, and the doing. */
    record Command(String name, String detail, String id, Runnable run) { }

    /** The commands for this line, the best first. Package-private: the window is modal, the list is what tests drive. */
    static List<Command> commands(YoruApp app, String typed) {
        var query = typed == null ? "" : typed.strip();
        var fixed = new ArrayList<Command>();
        for (var page : PAGES) fixed.add(new Command("Go to " + page, "Page", "go." + page.toLowerCase(Locale.ROOT), () -> app.show(page)));
        fixed.add(new Command("New page", "Pages", "page.new", app::newNote));
        fixed.add(new Command("Log time…", "Record time already spent", "time.log", () -> app.timeDialog(false)));
        fixed.add(new Command("Plan a block…", "Schedule", "time.plan", () -> app.timeDialog(true)));
        fixed.add(new Command("Toggle sidebar", "View", "view.sidebar", app::toggleSidebar));
        if (query.isEmpty()) return fixed;
        var matching = fixed.stream().filter(c -> matches(c.name(), query)).toList();
        var out = new ArrayList<Command>(matching);
        var task = addTask(app, query);
        if (task != null) {
            // A line that names a command is that command; otherwise it is a task.
            if (matching.isEmpty()) out.addFirst(task); else out.add(task);
        }
        return out;
    }

    private static final List<String> PAGES = List.of("Today", "Tasks", "Pages", "Habits", "Schedule", "Data", "Settings");

    /** Every typed word begins some word of the command's name. */
    private static boolean matches(String name, String query) {
        var words = Arrays.stream(name.toLowerCase(Locale.ROOT).split("[\\s…]+")).toList();
        for (var typed : query.toLowerCase(Locale.ROOT).split("\\s+"))
            if (words.stream().noneMatch(w -> w.startsWith(typed))) return false;
        return true;
    }

    /** The line as a task, or null when there is nothing to call it. */
    private static Command addTask(YoruApp app, String line) {
        var tracker = app.tracker();
        var state = tracker.state();
        QuickAdd.Result read;
        if (QuickAddSetting.enabled()) {
            var context = new QuickAdd.Context(app.today(), state.settings().weekStartsOn(),
                state.tags().stream().map(t -> t.name()).toList(), state.lists().stream().map(l -> l.name()).toList());
            read = QuickAdd.parse(line, context);
        } else {
            read = new QuickAdd.Result(line, null, null, List.of(), null, dev.yoru.domain.Model.Priority.NONE, null, List.of());
        }
        if (read.title().isBlank()) return null;
        var result = read;
        return new Command("Add task “" + result.title() + "”", summary(result), "task.add", () -> app.perform(() -> {
            var draft = QuickAdd.draft(result, tracker.state(), null, app.today(), app.now(), TagEditor::paletteColour);
            tracker.properties().save(draft.task(), draft.newTags(), Map.of());
        }));
    }

    /** What an added line will be, in a line: "Due Thu, Oct 8, 5:00 PM · #school · High · every Monday · Inbox". */
    static String summary(QuickAdd.Result r) {
        var bits = new ArrayList<String>();
        // No date typed is due today, as a task written down is (#67).
        bits.add((r.due() == null ? "Due today" : "Due " + r.due().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)))
            + (r.time() == null ? "" : ", " + DateText.time(r.time())));
        for (var tag : r.tags()) bits.add("#" + tag);
        if (r.priority() != dev.yoru.domain.Model.Priority.NONE) bits.add(r.priority().label);
        if (r.repeat() != null) bits.add(RepeatField.inSentence(r.repeat()));
        bits.add(r.list() == null ? "Inbox" : r.list());
        return String.join(" · ", bits);
    }

    /** Opens the palette over the window and does what is chosen. */
    static void open(YoruApp app) {
        var window = SwingUtilities.getWindowAncestor(app);
        var dialog = new JDialog(window, "Command palette", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        var field = styleInput(new JTextField(36));
        field.setName("palette.query");
        field.setFont(proseFont());
        field.getAccessibleContext().setAccessibleName("Go to, or type a task to add");
        var model = new DefaultListModel<Command>();
        var list = new JList<>(model);
        list.setName("palette.results");
        list.setFocusable(false);
        list.setBackground(PANEL);
        list.setSelectionBackground(shade(ACCENT_TEXT, DARK ? -90 : 95));
        list.setVisibleRowCount(9);
        list.setCellRenderer((l, row, index, selected, focus) -> {
            var cell = new JPanel(new BorderLayout(0, 2));
            cell.setOpaque(true);
            cell.setBackground(selected ? l.getSelectionBackground() : PANEL);
            cell.setBorder(new EmptyBorder(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD));
            cell.add(shortenable(row.name(), TYPE_LABEL, row.id().equals("task.add") ? ACCENT_TEXT : TEXT), BorderLayout.NORTH);
            cell.add(shortenable(row.detail(), TYPE_CAPTION, MUTED), BorderLayout.CENTER);
            return cell;
        });
        var hint = label("Type to go somewhere, or write a task: “Essay tomorrow 5pm #school”", TYPE_CAPTION, MUTED);
        var scroll = new JScrollPane(list);
        scroll.setBorder(new MatteBorder(HAIRLINE, 0, 0, 0, LINE));
        scroll.getViewport().setBackground(PANEL);
        var box = new JPanel(new BorderLayout(0, SPACE_SM));
        box.setBackground(PANEL);
        box.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(SPACE_MD, SPACE_MD, SPACE_MD, SPACE_MD)));
        box.add(field, BorderLayout.NORTH);
        box.add(scroll, BorderLayout.CENTER);
        box.add(hint, BorderLayout.SOUTH);
        dialog.setContentPane(box);
        Command[] taken = {null};
        Runnable refill = () -> {
            model.clear();
            for (var c : commands(app, field.getText())) model.addElement(c);
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refill.run(); }
            public void removeUpdate(DocumentEvent e) { refill.run(); }
            public void changedUpdate(DocumentEvent e) { }
        });
        field.addActionListener(e -> { taken[0] = list.getSelectedValue(); dialog.dispose(); });
        var keys = field.getInputMap();
        keys.put(KeyStroke.getKeyStroke("DOWN"), "palette.down");
        keys.put(KeyStroke.getKeyStroke("UP"), "palette.up");
        keys.put(KeyStroke.getKeyStroke("ESCAPE"), "palette.close");
        field.getActionMap().put("palette.down", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { list.setSelectedIndex(Math.min(model.size() - 1, list.getSelectedIndex() + 1)); list.ensureIndexIsVisible(list.getSelectedIndex()); }
        });
        field.getActionMap().put("palette.up", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1)); list.ensureIndexIsVisible(list.getSelectedIndex()); }
        });
        field.getActionMap().put("palette.close", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { dialog.dispose(); }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int at = list.locationToIndex(e.getPoint());
                if (at >= 0) { taken[0] = model.get(at); dialog.dispose(); }
            }
        });
        refill.run();
        dialog.pack();
        dialog.setSize(Math.max(dialog.getWidth(), grow(560)), dialog.getHeight());
        dialog.setLocationRelativeTo(window);
        if (window != null) dialog.setLocation(dialog.getX(), window.getY() + window.getHeight() / 6);
        dialog.setVisible(true);
        if (taken[0] != null) taken[0].run().run();
    }
}
