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
    public AnkiConnect() { this(URI.create("http://127.0.0.1:8765")); }
    public AnkiConnect(URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getUserInfo() != null) throw new IllegalArgumentException("Anki must be local.");
        this.endpoint = endpoint;
    }
    public record Snapshot(long today, NavigableMap<LocalDate, Long> days, Instant fetchedAt) {
        public Snapshot { days = Collections.unmodifiableNavigableMap(new TreeMap<>(days)); }
        public long lastSevenDays(LocalDate date) {
            return days.subMap(date.minusDays(6), true, date, true).values().stream().mapToLong(Long::longValue).sum();
        }
    }
    public Snapshot read(String key) throws IOException {
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
        return new Snapshot(today, days, Instant.now());
    }
    private static long count(Object value) throws IOException {
        if (!(value instanceof java.math.BigDecimal n)) throw invalid();
        try { long v = n.longValueExact(); if (v < 0 || v > Integer.MAX_VALUE) throw invalid(); return v; }
        catch (ArithmeticException e) { throw invalid(); }
    }
    private Object call(String action, String key) throws IOException {
        var request = new LinkedHashMap<String, Object>();
        request.put("action", action); request.put("version", 6);
        if (key != null && !key.isEmpty()) request.put("key", key);
        var connection = (HttpURLConnection) endpoint.toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            byte[] body = Json.write(request).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (var out = connection.getOutputStream()) { out.write(body); }
            if (connection.getResponseCode() != 200) throw new IOException("AnkiConnect did not accept the request. Check its settings.");
            byte[] bytes;
            try (var in = connection.getInputStream()) { bytes = in.readNBytes(LIMIT + 1); }
            if (bytes.length > LIMIT) throw invalid();
            Object parsed;
            try { parsed = Json.read(new String(bytes, StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw invalid(); }
            if (!(parsed instanceof Map<?, ?> result) || !result.containsKey("error") || !result.containsKey("result")) throw invalid();
            if (result.get("error") != null) throw new IOException("AnkiConnect rejected the request. Check the API key and add-on version.");
            return result.get("result");
        } finally { connection.disconnect(); }
    }
    private static IOException invalid() { return new IOException("AnkiConnect returned invalid review data. Update the add-on and try again."); }
}
