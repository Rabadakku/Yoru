package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import dev.yoru.persistence.PortableVault;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Anki study time as tracked time (AnkiTime, Tracker.addAnkiTime).
 *
 * Invented answers only, laid out on a fixed clock. The property everything
 * here defends is that a minute of Anki is counted once: refreshing again,
 * reading a shorter or longer reach, answers arriving late from another
 * device, and time already clocked in Yoru must never add it a second time.
 */
public final class AnkiTimeTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty(); boolean fail; int saves;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("Disk full"); state = next; saves++; }
        public void close() { }
    }
    /** A clock the test moves by hand. */
    private static final class Hand extends Clock {
        Instant now;
        Hand(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private static final Instant NOON = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant WEEK_AGO = NOON.minus(Duration.ofDays(7));
    private static Instant at(String time) { return Instant.parse("2026-09-09T" + time + ":00Z"); }

    /** count answers, each millis long, one straight after another, the first card shown at shown. */
    private static List<AnkiTime.Review> answers(Instant shown, int count, long millis) {
        var out = new ArrayList<AnkiTime.Review>();
        long t = shown.toEpochMilli();
        for (int i = 0; i < count; i++) { t += millis; out.add(new AnkiTime.Review(t, millis)); }
        return out;
    }
    @SafeVarargs private static List<AnkiTime.Review> all(List<AnkiTime.Review>... parts) {
        var out = new ArrayList<AnkiTime.Review>(); for (var p : parts) out.addAll(p); return out;
    }
    private static List<Session> sittings(State state) { return state.sessions().stream().filter(AnkiTime::isSitting).toList(); }
    private static long total(State state) {
        return Analytics.daily(state, null, ZoneOffset.UTC, NOON).values().stream().mapToLong(Long::longValue).sum();
    }
    private static Activity named(State state, String name) {
        return state.activities().stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
    }

    /** Sittings are split by pauses over ten minutes, and last as long as Anki says. */
    private static void sittingsFollowAnkisOwnTime() throws Exception {
        var repo = new Memory(); var t = new Tracker(repo, new Hand(NOON));
        // 09:00 — ten minutes, a three-minute pause, five more: fifteen minutes studied over eighteen.
        // 10:00 — fifteen minutes, after a gap of forty-two.
        var reviews = all(answers(at("09:00"), 60, 10_000), answers(at("09:13"), 30, 10_000), answers(at("10:00"), 45, 20_000));
        var added = t.addAnkiTime(reviews, WEEK_AGO);
        check(added.size() == 2, "two sittings, split at the long gap and not at the short pause");
        check(added.get(0).start().equals(at("09:00")) && added.get(0).seconds(added.get(0).end()) == 900,
            "a sitting starts with its first card and lasts as long as its answers, not its pauses");
        check(added.get(1).start().equals(at("10:00")) && added.get(1).seconds(added.get(1).end()) == 900, "the second sitting too");
        check(added.stream().allMatch(AnkiTime::isSitting), "both are recognisably Anki sittings");
        var anki = named(t.state(), AnkiTime.ACTIVITY);
        check(anki != null && added.stream().allMatch(s -> s.activityId().equals(anki.id())), "under an activity called Anki, made on first use");
        check(total(t.state()) == 1800, "thirty minutes of tracked time");
        check(AnkiTime.recordedOn(t.state(), LocalDate.of(2026, 9, 9), ZoneOffset.UTC, NOON) == 1800, "and is reported as recorded");
        check(AnkiTime.studiedOn(reviews, LocalDate.of(2026, 9, 9), ZoneOffset.UTC) == 1800, "the same as Anki's own figure");
        int saves = repo.saves;
        check(t.addAnkiTime(reviews, WEEK_AGO).isEmpty(), "refreshing again adds nothing");
        check(t.addAnkiTime(reviews, NOON.minus(Duration.ofDays(1))).isEmpty(), "nor does a shorter reach");
        check(repo.saves == saves, "and writes nothing");
        check(t.state().activities().size() == 1 && sittings(t.state()).size() == 2, "no second activity, no third session");
    }

    /** A sitting is written once, when it has finished, and never grown afterwards. */
    private static void stillGoingIsLeftOutUntilFinished() throws Exception {
        var clock = new Hand(NOON); var t = new Tracker(new Memory(), clock);
        var reviews = answers(at("11:40"), 90, 10_000);   // until 11:55
        check(t.addAnkiTime(reviews, WEEK_AGO).isEmpty(), "five minutes since the last answer: still going");
        clock.now = at("12:05").plusSeconds(1);
        var added = t.addAnkiTime(reviews, WEEK_AGO);
        check(added.size() == 1 && added.getFirst().seconds(added.getFirst().end()) == 900, "ten minutes on, it is added");
    }

    /** An edited sitting stays edited; a renamed activity keeps receiving them. */
    private static void editsAndRenamesAreRespected() throws Exception {
        var t = new Tracker(new Memory(), new Hand(NOON));
        var first = answers(at("09:00"), 60, 10_000);
        var sitting = t.addAnkiTime(first, WEEK_AGO).getFirst();
        t.editSession(sitting.id(), sitting.activityId(), sitting.start(), sitting.start().plus(Duration.ofMinutes(6)));
        check(t.addAnkiTime(first, WEEK_AGO).isEmpty(), "a shortened sitting is not put back");
        // Moved clear of the answers it came from, only its id says it was recorded.
        t.editSession(sitting.id(), sitting.activityId(), at("07:00"), at("07:06"));
        check(t.addAnkiTime(first, WEEK_AGO).isEmpty(), "a moved sitting is not put back where it was");
        t.renameActivity(sitting.activityId(), "Flashcards");
        var later = t.addAnkiTime(all(first, answers(at("10:00"), 60, 10_000)), WEEK_AGO);
        check(later.size() == 1 && later.getFirst().activityId().equals(sitting.activityId()), "the next one follows the renamed activity");
        check(named(t.state(), AnkiTime.ACTIVITY) == null, "rather than making a new Anki");
        check(total(t.state()) == 360 + 600, "the edit is what counts");

        var existing = new Tracker(new Memory(), new Hand(NOON));
        existing.addActivity("anki", 0);
        var id = existing.state().activities().getFirst().id();
        var added = existing.addAnkiTime(first, WEEK_AGO);
        check(added.getFirst().activityId().equals(id) && existing.state().activities().size() == 1, "an activity already called anki is used");
    }

    /** Time clocked in Yoru while Anki was open is already counted. */
    private static void clockedTimeIsNotCountedTwice() throws Exception {
        var clock = new Hand(at("09:00")); var t = new Tracker(new Memory(), clock);
        t.addActivity("Japanese", 0);
        var japanese = t.state().activities().getFirst().id();
        clock.now = NOON;
        t.log(japanese, at("09:00"), at("09:30"));
        var inside = answers(at("09:10"), 60, 10_000);        // 09:10–09:20, inside the clocked session
        var across = answers(at("09:25"), 60, 15_000);        // 09:25–09:40, across its end
        var after = answers(at("10:00"), 60, 10_000);         // 10:00–10:10, clear of it
        var added = t.addAnkiTime(all(inside, across, after), WEEK_AGO);
        check(added.size() == 1 && added.getFirst().start().equals(at("10:00")), "only the sitting clear of clocked time is added");
        check(total(t.state()) == 1800 + 600, "the clocked half hour, and ten Anki minutes after it");

        clock.now = at("11:00");
        t.start(japanese);
        clock.now = NOON;
        var before = answers(at("10:30"), 60, 10_000);        // 10:30–10:40, before the timer started
        var during = answers(at("11:05"), 60, 10_000);        // 11:05–11:15, while it runs
        added = t.addAnkiTime(all(inside, across, after, before, during), WEEK_AGO);
        check(added.size() == 1 && added.getFirst().start().equals(at("10:30")), "time under a running timer is left out");
    }

    /** A sitting under the vault's minimum is left out, as a clocked one would be. */
    private static void shortSittingsFollowTheFloor() throws Exception {
        var t = new Tracker(new Memory(), new Hand(NOON));
        var brief = answers(at("09:00"), 12, 10_000);   // two minutes
        check(t.addAnkiTime(brief, WEEK_AGO).isEmpty(), "two minutes is under the default five");
        t.settings(new Settings(ThemeId.MIDNIGHT, 4, 60));
        check(t.addAnkiTime(brief, WEEK_AGO).size() == 1, "and counts once the minimum is a minute");
    }

    /**
     * Answers before the reach may be missing, so a sitting that could have
     * begun with one is not recorded under the wrong id.
     */
    private static void theReachIsRespected() throws Exception {
        var t = new Tracker(new Memory(), new Hand(NOON));
        var reach = at("09:00");
        check(t.addAnkiTime(answers(reach.plus(Duration.ofMinutes(5)), 60, 10_000), reach).isEmpty(),
            "a sitting starting within ten minutes of the reach is left for a longer one");
        check(t.addAnkiTime(answers(reach.plus(Duration.ofMinutes(11)), 60, 10_000), reach).size() == 1,
            "one starting later is complete");

        // A sitting with a nine-minute pause, read first whole and then from inside the pause.
        var other = new Tracker(new Memory(), new Hand(NOON));
        var paused = all(answers(at("10:00"), 30, 10_000), answers(at("10:14"), 36, 10_000));   // 10:00–10:05, 10:14–10:20
        check(other.addAnkiTime(paused, WEEK_AGO).size() == 1, "one sitting across the pause");
        var tail = paused.stream().filter(r -> r.id() >= at("10:06").toEpochMilli()).toList();
        check(other.addAnkiTime(tail, at("10:06")).isEmpty(), "its second half is not a sitting of its own");
        check(total(other.state()) == 660, "eleven minutes, once");
    }

    /** Answers synced late from another device cannot add a sitting's time again. */
    private static void lateAnswersAreNotCountedTwice() throws Exception {
        var t = new Tracker(new Memory(), new Hand(NOON));
        var desk = answers(at("09:00"), 60, 10_000);   // 09:00–09:10
        check(t.addAnkiTime(desk, WEEK_AGO).size() == 1, "the desk sitting is added");
        var earlier = answers(at("08:55"), 20, 10_000);   // 08:55–08:58:20, joins it at the front
        var later = answers(at("09:12"), 12, 10_000);     // 09:12–09:14, joins it at the back
        check(t.addAnkiTime(all(earlier, desk, later), WEEK_AGO).isEmpty(), "the recorded sitting is not added again, larger");
        check(total(t.state()) == 600, "ten minutes, still");
    }

    /** A failed write changes nothing, and the next refresh tries again. */
    private static void aFailedWriteChangesNothing() throws Exception {
        var repo = new Memory(); var t = new Tracker(repo, new Hand(NOON));
        var reviews = answers(at("09:00"), 60, 10_000);
        repo.fail = true;
        try { t.addAnkiTime(reviews, WEEK_AGO); throw new AssertionError("the write failed"); }
        catch (IOException expected) { checks++; }
        check(t.state().activities().isEmpty() && t.state().sessions().isEmpty(), "no activity and no session");
        repo.fail = false;
        check(t.addAnkiTime(reviews, WEEK_AGO).size() == 1, "added on the next try");
    }

    /** Sittings stay recognisable through the vault and an export. */
    private static void sittingsSurviveTheVault() throws Exception {
        Path dir = Files.createTempDirectory("yoru-anki-"), file = dir.resolve("test.vault");
        String password = "test-only-password-789";
        var reviews = answers(at("09:00"), 60, 10_000);
        try {
            try (var vault = new EncryptedVault(file, password.toCharArray())) {
                var t = new Tracker(vault, new Hand(NOON));
                t.addActivity("Coding", 0);
                t.log(t.state().activities().getFirst().id(), at("07:00"), at("08:00"));
                check(t.addAnkiTime(reviews, WEEK_AGO).size() == 1, "added");
            }
            try (var vault = new EncryptedVault(file, password.toCharArray())) {
                var t = new Tracker(vault, new Hand(NOON));
                check(sittings(t.state()).size() == 1 && t.state().sessions().size() == 2, "the sitting, and only it, is one after reopening");
                check(t.addAnkiTime(reviews, WEEK_AGO).isEmpty(), "so it is not added again");
                var imported = PortableVault.parse(PortableVault.export(t.state(), NOON));
                check(sittings(imported).equals(sittings(t.state())), "an export keeps it a sitting");
            }
        } finally {
            try (var files = Files.walk(dir)) { for (var p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        var random = new Session(UUID.randomUUID(), UUID.randomUUID(), at("09:00"), at("10:00"));
        check(!AnkiTime.isSitting(random), "a clocked session is not a sitting");
    }

    private static void reviewsAreChecked() {
        for (long[] bad : new long[][]{{0, 1}, {-5, 1}, {1, -1}}) {
            try { new AnkiTime.Review(bad[0], bad[1]); throw new AssertionError("accepted an invalid answer"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        check(new AnkiTime.Review(NOON.toEpochMilli(), Duration.ofHours(5).toMillis()).millis() == Duration.ofHours(1).toMillis(),
            "one answer is never taken to have lasted more than an hour");
        var s = AnkiTime.sittings(all(answers(at("09:00"), 3, 10_000), List.of(new AnkiTime.Review(at("09:01").toEpochMilli(), 0))), WEEK_AGO);
        check(s.size() == 1 && s.getFirst().seconds() == 30, "an answer with no time adds none");
    }

    public static void main(String[] args) throws Exception {
        sittingsFollowAnkisOwnTime();
        stillGoingIsLeftOutUntilFinished();
        editsAndRenamesAreRespected();
        clockedTimeIsNotCountedTwice();
        shortSittingsFollowTheFloor();
        theReachIsRespected();
        lateAnswersAreNotCountedTwice();
        aFailedWriteChangesNothing();
        sittingsSurviveTheVault();
        reviewsAreChecked();
        System.out.println("PASS: " + checks + " Anki time checks (sittings, once only, clocked time, the floor, the reach, late answers, failed writes, the vault)");
    }
}
