package dev.yoru.anki;

import dev.yoru.application.AnkiTime;
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
    /** The furthest back review times are read. */
    public static final int MAX_DAYS = 30;
    /** Cards asked about in one request; each brings its whole review log. */
    private static final int BATCH = 100;
    private static final int MAX_CARDS = 200_000;
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
    /**
     * One refresh's worth of Anki.
     *
     * reviews are the answers given since complete, the moment they are known
     * to be complete from; they are what study time is made of (AnkiTime).
     */
    public record Snapshot(String profile, long today, NavigableMap<LocalDate, Long> days,
                           List<AnkiTime.Review> reviews, Instant complete, Instant fetchedAt) {
        /** A snapshot of review counts alone, with no answers to make study time from. */
        public Snapshot(String profile, long today, NavigableMap<LocalDate, Long> days, Instant fetchedAt) {
            this(profile, today, days, List.of(), fetchedAt, fetchedAt);
        }
        public Snapshot {
            days = Collections.unmodifiableNavigableMap(new TreeMap<>(days));
            reviews = List.copyOf(reviews);
        }
        public long lastSevenDays(LocalDate date) {
            return days.subMap(date.minusDays(6), true, date, true).values().stream().mapToLong(Long::longValue).sum();
        }
    }
    /** Review counts and the answers of the last day. */
    public Snapshot read(String key) throws IOException { return read(key, 1); }
    /**
     * Review counts, and every answer given in the last {@code days} days.
     *
     * Anki's search counts days from its own day boundary, which is still
     * ahead, so asking for one day more than wanted covers {@code days} whole
     * days back from now whatever the boundary is.
     */
    public Snapshot read(String key, int days) throws IOException {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("Read 1 to " + MAX_DAYS + " days of Anki reviews.");
        Instant complete = Instant.now().minus(java.time.Duration.ofDays(days));
        String profile = profile(call("getActiveProfile", key));
        long today = count(call("getNumCardsReviewedToday", key));
        Object raw = call("getNumCardsReviewedByDay", key);
        if (!(raw instanceof List<?> rows)) throw invalid();
        var dates = new TreeMap<LocalDate, Long>();
        try {
            for (Object row : rows) {
                if (!(row instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof String date)) throw invalid();
                if (dates.putIfAbsent(LocalDate.parse(date), count(pair.get(1))) != null) throw invalid();
            }
        } catch (java.time.DateTimeException e) { throw invalid(); }
        var reviews = reviews(key, days + 1, complete);
        if (!profile.equals(profile(call("getActiveProfile", key))))
            throw new Failure("The Anki profile changed during refresh. Refresh again to read the selected profile.");
        return new Snapshot(profile, today, dates, reviews, complete, Instant.now());
    }
    /**
     * The answers given since complete, from cards Anki's search finds were
     * answered in its last {@code searchDays} days.
     *
     * A card's review log comes back whole, so answers from before complete
     * are dropped here, and cards are asked about a batch at a time so that one
     * reply stays well inside the size limit. Only answers are kept: a row
     * with no answer button is a reschedule, not study.
     */
    private List<AnkiTime.Review> reviews(String key, int searchDays, Instant complete) throws IOException {
        Object found = call("findCards", key, Map.of("query", "rated:" + searchDays));
        if (!(found instanceof List<?> ids) || ids.size() > MAX_CARDS) throw invalid();
        var cards = new ArrayList<Long>(ids.size());
        for (Object id : ids) cards.add(positive(id));
        long from = complete.toEpochMilli();
        var out = new TreeMap<Long, AnkiTime.Review>();
        for (int i = 0; i < cards.size(); i += BATCH) {
            Object raw = call("getReviewsOfCards", key, Map.of("cards", cards.subList(i, Math.min(cards.size(), i + BATCH))));
            if (!(raw instanceof Map<?, ?> byCard)) throw invalid();
            for (Object log : byCard.values()) {
                if (!(log instanceof List<?> rows)) throw invalid();
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> r)) throw invalid();
                    long id = positive(r.get("id"));
                    long millis = count(r.get("time"));
                    long ease = count(r.get("ease"));
                    if (id < from || ease < 1 || ease > 4 || millis == 0) continue;
                    out.putIfAbsent(id, new AnkiTime.Review(id, millis));
                }
            }
        }
        return List.copyOf(out.values());
    }
    private static String profile(Object value) throws IOException {
        if (!(value instanceof String name) || name.isBlank() || name.length() > 256)
            throw new Failure("Open an Anki profile, then refresh again.");
        return name;
    }
    private static long positive(Object value) throws IOException {
        if (!(value instanceof java.math.BigDecimal n)) throw invalid();
        try { long v = n.longValueExact(); if (v <= 0) throw invalid(); return v; }
        catch (ArithmeticException e) { throw invalid(); }
    }
    private static long count(Object value) throws IOException {
        if (!(value instanceof java.math.BigDecimal n)) throw invalid();
        try { long v = n.longValueExact(); if (v < 0 || v > Integer.MAX_VALUE) throw invalid(); return v; }
        catch (ArithmeticException e) { throw invalid(); }
    }
    private Object call(String action, String key) throws IOException { return call(action, key, null); }
    private Object call(String action, String key, Map<String, ?> params) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        var request = new LinkedHashMap<String, Object>();
        request.put("action", action); request.put("version", 6);
        if (params != null) request.put("params", params);
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
