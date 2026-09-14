package dev.yoru.game;

import java.util.Arrays;
import java.util.Random;

/**
 * An independent oracle for the save file's sections, slots and checksums (#10).
 *
 * Gen3Fixture writes sections with Gen3Save's own offsets, signature, size table
 * and checksum, so a mistake in any of them would be written and read back in
 * agreement and pass every test built on the fixture. This test builds saves
 * from the game's documented layout instead, spelled out as literals here — the
 * sector footer and the SaveBlock chunk sizes of the game's own save code — with
 * its own little-endian writer and its own checksum, and asks Gen3Save only
 * what it makes of the result.
 *
 * Invented bytes only: every section's data is seeded noise.
 */
public final class Gen3SectionOracleTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final int FILE = 0x20000, SECTOR = 0x1000, PER_SLOT = 14;
    private static final int FOOTER_ID = 0xFF4, FOOTER_CHECKSUM = 0xFF6, FOOTER_SIGNATURE = 0xFF8, FOOTER_COUNTER = 0xFFC;
    private static final long SIGNATURE = 0x08012025L;
    /** SaveBlock2 is 0xF2C; SaveBlock1 is four chunks, 3 x 0xF80 then 0xF08; the PC is nine, 8 x 0xF80 then 0x7D0. */
    private static final int[] DATA = {0xF2C, 0xF80, 0xF80, 0xF80, 0xF08,
        0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0xF80, 0x7D0};

    private static void le16(byte[] b, int at, int v) { b[at] = (byte) v; b[at + 1] = (byte) (v >>> 8); }
    private static void le32(byte[] b, int at, long v) { for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >>> (8 * i)); }
    private static int le16(byte[] b, int at) { return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8; }
    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    /** The game's checksum: little-endian words summed modulo 2^32, then the two halves added and cut to 16 bits. */
    static int checksum(byte[] b, int at, int size) {
        long sum = 0;
        for (int word = 0; word < size / 4; word++) sum = (sum + le32(b, at + 4 * word)) & 0xFFFFFFFFL;
        return (int) (((sum >>> 16) + sum) & 0xFFFF);
    }

    private static byte[] blank() {
        var file = new byte[FILE];
        Arrays.fill(file, (byte) 0xFF);
        return file;
    }

    private static int sector(int slot, int position) { return slot * PER_SLOT * SECTOR + position * SECTOR; }

    /** A whole slot: the section at each position is {@code (position + rotation) % 14}, its data seeded noise. */
    private static void writeSlot(byte[] file, int slot, long counter, int rotation, long seed) {
        var noise = new Random(seed);
        for (int position = 0; position < PER_SLOT; position++) {
            int id = (position + rotation) % PER_SLOT, at = sector(slot, position);
            Arrays.fill(file, at, at + SECTOR, (byte) 0);
            for (int i = 0; i < DATA[id]; i++) file[at + i] = (byte) noise.nextInt(256);
            le16(file, at + FOOTER_ID, id);
            le16(file, at + FOOTER_CHECKSUM, checksum(file, at, DATA[id]));
            le32(file, at + FOOTER_SIGNATURE, SIGNATURE);
            le32(file, at + FOOTER_COUNTER, counter);
        }
    }

    /** Where section {@code id} sits in a slot, found from the footers this test wrote. */
    private static int find(byte[] file, int slot, int id) {
        for (int position = 0; position < PER_SLOT; position++)
            if (le16(file, sector(slot, position) + FOOTER_ID) == id) return sector(slot, position);
        throw new AssertionError("no section " + id + " in slot " + slot);
    }

    private static Gen3Save.Unreadable refusal(byte[] file) {
        try {
            Gen3Save.read(file);
            return null;
        } catch (Gen3Save.UnreadableSave refused) {
            return refused.reason();
        }
    }

    public static void main(String[] args) {
        // The oracle's checksum, pinned to hand arithmetic before anything leans on it.
        var words = new byte[8];
        le32(words, 0, 1);
        le32(words, 4, 0x10000);
        check(checksum(words, 0, 8) == 2, "1 + 0x10000 = 0x10001, and 0x1 + 0x0001 = 2");
        le32(words, 0, 0xFFFFFFFFL);
        check(checksum(words, 0, 4) == 0xFFFE, "0xFFFFFFFF folds to 0xFFFE");
        check(checksum(words, 0, 7) == checksum(words, 0, 4), "a trailing partial word is not summed");

        // Every rotation, in the slot each counter's parity selects.
        for (int rotation = 0; rotation < PER_SLOT; rotation++) {
            for (long counter : new long[]{4, 5}) {
                var file = blank();
                writeSlot(file, (int) (counter % 2), counter, rotation, 1000 + rotation);
                var save = Gen3Save.read(file);
                check(save.loadedSlot() == counter % 2 && save.counter() == counter,
                    "rotation " + rotation + ", counter " + counter + ": slot " + save.loadedSlot() + " counter " + save.counter());
            }
        }

        // Each section's checksum covers exactly its own data size: a flipped byte
        // inside it damages the save, and one just past it changes nothing.
        for (int id = 0; id < PER_SLOT; id++) {
            var inside = blank();
            writeSlot(inside, 0, 2, 5, 77);
            inside[find(inside, 0, id) + DATA[id] - 1] ^= 0x01;
            check(refusal(inside) == Gen3Save.Unreadable.DAMAGED, "a flipped byte at the end of section " + id + "'s data is damage");

            var past = blank();
            writeSlot(past, 0, 2, 5, 77);
            past[find(past, 0, id) + DATA[id]] = 0x5A;
            check(refusal(past) == null, "a byte just past section " + id + "'s " + DATA[id] + " data bytes is not checksummed");
        }

        // A sector's footer has to be exactly right.
        var unsigned = blank();
        writeSlot(unsigned, 0, 2, 0, 3);
        le32(unsigned, find(unsigned, 0, 9) + FOOTER_SIGNATURE, SIGNATURE ^ 1);
        check(refusal(unsigned) == Gen3Save.Unreadable.DAMAGED, "a sector without the signature leaves an incomplete slot");
        var outOfRange = blank();
        writeSlot(outOfRange, 0, 2, 0, 3);
        le16(outOfRange, find(outOfRange, 0, 13) + FOOTER_ID, PER_SLOT);
        check(refusal(outOfRange) == Gen3Save.Unreadable.DAMAGED, "a section id past the table is damage, not a guess");
        var duplicated = blank();
        writeSlot(duplicated, 0, 2, 0, 3);
        int twelve = find(duplicated, 0, 12), eleven = find(duplicated, 0, 11);
        System.arraycopy(duplicated, eleven, duplicated, twelve, SECTOR);
        check(refusal(duplicated) == Gen3Save.Unreadable.DAMAGED, "two copies of one section and none of another is incomplete");

        // Two sound slots: the newer counter wins, and damage to it falls back to the older.
        var both = blank();
        writeSlot(both, 0, 6, 3, 11);
        writeSlot(both, 1, 7, 9, 12);
        var newer = Gen3Save.read(both);
        check(newer.loadedSlot() == 1 && newer.counter() == 7, "the newer of two sound slots is loaded");
        both[find(both, 1, 4) + 10] ^= 0x40;
        var older = Gen3Save.read(both);
        check(older.loadedSlot() == 0 && older.counter() == 6, "a damaged newer slot falls back to the sound older one");

        // The counter wraps: 0 is newer than 0xFFFFFFFF.
        var wrapped = blank();
        writeSlot(wrapped, 0, 0, 2, 21);
        writeSlot(wrapped, 1, 0xFFFFFFFFL, 6, 22);
        var afterWrap = Gen3Save.read(wrapped);
        check(afterWrap.counter() == 0 && afterWrap.loadedSlot() == 0, "after the counter wraps, 0 is the newer save");

        // The game loads slot counter % 2, whatever slot the counter was found in.
        var misplaced = blank();
        writeSlot(misplaced, 0, 3, 0, 31);
        check(refusal(misplaced) == Gen3Save.Unreadable.INCOMPLETE_SLOT, "counter 3 in slot 1's place sends the game to an empty slot");

        check(refusal(blank()) == Gen3Save.Unreadable.NEVER_SAVED, "an erased cartridge has never saved");
        check(refusal(new byte[FILE - 1]) == Gen3Save.Unreadable.WRONG_SIZE, "a file one byte short is the wrong size");

        System.out.println("PASS: " + checks + " independent section checks (all rotations, per-section sizes, footers, slot choice, wrap, reasons)");
    }
}
