package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.domain.Model.ThemeId;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/**
 * The visual system's rules that can be checked rather than eyeballed (#9):
 * bold 28 px page titles, rounded cards, no breadcrumb over flat pages, Today's
 * agenda before its statistics, Settings in four sections with the vault among
 * them, compact theme choices, and a page that keeps its place when rebuilt.
 * Invented data only.
 */
public final class VisualSystemTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private interface Action { void run() throws Exception; }

    private static void edt(Action action) throws Exception {
        var failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception exception) throw exception;
    }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        edt(VisualSystemTest::titlesAndCards);
        edt(VisualSystemTest::pages);
        edt(VisualSystemTest::dailyGoal);
        edt(VisualSystemTest::wrappingRowsAskAgain);
        edt(VisualSystemTest::todayAtAGlance);
        headersKeepNames();
        System.out.println("PASS: " + checks + " visual system checks (titles, cards, breadcrumb, Today order, Settings sections, scroll, Today without scrolling)");
        // The windows built here leave Swing's threads running.
        System.exit(0);
    }

    /** Sentence case for a header, but a name keeps its capitals and mixed case is left as written. */
    private static void headersKeepNames() {
        check(Theme.quietCase("INTEGRATIONS · ANKI").equals("Integrations · Anki"), "Anki keeps its capital");
        check(Theme.quietCase("INTEGRATIONS · AI ASSISTANTS").equals("Integrations · AI assistants"), "AI stays AI");
        check(Theme.quietCase("TRACKING · POMODORO").equals("Tracking · pomodoro"), "Other words go to sentence case");
        check(Theme.quietCase("FAILED AGAIN").equals("Failed again"), "A name inside a word is not a name");
        check(Theme.quietCase("Daily totals · America/New_York").equals("Daily totals · America/New_York"), "Mixed case is left alone");
    }

    private static void titlesAndCards() {
        for (var theme : ThemeId.values()) {
            Theme.apply(theme);
            var title = Theme.title("Today");
            check(title.getFont().getSize() == 28 && title.getFont().isBold(), theme + ": a page title is 28 px and bold");
            var card = Theme.card();
            check(card instanceof Theme.CardPanel && !card.isOpaque(), theme + ": a card paints its own surface");
            card.setSize(120, 80);
            var image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            g.setColor(Theme.BG);
            g.fillRect(0, 0, 120, 80);
            card.paint(g);
            g.dispose();
            check(image.getRGB(0, 0) == Theme.BG.getRGB(), theme + ": a card's corner is round, so the ground shows there");
            check(image.getRGB(60, 40) == Theme.PANEL.getRGB(), theme + ": and its middle is the panel");
            check(image.getRGB(60, 0) == Theme.PANEL.getRGB(), theme + ": the surface is not enclosed by a border");
        }
        Theme.apply(ThemeId.MIDNIGHT);
    }

    private static void dailyGoal() throws Exception {
        var repo=new Memory();
        var now=java.time.Instant.parse("2026-09-20T12:00:00Z");
        var tracker=new Tracker(repo,Clock.fixed(now,java.time.ZoneOffset.UTC));
        tracker.addActivity("Invented study",30);
        var activity=tracker.state().activities().getFirst().id();
        var goal=new DailyGoal(tracker,java.time.ZoneOffset.UTC);
        var progress=(JProgressBar)named(goal,"today.goal.progress");
        check(progress.getValue()==0,"A new day starts at zero");
        int hours=tracker.state().settings().dailyGoalHours();
        tracker.log(activity,now.minusSeconds(hours*1800L),now);
        goal=new DailyGoal(tracker,java.time.ZoneOffset.UTC);
        progress=(JProgressBar)named(goal,"today.goal.progress");
        check(progress.getValue()==50,"Recorded time fills half of the daily goal");
        tracker.log(activity,now.minusSeconds(hours*5400L),now.minusSeconds(hours*1800L));
        goal=new DailyGoal(tracker,java.time.ZoneOffset.UTC);
        progress=(JProgressBar)named(goal,"today.goal.progress");
        check(progress.getValue()==100,"Over-goal time caps the bar");
        check(progress.getAccessibleContext().getAccessibleDescription().contains("Goal reached"),
            "Goal completion is announced in words as well as colour");
    }

    /**
     * A row that is given a new width asks to be measured again.
     *
     * This is what corrects the running app after a resize: the column that
     * holds the row caches what it measured, and the row is measured before it
     * is given its width, so without the asking the column keeps handing out a
     * height from the window's previous size and the last control is drawn
     * outside the row.
     */
    private static void wrappingRowsAskAgain() {
        Theme.apply(ThemeId.MIDNIGHT);
        var card = Theme.card();
        var row = Theme.wrappingRow();
        for (int i = 0; i < 4; i++) row.add(Theme.button("Invented control " + i, () -> { }));
        card.add(row);
        card.setSize(900, 200);
        row.setBounds(0, 0, 900, row.getPreferredSize().height);
        int onOneLine = card.getLayout().preferredLayoutSize(card).height;
        row.setBounds(0, 0, 200, row.getHeight());
        int wrapped = card.getLayout().preferredLayoutSize(card).height;
        check(wrapped > onOneLine, "a row given less width asks for more height: " + wrapped + " against " + onOneLine);
    }

    /**
     * Today is a glance (#86): at 1280×900 the whole day is on screen without
     * scrolling, Anki's line included when it is switched on (#85).
     */
    private static void todayAtAGlance() throws Exception {
        for (boolean anki : new boolean[]{false, true}) {
            Theme.apply(ThemeId.MIDNIGHT);
            var app = Preview.trackerApp(ThemeId.MIDNIGHT, 1280, 900, tracker -> {
                if (anki) tracker.anki(tracker.state().anki().enabled(true).withSnapshot(new dev.yoru.domain.Model.AnkiSnapshot("Synthetic", 24,
                    new java.util.TreeMap<>(), java.time.Instant.now())));
            });
            Preview.button(app, "Today").doClick();
            Preview.layout(app);
            JViewport page = null;
            for (var viewport : viewports(app)) if (page == null || viewport.getWidth() > page.getWidth()) page = viewport;
            check(page != null, "Today scrolls in a viewport of its own");
            int wants = page.getView().getPreferredSize().height, has = page.getExtentSize().height;
            check(wants <= has, "Today fits 1280×900 without scrolling" + (anki ? " with Anki's line" : "")
                + ": it wants " + wants + " px of " + has);
            if (anki) {
                var line = named(app, "today.anki.status");
                check(line != null && line.isVisible() && line.getWidth() > 0, "and Anki's line is drawn");
                var at = SwingUtilities.convertPoint(line, 0, line.getHeight(), page);
                check(at.y <= page.getExtentSize().height, "inside the window rather than below it");
            }
        }
    }

    private static java.util.List<JViewport> viewports(Container root) {
        var out = new java.util.ArrayList<JViewport>();
        for (var child : root.getComponents()) {
            if (child instanceof JViewport v && v.getView() != null) out.add(v);
            if (child instanceof Container nested) out.addAll(viewports(nested));
        }
        return out;
    }

    private static void pages() throws Exception {
        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());
        tracker.addActivity("Study", 30);
        // Two blocks today, so the agenda has a list to end.
        var activity = tracker.state().activities().getFirst().id();
        var noon = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant();
        tracker.plan(activity, noon, noon.plusSeconds(3600));
        tracker.plan(activity, noon.plusSeconds(7200), noon.plusSeconds(10800));
        var app = new YoruApp(tracker, repo);
        app.setSize(900, 640);

        open(app, "Today");
        check(labels(app).stream().noneMatch(text -> text.startsWith("~/")), "no page carries a breadcrumb");
        var controls=named(app,"today.timer.controls");
        var goal=named(app,"today.goal");
        check(controls!=null && goal!=null && controls.getY()<goal.getY(),
            "The timer action comes before progress and scenery");
        var agenda = named(app, "today.agenda");
        var stats = named(app, "today.stats");
        check(agenda != null && stats != null && agenda.getY() < stats.getY(), "Today's agenda comes before its statistics");
        // The rules inside a list divide its rows; the last row has nothing
        // below it but the card's own edge, so it carries no rule.
        var rows = new ArrayList<JComponent>();
        // Rows carry the list's rule or its end; the card's head carries neither.
        for (var child : ((Container) agenda).getComponents())
            if (child instanceof JPanel row && row.getLayout() instanceof BorderLayout && row.getBorder() != null) rows.add(row);
        check(rows.size() == 2, "the agenda lists the blocks planned today, got " + rows.size());
        check(rows.getFirst().getBorder() instanceof javax.swing.border.CompoundBorder,
            "a block is ruled off from the one after it");
        check(rows.getLast().getBorder() instanceof javax.swing.border.EmptyBorder,
            "and the last block is not ruled off from the card's edge");
        check(rows.getFirst().getBorder().getBorderInsets(rows.getFirst()).bottom
            + rows.getFirst().getBorder().getBorderInsets(rows.getFirst()).top
            == rows.getLast().getBorder().getBorderInsets(rows.getLast()).bottom
            + rows.getLast().getBorder().getBorderInsets(rows.getLast()).top,
            "without standing any shorter for it");

        tracker.start(tracker.state().activities().getFirst().id());
        open(app,"Tasks");
        var status=button(app,"session.status");
        check(status.isVisible()&&status.getText().contains("Recording"),
            "A running timer stays visible away from Today");
        status.doClick();
        check(named(app,"today.timer")!=null,"The recording status returns to the timer");
        tracker.stop(tracker.now());
        app.updateRecordingStatus();
        check(!button(app,"session.status").isVisible(),"Clocking out clears the recording status");

        open(app, "Data");
        check(!labels(app).contains("Vault"), "the vault's controls have left the Data page");

        open(app, "Settings");
        var sections = labels(app).stream().filter(List.of("Appearance", "Tracking",
            "Tracking · study music", "Vault", "Vault · reset data", "Updates", "Integrations")::contains).toList();
        check(sections.equals(List.of("Appearance", "Tracking", "Tracking · study music",
            "Vault", "Vault · reset data", "Updates")),
            "Settings reads appearance, then tracking, then the vault, with updates last: " + sections);
        var themes = (JPanel) named(app, "settings.themes");
        check(((GridLayout) themes.getLayout()).getColumns() == 3, "theme choices sit three across");

        var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, themes);
        int furthest = viewport.getView().getPreferredSize().height - viewport.getHeight();
        check(furthest > 300, "Settings is long enough to scroll, by " + furthest);
        app.scrollTo(new Point(0, 300));
        check(viewport.getViewPosition().y == 300, "a rebuilt page can be put back where it was");
        app.scrollTo(new Point(0, 1_000_000));
        check(viewport.getViewPosition().y == furthest, "and no further than the page reaches, got " + viewport.getViewPosition().y);
        open(app, "Settings");
        var rebuilt = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, named(app, "settings.themes"));
        check(rebuilt.getViewPosition().y == furthest, "rebuilding the page on screen keeps its place, got " + rebuilt.getViewPosition().y);

        for (var page : Preview.PAGES) {
            open(app, page);
            rowsHoldTheirLines(app, page);
        }
    }

    private static void open(YoruApp app, String page) {
        button(app, page).doClick();
        layout(app);
        layout(app);
    }

    private static List<String> labels(Component root) {
        var found = new ArrayList<String>();
        if (!(root instanceof Container container)) return found;
        for (var child : container.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null) found.add(label.getText());
            found.addAll(labels(child));
        }
        return found;
    }

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static JButton button(Container root, String name) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton b && (name.equals(b.getName()) || name.equals(b.getText()))) return b;
            if (child instanceof Container nested) { var found = button(nested, name); if (found != null) return found; }
        }
        return null;
    }

    /** The preview's own settling pass, so a render and a check see one layout. */
    private static void layout(Container c) { Preview.layout(c); }

    /**
     * A row that wraps is as tall as the lines it wrapped onto.
     *
     * A column measures its children before it gives any of them a width, so a
     * row that wraps could report one line, be laid out in the narrower room it
     * actually had, and draw its last control below the height it was given —
     * where it is not merely clipped but unclickable. The rule holds on every
     * page at the window's minimum, which is where rows wrap.
     */
    private static void rowsHoldTheirLines(Container root, String page) {
        for (var child : root.getComponents()) {
            if (child instanceof Container row && row.getLayout() instanceof WrapFlowLayout)
                for (var control : row.getComponents())
                    check(control.getY() + control.getHeight() <= row.getHeight(),
                        page + ": " + describe(control) + " is drawn past the row that holds it, "
                        + (control.getY() + control.getHeight()) + " into " + row.getHeight());
            if (child instanceof Container nested) rowsHoldTheirLines(nested, page);
        }
    }

    private static String describe(Component control) {
        if (control instanceof AbstractButton b && b.getText() != null && !b.getText().isBlank()) return b.getText();
        return control.getName() != null ? control.getName() : control.getClass().getSimpleName();
    }
}
