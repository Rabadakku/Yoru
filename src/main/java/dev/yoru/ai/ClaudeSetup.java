package dev.yoru.ai;

import dev.yoru.json.Json;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How an AI app is told where Yoru is (#47): the command that starts
 * {@code Yoru --mcp} on this computer, written the way Claude Desktop and
 * Claude Code each expect it.
 *
 * Adding Yoru to Claude Desktop's settings file is done only when the owner
 * presses the button for it. The file is theirs, so everything already in it is
 * kept, a copy of it as it was is left beside it, and a file Yoru cannot read
 * is left alone rather than rewritten.
 */
public final class ClaudeSetup {
    private ClaudeSetup() { }

    /** The name Yoru is listed under in an AI app's servers. */
    public static final String NAME = "yoru";
    /** The largest settings file read; Claude Desktop's is a few kilobytes. */
    private static final int MAX_CONFIG = 2_000_000;

    /**
     * The command that starts this copy of Yoru as a tool server.
     *
     * An installed Yoru is its own launcher, which the packager names in a
     * system property. Run from a build, it is Java, the classes and the main
     * class.
     */
    public static List<String> command() {
        String app = System.getProperty("jpackage.app-path");
        if (app != null && !app.isBlank()) return List.of(app, "--mcp");
        String java = ProcessHandle.current().info().command().orElse("java");
        try {
            var where = Path.of(McpMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return List.of(java, "-cp", where.toString(), "dev.yoru.ui.YoruApp", "--mcp");
        } catch (URISyntaxException | RuntimeException unknown) {
            return List.of(java, "-cp", "yoru.jar", "dev.yoru.ui.YoruApp", "--mcp");
        }
    }

    /** Where Claude Desktop keeps its settings on this kind of computer. */
    public static Path desktopConfig() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var home = Path.of(System.getProperty("user.home"));
        if (os.contains("mac")) return home.resolve(Path.of("Library", "Application Support", "Claude", "claude_desktop_config.json"));
        if (os.contains("win")) {
            String roaming = System.getenv("APPDATA");
            var base = roaming == null || roaming.isBlank() ? home.resolve(Path.of("AppData", "Roaming")) : Path.of(roaming);
            return base.resolve(Path.of("Claude", "claude_desktop_config.json"));
        }
        return home.resolve(Path.of(".config", "Claude", "claude_desktop_config.json"));
    }

    /** Yoru's entry in Claude Desktop's servers. */
    static Map<String, Object> entry(List<String> command) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("command", command.getFirst());
        entry.put("args", List.copyOf(command.subList(1, command.size())));
        return entry;
    }

    /** What to paste into Claude Desktop's settings file by hand. */
    public static String desktopSnippet(List<String> command) {
        return Json.pretty(Map.of("mcpServers", Map.of(NAME, entry(command))));
    }

    /** What to type in a terminal to add Yoru to Claude Code. */
    public static String codeCommand(List<String> command) {
        var words = new ArrayList<String>(List.of("claude", "mcp", "add", NAME, "--"));
        for (var part : command) words.add(quote(part));
        return String.join(" ", words);
    }

    /** A word a shell reads back unchanged: bare when it is plain, in single quotes when it is not. */
    static String quote(String word) {
        if (word.matches("[A-Za-z0-9_@%+=:,./-]+")) return word;
        return "'" + word.replace("'", "'\\''") + "'";
    }

    /**
     * Adds Yoru to Claude Desktop's settings file, keeping everything else in it.
     *
     * @return false when Yoru was already there exactly as it would be written.
     * @throws IOException when the file cannot be read as settings, which is
     *                     then left exactly as it was.
     */
    public static boolean addToDesktop(Path config, List<String> command) throws IOException {
        Map<String, Object> settings = new LinkedHashMap<>();
        String before = null;
        if (Files.exists(config)) {
            before = Files.readString(config, StandardCharsets.UTF_8);
            if (!before.isBlank()) {
                try {
                    Json.object(Json.read(before, MAX_CONFIG)).forEach((k, v) -> settings.put(k.toString(), v));
                } catch (IllegalArgumentException unreadable) {
                    throw new IOException("Claude Desktop's settings file is not one Yoru can read, so it was left alone. "
                        + "Add Yoru to it by hand instead.");
                }
            }
        }
        var servers = new LinkedHashMap<String, Object>();
        Object existing = settings.get("mcpServers");
        if (existing instanceof Map<?, ?> map) map.forEach((k, v) -> servers.put(k.toString(), v));
        else if (existing != null) throw new IOException("Claude Desktop's settings file lists its servers in a way Yoru "
            + "does not recognise, so it was left alone. Add Yoru to it by hand instead.");
        var entry = entry(command);
        if (entry.equals(servers.get(NAME))) return false;
        servers.put(NAME, entry);
        settings.put("mcpServers", servers);
        Files.createDirectories(config.getParent());
        if (before != null) Files.writeString(config.resolveSibling(config.getFileName() + ".before-yoru"), before, StandardCharsets.UTF_8);
        var written = config.resolveSibling(config.getFileName() + ".yoru-new");
        Files.writeString(written, Json.pretty(settings) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(written, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notHere) {
            Files.move(written, config, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
}
