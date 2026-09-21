package dev.yoru.anki;

import dev.yoru.json.Json;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Read-only, bounded access to the AnkiConnect instance on this computer. */
public final class AnkiConnect {
    private static final int LIMIT = 2_000_000;
    private final URI endpoint;
    private final int timeoutMillis;
    /** Safe messages produced locally, never raw Anki responses or credentials. */
    public static final class Failure extends IOException {
        Failure(String message) { super(message); }
    }
    public AnkiConnect() { this(URI.create("http://127.0.0.1:8765")); }
    public AnkiConnect(URI endpoint) { this(endpoint, 5000); }
    AnkiConnect(URI endpoint, int timeoutMillis) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getUserInfo() != null) throw new IllegalArgumentException("Anki must be local.");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Timeout must be positive.");
        this.endpoint = endpoint; this.timeoutMillis = timeoutMillis;
    }
    public record Snapshot(String profile, long today, NavigableMap<LocalDate, Long> days, Instant fetchedAt) {
        public Snapshot { days = Collections.unmodifiableNavigableMap(new TreeMap<>(days)); }
        public long lastSevenDays(LocalDate date) {
            return days.subMap(date.minusDays(6), true, date, true).values().stream().mapToLong(Long::longValue).sum();
        }
    }
    public Snapshot read(String key) throws IOException {
        String profile = profile(call("getActiveProfile", key));
        long today = count(call("getNumCardsReviewedToday", key));
        Object raw = call("getNumCardsReviewedByDay", key);
        if (!(raw instanceof List<?> rows)) throw invalid();
        var days = new TreeMap<LocalDate, Long>();
        try {
            for (Object row : rows) {
                if (!(row instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof String date)) throw invalid();
                if (days.putIfAbsent(LocalDate.parse(date), count(pair.get(1))) != null) throw invalid();
            }
        } catch (java.time.DateTimeException e) { throw invalid(); }
        if (!profile.equals(profile(call("getActiveProfile", key))))
            throw new Failure("The Anki profile changed during refresh. Refresh again to read the selected profile.");
        return new Snapshot(profile, today, days, Instant.now());
    }
    private static String profile(Object value) throws IOException {
        if (!(value instanceof String name) || name.isBlank() || name.length() > 256)
            throw new Failure("Open an Anki profile, then refresh again.");
        return name;
    }
    private static long count(Object value) throws IOException {
        if (!(value instanceof java.math.BigDecimal n)) throw invalid();
        try { long v = n.longValueExact(); if (v < 0 || v > Integer.MAX_VALUE) throw invalid(); return v; }
        catch (ArithmeticException e) { throw invalid(); }
    }
    private Object call(String action, String key) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        var request = new LinkedHashMap<String, Object>();
        request.put("action", action); request.put("version", 6);
        if (key != null && !key.isEmpty()) request.put("key", key);
        var connection = (HttpURLConnection) endpoint.toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(Math.min(3000, timeoutMillis)); connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            byte[] body = Json.write(request).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (var out = connection.getOutputStream()) { out.write(body); }
            if (connection.getResponseCode() != 200) throw new Failure("AnkiConnect did not accept the request. Check its settings.");
            byte[] bytes;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            try (var in = connection.getInputStream(); var data = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (data.size() <= LIMIT) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new java.net.SocketTimeoutException();
                    connection.setReadTimeout((int) Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                    int n = in.read(buffer, 0, Math.min(buffer.length, LIMIT + 1 - data.size()));
                    if (n < 0) break;
                    data.write(buffer, 0, n);
                }
                bytes = data.toByteArray();
            }
            if (bytes.length > LIMIT) throw invalid();
            Object parsed;
            try { parsed = Json.read(new String(bytes, StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw invalid(); }
            if (!(parsed instanceof Map<?, ?> result) || !result.containsKey("error") || !result.containsKey("result")) throw invalid();
            if (result.get("error") != null) throw new Failure("AnkiConnect rejected the request. Check the API key and update the add-on. Disconnect to change the key.");
            return result.get("result");
        } catch (java.net.SocketTimeoutException e) {
            throw new Failure("Anki took too long to respond. Wait for Anki to finish its current operation, then refresh.");
        } catch (java.net.ConnectException e) {
            throw new Failure("Open Anki with AnkiConnect enabled on port 8765, then refresh.");
        } finally { connection.disconnect(); }
    }
    private static IOException invalid() { return new Failure("AnkiConnect returned invalid review data. Update the add-on and try again."); }
}
