package dev.yoru.application;

import dev.yoru.domain.Model.Session;
import dev.yoru.domain.Model.State;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Anki study time, as tracked sessions.
 *
 * Anki logs every answer with the moment it was given and how long the card
 * was up, capped by the deck's maximum answer time. Answers less than
 * {@link #GAP} apart are one sitting, and a finished sitting becomes one
 * session: it starts when the sitting's first card went up and lasts as long
 * as the answer times added together, which is the figure Anki itself reports
 * as time studied. From then on it is tracked time like any other, so it
 * reaches the totals, the heat map, the daily goal and the encounters with no
 * rule of its own.
 *
 * Every refresh works the sittings out again from Anki's log, and three things
 * make that safe to repeat:
 *
 * - A sitting's session id is made from its first answer, so a sitting already
 *   recorded is recognised by id and never added twice, even after its session
 *   was edited.
 * - A sitting is added only once it has finished — nothing answered for
 *   {@link #GAP} — so a session is written once and never grown afterwards.
 * - A sitting that would overlap anything already recorded is left out whole:
 *   a session clocked in Yoru while Anki was open has counted that time
 *   already, and answers synced late from another device can run into a
 *   sitting that was recorded before they arrived. Time is only ever counted
 *   once; the rare case where that counts less is the safe side to err on.
 */
public final class AnkiTime {
    private AnkiTime() { }

    /** How long without an answer ends a sitting. */
    public static final Duration GAP = Duration.ofMinutes(10);
    /** Where Anki time goes when no sitting has been recorded yet. */
    public static final String ACTIVITY = "Anki";
    /**
     * The longest one answer is taken to have lasted. Anki caps it with the
     * deck's own limit; this is the backstop for a log row that claims more.
     */
    static final long LONGEST_ANSWER_MILLIS = Duration.ofHours(1).toMillis();

    /**
     * One answer from Anki's review log.
     *
     * id is the log's own id, which Anki makes from the moment of the answer in
     * epoch milliseconds; millis is how long the card was up before it.
     */
    public record Review(long id, long millis) {
        public Review {
            if (id <= 0 || millis < 0) throw new IllegalArgumentException("Invalid Anki review.");
            millis = Math.min(millis, LONGEST_ANSWER_MILLIS);
        }
        public Instant answered() { return Instant.ofEpochMilli(id); }
        public Instant shown() { return Instant.ofEpochMilli(id - millis); }
    }

    /**
     * Answers less than {@link #GAP} apart, as they will be recorded.
     *
     * start and end are the session; lastAnswer is when the sitting stopped,
     * which is later than end whenever there were pauses between cards.
     */
    public record Sitting(UUID id, Instant start, Instant end, Instant lastAnswer) {
        public long seconds() { return Duration.between(start, end).getSeconds(); }
    }

    // The id layout: "Anki", version 8 (RFC 9562's custom layout) and the
    // variant bits, then the sitting's first answer. A random session id is
    // version 4, so the two can never be mistaken for one another, and a
    // sitting's session is recognisable for as long as it exists.
    private static final long MARK = 0x416E6B69_0000_8000L, VARIANT = 0x8000_0000_0000_0000L;
    private static final long ANSWER_BITS = (1L << 62) - 1;

    /** The session id the sitting that starts with this answer is recorded under. */
    static UUID sittingId(long firstAnswer) { return new UUID(MARK, VARIANT | firstAnswer); }

    /** Whether a session was recorded from an Anki sitting. */
    public static boolean isSitting(Session session) {
        var id = session.id();
        return id.getMostSignificantBits() == MARK && (id.getLeastSignificantBits() & ~ANSWER_BITS) == VARIANT;
    }

    /**
     * The sittings in these answers.
     *
     * complete is the moment the answers are known to be complete from: an
     * answer before it may be missing. A sitting that starts less than
     * {@link #GAP} after it might have begun with an answer that is not here,
     * and would be recorded under the wrong id, so it is left out; it was
     * complete, and so considered, on an earlier refresh with a longer reach.
     * Answers before complete are ignored, as are answers with no time.
     */
    public static List<Sitting> sittings(Collection<Review> reviews, Instant complete) {
        var answers = new TreeMap<Long, Review>();
        long from = complete.toEpochMilli();
        for (var r : reviews) if (r.id() >= from && r.millis() > 0) answers.putIfAbsent(r.id(), r);
        var out = new ArrayList<Sitting>();
        var run = new ArrayList<Review>();
        for (var r : answers.values()) {
            if (!run.isEmpty() && r.shown().isAfter(run.getLast().answered().plus(GAP))) {
                sitting(run, complete).ifPresent(out::add);
                run.clear();
            }
            run.add(r);
        }
        if (!run.isEmpty()) sitting(run, complete).ifPresent(out::add);
        return out;
    }

    private static Optional<Sitting> sitting(List<Review> run, Instant complete) {
        var first = run.getFirst();
        if (first.shown().isBefore(complete.plus(GAP))) return Optional.empty();
        long studied = run.stream().mapToLong(Review::millis).sum();
        var last = run.getLast().answered();
        // Never longer than the sitting itself: answer times from two devices
        // can overlap, and a session must fit inside the stretch it stands for.
        studied = Math.min(studied, Duration.between(first.shown(), last).toMillis());
        if (studied < 1000) return Optional.empty();
        var start = first.shown();
        return Optional.of(new Sitting(sittingId(first.id()), start, start.plusMillis(studied), last));
    }

    /**
     * The sittings to add to the vault now, as sessions under this activity.
     *
     * Left out: a sitting already recorded, one still going, one under the
     * vault's minimum session — it would count for nothing, as a clocked
     * session that short is not kept either — and one whose stretch overlaps a
     * session already recorded, a running one included.
     */
    public static List<Session> toRecord(State state, List<Sitting> sittings, UUID activity, Instant now) {
        var recorded = new HashSet<UUID>();
        for (var s : state.sessions()) recorded.add(s.id());
        var out = new ArrayList<Session>();
        for (var sitting : sittings) {
            if (recorded.contains(sitting.id())) continue;
            if (sitting.lastAnswer().plus(GAP).isAfter(now)) continue;
            if (Analytics.tooShort(state, sitting.start(), sitting.end())) continue;
            // To the instant, not the second: Analytics.overlap rounds down, and
            // the vault refuses an overlap of any size.
            boolean overlaps = false;
            for (var s : state.sessions()) {
                var end = s.end() == null ? now : s.end();
                if (s.start().isBefore(sitting.lastAnswer()) && sitting.start().isBefore(end)) { overlaps = true; break; }
            }
            if (overlaps) continue;
            out.add(new Session(sitting.id(), activity, sitting.start(), sitting.end()));
        }
        return out;
    }

    /**
     * The activity new sittings go under: the one the latest recorded sitting
     * is in, so renaming it or moving Anki time elsewhere carries on; then an
     * activity called {@link #ACTIVITY}; null when there is neither yet.
     */
    public static UUID activity(State state) {
        return state.sessions().stream().filter(AnkiTime::isSitting)
            .max(Comparator.comparing(Session::start)).map(Session::activityId)
            .or(() -> state.activities().stream().filter(a -> a.name().equalsIgnoreCase(ACTIVITY))
                .findFirst().map(a -> a.id()))
            .orElse(null);
    }

    /** Anki's answer time on this date, whether or not it is recorded yet. */
    public static long studiedOn(Collection<Review> reviews, LocalDate date, ZoneId zone) {
        long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        var seen = new HashSet<Long>();
        long millis = 0;
        for (var r : reviews) if (r.id() >= from && r.id() < to && seen.add(r.id())) millis += r.millis();
        return millis / 1000;
    }

    /** Recorded Anki sittings on this date that count toward the totals, in seconds. */
    public static long recordedOn(State state, LocalDate date, ZoneId zone, Instant now) {
        var only = state.withCore(state.activities(),
            state.sessions().stream().filter(AnkiTime::isSitting).toList(), state.blocks());
        return Analytics.daily(only, null, zone, now).getOrDefault(date, 0L);
    }
}
