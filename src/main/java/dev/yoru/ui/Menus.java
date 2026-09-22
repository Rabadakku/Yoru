package dev.yoru.ui;

import javax.swing.*;
import javax.swing.border.*;
import static dev.yoru.ui.Theme.*;

/** The app's right-click and ⋯ menus, drawn in the theme rather than the look-and-feel's colours. */
final class Menus {
    private Menus() { }

    static JPopupMenu popup() {
        var menu = new JPopupMenu() {
            // The look-and-feel's separator is drawn in its own highlight colour,
            // which on these palettes was a bright accent line across the menu.
            @Override public void addSeparator() {
                var line = new JPopupMenu.Separator();
                line.setForeground(LINE);
                line.setBackground(PANEL);
                add(line);
            }
        };
        menu.setBackground(PANEL);
        menu.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(SPACE_XS, 0, SPACE_XS, 0)));
        return menu;
    }

    /** One entry; a disabled one says why in its tooltip. The name is for the tests. */
    static JMenuItem item(String text, String name, boolean enabled, Runnable action, String whyNot) {
        var item = new JMenuItem(text);
        item.setName(name);
        item.setFont(labelFont());
        item.setOpaque(true);
        item.setBackground(PANEL);
        item.setForeground(TEXT);
        item.setBorder(new EmptyBorder(SPACE_XS, SPACE_MD, SPACE_XS, SPACE_MD));
        item.setEnabled(enabled);
        if (!enabled && whyNot != null) item.setToolTipText(whyNot);
        item.addActionListener(e -> action.run());
        return item;
    }
}
