package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import dev.yoru.application.AnkiTime;
import dev.yoru.domain.Model.Anki;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * Where Anki is switched on and set up (#85).
 *
 * All of this used to sit on Today: the add-on's code, a key field, a Connect
 * button and four paragraphs of fine print, on the page that is meant to be a
 * glance. An integration is a setting, so it lives in Settings, and Today keeps
 * one line of what it found.
 *
 * The key is stored in the vault, which is the encrypted thing. It is never
 * written to this computer's preferences, never logged, and never exported
 * except inside a vault export, which says on its face that it is plain text.
 */
final class AnkiSettings {
    private AnkiSettings() { }

    /** The Anki section of the Settings page. */
    static JPanel card(Shell shell, AnkiCard.Source source) {
        var anki = shell.tracker().state().anki();
        var card = Theme.card();
        var on = button(anki.enabled() ? "On" : "Off",
            () -> shell.perform(() -> shell.tracker().anki(shell.tracker().state().anki().enabled(!anki.enabled()))));
        on.setName("anki.enabled");
        on.setToolTipText(anki.enabled() ? "Stop reading from Anki" : "Read your review counts and study time from Anki");
        on.getAccessibleContext().setAccessibleName("Anki integration, currently " + (anki.enabled() ? "on" : "off"));
        selected(on, anki.enabled());
        card.add(cardHead(sectionHeader("INTEGRATIONS · ANKI"), on));
        gap(card, SPACE_SM);
        card.add(bodyLabel("Yoru reads your review counts, and adds the time you spend in Anki to your tracked time."));
        gap(card, SPACE_MD);

        card.add(label("SETUP", TYPE_CAPTION, MUTED));
        gap(card, SPACE_XS);
        card.add(bodyLabel("In Anki: Tools → Add-ons → Get Add-ons, enter 2055492159, then restart Anki."));
        card.add(bodyLabel("Keep Anki open on the profile you want to track; Yoru only reads."));
        gap(card, SPACE_MD);

        card.add(label("API KEY · ONLY IF ANKICONNECT ASKS FOR ONE", TYPE_CAPTION, MUTED));
        gap(card, SPACE_XS);
        var key = new JPasswordField(anki.key(), 24);
        styleInput(key);
        key.setName("anki.key");
        key.getAccessibleContext().setAccessibleName("AnkiConnect API key");
        key.setAlignmentX(0);
        key.setMaximumSize(new Dimension(Integer.MAX_VALUE, key.getPreferredSize().height));
        card.add(key);
        gap(card, SPACE_SM);

        var result = wrapping(status(anki), TYPE_CAPTION, MUTED);
        result.setName("anki.result");
        var actions = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        actions.setOpaque(false);
        actions.setAlignmentX(0);
        actions.add(button("Save key", () -> {
            char[] typed = key.getPassword();
            String value = new String(typed);
            java.util.Arrays.fill(typed, '\0');
            shell.perform(() -> shell.tracker().anki(shell.tracker().state().anki().withKey(value)));
        }));
        actions.add(ghost(button("Clear key", () -> {
            key.setText("");
            shell.perform(() -> shell.tracker().anki(shell.tracker().state().anki().withKey("")));
        })));
        var test = ghost(button("Test connection", () -> { }));
        test.setName("anki.test");
        test.addActionListener(e -> {
            char[] typed = key.getPassword();
            String value = new String(typed);
            java.util.Arrays.fill(typed, '\0');
            result.setText("Asking Anki…");
            test.setEnabled(false);
            new SwingWorker<AnkiConnect.Snapshot, Void>() {
                protected AnkiConnect.Snapshot doInBackground() throws Exception { return source.read(value, 1); }
                protected void done() {
                    test.setEnabled(true);
                    try {
                        var snapshot = get();
                        result.setText("Connected to the profile “" + snapshot.profile() + "” · "
                            + snapshot.today() + " reviews today.");
                        result.setForeground(ACCENT_TEXT);
                    } catch (Exception failure) {
                        var cause = failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
                        result.setText(explain(cause));
                        result.setForeground(GOLD_TEXT);
                    }
                }
            }.execute();
        });
        actions.add(test);
        card.add(actions);
        gap(card, SPACE_SM);
        card.add(result);
        gap(card, SPACE_MD);

        var adds = new JCheckBox("Add my Anki study time to my tracked time", anki.addsTime());
        adds.setOpaque(false);
        adds.setForeground(TEXT);
        adds.setFont(labelFont());
        adds.setName("anki.addsTime");
        adds.addActionListener(e -> shell.perform(() -> {
            var current = shell.tracker().state().anki();
            shell.tracker().anki(new Anki(current.enabled(), current.key(), adds.isSelected(),
                current.refreshMinutes(), current.last()));
        }));
        card.add(adds);
        gap(card, SPACE_SM);
        card.add(wrapping("A sitting is added under “" + AnkiTime.ACTIVITY + "” once nothing has been answered for "
            + AnkiTime.GAP.toMinutes() + " minutes. Sittings that overlap time you clocked in Yoru, or that are shorter "
            + "than your minimum session, are left out.", TYPE_CAPTION, MUTED));
        gap(card, SPACE_MD);

        card.add(label("HOW OFTEN TO REFRESH", TYPE_CAPTION, MUTED));
        gap(card, SPACE_XS);
        var every = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_XS, SPACE_XS));
        every.setOpaque(false);
        every.setAlignmentX(0);
        for (int minutes : new int[]{1, 5, 15}) {
            int chosen = minutes;
            var pick = button(minutes + " min", () -> shell.perform(() -> {
                var current = shell.tracker().state().anki();
                shell.tracker().anki(new Anki(current.enabled(), current.key(), current.addsTime(), chosen, current.last()));
            }));
            pick.setName("anki.refresh." + minutes);
            every.add(selected(pick, anki.refreshMinutes() == minutes));
        }
        card.add(every);
        gap(card, SPACE_MD);
        card.add(wrapping("Only counts and times are kept in your vault — never a card, a question or an answer. "
            + "The key is stored in the vault, which is encrypted, and nowhere else.", TYPE_CAPTION, MUTED));
        return card;
    }

    /**
     * What a failed test means, and what to do about it here (#85): each way it
     * can fail says something different, so the owner is not left to guess
     * whether the fault is Anki, the add-on or the key.
     */
    static String explain(Throwable cause) {
        if (!(cause instanceof AnkiConnect.Failure failure))
            return "Anki did not answer. Open it, check the add-on is installed, then test again.";
        return switch (failure.kind()) {
            case NOT_ANSWERING -> "Anki isn't answering. Open Anki, and check the AnkiConnect add-on is installed "
                + "(Tools → Add-ons) and Anki was restarted after installing it.";
            case KEY_REJECTED -> "AnkiConnect refused the key. Enter the key set in the add-on's config, "
                + "or clear it if the add-on does not ask for one.";
            case NO_PROFILE -> "Anki is open, but no profile is. Open the profile you want to track, then test again.";
            case TOO_SLOW -> "Anki took too long to answer. Let it finish what it is doing, then test again.";
            case NOT_ACCEPTED -> "AnkiConnect did not accept the request. Check the add-on's settings, "
                + "then test again.";
            case UNREADABLE -> "AnkiConnect answered with something Yoru could not read. Update the add-on, "
                + "then test again.";
            case PROFILE_CHANGED -> "The Anki profile changed while Yoru was reading it. Test again.";
        };
    }

    private static String status(Anki anki) {
        if (!anki.enabled()) return "Switched off. Yoru does not contact Anki at all.";
        if (anki.last() == null) return "Switched on. Waiting for Anki to answer.";
        return "Last read " + DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .withZone(java.time.ZoneId.systemDefault()).format(anki.last().fetchedAt())
            + " from the profile “" + anki.last().profile() + "”.";
    }
}
