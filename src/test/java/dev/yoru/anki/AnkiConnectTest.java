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
                assert action.equals("getNumCardsReviewedToday") || action.equals("getNumCardsReviewedByDay");
                String response = "{\"error\":null,\"result\":" + (action.equals("getNumCardsReviewedToday") ? "12" : reply.get()) + "}";
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
            assert snapshot.today() == 12;
            assert snapshot.lastSevenDays(LocalDate.parse("2026-01-02")) == 17;
            assert snapshot.lastSevenDays(LocalDate.parse("2026-01-09")) == 0;
            assert client.read("fixture-key").days().equals(snapshot.days()) : "refresh does not accumulate duplicates";
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
        System.out.println("PASS: Anki local transport, API key, review history, idempotent refresh and invalid responses");
    }
}
