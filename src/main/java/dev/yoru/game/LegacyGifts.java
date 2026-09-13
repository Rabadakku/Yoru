package dev.yoru.game;

import dev.yoru.domain.Model.Reward;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Study gifts an older Yoru wrote in the wrong substructure order (#11).
 *
 * Before 1.0.3 the encoder chose the order with floor modulo on a signed int, so
 * every gift whose personality has its high bit set went into the game shuffled
 * wrong: the checksum still passed, and the game read another Pokémon's worth of
 * fields. Correcting the reader cannot fix those bytes, and rewriting every
 * high-bit record would damage genuine ones the game made.
 *
 * So a record counts as a legacy gift only when it is provably the gift: it
 * carries the reward's personality, it is the only record that does, and all of
 * its bytes equal what the signed encoder produced from today's builder — which
 * has not changed what it chooses since delivery began. Anything the game has
 * touched since, however slightly, is left exactly as it is.
 */
public final class LegacyGifts {
    private LegacyGifts() { }

    /** 1.0.3 shipped the unsigned encoder at this instant; nothing delivered later is legacy. */
    static final Instant FIXED = Instant.parse("2026-09-13T10:41:03Z");

    public enum Verdict { CORRECT, REPAIRABLE, CHANGED, AMBIGUOUS, NOT_FOUND }

    public record Finding(Reward reward, Verdict verdict, List<StudyGift.Location> where) { }

    public record Assessment(List<Finding> findings) {
        public List<Finding> repairable() {
            return findings.stream().filter(f -> f.verdict() == Verdict.REPAIRABLE).toList();
        }

        /** High-bit gifts from before the fix that could not be proven untouched, and so are not written. */
        public List<Finding> leftAlone() {
            return findings.stream()
                .filter(f -> f.verdict() == Verdict.CHANGED || f.verdict() == Verdict.AMBIGUOUS)
                .filter(f -> StudyGift.personalityFor(f.reward().id()) < 0)
                .filter(f -> f.reward().deliveredAt().isBefore(FIXED))
                .toList();
        }
    }

    private record Held(StudyGift.Location where, byte[] record) { }

    /** Every delivered reward's gift, judged against the save. Reads only; writes nothing. */
    public static Assessment assess(byte[] bytes, List<Reward> rewards) {
        var save = Gen3Save.read(bytes);
        var trainer = save.trainer();
        var storage = save.storage();
        var byMarker = new HashMap<Integer, List<Held>>();
        for (int i = 0; i < save.partyCount(); i++) {
            var record = save.partyRecord(i);
            byMarker.computeIfAbsent(personalityOf(record), k -> new ArrayList<>())
                .add(new Held(new StudyGift.Location(-1, i, true), record));
        }
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                int at = Gen3Save.slotOffset(box, slot);
                if (Gen3Save.empty(storage, at)) continue;
                var record = Arrays.copyOfRange(storage, at, at + Gen3Pokemon.BOX_SIZE);
                byMarker.computeIfAbsent(personalityOf(record), k -> new ArrayList<>())
                    .add(new Held(new StudyGift.Location(box, slot, false), record));
            }

        var findings = new ArrayList<Finding>();
        for (var reward : rewards) {
            if (!reward.delivered()) continue;
            var held = byMarker.getOrDefault(StudyGift.personalityFor(reward.id()), List.of());
            var where = held.stream().map(Held::where).toList();
            if (held.isEmpty()) findings.add(new Finding(reward, Verdict.NOT_FOUND, where));
            else if (held.size() > 1) findings.add(new Finding(reward, Verdict.AMBIGUOUS, where));
            else findings.add(new Finding(reward, verdict(held.getFirst(), reward, trainer), where));
        }
        return new Assessment(List.copyOf(findings));
    }

    private static Verdict verdict(Held held, Reward reward, Gen3Save.Trainer trainer) {
        byte[] correct = GameDelivery.companionFor(reward, trainer).encode();
        boolean party = held.where().inParty();
        byte[] box = Arrays.copyOf(held.record(), Gen3Pokemon.BOX_SIZE);
        if (Arrays.equals(box, correct) && (!party || Arrays.equals(held.record(), Gen3Pokemon.toParty(correct, 0))))
            return Verdict.CORRECT;
        // Below the sign bit both orders agree, so a mismatch there is a change, never a legacy layout.
        if (personalityOf(correct) >= 0) return Verdict.CHANGED;
        for (int flags : new int[] {0x02, 0x00}) {
            byte[] legacy = signedLayout(correct, flags);
            if (!Arrays.equals(box, legacy)) continue;
            if (!party || Arrays.equals(held.record(), legacyParty(correct, legacy))) return Verdict.REPAIRABLE;
        }
        return Verdict.CHANGED;
    }

    /**
     * Today's record with its substructures moved to where the signed encoder put
     * them, and the header flag byte an older build wrote. The checksum at 0x1C is
     * kept: a permutation does not change a sum.
     */
    static byte[] signedLayout(byte[] correctBox, int flags) {
        var out = correctBox.clone();
        int personality = personalityOf(out), otId = u32(out, 4);
        byte[] plain = Gen3Pokemon.decrypt(out, 0x20, personality, otId);
        var shuffled = new byte[Gen3Pokemon.DATA_SIZE];
        for (int substructure = 0; substructure < 4; substructure++)
            System.arraycopy(plain, Gen3Pokemon.offsetOf(personality, substructure),
                shuffled, signedOffsetOf(personality, substructure), Gen3Pokemon.SUBSTRUCTURE);
        Gen3Pokemon.encrypt(shuffled, personality, otId);
        System.arraycopy(shuffled, 0, out, 0x20, Gen3Pokemon.DATA_SIZE);
        out[0x13] = (byte) flags;
        return out;
    }

    /** The party record toParty wrote for a legacy gift: the legacy box, stats from the signed nature. */
    static byte[] legacyParty(byte[] correctBox, byte[] legacyBox) {
        var record = Gen3Pokemon.toParty(correctBox, 0);
        var mon = Gen3Pokemon.decode(correctBox, 0);
        int[] stats = Gen3Pokemon.stats(mon.nationalDex(), mon.levelFromExperience(), mon.ivs(), mon.evs,
            Math.floorMod(mon.personality, 25));
        putU16(record, 0x56, stats[0]);
        for (int i = 0; i < 6; i++) putU16(record, 0x58 + i * 2, stats[i]);
        System.arraycopy(legacyBox, 0, record, 0, Gen3Pokemon.BOX_SIZE);
        return record;
    }

    private static int signedOffsetOf(int personality, int substructure) {
        int[] order = Gen3Pokemon.ORDERS[Math.floorMod(personality, Gen3Pokemon.ORDERS.length)];
        for (int slot = 0; slot < order.length; slot++)
            if (order[slot] == substructure) return slot * Gen3Pokemon.SUBSTRUCTURE;
        throw new IllegalArgumentException("No such substructure " + substructure);
    }

    private static int personalityOf(byte[] record) { return u32(record, 0); }

    private static int u32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    private static void putU16(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >>> 8);
    }
}
