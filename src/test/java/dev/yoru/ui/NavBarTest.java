package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * The top bar: every tab whole and inside the bar at the smallest window and
 * every text size, roomy when the window is wide, and the open page marked.
 */
public final class NavBarTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static List<NavTab> tabs(Container root) {
        var out = new ArrayList<NavTab>();
        for (var c : root.getComponents()) {
            if (c instanceof NavTab t) out.add(t);
            if (c instanceof Container nested) out.addAll(tabs(nested));
        }
        return out;
    }

    private static void atSize(float scale, boolean smallest) throws Exception {
        Theme.textScale = scale;
        Theme.apply(ThemeId.MIDNIGHT);
        var probe = Preview.trackerApp(ThemeId.MIDNIGHT, 1440, 900);
        var min = probe.windowMinimum();
        int width = smallest ? min.width : Math.max(1600, min.width + 400);
        var app = Preview.trackerApp(ThemeId.MIDNIGHT, width, 900);
        Preview.layout(app);
        var all = tabs(app);
        check(all.size() == Preview.PAGES.length, "One tab per page at " + scale + "x, got " + all.size());
        var strip = all.getFirst().getParent();
        int previousBottom = Integer.MIN_VALUE;
        for (var tab : all) {
            var b = tab.getBounds();
            check(b.x >= 0 && b.x + b.width <= strip.getWidth() + 1,
                tab.getText() + " sits inside the bar at " + scale + "x, " + width + " wide: " + b + " in " + strip.getWidth());
            var metrics = tab.getFontMetrics(tab.getFont());
            var in = tab.getInsets();
            check(metrics.stringWidth(tab.getText()) + tab.getIcon().getIconWidth() + tab.getIconTextGap() <= b.width - in.left - in.right,
                tab.getText() + " is whole at " + scale + "x, " + width + " wide");
            check(b.y >= previousBottom + Theme.SPACE_XS, "Sidebar destinations never overlap, at " + scale + "x");
            previousBottom = b.y + b.height;
            if (!smallest) check(b.width >= tab.getPreferredSize().width, tab.getText() + " gets its full padding when there is room");
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (float scale : new float[]{1f, 1.25f, 1.5f, 2f}) { atSize(scale, true); atSize(scale, false); }
                Theme.textScale = 1f;
                Theme.apply(ThemeId.MIDNIGHT);
                var app = Preview.trackerApp(ThemeId.MIDNIGHT, 1440, 900);
                Preview.layout(app);
                var all = tabs(app);
                var tasks = all.stream().filter(t -> t.getName().equals("Tasks")).findFirst().orElseThrow();
                tasks.doClick();
                Preview.layout(app);
                check(tasks.isCurrent() && all.stream().filter(NavTab::isCurrent).count() == 1, "Opening a page marks its tab, and only its tab");
                check(tasks.getAccessibleContext().getAccessibleName().endsWith(", current"), "and says so to a screen reader");
                var toggle = Preview.button(app, "sidebar.toggle");
                var sidebar = all.getFirst().getParent().getParent().getParent().getParent();
                toggle.doClick(); Preview.layout(app);
                check(!sidebar.isVisible(), "The sidebar can be hidden");
                Preview.button(app, "sidebar.toggle").doClick(); Preview.layout(app);
                check(sidebar.isVisible(), "The toolbar restores the sidebar");
                var sizeBefore = all.getFirst().getSize();
                all.getFirst().doClick();
                Preview.layout(app);
                check(all.getFirst().getSize().equals(sizeBefore), "Being current changes no tab's size");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("PASS: " + checks + " sidebar checks (destinations fit, navigation, current state and collapse)");
        System.exit(0);
    }
}
