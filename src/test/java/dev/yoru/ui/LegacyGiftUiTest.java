package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.game.Gen3Fixture;
import java.awt.*;
import java.time.Clock;
import java.time.Instant;
import javax.swing.*;

/**
 * The Game page offers to repair study gifts an older Yoru wrote in the wrong
 * order (#11), and only when the save holds some. Showing the offer writes nothing.
 */
public final class LegacyGiftUiTest {
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

    /** YoruApp's ticker keeps the JVM alive, so every outcome ends the process explicitly. */
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
        byte[] plain = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 0), "TESTER", 0, 12345, 54321);
        onEdt(() -> {
            app = new YoruApp(tracker, repo);
            app.setSize(1280, 900);
            tracker.replaceGameSave(plain);
        });
        open("Game");
        check(find(app, "game.legacyGifts") == null, "a save with no legacy gifts shows no repair card");

        var id = Gen3Fixture.highBitRewardId(0);
        onEdt(() -> {
            tracker.bankReward(id, 258, 5);
            tracker.rewardDelivered(id, Instant.parse("2026-09-12T09:00:00Z"));
        });
        var reward = tracker.state().rewards().getFirst();
        byte[] legacy = Gen3Fixture.withLegacyGift(plain, reward, 0, 0);
        onEdt(() -> tracker.replaceGameSave(legacy));
        open("Game");
        check(find(app, "game.legacyGifts") != null, "a save holding a legacy gift shows the repair card");
        var repair = (JButton) find(app, "game.repairGifts");
        check(repair != null && repair.isEnabled() && "Repair 1 study gift".equals(repair.getText()),
            "with an enabled repair for exactly that gift, got " + (repair == null ? null : repair.getText()));
        check(tracker.state().game().holds(legacy), "showing the card changed nothing");

        onEdt(() -> ((JButton) find(app, "Lock & close")).doClick());
        System.out.println("PASS: " + checks + " legacy gift UI checks (no card without gifts, repair offered, nothing written)");
    }
}
