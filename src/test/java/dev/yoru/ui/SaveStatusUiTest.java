package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.game.Gen3Fixture;
import java.awt.*;
import java.time.Clock;
import java.util.List;
import javax.swing.*;

/**
 * Collection and Game with no core and no game file (#5): an absent save, an
 * unreadable one and a readable pre-starter one each say what they are, and
 * export and setup stay reachable when the save cannot be read.
 */
public final class SaveStatusUiTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    private static YoruApp app;

    private interface Action { void run() throws Exception; }

    private static void onEdt(Action action) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { action.run(); } catch (Exception e) { throw new IllegalStateException(e); }
            });
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static void open(String page) { onEdt(() -> ((JButton) find(app, page)).doClick()); }

    private static String text(String name) {
        var c = find(app, name);
        return c instanceof JLabel l ? l.getText() : c instanceof AbstractButton b ? b.getText() : null;
    }

    /**
     * YoruApp starts a Swing ticker the JVM will not exit past, so a failed
     * check would hang the suite instead of failing it. Every outcome ends
     * the process.
     */
    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run() throws Exception {
        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());
        tracker.addActivity("Study", 0);
        onEdt(() -> { app = new YoruApp(tracker, repo); app.setSize(900, 640); });

        open("Collection");
        check(find(app, "collection.unavailable") != null, "an empty vault says it has no save");
        check(find(app, "collection.saveStatus") == null,
            "once: no status line above the card repeating it, got " + text("collection.saveStatus"));
        check(find(app, "collection.openGame") != null, "and offers the game");
        check(find(app, "collection.arrange") == null, "and shows no storage to arrange");

        byte[] notASave = new byte[1000];
        onEdt(() -> tracker.replaceGameSave(notASave));
        open("Collection");
        check(find(app, "collection.unavailable") != null, "an unreadable save gets the card that says so");
        check(find(app, "collection.saveStatus") == null,
            "without a status line repeating the card, got " + text("collection.saveStatus"));
        var export = (JButton) find(app, "collection.export");
        check(export != null && export.isEnabled(), "an unreadable save can be exported from Collection");
        check(find(app, "collection.setup") != null, "and Game setup is one click away");
        check(find(app, "collection.arrange") == null, "and nothing offers to edit it");
        check(tracker.state().game().holds(notASave), "showing it changed nothing");

        open("Game");
        var gameExport = (JButton) find(app, "game.export");
        check(gameExport != null && gameExport.isEnabled(), "with no core or game file, the Game page still exports the save");

        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 0), "TESTER", 0, 12345, 54321);
        byte[] party = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 258, 5, 1)));
        onEdt(() -> tracker.replaceGameSave(party));
        open("Collection");
        String status = text("collection.saveStatus");
        check(status != null && status.startsWith("Saved in your vault · "), "a readable save says when it was saved, got " + status);
        check(find(app, "collection.arrange") != null, "and its storage is shown even though the starter flag is unset");
        check(find(app, "collection.unavailable") == null, "with no unavailable card beside it");

        open("Game");
        check("TESTER".equals(text("game.saveHeadline")),
            "a save with a party is not called a new adventure, got " + text("game.saveHeadline"));

        onEdt(() -> ((JButton) find(app, "Lock & close")).doClick());
        System.out.println("PASS: " + checks + " save status UI checks (absent, unreadable, pre-starter party, no core)");
    }
}
