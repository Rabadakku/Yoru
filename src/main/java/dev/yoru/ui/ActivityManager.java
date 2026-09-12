package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model;
import dev.yoru.domain.Model.Activity;
import javax.swing.*;
import java.awt.*;
import java.util.UUID;
import static dev.yoru.ui.Theme.*;

/**
 * Renaming and removing study activities (#38), shared by the Today picker and
 * the Data page so both pages offer the same outcomes.
 *
 * Three of them, and the wording says which is which: keep the label (rename,
 * which touches no record at all), keep the time (the sessions move to
 * {@link Model#UNCATEGORIZED} with the plans that point at them), or remove the
 * time with the activity. The confirmation quotes the session count and the
 * total duration before the choice is made, because those are the numbers the
 * deletion would take.
 *
 * Nothing here decides what a removal means — Tracker and Model do, in one
 * write. This class only asks, and reports what the vault said.
 */
final class ActivityManager {
    private ActivityManager() { }

    /** The two ways a removal can treat recorded time. */
    static final String KEEP = "Keep the time under " + Model.UNCATEGORIZED;
    static final String DELETE = "Delete the time too";

    interface Action { void run() throws Exception; }

    /**
     * What the confirmation says first: how much is at stake, and what survives
     * either way.
     */
    static String summary(String label, Tracker.ActivityUsage usage) {
        var text = new StringBuilder();
        if (usage.sessions() == 0) text.append("\"").append(label).append("\" has no recorded sessions.");
        else text.append("\"").append(label).append("\" has ").append(usage.sessions())
            .append(usage.sessions() == 1 ? " recorded session" : " recorded sessions")
            .append(" totalling ").append(Analytics.duration(usage.seconds())).append(".");
        var kept = new StringBuilder();
        if (usage.blocks() > 0) kept.append(usage.blocks()).append(usage.blocks() == 1 ? " planned block" : " planned blocks");
        if (usage.repeats() > 0) kept.append(kept.isEmpty() ? "" : ", ").append(usage.repeats())
            .append(usage.repeats() == 1 ? " weekly repeat" : " weekly repeats");
        if (usage.tasks() > 0) kept.append(kept.isEmpty() ? "" : ", ").append(usage.tasks())
            .append(usage.tasks() == 1 ? " task" : " tasks");
        if (!kept.isEmpty()) text.append(" Kept either way: ").append(kept).append(".");
        return text.toString();
    }

    /** Whether this activity may be removed right now, which a running timer forbids. */
    static boolean canRemove(Tracker tracker, UUID id) {
        var running = tracker.active();
        return running == null || !running.activityId().equals(id);
    }

    /** The vault-side rename, so the dialog and a test drive the same call. */
    static void rename(Tracker tracker, UUID id, String name) throws Exception {
        tracker.renameActivity(id, name);
    }

    /** The vault-side removal, so the dialog and a test drive the same call. */
    static void remove(Tracker tracker, UUID id, boolean keepTime) throws Exception {
        tracker.removeActivity(id, keepTime);
    }

    /** Rename one activity, by identity. */
    static void rename(Component parent, Tracker tracker, Activity activity, Runnable changed) {
        String name = Dialogs.input(parent, "Rename \"" + activity.name()
            + "\". Every session, block, repeat and task stays attached.", "Rename activity");
        if (name == null) return;
        apply(parent, () -> rename(tracker, activity.id(), name), changed);
    }

    /** Remove one activity, asking first what should happen to its recorded time. */
    static void remove(Component parent, Tracker tracker, Activity activity, Runnable changed) {
        if (!canRemove(tracker, activity.id())) {
            Dialogs.info(parent, "Clock out before deleting \"" + activity.name()
                + "\": it is timing right now.");
            return;
        }
        var usage = tracker.usage(activity.id());
        var body = stack();
        body.add(label(summary(activity.name(), usage), TYPE_LABEL, TEXT));
        gap(body, SPACE_MD);
        if (usage.sessions() == 0) {
            body.add(bodyLabel("Nothing it points at is deleted. This cannot be undone."));
            if (Dialogs.confirmDestructive(parent, body, "Delete activity", "Delete"))
                apply(parent, () -> remove(tracker, activity.id(), true), changed);
            return;
        }
        body.add(bodyLabel(KEEP + " — the recorded time stays in your totals, filed under "
            + Model.UNCATEGORIZED + "."));
        gap(body, SPACE_SM);
        body.add(bodyLabel(DELETE + " — the sessions above are deleted for good."));
        gap(body, SPACE_MD);
        body.add(bodyLabel("Planned blocks, weekly repeats and tasks are kept either way."));
        int choice = Dialogs.choose(parent, body, "Delete \"" + activity.name() + "\"", KEEP, DELETE, "Cancel");
        if (choice == 0) apply(parent, () -> remove(tracker, activity.id(), true), changed);
        else if (choice == 1) {
            var warning = stack();
            warning.add(label(usage.sessions() + (usage.sessions() == 1 ? " recorded session" : " recorded sessions")
                + " totalling " + Analytics.duration(usage.seconds()) + " will be deleted with \""
                + activity.name() + "\".", TYPE_LABEL, TEXT));
            gap(warning, SPACE_MD);
            warning.add(bodyLabel("This cannot be undone. Everything else keeps its records."));
            if (Dialogs.confirmDestructive(parent, warning, "Delete recorded time", "Delete time"))
                apply(parent, () -> remove(tracker, activity.id(), false), changed);
        }
    }

    /** Runs the change and refreshes the page it was made from, or says why not. */
    private static void apply(Component parent, Action action, Runnable changed) {
        try {
            action.run();
            changed.run();
        } catch (Exception e) {
            Dialogs.error(parent, e.getMessage());
        }
    }

    /**
     * The rows of an activity table, all of them sharing one grid.
     *
     * Each row used to be a flow of its own, so Rename and Delete landed
     * wherever the name and the figures happened to end: the pair stepped down
     * the card with the length of the label, and two rows of the same list put
     * the same two buttons 30 px apart. One grid for every row gives the names,
     * the figures and the controls a column each, so the controls line up down
     * the whole card.
     */
    static JPanel activityTable() {
        var table = new JPanel(new GridBagLayout());
        table.setOpaque(false);
        table.setAlignmentX(0);
        return table;
    }

    /**
     * One activity's row in that grid: name, figures, Rename, Delete.
     *
     * The figures take the slack ({@code weightx 1, fill HORIZONTAL}), which is
     * what holds the two controls against the row's right edge on every row, and
     * both controls are given the wider of their labels plus {@code PAD_H} a
     * side, so the column is one width whether the label is "Rename" or
     * "Delete". The height is left alone, so the buttons stay on the control
     * ladder.
     */
    static void activityRow(JPanel table, int row, JComponent name, JComponent detail,
                            JComponent rename, JComponent remove) {
        int width = Math.max(rename.getPreferredSize().width, remove.getPreferredSize().width);
        rename.setPreferredSize(new Dimension(width, rename.getPreferredSize().height));
        remove.setPreferredSize(new Dimension(width, remove.getPreferredSize().height));
        var cell = new GridBagConstraints();
        cell.gridy = row;
        cell.anchor = GridBagConstraints.WEST;
        // SPACE_XS above and below is the row's share of the vertical rhythm,
        // exactly what the flow it replaced carried.
        cell.insets = new Insets(SPACE_XS, 0, SPACE_XS, SPACE_MD);
        cell.gridx = 0;
        table.add(name, cell);
        cell.gridx = 1;
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        table.add(detail, cell);
        cell.weightx = 0;
        cell.fill = GridBagConstraints.NONE;
        cell.gridx = 2;
        table.add(rename, cell);
        cell.gridx = 3;
        cell.insets = new Insets(SPACE_XS, 0, SPACE_XS, 0);
        table.add(remove, cell);
    }

    /**
     * Every activity with its session count and recorded time, and the two
     * controls that change it.
     *
     * On the Data page rather than in a dialog, so the numbers a removal would
     * act on are legible before anything is opened.
     */
    static JPanel activities(Tracker tracker, Component parent, Runnable changed) {
        var box = card();
        box.add(sectionHeader("ACTIVITIES"));
        gap(box, SPACE_SM);
        box.add(bodyLabel("Rename a label, or delete an activity and choose what happens to its recorded time."));
        gap(box, SPACE_MD);
        if (tracker.state().activities().isEmpty()) {
            box.add(emptyState("No activities yet.","Create one to start a session.",null));
            return box;
        }
        var table = activityTable();
        int row = 0;
        for (var activity : tracker.state().activities()) {
            var usage = tracker.usage(activity.id());
            var name = label(activity.name(), TYPE_PROSE, TEXT);
            var recorded = label(usage.sessions() + (usage.sessions() == 1 ? " session" : " sessions")
                + " · " + Analytics.duration(usage.seconds()), TYPE_BODY, MUTED);
            // Named so the count and duration a removal quotes can be read back
            // off the page that offers it.
            recorded.setName("activity.recorded." + activity.id());
            var rename = button("Rename", () -> rename(parent, tracker, activity, changed));
            rename.setName("activity.rename." + activity.id());
            var remove = button("Delete", () -> remove(parent, tracker, activity, changed));
            remove.setName("activity.remove." + activity.id());
            boolean timing = !canRemove(tracker, activity.id());
            remove.setEnabled(!timing);
            remove.setToolTipText(timing ? "Clock out before deleting this activity" : "Delete this activity");
            activityRow(table, row++, name, recorded, rename, remove);
        }
        box.add(table);
        return box;
    }
}
