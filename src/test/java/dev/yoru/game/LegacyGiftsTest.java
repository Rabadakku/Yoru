package dev.yoru.game;

import dev.yoru.domain.Model.Reward;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Gifts written by the signed encoder (#11): which ones are provably untouched
 * since delivery, and which must be left alone. Every legacy record here is built
 * by Gen3RecordOracle in the signed order, never by the code that assesses it.
 */
public final class LegacyGiftsTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    static final Instant BEFORE_FIX = Instant.parse("2026-09-12T09:00:00Z");

    /**
     * The first reward id from {@code start} whose personality does, or does not,
     * have the high bit.
     *
     * Hashed from a name rather than counted. personalityFor folds the UUID's two
     * halves together, so ids that differ only in their low bits keep the same sign
     * bit for about two billion steps: every search from a nearby start then lands
     * on the same id, and four rewards sharing one personality are all ambiguous.
     * A name hash scatters the sign, and no id here can be one Gen3Fixture.member
     * uses for a party fixture.
     */
    static UUID rewardId(boolean highBit, int start) {
        for (int i = start; ; i++) {
            var id = UUID.nameUUIDFromBytes(("legacy-gift-test-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if ((StudyGift.personalityFor(id) < 0) == highBit) return id;
        }
    }

    static Reward delivered(UUID id, int dex, int level) {
        return new Reward(id, dex, level, BEFORE_FIX.minusSeconds(3600), BEFORE_FIX);
    }

    static byte[] trainerSave() {
        return Gen3Fixture.withTrainer(Gen3Fixture.save(2, 0), "TESTER", 0, 12345, 54321);
    }

    /** What the signed encoder wrote for this reward, built by the oracle. */
    static byte[] legacyBox(byte[] save, Reward reward, int flags) {
        var mon = GameDelivery.companionFor(reward, Gen3Save.read(save).trainer());
        return Gen3RecordOracle.box(mon, Gen3RecordOracle.signedOrder(mon.personality), flags);
    }

    static byte[] withBoxed(byte[] save, int box, int slot, byte[] record) {
        var s = Gen3Save.read(save);
        var storage = s.storage();
        System.arraycopy(record, 0, storage, Gen3Save.slotOffset(box, slot), Gen3Pokemon.BOX_SIZE);
        s.storage(storage);
        return s.bytes();
    }

    /** A party record as toParty wrote it before the fix: the legacy box, stats from the signed nature. */
    static byte[] legacyParty(byte[] save, Reward reward) {
        var correctBox = GameDelivery.companionFor(reward, Gen3Save.read(save).trainer()).encode();
        var record = Gen3Pokemon.toParty(correctBox, 0);
        var mon = Gen3Pokemon.decode(correctBox, 0);
        int[] stats = Gen3Pokemon.stats(mon.nationalDex(), mon.levelFromExperience(), mon.ivs(), mon.evs,
            Math.floorMod(mon.personality, 25));
        record[0x56] = (byte) stats[0];
        record[0x57] = (byte) (stats[0] >>> 8);
        for (int i = 0; i < 6; i++) {
            record[0x58 + i * 2] = (byte) stats[i];
            record[0x59 + i * 2] = (byte) (stats[i] >>> 8);
        }
        System.arraycopy(legacyBox(save, reward, 0x02), 0, record, 0, Gen3Pokemon.BOX_SIZE);
        return record;
    }

    static LegacyGifts.Verdict verdictOf(LegacyGifts.Assessment a, Reward r) {
        return a.findings().stream().filter(f -> f.reward().id().equals(r.id())).findFirst().orElseThrow().verdict();
    }

    public static void main(String[] args) {
        var save = trainerSave();
        var old = delivered(rewardId(true, 0), 258, 5);          // high bit: signed and unsigned orders differ
        var low = delivered(rewardId(false, 0), 252, 5);         // low bit: both orders agree
        var changed = delivered(rewardId(true, 1000), 255, 5);
        var gone = delivered(rewardId(true, 2000), 25, 5);
        var pending = new Reward(rewardId(true, 3000), 1, 5, BEFORE_FIX, null);
        var trainer = Gen3Save.read(save).trainer();

        check(!Arrays.equals(legacyBox(save, old, 0x02), GameDelivery.companionFor(old, trainer).encode()),
            "a high-bit gift's legacy record differs from today's");
        check(Arrays.equals(LegacyGifts.signedLayout(GameDelivery.companionFor(old, trainer).encode(), 0x02),
            legacyBox(save, old, 0x02)), "signedLayout reproduces the oracle's signed record byte for byte");

        save = withBoxed(save, 0, 0, legacyBox(save, old, 0x02));
        save = withBoxed(save, 0, 1, GameDelivery.companionFor(low, trainer).encode());
        var editedMon = Gen3Pokemon.decode(GameDelivery.companionFor(changed, trainer).encode(), 0);
        editedMon.experience += 1;                                // the game gave it experience since
        byte[] edited = Gen3RecordOracle.box(editedMon, Gen3RecordOracle.signedOrder(editedMon.personality), 0x02);
        save = withBoxed(save, 3, 7, edited);
        // A record with a high-bit personality that no reward owns, as the game would have made it.
        byte[] wild = GameDelivery.companionFor(delivered(rewardId(true, 4000), 263, 3), trainer).encode();
        save = withBoxed(save, 5, 5, wild);

        var rewards = List.of(old, low, changed, gone, pending);
        var before = save.clone();
        var a = LegacyGifts.assess(save, rewards);
        check(Arrays.equals(before, save), "assessing writes nothing");
        check(verdictOf(a, old) == LegacyGifts.Verdict.REPAIRABLE, "an untouched signed-order gift is repairable");
        check(verdictOf(a, low) == LegacyGifts.Verdict.CORRECT, "a low-bit gift was always written correctly");
        check(verdictOf(a, changed) == LegacyGifts.Verdict.CHANGED, "a gift the game has changed is not repairable");
        check(verdictOf(a, gone) == LegacyGifts.Verdict.NOT_FOUND, "a gift no longer in the save is not found");
        check(a.findings().stream().noneMatch(f -> f.reward().id().equals(pending.id())), "an undelivered reward is not assessed");
        check(a.repairable().size() == 1 && a.repairable().getFirst().where().getFirst().box() == 0
            && a.repairable().getFirst().where().getFirst().slot() == 0, "and the repairable one is located exactly");
        check(a.leftAlone().size() == 1 && a.leftAlone().getFirst().reward().id().equals(changed.id()),
            "the changed high-bit gift is reported as left alone");

        // The flag byte from the first delivery build is recognised too.
        var zeroFlag = withBoxed(trainerSave(), 1, 1, legacyBox(trainerSave(), old, 0x00));
        check(verdictOf(LegacyGifts.assess(zeroFlag, List.of(old)), old) == LegacyGifts.Verdict.REPAIRABLE,
            "a gift written with flag byte 0x00 is recognised as legacy");

        // Two records with the same personality: never guess which is the gift.
        var twice = withBoxed(withBoxed(trainerSave(), 0, 0, legacyBox(trainerSave(), old, 0x02)), 0, 1,
            legacyBox(trainerSave(), old, 0x02));
        check(verdictOf(LegacyGifts.assess(twice, List.of(old)), old) == LegacyGifts.Verdict.AMBIGUOUS,
            "a duplicated marker is ambiguous");

        // A legacy gift in the party, with its tail as toParty wrote it then.
        var partySave = trainerSave();
        partySave = Gen3Fixture.withParty(partySave, List.of(Gen3Fixture.member(partySave, 252, 5, 1), legacyParty(partySave, old)));
        var inParty = LegacyGifts.assess(partySave, List.of(old));
        check(verdictOf(inParty, old) == LegacyGifts.Verdict.REPAIRABLE && inParty.repairable().getFirst().where().getFirst().inParty(),
            "an untouched legacy party gift is repairable, and found in the party");

        // Delivered after 1.0.3 shipped: correct by construction, never "left alone".
        var late = new Reward(changed.id(), 255, 5, BEFORE_FIX, Instant.parse("2026-09-14T00:00:00Z"));
        check(LegacyGifts.assess(save, List.of(late)).leftAlone().isEmpty(), "a change to a gift delivered after 1.0.3 is not reported");

        // Repair rewrites exactly the provable gifts, as today's delivery would have written them.
        var repaired = LegacyGifts.repair(save, rewards);
        var repairedSave = Gen3Save.read(repaired);
        var after = LegacyGifts.assess(repaired, rewards);
        check(verdictOf(after, old) == LegacyGifts.Verdict.CORRECT, "the repaired gift now assesses as correct");
        var expected = GameDelivery.companionFor(old, repairedSave.trainer());
        int firstSlot = Gen3Save.slotOffset(0, 0);
        byte[] repairedRecord = Arrays.copyOfRange(repairedSave.storage(), firstSlot, firstSlot + Gen3Pokemon.BOX_SIZE);
        check(Arrays.equals(Gen3RecordOracle.box(expected, Gen3RecordOracle.unsignedOrder(expected.personality), 0x02), repairedRecord),
            "and the oracle, in the game's own order, agrees with it byte for byte");
        var fixed = Gen3Pokemon.decode(repairedRecord, 0);
        check(fixed.nationalDex() == 258 && fixed.personality == StudyGift.personalityFor(old.id()),
            "it is the Pokémon the reward promised, with its identity kept");
        check(verdictOf(after, changed) == LegacyGifts.Verdict.CHANGED, "the changed gift is still left alone");
        int changedSlot = Gen3Save.slotOffset(3, 7), wildSlot = Gen3Save.slotOffset(5, 5);
        check(Arrays.equals(Arrays.copyOfRange(repairedSave.storage(), changedSlot, changedSlot + Gen3Pokemon.BOX_SIZE), edited),
            "byte for byte");
        check(Arrays.equals(Arrays.copyOfRange(repairedSave.storage(), wildSlot, wildSlot + Gen3Pokemon.BOX_SIZE), wild),
            "and a record no reward owns is untouched");
        check(Arrays.equals(LegacyGifts.repair(repaired, rewards), repaired), "a second run changes nothing");

        var partyFixed = Gen3Save.read(LegacyGifts.repair(partySave, List.of(old)));
        check(Arrays.equals(partyFixed.partyRecord(1),
            Gen3Pokemon.toParty(GameDelivery.companionFor(old, partyFixed.trainer()).encode(), 0)),
            "a repaired party gift carries the stats its real nature gives");
        check(Arrays.equals(partyFixed.partyRecord(0), Gen3Save.read(partySave).partyRecord(0)),
            "and its party neighbour is untouched");

        // Refusals: a save the game might not load a change into, and a result that changed something else.
        try {
            LegacyGifts.repair(Gen3Fixture.interrupted(save), rewards);
            check(false, "an interrupted save is refused");
        } catch (IllegalStateException expectedRefusal) { }
        var tampered = withBoxed(repaired, 9, 9, wild);
        try {
            LegacyGifts.verify(save, tampered, rewards);
            check(false, "a result that changed another slot is refused");
        } catch (IllegalStateException expectedRefusal) { }

        // A full PC does not matter: a repair never needs a free slot.
        var full = Gen3Save.read(save);
        var fullStorage = full.storage();
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                int at = Gen3Save.slotOffset(box, slot);
                if (Gen3Save.empty(fullStorage, at)) System.arraycopy(wild, 0, fullStorage, at, Gen3Pokemon.BOX_SIZE);
            }
        full.storage(fullStorage);
        var fullSave = full.bytes();
        check(verdictOf(LegacyGifts.assess(LegacyGifts.repair(fullSave, List.of(old)), List.of(old)), old)
            == LegacyGifts.Verdict.CORRECT, "a gift in a full PC is repaired in place");

        System.out.println("PASS: " + checks + " legacy gift checks (assessment, in-place repair, party stats, refusals, full PC, idempotence)");
    }
}
