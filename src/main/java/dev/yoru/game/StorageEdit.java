package dev.yoru.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Edits to the PC storage, made while the game is closed (#44).
 *
 * Pure: bytes in, bytes out. Each operation applies its change to a Gen3Save —
 * which writes it as the game's next save and re-reads it back — and then
 * proves, byte by byte, that nothing changed except what the operation was
 * asked to change. The caller commits through Tracker.editSave, so the save
 * and the vault are one write.
 *
 * The game must be closed: while it runs it holds its own copy of the save and
 * would overwrite any edit with its next in-game save. The page disables these
 * edits while the game is open, and the changed-save guard in editSave is the
 * backstop.
 */
public final class StorageEdit {

    private StorageEdit() { }

    /** A slot in a box, or a party position. The party is Place(true, -1, index). */
    public record Place(boolean party, int box, int slot) { }

    /** Orange Mail to Retro Mail: the game's item ids for letters a Pokémon can carry. */
    static final int FIRST_MAIL = 121, LAST_MAIL = 132;

    /**
     * Moves a Pokémon, or swaps two. Dropping on an empty slot moves; dropping
     * on an occupied slot swaps, as the game's own MOVE POKÉMON does. A
     * party-to-party move reorders the party, and dropping on any empty party
     * cell adds to the end, because the party has no gaps.
     *
     * The game's own refusals apply (pokemon_storage_system.c): the party must
     * keep a Pokémon able to battle — hatched, with HP left — and a Pokémon
     * holding Mail never goes into a box.
     */
    public static byte[] move(byte[] before, Place from, Place to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        var save = Gen3Save.read(before);
        requireEditable(save);
        var storage = save.storage();
        var party = new ArrayList<>(save.partyRecords());
        var partyBefore = List.copyOf(party);

        byte[] moving = boxFormAt(party, storage, from);
        if (moving == null) throw new IllegalArgumentException("Nothing there to move.");
        if (to.party() && (to.slot() < 0 || to.slot() >= Gen3Save.PARTY_LIMIT))
            throw new IllegalArgumentException("No party position " + (to.slot() + 1) + ".");
        if (to.party() && to.slot() > party.size()) to = new Place(true, -1, party.size());
        byte[] target = boxFormAt(party, storage, to);
        if (from.party() && !to.party() && party.size() == 1 && target == null)
            throw new IllegalArgumentException("That is your last Pokémon. The party can never be empty.");
        if (from.party() && !to.party()) requireNoMail(moving);
        if (!from.party() && to.party() && target != null) requireNoMail(target);

        var wantedSlots = new HashMap<Integer, byte[]>();
        var partyGain = new HashMap<String, Integer>();
        var partyLoss = new HashMap<String, Integer>();
        var partyDerivedFrom = new HashMap<String, byte[]>();

        if (!to.party() && !from.party()) {
            int toAt = Gen3Save.slotOffset(to.box(), to.slot());
            int fromAt = Gen3Save.slotOffset(from.box(), from.slot());
            writeSlot(storage, toAt, moving);
            wantedSlots.put(toAt, moving);
            if (target == null) { clearSlot(storage, fromAt); wantedSlots.put(fromAt, null); }
            else { writeSlot(storage, fromAt, target); wantedSlots.put(fromAt, target); }
        } else if (!to.party()) {
            int toAt = Gen3Save.slotOffset(to.box(), to.slot());
            writeSlot(storage, toAt, moving);
            wantedSlots.put(toAt, moving);
            partyLoss.merge(identity(moving), 1, Integer::sum);
            if (target == null) party.remove(from.slot());
            else { party.set(from.slot(), Gen3Pokemon.toParty(target, 0)); partyGain.merge(identity(target), 1, Integer::sum); partyDerivedFrom.put(identity(target), target); }
        } else if (!from.party()) {
            int index = to.slot();
            int fromAt = Gen3Save.slotOffset(from.box(), from.slot());
            if (target == null) {
                party.add(index, Gen3Pokemon.toParty(moving, 0));
                clearSlot(storage, fromAt);
                wantedSlots.put(fromAt, null);
            } else {
                party.set(index, Gen3Pokemon.toParty(moving, 0));
                writeSlot(storage, fromAt, target);
                wantedSlots.put(fromAt, target);
                partyLoss.merge(identity(target), 1, Integer::sum);
            }
            partyGain.merge(identity(moving), 1, Integer::sum);
            partyDerivedFrom.put(identity(moving), moving);
        } else if (to.slot() < party.size()) {
            // Party-to-party onto a member: the two swap positions, as the
            // game's party menu does. Nobody is gained or lost.
            var displaced = party.get(to.slot());
            party.set(to.slot(), party.get(from.slot()));
            party.set(from.slot(), displaced);
        } else {
            // Onto the empty cell past the last member: a move to the end.
            var picked = party.remove(from.slot());
            party.add(picked);
        }

        requireOneAbleToBattle(partyBefore, party);
        save.storage(storage);
        save.party(party);
        byte[] after = save.bytes();
        verifyMove(before, after, wantedSlots, partyGain, partyLoss, partyDerivedFrom);
        return after;
    }

    /**
     * "That's your last POKéMON!" — the game will not let the party lose its
     * last member able to battle (CanMovePartyMon and CanShiftMon count the
     * hatched members with HP left). A party of eggs or fainted Pokémon cannot
     * leave the house, so Yoru must not make one either.
     */
    private static void requireOneAbleToBattle(List<byte[]> before, List<byte[]> after) {
        if (ableToBattle(after) > 0 || ableToBattle(before) == 0) return;
        throw new IllegalArgumentException("That would leave your party with no Pokémon able to battle. "
            + "Keep one that has hatched and has HP left, as the game does.");
    }

    private static int ableToBattle(List<byte[]> party) {
        int able = 0;
        for (var record : party) {
            var mon = Gen3Pokemon.decodeFromParty(record, 0);
            if (!mon.isEgg() && mon.currentHp > 0) able++;
        }
        return able;
    }

    /** The game keeps Mail with the party: a Pokémon holding a letter is never put in a box. */
    private static void requireNoMail(byte[] boxForm) {
        int item = Gen3Pokemon.decode(boxForm, 0).heldItem;
        if (item >= FIRST_MAIL && item <= LAST_MAIL)
            throw new IllegalArgumentException("That Pokémon is holding Mail, and the game never puts Mail in a box. "
                + "Take the Mail from it in the game first.");
    }

    /** Renames a box, in the game's own encoding: one to eight characters the game can store. */
    public static byte[] renameBox(byte[] before, int box, String name) {
        var save = Gen3Save.read(before);
        requireEditable(save);
        for (int i = 0; i < name.length(); i++)
            if (Gen3Text.encode(name.charAt(i)) < 0)
                throw new IllegalArgumentException("The game has no character for '" + name.charAt(i)
                    + "'. Box names use letters, digits and the game's own punctuation.");
        var storage = save.storage();
        Gen3Save.boxName(storage, box, name);
        save.storage(storage);
        byte[] after = save.bytes();
        verifyRename(before, after, box, name);
        return after;
    }

    /** Changes a box's wallpaper; the id wraps by the game's own count. */
    public static byte[] wallpaper(byte[] before, int box, int id) {
        var save = Gen3Save.read(before);
        requireEditable(save);
        int next = Math.floorMod(id, Gen3Save.WALLPAPERS);
        var storage = save.storage();
        Gen3Save.boxWallpaper(storage, box, next);
        save.storage(storage);
        byte[] after = save.bytes();
        verifyWallpaper(before, after, box, next);
        return after;
    }

    private static void requireEditable(Gen3Save save) {
        String refusal = save.whyNotEditable();
        if (refusal != null) throw new IllegalStateException(refusal);
    }

    /** The Pokémon at a place, box-encoded, or null when the place is empty. */
    private static byte[] boxFormAt(List<byte[]> party, byte[] storage, Place place) {
        if (place.party())
            // The box form is the first 80 bytes of the party record, exactly as
            // the game's MonToBoxMon copies them. Copying rather than re-encoding
            // means a deposit is pinned to its source bytes, not to encode's idea
            // of them.
            return place.slot() >= 0 && place.slot() < party.size()
                ? Arrays.copyOf(party.get(place.slot()), Gen3Pokemon.BOX_SIZE) : null;
        int at = Gen3Save.slotOffset(place.box(), place.slot());
        return Gen3Save.empty(storage, at) ? null : Arrays.copyOfRange(storage, at, at + Gen3Pokemon.BOX_SIZE);
    }

    private static void writeSlot(byte[] storage, int at, byte[] record) {
        System.arraycopy(record, 0, storage, at, Gen3Pokemon.BOX_SIZE);
    }

    private static void clearSlot(byte[] storage, int at) {
        Arrays.fill(storage, at, at + Gen3Pokemon.BOX_SIZE, (byte) 0);
    }

    /** Who a Pokémon is, apart from where it stands: the fields a move must preserve. */
    private static String identity(Gen3Pokemon mon) {
        return mon.personality + ":" + mon.otId + ":" + mon.nationalDex() + ":" + mon.experience;
    }

    private static String identity(byte[] record) { return identity(Gen3Pokemon.decode(record, 0)); }

    private static String identityOfPartyRecord(byte[] record) {
        return identity(Gen3Pokemon.decodeFromParty(record, 0));
    }

    // ---- proving the change before handing it back --------------------------

    /**
     * Proves a move changed exactly what it says.
     *
     * Nothing outside the party region of section 1 and the PC storage may
     * change at all. Inside the PC, the slots named in wantedSlots must hold
     * exactly the records intended — byte for byte — or be empty when the
     * intent was null; every other slot, and the four header bytes and the box
     * names and wallpapers after the slots, must be byte-identical to before.
     * In the party, every member whose identity is present both before and
     * after must be byte-identical wherever it now stands, and the members
     * gained or lost must match partyGain minus partyLoss exactly, with every
     * surviving record's checksum intact.
     *
     * A deposit is a plain byte copy, so its slot is pinned against the source
     * bytes it came from. A withdrawal is the one place a move re-derives
     * anything — the game recalculates level and stats when a boxed Pokémon
     * joins the party — and the substructure checksum does not cover those
     * fields. So every gained member is re-derived here from the box record in
     * partyDerivedFrom and compared across all 100 bytes, not just its
     * identity and checksum.
     *
     * In correct code this never fires, so every branch is exercised directly
     * by the tests with a change it must refuse.
     */
    static void verifyMove(byte[] before, byte[] after, Map<Integer, byte[]> wantedSlots,
                           Map<String, Integer> partyGain, Map<String, Integer> partyLoss,
                           Map<String, byte[]> partyDerivedFrom) {
        var a = Gen3Save.read(before);
        var b = Gen3Save.read(after);
        for (int id = 0; id < Gen3Save.SECTIONS; id++) {
            if (id == 1 || (id >= Gen3Save.STORAGE_FIRST && id <= Gen3Save.STORAGE_LAST)) continue;
            byte[] sa = a.section(id), sb = b.section(id);
            for (int i = 0; i < Gen3Save.CHECKSUMMED[id]; i++)
                if (sa[i] != sb[i]) throw new IllegalStateException("Section " + id + " changed at byte " + i
                    + ", which a move never touches.");
        }
        byte[] s1a = a.section(1), s1b = b.section(1);
        for (int i = 0; i < Gen3Save.CHECKSUMMED[1]; i++)
            if (s1a[i] != s1b[i] && !(i >= Gen3Save.PARTY_COUNT_AT && i < Gen3Save.PARTY_END))
                throw new IllegalStateException("Section 1 changed at byte " + i + " outside the party.");
        byte[] storageA = a.storage(), storageB = b.storage();
        // A move writes only the box slots. The four header bytes (the box the
        // PC had open) and everything after the last slot — the fourteen box
        // names and wallpapers — must be byte-identical, or something other
        // than the move changed.
        for (int i = 0; i < Gen3Save.slotOffset(0, 0); i++)
            if (storageA[i] != storageB[i])
                throw new IllegalStateException("Storage header byte " + i + " changed, which a move never touches.");
        for (int i = Gen3Save.BOX_NAMES_AT; i < Gen3Save.STORAGE_SIZE; i++)
            if (storageA[i] != storageB[i])
                throw new IllegalStateException("Storage byte " + i + " changed, which a move never touches.");
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                int at = Gen3Save.slotOffset(box, slot);
                if (wantedSlots.containsKey(at)) {
                    byte[] want = wantedSlots.get(at);
                    if (want == null) {
                        if (!Gen3Save.empty(storageB, at))
                            throw new IllegalStateException("Box " + (box + 1) + " slot " + (slot + 1)
                                + " should be empty after the move.");
                        continue;
                    }
                    if (!Arrays.equals(storageB, at, at + Gen3Pokemon.BOX_SIZE, want, 0, Gen3Pokemon.BOX_SIZE))
                        throw new IllegalStateException("Box " + (box + 1) + " slot " + (slot + 1)
                            + " does not hold the exact record moved there.");
                    if (!Gen3Pokemon.intact(storageB, at))
                        throw new IllegalStateException("A moved record's checksum does not verify.");
                    continue;
                }
                if (!Arrays.equals(storageA, at, at + Gen3Pokemon.BOX_SIZE, storageB, at, at + Gen3Pokemon.BOX_SIZE))
                    throw new IllegalStateException("Box " + (box + 1) + " slot " + (slot + 1) + " changed.");
            }

        // Party: match members by identity across the move, so a shifted
        // member is still compared byte for byte; only the members a move
        // actually touched may differ.
        var beforeParty = a.partyRecords();
        var afterParty = b.partyRecords();
        var matched = new boolean[afterParty.size()];
        var delta = new HashMap<String, Integer>();
        for (var beforeRecord : beforeParty) {
            String id = identityOfPartyRecord(beforeRecord);
            int match = -1;
            for (int i = 0; i < afterParty.size(); i++)
                if (!matched[i] && id.equals(identityOfPartyRecord(afterParty.get(i)))) { match = i; break; }
            if (match < 0) delta.merge(id, -1, Integer::sum);
            else {
                matched[match] = true;
                if (!Arrays.equals(beforeRecord, afterParty.get(match)))
                    throw new IllegalStateException("A party member the move did not touch changed.");
            }
        }
        for (int i = 0; i < afterParty.size(); i++)
            if (!matched[i]) delta.merge(identityOfPartyRecord(afterParty.get(i)), 1, Integer::sum);
        delta.values().removeIf(v -> v == 0);
        var expected = new HashMap<String, Integer>();
        partyGain.forEach((id, n) -> expected.merge(id, n, Integer::sum));
        partyLoss.forEach((id, n) -> expected.merge(id, -n, Integer::sum));
        expected.values().removeIf(v -> v == 0);
        if (!delta.equals(expected)) throw new IllegalStateException("The party gained or lost the wrong members: "
            + delta + " (expected " + expected + ")");
        // A gained member was withdrawn from a box and had its party record
        // re-derived. Re-derive it here from the box record it came from and
        // compare all 100 bytes, so a wrong level or stat cannot ship under a
        // valid checksum (which never covers the party tail).
        for (int i = 0; i < afterParty.size(); i++) {
            if (matched[i]) continue;
            byte[] source = partyDerivedFrom.get(identityOfPartyRecord(afterParty.get(i)));
            if (source == null)
                throw new IllegalStateException("A party member was gained that no withdrawal explains.");
            if (!Arrays.equals(afterParty.get(i), Gen3Pokemon.toParty(source, 0)))
                throw new IllegalStateException("A withdrawn party member's stats do not match the game's own.");
        }
        for (int i = 0; i < b.partyCount(); i++)
            if (!Gen3Pokemon.intact(b.partyRecord(i), 0))
                throw new IllegalStateException("Party member " + (i + 1) + "'s checksum does not verify.");
    }

    /** Everything outside one box name's nine bytes must be identical, and the name must read back. */
    static void verifyRename(byte[] before, byte[] after, int box, String name) {
        int start = Gen3Save.BOX_NAMES_AT + box * Gen3Save.BOX_NAME_BYTES;
        assertOnlyChanges(before, after, at -> at >= start && at < start + Gen3Save.BOX_NAME_BYTES);
        // Compared as stored bytes rather than text: a typed apostrophe is
        // stored as the game's own and reads back curly, and is still the name
        // that was written.
        byte[] saved = Gen3Save.read(after).storage();
        byte[] wanted = Gen3Text.bytes(name.strip(), Gen3Save.BOX_NAME_BYTES);
        if (!Arrays.equals(saved, start, start + Gen3Save.BOX_NAME_BYTES, wanted, 0, Gen3Save.BOX_NAME_BYTES))
            throw new IllegalStateException("The box name did not come back as written.");
    }

    /** Everything outside one wallpaper byte must be identical, and the id must read back. */
    static void verifyWallpaper(byte[] before, byte[] after, int box, int id) {
        int at = Gen3Save.WALLPAPERS_AT + box;
        assertOnlyChanges(before, after, i -> i == at);
        var saved = Gen3Save.read(after);
        if (saved.boxWallpaper(saved.storage(), box) != id)
            throw new IllegalStateException("The wallpaper did not come back as written.");
    }

    /** Sections outside the PC must match entirely; inside it, only bytes the predicate allows may differ. */
    private static void assertOnlyChanges(byte[] before, byte[] after, java.util.function.IntPredicate allowedInStorage) {
        var a = Gen3Save.read(before);
        var b = Gen3Save.read(after);
        for (int id = 0; id < Gen3Save.SECTIONS; id++) {
            if (id >= Gen3Save.STORAGE_FIRST && id <= Gen3Save.STORAGE_LAST) continue;
            byte[] sa = a.section(id), sb = b.section(id);
            for (int i = 0; i < Gen3Save.CHECKSUMMED[id]; i++)
                if (sa[i] != sb[i]) throw new IllegalStateException("Section " + id + " changed at byte " + i
                    + ", which this edit never touches.");
        }
        byte[] storageA = a.storage(), storageB = b.storage();
        for (int i = 0; i < storageA.length; i++)
            if (storageA[i] != storageB[i] && !allowedInStorage.test(i))
                throw new IllegalStateException("Storage changed at byte " + i + ", which this edit never touches.");
    }
}
