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
                        assert item.getAccelerator() != null;
                    }
                    for (int i=0;i<bar.getMenu(1).getItemCount();i++) {
                        var item = bar.getMenu(1).getItem(i);
                        if (item != null) { assert item.getAccelerator() != null; item.doClick(); }
                    }
                    app.closeVault();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            System.out.println("PASS: standard menus, navigation accelerators and safe edit actions without a text focus");
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
