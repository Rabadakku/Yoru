package dev.yoru.ai;

import dev.yoru.json.Json;
import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The way from an AI app to the running window (#47): the socket and token
 * only this account can use, one request per connection, a stranger without the
 * token turned away, a second window refused, a stale socket replaced, and the
 * whole path from protocol lines on standard input to a tool's answer.
 */
public final class BridgeTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("yoru-bridge");
        var folder = root.resolve("assistant");
        var calls = new AtomicInteger();
        Bridge.Handler handler = (tool, arguments) -> {
            calls.incrementAndGet();
            return Mcp.text(tool + " " + arguments.get("word"), false);
        };

        try (var bridge = Bridge.open(folder, handler)) {
            check(Files.exists(folder.resolve(Bridge.SOCKET)) && Files.exists(folder.resolve(Bridge.TOKEN)), "The socket and token are made");
            if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
                check(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)).equals("rwx------"), "Only this account can enter the folder");
                check(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve(Bridge.TOKEN))).equals("rw-------"), "or read the token");
            }
            String token = Files.readString(folder.resolve(Bridge.TOKEN)).strip();
            check(token.matches("[0-9a-f]{64}"), "The token is 256 random bits");

            var result = Bridge.call(folder, "echo", Map.of("word", "moon"));
            check("echo moon".equals(text(result)) && Boolean.FALSE.equals(result.get("isError")), "A call is answered: " + result);
            check(!Json.write(result).contains(token), "The token never comes back in an answer");

            // Unicode and line breaks inside arguments survive the one-line transport.
            var unusual = Bridge.call(folder, "echo", Map.of("word", "夜\nline \"two\""));
            check("echo 夜\nline \"two\"".equals(text(unusual)), "Any text survives the trip");

            int before = calls.get();
            check(Boolean.TRUE.equals(raw(folder, "{\"token\":\"" + "0".repeat(64) + "\",\"tool\":\"echo\"}").get("isError")),
                "A wrong token is refused");
            check(Boolean.TRUE.equals(raw(folder, "{\"tool\":\"echo\"}").get("isError")), "and so is no token");
            check(Boolean.TRUE.equals(raw(folder, "not json").get("isError")), "and a request that is not JSON");
            check(calls.get() == before, "None of them reached the handler");

            try (var second = Bridge.open(folder, handler)) {
                check(false, "A second window cannot take over the socket");
            } catch (IOException refused) {
                check(refused.getMessage().contains("Another Yoru window"), "It is told why: " + refused.getMessage());
            }
            check(text(Bridge.call(folder, "echo", Map.of("word", "still"))).equals("echo still"), "and the first keeps answering");

            // Several callers at once each get their own answer.
            var threads = new ArrayList<Thread>();
            var answers = Collections.synchronizedList(new ArrayList<String>());
            for (int i = 0; i < 8; i++) {
                int n = i;
                threads.add(Thread.ofVirtual().start(() -> {
                    try { answers.add(text(Bridge.call(folder, "echo", Map.of("word", "w" + n)))); }
                    catch (IOException e) { answers.add("failed"); }
                }));
            }
            for (var t : threads) t.join();
            check(answers.size() == 8 && new HashSet<>(answers).size() == 8 && !answers.contains("failed"), "Callers at once: " + answers);

            endToEnd(folder, true);
        }
        check(!Files.exists(folder.resolve(Bridge.SOCKET)) && !Files.exists(folder.resolve(Bridge.TOKEN)), "Closing removes the socket and the token");
        try {
            Bridge.call(folder, "echo", Map.of());
            check(false, "Nobody answers once it is closed");
        } catch (Bridge.Unavailable closed) {
            check(true, "A closed bridge is unavailable");
        }
        endToEnd(folder, false);

        // A socket left by a copy that did not close is replaced, not a reason to refuse.
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(Bridge.SOCKET), "left behind");
        try (var again = Bridge.open(folder, handler)) {
            check(text(Bridge.call(folder, "echo", Map.of("word", "again"))).equals("echo again"), "A stale socket is replaced");
        }

        // A handler that fails is an answer that says so, never a hung caller.
        try (var broken = Bridge.open(folder, (tool, arguments) -> { throw new IllegalStateException("invented failure"); })) {
            var failed = Bridge.call(folder, "echo", Map.of());
            check(Boolean.TRUE.equals(failed.get("isError")) && !text(failed).contains("invented failure"),
                "A failure is reported in Yoru's own words: " + text(failed));
        }
        try (var files = Files.walk(root)) {
            for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        System.out.println("PASS: " + checks + " bridge checks (owner-only socket and token, strangers refused, one window, end to end)");
    }

    /** The protocol lines an AI app would send, through McpMain, to whatever is listening in {@code folder}. */
    private static void endToEnd(Path folder, boolean open) throws IOException {
        String input = String.join("\n",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_overview\",\"arguments\":{\"word\":\"sun\"}}}") + "\n";
        var out = new ByteArrayOutputStream();
        McpMain.run(new BufferedReader(new StringReader(input)), out, folder, "1.2.3");
        var lines = out.toString(StandardCharsets.UTF_8).strip().split("\n");
        check(lines.length == 3, "Three requests, three replies, and none for the notification: " + lines.length);
        var tools = Json.array(Json.object(Json.object(Json.read(lines[1])).get("result")).get("tools"));
        check(tools.size() == WorkspaceTools.tools().size(), "The tool list is served " + (open ? "with the app open" : "with the app closed"));
        var result = Json.object(Json.object(Json.read(lines[2])).get("result"));
        if (open) check("get_overview sun".equals(text(result)), "A call reaches the app: " + result);
        else check(Boolean.TRUE.equals(result.get("isError")) && text(result).contains("open Yoru"),
            "With the app closed, a call says how to fix it: " + text(result));
    }

    private static Map<?, ?> raw(Path folder, String line) throws IOException {
        try (var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.connect(UnixDomainSocketAddress.of(folder.resolve(Bridge.SOCKET)));
            var out = Channels.newOutputStream(socket);
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            var in = new BufferedReader(new InputStreamReader(Channels.newInputStream(socket), StandardCharsets.UTF_8));
            return Json.object(Json.read(in.readLine()));
        }
    }

    private static String text(Map<?, ?> result) {
        return (String) Json.object(Json.array(result.get("content")).getFirst()).get("text");
    }
}
