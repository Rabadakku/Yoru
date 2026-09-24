package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.awt.Component;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import javax.swing.JPanel;

/**
 * A window for a page part built on its own: what {@link Shell} asks of the
 * real one, recorded instead of shown.
 *
 * {@link #perform} runs the work and keeps any failure in {@link #errors}
 * rather than raising a dialog, which would hang a headless test; every
 * rebuild it would have caused is counted in {@link #refreshes}.
 */
final class TestShell implements Shell {
    final Tracker tracker;
    final ZoneId zone;
    final List<Exception> errors = new ArrayList<>();
    int refreshes;
    final List<String> shown = new ArrayList<>();

    TestShell(Tracker tracker, ZoneId zone) { this.tracker = tracker; this.zone = zone; }

    @Override public Tracker tracker() { return tracker; }
    @Override public Component owner() { return null; }
    @Override public boolean reducedMotion() { return true; }
    @Override public void show(String page) { shown.add(page); refreshes++; }
    @Override public void refresh() { refreshes++; }
    @Override public void perform(Work work) {
        try { work.run(); } catch (Exception e) { errors.add(e); }
        refreshes++;
    }
    @Override public void error(Exception e) { errors.add(e); }
    @Override public ZoneId zone() { return zone; }
    @Override public void timeDialog(boolean plan) { }
    @Override public void editTime(Session session) { }
    @Override public void addActivity() { }
    @Override public String activityName(UUID id) {
        return tracker.state().activities().stream().filter(a -> a.id().equals(id)).map(Activity::name)
            .findFirst().orElse("Activity");
    }
    @Override public void applySettings(UnaryOperator<Settings> change) {
        perform(() -> tracker.settings(change.apply(tracker.state().settings())));
    }
    @Override public void reducedMotion(boolean on) { }
    @Override public void textSize(int percent) { }
    @Override public JPanel vaultCard() { return new JPanel(); }
    @Override public void chooseReset() { }
    @Override public void quitForUpdate(Runnable afterVaultClosed) { }
}
