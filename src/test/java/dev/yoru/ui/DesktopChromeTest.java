package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.swing.*;
import java.util.*;

/** Standard navigation menus use the same workspace actions as the sidebar. */
public final class DesktopChromeTest {
    public static void main(String[] args) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Theme.install();
                    var app = Preview.trackerApp(ThemeId.MIDNIGHT,1280,900);
                    var bar = DesktopChrome.menus(app,null);
                    var names = new ArrayList<String>();
                    for (int i=0;i<bar.getMenuCount();i++) names.add(bar.getMenu(i).getText());
                    assert names.equals(List.of("File","Edit","View","Window","Help"));
                    var view = bar.getMenu(2);
                    for (int i=2;i<view.getItemCount();i++) {
                        var item = view.getItem(i); item.doClick(); Preview.layout(app);
                        var nav = (NavTab)Preview.button(app,item.getText());
                        assert nav.isCurrent() : item.getText();
                        assert nav.getAccessibleContext().getAccessibleStateSet().contains(javax.accessibility.AccessibleState.SELECTED);
                        assert item.getAccelerator() != null;
                    }
                    for (int i=0;i<bar.getMenu(1).getItemCount();i++) {
                        var item = bar.getMenu(1).getItem(i);
                        if (item != null) { assert item.getAccelerator() != null; item.doClick(); }
                    }
                    var edit = bar.getMenu(1);
                    var field = new JTextArea();
                    DesktopChrome.updateEditMenu(edit, field);
                    assert !edit.getItem(0).isEnabled() && !edit.getItem(1).isEnabled();
                    assert !edit.getItem(4).isEnabled() && edit.getItem(5).isEnabled();
                    field.append("Sample text"); field.selectAll();
                    DesktopChrome.updateEditMenu(edit, field);
                    assert edit.getItem(0).isEnabled() && edit.getItem(3).isEnabled() && edit.getItem(4).isEnabled();
                    field.getActionMap().get("yoru.undo").actionPerformed(null);
                    DesktopChrome.updateEditMenu(edit, field);
                    assert !edit.getItem(0).isEnabled() && edit.getItem(1).isEnabled();
                    field.getActionMap().get("yoru.redo").actionPerformed(null);
                    field.selectAll(); field.setEditable(false);
                    DesktopChrome.updateEditMenu(edit, field);
                    assert !edit.getItem(0).isEnabled() && !edit.getItem(3).isEnabled();
                    assert edit.getItem(4).isEnabled() && !edit.getItem(5).isEnabled();
                    var secret = new JPasswordField("synthetic"); secret.selectAll();
                    DesktopChrome.updateEditMenu(edit, secret);
                    assert !edit.getItem(3).isEnabled() && !edit.getItem(4).isEnabled();
                    assert Theme.button("Test", () -> {}).isRolloverEnabled();
                    assert new JScrollBar().getUI() instanceof Theme.QuietScrollBarUI;
                    app.closeVault();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            System.out.println("PASS: standard menus, navigation accelerators and safe edit actions without a text focus");
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
