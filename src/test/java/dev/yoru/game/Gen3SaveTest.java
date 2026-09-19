package dev.yoru.game;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The Gen 3 save container (#29), held to what Emerald itself does.
 *
 * Slot selection is checked against rules read from pokeemerald's
 * GetSaveValidStatus and CopySaveSlotData, not against what seemed sensible:
 * wherever the two differ, a player would see different progress in Yoru than
 * in the game. Fixtures are built rather than copied — a real save is somebody's
 * progress — and each puts its slot where its counter says the game will look.
 */
public final class Gen3SaveTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static void refused(Runnable action, String why) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException e) { checks++; return; }
        throw new AssertionError(why);
    }

    /** A save at this counter, sections rotated by {@code shift}, filled with noise. */
    private static byte[] build(long counter, int shift, long seed) {
        return fill(Gen3Fixture.save(counter, shift), (int) (counter % 2), seed);
    }

    /** Noise in every checksummed byte, so a size-table error shows up as a checksum error. */
    private static byte[] fill(byte[] raw, int slot, long seed) {
        var random = new Random(seed);
        for (int position = 0; position < Gen3Save.SECTIONS; position++) {
            int at = slot * Gen3Save.SLOT + position * Gen3Save.SECTION;
            int id = Gen3Save.u16(raw, at + Gen3Save.ID_AT);
            for (int i = 0; i < Gen3Save.CHECKSUMMED[id]; i++) raw[at + i] = (byte) random.nextInt(256);
        }
        return Gen3Fixture.stamp(raw, slot);
    }

    /** Two saves, one in each slot; each counter's parity puts it in its own. */
    private static byte[] both(long first, long second, long seed) {
        if (first % 2 == second % 2) throw new IllegalArgumentException("two saves need counters of different parity");
        var raw = build(first, 3, seed);
        Gen3Fixture.writeSlot(raw, (int) (second % 2), second, 5);
        return fill(raw, (int) (second % 2), seed + 1);
    }

    /** Sections are found by their id, wherever the rotation put them. */
    private static void sectionsAreFoundByIdNotPosition() {
        for (int shift : new int[]{0, 1, 7, 13}) {
            var raw = build(4, shift, 42 + shift);
            var save = Gen3Save.read(raw);
            for (int id = 0; id < Gen3Save.SECTIONS; id++) {
                int at = Math.floorMod(id - shift, Gen3Save.SECTIONS) * Gen3Save.SECTION;
                check(Arrays.equals(save.section(id), 0, Gen3Save.SECTION, raw, at, at + Gen3Save.SECTION),
                    "section " + id + " is read from where rotation " + shift + " put it");
            }
        }
        check(Gen3Save.u16(build(4, 1, 7), Gen3Save.ID_AT) != 0,
            "with a rotation of one, position 0 does not hold section 0, so the test above means something");
    }

    /**
     * The checksum arithmetic, against values worked out by hand.
     *
     * Every other checksum assertion builds its fixture with the function under
     * test, so they would all still pass if the fold were replaced by a
     * truncation. These cases are chosen so the two give different answers.
     */
    private static void checksumFoldsRatherThanTruncates() {
        var block = new byte[Gen3Save.SECTION];
        block[0] = block[1] = block[2] = block[3] = (byte) 0xFF;
        check(Gen3Save.checksum(block, 0, 4) == 0xFFFE,
            "0xFFFFFFFF folds to 0xFFFE, got 0x" + Integer.toHexString(Gen3Save.checksum(block, 0, 4)));
        Arrays.fill(block, (byte) 0);
        block[1] = (byte) 0x80;
        block[5] = (byte) 0x80;
        check(Gen3Save.checksum(block, 0, 8) == 1, "a sum of exactly 0x10000 folds to 1, got " + Gen3Save.checksum(block, 0, 8));
        Arrays.fill(block, (byte) 0);
        block[8] = 0x7F;
        check(Gen3Save.checksum(block, 0, 8) == 0, "a word past the size is not counted");
        check(Gen3Save.checksum(block, 0, 12) == 0x7F, "and is counted once it is inside");
    }

    /** The newer save wins, a blank slot is skipped, and the wrap is the game's. */
    private static void slotSelectionFollowsTheGame() {
        var save = Gen3Save.read(both(7, 8, 1));
        check(save.counter() == 8 && save.loadedSlot() == 0, "the larger counter wins, got " + save.counter());
        check(save.otherSlot() == Gen3Save.Status.OK, "and the other slot is known to be a sound older save");

        var only = Gen3Save.read(build(1, 3, 3));
        check(only.loadedSlot() == 1 && only.counter() == 1, "a cartridge that has saved once loads that one slot");
        check(only.otherSlot() == Gen3Save.Status.EMPTY, "and knows the other was never written");

        check(Gen3Save.newer(0xFFFFFFFFL, 0) == 0, "0 follows 0xFFFFFFFF");
        check(Gen3Save.newer(0, 0xFFFFFFFFL) == 0, "in either order");
        check(Gen3Save.newer(5, 0xFFFFFFFFL) == 0xFFFFFFFFL,
            "otherwise the larger wins, compared unsigned as the game compares it");
        check(Gen3Save.newer(0x80000000L, 3) == 0x80000000L, "including past the signed range");
        check(Gen3Save.newer(9, 8) == 9 && Gen3Save.newer(8, 9) == 9, "and ordinarily the larger is newer");
        var wrapped = Gen3Save.read(both(0xFFFFFFFFL, 0, 9));
        check(wrapped.counter() == 0 && wrapped.loadedSlot() == 0, "after the wrap, the save at 0 is the one loaded");
    }

    /** The game loads slot counter % 2, even when that is not where the counter came from. */
    private static void theCounterPicksTheSlot() {
        var misplaced = Gen3Fixture.saveInSlot(1, 4, 0);
        refused(() -> Gen3Save.read(misplaced),
            "counter 4 sitting in slot 2 sends the game to slot 1, which is empty — refused, not read");

        var raw = Gen3Fixture.saveInSlot(0, 5, 0);
        Gen3Fixture.writeSlot(raw, 1, 2, 4);
        var save = Gen3Save.read(raw);
        check(save.counter() == 5 && save.loadedSlot() == 1,
            "slot 1 at 5, slot 2 at 2: 5 is odd, so the game loads slot 2 — and so does Yoru");
        check(save.whyNotEditable() != null, "but Yoru will not change a save whose slots are out of step");
    }

    /** Damage is refused, and a half-written slot gives way to the complete older one. */
    private static void damageIsRefusedAndTheOlderSlotSurvives() {
        var only = build(4, 5, 11);
        only[Gen3Fixture.offsetOf(only, 3) + 100] ^= 0x01;
        refused(() -> Gen3Save.read(only), "a save whose only slot is damaged is refused rather than read");

        var raw = both(7, 8, 21);
        check(Gen3Save.read(raw).counter() == 8, "with both sound, the newer wins");
        raw[Gen3Fixture.offsetOf(raw, 9) + 40] ^= 0x01;
        check(Gen3Save.scan(raw, 0).status() == Gen3Save.Status.ERROR, "the newer slot is now incomplete");
        check(Gen3Save.u32(raw, Gen3Save.SIGNATURE_AT) == Gen3Save.SIGNATURE, "while its first sector still looks perfect");
        var chosen = Gen3Save.read(raw);
        check(chosen.counter() == 7 && chosen.loadedSlot() == 1,
            "so the complete older save is loaded, got counter " + chosen.counter());
        check(chosen.otherSlot() == Gen3Save.Status.ERROR, "and the damage is known, as the game would warn of it");

        var duplicated = build(2, 0, 31);
        Gen3Save.putU16(duplicated, Gen3Save.SECTION * 5 + Gen3Save.ID_AT, 4);
        Gen3Fixture.stamp(duplicated, 0);
        check(Gen3Save.scan(duplicated, 0).status() == Gen3Save.Status.ERROR,
            "two sectors claiming one id leave another id missing");

        var beyond = build(4, 0, 11);
        beyond[Gen3Fixture.offsetOf(beyond, 13) + Gen3Save.CHECKSUMMED[13] + 4] ^= 0x01;
        check(Gen3Save.scan(beyond, 0).status() == Gen3Save.Status.OK,
            "a byte past section 13's checksummed 2000 does not affect it");
    }

    /** Sectors that disagree about the counter are read as the game reads them, and never changed. */
    private static void mixedCountersAreReadButNeverChanged() {
        var raw = build(8, 0, 99);
        Gen3Save.putU32(raw, 5 * Gen3Save.SECTION + Gen3Save.COUNTER_AT, 6);
        check(Gen3Save.scan(raw, 0).status() == Gen3Save.Status.OK,
            "every sector still passes on its own — the checksum does not cover the footer");
        var save = Gen3Save.read(raw);
        check(save.loadedSlot() == 0 && save.counter() == 8,
            "the game keeps the last valid sector's counter and loads the slot regardless");
        check(save.whyNotEditable() != null, "Yoru reads it but will not change it");
        save.section(0)[0x20] ^= 1;
        refused(save::bytes, "and a change to it is refused at the point of writing");

        var two = build(8, 0, 98);
        Gen3Fixture.writeSlot(two, 1, 5, 2);
        Gen3Save.putU32(two, 13 * Gen3Save.SECTION + Gen3Save.COUNTER_AT, 9);
        var moved = Gen3Save.read(two);
        check(moved.counter() == 9 && moved.loadedSlot() == 1,
            "a 9 in slot 1's last sector sends the game to slot 2, and Yoru follows, got slot " + (moved.loadedSlot() + 1));
        check(moved.whyNotEditable() != null, "without offering to change it");
    }

    private static void bothSlotsBrokenIsRefused() {
        var raw = both(4, 5, 61);
        check(Gen3Save.read(raw).counter() == 5, "both sound to begin with");
        raw[Gen3Save.SECTION * 2 + 30] ^= 0x01;
        raw[Gen3Save.SLOT + Gen3Save.SECTION * 6 + 30] ^= 0x01;
        refused(() -> Gen3Save.read(raw), "with neither slot sound, nothing is read");
    }

    private static void anUnchangedSaveIsReturnedExactly() {
        var raw = build(6, 9, 21);
        var save = Gen3Save.read(raw);
        check(!save.changed(), "nothing has been changed");
        check(Arrays.equals(save.bytes(), raw), "so the bytes handed back are exactly the file");
        check(save.whyNotEditable() == null, "and an ordinary save is editable");
    }

    /** A change is written as the game's next save would be, leaving the one it replaces. */
    private static void changesAreWrittenAsTheNextSave() {
        var raw = build(4, 9, 21);
        var save = Gen3Save.read(raw);
        var pc = save.storage();
        pc[100] ^= 0x5A;
        save.storage(pc);
        check(save.changed(), "a change is noticed");
        var written = save.bytes();
        check(Arrays.equals(written, 0, Gen3Save.SLOT, raw, 0, Gen3Save.SLOT), "the loaded slot is left exactly as it was");
        check(Arrays.equals(written, 2 * Gen3Save.SLOT, Gen3Save.SIZE, raw, 2 * Gen3Save.SLOT, Gen3Save.SIZE),
            "and nothing past the two slots is touched");
        var reread = Gen3Save.read(written);
        check(reread.loadedSlot() == 1 && reread.counter() == 5, "the change is the next save: slot 2, counter 5");
        check(Arrays.equals(reread.storage(), pc), "holding the change");
        var scan = Gen3Save.scan(written, 1);
        check(scan.status() == Gen3Save.Status.OK && scan.consistent(), "every sector of it sound and at one counter");

        var second = Gen3Save.read(written);
        var pc2 = second.storage();
        pc2[200] ^= 1;
        second.storage(pc2);
        var again = Gen3Save.read(second.bytes());
        check(again.loadedSlot() == 0 && again.counter() == 6, "a second change alternates back, at counter 6");

        var top = Gen3Save.read(build(0xFFFFFFFFL, 2, 5));
        var pc3 = top.storage();
        pc3[10] ^= 1;
        top.storage(pc3);
        var wrapped = Gen3Save.read(top.bytes());
        check(wrapped.counter() == 0 && wrapped.loadedSlot() == 0,
            "a save at 0xFFFFFFFF writes its next at 0, which the game still prefers");
    }

    /**
     * The PC survives being split across nine sections and put back — the
     * assertion that catches stitching off by a footer, which looks fine for
     * the first boxes and corrupts the later ones.
     */
    private static void storageStitchesAcrossSections() {
        var save = Gen3Save.read(build(2, 4, 31));
        var storage = save.storage();
        check(storage.length == Gen3Save.STORAGE_SIZE, "the PC is " + Gen3Save.STORAGE_SIZE + " bytes");
        check(Gen3Save.STORAGE_SIZE == 8 * 3968 + 2000, "which is sections 5 to 13 end to end");
        var marked = storage.clone();
        for (int i = 0; i < marked.length; i++) marked[i] = (byte) (i * 31);
        save.storage(marked);
        check(Arrays.equals(save.storage(), marked), "every byte comes back, including across the joins");
        for (int id = Gen3Save.STORAGE_FIRST; id <= Gen3Save.STORAGE_LAST; id++)
            check(Gen3Save.u16(save.section(id), Gen3Save.ID_AT) == id, "section " + id + "'s footer was not written over");
        check(Arrays.equals(Gen3Save.read(save.bytes()).storage(), marked), "and the written save holds that PC");
    }

    private static void slotArithmetic() {
        check(Gen3Save.slotOffset(0, 0) == 4, "the first slot follows the current-box word");
        check(Gen3Save.slotOffset(0, 1) == 4 + Gen3Pokemon.BOX_SIZE, "slots are 80 bytes apart");
        check(Gen3Save.slotOffset(1, 0) == 4 + 30 * Gen3Pokemon.BOX_SIZE, "a box holds thirty");
        check(Gen3Save.BOX_NAMES_AT == 33604 && Gen3Save.WALLPAPERS_AT == 33730,
            "names follow the 420 slots at 33604 and wallpapers the 126 name bytes at 33730, worked out by hand");
        refused(() -> Gen3Save.slotOffset(Gen3Save.BOXES, 0), "a box past the last is refused");
    }

    /** Free slots are found from the box the PC last had open, wrapping, as the game sends them. */
    private static void freeSlotsStartAtTheOpenBox() {
        var save = Gen3Save.read(Gen3Fixture.save(2, 0));
        var storage = new byte[Gen3Save.STORAGE_SIZE];
        check(Arrays.equals(save.firstFreeSlot(storage), new int[]{0, 0}), "an empty PC offers box 1, slot 1");
        storage[0] = 3;
        check(Arrays.equals(save.firstFreeSlot(storage), new int[]{3, 0}), "with box 4 open, box 4 comes first");
        for (int box = 3; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) storage[Gen3Save.slotOffset(box, slot) + 8] = 1;
        check(Arrays.equals(save.firstFreeSlot(storage), new int[]{0, 0}), "and past the last box it wraps to the first");
        for (int box = 0; box < 3; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) storage[Gen3Save.slotOffset(box, slot) + 8] = 1;
        check(save.firstFreeSlot(storage) == null, "a full PC offers nothing");

        var occupant = new Gen3Pokemon();
        occupant.personality = 0x1234;
        occupant.otId = 0x5678;
        occupant.species = 25;
        occupant.nickname = "Sparky";
        var one = new byte[Gen3Save.STORAGE_SIZE];
        System.arraycopy(occupant.encode(), 0, one, Gen3Save.slotOffset(0, 0), Gen3Pokemon.BOX_SIZE);
        check(save.boxed(one, 0, 0).nickname.equals("Sparky"), "an occupant reads back");
        check(save.boxed(one, 0, 1) == null, "an empty slot reads as nothing");
        check(Arrays.equals(save.firstFreeSlot(one), new int[]{0, 1}), "and the next slot is the first free");
    }

    private static void boxNamesAndWallpapers() {
        var save = Gen3Save.read(Gen3Fixture.save(2, 0));
        var storage = save.storage();
        Gen3Save.boxName(storage, 4, "STUDY");
        Gen3Save.boxWallpaper(storage, 4, 11);
        check(save.boxName(storage, 4).equals("STUDY"), "a renamed box reads back");
        check(save.boxWallpaper(storage, 4) == 11, "and so does its wallpaper");
        check((storage[33604 + 4 * 9 + 5] & 0xFF) == 0xFF, "a name is terminated where it ends");
        refused(() -> Gen3Save.boxName(storage, 4, "NINELETTR"), "names stop at eight characters, as the game's do");
        refused(() -> Gen3Save.boxWallpaper(storage, 4, 16), "only sixteen wallpapers exist");
    }

    /** Progress, the Pokédex and money, at offsets worked out by hand from pokeemerald's global.h. */
    private static void progressIsReadWhereTheGameKeepsIt() {
        var raw = Gen3Fixture.save(2, 6);
        int section0 = Gen3Fixture.offsetOf(raw, 0);
        int section1 = Gen3Fixture.offsetOf(raw, 1);
        int section2 = Gen3Fixture.offsetOf(raw, 2);

        // SaveBlock1's flags start at 0x1270 = 4720, which is section 2, byte 752 (0x2F0).
        int flags = section2 + 0x2F0;
        // 0x860 (starter received) is byte 0x10C bit 0; badges 1-3 (0x867-0x869)
        // are byte 0x10C bit 7 and byte 0x10D bits 0 and 1.
        raw[flags + 0x10C] |= (byte) 0x81;
        raw[flags + 0x10D] |= 0x03;
        Gen3Save.putU32(raw, section0 + 0xAC, 0x12345678L);
        Gen3Save.putU32(raw, section1 + 0x490, 3000 ^ 0x12345678L);
        Gen3Fixture.stamp(raw, 0);

        var save = Gen3Save.read(raw);
        check(save.hasStarter(), "the starter flag is read");
        check(save.badges() == 3 && save.badge(0) && save.badge(2) && !save.badge(3), "three badges, the first three");
        check(!save.gameClear(), "and the game is not yet cleared");
        check(save.money() == 3000, "money comes back through its key, got " + save.money());

        save.register(252);
        check(save.owned(252) && save.seen(252) && !save.owned(253), "registering Treecko marks it and nothing else");
        var after = Gen3Save.read(save.bytes());
        // Treecko is National 252: index 251, so byte 31, bit 3.
        check((after.section(0)[0x28 + 31] & 0x08) != 0, "owned, in SaveBlock2 at 0x28");
        check((after.section(0)[0x5C + 31] & 0x08) != 0, "seen, in SaveBlock2 at 0x5C");
        check((after.section(1)[0x988 + 31] & 0x08) != 0, "seen1, in SaveBlock1 at 0x988, section 1");
        // seen2 is SaveBlock1 0x3B24 = 15140: section 4, byte 3236 (0xCA4).
        check((after.section(4)[0xCA4 + 31] & 0x08) != 0, "and seen2, section 4 at 0xCA4 — all three, or the game clears it");
        check(after.ownedCount() == 1, "one species owned");
    }

    private static void thePartyIsWrittenWhole() {
        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 3), "TESTER", 0, 12345, 54321);
        var a = Gen3Fixture.member(raw, 252, 5, 1);
        var b = Gen3Fixture.member(raw, 255, 7, 2);
        var save = Gen3Save.read(raw);
        check(save.trainer().name().equals("TESTER") && save.trainer().otId() == ((54321 << 16) | 12345),
            "the trainer is read from section 0");
        save.party(List.of(a, b));
        check(save.partyCount() == 2 && Arrays.equals(save.partyRecord(1), b), "two members, in order");
        check(save.party().get(0).nationalDex() == 252 && save.party().get(1).level() == 7, "reading back as themselves");
        var reread = Gen3Save.read(save.bytes());
        check(reread.partyCount() == 2 && Arrays.equals(reread.partyRecord(0), a), "and surviving the write");
        // The sixth entry was never part of the party; mark it, so leaving it
        // alone is told apart from rewriting it the same.
        int sixth = Gen3Save.PARTY_AT + 5 * Gen3Pokemon.PARTY_SIZE;
        save.section(1)[sixth + 7] = 0x5A;
        save.party(List.of(a));
        int vacated = Gen3Save.PARTY_AT + Gen3Pokemon.PARTY_SIZE;
        boolean emptied = true;
        for (int i = 0; i < Gen3Pokemon.PARTY_SIZE; i++)
            emptied &= save.section(1)[vacated + i] == (i == 0x55 ? (byte) 0xFF : 0);
        check(emptied, "the member a shorter party lets go of is emptied as ZeroMonData empties it: "
            + "zeros, and no mail (0xFF) in the mail byte");
        check(save.section(1)[sixth + 7] == 0x5A, "and an entry past both parties is left exactly as it was");
        refused(() -> save.party(List.of(a, a, a, a, a, a, a)), "a seventh member is refused");
    }

    private static void refusesWhatItCannotRead() {
        refused(() -> Gen3Save.read(new byte[1024]), "a file that is not 128KB is refused");
        var blank = new byte[Gen3Save.SIZE];
        Arrays.fill(blank, (byte) 0xFF);
        refused(() -> Gen3Save.read(blank), "a cartridge that has never saved is refused, not read as empty");
    }

    public static void main(String[] args) {
        sectionsAreFoundByIdNotPosition();
        checksumFoldsRatherThanTruncates();
        slotSelectionFollowsTheGame();
        theCounterPicksTheSlot();
        damageIsRefusedAndTheOlderSlotSurvives();
        mixedCountersAreReadButNeverChanged();
        bothSlotsBrokenIsRefused();
        anUnchangedSaveIsReturnedExactly();
        changesAreWrittenAsTheNextSave();
        storageStitchesAcrossSections();
        slotArithmetic();
        freeSlotsStartAtTheOpenBox();
        boxNamesAndWallpapers();
        progressIsReadWhereTheGameKeepsIt();
        thePartyIsWrittenWhole();
        refusesWhatItCannotRead();
        System.out.println("PASS: " + checks + " Gen 3 save checks (the game's slot rules, next-save writes, PC, party, progress)");
    }
}
