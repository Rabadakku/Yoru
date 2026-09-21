package dev.yoru.anki;

import java.io.*;
import dev.yoru.json.Json;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class AnkiConnectTest {
    public static void main(String[] args) throws Exception {
        var reply = new AtomicReference<>("[[\"2026-01-02\",12],[\"2026-01-01\",5]]");
        var changeProfile = new java.util.concurrent.atomic.AtomicBoolean();
        var profileReads = new java.util.concurrent.atomic.AtomicInteger();
        var envelope = new AtomicReference<String>();
        // 250 cards, so the logs are asked for in more than one batch. Each
        // card's log: an answer an hour ago (the cards a second apart), one
        // three days ago, and a reschedule, which is not an answer.
        long now = System.currentTimeMillis(), hour = now - 3_600_000, days3 = now - 3 * 86_400_000L;
        var cardIds = new java.util.ArrayList<Long>();
        for (long i = 1; i <= 250; i++) cardIds.add(i);
        var found = new AtomicReference<>(cardIds.toString());
        var log = new AtomicReference<java.util.function.LongFunction<String>>(card ->
            "[{\"id\":" + (hour + card * 1000) + ",\"usn\":1,\"ease\":3,\"ivl\":4,\"lastIvl\":1,\"factor\":2500,\"time\":5000,\"type\":1},"
            + "{\"id\":" + (days3 + card * 1000) + ",\"usn\":1,\"ease\":1,\"ivl\":1,\"lastIvl\":1,\"factor\":2300,\"time\":8000,\"type\":2},"
            + "{\"id\":" + (hour + card * 1000 + 7) + ",\"usn\":1,\"ease\":0,\"ivl\":0,\"lastIvl\":4,\"factor\":2500,\"time\":0,\"type\":4}]");
        var searches = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var batches = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
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
                assert action.equals("getNumCardsReviewedToday") || action.equals("getNumCardsReviewedByDay") || action.equals("getActiveProfile")
                    || action.equals("findCards") || action.equals("getReviewsOfCards") : action;
                String response = "{\"error\":null,\"result\":" + (action.equals("getNumCardsReviewedToday") ? "12" : reply.get()) + "}";
                if (action.equals("findCards")) {
                    var params = (Map<?, ?>) request.get("params");
                    searches.add((String) params.get("query"));
                    response = "{\"error\":null,\"result\":" + found.get() + "}";
                }
                if (action.equals("getReviewsOfCards")) {
                    var cards = (List<?>) ((Map<?, ?>) request.get("params")).get("cards");
                    batches.add(cards.size());
                    var byCard = new StringBuilder("{");
                    for (Object card : cards) {
                        if (byCard.length() > 1) byCard.append(',');
                        byCard.append('"').append(card).append("\":").append(log.get().apply(((Number) card).longValue()));
                    }
                    response = "{\"error\":null,\"result\":" + byCard.append('}') + "}";
                }
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
            // Review times: one day's reach asks Anki for two of its days, in batches.
            assert searches.getFirst().equals("rated:2") : searches;
            assert batches.stream().allMatch(n -> n <= 100) && batches.stream().mapToInt(Integer::intValue).sum() == 500 : batches;
            assert snapshot.reviews().size() == 250 : "the hour-old answers, without the old one or the reschedule";
            assert snapshot.reviews().stream().allMatch(r -> r.millis() == 5000);
            assert Math.abs(snapshot.complete().toEpochMilli() - (now - 86_400_000L)) < 60_000 : snapshot.complete();
            searches.clear();
            var week = client.read("fixture-key", 7);
            assert searches.getFirst().equals("rated:8") && week.reviews().size() == 500 : "a week's reach keeps the older answers";
            for (int days : new int[]{0, AnkiConnect.MAX_DAYS + 1}) {
                try { client.read("fixture-key", days); throw new AssertionError("accepted a reach of " + days); }
                catch (IllegalArgumentException expected) { }
            }
            try { snapshot.reviews().clear(); throw new AssertionError("mutable reviews"); }
            catch (UnsupportedOperationException expected) { }
            var goodLog = log.get();
            for (java.util.function.LongFunction<String> invalid : List.<java.util.function.LongFunction<String>>of(
                    card -> "{}", card -> "[1]", card -> "[{\"id\":" + hour + ",\"ease\":3}]",
                    card -> "[{\"id\":-5,\"ease\":3,\"time\":1000}]", card -> "[{\"id\":" + hour + ",\"ease\":3,\"time\":-1}]")) {
                log.set(invalid);
                try { client.read("fixture-key"); throw new AssertionError("accepted an invalid review log"); }
                catch (java.io.IOException expected) { assert expected.getMessage().contains("invalid review data"); }
            }
            log.set(goodLog);
            for (String invalid : new String[]{"{}", "[0]", "[\"1\"]"}) {
                found.set(invalid);
                try { client.read("fixture-key"); throw new AssertionError("accepted invalid card ids"); }
                catch (java.io.IOException expected) { }
            }
            found.set(cardIds.toString());
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
        System.out.println("PASS: Anki local transport, API key, review history, review times in batches, idempotent refresh and invalid responses");
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
