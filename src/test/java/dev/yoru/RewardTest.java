package dev.yoru;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import dev.yoru.persistence.PortableVault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Rewards earned for the real game, and where they are kept (#29).
 *
 * A reward is study credit that has not yet reached the game, or a record that
 * it has. Losing one is losing time the player spent studying, so the thing
 * most worth proving is that nothing else in the app can make one disappear.
 * They live on State rather than inside the collection for exactly that
 * reason: the collection is rebuilt from its parts by more than a dozen
 * operations, and each would otherwise have to remember to copy them across.
 */
public final class RewardTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static final String PASSWORD = "reward-test-password";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);

    /** A repository that keeps state in memory. */
    private static final class Memory implements Repository {
        State state = State.empty();
        @Override public State load() { return state; }
        @Override public void save(State next) { state = next; }
        @Override public void close() { }
    }

    /** Banking and spending, including the retry that delivery depends on. */
    private static void bankingAndSpending() throws IOException {
        var tracker = new Tracker(new Memory(), CLOCK);
        var id = UUID.randomUUID();
        var reward = tracker.bankReward(id, 252, 5);
        check(reward.earnedAt().equals(CLOCK.instant()), "a reward records when it was earned");
        check(!reward.delivered(), "and starts unspent");
        check(tracker.state().pendingRewards().equals(List.of(reward)), "and is waiting to be delivered");

        boolean refused = false;
        try { tracker.bankReward(id, 252, 5); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "the same reward cannot be banked twice");
        check(tracker.state().rewards().size() == 1, "and a refused attempt leaves one reward, not two");

        var first = Instant.parse("2026-09-11T13:00:00Z");
        tracker.rewardDelivered(id, first);
        check(tracker.state().rewards().get(0).deliveredAt().equals(first), "delivery records the time");
        check(tracker.state().pendingRewards().isEmpty(), "and the reward is no longer waiting");

        // A retry that finds the companion already in the save calls this again.
        tracker.rewardDelivered(id, Instant.parse("2026-09-11T14:00:00Z"));
        check(tracker.state().rewards().get(0).deliveredAt().equals(first),
            "marking it again is harmless and keeps the original time");

        refused = false;
        try { tracker.rewardDelivered(UUID.randomUUID(), first); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "an unknown reward cannot be marked delivered");
    }

    /** Nothing that touches the game save can take a reward with it. */
    private static void gameOperationsKeepRewards() throws IOException {
        var tracker = new Tracker(new Memory(), CLOCK);
        var pending = tracker.bankReward(UUID.randomUUID(), 258, 5);
        var spent = tracker.bankReward(UUID.randomUUID(), 255, 5);
        tracker.rewardDelivered(spent.id(), CLOCK.instant());

        var save = dev.yoru.game.Gen3Fixture.save(2, 4);
        tracker.gameSaved(save);
        check(tracker.state().game() != null, "a save can be kept in the vault");
        var kept = tracker.state().rewards();
        check(kept.size() == 2 && kept.stream().anyMatch(r -> r.id().equals(pending.id()) && !r.delivered())
            && kept.stream().anyMatch(r -> r.id().equals(spent.id()) && r.delivered()),
            "keeping a save leaves both rewards exactly as they were");

        // A delivery that changes nothing still changes no reward.
        tracker.recordDelivery(save, save, List.of());
        check(tracker.state().rewards().equals(kept), "an unchanged delivery marks nothing");
        check(tracker.state().pendingRewards().size() == 1, "the pending one is still waiting");

        // Replacing the save backs the vault up but never touches the ledger.
        tracker.replaceGameSave(dev.yoru.game.Gen3Fixture.save(4, 4));
        check(tracker.state().rewards().equals(kept), "replacing the save leaves the rewards");
    }

    /**
     * A reset keeps every reward, even a reset of everything.
     *
     * This is a policy, and it is written down here so it cannot drift. A
     * delivered reward stands for a Pokémon the game already holds — deleting
     * the record would not remove it, only Yoru's knowledge of it. A pending one
     * is study credit already earned. Resetting study history or the collection
     * therefore leaves both alone, so a reset can never damage the campaign or
     * quietly take back time that was spent studying.
     */
    private static void resetsKeepRewards() throws IOException {
        var tracker = new Tracker(new Memory(), CLOCK);
        var pending = tracker.bankReward(UUID.randomUUID(), 258, 5);
        var spent = tracker.bankReward(UUID.randomUUID(), 255, 5);
        tracker.rewardDelivered(spent.id(), CLOCK.instant());
        tracker.gameSaved(dev.yoru.game.Gen3Fixture.save(2, 4));
        tracker.reset(java.util.EnumSet.allOf(Tracker.ResetPart.class));
        var kept = tracker.state().rewards();
        check(kept.size() == 2, "a full reset keeps both rewards, got " + kept.size());
        check(kept.stream().anyMatch(r -> r.id().equals(pending.id()) && !r.delivered())
            && kept.stream().anyMatch(r -> r.id().equals(spent.id()) && r.delivered()),
            "each exactly as it was");
    }

    /** The portable export carries rewards, and an export from before them imports with none. */
    private static void theExportCarriesThem() {
        var pending = new Reward(UUID.randomUUID(), 252, 5, Instant.parse("2026-09-11T08:00:00Z"), null);
        var spent = new Reward(UUID.randomUUID(), 258, 7, Instant.parse("2026-09-11T08:30:00Z"),
            Instant.parse("2026-09-11T09:00:00Z"));
        var state = State.empty().withRewards(List.of(pending, spent));
        String text = PortableVault.export(state, Instant.parse("2026-09-11T12:00:00Z"));
        check(PortableVault.parse(text).rewards().equals(List.of(pending, spent)),
            "both rewards survive an export and import exactly");

        String older = text.replaceFirst("(?s),\\s*\"rewards\"\\s*:\\s*\\[.*?\\]", "");
        check(!older.contains("\"rewards\""), "the older-shaped export really has no rewards key");
        check(PortableVault.parse(older).rewards().isEmpty(), "and imports cleanly with none");
    }

    /** Rewards survive the encrypted vault, and a vault from before them opens with none. */
    private static void theVaultKeepsThem() throws IOException {
        var dir = Files.createTempDirectory("yoru-rewards");
        try {
            Path file = dir.resolve("rewards.vault");
            var pending = new Reward(UUID.randomUUID(), 252, 5, Instant.parse("2026-09-11T08:00:00Z"), null);
            var spent = new Reward(UUID.randomUUID(), 258, 7, Instant.parse("2026-09-11T08:30:00Z"),
                Instant.parse("2026-09-11T09:00:00Z"));
            var state = State.empty().withRewards(List.of(pending, spent));
            // The vault zeroes the password it is given, so each open needs its own copy.
            try (var vault = new EncryptedVault(file, PASSWORD.toCharArray())) { vault.save(state); }
            State back;
            try (var vault = new EncryptedVault(file, PASSWORD.toCharArray())) { back = vault.load(); }
            check(back.rewards().equals(List.of(pending, spent)), "both rewards come back exactly, got " + back.rewards());
            check(back.pendingRewards().equals(List.of(pending)), "with the same one still pending");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            }
        }
    }

    public static void main(String[] args) throws IOException {
        bankingAndSpending();
        gameOperationsKeepRewards();
        resetsKeepRewards();
        theExportCarriesThem();
        theVaultKeepsThem();
        System.out.println("PASS: " + checks + " reward checks (banking, idempotent spending, game operations, vault)");
    }
}
