package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import java.awt.BorderLayout;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/** Session-only Anki view: no card content, credentials or review data enter a vault. */
final class AnkiCard extends JPanel {
    interface Source { AnkiConnect.Snapshot read(String key) throws Exception; }
    private final Source source;
    private final Timer timer;
    private AnkiConnect.Snapshot snapshot;
    private String key = "", message = "Connect to show your Anki reviews here.";
    private boolean connected, busy;
    private long generation;
    private SwingWorker<AnkiConnect.Snapshot, Void> worker;

    AnkiCard() { this(new AnkiConnect()::read); }
    AnkiCard(Source source) {
        super(new BorderLayout()); this.source = source;
        setOpaque(false); setAlignmentX(0); setName("today.anki");
        timer = new Timer(60_000, e -> refresh());
        render();
    }
    @Override public void addNotify() {
        super.addNotify(); if (connected) { timer.start(); refresh(); }
    }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    void disconnect() {
        generation++;
        if (worker != null) { worker.cancel(true); worker = null; }
        connected = false; busy = false; timer.stop();
        snapshot = null; key = ""; message = "Disconnected. Anki data has been cleared from this view."; render();
    }
    void refresh() {
        if (busy || !connected) return;
        busy = true; message = "Refreshing Anki…"; render();
        long ticket = generation;
        String requestKey = key;
        worker = new SwingWorker<AnkiConnect.Snapshot, Void>() {
            protected AnkiConnect.Snapshot doInBackground() throws Exception { return source.read(requestKey); }
            protected void done() {
                if (ticket != generation) return;
                busy = false; worker = null;
                try {
                    snapshot = get();
                    message = "Updated " + DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault()).format(snapshot.fetchedAt())
                        + " · refreshes every minute while visible";
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
            }
        };
        worker.execute();
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
                connected = true; generation++; if (isDisplayable()) timer.start(); refresh();
            }));
        } else {
            var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, SPACE_SM, 0)); actions.setOpaque(false);
            var refresh = button("Refresh", this::refresh); refresh.setEnabled(!busy); actions.add(refresh);
            actions.add(button("Disconnect", this::disconnect));
            actions.setAlignmentX(0); actions.setMaximumSize(actions.getPreferredSize()); content.add(actions);
        }
        gap(content, SPACE_MD);
        content.add(wrapping("Read-only · current Anki profile · data stays in this window. Review counts do not add study time or game rewards.", TYPE_CAPTION, MUTED));
        add(content, BorderLayout.CENTER); revalidate(); repaint();
    }
}
