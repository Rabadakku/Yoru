package dev.yoru.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.UUID;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * Move up and Move down for a record in a list the owner orders (#59): a pair
 * of small arrows beside a row's other controls, for rows that show their
 * controls rather than keep them in a ⋯ menu (activities on Data, tags).
 *
 * A move rebuilds the rows, so focus fell back to the window and moving an
 * item three places from the keyboard meant finding it again each time. Focus
 * goes back to the same arrow, now on the item's new row, or to the other arrow
 * once the item reaches the end and this one is switched off.
 */
final class Reorder {
    private Reorder() { }

    /** Moves the item one place, -1 for up and 1 for down, and rebuilds what shows it. */
    interface Move { void run(int direction) throws Exception; }

    /**
     * The arrows for one item, named {@code prefix.up.id} and {@code prefix.down.id}.
     *
     * {@code root} holds the rebuilt rows, where focus is looked for afterwards,
     * and is what a refusal is reported over. {@code quiet} draws them as ghost
     * buttons, for rows whose other controls are ghosts too.
     */
    static JComponent buttons(Component root, String prefix, UUID id, String label, int at, int count,
                              boolean quiet, Move move) {
        var pair = new JPanel();
        pair.setOpaque(false);
        pair.setLayout(new BoxLayout(pair, BoxLayout.X_AXIS));
        pair.add(arrow(root, prefix, id, label, -1, at > 0, quiet, move));
        pair.add(Box.createHorizontalStrut(SPACE_XS));
        pair.add(arrow(root, prefix, id, label, 1, at < count - 1, quiet, move));
        return pair;
    }

    private static JButton arrow(Component root, String prefix, UUID id, String label, int direction,
                                 boolean enabled, boolean quiet, Move move) {
        boolean up = direction < 0;
        String name = prefix + (up ? ".up." : ".down.") + id;
        String other = prefix + (up ? ".down." : ".up.") + id;
        var arrow = button(up ? "↑" : "↓", () -> {
            try {
                move.run(direction);
            } catch (Exception refused) {
                Dialogs.error(root, refused.getMessage());
                return;
            }
            var again = find(root, name);
            if (!(again instanceof JButton button && button.isEnabled())) again = find(root, other);
            if (again != null) again.requestFocusInWindow();
        });
        if (quiet) ghost(arrow);
        arrow.setName(name);
        arrow.setEnabled(enabled);
        arrow.setToolTipText(enabled ? (up ? "Move up" : "Move down") : (up ? "Already first" : "Already last"));
        arrow.getAccessibleContext().setAccessibleName("Move " + label + (up ? " up" : " down"));
        return arrow;
    }

    private static Component find(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container holder)
            for (var child : holder.getComponents()) {
                var found = find(child, name);
                if (found != null) return found;
            }
        return null;
    }
}
