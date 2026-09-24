package dev.yoru.ai;

import dev.yoru.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Telling an AI app where Yoru is (#47): the command that starts it, the
 * settings Claude Desktop and Claude Code each take, and adding Yoru to Claude
 * Desktop's file without disturbing anything else the owner keeps there.
 */
public final class ClaudeSetupTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        var command = ClaudeSetup.command();
        check(command.getLast().equals("--mcp"), "The command starts the tool server: " + command);
        check(command.contains("dev.yoru.Yoru"), "From a build, it names the main class and the classes");

        var installed = List.of("/Applications/Yoru.app/Contents/MacOS/Yoru", "--mcp");
        var snippet = Json.object(Json.read(ClaudeSetup.desktopSnippet(installed)));
        var entry = Json.object(Json.object(snippet.get("mcpServers")).get("yoru"));
        check(installed.getFirst().equals(entry.get("command")) && List.of("--mcp").equals(entry.get("args")), "The Desktop settings: " + snippet);
        check(ClaudeSetup.codeCommand(List.of("/opt/yoru/bin/Yoru", "--mcp")).equals("claude mcp add yoru -- /opt/yoru/bin/Yoru --mcp"),
            "The Claude Code command, bare when nothing needs quoting");
        check(ClaudeSetup.codeCommand(List.of("/Invented Apps/Yoru's.app/Yoru", "--mcp"))
                .equals("claude mcp add yoru -- '/Invented Apps/Yoru'\\''s.app/Yoru' --mcp"),
            "and quoted for a shell when a path has spaces or quotes");

        String os = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            check(ClaudeSetup.desktopConfig().endsWith(Path.of("Library", "Application Support", "Claude", "claude_desktop_config.json")), "The Mac's settings file");
            System.setProperty("os.name", "Windows 11");
            check(ClaudeSetup.desktopConfig().endsWith(Path.of("Claude", "claude_desktop_config.json")), "Windows's settings file");
            System.setProperty("os.name", "Linux");
            check(ClaudeSetup.desktopConfig().endsWith(Path.of(".config", "Claude", "claude_desktop_config.json")), "and elsewhere");
        } finally {
            System.setProperty("os.name", os);
        }

        var dir = Files.createTempDirectory("yoru-claude-setup");
        try {
            var fresh = dir.resolve("new").resolve("claude_desktop_config.json");
            check(ClaudeSetup.addToDesktop(fresh, installed), "A missing file is made");
            check(Json.object(Json.object(Json.read(Files.readString(fresh))).get("mcpServers")).containsKey("yoru"), "with Yoru in it");
            check(!Files.exists(fresh.resolveSibling("claude_desktop_config.json.before-yoru")), "and no copy of a file that was not there");

            var config = dir.resolve("claude_desktop_config.json");
            String theirs = "{\n  \"globalShortcut\": \"Alt+Space\",\n  \"mcpServers\": {\n    \"invented\": {\"command\": \"invented-server\", \"args\": [\"--flag\"], \"env\": {\"LEVEL\": \"2\"}}\n  },\n  \"number\": 1.50\n}\n";
            Files.writeString(config, theirs);
            check(ClaudeSetup.addToDesktop(config, installed), "An existing file is added to");
            var after = Json.object(Json.read(Files.readString(config)));
            check("Alt+Space".equals(after.get("globalShortcut")) && after.get("number").toString().equals("1.50"), "Its other settings are kept");
            var servers = Json.object(after.get("mcpServers"));
            check(servers.containsKey("invented") && servers.containsKey("yoru"), "and its other servers");
            check(Json.object(Json.object(servers.get("invented")).get("env")).get("LEVEL").equals("2"), "exactly");
            check(Files.readString(config.resolveSibling("claude_desktop_config.json.before-yoru")).equals(theirs), "A copy of the file as it was is left beside it");
            check(!ClaudeSetup.addToDesktop(config, installed), "Adding it again changes nothing");
            check(ClaudeSetup.addToDesktop(config, List.of("/Invented/Other/Yoru", "--mcp")), "A moved Yoru is updated");
            check(Json.object(Json.object(Json.object(Json.read(Files.readString(config))).get("mcpServers")).get("yoru")).get("command")
                .equals("/Invented/Other/Yoru"), "to its new place");

            var broken = dir.resolve("broken.json");
            Files.writeString(broken, "{ this is not json");
            refused(broken, installed, "{ this is not json");
            var odd = dir.resolve("odd.json");
            Files.writeString(odd, "{\"mcpServers\": [1, 2]}");
            refused(odd, installed, "{\"mcpServers\": [1, 2]}");
            var empty = dir.resolve("empty.json");
            Files.writeString(empty, "");
            check(ClaudeSetup.addToDesktop(empty, installed), "An empty file is filled in");
            check(Files.readString(fresh, StandardCharsets.UTF_8).endsWith("\n"), "Files end with a line break");
        } finally {
            try (var files = Files.walk(dir)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: " + checks + " setup checks (the command, both apps' settings, Claude Desktop's file kept intact)");
    }

    private static void refused(Path file, List<String> command, String unchanged) throws IOException {
        try {
            ClaudeSetup.addToDesktop(file, command);
            check(false, "A file Yoru cannot read is refused: " + file.getFileName());
        } catch (IOException refused) {
            check(refused.getMessage().contains("left alone"), "and says it was left alone: " + refused.getMessage());
        }
        check(Files.readString(file).equals(unchanged), "and it is untouched");
        check(!Files.exists(file.resolveSibling(file.getFileName() + ".yoru-new")), "with nothing half-written beside it");
    }
}
