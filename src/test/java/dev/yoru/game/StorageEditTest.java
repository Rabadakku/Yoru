package dev.yoru.game;

import java.util.List;
import java.util.UUID;

/** Storage edits made while the game is closed (#44). Saves are invented, never real ones. */
public final class StorageEditTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** A trainer with two party members (starter first) and nothing in any box. */
    private static byte[] save() {
        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 4), "TESTER", 0, 12345, 54321);
        raw = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 255, 5, 1), Gen3Fixture.member(raw, 25, 9, 2)));
        return raw;
    }

    private static StorageEdit.Place box(int box, int slot) { return new StorageEdit.Place(false, box, slot); }
    private static StorageEdit.Place party(int index) { return new StorageEdit.Place(true, -1, index); }

    /** Who a Pokémon is, as StorageEdit keys it: personality, trainer, species, experience. */
    private static String identity(byte[] record) {
        var mon = Gen3Pokemon.decode(record, 0);
        return mon.personality + ":" + mon.otId + ":" + mon.nationalDex() + ":" + mon.experience;
    }

    /** The save after withdrawing the boxed Pokémon at (2,5) into a fresh party slot. */
    private static byte[] withdrawn(byte[] before, byte[] partyRecord) {
        var save = Gen3Save.read(before);
        var storage = save.storage();
        java.util.Arrays.fill(storage, Gen3Save.slotOffset(2, 5), Gen3Save.slotOffset(2, 5) + Gen3Pokemon.BOX_SIZE, (byte) 0);
        save.storage(storage);
        var party = new java.util.ArrayList<>(save.partyRecords());
        party.add(partyRecord);
        save.party(party);
        return save.bytes();
    }

    /** box→box move: the slot fills, the source empties, nothing else in the box changes. */
    private static void movesWithinABox() {
        var raw = save();
        var prepared = Gen3Save.read(raw);
        var storage = prepared.storage();
        var planted = Gen3Save.read(raw).partyRecord(0);
        var plantedBox = Gen3Pokemon.decodeFromParty(planted, 0).encode();
        System.arraycopy(plantedBox, 0, storage, Gen3Save.slotOffset(0, 3), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();

        var after = StorageEdit.move(before, box(0, 3), box(0, 7));
        var reread = Gen3Save.read(after);
        check(reread.boxed(reread.storage(), 0, 7) != null, "the destination slot now holds the Pokémon");
        check(Gen3Save.empty(reread.storage(), Gen3Save.slotOffset(0, 3)), "the source slot is empty");
        check(reread.partyCount() == 2, "the party is untouched");
        check(reread.boxed(reread.storage(), 0, 0) == null && reread.boxed(reread.storage(), 1, 0) == null,
            "no other slot changed");
    }

    /** Dropping on an occupied slot swaps, as the game's MOVE POKÉMON does. */
    private static void swapsOntoAnOccupiedSlot() {
        var prepared = Gen3Save.read(save());
        var storage = prepared.storage();
        var a = Gen3Pokemon.decodeFromParty(prepared.partyRecord(0), 0).encode();
        var b = Gen3Pokemon.decodeFromParty(prepared.partyRecord(1), 0).encode();
        System.arraycopy(a, 0, storage, Gen3Save.slotOffset(0, 3), Gen3Pokemon.BOX_SIZE);
        System.arraycopy(b, 0, storage, Gen3Save.slotOffset(0, 7), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();

        var after = StorageEdit.move(before, box(0, 3), box(0, 7));
        var reread = Gen3Save.read(after);
        var at3 = reread.boxed(reread.storage(), 0, 3);
        var at7 = reread.boxed(reread.storage(), 0, 7);
        check(at3 != null && at7 != null, "both slots are occupied after a swap");
        check(at3.personality == Gen3Pokemon.decode(b, 0).personality, "the displaced Pokémon moved to the source slot");
        check(at7.personality == Gen3Pokemon.decode(a, 0).personality, "and the picked one to the destination");
        check(reread.partyCount() == 2, "the party is untouched by a box swap");
    }

    /** box→party appends at the first empty position, and the source slot empties. */
    private static void movesIntoTheParty() {
        var raw = save();
        var prepared = Gen3Save.read(raw);
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(raw, 280, 7, 3), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(2, 5), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();

        var after = StorageEdit.move(before, box(2, 5), party(2));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 3, "the party grew to three");
        check(Gen3Save.empty(reread.storage(), Gen3Save.slotOffset(2, 5)), "the source slot is empty");
        check(reread.party().get(2).personality == Gen3Pokemon.decode(planted, 0).personality,
            "the moved member sits at the chosen party position");
    }

    /** party→box deposits, shifting the party up, and the box slot fills. */
    private static void depositsFromTheParty() {
        var before = save();
        var after = StorageEdit.move(before, party(1), box(4, 9));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 1, "the party shrank to one");
        check(reread.party().getFirst().nationalDex() == 255, "the starter remains the lead");
        check(reread.boxed(reread.storage(), 4, 9) != null, "the box slot holds the deposited member");
        check(reread.section(1)[Gen3Save.PARTY_AT + Gen3Pokemon.PARTY_SIZE + Gen3Pokemon.MAIL_AT]
                == (byte) Gen3Pokemon.MAIL_NONE,
            "the entry the party let go of is emptied as the game empties one, no-mail byte and all");
    }

    /** Dropping onto an occupied party position swaps the two. */
    private static void swapsWithAPartyMember() {
        var raw = save();
        var prepared = Gen3Save.read(raw);
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(raw, 280, 7, 3), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(5, 0), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();

        var after = StorageEdit.move(before, box(5, 0), party(0));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 2, "a swap leaves the party the same size");
        check(reread.party().getFirst().personality == Gen3Pokemon.decode(planted, 0).personality,
            "the picked member leads the party");
        var displaced = reread.boxed(reread.storage(), 5, 0);
        check(displaced != null && displaced.nationalDex() == 255, "the displaced leader went to the box slot");
    }

    /** party→party onto a member swaps positions; onto the end cell moves there. */
    private static void reordersTheParty() {
        var raw = save();
        var three = Gen3Save.read(raw);
        three.party(List.of(three.partyRecord(0), three.partyRecord(1),
            Gen3Fixture.member(raw, 280, 7, 3)));
        var before = three.bytes();
        var after = StorageEdit.move(before, party(0), party(2));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 3, "a reorder leaves the party the same size");
        check(reread.party().get(2).nationalDex() == 255 && reread.party().get(1).nationalDex() == 25
            && reread.party().getFirst().nationalDex() == 280,
            "the two swapped positions and nobody else moved");
        var toEnd = StorageEdit.move(after, party(0), party(3));
        var ended = Gen3Save.read(toEnd);
        check(ended.party().get(2).nationalDex() == 280, "dropping on the empty end cell moves it there");
    }

    /** The last party member cannot be deposited, and an empty place cannot be picked up. */
    private static void refusesTheImpossible() {
        var solo = Gen3Save.read(save());
        solo.party(List.of(solo.partyRecord(0)));
        var before = solo.bytes();
        boolean refused = false;
        try { StorageEdit.move(before, party(0), box(0, 0)); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "depositing the last party member is refused");

        refused = false;
        try { StorageEdit.move(save(), box(0, 0), box(0, 1)); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "picking up an empty slot is refused");

        refused = false;
        try { StorageEdit.move(save(), party(5), box(0, 0)); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "picking up an empty party position is refused");

        refused = false;
        try { StorageEdit.move(save(), party(0), party(-1)); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "dropping onto a negative party position is refused");

        refused = false;
        try { StorageEdit.move(save(), party(0), party(7)); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "dropping onto a party position past the end is refused");

        // Swapping the last party member with a boxed one is fine: the party
        // still holds one afterwards.
        var soloBoxed = Gen3Save.read(solo.bytes());
        var storage = soloBoxed.storage();
        var standby = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(solo.bytes(), 280, 7, 3), 0).encode();
        System.arraycopy(standby, 0, storage, Gen3Save.slotOffset(0, 0), Gen3Pokemon.BOX_SIZE);
        soloBoxed.storage(storage);
        var swappable = StorageEdit.move(soloBoxed.bytes(), party(0), box(0, 0));
        var swapped = Gen3Save.read(swappable);
        check(swapped.partyCount() == 1 && swapped.party().getFirst().nationalDex() == 280,
            "swapping the last party member with a boxed one keeps the party at one");
    }

    /** A rename touches exactly one box name and reads back as written. */
    private static void renamesABox() {
        var before = save();
        var after = StorageEdit.renameBox(before, 3, "STUDY");
        var reread = Gen3Save.read(after);
        check(reread.boxName(reread.storage(), 3).strip().equals("STUDY"), "the name reads back as written");
        check(reread.boxName(reread.storage(), 2).isBlank(), "no other box name changed");
        check(reread.boxWallpaper(reread.storage(), 3) == Gen3Save.read(before).boxWallpaper(Gen3Save.read(before).storage(), 3),
            "the wallpaper is untouched by a rename");
        boolean refused = false;
        try { StorageEdit.renameBox(before, 3, "THIS NAME IS FAR TOO LONG"); }
        catch (IllegalArgumentException e) { refused = true; }
        check(refused, "a name over eight characters is refused");
        refused = false;
        try { StorageEdit.renameBox(before, 3, "CAFÉ"); }
        catch (IllegalArgumentException e) { refused = true; }
        check(refused, "a character the game cannot store is refused");

        // A typed apostrophe is stored as the game's own, which reads back curly;
        // the rename's own check must still recognise the name it wrote.
        var quoted = Gen3Save.read(StorageEdit.renameBox(before, 3, "DON'T"));
        check(quoted.boxName(quoted.storage(), 3).equals("DON’T"), "an apostrophe reads back as the game draws it");
        check((quoted.storage()[Gen3Save.BOX_NAMES_AT + 3 * Gen3Save.BOX_NAME_BYTES + 3] & 0xFF) == 0xB4,
            "stored as the game's apostrophe, 0xB4");

        // A pasted NUL is a character the game cannot store like any other, and
        // is refused before anything is written rather than failing the check after.
        String refusal = null;
        try { StorageEdit.renameBox(before, 3, "A\0B"); }
        catch (IllegalArgumentException e) { refusal = e.getMessage(); }
        check(refusal != null && refusal.startsWith("The game has no character"),
            "a NUL in a box name is refused up front, got " + refusal);
    }

    /** A wallpaper change touches exactly one byte and wraps by the game's count. */
    private static void changesTheWallpaper() {
        var before = save();
        var after = StorageEdit.wallpaper(before, 3, 7);
        var reread = Gen3Save.read(after);
        check(reread.boxWallpaper(reread.storage(), 3) == 7, "the wallpaper reads back as written");
        check(reread.boxWallpaper(reread.storage(), 2) == Gen3Save.read(before).boxWallpaper(Gen3Save.read(before).storage(), 2),
            "no other box's wallpaper changed");
        var wrapped = StorageEdit.wallpaper(before, 3, Gen3Save.WALLPAPERS);
        check(Gen3Save.read(wrapped).boxWallpaper(Gen3Save.read(wrapped).storage(), 3) == 0,
            "an id past the end wraps to the first wallpaper");
    }

    /** verifyMove refuses anything but the intended change. */
    private static void verifyMoveRefusesCollateralChanges() {
        var raw = save();
        var prepared = Gen3Save.read(raw);
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(raw, 280, 7, 3), 0).encode();
        var bystander = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(raw, 285, 8, 4), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(0, 3), Gen3Pokemon.BOX_SIZE);
        System.arraycopy(bystander, 0, storage, Gen3Save.slotOffset(1, 2), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();
        var good = StorageEdit.move(before, box(0, 3), box(0, 7));

        var wantedSlots = new java.util.HashMap<Integer, byte[]>();
        wantedSlots.put(Gen3Save.slotOffset(0, 7), planted);
        wantedSlots.put(Gen3Save.slotOffset(0, 3), null);
        StorageEdit.verifyMove(before, good, wantedSlots, java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        check(true, "the honest change is accepted");

        // A byte changed inside an occupied slot nobody moved — a nickname
        // byte, which an identity check alone would never see.
        var sneaky = Gen3Save.read(good);
        var s = sneaky.storage();
        s[Gen3Save.slotOffset(1, 2) + 8] ^= 1;
        sneaky.storage(s);
        refusesVerify(() -> StorageEdit.verifyMove(before, sneaky.bytes(), wantedSlots, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()),
            "a byte changed inside an occupied slot nobody moved");

        // Money is outside the party region of section 1.
        var money = Gen3Save.read(good);
        money.section(1)[Gen3Save.MONEY_AT] ^= 1;
        refusesVerify(() -> StorageEdit.verifyMove(before, money.bytes(), wantedSlots, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()),
            "a change outside the party and the PC");

        // A wrong occupant in the destination slot.
        var wrong = Gen3Save.read(good);
        var ws = wrong.storage();
        var other = Gen3Pokemon.decodeFromParty(Gen3Save.read(before).partyRecord(1), 0).encode();
        System.arraycopy(other, 0, ws, Gen3Save.slotOffset(0, 7), Gen3Pokemon.BOX_SIZE);
        wrong.storage(ws);
        refusesVerify(() -> StorageEdit.verifyMove(before, wrong.bytes(), wantedSlots, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()),
            "a different Pokémon than the one intended");

        // A party member gained that no move explains.
        var grew = Gen3Save.read(good);
        grew.party(List.of(Gen3Save.read(before).partyRecord(0), Gen3Save.read(before).partyRecord(1),
            Gen3Save.read(before).partyRecord(1)));
        refusesVerify(() -> StorageEdit.verifyMove(before, grew.bytes(), wantedSlots, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()),
            "a party member gained that no move explains");

        // The storage header (the box the PC had open) and the box names are
        // outside the slots a move touches; a change there is never a move's.
        var header = Gen3Save.read(good);
        var hs = header.storage();
        hs[0] ^= 1;
        header.storage(hs);
        refusesVerify(() -> StorageEdit.verifyMove(before, header.bytes(), wantedSlots, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()),
            "a change to the storage header");

        var names = Gen3Save.read(good);
        var ns = names.storage();
        ns[Gen3Save.BOX_NAMES_AT + 3] ^= 1;
        names.storage(ns);
        refusesVerify(() -> StorageEdit.verifyMove(before, names.bytes(), wantedSlots, java.util.Map.of(), java.util.Map.of(), java.util.Map.of()),
            "a change to a box name");
    }

    /** verifyRename and verifyWallpaper refuse changes outside their one allowed region. */
    private static void verifyNameAndWallpaperRefuseCollateralChanges() {
        var before = save();
        var renamed = StorageEdit.renameBox(before, 3, "STUDY");
        StorageEdit.verifyRename(before, renamed, 3, "STUDY");
        check(true, "the honest rename is accepted");

        var sneaky = Gen3Save.read(renamed);
        var s = sneaky.storage();
        s[Gen3Save.WALLPAPERS_AT + 2] ^= 1;   // another box's wallpaper
        sneaky.storage(s);
        refusesVerify(() -> StorageEdit.verifyRename(before, sneaky.bytes(), 3, "STUDY"),
            "a rename that also changed another box's wallpaper");

        var papered = StorageEdit.wallpaper(before, 3, 7);
        StorageEdit.verifyWallpaper(before, papered, 3, 7);
        check(true, "the honest wallpaper change is accepted");

        var sneakyPaper = Gen3Save.read(papered);
        var sp = sneakyPaper.storage();
        sp[Gen3Save.slotOffset(5, 5) + 4] ^= 1;   // a Pokémon slot, far from the wallpaper byte
        sneakyPaper.storage(sp);
        refusesVerify(() -> StorageEdit.verifyWallpaper(before, sneakyPaper.bytes(), 3, 7),
            "a wallpaper change that also touched a Pokémon");
    }

    /**
     * A withdrawal re-derives level and stats, which the substructure checksum
     * never covers. verifyMove re-derives them again from the source box record
     * and compares all 100 bytes, so a wrong derivation cannot ship.
     */
    private static void verifyMoveRefusesAWrongWithdrawal() {
        var raw = save();
        var prepared = Gen3Save.read(raw);
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(raw, 280, 7, 3), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(2, 5), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();

        var wantedSlots = new java.util.HashMap<Integer, byte[]>();
        wantedSlots.put(Gen3Save.slotOffset(2, 5), null);
        var gain = java.util.Map.of(identity(planted), 1);
        var derived = java.util.Map.of(identity(planted), planted);

        StorageEdit.verifyMove(before, withdrawn(before, Gen3Pokemon.toParty(planted, 0)),
            wantedSlots, gain, java.util.Map.of(), derived);
        check(true, "the honest withdrawal is accepted");

        var bad = Gen3Pokemon.toParty(planted, 0);
        bad[0x56] ^= 1;   // current HP, a field the substructure checksum never covers
        refusesVerify(() -> StorageEdit.verifyMove(before, withdrawn(before, bad),
                wantedSlots, gain, java.util.Map.of(), derived),
            "a withdrawal whose stats were computed wrong");
    }

    /** A party record with one field changed and its checksum made good, as the game would store it. */
    private static byte[] altered(byte[] partyRecord, java.util.function.Consumer<Gen3Pokemon> change) {
        var mon = Gen3Pokemon.decodeFromParty(partyRecord, 0);
        change.accept(mon);
        return mon.encodeForParty();
    }

    private static void refusesMove(byte[] before, StorageEdit.Place from, StorageEdit.Place to, String why) {
        try { StorageEdit.move(before, from, to); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("accepted " + why);
    }

    /**
     * The game's "That's your last POKéMON!": the party must keep a member able
     * to battle — hatched, with HP left — not merely a member.
     */
    private static void keepsOneAbleToBattle() {
        var raw = save();
        var start = Gen3Save.read(raw);
        var egg = altered(start.partyRecord(1), mon -> mon.ivsEggAbility |= 1 << 30);
        var withEgg = Gen3Save.read(raw);
        withEgg.party(List.of(start.partyRecord(0), egg));
        refusesMove(withEgg.bytes(), party(0), box(0, 0), "depositing the only hatched member, leaving an egg");

        var fainted = altered(start.partyRecord(1), mon -> mon.currentHp = 0);
        var withFainted = Gen3Save.read(raw);
        withFainted.party(List.of(start.partyRecord(0), fainted));
        refusesMove(withFainted.bytes(), party(0), box(0, 0), "depositing the only member with HP left");
        check(Gen3Save.read(StorageEdit.move(withFainted.bytes(), party(1), box(0, 0))).partyCount() == 1,
            "the fainted one can still be deposited");

        // Swapping the only able member for a boxed egg is the same thing by another route.
        var solo = Gen3Save.read(raw);
        solo.party(List.of(start.partyRecord(0)));
        var storage = solo.storage();
        System.arraycopy(Gen3Pokemon.decodeFromParty(egg, 0).encode(), 0, storage, Gen3Save.slotOffset(0, 0), Gen3Pokemon.BOX_SIZE);
        solo.storage(storage);
        refusesMove(solo.bytes(), box(0, 0), party(0), "swapping the only able member for a boxed egg");
    }

    /** A Pokémon holding Mail stays in the party, as the game insists. */
    private static void keepsMailOutOfTheBoxes() {
        var raw = save();
        var start = Gen3Save.read(raw);
        var courier = altered(start.partyRecord(1), mon -> mon.heldItem = StorageEdit.FIRST_MAIL);
        var withMail = Gen3Save.read(raw);
        withMail.party(List.of(start.partyRecord(0), courier));
        refusesMove(withMail.bytes(), party(1), box(0, 0), "depositing a Pokémon holding Mail");
        var reordered = StorageEdit.move(withMail.bytes(), party(1), party(0));
        check(Gen3Save.read(reordered).party().getFirst().heldItem == StorageEdit.FIRST_MAIL,
            "it can still move within the party");

        var swap = Gen3Save.read(withMail.bytes());
        var storage = swap.storage();
        var boxed = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(raw, 280, 7, 3), 0).encode();
        System.arraycopy(boxed, 0, storage, Gen3Save.slotOffset(0, 0), Gen3Pokemon.BOX_SIZE);
        swap.storage(storage);
        refusesMove(swap.bytes(), box(0, 0), party(1), "swapping a Mail holder out of the party");
    }

    /** The party has no gaps: dropping on any empty party cell adds to the end. */
    private static void anyEmptyPartyCellAddsToTheEnd() {
        var raw = save();
        var prepared = Gen3Save.read(raw);
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(Gen3Fixture.member(raw, 280, 7, 3), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(2, 5), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var after = StorageEdit.move(prepared.bytes(), box(2, 5), party(5));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 3 && reread.party().get(2).nationalDex() == 280,
            "dropped on the last cell, it joins as the third member");
        check(Gen3Save.read(StorageEdit.move(after, party(0), party(4))).party().get(2).nationalDex() == 255,
            "and a member dropped past the end moves to the end");
    }

    private static void refusesVerify(Runnable run, String why) {
        try { run.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("verify accepted " + why);
    }

    public static void main(String[] args) {
        movesWithinABox();
        swapsOntoAnOccupiedSlot();
        movesIntoTheParty();
        depositsFromTheParty();
        swapsWithAPartyMember();
        reordersTheParty();
        refusesTheImpossible();
        renamesABox();
        changesTheWallpaper();
        verifyMoveRefusesCollateralChanges();
        verifyMoveRefusesAWrongWithdrawal();
        verifyNameAndWallpaperRefuseCollateralChanges();
        keepsOneAbleToBattle();
        keepsMailOutOfTheBoxes();
        anyEmptyPartyCellAddsToTheEnd();
        System.out.println("PASS: " + checks + " storage edit checks (moves, swaps, reorder, renames, wallpapers, verifier refusals)");
    }
}
