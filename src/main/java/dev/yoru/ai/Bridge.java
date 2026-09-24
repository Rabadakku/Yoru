package dev.yoru.ai;

import dev.yoru.json.Json;
import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * How the {@code --mcp} process reaches the running app (#47).
 *
 * An AI app starts a second copy of Yoru to talk to; the vault is open, and
 * unlocked, in the first. The two meet at a Unix-domain socket in a folder only
 * this account can enter, and every request carries a token read from a file in
 * that folder, so another account on the computer can neither connect nor
 * guess its way in. Nothing listens on the network: there is no port.
 *
 * One request per connection: the token, the tool and its arguments on one
 * line, the result on one line back. An app that is closed, locked or has
 * assistants switched off is simply not listening, and the caller says so.
 */
public final class Bridge implements AutoCloseable {
    static final String SOCKET = "yoru.sock", TOKEN = "token";
    /** The longest request read: a page's worth of Markdown with room to spare. */
    static final int MAX_REQUEST = 8_000_000;
    /** The longest a caller waits for an answer before giving up on the app. */
    static final long WAIT_SECONDS = 60;
    /** How many requests are answered at once; more wait their turn. */
    private static final int AT_ONCE = 4;

    /** What the app does with one tool call. */
    @FunctionalInterface
    public interface Handler {
        Map<String, Object> call(String tool, Map<String, Object> arguments);
    }

    private final Path folder;
    private final ServerSocketChannel server;
    private final byte[] token;
    private final Handler handler;
    private final Semaphore turns = new Semaphore(AT_ONCE);
    private volatile boolean open = true;

    private Bridge(Path folder, ServerSocketChannel server, byte[] token, Handler handler) {
        this.folder = folder;
        this.server = server;
        this.token = token;
        this.handler = handler;
    }

    /** Where the bridge lives for this account: beside the vaults, in Yoru's own folder. */
    public static Path folder() {
        return Path.of(System.getProperty("user.home"), "Yoru", "assistant");
    }

    /**
     * Starts answering in {@code folder}, or refuses when another copy of Yoru
     * already is: two windows cannot both be the one an assistant talks to.
     */
    public static Bridge open(Path folder, Handler handler) throws IOException {
        privateFolder(folder);
        var socket = folder.resolve(SOCKET);
        if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            if (answering(socket)) throw new IOException("Another Yoru window is already answering assistants.");
            // Left behind by a copy that did not close cleanly.
            Files.delete(socket);
        }
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(socket));
            ownerOnly(socket);
            byte[] token = new byte[32];
            new SecureRandom().nextBytes(token);
            writeToken(folder.resolve(TOKEN), HexFormat.of().formatHex(token));
            var bridge = new Bridge(folder, server, HexFormat.of().formatHex(token).getBytes(StandardCharsets.US_ASCII), handler);
            Thread.ofPlatform().daemon().name("yoru-assistant-bridge").start(bridge::serve);
            return bridge;
        } catch (IOException | RuntimeException failed) {
            server.close();
            Files.deleteIfExists(socket);
            throw failed;
        }
    }

    private static boolean answering(Path socket) {
        try (var probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException gone) {
            return false;
        }
    }

    private void serve() {
        while (open) {
            SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException closed) {
                if (!open) return;
                continue;
            }
            Thread.ofVirtual().name("yoru-assistant-request").start(() -> answer(client));
        }
    }

    private void answer(SocketChannel client) {
        try (client) {
            var in = new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
            var out = Channels.newOutputStream(client);
            // Read before taking a turn: a caller that connects and says
            // nothing waits on its own thread, and never holds a turn from one
            // that has asked something.
            String line = readLine(in, MAX_REQUEST);
            if (line == null) return;
            Map<String, Object> reply;
            turns.acquire();
            try {
                reply = respond(line);
            } finally {
                turns.release();
            }
            out.write((Json.write(reply) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException | InterruptedException ignored) {
            // The caller went away before its answer; there is nobody left to tell.
        }
    }

    private Map<String, Object> respond(String line) {
        Map<?, ?> request;
        try {
            request = Json.object(Json.read(line, MAX_REQUEST));
        } catch (IllegalArgumentException bad) {
            return Mcp.text("Yoru could not read that request.", true);
        }
        byte[] given = request.get("token") instanceof String s ? s.getBytes(StandardCharsets.US_ASCII) : new byte[0];
        // Compared in constant time, so the answer's speed says nothing about how close a guess was.
        if (!MessageDigest.isEqual(given, token)) return Mcp.text("Yoru refused a request without its token.", true);
        if (!(request.get("tool") instanceof String tool)) return Mcp.text("Name the tool to call.", true);
        var arguments = new LinkedHashMap<String, Object>();
        if (request.get("arguments") instanceof Map<?, ?> map) map.forEach((k, v) -> arguments.put(k.toString(), v));
        try {
            return handler.call(tool, arguments);
        } catch (RuntimeException failed) {
            return Mcp.text("Yoru hit a problem answering that: " + failed.getClass().getSimpleName() + ".", true);
        }
    }

    /** Stops answering and removes the socket and token, so no caller finds a door that leads nowhere. */
    @Override public void close() {
        open = false;
        try { server.close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(folder.resolve(SOCKET)); } catch (IOException ignored) { }
        try { Files.deleteIfExists(folder.resolve(TOKEN)); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ the caller's side

    /** Thrown when the app is not there to ask: closed, locked, or with assistants switched off. */
    public static final class Unavailable extends IOException {
        Unavailable(String message) { super(message); }
    }

    /**
     * Asks the running app to run one tool, and returns its result.
     *
     * @throws Unavailable when no app is answering in {@code folder}, or it does not answer in time.
     */
    public static Map<String, Object> call(Path folder, String tool, Map<String, Object> arguments) throws Unavailable {
        String token;
        try {
            token = Files.readString(folder.resolve(TOKEN), StandardCharsets.US_ASCII).strip();
        } catch (IOException none) {
            throw new Unavailable("Yoru is not answering.");
        }
        var request = new LinkedHashMap<String, Object>();
        request.put("token", token);
        request.put("tool", tool);
        request.put("arguments", arguments);
        var asking = Executors.newVirtualThreadPerTaskExecutor();
        SocketChannel[] channel = new SocketChannel[1];
        Future<String> answer = asking.submit(() -> {
            try (var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel[0] = socket;
                socket.connect(UnixDomainSocketAddress.of(folder.resolve(SOCKET)));
                var out = Channels.newOutputStream(socket);
                out.write((Json.write(request) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                var in = new BufferedReader(new InputStreamReader(Channels.newInputStream(socket), StandardCharsets.UTF_8));
                return readLine(in, Mcp.MAX_MESSAGE);
            }
        });
        try {
            String line = answer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            if (line == null) throw new Unavailable("Yoru closed the connection without answering.");
            var reply = Json.object(Json.read(line, Mcp.MAX_MESSAGE));
            var result = new LinkedHashMap<String, Object>();
            reply.forEach((k, v) -> result.put(k.toString(), v));
            return result;
        } catch (TimeoutException slow) {
            try { if (channel[0] != null) channel[0].close(); } catch (IOException ignored) { }
            throw new Unavailable("Yoru did not answer within " + WAIT_SECONDS + " seconds.");
        } catch (ExecutionException | IllegalArgumentException failed) {
            throw new Unavailable("Yoru is not answering.");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Stopped waiting for Yoru.");
        } finally {
            asking.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ files only this account can open

    private static void privateFolder(Path folder) throws IOException {
        Files.createDirectories(folder);
        if (Files.getFileStore(folder).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    }

    private static void ownerOnly(Path file) throws IOException {
        if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    private static void writeToken(Path file, String token) throws IOException {
        var temporary = file.resolveSibling(TOKEN + ".new");
        Files.deleteIfExists(temporary);
        if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(temporary);
        }
        Files.writeString(temporary, token, StandardCharsets.US_ASCII);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** One line, refused rather than read on past {@code limit} characters. */
    static String readLine(BufferedReader in, int limit) throws IOException {
        var line = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') return line.toString();
            if (c != '\r') line.append((char) c);
            if (line.length() > limit) throw new IOException("That message is too long.");
        }
        return line.isEmpty() ? null : line.toString();
    }
}
