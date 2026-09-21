package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import java.util.prefs.Preferences;
import javax.swing.*;

/**
 * The text size is this computer's, one of the offered steps, and reaches every
 * face Theme hands out (#31). Settings offers each step and marks the one in
 * use; choosing one keeps it. Runs under the suite's in-memory preferences.
 */
public final class TextSizeTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        var failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] != null) {
            failure[0].printStackTrace();
            // The window built here leaves Swing's threads running.
            System.exit(1);
        }
        System.out.println("PASS: " + checks + " text size checks (default, saved, refused, faces, Settings, drawn views)");
        System.exit(0);
    }

    private static void run() throws Exception {
        var saved = Preferences.userRoot().node("dev/yoru/desktop");
        saved.remove("text.size");
        check(TextSize.saved() == 100, "with nothing saved, text is drawn at its designed size");
        check(TextSize.STEPS.equals(java.util.List.of(100, 125, 150, 200)), "Settings offers 100, 125, 150 and 200%");

        TextSize.choose(150);
        check(TextSize.saved() == 150 && TextSize.current() == 150, "a chosen size is used and kept");
        check(Theme.labelFont().getSize() == Math.round(Theme.TYPE_LABEL * 1.5f), "a control's face grows with it");
        check(Theme.timerFont().getSize() == Math.round(Theme.TYPE_TIMER * 1.5f), "so does the timer's");
        Theme.apply(ThemeId.MIDNIGHT);
        check(((java.awt.Font) UIManager.get("Button.font")).getSize() == Theme.labelFont().getSize(),
            "the look-and-feel's faces are installed at the new size");

        saved.putInt("text.size", 175);
        check(TextSize.saved() == 100, "a saved size that is no longer offered falls back to the designed size");
        boolean refused = false;
        try { TextSize.use(175); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "only an offered size can be used");

        TextSize.choose(100);
        Theme.apply(ThemeId.MIDNIGHT);
        var app = Preview.trackerApp(ThemeId.MIDNIGHT, 1280, 900);
        Preview.button(app, "Settings").doClick();
        Preview.layout(app);
        for (int step : TextSize.STEPS) {
            var pick = named(app, "textSize." + step);
            check(pick != null && pick.getText().equals(step + "%"), "Settings offers " + step + "%");
            check(pick.getBackground().equals(Theme.CYAN) == (step == 100), step + "% is marked only when it is in use");
        }
        named(app, "textSize.125").doClick();
        check(TextSize.saved() == 125 && TextSize.current() == 125, "choosing 125% in Settings keeps it for this computer");
        TextSize.use(100);
        drawnViews();
        TextSize.use(100);
    }

    /**
     * The views that are drawn rather than laid out follow the text too.
     *
     * A layout grows because its labels do; a painted view only grows if its
     * own measurements are asked to. The heat map's were not, so at 200% the
     * month names were drawn half above the top of it, the weekday initials ran
     * into the first column of days, and the cells stayed the size they were
     * designed at under text twice as tall.
     */
    private static void drawnViews() throws Exception {
        var today = java.time.LocalDate.now();
        TextSize.use(100);
        var designed = new Heatmap(java.util.Map.of(), today, 4, java.time.DayOfWeek.MONDAY);
        int small = designed.getPreferredSize().height;
        designed.setSize(1000, small);
        check(reading(designed, 40, 30) != null, "at 100% the map's first row of days is where it was");

        TextSize.use(200);
        var large = new Heatmap(java.util.Map.of(), today, 4, java.time.DayOfWeek.MONDAY);
        int tall = large.getPreferredSize().height;
        check(tall > small * 3 / 2, "at 200% the heat map asks for the room its text needs: "
            + tall + " against " + small);
        large.setSize(1000, tall);
        check(reading(large, 100, 30) == null, "the band the months are written in has grown with them");
        check(reading(large, 100, tall / 2) != null, "and the days are still read under it");
        // The column the weekday initials sit in has grown with them as well:
        // a point that was the first day of a week at 100% is now beside it.
        check(reading(large, 40, tall / 2) == null, "the initials' column has grown with them too");
        drawnCalendar();
        drawnWeek();
    }

    /**
     * The month calendar is drawn too, and was cut in the same three ways: the
     * weekday names lost their tops to the edge of the component, the day
     * numbers sat over the chips under them, and a chip held two-thirds of a
     * line of its title.
     */
    private static void drawnCalendar() {
        var today = java.time.LocalDate.now();
        var month = java.time.YearMonth.from(today);
        var empty = dev.yoru.domain.Model.State.empty();

        TextSize.use(100);
        var designed = new TaskCalendar(empty, month, today, null);
        int small = designed.getPreferredSize().height;
        designed.setSize(880, small);
        designed.relayout();
        check(designed.dateAt(new java.awt.Point(20, 30)) != null,
            "at 100% the calendar's first row of days is where it was");

        TextSize.use(200);
        var large = new TaskCalendar(empty, month, today, null);
        int tall = large.getPreferredSize().height;
        check(tall > small, "at 200% the calendar asks for the room its text needs: " + tall + " against " + small);
        large.setSize(880, tall);
        large.relayout();
        check(large.dateAt(new java.awt.Point(20, 30)) == null,
            "the band the weekday names are written in has grown with them");
        check(large.dateAt(new java.awt.Point(20, tall / 2)) != null, "and the days are still under it");
    }

    /**
     * The week grid is the third drawn view, and was the worst cut of the
     * three: at 200% the day names were printed over the lane labels beneath
     * them, the hours ran into the grid, and every block's title sat outside
     * the block it belonged to.
     */
    private static void drawnWeek() throws Exception {
        var repo = new Memory();
        var tracker = new dev.yoru.application.Tracker(repo, java.time.Clock.systemUTC());
        tracker.addActivity("Invented study", 0);
        var activity = tracker.state().activities().getFirst().id();
        var monday = java.time.LocalDate.parse("2026-09-07");
        var zone = java.time.ZoneId.of("UTC");
        tracker.plan(activity, java.time.Instant.parse("2026-09-08T09:00:00Z"),
            java.time.Instant.parse("2026-09-08T11:00:00Z"));
        var block = tracker.state().blocks().getFirst().id();
        var now = java.time.Instant.parse("2026-09-09T15:00:00Z");

        TextSize.use(100);
        int small = new ScheduleGrid(tracker.state(), monday, zone, now).getPreferredSize().height;
        TextSize.use(200);
        var large = new ScheduleGrid(tracker.state(), monday, zone, now);
        int tall = large.getPreferredSize().height;
        check(tall > small, "at 200% the week grid asks for the room its text needs: " + tall + " against " + small);
        large.setSize(900, tall);
        // Self-consistent at that size: the grid reports a block where it drew it.
        check(block.equals(large.actionableAt(large.pointOn(block))),
            "and a block is still where the grid says it is");
    }

    private static final class Memory implements dev.yoru.application.Repository {
        dev.yoru.domain.Model.State state = dev.yoru.domain.Model.State.empty();
        public dev.yoru.domain.Model.State load() { return state; }
        public void save(dev.yoru.domain.Model.State next) { state = next; }
        public void close() { }
    }

    /** What the map says about the day under a point, or null where there is no day. */
    private static String reading(Heatmap map, int x, int y) {
        return map.getToolTipText(new java.awt.event.MouseEvent(map,
            java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false));
    }

    private static JButton named(java.awt.Container root, String name) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton b && name.equals(b.getName())) return b;
            if (child instanceof java.awt.Container nested) {
                var found = named(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
