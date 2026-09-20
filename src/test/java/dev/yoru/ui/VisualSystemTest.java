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
        System.out.println("PASS: " + checks + " visual system checks (titles, cards, breadcrumb, Today order, Settings sections, scroll)");
        // The windows built here leave Swing's threads running.
        System.exit(0);
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
            check(image.getRGB(60, 0) != Theme.PANEL.getRGB(), theme + ": with a hairline along its edge");
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

    private static void pages() throws Exception {
        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());
        tracker.addActivity("Study", 30);
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
        check(!labels(app).contains("VAULT"), "the vault's controls have left the Data page");

        open(app, "Settings");
        var sections = labels(app).stream().filter(List.of("APPEARANCE", "TRACKING",
            "TRACKING · STUDY MUSIC", "GAME & ARTWORK", "VAULT", "VAULT · RESET DATA", "UPDATES", "INTEGRATIONS")::contains).toList();
        check(sections.equals(List.of("APPEARANCE", "TRACKING", "TRACKING · STUDY MUSIC",
            "GAME & ARTWORK", "VAULT", "VAULT · RESET DATA", "UPDATES")),
            "Settings reads appearance, tracking, game and artwork, then the vault, with updates last: " + sections);
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

    private static void layout(Container c) {
        c.doLayout();
        for (var child : c.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
