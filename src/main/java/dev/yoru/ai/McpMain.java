package dev.yoru.ai;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code Yoru --mcp}: the process an AI app starts to reach Yoru (#47).
 *
 * It opens no window and no vault. It speaks the Model Context Protocol on
 * standard input and output, answers what it can by itself — the handshake and
 * the list of tools — and passes each tool call to the running app over the
 * {@link Bridge}. When the app is closed, locked, or has assistants switched
 * off, a tool call says so and how to change it, rather than failing silently,
 * and the next call tries again: opening Yoru later needs no restart here.
 *
 * Standard output carries protocol messages and nothing else, so nothing here
 * prints; anything worth saying goes back as a message.
 */
public final class McpMain {
    private McpMain() { }

    /** What a tool call says when there is no app to answer it. */
    static final String CLOSED = "Yoru is not open, or it is not letting assistants in. Ask the user to open Yoru, "
        + "unlock their vault, and switch on AI assistants in Yoru's Settings. Then try again.";

    public static void main(String[] args) throws IOException {
        // Before anything could touch the window system: this process never draws.
        System.setProperty("java.awt.headless", "true");
        var in = new BufferedReader(new InputStreamReader(new FileInputStream(FileDescriptor.in), StandardCharsets.UTF_8));
        var out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));
        // A build run from source has no release number; the protocol still wants a version.
        var running = dev.yoru.update.Version.running();
        run(in, out, Bridge.folder(), running == null ? "development" : running.toString());
    }

    /** Answers messages from {@code in} on {@code out} until the input ends. */
    static void run(BufferedReader in, OutputStream out, Path folder, String version) throws IOException {
        var server = new Mcp(backend(folder), version, WorkspaceTools.instructions());
        for (String line; (line = Bridge.readLine(in, Mcp.MAX_MESSAGE)) != null; ) {
            if (line.isBlank()) continue;
            String reply = server.handle(line);
            if (reply == null) continue;
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    static Mcp.Backend backend(Path folder) {
        return new Mcp.Backend() {
            public List<Map<String, Object>> tools() {
                return WorkspaceTools.tools().stream().map(WorkspaceTools.Tool::definition).toList();
            }
            public boolean has(String name) { return WorkspaceTools.tool(name).isPresent(); }
            public Map<String, Object> call(String name, Map<String, Object> arguments) {
                try {
                    return Bridge.call(folder, name, arguments);
                } catch (Bridge.Unavailable closed) {
                    return Mcp.text(CLOSED, true);
                }
            }
        };
    }
}
