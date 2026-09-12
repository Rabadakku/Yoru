package dev.yoru.game;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Builds synthetic Gen 3 saves for tests.
 *
 * A real save is somebody's progress and does not belong in a repository, but
 * its structure can be reproduced exactly. Sections are rotated by default,
 * because a fixture whose sections sit in id order would not exercise the one
 * property most likely to be got wrong.
 *
 * A fixture's slot is chosen by its counter, as the game chooses: the game
 * loads slot {@code counter % 2}, so a save written anywhere else is one the
 * game would never load. An earlier fixture put counter 3 in slot 1 and tests
 * built on it passed while describing a save the real game could not open.
 */
public final class Gen3Fixture {

    private Gen3Fixture() { }

    /** A complete save in the slot the game loads for this counter, with the other slot unwritten. */
    public static byte[] save(long counter, int shift) {
        return saveInSlot((int) (counter % 2), counter, shift);
    }

    /** A complete save in a named slot. Only for tests about a counter that points elsewhere. */
    public static byte[] saveInSlot(int slot, long counter, int shift) {
        var raw = new byte[Gen3Save.SIZE];
        Arrays.fill(raw, (byte) 0xFF);
        writeSlot(raw, slot, counter, shift);
        return raw;
    }

    /** Writes a complete slot of zeroed sections into an existing file. */
    public static void writeSlot(byte[] raw, int slot, long counter, int shift) {
        for (int position = 0; position < Gen3Save.SECTIONS; position++) {
            int id = Math.floorMod(position + shift, Gen3Save.SECTIONS);
            int at = slot * Gen3Save.SLOT + position * Gen3Save.SECTION;
            Arrays.fill(raw, at, at + Gen3Save.SECTION, (byte) 0);
            Gen3Save.putU16(raw, at + Gen3Save.ID_AT, id);
            Gen3Save.putU32(raw, at + Gen3Save.SIGNATURE_AT, Gen3Save.SIGNATURE);
            Gen3Save.putU32(raw, at + Gen3Save.COUNTER_AT, counter);
        }
        stamp(raw, slot);
    }

    /** Where a section sits in the file, within the slot the game loads. */
    public static int offsetOf(byte[] raw, int id) {
        int slot = Gen3Save.read(raw).loadedSlot();
        for (int position = 0; position < Gen3Save.SECTIONS; position++) {
            int at = slot * Gen3Save.SLOT + position * Gen3Save.SECTION;
            if (Gen3Save.u16(raw, at + Gen3Save.ID_AT) == id) return at;
        }
        throw new IllegalStateException("No section " + id);
    }

    /** Writes a trainer into section 0 in place, so the fixture stays a one-slot save. */
    public static byte[] withTrainer(byte[] raw, String name, int gender, int publicId, int secretId) {
        var out = raw.clone();
        int slot = Gen3Save.read(out).loadedSlot();
        int at = offsetOf(out, 0);
        Gen3Text.write(name, out, at, 8);
        out[at + 0x08] = (byte) gender;
        Gen3Save.putU16(out, at + 0x0A, publicId);
        Gen3Save.putU16(out, at + 0x0C, secretId);
        return stamp(out, slot);
    }

    /** Writes a party into section 1 in place. */
    public static byte[] withParty(byte[] raw, List<byte[]> records) {
        var out = raw.clone();
        int slot = Gen3Save.read(out).loadedSlot();
        int at = offsetOf(out, 1);
        out[at + Gen3Save.PARTY_COUNT_AT] = (byte) records.size();
        for (int i = 0; i < records.size(); i++)
            System.arraycopy(records.get(i), 0, out, at + Gen3Save.PARTY_AT + i * Gen3Pokemon.PARTY_SIZE,
                Gen3Pokemon.PARTY_SIZE);
        return stamp(out, slot);
    }

    /** A party member for fixtures, belonging to the save's trainer and marked by {@code seed}. */
    public static byte[] member(byte[] raw, int national, int level, int seed) {
        var trainer = Gen3Save.read(raw).trainer();
        var id = new UUID(0x5EED_0000_0000_4000L, 0x8000_0000_0000_0000L | seed);
        return Gen3Pokemon.toParty(StudyGift.build(id, national, level, trainer, null, 0).encode(), 0);
    }

    /** Sets one of the game's event flags, in the slot the game loads. */
    public static byte[] withFlag(byte[] raw, int flagId) {
        var out = raw.clone();
        int slot = Gen3Save.read(out).loadedSlot();
        int sb1 = Gen3Save.FLAGS_AT + flagId / 8;
        int at = offsetOf(out, 1 + sb1 / Gen3Save.SB1_PER_SECTION);
        out[at + sb1 % Gen3Save.SB1_PER_SECTION] |= (byte) (1 << (flagId % 8));
        return stamp(out, slot);
    }

    /** Recomputes every section checksum in a slot, so a fixture edited in place stays valid. */
    public static byte[] stamp(byte[] raw, int slot) {
        for (int position = 0; position < Gen3Save.SECTIONS; position++) {
            int at = slot * Gen3Save.SLOT + position * Gen3Save.SECTION;
            int id = Gen3Save.u16(raw, at + Gen3Save.ID_AT);
            if (id < Gen3Save.SECTIONS)
                Gen3Save.putU16(raw, at + Gen3Save.CHECKSUM_AT, Gen3Save.checksum(raw, at, Gen3Save.CHECKSUMMED[id]));
        }
        return raw;
    }
}
