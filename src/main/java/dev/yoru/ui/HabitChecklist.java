package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.Habit;
import dev.yoru.domain.Model.HabitKind;
import java.awt.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import static dev.yoru.ui.Theme.*;

/**
 * Today's daily habits, as a list you tick off.
 *
 * Checking a habit off and looking at how a habit is going are different jobs,
 * and they were in the same card: the Habits page mixed a "Done today" button
 * with a month of history. The ticking lives here, beside the tasks on the
 * Tasks page and on Today (#54), and the streaks and the history stay on the
 * Habits page.
 *
 * One row per habit: the box, the name, and how long the run is. Nothing else,
 * because everything else is a question for the other page.
 */
final class HabitChecklist {
    private HabitChecklist() { }

    /** The daily habits, each with today's box. Empty when there are none. */
    static JPanel card(Tracker tracker, Runnable refresh, java.util.function.Consumer<Exception> onError) {
        return card(tracker, refresh, onError, Clock.systemUTC());
    }

    /**
     * The same, on a given clock. Each habit's today is read in the habit's
     * own zone, so the day turns over at that zone's midnight whatever the
     * clock's zone is.
     */
    static JPanel card(Tracker tracker, Runnable refresh, java.util.function.Consumer<Exception> onError, Clock clock) {
        var daily = tracker.state().habits().stream().filter(h -> h.kind() == HabitKind.DAILY).toList();
        var card = Theme.card();
        int done = 0;
        for (var habit : daily) if (checkedToday(habit, clock)) done++;
        card.add(cardHead(sectionHeader("HABITS TODAY"),
            label(daily.isEmpty() ? "" : done + " of " + daily.size() + " done", TYPE_CAPTION,
                done == daily.size() ? ACCENT_TEXT : MUTED)));
        gap(card, SPACE_SM);
        if (daily.isEmpty()) {
            card.add(bodyLabel("No daily habits yet. Add one on the Habits page."));
            return card;
        }
        var boxes = new ArrayList<JCheckBox>();
        for (var habit : daily) {
            var row = row(tracker, habit, refresh, onError, clock);
            boxes.add((JCheckBox) ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER));
            card.add(row);
        }
        arrows(boxes);
        return card;
    }

    /**
     * Up and Down step through the list as they do through a list anywhere
     * else, and Space ticks the one that has focus: the whole morning's
     * habits can be ticked off without the mouse.
     */
    private static void arrows(List<JCheckBox> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            var box = boxes.get(i);
            var above = i > 0 ? boxes.get(i - 1) : null;
            var below = i + 1 < boxes.size() ? boxes.get(i + 1) : null;
            step(box, "UP", "habit.previous", above);
            step(box, "DOWN", "habit.next", below);
        }
    }

    private static void step(JCheckBox from, String key, String name, JCheckBox to) {
        from.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        from.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (to != null) to.requestFocusInWindow();
            }
            @Override public boolean isEnabled() { return to != null; }
        });
    }

    /** Whether this habit is checked off for today, in the habit's own zone. */
    static boolean checkedToday(Habit habit, Clock clock) {
        return habit.checkIns().contains(today(habit, clock));
    }

    /** Today where the habit is kept, which is where its day begins and ends. */
    static LocalDate today(Habit habit, Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(habit.zone()));
    }

    private static JPanel row(Tracker tracker, Habit habit, Runnable refresh,
                              java.util.function.Consumer<Exception> onError, Clock clock) {
        var today = today(habit, clock);
        boolean done = habit.checkIns().contains(today);
        var line = new JPanel(new BorderLayout(SPACE_SM, 0));
        line.setOpaque(false);
        line.setAlignmentX(0);
        line.setBorder(new EmptyBorder(SPACE_XS, 0, SPACE_XS, 0));

        var check = new JCheckBox(habit.name(), done);
        check.setOpaque(false);
        check.setFont(labelFont());
        // A finished habit steps back, the way a finished task does.
        check.setForeground(done ? MUTED : TEXT);
        check.setName("habit.today." + habit.id());
        check.setToolTipText(habit.name());
        check.getAccessibleContext().setAccessibleName(habit.name() + (done ? ", done today" : ", not done today"));
        check.addActionListener(e -> {
            try {
                tracker.checkIn(habit.id(), today, check.isSelected());
                refresh.run();
            } catch (Exception failure) {
                onError.accept(failure);
            }
        });
        line.add(check, BorderLayout.CENTER);

        int streak = habit.streak(today);
        var run = label(streak == 0 ? "" : streak + " day streak", TYPE_CAPTION, streak == 0 ? MUTED : ACCENT_TEXT);
        run.setToolTipText(streak == 0 ? "Not started yet" : "Checked off " + streak + " days in a row");
        line.add(run, BorderLayout.EAST);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        return line;
    }
}
