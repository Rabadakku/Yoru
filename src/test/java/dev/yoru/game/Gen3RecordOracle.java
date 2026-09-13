package dev.yoru.game;

/**
 * Builds Generation III Pokémon records by hand, from the game's own definition.
 *
 * Nothing here calls Gen3Pokemon's layout, encryption or checksum. The position
 * table is pokeemerald's GetSubstruct as written there — for each order, where
 * Growth, Attacks, EVs and Misc sit — which is the inverse of the slot table
 * Gen3Pokemon keeps, so one transcription mistake cannot be shared by both. The
 * order is a parameter, so a test can build what the game reads (unsigned) and
 * what an older Yoru wrote (signed) from the same fields.
 */
public final class Gen3RecordOracle {
    private Gen3RecordOracle() { }

    /** pokeemerald SUBSTRUCT_CASE(n, growth, attacks, evs, misc), row n. */
    private static final int[][] POSITIONS = {
        {0,1,2,3}, {0,1,3,2}, {0,2,1,3}, {0,3,1,2}, {0,2,3,1}, {0,3,2,1},
        {1,0,2,3}, {1,0,3,2}, {2,0,1,3}, {3,0,1,2}, {2,0,3,1}, {3,0,2,1},
        {1,2,0,3}, {1,3,0,2}, {2,1,0,3}, {3,1,0,2}, {2,3,0,1}, {3,2,0,1},
        {1,2,3,0}, {1,3,2,0}, {2,1,3,0}, {3,1,2,0}, {2,3,1,0}, {3,2,1,0},
    };

    static long unsigned(int value) { return value & 0xFFFFFFFFL; }

    /** The order the game uses: personality % 24 on a u32. */
    static int unsignedOrder(int personality) { return (int) (unsigned(personality) % 24); }

    /** The order Yoru's encoder used before 1.0.3: floor modulo on a signed int. */
    static int signedOrder(int personality) { return Math.floorMod(personality, 24); }

    /** An 80-byte box record of these field values, laid out in position-table row {@code order}. */
    static byte[] box(Gen3Pokemon f, int order, int flags) {
        var out = new byte[80];
        putU32(out, 0x00, unsigned(f.personality));
        putU32(out, 0x04, unsigned(f.otId));
        Gen3Text.write(f.nickname, out, 0x08, 10);     // names are not what this oracle is checking
        out[0x12] = (byte) f.language;
        out[0x13] = (byte) flags;
        Gen3Text.write(f.otName, out, 0x14, 7);
        out[0x1B] = (byte) f.markings;

        var plain = new byte[48];
        int[] position = POSITIONS[order];
        int g = position[0] * 12, a = position[1] * 12, e = position[2] * 12, m = position[3] * 12;
        putU16(plain, g, f.species);
        putU16(plain, g + 2, f.heldItem);
        putU32(plain, g + 4, unsigned(f.experience));
        plain[g + 8] = (byte) f.ppBonuses;
        plain[g + 9] = (byte) f.friendship;
        for (int i = 0; i < 4; i++) putU16(plain, a + i * 2, f.moves[i]);
        for (int i = 0; i < 4; i++) plain[a + 8 + i] = (byte) f.pp[i];
        for (int i = 0; i < 6; i++) plain[e + i] = (byte) f.evs[i];
        for (int i = 0; i < 6; i++) plain[e + 6 + i] = (byte) f.contest[i];
        plain[m] = (byte) f.pokerus;
        plain[m + 1] = (byte) f.metLocation;
        putU16(plain, m + 2, f.origins);
        putU32(plain, m + 4, unsigned(f.ivsEggAbility));
        putU32(plain, m + 8, unsigned(f.ribbons));

        long sum = 0;
        for (int i = 0; i < 48; i += 2) sum += u16(plain, i);
        putU16(out, 0x1C, (int) (sum & 0xFFFF));

        long key = unsigned(f.personality) ^ unsigned(f.otId);
        for (int i = 0; i < 48; i += 4) putU32(out, 0x20 + i, u32(plain, i) ^ key);
        return out;
    }

    private static int u16(byte[] b, int at) { return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8; }

    private static long u32(byte[] b, int at) { return u16(b, at) | (long) u16(b, at + 2) << 16; }

    private static void putU16(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >>> 8);
    }

    private static void putU32(byte[] b, int at, long value) {
        putU16(b, at, (int) (value & 0xFFFF));
        putU16(b, at + 2, (int) (value >>> 16 & 0xFFFF));
    }
}
