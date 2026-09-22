package dev.yoru;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.VaultStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Switching vaults (#41).
 *
 * The rule is the one the whole ticket is about: a vault change is all or
 * nothing. The vault being moved to is opened and read first, and only then is
 * the vault being left let go — so a vault that will not open (a wrong
 * password, a file that is not there) costs the person nothing, and the app is
 * never left holding no vault at all.
 *
 * Everything here is invented: temporary folders and fixture saves.
 */
public final class VaultSwitchTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final String PASSWORD = "a-test-password";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);

    /** A vault in memory, which can refuse to open and remembers being closed. */
    private static final class Memory implements Repository {
        State state = State.empty();
        boolean refuse;
        boolean closed;
        public State load() throws IOException {
            if (refuse) throw new IOException("that vault will not open");
            return state;
        }
        public void save(State next) { state = next; }
        public void close() { closed = true; }
    }

    /** Invented contents: one activity with one recorded session. */
    private static State invented(String label) throws IOException {
        var tracker = new Tracker(new Memory(), CLOCK);
        tracker.addActivity(label, 30);
        var activity = tracker.state().activities().getFirst().id();
        tracker.log(activity, CLOCK.instant().minusSeconds(1800), CLOCK.instant());
        tracker.addTasks(List.of(new Task(UUID.randomUUID(), activity, null, "Read " + label, "",
            null, TaskStatus.TODO, "test", CLOCK.instant(), 0, null)));
        return tracker.state();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** The tracker moves to the other vault, and the vault it left is closed. */
    private static void movesOnlyOnceTheNextVaultIsInHand() throws IOException {
        var one = new Memory();
        var two = new Memory();
        one.state = invented("Study");
        two.state = invented("Reading");
        var tracker = new Tracker(one, CLOCK);

        tracker.switchTo(two);
        check(tracker.state().equals(two.state), "the tracker is on the vault it moved to");
        check(one.closed, "and the vault it left was closed, giving up its lock");
        check(!two.closed, "the vault it moved to is open");
        check(tracker.state().sessions().size() == 1 && tracker.state().activities().getFirst().name().equals("Reading"),
            "with the other vault's data, nothing carried over");
    }

    /** A vault that will not open changes nothing: the tracker stays where it was. */
    private static void aVaultThatWillNotOpenChangesNothing() throws IOException {
        var one = new Memory();
        var two = new Memory();
        one.state = invented("Study");
        two.refuse = true;
        var tracker = new Tracker(one, CLOCK);

        boolean refused = false;
        try { tracker.switchTo(two); } catch (IOException expected) { refused = true; }
        check(refused, "a vault that will not load is refused");
        check(tracker.state().equals(one.state), "and the tracker is still on the vault it had");
        check(!one.closed, "which is still open");
        check(two.closed, "while the vault that would not open was released, not left locked");
    }

    /** Two real vaults in managed storage: across and back, with nothing lost either way. */
    private static void realVaultsSurviveSwitching(Path dir) throws Exception {
        var store = new VaultStore(dir);
        var study = invented("Study");
        var reading = invented("Reading");
        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(study); }
        try (var vault = store.create("Personal", PASSWORD.toCharArray())) { vault.save(reading); }

        var tracker = new Tracker(store.open("School", PASSWORD.toCharArray()), CLOCK);
        check(tracker.state().equals(study), "the first vault opens with its own data");

        tracker.switchTo(store.open("Personal", PASSWORD.toCharArray()));
        check(tracker.state().equals(reading), "the switch lands on the second vault's data");

        // The first vault's lock was given up by the switch: reopening it would
        // fail outright if the tracker still held it.
        tracker.switchTo(store.open("School", PASSWORD.toCharArray()));
        check(tracker.state().equals(study), "and switching back returns everything that was there");

        // An edit in one vault is never seen in the other.
        try (var other = store.open("Personal", PASSWORD.toCharArray())) {
            check(other.load().equals(reading), "the vault left behind holds exactly what it held");
            check(other.load().sessions().getFirst().seconds(null) == 1800,
                "including the time recorded in it");
        }
    }

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("yoru-switch-");
        try {
            movesOnlyOnceTheNextVaultIsInHand();
            aVaultThatWillNotOpenChangesNothing();
            realVaultsSurviveSwitching(root.resolve("real"));
        } finally {
            deleteTree(root);
        }
        System.out.println("PASS: " + checks + " vault switch checks (all or nothing, locks released, no loss)");
    }
}
