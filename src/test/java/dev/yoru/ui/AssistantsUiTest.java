package dev.yoru.ui;

import dev.yoru.ai.Bridge;
import dev.yoru.domain.Model.ThemeId;
import dev.yoru.json.Json;
import java.awt.*;
import java.nio.file.Files;
import java.util.Map;
import javax.swing.*;

/**
 * AI assistants from the owner's side (#47): the Settings card, both switches
 * off until turned on, answering only while switched on, a change drawn in the
 * window the moment it is made and listed for the owner, and the socket gone
 * when the vault closes. Every theme draws the card.
 */
public final class AssistantsUiTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> Theme.install());
        check(!Assistants.reads() && !Assistants.changes(), "Assistants are off until the owner turns them on");

        var app = onEdt(() -> Preview.trackerApp(ThemeId.MIDNIGHT, 1280, 900));
        var folder = Bridge.folder();
        onEdt(() -> { open(app, "Settings"); return null; });
        var toggle = (JButton) named(app, "assistants.enabled");
        var changes = (JCheckBox) named(app, "assistants.changes");
        check(toggle != null && toggle.getText().equals("Off"), "The card offers the switch, off");
        check(changes != null && !changes.isEnabled() && !changes.isSelected(), "Changes cannot be allowed before reading is");
        check(text(named(app, "assistants.status")).startsWith("Off"), "The status says it is off");
        check(named(app, "assistants.desktop") instanceof JButton, "Claude Desktop can be set up from here");
        check(named(app, "assistants.code") instanceof JTextField field && field.getText().startsWith("claude mcp add yoru -- ")
            && !field.isEditable(), "and Claude Code's command is shown to copy");
        check(!Files.exists(folder.resolve("yoru.sock")), "Nothing listens while it is off");

        onEdt(() -> { toggle.doClick(); return null; });
        check(Assistants.reads() && app.assistants().answering(), "Switching it on starts answering");
        check(Files.exists(folder.resolve("yoru.sock")), "at the socket in Yoru's own folder");
        var on = (JButton) named(app, "assistants.enabled");
        check(on.getText().equals("On") && named(app, "assistants.changes").isEnabled(), "The card redraws with the switch on");

        // A call from another process, answered on the window's thread.
        var overview = Bridge.call(folder, "get_overview", Map.of());
        check(Boolean.FALSE.equals(overview.get("isError")) && text(overview).contains("\"today\""), "An assistant reads the vault: " + text(overview));
        onEdt(() -> null);
        check(text(named(app, "assistants.status")).contains("1 request"), "The card counts it: " + text(named(app, "assistants.status")));

        var refused = Bridge.call(folder, "add_task", Map.of("title", "Invented from an assistant"));
        check(Boolean.TRUE.equals(refused.get("isError")) && text(refused).contains("Settings"), "Changing is refused until allowed");
        check(app.tracker().state().tasks().stream().noneMatch(t -> t.title().equals("Invented from an assistant")), "and nothing changed");

        onEdt(() -> { ((JCheckBox) named(app, "assistants.changes")).doClick(); return null; });
        check(Assistants.changes(), "Changes can then be allowed");
        onEdt(() -> { open(app, "Tasks"); return null; });
        var added = Bridge.call(folder, "add_task", Map.of("title", "Invented from an assistant"));
        check(Boolean.FALSE.equals(added.get("isError")), "An assistant adds a task: " + text(added));
        onEdt(() -> null);
        check(app.tracker().state().tasks().stream().anyMatch(t -> t.title().equals("Invented from an assistant")), "It is in the vault");
        var made = app.tracker().state().tasks().stream().filter(t -> t.title().equals("Invented from an assistant")).findFirst().orElseThrow();
        check(onEdt(() -> named(app, "task.title." + made.id()) != null), "and on the Tasks page at once, without a click");
        check(app.assistants().made().getFirst().what().contains("Invented from an assistant"), "The change is kept for the owner to read");
        onEdt(() -> { open(app, "Settings"); return null; });
        check(onEdt(() -> containsText(app, "CHANGES ASSISTANTS MADE")), "and listed on the card");

        // Every theme draws the card, on and off.
        for (var theme : ThemeId.values()) {
            var themed = onEdt(() -> Preview.trackerApp(theme, 1280, 900));
            onEdt(() -> { open(themed, "Settings"); return null; });
            check(named(themed, "assistants.card") != null, "The card is drawn in " + theme);
            check(text(named(themed, "assistants.status")).contains("Another Yoru window"),
                "A second window says another is answering: " + text(named(themed, "assistants.status")));
            onEdt(() -> { themed.closeVault(); return null; });
        }
        check(Files.exists(folder.resolve("yoru.sock")), "A second window refused the socket rather than taking it");

        onEdt(() -> { ((JButton) named(app, "assistants.enabled")).doClick(); return null; });
        check(!Assistants.reads() && !app.assistants().answering(), "Switching it off stops answering");
        check(!Files.exists(folder.resolve("yoru.sock")) && !Files.exists(folder.resolve("token")), "and removes the socket and token");
        try {
            Bridge.call(folder, "get_overview", Map.of());
            check(false, "Nobody answers while it is off");
        } catch (Bridge.Unavailable off) {
            check(true, "An assistant is told Yoru is not answering");
        }

        onEdt(() -> { ((JButton) named(app, "assistants.enabled")).doClick(); return null; });
        check(app.assistants().answering(), "On again");
        onEdt(() -> { app.closeVault(); return null; });
        check(!app.assistants().answering() && !Files.exists(folder.resolve("yoru.sock")), "Closing the vault stops answering");
        Assistants.reads(false);
        Assistants.changes(false);
        System.out.println("PASS: " + checks + " assistant settings checks (switches off by default, answering, changes drawn at once, every theme)");
        // The windows' timers would keep the event thread, and so this test, alive.
        System.exit(0);
    }

    interface Work<T> { T run() throws Exception; }

    static <T> T onEdt(Work<T> work) throws Exception {
        var result = new Object[1];
        var failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try { result[0] = work.run(); } catch (Exception e) { failure[0] = e; }
        });
        if (failure[0] != null) throw failure[0];
        @SuppressWarnings("unchecked") T t = (T) result[0];
        return t;
    }

    private static void open(YoruApp app, String page) {
        Preview.button(app, page).doClick();
        Preview.layout(app);
    }

    private static Component named(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static boolean containsText(Container root, String wanted) {
        for (var child : root.getComponents()) {
            if (child instanceof JLabel l && l.getText() != null && l.getText().contains(wanted)) return true;
            if (child instanceof AbstractButton b && b.getText() != null && b.getText().contains(wanted)) return true;
            if (child instanceof JTable table)
                for (int r = 0; r < table.getRowCount(); r++)
                    for (int c = 0; c < table.getColumnCount(); c++)
                        if (String.valueOf(table.getValueAt(r, c)).contains(wanted)) return true;
            if (child instanceof Container nested && containsText(nested, wanted)) return true;
        }
        return false;
    }

    private static String text(Component c) {
        return c instanceof JLabel l ? l.getText() : c instanceof JTextField f ? f.getText() : String.valueOf(c);
    }

    private static String text(Map<String, Object> result) {
        return (String) Json.object(Json.array(result.get("content")).getFirst()).get("text");
    }

}
