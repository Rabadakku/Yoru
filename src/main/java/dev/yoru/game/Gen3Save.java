package dev.yoru.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A Gen 3 battery save, read the way Emerald itself reads it.
 *
 * <h2>Layout</h2>
 * <pre>
 *   slot 1   0x00000  14 sectors of 4096
 *   slot 2   0x0E000  14 sectors of 4096
 *   0x1C000           Hall of Fame, Trainer Hill, recorded battle — never touched here
 *   sector: data, then
 *     0x0FF4 u16 section id
 *     0x0FF6 u16 checksum
 *     0x0FF8 u32 signature 0x08012025
 *     0x0FFC u32 save counter
 * </pre>
 *
 * <h2>Which slot the game loads</h2>
 * Taken from pokeemerald's {@code GetSaveValidStatus} and
 * {@code CopySaveSlotData} rather than inferred, because this decides whose
 * progress a player sees:
 * <ul>
 * <li>A slot is OK when all fourteen section ids pass their signature and
 *     checksum; ERROR when something is signed but not all fourteen pass;
 *     EMPTY when nothing is signed.</li>
 * <li>A slot's counter is the counter of the last valid sector read. The game
 *     never compares counters across sectors.</li>
 * <li>With both slots OK the larger counter wins, compared unsigned — except
 *     that 0 beats 0xFFFFFFFF, which is how the counter wraps. With one OK,
 *     that one wins.</li>
 * <li>The game then loads physical slot <b>counter % 2</b>. Normally that is
 *     where the counter came from, because the game alternates slots as it
 *     counts. When it is not, the game loads the other slot — and so does
 *     this, refusing outright if that slot is not a complete save.</li>
 * </ul>
 * An earlier version of this class rejected slots whose sectors disagreed
 * about the counter and fell back to the older slot. The game does neither, so
 * Yoru could have shown different progress than the game would load. Those
 * saves are now read exactly as the game reads them, and refused for editing.
 *
 * <h2>How changes are written</h2>
 * The slot that was loaded is never modified. A changed save is written into
 * the other slot with the counter one higher — exactly as the game's own next
 * save would be — so the file always still holds the save it replaced, and the
 * game's fallback protects a player from Yoru as it protects them from a
 * console switched off mid-save.
 *
 * <h2>Sections rotate, and checksum different lengths</h2>
 * A section's position is not its identity, so everything here indexes by the
 * id at 0x0FF4. Each section checksums its own number of bytes; the table was
 * verified against a real Emerald save on 2026-09-10. A wrong entry raises
 * nothing — it writes a save the game rejects.
 */
public final class Gen3Save {

    public static final int SIZE = 131072, SECTION = 4096, SECTIONS = 14,
        SLOT = SECTION * SECTIONS, SIGNATURE = 0x08012025;
    static final int ID_AT = 0x0FF4, CHECKSUM_AT = 0x0FF6, SIGNATURE_AT = 0x0FF8, COUNTER_AT = 0x0FFC;

    /** Bytes each section checksums, indexed by section id. Verified, not recalled. */
    static final int[] CHECKSUMMED = {
        3884, 3968, 3968, 3968, 3848, 3968, 3968,
        3968, 3968, 3968, 3968, 3968, 3968, 2000
    };

    /** The PC spans sections 5 to 13 — 33,744 bytes once concatenated. */
    public static final int STORAGE_FIRST = 5, STORAGE_LAST = 13;
    public static final int STORAGE_SIZE = 8 * 3968 + 2000;
    public static final int BOXES = 14, PER_BOX = 30, ENTRY = Gen3Pokemon.BOX_SIZE;
    /** After the Pokémon: fourteen names of eight characters plus a terminator, then a wallpaper byte each. */
    static final int BOX_NAMES_AT = 4 + BOXES * PER_BOX * ENTRY, BOX_NAME_BYTES = 9,
        WALLPAPERS_AT = BOX_NAMES_AT + BOXES * BOX_NAME_BYTES;
    public static final int BOX_NAME_LENGTH = 8, WALLPAPERS = 16;

    /** Party lives in section 1: a count byte, padding, then six 100-byte entries. */
    public static final int PARTY_COUNT_AT = 0x234, PARTY_AT = 0x238, PARTY_LIMIT = 6;
    /** Where the sixth party entry ends; nothing past it in section 1 is the party's. */
    static final int PARTY_END = PARTY_AT + PARTY_LIMIT * Gen3Pokemon.PARTY_SIZE;

    /**
     * Offsets inside SaveBlock1 (sections 1 to 4, 3968 bytes each) and
     * SaveBlock2 (section 0), from pokeemerald's include/global.h.
     */
    static final int SB1_PER_SECTION = 3968;
    static final int MONEY_AT = 0x490, SEEN1_AT = 0x988, FLAGS_AT = 0x1270, SEEN2_AT = 0x3B24;
    static final int DEX_OWNED_AT = 0x28, DEX_SEEN_AT = 0x5C, ENCRYPTION_KEY_AT = 0xAC, DEX_BYTES = 52;

    /** Flag ids, from include/constants/flags.h. */
    public static final int FLAG_SYS_POKEMON_GET = 0x860, FLAG_SYS_GAME_CLEAR = 0x864,
        FLAG_BADGE01_GET = 0x867;

    public enum Status { OK, ERROR, EMPTY }

    /** What the game concludes about one slot. */
    record Scan(Status status, long counter, boolean consistent) { }

    private final byte[] original;
    private final int slot;
    private final long counter;
    private final boolean consistent;
    private final boolean aligned;
    private final Status other;
    /** Working copies of the loaded slot's sectors, indexed by section id. */
    private final byte[][] sections = new byte[SECTIONS][];
    private final int[] positionOf = new int[SECTIONS];

    private Gen3Save(byte[] raw, int slot, long counter, boolean consistent, boolean aligned, Status other) {
        this.original = raw.clone();
        this.slot = slot;
        this.counter = counter;
        this.consistent = consistent;
        this.aligned = aligned;
        this.other = other;
        for (int position = 0; position < SECTIONS; position++) {
            int at = slot * SLOT + position * SECTION;
            int id = u16(raw, at + ID_AT);
            sections[id] = Arrays.copyOfRange(raw, at, at + SECTION);
            positionOf[id] = position;
        }
    }

    /** Why the game would not load a save, in terms a page can explain. */
    public enum Unreadable { WRONG_SIZE, NEVER_SAVED, DAMAGED, INCOMPLETE_SLOT }

    /**
     * A refusal from {@link #read}. Still an IllegalArgumentException, so every
     * caller that already catches one is unchanged; the reason is for pages.
     */
    public static final class UnreadableSave extends IllegalArgumentException {
        private final Unreadable reason;
        UnreadableSave(Unreadable reason, String message) { super(message); this.reason = reason; }
        public Unreadable reason() { return reason; }
    }

    /**
     * Reads a save and selects the slot the game would load.
     *
     * Refuses a file the game would not load completely: the wrong size, no
     * sound slot at all, or a counter that points at a slot that is not whole.
     */
    public static Gen3Save read(byte[] raw) {
        if (raw.length != SIZE)
            throw new UnreadableSave(Unreadable.WRONG_SIZE, "A Gen 3 save is " + SIZE + " bytes, got " + raw.length);
        Scan first = scan(raw, 0), second = scan(raw, 1);
        long chosen;
        if (first.status() == Status.OK && second.status() == Status.OK) chosen = newer(first.counter(), second.counter());
        else if (first.status() == Status.OK) chosen = first.counter();
        else if (second.status() == Status.OK) chosen = second.counter();
        else if (first.status() == Status.EMPTY && second.status() == Status.EMPTY)
            throw new UnreadableSave(Unreadable.NEVER_SAVED, "This save is empty: the game has never saved to it.");
        else throw new UnreadableSave(Unreadable.DAMAGED, "Neither save slot holds a complete, checksummed save.");
        int slot = (int) (chosen % 2);
        Scan loaded = slot == 0 ? first : second;
        if (loaded.status() != Status.OK)
            throw new UnreadableSave(Unreadable.INCOMPLETE_SLOT, "The game would load save slot " + (slot + 1)
                + ", which is not a complete save, so it cannot be read reliably.");
        return new Gen3Save(raw, slot, chosen, loaded.consistent(), loaded.counter() == chosen,
            slot == 0 ? second.status() : first.status());
    }

    /** The counter the game settles on when both slots are sound. */
    static long newer(long a, long b) {
        if ((a == 0xFFFFFFFFL && b == 0) || (a == 0 && b == 0xFFFFFFFFL))
            return ((a + 1) & 0xFFFFFFFFL) < ((b + 1) & 0xFFFFFFFFL) ? b : a;
        return a < b ? b : a;
    }

    /** One slot, judged as GetSaveValidStatus judges it. */
    static Scan scan(byte[] raw, int slot) {
        boolean signed = false, consistent = true;
        int valid = 0;
        long counter = 0, first = -1;
        for (int position = 0; position < SECTIONS; position++) {
            int at = slot * SLOT + position * SECTION;
            if (u32(raw, at + SIGNATURE_AT) != SIGNATURE) continue;
            signed = true;
            int id = u16(raw, at + ID_AT);
            // The game would index past its own table with an id this large.
            // Such a sector is treated as damaged rather than guessed at.
            if (id >= SECTIONS) continue;
            if (u16(raw, at + CHECKSUM_AT) != checksum(raw, at, CHECKSUMMED[id])) continue;
            long sectorCounter = u32(raw, at + COUNTER_AT);
            if (first < 0) first = sectorCounter;
            else if (sectorCounter != first) consistent = false;
            counter = sectorCounter;
            valid |= 1 << id;
        }
        Status status = !signed ? Status.EMPTY : valid == (1 << SECTIONS) - 1 ? Status.OK : Status.ERROR;
        return new Scan(status, counter, consistent);
    }

    /**
     * Why changing this save would be unsafe, or null when it is safe.
     *
     * Both refusals describe saves the game loads without complaint but where
     * a change could land somewhere other than where the game will look. One
     * in-game save straightens either out.
     */
    public String whyNotEditable() {
        if (!consistent) return "This save looks like it was interrupted part-way through saving. "
            + "Open the game, save once, and try again.";
        if (!aligned) return "This save's two slots are out of step, so the game might not load a change "
            + "Yoru made. Open the game, save once, and try again.";
        return null;
    }

    public long counter() { return counter; }
    /** The physical slot the game loads, 0 or 1. */
    public int loadedSlot() { return slot; }
    /** What the game makes of the slot it is not loading. ERROR means it will warn of a damaged save. */
    public Status otherSlot() { return other; }

    /** The working copy of one section. Changes go through the methods below. */
    byte[] section(int id) { return sections[id]; }

    /** True when any section's data differs from what was read. */
    public boolean changed() {
        for (int id = 0; id < SECTIONS; id++) {
            int at = slot * SLOT + positionOf[id] * SECTION;
            if (!Arrays.equals(sections[id], 0, CHECKSUMMED[id], original, at, at + CHECKSUMMED[id])) return true;
        }
        return false;
    }

    /**
     * The save as a file. Unchanged, it is exactly what was read; changed, the
     * changes are the game's next save, in the other slot with the counter one
     * higher, and the loaded slot is left as it was.
     */
    public byte[] bytes() {
        if (!changed()) return original.clone();
        String refusal = whyNotEditable();
        if (refusal != null) throw new IllegalStateException(refusal);
        byte[] out = original.clone();
        int target = 1 - slot;
        long next = (counter + 1) & 0xFFFFFFFFL;
        for (int id = 0; id < SECTIONS; id++) {
            byte[] sector = sections[id].clone();
            putU16(sector, ID_AT, id);
            putU32(sector, SIGNATURE_AT, SIGNATURE);
            putU32(sector, COUNTER_AT, next);
            putU16(sector, CHECKSUM_AT, checksum(sector, 0, CHECKSUMMED[id]));
            System.arraycopy(sector, 0, out, target * SLOT + positionOf[id] * SECTION, SECTION);
        }
        // Not trusted: read back the way the game will, and it has to choose
        // exactly the slot just written.
        var reread = read(out);
        if (reread.slot != target || reread.counter != next)
            throw new IllegalStateException("The written save would not load as intended.");
        return out;
    }

    // ---- who the save belongs to ------------------------------------------

    /**
     * The player's own identity, as the save records it.
     *
     * A Pokémon written with a different trainer id or name is a traded one:
     * the game marks it as met elsewhere and, past a badge threshold, it
     * disobeys. So anything Yoru delivers carries these exact values.
     *
     * {@code storedName} is the name as a Pokémon record stores it, in seven
     * bytes. The game compares those with the player's own byte for byte
     * (IsOtherTrainer), and {@code name} cannot stand in for them — a byte the
     * table has no character for reads as '?' — so a gift copies these.
     */
    public record Trainer(String name, int gender, int publicId, int secretId, int playTimeMinutes,
                          byte[] storedName) {
        public Trainer {
            if (storedName.length != Gen3Pokemon.OT_NAME_BYTES)
                throw new IllegalArgumentException("A stored trainer name is " + Gen3Pokemon.OT_NAME_BYTES + " bytes");
            storedName = storedName.clone();
        }

        /** A trainer no save holds: the name is stored as the game's alphabet writes it. */
        public Trainer(String name, int gender, int publicId, int secretId, int playTimeMinutes) {
            this(name, gender, publicId, secretId, playTimeMinutes, Gen3Text.bytes(name, Gen3Pokemon.OT_NAME_BYTES));
        }

        @Override public byte[] storedName() { return storedName.clone(); }

        // A record compares an array by reference; two reads of one save are one trainer.
        @Override public boolean equals(Object o) {
            return o instanceof Trainer t && name.equals(t.name) && gender == t.gender && publicId == t.publicId
                && secretId == t.secretId && playTimeMinutes == t.playTimeMinutes
                && Arrays.equals(storedName, t.storedName);
        }

        @Override public int hashCode() {
            return Objects.hash(name, gender, publicId, secretId, playTimeMinutes) * 31 + Arrays.hashCode(storedName);
        }

        /** The full 32-bit value a Pokémon record stores as its OT id. */
        public int otId() { return (secretId << 16) | (publicId & 0xFFFF); }
    }

    public Trainer trainer() {
        byte[] s = sections[0];
        // The game copies the first seven bytes of the player's name into each
        // Pokémon it gives them (CreateBoxMon), so they are copied, not re-encoded.
        return new Trainer(Gen3Text.read(s, 0, 8), s[0x08] & 0xFF, u16(s, 0x0A), u16(s, 0x0C),
            u16(s, 0x0E) * 60 + (s[0x10] & 0xFF), Gen3Text.copy(s, 0, Gen3Pokemon.OT_NAME_BYTES));
    }

    /** Money, stored XOR the save's own encryption key. */
    public long money() {
        return (u32(sections[1], MONEY_AT) ^ u32(sections[0], ENCRYPTION_KEY_AT)) & 0xFFFFFFFFL;
    }

    // ---- progress -----------------------------------------------------------

    int sb1Byte(int offset) { return sections[1 + offset / SB1_PER_SECTION][offset % SB1_PER_SECTION] & 0xFF; }

    private void sb1Byte(int offset, int value) {
        sections[1 + offset / SB1_PER_SECTION][offset % SB1_PER_SECTION] = (byte) value;
    }

    /** One of the game's event flags. */
    public boolean flag(int id) { return (sb1Byte(FLAGS_AT + id / 8) >> (id % 8) & 1) != 0; }

    public boolean badge(int index) {
        if (index < 0 || index >= 8) throw new IllegalArgumentException("No badge " + index);
        return flag(FLAG_BADGE01_GET + index);
    }

    public int badges() {
        int n = 0;
        for (int i = 0; i < 8; i++) if (badge(i)) n++;
        return n;
    }

    /** Whether the player has received their first Pokémon from Professor Birch's bag. */
    public boolean hasStarter() { return flag(FLAG_SYS_POKEMON_GET); }

    /** Whether the player has entered the Hall of Fame. */
    public boolean gameClear() { return flag(FLAG_SYS_GAME_CLEAR); }

    // ---- the Pokédex --------------------------------------------------------

    public boolean owned(int national) { return dexBit(DEX_OWNED_AT, national); }
    public boolean seen(int national) { return dexBit(DEX_SEEN_AT, national); }

    public int ownedCount() {
        int n = 0;
        for (int national = 1; national <= SpeciesNames.COUNT; national++) if (owned(national)) n++;
        return n;
    }

    private boolean dexBit(int at, int national) {
        int index = checkDex(national) - 1;
        return (sections[0][at + index / 8] >> (index % 8) & 1) != 0;
    }

    /**
     * Records a species as seen and caught, as the game's own two setters do:
     * FLAG_SET_SEEN writes all three seen copies, FLAG_SET_CAUGHT the owned bit.
     * Writing only one copy would be undone the next time the game checked it,
     * because GetSetPokedexFlag clears a bit the copies disagree about.
     */
    public void register(int national) {
        int index = checkDex(national) - 1, mask = 1 << (index % 8), at = index / 8;
        sections[0][DEX_OWNED_AT + at] |= (byte) mask;
        sections[0][DEX_SEEN_AT + at] |= (byte) mask;
        sb1Byte(SEEN1_AT + at, sb1Byte(SEEN1_AT + at) | mask);
        sb1Byte(SEEN2_AT + at, sb1Byte(SEEN2_AT + at) | mask);
    }

    private static int checkDex(int national) {
        if (national < 1 || national > SpeciesNames.COUNT)
            throw new IllegalArgumentException("No species " + national + ".");
        return national;
    }

    // ---- the party ------------------------------------------------------------

    public int partyCount() { return Math.min(PARTY_LIMIT, sections[1][PARTY_COUNT_AT] & 0xFF); }

    public List<Gen3Pokemon> party() {
        var out = new ArrayList<Gen3Pokemon>();
        for (int i = 0; i < partyCount(); i++)
            out.add(Gen3Pokemon.decodeFromParty(sections[1], PARTY_AT + i * Gen3Pokemon.PARTY_SIZE));
        return out;
    }

    /** One party member's 100 bytes, copied. */
    public byte[] partyRecord(int index) {
        if (index < 0 || index >= partyCount()) throw new IllegalArgumentException("No party member " + index);
        int at = PARTY_AT + index * Gen3Pokemon.PARTY_SIZE;
        return Arrays.copyOfRange(sections[1], at, at + Gen3Pokemon.PARTY_SIZE);
    }

    public List<byte[]> partyRecords() {
        var out = new ArrayList<byte[]>();
        for (int i = 0; i < partyCount(); i++) out.add(partyRecord(i));
        return out;
    }

    /**
     * Replaces the party.
     *
     * An entry the party no longer reaches is emptied as the game's ZeroMonData
     * empties it: zeros, with MAIL_NONE in the mail byte. Entries past both the
     * old party and the new one are left exactly as the game left them, so a
     * change that keeps the party's size touches only the members it replaces.
     */
    public void party(List<byte[]> records) {
        if (records.size() > PARTY_LIMIT) throw new IllegalArgumentException("A party holds at most " + PARTY_LIMIT);
        for (var record : records)
            if (record.length != Gen3Pokemon.PARTY_SIZE)
                throw new IllegalArgumentException("A party record is " + Gen3Pokemon.PARTY_SIZE + " bytes");
        byte[] s = sections[1];
        int previous = partyCount();
        s[PARTY_COUNT_AT] = (byte) records.size();
        for (int i = 0; i < Math.max(previous, records.size()); i++) {
            int at = PARTY_AT + i * Gen3Pokemon.PARTY_SIZE;
            if (i < records.size()) System.arraycopy(records.get(i), 0, s, at, Gen3Pokemon.PARTY_SIZE);
            else {
                Arrays.fill(s, at, at + Gen3Pokemon.PARTY_SIZE, (byte) 0);
                s[at + Gen3Pokemon.MAIL_AT] = (byte) Gen3Pokemon.MAIL_NONE;
            }
        }
    }

    // ---- the PC -----------------------------------------------------------

    /**
     * The storage system, gathered from the sections it is split across.
     *
     * It does not fit in one 4096-byte section, so it is spread over nine and
     * has to be stitched together before anything in it can be read — a box
     * boundary can fall inside a section, and a Pokémon can straddle two.
     */
    public byte[] storage() {
        var out = new byte[STORAGE_SIZE];
        int written = 0;
        for (int id = STORAGE_FIRST; id <= STORAGE_LAST; id++) {
            int take = Math.min(CHECKSUMMED[id], out.length - written);
            System.arraycopy(sections[id], 0, out, written, take);
            written += take;
        }
        return out;
    }

    /** Writes the storage system back into the sections it is split across. */
    public void storage(byte[] storage) {
        if (storage.length != STORAGE_SIZE)
            throw new IllegalArgumentException("Storage is " + STORAGE_SIZE + " bytes, got " + storage.length);
        int read = 0;
        for (int id = STORAGE_FIRST; id <= STORAGE_LAST; id++) {
            int take = Math.min(CHECKSUMMED[id], storage.length - read);
            System.arraycopy(storage, read, sections[id], 0, take);
            read += take;
        }
    }

    /** Which box the PC last had open. */
    public static int currentBox(byte[] storage) { return (storage[0] & 0xFF) % BOXES; }

    /** Byte offset of one box slot inside the stitched storage block. */
    public static int slotOffset(int box, int index) {
        if (box < 0 || box >= BOXES) throw new IllegalArgumentException("No box " + box);
        if (index < 0 || index >= PER_BOX) throw new IllegalArgumentException("No slot " + index);
        return 4 + (box * PER_BOX + index) * ENTRY;
    }

    /** The Pokémon in a box slot, or null when it is empty. */
    public Gen3Pokemon boxed(byte[] storage, int box, int index) {
        int at = slotOffset(box, index);
        return empty(storage, at) ? null : Gen3Pokemon.decode(storage, at);
    }

    /** How many PC slots hold a Pokémon, across every box. */
    public int boxedCount() {
        var storage = storage();
        int count = 0;
        for (int box = 0; box < BOXES; box++)
            for (int index = 0; index < PER_BOX; index++)
                if (!empty(storage, slotOffset(box, index))) count++;
        return count;
    }

    /** An all-zero entry is an empty slot; the game writes nothing else there. */
    static boolean empty(byte[] storage, int at) {
        for (int i = 0; i < ENTRY; i++) if (storage[at + i] != 0) return false;
        return true;
    }

    /**
     * The first empty slot, searching from the box the PC last had open and
     * wrapping round — the order the game's own CopyMonToPC uses for a Pokémon
     * sent to the PC. Null when every box is full.
     */
    public int[] firstFreeSlot(byte[] storage) {
        int start = currentBox(storage);
        for (int step = 0; step < BOXES; step++) {
            int box = (start + step) % BOXES;
            for (int index = 0; index < PER_BOX; index++)
                if (empty(storage, slotOffset(box, index))) return new int[]{box, index};
        }
        return null;
    }

    /** Box names, as the game stores them. */
    public String boxName(byte[] storage, int box) {
        return Gen3Text.read(storage, BOX_NAMES_AT + checkBox(box) * BOX_NAME_BYTES, BOX_NAME_BYTES);
    }

    /** Renames a box, in the game's own encoding: up to eight characters and a terminator. */
    public static void boxName(byte[] storage, int box, String name) {
        if (name.isBlank() || name.strip().length() > BOX_NAME_LENGTH)
            throw new IllegalArgumentException("A box name is 1 to " + BOX_NAME_LENGTH + " characters.");
        Gen3Text.write(name.strip(), storage, BOX_NAMES_AT + checkBox(box) * BOX_NAME_BYTES, BOX_NAME_BYTES);
    }

    public int boxWallpaper(byte[] storage, int box) {
        return storage[WALLPAPERS_AT + checkBox(box)] & 0xFF;
    }

    public static void boxWallpaper(byte[] storage, int box, int wallpaper) {
        if (wallpaper < 0 || wallpaper >= WALLPAPERS) throw new IllegalArgumentException("No wallpaper " + wallpaper);
        storage[WALLPAPERS_AT + checkBox(box)] = (byte) wallpaper;
    }

    private static int checkBox(int box) {
        if (box < 0 || box >= BOXES) throw new IllegalArgumentException("No box " + box);
        return box;
    }

    // ---- checksums and little-endian fields ---------------------------------

    /**
     * A section's checksum: sum its words, then fold the halves together.
     *
     * The fold is not decoration — the sum overflows 16 bits constantly, and
     * truncating instead of folding gives a plausible-looking wrong answer.
     */
    static int checksum(byte[] raw, int at, int size) {
        int sum = 0;
        for (int i = 0; i < size; i += 4) sum += (int) u32(raw, at + i);
        return ((sum >>> 16) + (sum & 0xFFFF)) & 0xFFFF;
    }

    static int u16(byte[] b, int at) { return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8; }

    static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    static void putU16(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >>> 8);
    }

    static void putU32(byte[] b, int at, long value) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (value >>> (8 * i));
    }
}
