package dev.yoru.game;

import dev.yoru.domain.Model.Reward;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plans how study-earned Pokémon go into a game save.
 *
 * Pure: bytes in, bytes and outcomes out. The save and the reward ledger live in
 * the same vault, so the caller writes both at once (GameSync) and a crash can
 * never leave one changed without the other.
 *
 * <h2>Where they go</h2>
 * Where a Pokémon caught in the game would: the party while it has room, then
 * the PC, starting from the box the PC last had open. Party members get the
 * stats the game itself calculates on withdrawal, and every delivered species
 * is recorded in the Pokédex as seen and caught. Nothing is sent before the
 * player has a starter, because the game's opening scenes expect the party to
 * hold exactly the Pokémon from Birch's bag.
 *
 * <h2>Recognising what already arrived</h2>
 * Every delivered Pokémon carries its reward's identity in its personality
 * value ({@link StudyGift#personalityFor}), so the save is its own record of
 * what it has received. A reward whose Pokémon is already there — from a sync
 * that was interrupted, or a save brought in from elsewhere — is only
 * recorded, never delivered twice.
 *
 * <h2>What it refuses</h2>
 * A save the game might load differently than Yoru read it is never changed,
 * and nothing is handed back that has not first been re-read and compared
 * against the original, byte by byte.
 */
public final class GameDelivery {

    public enum Kind {
        /** Placed in the save. */
        DELIVERED,
        /** Already in the save; recorded as spent. */
        ALREADY_THERE,
        /** Already recorded as delivered; nothing to do. */
        ALREADY_SPENT,
        /** Nothing is wrong; the game is not ready yet — no starter has been chosen. */
        WAITING,
        /** There is no save yet. */
        NO_SAVE,
        /** The save is not one the game would load completely. */
        UNREADABLE,
        /** A readable save in a state Yoru will not change. */
        NOT_EDITABLE,
        /** The party and every box are full. */
        FULL,
        /** The change failed its own check; nothing was changed. */
        FAILED;

        public boolean spent() { return this == DELIVERED || this == ALREADY_THERE || this == ALREADY_SPENT; }
    }

    /** Where a delivered Pokémon went: a party position, or a box and slot. */
    public record Place(boolean party, int box, int slot) {
        public String describe(Gen3Save save, byte[] storage) {
            return party ? "your party" : save.boxName(storage, box).strip() + ", slot " + (slot + 1);
        }
    }

    /** What happened to one reward, in words the interface can show as they are. */
    public record Outcome(UUID reward, Kind kind, String message, Place place) {
        public boolean spent() { return kind.spent(); }
    }

    /** The save after delivery — the same bytes when nothing was placed — and an outcome per reward. */
    public record Plan(byte[] save, List<Outcome> outcomes) {
        /** Rewards to record as delivered: placed now, or found already there. */
        public List<UUID> spent() {
            return outcomes.stream().filter(o -> o.kind() == Kind.DELIVERED || o.kind() == Kind.ALREADY_THERE)
                .map(Outcome::reward).toList();
        }
        public boolean changes(byte[] before) { return !Arrays.equals(before, save); }
    }

    /**
     * Where Littleroot Town sits in the met-location table. Every study gift is
     * recorded as met there: the one place every save has already been.
     */
    public static final int MET_IN_LITTLEROOT = 0;

    private GameDelivery() { }

    /** The Pokémon a reward stands for, as delivery builds it. */
    static Gen3Pokemon companionFor(Reward reward, Gen3Save.Trainer trainer) {
        return StudyGift.build(reward.id(), reward.nationalDex(), reward.level(), trainer, null, MET_IN_LITTLEROOT);
    }

    /** Plans delivering every reward it can into {@code original}, which may be null when there is no save yet. */
    public static Plan plan(byte[] original, List<Reward> rewards) {
        var outcomes = new LinkedHashMap<UUID, Outcome>();
        var pending = new ArrayList<Reward>();
        for (var reward : rewards) {
            if (reward.delivered()) outcomes.put(reward.id(), outcome(reward, Kind.ALREADY_SPENT, "This one is already in your game.", null));
            else pending.add(reward);
        }
        byte[] result = original;
        if (!pending.isEmpty()) result = placeAll(original, pending, outcomes);
        return new Plan(result, rewards.stream().map(r -> outcomes.get(r.id())).toList());
    }

    private static byte[] placeAll(byte[] original, List<Reward> pending, Map<UUID, Outcome> outcomes) {
        if (original == null) {
            every(pending, outcomes, Kind.NO_SAVE, "Waiting for your first save: press Play, start the game, "
                + "choose your starter and save.");
            return null;
        }
        Gen3Save save;
        try { save = Gen3Save.read(original); }
        catch (IllegalArgumentException e) {
            every(pending, outcomes, Kind.UNREADABLE, "The save has been left alone: " + e.getMessage());
            return original;
        }

        var storage = save.storage();
        var remaining = new ArrayList<Reward>();
        for (var reward : pending) {
            var found = StudyGift.whereDelivered(save, storage, reward.id());
            if (found == null) { remaining.add(reward); continue; }
            outcomes.put(reward.id(), outcome(reward, Kind.ALREADY_THERE, "Already in your game (" + found.describe() + ").",
                new Place(found.inParty(), found.box(), found.slot())));
        }
        if (remaining.isEmpty()) return original;

        String refusal = save.whyNotEditable();
        if (refusal != null) { every(remaining, outcomes, Kind.NOT_EDITABLE, refusal); return original; }
        if (save.partyCount() == 0) {
            every(remaining, outcomes, Kind.WAITING, "Waiting for your starter: choose it in the game and save, "
                + "and this goes in the next time you press Play.");
            return original;
        }

        var trainer = save.trainer();
        var party = new ArrayList<>(save.partyRecords());
        var placed = new LinkedHashMap<Reward, Place>();
        var records = new HashMap<Reward, byte[]>();
        for (var reward : remaining) {
            byte[] record = companionFor(reward, trainer).encode();
            Place place;
            if (party.size() < Gen3Save.PARTY_LIMIT) {
                place = new Place(true, -1, party.size());
                party.add(Gen3Pokemon.toParty(record, 0));
            } else {
                int[] free = save.firstFreeSlot(storage);
                if (free == null) {
                    outcomes.put(reward.id(), outcome(reward, Kind.FULL, "Your party and every PC box are full. "
                        + "Make room in the game and save, and it goes in next time.", null));
                    continue;
                }
                place = new Place(false, free[0], free[1]);
                System.arraycopy(record, 0, storage, Gen3Save.slotOffset(free[0], free[1]), Gen3Pokemon.BOX_SIZE);
            }
            placed.put(reward, place);
            records.put(reward, record);
            save.register(reward.nationalDex());
        }
        if (placed.isEmpty()) return original;

        byte[] written;
        try {
            save.party(party);
            save.storage(storage);
            written = save.bytes();
            verify(original, written, placed, records);
        } catch (RuntimeException e) {
            every(placed.keySet(), outcomes, Kind.FAILED, "Nothing was changed; it is still yours to send. " + e.getMessage());
            return original;
        }
        for (var entry : placed.entrySet())
            outcomes.put(entry.getKey().id(), outcome(entry.getKey(), Kind.DELIVERED,
                "Sent to " + entry.getValue().describe(save, storage) + ".", entry.getValue()));
        return written;
    }

    private static Outcome outcome(Reward reward, Kind kind, String message, Place place) {
        return new Outcome(reward.id(), kind, message, place);
    }

    private static void every(java.util.Collection<Reward> rewards, Map<UUID, Outcome> outcomes, Kind kind, String message) {
        for (var reward : rewards) outcomes.put(reward.id(), outcome(reward, kind, message, null));
    }

    // ---- proving the change before handing it back --------------------------

    /**
     * Proves the written save is the original plus these Pokémon and nothing else.
     *
     * Re-read from its own bytes rather than trusted. Outside the few regions a
     * delivery may touch — the party, the PC and the Pokédex flags — every byte
     * of every section must match. Inside them: every Pokémon already there is
     * byte for byte where it was, each new one sits exactly where it was placed
     * with a sound checksum, the stats the game would give it, and its reward's
     * marker, and Pokédex bits are only ever set, for the species delivered.
     *
     * In correct code this never fires, so every branch is exercised directly by
     * the tests with a change it must refuse.
     */
    static void verify(byte[] original, byte[] written, Map<Reward, Place> placed, Map<Reward, byte[]> records) {
        var before = Gen3Save.read(original);
        var after = Gen3Save.read(written);

        for (int id = 0; id < Gen3Save.STORAGE_FIRST; id++) {
            byte[] a = before.section(id), b = after.section(id);
            for (int i = 0; i < Gen3Save.CHECKSUMMED[id]; i++)
                if (a[i] != b[i] && !mayChange(id, i))
                    throw new IllegalStateException("Save section " + id + " changed at byte " + i
                        + ", which a delivery never touches.");
        }

        var pcBefore = before.storage();
        var pcAfter = after.storage();
        var wantedInPc = new HashMap<Integer, byte[]>();
        for (var entry : placed.entrySet())
            if (!entry.getValue().party())
                wantedInPc.put(Gen3Save.slotOffset(entry.getValue().box(), entry.getValue().slot()), records.get(entry.getKey()));
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                int at = Gen3Save.slotOffset(box, slot);
                byte[] wanted = wantedInPc.get(at);
                if (wanted == null) {
                    if (!Arrays.equals(pcBefore, at, at + Gen3Save.ENTRY, pcAfter, at, at + Gen3Save.ENTRY))
                        throw new IllegalStateException("Box " + (box + 1) + " slot " + (slot + 1) + " changed.");
                } else {
                    if (!Gen3Save.empty(pcBefore, at))
                        throw new IllegalStateException("A delivery would have overwritten an occupied slot.");
                    if (!Arrays.equals(pcAfter, at, at + Gen3Save.ENTRY, wanted, 0, Gen3Save.ENTRY))
                        throw new IllegalStateException("A delivered slot does not hold its intended record.");
                    if (!Gen3Pokemon.intact(pcAfter, at))
                        throw new IllegalStateException("A delivered record's checksum does not verify.");
                }
            }
        if (!Arrays.equals(pcBefore, 0, 4, pcAfter, 0, 4)
            || !Arrays.equals(pcBefore, Gen3Save.BOX_NAMES_AT, Gen3Save.STORAGE_SIZE,
                pcAfter, Gen3Save.BOX_NAMES_AT, Gen3Save.STORAGE_SIZE))
            throw new IllegalStateException("The box names, wallpapers or open box changed.");

        int joined = (int) placed.values().stream().filter(Place::party).count();
        if (after.partyCount() != before.partyCount() + joined)
            throw new IllegalStateException("The party has the wrong number of members.");
        for (int i = 0; i < before.partyCount(); i++)
            if (!Arrays.equals(before.partyRecord(i), after.partyRecord(i)))
                throw new IllegalStateException("Party member " + (i + 1) + " changed.");
        for (var entry : placed.entrySet()) {
            if (!entry.getValue().party()) continue;
            byte[] expected = Gen3Pokemon.toParty(records.get(entry.getKey()), 0);
            if (!Arrays.equals(after.partyRecord(entry.getValue().slot()), expected))
                throw new IllegalStateException("A delivered party member is not the record intended.");
            if (!Gen3Pokemon.intact(expected, 0))
                throw new IllegalStateException("A delivered record's checksum does not verify.");
        }

        var dex = new int[Gen3Save.DEX_BYTES];
        for (var entry : records.entrySet()) {
            if (Gen3Pokemon.decode(entry.getValue(), 0).personality != StudyGift.personalityFor(entry.getKey().id()))
                throw new IllegalStateException("A delivered record does not carry its reward's marker.");
            int index = entry.getKey().nationalDex() - 1;
            dex[index / 8] |= 1 << (index % 8);
        }
        for (int i = 0; i < Gen3Save.DEX_BYTES; i++) {
            checkDex(before.section(0)[Gen3Save.DEX_OWNED_AT + i], after.section(0)[Gen3Save.DEX_OWNED_AT + i], dex[i]);
            checkDex(before.section(0)[Gen3Save.DEX_SEEN_AT + i], after.section(0)[Gen3Save.DEX_SEEN_AT + i], dex[i]);
            checkDex(before.sb1Byte(Gen3Save.SEEN1_AT + i), after.sb1Byte(Gen3Save.SEEN1_AT + i), dex[i]);
            checkDex(before.sb1Byte(Gen3Save.SEEN2_AT + i), after.sb1Byte(Gen3Save.SEEN2_AT + i), dex[i]);
        }
    }

    private static void checkDex(int before, int after, int added) {
        if ((after & 0xFF) != ((before | added) & 0xFF))
            throw new IllegalStateException("The Pokédex changed in a way a delivery does not.");
    }

    /** The bytes outside the PC a delivery is allowed to change. */
    private static boolean mayChange(int section, int at) {
        return switch (section) {
            case 0 -> within(at, Gen3Save.DEX_OWNED_AT, Gen3Save.DEX_BYTES) || within(at, Gen3Save.DEX_SEEN_AT, Gen3Save.DEX_BYTES);
            case 1 -> within(at, Gen3Save.PARTY_COUNT_AT, Gen3Save.PARTY_END - Gen3Save.PARTY_COUNT_AT)
                || within(at, Gen3Save.SEEN1_AT, Gen3Save.DEX_BYTES);
            case 4 -> within(at, Gen3Save.SEEN2_AT - 3 * Gen3Save.SB1_PER_SECTION, Gen3Save.DEX_BYTES);
            default -> false;
        };
    }

    private static boolean within(int at, int start, int length) { return at >= start && at < start + length; }
}
