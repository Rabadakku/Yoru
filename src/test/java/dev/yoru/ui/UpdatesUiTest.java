package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.update.ReleaseFeed;
import dev.yoru.update.Updates;
import dev.yoru.update.Version;
import java.awt.*;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import javax.swing.*;

/**
 * The Updates section of Settings: what it offers for each answer the release
 * feed can give. The feed and the window are stand-ins, so nothing here reaches
 * GitHub or quits the test.
 */
public final class UpdatesUiTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    private static final class Host implements UpdatesCard.Host {
        boolean running;
        public boolean gameRunning() { return running; }
        public void quitThen(Runnable afterVaultClosed) { }
        public Component owner() { return null; }
    }

    private interface Action { void run() throws Exception; }

    private static void onEdt(Action action) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { action.run(); } catch (Exception e) { throw new IllegalStateException(e); }
            });
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Component find(Container root, String name) {
        if (name.equals(root.getName())) return root;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static String text(Container root, String name) {
        var c = find(root, name);
        return c instanceof JLabel l ? l.getText() : c instanceof AbstractButton b ? b.getText() : null;
    }

    private static ReleaseFeed.Release release(String version) {
        String tag = "v" + version;
        return new ReleaseFeed.Release(Version.parse(version), tag,
            URI.create("https://github.com/Rabadakku/Yoru/releases/tag/" + tag), "Notes",
            List.of(new ReleaseFeed.Asset("Yoru-" + version + ".dmg", 10, "0".repeat(64),
                URI.create("https://github.com/Rabadakku/Yoru/releases/download/" + tag + "/Yoru-" + version + ".dmg"))));
    }

    private static UpdatesCard card;
    private static YoruApp app;

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
        var host = new Host();
        onEdt(() -> {
            card = new UpdatesCard(Version.parse("1.0.4"), Updates.Platform.MAC, () -> release("1.0.5"), host);
            card.show(release("1.0.5"));
        });
        check("Yoru 1.0.5 is available. You have 1.0.4.".equals(text(card, "updates.status")),
            "a newer release says so, got " + text(card, "updates.status"));
        var install = (JButton) find(card, "updates.install");
        check(install != null && install.isEnabled() && "Download and install 1.0.5".equals(install.getText()),
            "and offers to install it, got " + (install == null ? null : install.getText()));

        host.running = true;
        onEdt(() -> card.show(release("1.0.5")));
        install = (JButton) find(card, "updates.install");
        check(install != null && !install.isEnabled(), "installing waits until the game is closed");
        host.running = false;

        onEdt(() -> card.show(release("1.0.4")));
        check("Yoru 1.0.4 is the latest version.".equals(text(card, "updates.status")),
            "the same version is up to date, got " + text(card, "updates.status"));
        check(find(card, "updates.install") == null, "with nothing to install");

        onEdt(() -> {
            card = new UpdatesCard(null, Updates.Platform.MAC, () -> release("1.0.5"), host);
            card.show(release("1.0.5"));
        });
        check("Yoru 1.0.5 is the latest release.".equals(text(card, "updates.status")),
            "a copy built from source still hears about the release, got " + text(card, "updates.status"));
        check(find(card, "updates.install") == null, "but is never overwritten by an installer");

        onEdt(() -> {
            card = new UpdatesCard(Version.parse("1.0.4"), Updates.Platform.UNSUPPORTED, () -> release("1.0.5"), host);
            card.show(release("1.0.5"));
        });
        check(find(card, "updates.install") == null, "a computer with no installer gets no install button");
        String detail = text(card, "updates.detail");
        check(detail != null && detail.contains("no installer"), "and is told why, got " + detail);

        onEdt(() -> card.failed("Yoru could not reach GitHub. Check your internet connection and try again."));
        check(text(card, "updates.status").startsWith("Yoru could not reach GitHub."), "a failed check says what went wrong");
        check("Check again".equals(text(card, "updates.check")), "and offers to check again");

        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());
        try { tracker.addActivity("Study", 0); } catch (Exception e) { throw new IllegalStateException(e); }
        System.clearProperty("jpackage.app-version");
        onEdt(() -> {
            app = new YoruApp(tracker, repo);
            app.setSize(1280, 900);
            ((JButton) find(app, "Settings")).doClick();
        });
        check(find(app, "settings.updates") != null, "Settings has an Updates section");
        check("Check for updates".equals(text(app, "updates.check")), "with Check for updates");
        check(text(app, "updates.version") != null && text(app, "updates.version").contains("built from source"),
            "and says this copy was built from source, got " + text(app, "updates.version"));
        onEdt(() -> ((JButton) find(app, "Lock & close")).doClick());

        System.out.println("PASS: " + checks + " updates UI checks (available, game running, up to date, source build, unsupported, failure, Settings)");
    }
}
