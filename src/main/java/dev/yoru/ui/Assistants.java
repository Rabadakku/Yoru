package dev.yoru.ui;

import dev.yoru.ai.Bridge;
import dev.yoru.ai.ClaudeSetup;
import dev.yoru.ai.Mcp;
import dev.yoru.ai.WorkspaceTools;
import dev.yoru.application.Tracker;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * AI assistants in Yoru (#47): whether one may read and change the open vault,
 * the {@link Bridge} it reaches the vault through, and the Settings card that
 * sets it up.
 *
 * Yoru holds no API key and calls no AI service. Claude Desktop, Claude Code or
 * another app that speaks the Model Context Protocol starts {@code Yoru --mcp},
 * which asks this window; the owner's own account with that app pays for and
 * runs the model. Both switches are off until the owner turns them on, and are
 * kept on this computer, as the text size is: they say what may reach this
 * computer's Yoru, not anything about the vault.
 *
 * Every tool runs on the window's own thread, after the page editor has saved
 * what it holds, and the page on screen is redrawn after a change, so an
 * assistant's edit shows up the way the owner's own would. The first change in
 * a while backs the vault up first.
 */
final class Assistants implements AutoCloseable {
    private static final String READS = "assistants.reads", CHANGES = "assistants.changes";

    private static Preferences preferences() { return Preferences.userRoot().node("dev/yoru/desktop"); }

    /** Whether assistants may read the open vault. Off until the owner turns it on. */
    static boolean reads() { return preferences().getBoolean(READS, false); }

    static void reads(boolean on) { preferences().putBoolean(READS, on); }

    /** Whether they may also change it. Off until the owner turns it on, and never on without reading. */
    static boolean changes() { return reads() && preferences().getBoolean(CHANGES, false); }

    static void changes(boolean on) { preferences().putBoolean(CHANGES, on); }

    /** The window the vault is shown in, which saves its own unsaved text before a change and redraws after. */
    interface Window {
        boolean flush();

        void refresh();
    }

    /** One change an assistant made, for the owner to read back. */
    record Entry(Instant at, String what) { }

    /** How long after an assistant's change the next is covered by the same backup. */
    static final Duration BACKUP_EVERY = Duration.ofMinutes(15);
    private static final int KEPT = 20;

    private final Tracker tracker;
    private final ZoneId zone;
    private final Path folder;
    private Window window;
    private Bridge bridge;
    private String problem;
    private final Deque<Entry> made = new ArrayDeque<>();
    private int requests;
    private Instant lastRequest, lastBackup;
    private Runnable watcher = () -> { };

    Assistants(Tracker tracker, ZoneId zone, Path folder) {
        this.tracker = tracker;
        this.zone = zone;
        this.folder = folder;
    }

    /** The window now on screen: a new palette builds a new one around the same vault. */
    void attach(Window window) { this.window = window; }

    /** Called on the window's thread after each request, so a card showing the count can redraw it. */
    void watch(Runnable watcher) { this.watcher = watcher == null ? () -> { } : watcher; }

    /** Starts or stops answering to match the switch. Safe to call again. */
    void sync() {
        if (reads() && bridge == null) {
            try {
                bridge = Bridge.open(folder, this::answer);
                problem = null;
            } catch (IOException | RuntimeException failed) {
                problem = failed.getMessage() == null ? "Yoru could not start listening for assistants." : failed.getMessage();
            }
        } else if (!reads()) {
            if (bridge != null) bridge.close();
            bridge = null;
            problem = null;
        }
    }

    boolean answering() { return bridge != null; }

    String problem() { return problem; }

    int requests() { return requests; }

    Instant lastRequest() { return lastRequest; }

    /** What assistants changed while this window was open, newest first. */
    List<Entry> made() { return List.copyOf(made); }

    @Override public void close() {
        if (bridge != null) bridge.close();
        bridge = null;
    }

    /** On the bridge's thread: the tool runs where the window lives, and this waits for it. */
    private Map<String, Object> answer(String tool, Map<String, Object> arguments) {
        var result = new AtomicReference<Map<String, Object>>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(run(tool, arguments)));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Mcp.text("Yoru stopped before it could answer.", true);
        } catch (InvocationTargetException failed) {
            return Mcp.text("Yoru hit a problem answering that.", true);
        }
        return result.get();
    }

    /** One tool call, on the window's thread. */
    Map<String, Object> run(String tool, Map<String, ?> arguments) {
        // Switched off while the request was on its way.
        if (!reads()) return Mcp.text("The user has switched off assistants in Yoru.", true);
        requests++;
        lastRequest = tracker.now();
        try {
            return new WorkspaceTools(tracker, zone, this::apply).call(tool, arguments, changes());
        } finally {
            watcher.run();
        }
    }

    private void apply(String summary, WorkspaceTools.Change change) throws IOException {
        if (window != null && !window.flush())
            throw new IOException("The page open in Yoru's editor has text that could not be saved first.");
        var now = tracker.now();
        if (lastBackup == null || Duration.between(lastBackup, now).compareTo(BACKUP_EVERY) >= 0) {
            tracker.backup();
            lastBackup = now;
        }
        change.run();
        made.addFirst(new Entry(now, summary));
        while (made.size() > KEPT) made.removeLast();
        if (window != null) window.refresh();
    }

    // ------------------------------------------------------------------ the Settings card

    /** The AI assistants section of the Settings page. */
    static JPanel card(Shell shell, Assistants assistants) {
        boolean on = reads();
        var card = Theme.card();
        card.setName("assistants.card");
        var toggle = button(on ? "On" : "Off", () -> {
            reads(!on);
            if (assistants != null) assistants.sync();
            shell.refresh();
        });
        toggle.setName("assistants.enabled");
        toggle.setToolTipText(on ? "Stop assistants reaching this vault" : "Let assistants read this vault while Yoru is open");
        toggle.getAccessibleContext().setAccessibleName("AI assistants, currently " + (on ? "on" : "off"));
        selected(toggle, on);
        card.add(cardHead(sectionHeader("INTEGRATIONS · AI ASSISTANTS"), toggle));
        gap(card, SPACE_SM);
        card.add(bodyLabel("Use Claude, or another assistant that supports the Model Context Protocol, with this "
            + "workspace: ask what is due, plan your week, or turn notes into tasks and pages."));
        card.add(bodyLabel("Yoru holds no key and sends nothing anywhere itself. The assistant's app asks, and Yoru "
            + "answers on this computer, only while it is open and unlocked."));
        gap(card, SPACE_MD);

        var changing = new JCheckBox("Let assistants make changes", on && changes());
        changing.setName("assistants.changes");
        changing.setOpaque(false);
        changing.setForeground(TEXT);
        changing.setFont(labelFont());
        changing.setAlignmentX(0);
        changing.setEnabled(on);
        changing.addActionListener(e -> {
            changes(changing.isSelected());
            shell.refresh();
        });
        card.add(changing);
        gap(card, SPACE_XS);
        card.add(wrapping("Add and edit tasks, pages, plans, time and habit check-ins. Nothing can be deleted, and "
            + "the vault is backed up before an assistant's first change.", TYPE_CAPTION, MUTED));
        gap(card, SPACE_MD);

        card.add(label("STATUS", TYPE_CAPTION, MUTED));
        gap(card, SPACE_XS);
        var status = wrapping(status(assistants, on), TYPE_BODY, assistants != null && assistants.problem() != null ? GOLD_TEXT : TEXT);
        status.setName("assistants.status");
        card.add(status);
        if (assistants != null) assistants.watch(() -> status.setText(status(assistants, reads())));
        gap(card, SPACE_LG);

        var command = ClaudeSetup.command();
        card.add(label("SET UP · CLAUDE DESKTOP", TYPE_CAPTION, MUTED));
        gap(card, SPACE_XS);
        var desktop = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        desktop.setOpaque(false);
        desktop.setAlignmentX(0);
        desktop.add(named(button("Add Yoru to Claude Desktop", () -> addToDesktop(shell, command)), "assistants.desktop"));
        desktop.add(named(ghost(button("Copy settings", () -> copy(shell, ClaudeSetup.desktopSnippet(command),
            "Copied. Paste it into Claude Desktop's settings file: Settings → Developer → Edit Config."))), "assistants.desktop.copy"));
        card.add(desktop);
        gap(card, SPACE_XS);
        card.add(wrapping("Then quit and reopen Claude Desktop, and Yoru's tools appear in its chat.", TYPE_CAPTION, MUTED));
        gap(card, SPACE_MD);

        card.add(label("SET UP · CLAUDE CODE", TYPE_CAPTION, MUTED));
        gap(card, SPACE_XS);
        String line = ClaudeSetup.codeCommand(command);
        var field = styleInput(new JTextField(line));
        field.setEditable(false);
        field.setFont(mono(TYPE_CAPTION));
        field.setName("assistants.code");
        field.getAccessibleContext().setAccessibleName("Command that adds Yoru to Claude Code");
        field.setAlignmentX(0);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        card.add(field);
        gap(card, SPACE_XS);
        var code = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        code.setOpaque(false);
        code.setAlignmentX(0);
        code.add(named(ghost(button("Copy command", () -> copy(shell, line, "Copied. Run it in a terminal."))), "assistants.code.copy"));
        card.add(code);

        if (assistants != null && !assistants.made().isEmpty()) {
            gap(card, SPACE_LG);
            card.add(label("CHANGES ASSISTANTS MADE WHILE YORU WAS OPEN", TYPE_CAPTION, MUTED));
            gap(card, SPACE_XS);
            var time = DateTimeFormatter.ofPattern("HH:mm");
            for (var entry : assistants.made().subList(0, Math.min(5, assistants.made().size())))
                card.add(wrapping(time.format(entry.at().atZone(shell.zone())) + "  " + entry.what(), TYPE_CAPTION, TEXT));
        }
        return card;
    }

    static String status(Assistants assistants, boolean on) {
        if (!on) return "Off. No assistant can reach this vault.";
        if (assistants == null) return "On. Assistants reach Yoru while its window is open.";
        if (assistants.problem() != null) return assistants.problem();
        if (!assistants.answering()) return "Starting…";
        var mode = changes() ? "Assistants can read and change this vault" : "Assistants can read this vault";
        if (assistants.requests() == 0) return mode + " while Yoru is open. None has asked anything yet.";
        String last = DateTimeFormatter.ofPattern("HH:mm").format(assistants.lastRequest().atZone(assistants.zone));
        return mode + " while Yoru is open · " + plural(assistants.requests(), "request") + ", the last at " + last + ".";
    }

    private static void addToDesktop(Shell shell, List<String> command) {
        var config = ClaudeSetup.desktopConfig();
        var ask = stack();
        ask.add(label("Add Yoru to Claude Desktop?", TYPE_HEADING, TEXT));
        gap(ask, SPACE_MD);
        ask.add(label("Yoru adds itself to Claude Desktop's settings and keeps everything else there.", TYPE_BODY, MUTED));
        ask.add(label("A copy of the settings as they were is left beside them.", TYPE_BODY, MUTED));
        if (!Dialogs.confirm(shell.owner(), ask, "Add to Claude Desktop", "Add")) return;
        try {
            boolean added = ClaudeSetup.addToDesktop(config, command);
            Dialogs.info(shell.owner(), added ? "Yoru is added. Quit and reopen Claude Desktop to use it."
                : "Claude Desktop already has Yoru. Quit and reopen it if Yoru's tools are missing.");
        } catch (IOException failed) {
            Dialogs.error(shell.owner(), failed.getMessage());
        }
    }

    private static void copy(Shell shell, String text, String done) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            Dialogs.info(shell.owner(), done);
        } catch (HeadlessException | IllegalStateException unavailable) {
            Dialogs.error(shell.owner(), "The clipboard is busy. Try again in a moment.");
        }
    }
}
