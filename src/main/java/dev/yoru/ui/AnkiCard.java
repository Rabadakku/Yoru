package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import dev.yoru.application.AnkiTime;
import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.AnkiSnapshot;
import java.awt.BorderLayout;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * Anki on Today: one line saying how the day is going, and nothing else (#85).
 *
 * The card used to be a setup form, a title-sized number, seven lines of
 * history and four paragraphs of fine print, on the page that is supposed to be
 * a glance. The setting-up moved to Settings, the history to Data, and what is
 * left is the sentence: how many reviews, how much time, and when that was
 * last true.
 *
 * It keeps showing the last numbers while Anki is closed, because Anki is
 * closed most of the day (#51). What reaches the vault is the length of each
 * finished sitting, as a session, and counts and times for this line — never a
 * card, a question or an answer.
 */
final class AnkiCard extends JPanel {
    interface Source { AnkiConnect.Snapshot read(String key, int days) throws Exception; }

    /** How far back the first refresh after switching on looks for sittings to add. */
    static final int CATCH_UP_DAYS = 7;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm");

    private final Source source;
    private final Supplier<Tracker> tracker;
    private final Supplier<ZoneId> zone;
    private final Runnable recorded;
    private final Runnable openDetails;
    private final Timer timer;
    /** Why the last attempt failed, or null when Anki answered. */
    private String trouble;
    private boolean busy, caughtUp;
    private String connectionKey;
    private boolean connectionAddsTime;
    private long generation;
    private SwingWorker<AnkiConnect.Snapshot, Void> worker;

    /**
     * @param recorded    run after sittings were added, so the page can show the new totals
     * @param openDetails run when the line is clicked: the page that holds the history
     */
    AnkiCard(Source source, Supplier<Tracker> tracker, Supplier<ZoneId> zone, Runnable recorded, Runnable openDetails) {
        super(new BorderLayout());
        this.source = source; this.tracker = tracker; this.zone = zone;
        this.recorded = recorded; this.openDetails = openDetails;
        setOpaque(false);
        setAlignmentX(0);
        setName("today.anki");
        timer = new Timer(60_000, e -> refresh());
        // Not rendered here: this is built while the window still is, so the
        // vault it would read from is not there yet. The page renders it.
        setVisible(false);
    }

    private dev.yoru.domain.Model.Anki settings() { return tracker.get().state().anki(); }

    @Override public void addNotify() {
        super.addNotify();
        syncConnection();
    }

    /** The connection belongs to the open vault, not to whichever tab is visible. */
    void syncConnection() {
        var current = settings();
        if (!current.enabled()) {
            if (timer.isRunning() || busy || connectionKey != null) disconnect();
            return;
        }
        boolean changed = !java.util.Objects.equals(connectionKey, current.key())
            || connectionAddsTime != current.addsTime();
        if (changed) disconnect();
        connectionKey = current.key(); connectionAddsTime = current.addsTime();
        int interval = current.refreshMinutes() * 60_000;
        timer.setDelay(interval); timer.setInitialDelay(interval);
        if (!timer.isRunning()) { timer.start(); refresh(); }
    }

    /** Removing Today must not stop retries; close/vault switch calls disconnect. */
    @Override public void removeNotify() { super.removeNotify(); }

    /** Stops asking Anki: the integration was switched off, or the vault closed. */
    void disconnect() {
        generation++;
        if (worker != null) { worker.cancel(true); worker = null; }
        busy = false;
        caughtUp = false;
        timer.stop();
        trouble = null;
        connectionKey = null;
        render();
    }

    /** Whether a read of Anki is in flight; a second one waits rather than piling up. */
    boolean refreshing() { return busy; }

    void refresh() {
        var settings = settings();
        if (busy || !settings.enabled()) return;
        busy = true;
        long ticket = generation;
        String key = settings.key();
        var requestTracker = tracker.get();
        int days = caughtUp ? 1 : CATCH_UP_DAYS;
        worker = new SwingWorker<AnkiConnect.Snapshot, Void>() {
            protected AnkiConnect.Snapshot doInBackground() throws Exception { return source.read(key, days); }
            protected void done() {
                if (ticket != generation) return;
                busy = false;
                worker = null;
                var current = settings();
                if (tracker.get() != requestTracker || !current.enabled() || !current.key().equals(key)
                    || current.addsTime() != settings.addsTime()) { render(); return; }
                boolean changed = false;
                try {
                    var snapshot = get();
                    trouble = null;
                    changed = record(snapshot);
                    keep(snapshot);
                } catch (Exception e) {
                    var cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
                    trouble = cause instanceof AnkiConnect.Failure ? cause.getMessage() : "Anki is closed";
                }
                render();
                // After this refresh is finished with, not during it: the page
                // rebuild takes this card off screen and puts it back.
                if (changed) SwingUtilities.invokeLater(recorded);
            }
        };
        worker.execute();
    }

    /** Adds finished sittings to the tracked time, when the owner asked for that. */
    private boolean record(AnkiConnect.Snapshot snapshot) {
        if (!settings().addsTime()) { caughtUp = true; return false; }
        try {
            var sessions = tracker.get().addAnkiTime(snapshot.reviews(), snapshot.complete());
            caughtUp = true;
            return !sessions.isEmpty();
        } catch (Exception e) {
            trouble = "Anki time was not added: " + (e.getMessage() == null ? "the vault could not be written." : e.getMessage());
            return false;
        }
    }

    /** Keeps the counts in the vault, so the line still says something while Anki is closed. */
    private void keep(AnkiConnect.Snapshot snapshot) {
        try {
            tracker.get().ankiSeen(new AnkiSnapshot(snapshot.profile(), snapshot.today(), snapshot.days(), snapshot.fetchedAt()));
        } catch (Exception e) {
            trouble = "Latest Anki counts could not be saved; keeping previous data";
        }
    }

    /** The line: a dot for the state, the day's numbers, and when they were true. */
    void render() {
        removeAll();
        var settings = settings();
        setVisible(settings.enabled());
        if (!settings.enabled()) { revalidate(); repaint(); return; }

        var last = settings.last();
        var today = LocalDate.now(zone.get());
        boolean stale = last == null || !last.fetchedAt().atZone(zone.get()).toLocalDate().equals(today);
        long tracked = AnkiTime.recordedOn(tracker.get().state(), today, zone.get(), tracker.get().now());

        String text;
        if (last == null) text = trouble == null ? "Anki · connecting…" : "Anki · " + trouble;
        else text = "Anki · " + last.today() + (stale ? " reviews at last refresh" : " reviews")
            + (tracked > 0 ? " · " + Analytics.report(tracked) + " tracked" : "")
            + " · updated " + (stale ? DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(zone.get()).format(last.fetchedAt())
                : WHEN.withZone(zone.get()).format(last.fetchedAt()))
            + (trouble == null ? "" : " · " + trouble);

        var line = button(text, openDetails);
        line.setName("today.anki.status");
        line.setHorizontalAlignment(SwingConstants.LEFT);
        line.setFont(labelFont());
        line.setForeground(TEXT);
        line.setBackground(PANEL);
        line.setBorder(controlBorder(LINE));
        line.setIcon(dot(trouble, stale));
        line.setToolTipText(last == null ? text + ". Open the Data page for Anki's history"
            : text + ". Profile " + last.profile() + " · last read "
              + DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(zone.get()).format(last.fetchedAt())
              + ". Open the Data page for its history.");
        line.getAccessibleContext().setAccessibleName(text);
        add(line, BorderLayout.CENTER);
        setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        revalidate();
        repaint();
    }

    /** Live, closed, or a problem worth looking at. */
    private static Icon dot(String trouble, boolean stale) {
        var colour = trouble == null && !stale ? CYAN : trouble != null && !trouble.startsWith("Anki is closed") ? GOLD_TEXT : MUTED;
        return new Icon() {
            @Override public int getIconWidth() { return grow(SPACE_SM); }
            @Override public int getIconHeight() { return grow(SPACE_SM); }
            @Override public void paintIcon(java.awt.Component c, java.awt.Graphics graphics, int x, int y) {
                var g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(colour);
                g.fillOval(x, y, getIconWidth(), getIconHeight());
                g.dispose();
            }
        };
    }

    /** How long ago the counts were read, for the Data page's heading. */
    static String ago(Instant when, Instant now) {
        long minutes = Math.max(0, Duration.between(when, now).toMinutes());
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        return hours < 24 ? hours + (hours == 1 ? " hour ago" : " hours ago") : (hours / 24) + " days ago";
    }
}
