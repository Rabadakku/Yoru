package dev.yoru.game;

import dev.yoru.application.GameSync;
import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.GameSave;
import dev.yoru.domain.Model.Reward;
import dev.yoru.domain.Model.State;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivering study-earned Pokémon into a real save (#43).
 *
 * The property that matters is that no failure can produce two Pokémon or
 * none. GameDelivery.plan decides; Tracker.recordDelivery writes the new save
 * and the reward ledger in one vault write, so there is no file-rename window
 * left to fail in. This proves the one-write property by failing the vault
 * write and counting, and exercises every refusal plan() can produce, directly.
 *
 * Saves are built synthetically. A real one is somebody's progress and does not
 * belong in a repository.
 */
public final class GameDeliveryTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final Instant EARNED = Instant.parse("2026-09-11T09:00:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-09-11T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(DELIVERED_AT, ZoneOffset.UTC);

    private static Reward reward(String id, int national) { return new Reward(UUID.fromString(id), national, 5, EARNED, null); }
    private static Reward treecko() { return reward("0a0b0c0d-1111-2222-3333-444455556666", 252); }
    private static Reward mudkip() { return reward("0a0b0c0d-1111-2222-3333-444455556667", 258); }
    private static Reward pikachu() { return reward("0a0b0c0d-1111-2222-3333-444455556668", 25); }

    /** The tester's save with {@code members} Pokémon in the party, the first being the starter. */
    private static byte[] saveWith(int members) {
        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 4), "TESTER", 0, 12345, 54321);
        var party = new ArrayList<byte[]>();
        for (int i = 0; i < members; i++) party.add(Gen3Fixture.member(raw, 255, 5, i + 1));
        return Gen3Fixture.withParty(raw, party);
    }

    /** A vault that keeps state in memory and counts writes, so "one write" can be asserted. */
    private static final class Vault implements Repository {
        State state = State.empty();
        int saves;
        boolean fail;
        public State load() { return state; }
        public void save(State next) throws IOException {
            if (fail) throw new IOException("vault write failed");
            saves++; state = next;
        }
        public void close() { }
    }

    /** A tracker whose vault already holds {@code saveBytes} (or none) and the rewards waiting for delivery. */
    private static Tracker trackerWith(Vault repo, byte[] saveBytes, List<Reward> rewards) throws IOException {
        var state = State.empty().withRewards(rewards);
        if (saveBytes != null) state = state.withGame(new GameSave(saveBytes, EARNED));
        repo.state = state;
        return new Tracker(repo, CLOCK);
    }

    /** How many Pokémon anywhere in the save carry this reward's marker. */
    private static int copies(byte[] saveBytes, Reward reward) {
        var save = Gen3Save.read(saveBytes);
        var storage = save.storage();
        int wanted = StudyGift.personalityFor(reward.id()), count = 0;
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                var mon = save.boxed(storage, box, slot);
                if (mon != null && mon.personality == wanted) count++;
            }
        for (var mon : save.party()) if (mon.personality == wanted) count++;
        return count;
    }

    /** The ordinary case: a starter in the party, and the study Pokémon joins it — in one vault write. */
    private static void joinsThePartyFirst() throws IOException {
        var repo = new Vault();
        byte[] original = saveWith(1);
        var tracker = trackerWith(repo, original, List.of(treecko()));
        var outcome = GameSync.deliver(tracker).getFirst();

        check(outcome.kind() == GameDelivery.Kind.DELIVERED, "delivered, got " + outcome.kind() + ": " + outcome.message());
        check(outcome.place().party() && outcome.place().slot() == 1, "into the party, beside the starter");
        check(repo.saves == 1, "the save and the ledger were committed in one write, got " + repo.saves);

        byte[] after = tracker.state().game().bytes();
        var save = Gen3Save.read(after);
        check(save.partyCount() == 2, "the party now holds two");
        var member = save.party().get(1);
        check(member.personality == StudyGift.personalityFor(treecko().id()) && member.nationalDex() == 252,
            "the second member is this reward's Treecko");
        check(member.level == 5 && member.maxHp > 0 && member.currentHp == member.maxHp, "at level 5, at full health");
        check(Arrays.equals(save.partyRecord(0), Gen3Save.read(original).partyRecord(0)), "the starter is untouched");
        check(save.owned(252) && save.seen(252), "the Pokédex records it as caught");
        check(copies(after, treecko()) == 1, "exactly one copy exists");
        check(tracker.state().rewards().getFirst().deliveredAt().equals(DELIVERED_AT), "and the reward is recorded as delivered");
        check(tracker.state().pendingRewards().isEmpty(), "with nothing left waiting");
    }

    /** With a full party, the PC — from the box it last had open, as the game sends them. */
    private static void thenThePcFromTheOpenBox() throws IOException {
        var save = Gen3Save.read(saveWith(6));
        var storage = save.storage();
        storage[0] = 2;
        save.storage(storage);
        var repo = new Vault();
        var tracker = trackerWith(repo, save.bytes(), List.of(treecko()));
        var outcome = GameSync.deliver(tracker).getFirst();
        check(outcome.kind() == GameDelivery.Kind.DELIVERED, "delivered, got " + outcome.kind());
        check(!outcome.place().party() && outcome.place().box() == 2 && outcome.place().slot() == 0,
            "into box 3, which was open, got " + outcome.place());
        var after = Gen3Save.read(tracker.state().game().bytes());
        check(after.partyCount() == 6, "the party is left at six");
        check(after.boxed(after.storage(), 2, 0).personality == StudyGift.personalityFor(treecko().id()),
            "and the slot holds this reward's Pokémon");
    }

    /** Several rewards go in one write: the party fills, then the PC. */
    private static void severalInOneWrite() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, saveWith(4), List.of(treecko(), mudkip(), pikachu()));
        var outcomes = GameSync.deliver(tracker);
        check(outcomes.stream().allMatch(o -> o.kind() == GameDelivery.Kind.DELIVERED), "all three delivered, got " + outcomes);
        check(outcomes.get(0).place().party() && outcomes.get(1).place().party() && !outcomes.get(2).place().party(),
            "two fill the party and the third goes to the PC");
        check(repo.saves == 1, "all three land in one vault write, got " + repo.saves);
        byte[] after = tracker.state().game().bytes();
        for (var r : List.of(treecko(), mudkip(), pikachu())) check(copies(after, r) == 1, "one copy of each");
        check(tracker.state().pendingRewards().isEmpty(), "and all three rewards are recorded");
    }

    /** Before the starter, nothing is sent: the opening scenes expect the party to hold only it. */
    private static void waitsForTheStarter() throws IOException {
        var repo = new Vault();
        byte[] original = saveWith(0);
        var tracker = trackerWith(repo, original, List.of(treecko()));
        var outcome = GameSync.deliver(tracker).getFirst();
        check(outcome.kind() == GameDelivery.Kind.WAITING, "waiting, got " + outcome.kind());
        check(!outcome.spent(), "unspent");
        check(repo.saves == 0, "nothing was committed");
        check(tracker.state().game().holds(original), "and the save untouched");
        check(tracker.state().pendingRewards().size() == 1, "the reward still waits");
    }

    /** With no save at all, nothing is delivered and nothing is written. */
    private static void withNoSaveNothingHappens() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, null, List.of(treecko()));
        var outcome = GameSync.deliver(tracker).getFirst();
        check(outcome.kind() == GameDelivery.Kind.NO_SAVE, "no save is reported, got " + outcome.kind());
        check(!outcome.spent() && repo.saves == 0 && tracker.state().game() == null,
            "and nothing is written or spent");
    }

    /** A damaged save, or one the game might load differently: reported, never touched. */
    private static void refusesWhatItCannotSafelyChange() {
        byte[] broken = saveWith(1);
        broken[Gen3Fixture.offsetOf(broken, 2) + 64] ^= 0x01;
        var plan = GameDelivery.plan(broken, List.of(treecko()));
        check(plan.outcomes().getFirst().kind() == GameDelivery.Kind.UNREADABLE,
            "a damaged save is refused, got " + plan.outcomes().getFirst().kind());
        check(Arrays.equals(plan.save(), broken), "and left exactly as it was");

        byte[] interrupted = saveWith(1);
        Gen3Save.putU32(interrupted, 5 * Gen3Save.SECTION + Gen3Save.COUNTER_AT, 1);
        var refused = GameDelivery.plan(interrupted, List.of(treecko()));
        check(refused.outcomes().getFirst().kind() == GameDelivery.Kind.NOT_EDITABLE,
            "an interrupted save is refused, got " + refused.outcomes().getFirst().kind());
        check(Arrays.equals(refused.save(), interrupted), "and left exactly as it was");
        check(!refused.outcomes().getFirst().spent(), "with nothing spent");
    }

    /** A full PC keeps the reward unspent; an occupied slot is never overwritten. */
    private static void neverOverwritesAndReportsFull() {
        var save = Gen3Save.read(saveWith(6));
        var storage = save.storage();
        var first = StudyGift.build(UUID.randomUUID(), 25, 9, save.trainer(), null, 0).encode();
        System.arraycopy(first, 0, storage, Gen3Save.slotOffset(0, 0), Gen3Pokemon.BOX_SIZE);
        save.storage(storage);
        byte[] withOneBoxed = save.bytes();

        var plan = GameDelivery.plan(withOneBoxed, List.of(treecko()));
        check(plan.outcomes().getFirst().kind() == GameDelivery.Kind.DELIVERED && plan.outcomes().getFirst().place().slot() == 1,
            "an occupied first slot pushes delivery to the next, got " + plan.outcomes().getFirst().place());
        var reread = Gen3Save.read(plan.save());
        check(Arrays.equals(reread.storage(), Gen3Save.slotOffset(0, 0), Gen3Save.slotOffset(0, 0) + 80, first, 0, 80),
            "and the Pokémon already there is untouched");

        var full = Gen3Save.read(plan.save());
        var packed = full.storage();
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) packed[Gen3Save.slotOffset(box, slot) + 8] |= 1;
        full.storage(packed);
        byte[] fullBytes = full.bytes();
        var refused = GameDelivery.plan(fullBytes, List.of(mudkip()));
        check(refused.outcomes().getFirst().kind() == GameDelivery.Kind.FULL,
            "a full party and PC is reported, got " + refused.outcomes().getFirst().kind());
        check(!refused.outcomes().getFirst().spent(), "and the reward stays unspent");
        check(Arrays.equals(refused.save(), fullBytes), "with the save untouched");
    }

    /**
     * The whole delivery is one vault write, so the only failure left to worry
     * about is that write. Fail it, retry, and count: the save and the ledger
     * both stay as they were, and the retry delivers exactly once.
     */
    private static void aFailedVaultWriteIsRecovered() throws IOException {
        var repo = new Vault();
        byte[] original = saveWith(1);
        var tracker = trackerWith(repo, original, List.of(treecko()));
        repo.fail = true;
        boolean refused = false;
        try { GameSync.deliver(tracker); } catch (IOException e) { refused = true; }
        check(refused, "a failed vault write is reported");
        check(tracker.state().game().holds(original), "the save is unchanged");
        check(tracker.state().pendingRewards().size() == 1, "the reward is still waiting");
        check(repo.saves == 0, "and nothing was committed");

        repo.fail = false;
        var retry = GameSync.deliver(tracker).getFirst();
        check(retry.kind() == GameDelivery.Kind.DELIVERED, "the retry delivers, got " + retry.kind());
        check(repo.saves == 1, "in exactly one write");
        check(copies(tracker.state().game().bytes(), treecko()) == 1, "and exactly one Pokémon exists");
        check(tracker.state().pendingRewards().isEmpty(), "with the reward recorded");
    }

    /** A delivered Pokémon is recognised wherever the player has since put it. */
    private static void foundWhereverThePlayerPutIt() {
        var raw = saveWith(1);
        var trainer = Gen3Save.read(raw).trainer();
        var record = GameDelivery.companionFor(treecko(), trainer).encode();
        var inPartyBytes = Gen3Fixture.withParty(raw,
            List.of(Gen3Save.read(raw).partyRecord(0), Gen3Pokemon.toParty(record, 0)));
        var inParty = GameDelivery.plan(inPartyBytes, List.of(treecko()));
        check(inParty.outcomes().getFirst().kind() == GameDelivery.Kind.ALREADY_THERE
            && inParty.outcomes().getFirst().message().contains("party"),
            "found in the party, got " + inParty.outcomes().getFirst().kind() + ": " + inParty.outcomes().getFirst().message());
        check(inParty.spent().equals(List.of(treecko().id())), "and counts as spent without a second copy");
        check(copies(inPartyBytes, treecko()) == 1, "without a second copy");

        var boxed = Gen3Save.read(saveWith(1));
        var storage = boxed.storage();
        System.arraycopy(record, 0, storage, Gen3Save.slotOffset(11, 27), Gen3Pokemon.BOX_SIZE);
        boxed.storage(storage);
        var inBox = GameDelivery.plan(boxed.bytes(), List.of(treecko())).outcomes().getFirst();
        check(inBox.kind() == GameDelivery.Kind.ALREADY_THERE && !inBox.place().party() && inBox.place().box() == 11,
            "and in box 12 after the player moved it there, got " + inBox.kind());
    }

    private static void aSpentRewardIsLeftAlone() {
        byte[] original = saveWith(1);
        var plan = GameDelivery.plan(original, List.of(treecko().deliveredAt(DELIVERED_AT)));
        check(plan.outcomes().getFirst().kind() == GameDelivery.Kind.ALREADY_SPENT,
            "a spent reward is not delivered, got " + plan.outcomes().getFirst().kind());
        check(plan.spent().isEmpty() && Arrays.equals(plan.save(), original), "and nothing is spent or written");
    }

    /** recordDelivery refuses when the vault's save is no longer the one delivery was planned against. */
    private static void aSaveChangedUnderneathIsRefused() throws IOException {
        var repo = new Vault();
        byte[] original = saveWith(1);
        var tracker = trackerWith(repo, original, List.of(treecko()));
        byte[] other = saveWith(2);
        boolean refused = false;
        try { tracker.recordDelivery(other, other, List.of()); } catch (IllegalStateException e) { refused = true; }
        check(refused, "a delivery planned against another save is refused");
        check(tracker.state().game().holds(original), "and the vault's save is untouched");
        check(repo.saves == 0, "with nothing written");
    }

    /**
     * The check before writing refuses anything but the intended additions.
     *
     * In correct code verify() never fires, so removing it would pass every
     * other test here. Its logic is therefore exercised directly, with each
     * kind of change it must refuse.
     */
    private static void verificationRefusesCollateralChanges() {
        byte[] original = saveWith(1);
        var reward = treecko();
        byte[] record = GameDelivery.companionFor(reward, Gen3Save.read(original).trainer()).encode();
        var placed = Map.of(reward, new GameDelivery.Place(true, -1, 1));
        var records = Map.of(reward, record);

        var honest = Gen3Save.read(original);
        var party = new ArrayList<>(honest.partyRecords());
        party.add(Gen3Pokemon.toParty(record, 0));
        honest.party(party);
        honest.register(252);
        byte[] good = honest.bytes();
        GameDelivery.verify(original, good, placed, records);
        check(true, "the honest change is accepted");

        var pcChanged = Gen3Save.read(good);
        var storage = pcChanged.storage();
        storage[Gen3Save.slotOffset(3, 7) + 10] ^= 1;
        pcChanged.storage(storage);
        refuses(original, pcChanged.bytes(), placed, records, "a byte changed in a PC slot nobody placed anything in");

        var trainerChanged = Gen3Save.read(good);
        trainerChanged.section(0)[0x10] ^= 1;
        refuses(original, trainerChanged.bytes(), placed, records, "a change outside the party, PC and Pokédex");

        var starterChanged = Gen3Save.read(good);
        var members = new ArrayList<>(starterChanged.partyRecords());
        members.getFirst()[0x08] ^= 1;
        starterChanged.party(members);
        refuses(original, starterChanged.bytes(), placed, records, "the starter changed");

        var extraSpecies = Gen3Save.read(good);
        extraSpecies.register(1);
        refuses(original, extraSpecies.bytes(), placed, records, "a species registered that was not delivered");

        var twice = Gen3Save.read(good);
        var doubled = new ArrayList<>(twice.partyRecords());
        doubled.add(Gen3Pokemon.toParty(record, 0));
        twice.party(doubled);
        refuses(original, twice.bytes(), placed, records, "the new member written twice");

        var stranger = Map.of(reward, GameDelivery.companionFor(mudkip(), Gen3Save.read(original).trainer()).encode());
        refuses(original, good, placed, stranger, "a record that is not the one intended");
    }

    private static void refuses(byte[] original, byte[] written, Map<Reward, GameDelivery.Place> placed,
                                Map<Reward, byte[]> records, String why) {
        try { GameDelivery.verify(original, written, placed, records); }
        catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("verify accepted " + why);
    }

    public static void main(String[] args) throws IOException {
        joinsThePartyFirst();
        thenThePcFromTheOpenBox();
        severalInOneWrite();
        waitsForTheStarter();
        withNoSaveNothingHappens();
        refusesWhatItCannotSafelyChange();
        neverOverwritesAndReportsFull();
        aFailedVaultWriteIsRecovered();
        foundWhereverThePlayerPutIt();
        aSpentRewardIsLeftAlone();
        aSaveChangedUnderneathIsRefused();
        verificationRefusesCollateralChanges();
        System.out.println("PASS: " + checks + " delivery checks (party first, PC from the open box, one vault write, every refusal)");
    }
}
