package dev.yoru.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * One Pokémon as the Gen 3 games store it: 80 bytes, encrypted and shuffled.
 *
 * This is the format a study-earned companion has to be written in for the real
 * game to accept it. Getting any of it wrong does not produce an error — it
 * produces a <b>Bad Egg</b>, because the game validates by checksum and treats
 * a mismatch as corruption. So every field here is derived, never guessed, and
 * {@link #encode} is the exact inverse of {@link #decode}.
 *
 * <h2>The layout</h2>
 * <pre>
 *   0x00 u32  personality value
 *   0x04 u32  original trainer id (secret id in the high half)
 *   0x08 10   nickname, in the game's own character set
 *   0x12 u8   language
 *   0x13 u8   flags: bit 0 bad egg, bit 1 has species, bit 2 is egg
 *   0x14 7    original trainer name
 *   0x1B u8   markings
 *   0x1C u16  checksum of the decrypted 48 bytes
 *   0x1E u16  padding
 *   0x20 48   four 12-byte substructures, shuffled and encrypted
 * </pre>
 *
 * <h2>The two tricks</h2>
 * The 48 bytes are XOR-encrypted with {@code otId ^ personality}, one 32-bit
 * word at a time. Underneath, the four substructures — Growth, Attacks, EVs,
 * Misc — appear in one of 24 orders chosen by {@code personality % 24}. Both
 * are keyed off values stored in the clear, which is what makes the format
 * decodable at all.
 *
 * The checksum is the sum of the 48 decrypted bytes read as 24 unsigned
 * halfwords, truncated to 16 bits. It is stored in the clear at 0x1C.
 */
public final class Gen3Pokemon {

    public static final int BOX_SIZE = 80, PARTY_SIZE = 100, DATA_SIZE = 48, SUBSTRUCTURE = 12;
    /** The trainer name field at 0x14: seven bytes, with no room for a terminator after a seventh character. */
    static final int OT_NAME_BYTES = 7;

    /** Substructure identities, in the order the game names them. */
    private static final int GROWTH = 0, ATTACKS = 1, EVS = 2, MISC = 3;

    /**
     * The 24 orders, indexed by {@code personality % 24}.
     *
     * Each row lists which substructure occupies slot 0, 1, 2 and 3. This is
     * the standard permutation table in lexicographic order of the letters
     * G, A, E, M — which is worth stating because it is the one part of this
     * that cannot be derived from anything else in the record.
     */
    static final int[][] ORDERS = {
        {GROWTH,ATTACKS,EVS,MISC}, {GROWTH,ATTACKS,MISC,EVS}, {GROWTH,EVS,ATTACKS,MISC},
        {GROWTH,EVS,MISC,ATTACKS}, {GROWTH,MISC,ATTACKS,EVS}, {GROWTH,MISC,EVS,ATTACKS},
        {ATTACKS,GROWTH,EVS,MISC}, {ATTACKS,GROWTH,MISC,EVS}, {ATTACKS,EVS,GROWTH,MISC},
        {ATTACKS,EVS,MISC,GROWTH}, {ATTACKS,MISC,GROWTH,EVS}, {ATTACKS,MISC,EVS,GROWTH},
        {EVS,GROWTH,ATTACKS,MISC}, {EVS,GROWTH,MISC,ATTACKS}, {EVS,ATTACKS,GROWTH,MISC},
        {EVS,ATTACKS,MISC,GROWTH}, {EVS,MISC,GROWTH,ATTACKS}, {EVS,MISC,ATTACKS,GROWTH},
        {MISC,GROWTH,ATTACKS,EVS}, {MISC,GROWTH,EVS,ATTACKS}, {MISC,ATTACKS,GROWTH,EVS},
        {MISC,ATTACKS,EVS,GROWTH}, {MISC,EVS,GROWTH,ATTACKS}, {MISC,EVS,ATTACKS,GROWTH},
    };

    // ---- the decoded fields ----------------------------------------------

    private boolean checksumValid = true;
    public boolean checksumValid() { return checksumValid; }

    public int personality;
    public int otId;
    public String nickname = "";
    public String otName = "";
    /**
     * The trainer name's stored bytes, when they are known exactly; {@link #encode}
     * then writes these rather than {@link #otName}. The game decides whose a
     * Pokémon is by comparing them with the player's own name byte for byte
     * (IsOtherTrainer), and text cannot carry every byte a name can hold.
     * Decoding leaves this unset: only a gift built from a save's trainer has it.
     */
    byte[] otNameBytes;
    public int language = 2;          // English
    public int markings;
    /**
     * Byte 0x13, the game's own flag bitfield: bit 0 bad egg, bit 1 has
     * species, bit 2 is egg, bit 3 blocked from Pokémon Box, the rest unused.
     *
     * An earlier version of this class called it an "egg-name flag" and wrote
     * it as zero. The game sets bit 1 whenever it gives a Pokémon a species,
     * so a record with it clear is one the game never wrote. {@link #encode}
     * now derives bits 1 and 2 from the species and the substructure's egg
     * bit, so they can never disagree with the data they describe; bit 0 and
     * the high bits are the game's to set and are carried through unchanged.
     */
    public int flags;

    public int species, heldItem, experience, ppBonuses, friendship;
    public final int[] moves = new int[4];
    public final int[] pp = new int[4];
    public final int[] evs = new int[6];
    public final int[] contest = new int[6];
    public int pokerus, metLocation, origins, ivsEggAbility, ribbons;

    /** Party-only fields; meaningless for a boxed Pokémon. */
    public int status, level, mail, currentHp, maxHp, attack, defense, speed, spAttack, spDefense;

    public Gen3Pokemon() { }

    // ---- the two operations that must be exact inverses -------------------

    /** Reads an 80-byte box entry (or the first 80 bytes of a party entry). */
    public static Gen3Pokemon decode(byte[] bytes, int at) {
        var buffer = view(bytes, at, BOX_SIZE);
        var p = new Gen3Pokemon();
        p.personality = buffer.getInt(0x00);
        p.otId = buffer.getInt(0x04);
        p.nickname = Gen3Text.read(bytes, at + 0x08, 10);
        p.language = buffer.get(0x12) & 0xFF;
        p.flags = buffer.get(0x13) & 0xFF;
        p.otName = Gen3Text.read(bytes, at + 0x14, 7);
        p.markings = buffer.get(0x1B) & 0xFF;

        var data = decrypt(bytes, at + 0x20, p.personality, p.otId);
        p.checksumValid = (buffer.getShort(0x1C) & 0xffff) == checksum(data);
        p.readSubstructures(data);
        return p;
    }

    /** Whether the stored checksum agrees with the data it covers. */
    public static boolean intact(byte[] bytes, int at) {
        var buffer = view(bytes, at, BOX_SIZE);
        int personality = buffer.getInt(0x00), otId = buffer.getInt(0x04);
        int stored = buffer.getShort(0x1C) & 0xFFFF;
        return stored == checksum(decrypt(bytes, at + 0x20, personality, otId));
    }

    /** Writes this Pokémon as an 80-byte box entry. */
    public byte[] encode() {
        var out = new byte[BOX_SIZE];
        var buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x00, personality);
        buffer.putInt(0x04, otId);
        Gen3Text.write(nickname, out, 0x08, 10);
        buffer.put(0x12, (byte) language);
        // Bits 1 and 2 are derived, as SetBoxMonData derives them in the game.
        int derived = (flags & ~0x06) | (species != 0 ? 0x02 : 0) | (isEgg() ? 0x04 : 0);
        buffer.put(0x13, (byte) derived);
        if (otNameBytes != null) System.arraycopy(otNameBytes, 0, out, 0x14, OT_NAME_BYTES);
        else Gen3Text.write(otName, out, 0x14, OT_NAME_BYTES);
        buffer.put(0x1B, (byte) markings);

        var data = writeSubstructures();
        buffer.putShort(0x1C, (short) checksum(data));
        buffer.putShort(0x1E, (short) 0);
        encrypt(data, personality, otId);
        System.arraycopy(data, 0, out, 0x20, DATA_SIZE);
        return out;
    }

    /** Writes this Pokémon as a 100-byte party entry, stats included. */
    public byte[] encodeForParty() {
        var out = new byte[PARTY_SIZE];
        System.arraycopy(encode(), 0, out, 0, BOX_SIZE);
        var buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x50, status);
        buffer.put(0x54, (byte) level);
        buffer.put(0x55, (byte) mail);
        buffer.putShort(0x56, (short) currentHp);
        buffer.putShort(0x58, (short) maxHp);
        buffer.putShort(0x5A, (short) attack);
        buffer.putShort(0x5C, (short) defense);
        buffer.putShort(0x5E, (short) speed);
        buffer.putShort(0x60, (short) spAttack);
        buffer.putShort(0x62, (short) spDefense);
        return out;
    }

    /** Reads a 100-byte party entry. */
    public static Gen3Pokemon decodeFromParty(byte[] bytes, int at) {
        var p = decode(bytes, at);
        var buffer = view(bytes, at, PARTY_SIZE);
        p.status = buffer.getInt(0x50);
        p.level = buffer.get(0x54) & 0xFF;
        p.mail = buffer.get(0x55) & 0xFF;
        p.currentHp = buffer.getShort(0x56) & 0xFFFF;
        p.maxHp = buffer.getShort(0x58) & 0xFFFF;
        p.attack = buffer.getShort(0x5A) & 0xFFFF;
        p.defense = buffer.getShort(0x5C) & 0xFFFF;
        p.speed = buffer.getShort(0x5E) & 0xFFFF;
        p.spAttack = buffer.getShort(0x60) & 0xFFFF;
        p.spDefense = buffer.getShort(0x62) & 0xFFFF;
        return p;
    }

    /** A little-endian window onto {@code size} bytes from {@code at}, indexed from zero. */
    private static ByteBuffer view(byte[] bytes, int at, int size) {
        return ByteBuffer.wrap(bytes, at, size).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ---- encryption and shuffling ----------------------------------------

    /** The 48 data bytes, decrypted but still in their shuffled order. */
    static byte[] decrypt(byte[] bytes, int at, int personality, int otId) {
        var data = new byte[DATA_SIZE];
        System.arraycopy(bytes, at, data, 0, DATA_SIZE);
        encrypt(data, personality, otId);   // XOR is its own inverse
        return data;
    }

    /** XORs the 48 bytes with the key, word by word. Applying it twice undoes it. */
    static void encrypt(byte[] data, int personality, int otId) {
        int key = otId ^ personality;
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int word = 0; word < DATA_SIZE / 4; word++)
            buffer.putInt(word * 4, buffer.getInt(word * 4) ^ key);
    }

    /** Sum of the decrypted data as 24 unsigned halfwords, truncated to 16 bits. */
    static int checksum(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (int half = 0; half < DATA_SIZE / 2; half++) sum += buffer.getShort(half * 2) & 0xFFFF;
        return sum & 0xFFFF;
    }

    /** The cartridge treats personality as u32, including when Java stores a negative int. */
    static int offsetOf(int personality, int substructure) {
        int[] order = ORDERS[Integer.remainderUnsigned(personality, ORDERS.length)];
        for (int slot = 0; slot < order.length; slot++)
            if (order[slot] == substructure) return slot * SUBSTRUCTURE;
        throw new IllegalArgumentException("No such substructure " + substructure);
    }

    private void readSubstructures(byte[] data) {
        var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int g = offsetOf(personality, GROWTH), a = offsetOf(personality, ATTACKS),
            e = offsetOf(personality, EVS), m = offsetOf(personality, MISC);

        species = b.getShort(g) & 0xFFFF;
        heldItem = b.getShort(g + 2) & 0xFFFF;
        experience = b.getInt(g + 4);
        ppBonuses = b.get(g + 8) & 0xFF;
        friendship = b.get(g + 9) & 0xFF;

        for (int i = 0; i < 4; i++) moves[i] = b.getShort(a + i * 2) & 0xFFFF;
        for (int i = 0; i < 4; i++) pp[i] = b.get(a + 8 + i) & 0xFF;

        for (int i = 0; i < 6; i++) evs[i] = b.get(e + i) & 0xFF;
        for (int i = 0; i < 6; i++) contest[i] = b.get(e + 6 + i) & 0xFF;

        pokerus = b.get(m) & 0xFF;
        metLocation = b.get(m + 1) & 0xFF;
        origins = b.getShort(m + 2) & 0xFFFF;
        ivsEggAbility = b.getInt(m + 4);
        ribbons = b.getInt(m + 8);
    }

    private byte[] writeSubstructures() {
        var data = new byte[DATA_SIZE];
        var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int g = offsetOf(personality, GROWTH), a = offsetOf(personality, ATTACKS),
            e = offsetOf(personality, EVS), m = offsetOf(personality, MISC);

        b.putShort(g, (short) species);
        b.putShort(g + 2, (short) heldItem);
        b.putInt(g + 4, experience);
        b.put(g + 8, (byte) ppBonuses);
        b.put(g + 9, (byte) friendship);

        for (int i = 0; i < 4; i++) b.putShort(a + i * 2, (short) moves[i]);
        for (int i = 0; i < 4; i++) b.put(a + 8 + i, (byte) pp[i]);

        for (int i = 0; i < 6; i++) b.put(e + i, (byte) evs[i]);
        for (int i = 0; i < 6; i++) b.put(e + 6 + i, (byte) contest[i]);

        b.put(m, (byte) pokerus);
        b.put(m + 1, (byte) metLocation);
        b.putShort(m + 2, (short) origins);
        b.putInt(m + 4, ivsEggAbility);
        b.putInt(m + 8, ribbons);
        return data;
    }

    // ---- what the game derives rather than stores -------------------------

    /** The National Dex number, or 0 for an empty record or a species this build does not know. */
    public int nationalDex() { return species == 0 ? 0 : SpeciesIds.nationalOf(species); }

    /**
     * The level. A party record stores one; a boxed record does not, and the
     * game derives it from experience on the species' own curve each time.
     */
    public int level() { return level > 0 ? level : levelFromExperience(); }

    public int levelFromExperience() {
        int national = nationalDex();
        return national == 0 ? 1 : Experience.levelFor(SpeciesIds.growthOf(national), experience);
    }

    public static final int MALE = 0x00, FEMALE = 0xFE, GENDERLESS = 0xFF;

    /** Gender from the personality against the species' ratio, as GetGenderFromSpeciesAndPersonality decides it. */
    public int gender() {
        int national = nationalDex();
        if (national == 0) return GENDERLESS;
        int ratio = SpeciesIds.genderRatioOf(national);
        if (ratio == MALE || ratio == FEMALE || ratio == GENDERLESS) return ratio;
        return ratio > (personality & 0xFF) ? FEMALE : MALE;
    }

    // ---- joining the party -------------------------------------------------

    public static final int SHEDINJA = 292;
    /** A party record's mail byte, and what it holds when there is no letter. */
    static final int MAIL_AT = 0x55, MAIL_NONE = 0xFF;

    /**
     * The party record the game makes when a boxed Pokémon joins the party.
     *
     * BoxMonToMon: status cleared, no mail, then CalculateMonStats with no
     * previous HP — which leaves it at full health. A Pokémon placed in the
     * party here therefore arrives exactly as one withdrawn at a Pokémon Center
     * PC would, with the stats the game itself would show.
     */
    public static byte[] toParty(byte[] bytes, int at) {
        var mon = decode(bytes, at);
        int national = mon.nationalDex();
        if (national == 0) throw new IllegalArgumentException("Only a known species can join the party.");
        int level = mon.levelFromExperience();
        int[] stats = stats(national, level, mon.ivs(), mon.evs, mon.nature());
        var out = new byte[PARTY_SIZE];
        System.arraycopy(bytes, at, out, 0, BOX_SIZE);
        var b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x50, 0);
        b.put(0x54, (byte) level);
        b.put(MAIL_AT, (byte) MAIL_NONE);
        b.putShort(0x56, (short) stats[0]);
        for (int i = 0; i < 6; i++) b.putShort(0x58 + i * 2, (short) stats[i]);
        return out;
    }

    /**
     * The box record the game makes when it places a Pokémon in a box.
     *
     * SetPlacedMonData runs BoxMonRestorePP before SetBoxMonAt, so every
     * Pokémon put in a box — deposited, or dropped there by MOVE POKÉMON — has
     * each move's PP refilled to what its PP Ups allow (CalculatePPWithBonus).
     * The record is patched in place: only the PP bytes and the checksum can
     * change, and every other byte the game wrote stays as it was, where
     * {@link #encode} would derive some of them afresh.
     *
     * A record whose checksum fails is copied unchanged, as the game refills
     * nothing in one either; so is the PP of a move this build has no PP for.
     */
    public static byte[] toBox(byte[] bytes, int at) {
        byte[] out = Arrays.copyOfRange(bytes, at, at + BOX_SIZE);
        if (!intact(out, 0)) return out;
        var head = view(out, 0, BOX_SIZE);
        int personality = head.getInt(0x00), otId = head.getInt(0x04);
        var data = decrypt(out, 0x20, personality, otId);
        var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int g = offsetOf(personality, GROWTH), a = offsetOf(personality, ATTACKS);
        int bonuses = b.get(g + 8) & 0xFF;
        for (int i = 0; i < 4; i++) {
            int base = Learnsets.pp(b.getShort(a + i * 2) & 0xFFFF);
            if (base == 0) continue;           // no move there, or one past this build's table
            int ups = (bonuses >>> (2 * i)) & 3;
            b.put(a + 8 + i, (byte) (base + base * 20 * ups / 100));
        }
        head.putShort(0x1C, (short) checksum(data));
        encrypt(data, personality, otId);
        System.arraycopy(data, 0, out, 0x20, DATA_SIZE);
        return out;
    }

    /**
     * Stats as CalculateMonStats computes them, in the order the party record
     * stores them: HP, Attack, Defense, Speed, Sp. Atk, Sp. Def. Every division
     * truncates where the game's does.
     */
    static int[] stats(int national, int level, int[] ivs, int[] evs, int nature) {
        int[] base = {BaseStats.hp(national), BaseStats.attack(national), BaseStats.defense(national),
            BaseStats.speed(national), BaseStats.specialAttack(national), BaseStats.specialDefense(national)};
        var out = new int[6];
        out[0] = national == SHEDINJA ? 1 : ((2 * base[0] + ivs[0] + evs[0] / 4) * level) / 100 + level + 10;
        for (int stat = 1; stat < 6; stat++) {
            int n = ((2 * base[stat] + ivs[stat] + evs[stat] / 4) * level) / 100 + 5;
            out[stat] = byNature(nature, n & 0xFFFF, stat);
        }
        return out;
    }

    /**
     * ModifyStatByNature. Stat indices 1 to 5 are Attack, Defense, Speed,
     * Sp. Atk and Sp. Def; nature n raises stat n / 5 + 1 and lowers
     * n % 5 + 1, which is exactly gNatureStatTable.
     *
     * The cartridge keeps the product in sixteen bits (a u16 in pokeemerald
     * unless BUGFIX is defined), so a raised stat above 595 or a lowered one
     * above 728 would wrap there. No Emerald stat reaches that — the highest a
     * nature touches is Shuckle's 559 Defense — so Yoru keeps the whole
     * product, as the BUGFIX build does, and agrees with the game on every
     * stat the game can produce.
     */
    static int byNature(int nature, int stat, int statIndex) {
        int raised = nature / 5 + 1, lowered = nature % 5 + 1;
        if (raised == lowered) return stat;
        if (statIndex == raised) return (stat * 110) / 100;
        if (statIndex == lowered) return (stat * 90) / 100;
        return stat;
    }

    // ---- the packed IV word ----------------------------------------------

    /** Individual values, in the order HP, Attack, Defense, Speed, SpAtk, SpDef. */
    public int[] ivs() {
        var out = new int[6];
        for (int i = 0; i < 6; i++) out[i] = (ivsEggAbility >>> (i * 5)) & 0x1F;
        return out;
    }

    public void setIvs(int[] values) {
        int packed = ivsEggAbility & ~0x3FFFFFFF;
        for (int i = 0; i < 6; i++) packed |= (values[i] & 0x1F) << (i * 5);
        ivsEggAbility = packed;
    }

    public boolean isEgg() { return ((ivsEggAbility >>> 30) & 1) == 1; }
    /** Set by the game when a record fails its checksum on access. */
    public boolean badEgg() { return (flags & 0x01) != 0; }

    /** True when the personality makes this one shiny for its trainer. */
    public boolean shiny() {
        int trainer = otId & 0xFFFF, secret = otId >>> 16;
        int high = personality >>> 16, low = personality & 0xFFFF;
        return (trainer ^ secret ^ high ^ low) < 8;
    }

    /** Nature is the personality modulo 25 — it is not stored separately. */
    public int nature() { return Integer.remainderUnsigned(personality, 25); }
}
