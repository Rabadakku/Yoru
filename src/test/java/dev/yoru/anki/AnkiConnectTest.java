package dev.yoru.anki;

import java.io.*;
import dev.yoru.json.Json;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class AnkiConnectTest {
    public static void main(String[] args) throws Exception {
        var reply = new AtomicReference<>("[[\"2026-01-02\",12],[\"2026-01-01\",5]]");
        var changeProfile = new java.util.concurrent.atomic.AtomicBoolean();
        var profileReads = new java.util.concurrent.atomic.AtomicInteger();
        var envelope = new AtomicReference<String>();
        var server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        var serverFailure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().daemon().start(() -> {
            while (!server.isClosed()) try (var socket = server.accept()) {
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assert in.readLine().startsWith("POST ");
                int length = 0;
                for (String line; !(line = in.readLine()).isEmpty();) {
                    if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                }
                char[] body = new char[length]; int offset = 0;
                while (offset < length) { int n = in.read(body, offset, length - offset); if (n < 0) throw new EOFException(); offset += n; }
                var request = (Map<?, ?>) Json.read(new String(body));
                assert "fixture-key".equals(request.get("key"));
                assert request.get("version").toString().equals("6");
                String action = (String) request.get("action");
                assert action.equals("getNumCardsReviewedToday") || action.equals("getNumCardsReviewedByDay") || action.equals("getActiveProfile");
                String response = "{\"error\":null,\"result\":" + (action.equals("getNumCardsReviewedToday") ? "12" : reply.get()) + "}";
                if (action.equals("getActiveProfile")) {
                    String profile = changeProfile.get() && profileReads.incrementAndGet() % 2 == 0 ? "Other" : "Practice";
                    response = "{\"error\":null,\"result\":\"" + profile + "\"}";
                }
                if (envelope.get() != null) response = envelope.get();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                var out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes); out.flush();
            } catch (Throwable e) { if (!server.isClosed()) serverFailure.set(e); }
        });
        try {
            var client = new AnkiConnect(URI.create("http://127.0.0.1:" + server.getLocalPort()));
            var snapshot = client.read("fixture-key");
            assert snapshot.profile().equals("Practice");
            assert snapshot.today() == 12;
            assert snapshot.lastSevenDays(LocalDate.parse("2026-01-02")) == 17;
            assert snapshot.lastSevenDays(LocalDate.parse("2026-01-09")) == 0;
            assert client.read("fixture-key").days().equals(snapshot.days()) : "refresh does not accumulate duplicates";
            changeProfile.set(true);
            try { client.read("fixture-key"); throw new AssertionError("mixed profiles"); }
            catch (AnkiConnect.Failure expected) { assert expected.getMessage().contains("profile changed"); }
            changeProfile.set(false);
            try { snapshot.days().clear(); throw new AssertionError("mutable snapshot"); }
            catch (UnsupportedOperationException expected) { }
            for (String invalid : new String[]{"null", "{}", "[[\"bad-date\",1]]", "[[\"2026-01-02\",-1]]",
                    "[[\"2026-01-02\",1.5]]", "[[\"2026-01-02\",1],[\"2026-01-02\",2]]", "[[\"2026-01-02\",999999999999999]]"}) {
                reply.set(invalid);
                try { client.read("fixture-key"); throw new AssertionError("accepted invalid data"); }
                catch (java.io.IOException expected) { }
            }
            for (String invalid : new String[]{"{\"error\":\"bad key\",\"result\":null}",
                    "{\"result\":12}", "not json", "{\"error\":null,\"result\":-1}"}) {
                envelope.set(invalid);
                try { client.read("fixture-key"); throw new AssertionError("accepted invalid envelope"); }
                catch (java.io.IOException expected) { }
            }
            try { new AnkiConnect(URI.create("https://example.invalid")); throw new AssertionError("remote endpoint"); }
            catch (IllegalArgumentException expected) { }
        } finally { server.close(); worker.join(1000); }
        assert serverFailure.get() == null : serverFailure.get();
        transport("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/\r\nContent-Length: 0\r\n\r\n", false, "did not accept");
        transport("HTTP/1.1 200 OK\r\nContent-Length: 2000100\r\n\r\n" + "x".repeat(2_000_100), false, "invalid review data");
        transport("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n", true, "too long");
        Thread.currentThread().interrupt();
        try { new AnkiConnect().read(null); throw new AssertionError("interrupted request continued"); }
        catch (InterruptedIOException expected) { }
        finally { Thread.interrupted(); }
        System.out.println("PASS: Anki local transport, API key, review history, idempotent refresh and invalid responses");
    }
    private static void transport(String response, boolean slow, String expectedMessage) throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = Thread.ofPlatform().daemon().start(() -> {
                try (var accepted = socket.accept()) {
                    var in = new BufferedReader(new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8));
                    int length = 0;
                    for (String line; !(line = in.readLine()).isEmpty();) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    }
                    for (int i = 0; i < length; i++) in.read();
                    var out = accepted.getOutputStream();
                    out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush();
                    if (slow) for (int i = 0; i < 100; i++) { out.write(' '); out.flush(); Thread.sleep(20); }
                } catch (IOException expected) { /* the bounded client deliberately closes early */ }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            var client = new AnkiConnect(URI.create("http://127.0.0.1:" + socket.getLocalPort()), slow ? 150 : 2000);
            long started = System.nanoTime();
            try { client.read(null); throw new AssertionError("accepted invalid transport"); }
            catch (AnkiConnect.Failure expected) { assert expected.getMessage().contains(expectedMessage) : expected; }
            if (slow) assert System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(2) : "body deadline was not enforced";
            responder.join(3000);
        }
    }

}
