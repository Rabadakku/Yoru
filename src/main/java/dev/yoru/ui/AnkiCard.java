package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import dev.yoru.application.AnkiTime;
import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.Session;
import java.awt.BorderLayout;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * The Anki card: review counts, and Anki study time added to the tracked time.
 *
 * The connection and its key last as long as this window. What reaches the
 * vault is the time of each finished sitting, as a session (AnkiTime) — never
 * card content, answers or the key.
 */
final class AnkiCard extends JPanel {
    interface Source { AnkiConnect.Snapshot read(String key, int days) throws Exception; }
    /** How far back the first refresh after connecting looks for sittings to add. */
    static final int CATCH_UP_DAYS = 7;
    private final Source source;
    private final Supplier<Tracker> tracker;
    private final Supplier<ZoneId> zone;
    private final Runnable recorded;
    private final Timer timer;
    private AnkiConnect.Snapshot snapshot;
    private String key = "", message = "Connect to show your Anki reviews and add your Anki study time here.";
    /** What the last refresh added to the tracked time, or why it could not. */
    private String added = "";
    private boolean connected, busy;
    /** Whether sittings from the catch-up reach have been added since connecting. */
    private boolean caughtUp;
    private long generation;
    private SwingWorker<AnkiConnect.Snapshot, Void> worker;

    /**
     * @param recorded run after sittings were added, so the page can show the
     *                 new totals
     */
    AnkiCard(Source source, Supplier<Tracker> tracker, Supplier<ZoneId> zone, Runnable recorded) {
        super(new BorderLayout()); this.source = source; this.tracker = tracker; this.zone = zone; this.recorded = recorded;
        setOpaque(false); setAlignmentX(0); setName("today.anki");
        timer = new Timer(60_000, e -> refresh());
        render();
    }
    @Override public void addNotify() {
        super.addNotify(); if (connected) { timer.start(); refresh(); }
    }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    void disconnect() { disconnect("Disconnected. Anki data has been cleared from this view."); }
    void disconnect(String why) {
        generation++;
        if (worker != null) { worker.cancel(true); worker = null; }
        connected = false; busy = false; caughtUp = false; timer.stop();
        snapshot = null; key = ""; added = ""; message = why; render();
    }
    void refresh() {
        if (busy || !connected) return;
        busy = true; message = "Refreshing Anki…"; render();
        long ticket = generation;
        String requestKey = key;
        int days = caughtUp ? 1 : CATCH_UP_DAYS;
        worker = new SwingWorker<AnkiConnect.Snapshot, Void>() {
            protected AnkiConnect.Snapshot doInBackground() throws Exception { return source.read(requestKey, days); }
            protected void done() {
                if (ticket != generation) return;
                busy = false; worker = null;
                boolean changed = false;
                try {
                    snapshot = get();
                    message = "Updated " + DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault()).format(snapshot.fetchedAt())
                        + " · refreshes every minute while visible";
                    changed = record(snapshot);
                } catch (Exception e) {
                    Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
                    String reason = cause instanceof AnkiConnect.Failure ? cause.getMessage()
                        : "Open Anki with AnkiConnect enabled; check the API key, then retry.";
                    message = (snapshot == null ? "Could not connect. " : "Showing previous data. ")
                        + reason
                        + (snapshot == null ? "" : " Last updated " + DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
                            .withZone(ZoneId.systemDefault()).format(snapshot.fetchedAt()) + ".");
                }
                render();
                // After this refresh is finished with, not during it: the page
                // rebuild takes this card off screen and puts it back, which
                // starts a refresh of its own.
                if (changed) SwingUtilities.invokeLater(recorded);
            }
        };
        worker.execute();
    }
    /**
     * Adds the snapshot's finished sittings to the tracked time.
     *
     * A failure here is the vault's, not Anki's, so it is said on the card and
     * the counts stay: a dialog every minute would be worse than the line.
     * The catch-up reach is kept until a write has succeeded.
     */
    private boolean record(AnkiConnect.Snapshot snapshot) {
        try {
            List<Session> sessions = tracker.get().addAnkiTime(snapshot.reviews(), snapshot.complete());
            caughtUp = true;
            if (sessions.isEmpty()) return false;
            long seconds = sessions.stream().mapToLong(s -> s.seconds(s.end())).sum();
            added = "Last added " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(snapshot.fetchedAt())
                + ": " + plural(sessions.size(), "sitting") + " · " + Analytics.report(seconds) + " to your tracked time.";
            return true;
        } catch (Exception e) {
            added = "Anki time was not added: " + (e.getMessage() == null ? "the vault could not be written." : e.getMessage())
                + " It is tried again on the next refresh.";
            return false;
        }
    }
    private String activityName() {
        var state = tracker.get().state();
        var id = AnkiTime.activity(state);
        return id == null ? AnkiTime.ACTIVITY : state.activities().stream().filter(a -> a.id().equals(id))
            .map(a -> a.name()).findFirst().orElse(AnkiTime.ACTIVITY);
    }
    void render() {
        removeAll();
        var content = card(); content.add(sectionHeader("ANKI REVIEWS")); gap(content, SPACE_MD);
        content.add(wrapping(message, TYPE_CAPTION, MUTED)); gap(content, SPACE_MD);
        if (snapshot != null) {
            content.add(wrapping("Anki profile: " + snapshot.profile(), TYPE_CAPTION, MUTED));
            gap(content, SPACE_SM);
            LocalDate fetchedDate = snapshot.fetchedAt().atZone(ZoneId.systemDefault()).toLocalDate();
            content.add(wrapping(snapshot.today() + (fetchedDate.equals(LocalDate.now())
                ? " reviews today" : " reviews at last refresh"), TYPE_TITLE, TEXT));
            content.add(wrapping("Today follows Anki’s configured day boundary. Counts include repeat reviews of a card.", TYPE_CAPTION, MUTED));
            gap(content, SPACE_MD);
            studyTime(content);
            gap(content, SPACE_MD);
            LocalDate today = fetchedDate;
            content.add(wrapping(snapshot.lastSevenDays(today) + " reviews · last 7 calendar dates", TYPE_BODY, TEXT));
            for (int i = 6; i >= 0; i--) {
                LocalDate date = today.minusDays(i);
                content.add(label(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")) + "   ·   "
                    + snapshot.days().getOrDefault(date, 0L), TYPE_CAPTION, MUTED));
            }
            gap(content, SPACE_MD);
        }
        if (!connected) {
            content.add(wrapping("In Anki: Tools → Add-ons → Get Add-ons, enter 2055492159, then restart Anki. Keep Anki open on the profile you want to track.", TYPE_BODY, MUTED));
            gap(content, SPACE_MD);
            content.add(label("API key (only if configured in AnkiConnect)", TYPE_CAPTION, MUTED));
            var password = new JPasswordField(20);
            password.getAccessibleContext().setAccessibleName("AnkiConnect API key");
            password.setAlignmentX(0);
            password.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, password.getPreferredSize().height));
            content.add(password); gap(content, SPACE_MD);
            content.add(button("Connect Anki", () -> {
                char[] chars = password.getPassword(); key = new String(chars); java.util.Arrays.fill(chars, '\0'); password.setText("");
                connected = true; caughtUp = false; generation++; if (isDisplayable()) timer.start(); refresh();
            }));
        } else {
            var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, SPACE_SM, 0)); actions.setOpaque(false);
            var refresh = button("Refresh", this::refresh); refresh.setEnabled(!busy); actions.add(refresh);
            actions.add(button("Disconnect", this::disconnect));
            actions.setAlignmentX(0); actions.setMaximumSize(actions.getPreferredSize()); content.add(actions);
        }
        gap(content, SPACE_MD);
        content.add(wrapping("Read-only in Anki · current profile. Only the length of each Anki sitting is saved to "
            + "your vault — no cards, answers or API key — and it counts like any other session, encounters included.",
            TYPE_CAPTION, MUTED));
        add(content, BorderLayout.CENTER); revalidate(); repaint();
    }
    /** Anki's study time today beside how much of it is tracked time. */
    private void studyTime(JPanel content) {
        var zone = this.zone.get();
        var today = LocalDate.now(zone);
        var tracked = tracker.get();
        long studied = AnkiTime.studiedOn(snapshot.reviews(), today, zone);
        long inTracked = AnkiTime.recordedOn(tracked.state(), today, zone, tracked.now());
        content.add(wrapping(Analytics.report(studied) + " studied in Anki today · "
            + Analytics.report(inTracked) + " in your tracked time", TYPE_BODY, TEXT));
        if (!added.isEmpty()) content.add(wrapping(added, TYPE_CAPTION, MUTED));
        int floor = tracked.state().settings().minSessionSeconds() / 60;
        content.add(wrapping("A sitting is added under “" + activityName() + "” once nothing has been answered for "
            + AnkiTime.GAP.toMinutes() + " minutes. Sittings that overlap time you clocked in Yoru"
            + (floor > 0 ? ", or are shorter than your " + floor + "-minute minimum," : "")
            + " are left out.", TYPE_CAPTION, MUTED));
    }
}
