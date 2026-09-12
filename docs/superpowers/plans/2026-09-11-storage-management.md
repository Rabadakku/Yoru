# Storage Management (#44 remainder) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rearrange Pokémon between boxes and the party, rename boxes and change wallpapers from Yoru while the game is closed, written through the same verified transaction as delivery.

**Architecture:** A pure planner `game/StorageEdit` (bytes in, bytes out) applies each op to a `Gen3Save` — which writes changes as the game's next save and re-reads them — then proves byte-for-byte that nothing changed except what the op was asked to change. `Tracker.editSave` commits with the changed-save guard and a backup first. The Collection page gains Arrange/Rename/Wallpaper controls that are disabled while the game runs.

**Tech Stack:** Java 22, Swing, no runtime dependencies. Spec: `docs/superpowers/specs/2026-09-11-storage-management-design.md`. Branch: `claude/storage-management`.

**Key facts the engineer needs (verified):**
- `Gen3Save` (same package) already provides: `read`, `storage()`, `storage(byte[])`, `party()`, `partyRecords()`, `party(List<byte[]>)`, `partyCount()`, `partyRecord(int)`, `boxed(byte[],int,int)`, `empty(byte[],int)` (package-private static), `slotOffset(int,int)`, `boxName(byte[],int)` / `static boxName(byte[],int,String)`, `boxWallpaper(byte[],int)` / `static boxWallpaper(byte[],int,int)`, `whyNotEditable()`, `bytes()`, `section(int)` (package-private), constants `SECTIONS`, `STORAGE_FIRST`, `STORAGE_LAST`, `CHECKSUMMED`, `PARTY_COUNT_AT`, `PARTY_AT`, `PARTY_LIMIT`, `BOX_NAMES_AT`, `BOX_NAME_BYTES`, `WALLPAPERS_AT`, `WALLPAPERS` (=16), `BOXES`, `PER_BOX`, `ENTRY`, `STORAGE_SIZE`.
- `Gen3Pokemon`: public fields `personality`, `otId`, `experience`; `decode(byte[],int)`, `decodeFromParty(byte[],int)`, `encode()`, `intact(byte[],int)`, `toParty(byte[],int)`, `nationalDex()`, `PARTY_SIZE`, `BOX_SIZE`.
- `Tracker.recordDelivery(byte[] before, byte[] after, Collection<UUID> spent)` — the existing one-write commit; `editSave` follows its shape.
- `Shell.perform(Work)` runs a change, reports failure in a dialog, then rebuilds the current page — so after each edit the page refreshes itself.
- `StorageScreen` (package-private, `ui`): constructor `(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox)`; fields `box`, `selected`; methods `slotAt`, `partyAt`, `box()`, `selected()`, `click` (private); geometry statics `PAD`, `HEADER`, `PARTY_GAP`, `CELL`, `GRID_X`, `GRID_Y`, `gridX()`, `gridY()`, `partyY()`, `boxWidth()`, `COLUMNS`, `ROWS`, `PER_BOX` via `Gen3Save`.
- `CollectionPage` (package-private, `ui`): `view()` builds the page; `storage(Gen3Save)` builds the storage card; `shell.game().running()` says whether the game is open; `Dialogs.input(parent, prompt, title, initial)` returns a String or null on cancel; `Theme.button(text, fn)`, `label(s,size,color)`, `gap(p,h)`, `stack()`, `card()`.

Commit convention: `git commit -m "..."` with a why-focused message, end with the Co-Authored-By trailer. Every command in this plan runs from the repository root.

---

### Task 1: `StorageEdit.move` — box↔box

**Files:**
- Create: `src/main/java/dev/yoru/game/StorageEdit.java`
- Test: `src/test/java/dev/yoru/game/StorageEditTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/yoru/game/StorageEditTest.java`:

```java
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

    public static void main(String[] args) {
        movesWithinABox();
        swapsOntoAnOccupiedSlot();
        System.out.println("StorageEditTest ok (" + checks + " checks)");
    }
}
```

- [ ] **Step 2: Run the test to watch it fail**

Run: `./build.sh && javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes src/test/java/dev/yoru/game/StorageEditTest.java && java -ea -cp build/classes dev.yoru.game.StorageEditTest`
Expected: compile error — `package dev.yoru.game.StorageEdit does not exist`.

- [ ] **Step 3: Implement `StorageEdit` (move only)**

Create `src/main/java/dev/yoru/game/StorageEdit.java`:

```java
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

    /**
     * Moves a Pokémon, or swaps two. Dropping on an empty slot moves; dropping
     * on an occupied slot swaps, exactly as the game's own MOVE POKÉMON does.
     * A party-to-party move reorders the party. The last party member cannot
     * be deposited: the party can never be empty.
     */
    public static byte[] move(byte[] before, Place from, Place to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        var save = Gen3Save.read(before);
        requireEditable(save);
        var storage = save.storage();
        var party = new ArrayList<>(save.partyRecords());

        byte[] moving = boxFormAt(party, storage, from);
        if (moving == null) throw new IllegalArgumentException("Nothing there to move.");
        byte[] target = boxFormAt(party, storage, to);
        if (from.party() && !to.party() && party.size() == 1)
            throw new IllegalArgumentException("That is your last Pokémon. The party can never be empty.");

        var wantedSlots = new HashMap<Integer, String>();
        var partyGain = new HashMap<String, Integer>();
        var partyLoss = new HashMap<String, Integer>();

        if (!to.party() && !from.party()) {
            int toAt = Gen3Save.slotOffset(to.box(), to.slot());
            int fromAt = Gen3Save.slotOffset(from.box(), from.slot());
            writeSlot(storage, toAt, moving);
            wantedSlots.put(toAt, identity(moving));
            if (target == null) { clearSlot(storage, fromAt); wantedSlots.put(fromAt, null); }
            else { writeSlot(storage, fromAt, target); wantedSlots.put(fromAt, identity(target)); }
        } else if (!to.party()) {
            int toAt = Gen3Save.slotOffset(to.box(), to.slot());
            writeSlot(storage, toAt, moving);
            wantedSlots.put(toAt, identity(moving));
            partyLoss.merge(identity(moving), 1, Integer::sum);
            if (target == null) party.remove(from.slot());
            else { party.set(from.slot(), Gen3Pokemon.toParty(target, 0)); partyGain.merge(identity(target), 1, Integer::sum); }
        } else if (!from.party()) {
            int index = Math.min(to.slot(), party.size());
            int fromAt = Gen3Save.slotOffset(from.box(), from.slot());
            if (target == null) {
                party.add(index, Gen3Pokemon.toParty(moving, 0));
                clearSlot(storage, fromAt);
                wantedSlots.put(fromAt, null);
            } else {
                party.set(index, Gen3Pokemon.toParty(moving, 0));
                writeSlot(storage, fromAt, target);
                wantedSlots.put(fromAt, identity(target));
                partyLoss.merge(identity(target), 1, Integer::sum);
            }
            partyGain.merge(identity(moving), 1, Integer::sum);
        } else {
            // Party-to-party: a positional swap, nothing gained or lost.
            var picked = party.remove(from.slot());
            party.add(from.slot() < to.slot() ? to.slot() - 1 : to.slot(), picked);
        }

        save.storage(storage);
        save.party(party);
        byte[] after = save.bytes();
        verifyMove(before, after, wantedSlots, partyGain, partyLoss);
        return after;
    }

    private static void requireEditable(Gen3Save save) {
        String refusal = save.whyNotEditable();
        if (refusal != null) throw new IllegalStateException(refusal);
    }

    /** The Pokémon at a place, box-encoded, or null when the place is empty. */
    private static byte[] boxFormAt(List<byte[]> party, byte[] storage, Place place) {
        if (place.party())
            return place.slot() >= 0 && place.slot() < party.size()
                ? Gen3Pokemon.decodeFromParty(party.get(place.slot()), 0).encode() : null;
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

    private static String identity(byte[] boxRecord) { return identity(Gen3Pokemon.decode(boxRecord, 0)); }

    // ---- proving the change before handing it back --------------------------

    /**
     * Proves a move changed exactly what it says: nothing outside the party
     * region of section 1 and the PC storage; inside the PC, only the slots
     * named in wantedSlots (whose wanted identity may be null for "emptied");
     * and the party multiset of identities changed by exactly partyGain minus
     * partyLoss, with every surviving record's checksum intact.
     *
     * In correct code this never fires, so every branch is exercised directly
     * by the tests with a change it must refuse.
     */
    static void verifyMove(byte[] before, byte[] after, Map<Integer, String> wantedSlots,
                           Map<String, Integer> partyGain, Map<String, Integer> partyLoss) {
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
            if (s1a[i] != s1b[i] && !(i >= Gen3Save.PARTY_COUNT_AT
                && i < Gen3Save.PARTY_AT + Gen3Save.PARTY_LIMIT * Gen3Pokemon.PARTY_SIZE))
                throw new IllegalStateException("Section 1 changed at byte " + i + " outside the party.");
        byte[] storageA = a.storage(), storageB = b.storage();
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                int at = Gen3Save.slotOffset(box, slot);
                var monA = Gen3Save.empty(storageA, at) ? null : Gen3Pokemon.decode(storageA, at);
                var monB = Gen3Save.empty(storageB, at) ? null : Gen3Pokemon.decode(storageB, at);
                if (wantedSlots.containsKey(at)) {
                    String want = wantedSlots.get(at);
                    if (want == null) { if (monB != null) throw new IllegalStateException("Box " + (box + 1)
                        + " slot " + (slot + 1) + " should be empty after the move."); continue; }
                    if (monB == null || !identity(monB).equals(want))
                        throw new IllegalStateException("Box " + (box + 1) + " slot " + (slot + 1)
                            + " does not hold the Pokémon moved there.");
                    if (!Gen3Pokemon.intact(storageB, at))
                        throw new IllegalStateException("A moved record's checksum does not verify.");
                    continue;
                }
                if (!Objects.equals(identity(monA), identity(monB)))
                    throw new IllegalStateException("Box " + (box + 1) + " slot " + (slot + 1) + " changed.");
            }
        var delta = new HashMap<String, Integer>();
        partyGain.forEach((id, n) -> delta.merge(id, n, Integer::sum));
        partyLoss.forEach((id, n) -> delta.merge(id, -n, Integer::sum));
        for (var mon : a.party()) delta.merge(identity(mon), -1, Integer::sum);
        for (var mon : b.party()) delta.merge(identity(mon), 1, Integer::sum);
        delta.values().removeIf(v -> v == 0);
        if (!delta.isEmpty()) throw new IllegalStateException("The party gained or lost the wrong members: " + delta);
        for (int i = 0; i < b.partyCount(); i++)
            if (!Gen3Pokemon.intact(b.partyRecord(i), 0))
                throw new IllegalStateException("Party member " + (i + 1) + "'s checksum does not verify.");
    }
}
```

- [ ] **Step 4: Run the test to watch it pass**

Run the Step 2 command again. Expected: `StorageEditTest ok (12 checks)`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/yoru/game/StorageEdit.java src/test/java/dev/yoru/game/StorageEditTest.java
git commit -m "Move Pokémon between PC slots from Yoru, game closed (#44)

StorageEdit.move is a pure bytes-in/bytes-out edit: the change goes through
Gen3Save, which writes it as the game's next save and re-reads it, and then
verifyMove proves nothing else moved — the same discipline as delivery. A
drop on an occupied slot swaps, as the game's own MOVE POKÉMON does.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Party moves, swaps, and the last-member rule

**Files:**
- Modify: `src/test/java/dev/yoru/game/StorageEditTest.java`
- Modify: `src/main/java/dev/yoru/game/StorageEdit.java` (no change expected — Task 1 already implements these paths; this task proves them)

- [ ] **Step 1: Extend the test with box→party, party→box, swap, reorder, refusal**

Append to `StorageEditTest` (inside the class, before `main`) and extend `main`:

```java
    /** box→party appends or inserts, and the source slot empties. */
    private static void movesIntoTheParty() {
        var prepared = Gen3Save.read(save());
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(prepared.partyRecord(1), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(2, 5), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();

        var after = StorageEdit.move(before, box(2, 5), party(1));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 3, "the party grew to three");
        check(Gen3Save.empty(reread.storage(), Gen3Save.slotOffset(2, 5)), "the source slot is empty");
        check(reread.party().get(1).personality == Gen3Pokemon.decode(planted, 0).personality,
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
    }

    /** Dropping onto an occupied party position swaps the two. */
    private static void swapsWithAPartyMember() {
        var prepared = Gen3Save.read(save());
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(prepared.partyRecord(0), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(5, 0), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();

        var after = StorageEdit.move(before, box(5, 0), party(0));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 2, "a swap leaves the party the same size");
        check(reread.party().getFirst().personality == Gen3Pokemon.decode(planted, 0).personality,
            "the picked member leads the party");
        check(reread.boxed(reread.storage(), 5, 0) != null
            && reread.boxed(reread.storage(), 5, 0).otId != reread.party().getFirst().otId,
            "and the displaced leader went to the box slot");
    }

    /** party→party reorders without changing who is in it. */
    private static void reordersTheParty() {
        var before = save();
        var after = StorageEdit.move(before, party(0), party(1));
        var reread = Gen3Save.read(after);
        check(reread.partyCount() == 2, "a reorder leaves the party the same size");
        check(reread.party().getFirst().nationalDex() == 25 && reread.party().get(1).nationalDex() == 255,
            "the members traded places");
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
    }
```

And change `main` to:

```java
    public static void main(String[] args) {
        movesWithinABox();
        swapsOntoAnOccupiedSlot();
        movesIntoTheParty();
        depositsFromTheParty();
        swapsWithAPartyMember();
        reordersTheParty();
        refusesTheImpossible();
        System.out.println("StorageEditTest ok (" + checks + " checks)");
    }
```

- [ ] **Step 2: Run the test**

Run: `./build.sh && javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes src/test/java/dev/yoru/game/StorageEditTest.java && java -ea -cp build/classes dev.yoru.game.StorageEditTest`
Expected: PASS on first run (Task 1 implemented all paths). If any check fails, fix `StorageEdit.move` — the bug is there, not in the test — then rerun.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/yoru/game/StorageEditTest.java
git commit -m "Cover party moves, swaps, reorders and the last-member rule (#44)

The paths existed from the first commit; these checks pin them: a deposit
shifts the party up, a drop onto an occupied party position swaps, a
party-to-party move reorders without gaining or losing anyone, and the party
can never be emptied.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: `renameBox` and `wallpaper`, with their verifiers

**Files:**
- Modify: `src/test/java/dev/yoru/game/StorageEditTest.java`
- Modify: `src/main/java/dev/yoru/game/StorageEdit.java`

- [ ] **Step 1: Write the failing test**

Append to `StorageEditTest` and extend `main`:

```java
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
    }

    /** A wallpaper change touches exactly one byte and wraps by the game's count. */
    private static void changesTheWallpaper() {
        var before = save();
        var after = StorageEdit.wallpaper(before, 3, 7);
        var reread = Gen3Save.read(after);
        check(reread.boxWallpaper(reread.storage(), 3) == 7, "the wallpaper reads back as written");
        check(reread.boxWallpaper(reread.storage(), 2) == Gen3Save.read(before).boxWallpaper(Gen3Save.read(before).storage(), 2),
            "no other box's wallpaper changed");
        check(StorageEdit.wallpaper(before, 3, Gen3Save.WALLPAPERS).equals(after)
            || Gen3Save.read(StorageEdit.wallpaper(before, 3, Gen3Save.WALLPAPERS)).boxWallpaper(
                Gen3Save.read(StorageEdit.wallpaper(before, 3, Gen3Save.WALLPAPERS)).storage(), 3) == 0,
            "an id past the end wraps to the first wallpaper");
    }
```

- [ ] **Step 2: Run to watch it fail**

Same command as Task 2 Step 2. Expected: `StorageEdit.renameBox`/`wallpaper` do not exist — compile error.

- [ ] **Step 3: Implement the two ops and their verifiers**

Append to `StorageEdit` (before the `// ---- proving` section):

```java
    /** Renames a box, in the game's own encoding: one to eight characters. */
    public static byte[] renameBox(byte[] before, int box, String name) {
        var save = Gen3Save.read(before);
        requireEditable(save);
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
```

And append to the proving section:

```java
    /** Everything outside one box name's nine bytes must be identical, and the name must read back. */
    static void verifyRename(byte[] before, byte[] after, int box, String name) {
        int start = Gen3Save.BOX_NAMES_AT + box * Gen3Save.BOX_NAME_BYTES;
        assertOnlyChanges(before, after, at -> at >= start && at < start + Gen3Save.BOX_NAME_BYTES);
        var saved = Gen3Save.read(after);
        if (!saved.boxName(saved.storage(), box).strip().equals(name.strip()))
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
```

- [ ] **Step 4: Run to watch it pass**

Same command. Expected: `StorageEditTest ok (… checks)` — count grows from 12 by 9.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/yoru/game/StorageEdit.java src/test/java/dev/yoru/game/StorageEditTest.java
git commit -m "Rename boxes and change wallpapers from Yoru, game closed (#44)

Each op is verified the way a move is: outside the one name's nine bytes or
the one wallpaper byte, every byte of the save must be identical. The name
goes through the game's own Gen3Text encoding with the game's own
eight-character limit.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: The verifiers refuse collateral changes

**Files:**
- Modify: `src/test/java/dev/yoru/game/StorageEditTest.java`

- [ ] **Step 1: Write the failing test** (the mutations fail, so the test passes only when verify refuses)

Append to `StorageEditTest` and extend `main`:

```java
    /** verifyMove refuses anything but the intended change. */
    private static void verifyMoveRefusesCollateralChanges() {
        var prepared = Gen3Save.read(save());
        var storage = prepared.storage();
        var planted = Gen3Pokemon.decodeFromParty(prepared.partyRecord(0), 0).encode();
        System.arraycopy(planted, 0, storage, Gen3Save.slotOffset(0, 3), Gen3Pokemon.BOX_SIZE);
        prepared.storage(storage);
        var before = prepared.bytes();
        var good = StorageEdit.move(before, box(0, 3), box(0, 7));

        var wantedSlots = new HashMap<Integer, String>();
        wantedSlots.put(Gen3Save.slotOffset(0, 7), Gen3Pokemon.decode(planted, 0).personality + ":"
            + Gen3Pokemon.decode(planted, 0).otId + ":" + Gen3Pokemon.decode(planted, 0).nationalDex() + ":"
            + Gen3Pokemon.decode(planted, 0).experience);
        wantedSlots.put(Gen3Save.slotOffset(0, 3), null);
        StorageEdit.verifyMove(before, good, wantedSlots, Map.of(), Map.of());
        check(true, "the honest change is accepted");

        // A byte changed in a slot nobody moved.
        var sneaky = Gen3Save.read(good);
        var s = sneaky.storage();
        s[Gen3Save.slotOffset(1, 2) + 10] ^= 1;
        sneaky.storage(s);
        refusesVerify(() -> StorageEdit.verifyMove(before, sneaky.bytes(), wantedSlots, Map.of(), Map.of()),
            "a byte changed in a slot nobody moved");

        // Money is outside the party region of section 1.
        var money = Gen3Save.read(good);
        money.section(1)[Gen3Save.MONEY_AT] ^= 1;
        refusesVerify(() -> StorageEdit.verifyMove(before, money.bytes(), wantedSlots, Map.of(), Map.of()),
            "a change outside the party and the PC");

        // A wrong occupant in the destination slot.
        var wrong = Gen3Save.read(good);
        var ws = wrong.storage();
        var other = Gen3Pokemon.decodeFromParty(Gen3Save.read(before).partyRecord(1), 0).encode();
        System.arraycopy(other, 0, ws, Gen3Save.slotOffset(0, 7), Gen3Pokemon.BOX_SIZE);
        wrong.storage(ws);
        refusesVerify(() -> StorageEdit.verifyMove(before, wrong.bytes(), wantedSlots, Map.of(), Map.of()),
            "a different Pokémon than the one intended");

        // A party member gained that no move explains.
        var grew = Gen3Save.read(good);
        grew.party(List.of(Gen3Save.read(before).partyRecord(0), Gen3Save.read(before).partyRecord(1),
            Gen3Save.read(before).partyRecord(1)));
        refusesVerify(() -> StorageEdit.verifyMove(before, grew.bytes(), wantedSlots, Map.of(), Map.of()),
            "a party member gained that no move explains");
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

    private static void refusesVerify(Runnable run, String why) {
        try { run.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("verify accepted " + why);
    }
```

Add to `main`: `verifyMoveRefusesCollateralChanges(); verifyNameAndWallpaperRefuseCollateralChanges();` and the import `java.util.Map; java.util.HashMap;` at the top (currently only `List`/`UUID` are imported — add both).

- [ ] **Step 2: Run to watch it pass** (the mutations in the test target real gaps; if a check fails, the test's fixture byte math is off — recompute it, don't weaken the verifier)

Run the same command. Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/yoru/game/StorageEditTest.java
git commit -m "Drive the storage verifiers directly, with every change they must refuse (#44)

verifyMove/verifyRename/verifyWallpaper never fire in correct code, so their
refusals are exercised the GameDeliveryTest way: honest changes pass, and a
changed slot, money, a wrong occupant, a mystery party member, a neighbor's
wallpaper or a touched Pokémon slot each fail.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: `Tracker.editSave` — the one-write commit with a backup

**Files:**
- Modify: `src/main/java/dev/yoru/application/Tracker.java` (add `editSave` after `recordDelivery`, ~line 297)
- Test: create `src/test/java/dev/yoru/SaveEditTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/yoru/SaveEditTest.java`:

```java
package dev.yoru;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.GameSave;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Fixture;
import dev.yoru.game.StorageEdit;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The commit behind storage edits: one write, a backup first, and a refusal
 * when the save changed underneath. Also proves two vaults holding the same
 * ROM save diverge independently (#40).
 */
public final class SaveEditTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);

    private static final class Vault implements Repository {
        State state = State.empty();
        int saves, backups;
        boolean fail;
        public State load() { return state; }
        public void save(State next) throws IOException { if (fail) throw new IOException("vault write failed"); saves++; state = next; }
        public void backup() throws IOException { if (fail) throw new IOException("backup failed"); backups++; }
        public void close() { }
    }

    private static Tracker trackerWith(Vault repo, byte[] saveBytes) throws IOException {
        repo.state = State.empty().withGame(new GameSave(saveBytes, CLOCK.instant()));
        return new Tracker(repo, CLOCK);
    }

    /** The edit commits in one write, after a backup, and touches nothing else. */
    private static void commitsOneWriteAfterABackup() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, Gen3Fixture.save(2, 4));
        byte[] before = tracker.state().game().bytes();
        byte[] after = StorageEdit.renameBox(before, 0, "STUDY");

        tracker.editSave(before, after);
        check(repo.saves == 1, "the save was committed in one write, got " + repo.saves);
        check(repo.backups == 1, "the vault was backed up first, got " + repo.backups);
        check(tracker.state().game().holds(after), "the vault holds the edited save");
    }

    /** An edit planned against one save is never written over another. */
    private static void refusesWhenTheSaveChangedUnderneath() throws IOException {
        var repo = new Vault();
        var tracker = trackerWith(repo, Gen3Fixture.save(2, 4));
        byte[] before = tracker.state().game().bytes();
        byte[] other = Gen3Fixture.save(4, 4);
        boolean refused = false;
        try { tracker.editSave(other, StorageEdit.renameBox(other, 0, "STUDY")); }
        catch (IllegalStateException e) { refused = true; }
        check(refused, "an edit planned against another save is refused");
        check(tracker.state().game().holds(before), "and the vault's save is untouched");
        check(repo.saves == 0 && repo.backups == 0, "with nothing written or backed up");
    }

    /** Two vaults holding the same ROM save diverge independently — #40's isolation criterion. */
    private static void twoVaultsDivergeIndependently() throws IOException {
        byte[] sameRomSave = Gen3Fixture.save(2, 4);
        var one = new Vault();
        var two = new Vault();
        var a = trackerWith(one, sameRomSave);
        var b = trackerWith(two, sameRomSave);

        a.editSave(sameRomSave, StorageEdit.renameBox(sameRomSave, 0, "ALPHA"));
        var bAfter = StorageEdit.wallpaper(sameRomSave, 1, 9);
        b.editSave(sameRomSave, bAfter);

        check(!one.state.game().equals(two.state.game()), "each vault holds its own edited save");
        check(a.state().game().holds(StorageEdit.renameBox(sameRomSave, 0, "ALPHA")), "vault A has only its own rename");
        check(b.state().game().holds(bAfter), "vault B has only its own wallpaper change");
    }

    public static void main(String[] args) throws IOException {
        commitsOneWriteAfterABackup();
        refusesWhenTheSaveChangedUnderneath();
        twoVaultsDivergeIndependently();
        System.out.println("PASS: " + checks + " save-edit checks (one write, backup first, changed-save refusal, vault isolation)");
    }
}
```

Note `List` import is unused in the first draft — remove it if javac warns; it compiles either way, but keep imports clean.

- [ ] **Step 2: Run to watch it fail**

Run: `./build.sh && javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes src/test/java/dev/yoru/SaveEditTest.java && java -ea -cp build/classes dev.yoru.SaveEditTest`
Expected: compile error — `editSave` does not exist on `Tracker`.

- [ ] **Step 3: Implement `Tracker.editSave`**

In `src/main/java/dev/yoru/application/Tracker.java`, after `recordDelivery` (ends line 285 with the closing brace before `replaceGameSave`), insert:

```java
    /**
     * Commits an edit to the game's save, made while the game is closed (#44).
     *
     * The same discipline as recordDelivery: refused when the vault's save is
     * no longer the one the edit was planned against, so a change made from one
     * save is never written over another. The vault is backed up first, because
     * this writes the game's save without the game's help.
     */
    public void editSave(byte[] before, byte[] after) throws IOException {
        if (state.game() == null || !state.game().holds(before))
            throw new IllegalStateException("The game save changed while Yoru was working on it, so nothing was changed.");
        if (Arrays.equals(before, after)) return;
        repository.backup();
        commit(state.withGame(new GameSave(after, clock.instant())));
    }
```

`Arrays` is already imported in Tracker (it uses `Arrays.equals` in `recordDelivery`), `GameSave` comes from the existing `import dev.yoru.domain.Model.*`.

- [ ] **Step 4: Run to watch it pass**

Same command. Expected: `PASS: 12 save-edit checks (one write, backup first, changed-save refusal, vault isolation)`.

- [ ] **Step 5: Wire the new suite into `./test.sh`**

In `test.sh`, after line 30 (`java -ea -cp build/classes dev.yoru.game.GameDeliveryTest`) insert:

```sh
java -ea -cp build/classes dev.yoru.game.StorageEditTest
java -ea -cp build/classes dev.yoru.SaveEditTest
```

Then run: `./test.sh` — the full suite must stay green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/yoru/application/Tracker.java src/test/java/dev/yoru/SaveEditTest.java test.sh
git commit -m "Commit storage edits as one vault write, backed up first (#44)

Tracker.editSave is recordDelivery's shape: the changed-save guard refuses a
plan made against a different save, and repository.backup() runs first because
this writes the game's save without the game's help. SaveEditTest also proves
#40's isolation criterion: two vaults holding the same ROM save diverge
independently.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: `StorageScreen` arranging mode

**Files:**
- Modify: `src/main/java/dev/yoru/ui/StorageScreen.java`

- [ ] **Step 1: Add the arranging state and the pick/place click path**

Read `StorageScreen.java` first. Then:

1. Add imports: `import dev.yoru.game.StorageEdit;` and `import java.util.function.BiConsumer;` and `import javax.swing.AbstractAction;` and `import javax.swing.KeyStroke;`.

2. Add fields after `private Gen3Pokemon selected;`:

```java
    private final BiConsumer<StorageEdit.Place, StorageEdit.Place> onArrange;
    private boolean arranging;
    private Gen3Pokemon picked;
    private StorageEdit.Place pickedPlace;
```

3. Change the existing constructor into a delegating overload, and add the full one:

```java
    StorageScreen(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox) {
        this(save, box, onSelect, onBox, null);
    }

    StorageScreen(Gen3Save save, int box, Consumer<Gen3Pokemon> onSelect, IntConsumer onBox,
                  BiConsumer<StorageEdit.Place, StorageEdit.Place> onArrange) {
        this.save = save;
        this.storage = save.storage();
        this.party = save.party();
        this.onSelect = onSelect;
        this.onBox = onBox;
        this.onArrange = onArrange;
        this.box = Math.floorMod(box, Gen3Save.BOXES);
        setOpaque(false);
        setPreferredSize(new Dimension(boxWidth() + PARTY_GAP + CELL + PAD * 2, HEADER + boxHeight() + PAD * 2));
        setAlignmentX(LEFT_ALIGNMENT);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { click(e.getX(), e.getY()); }
        });
        // Escape drops whatever was picked up, whatever has focus.
        getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("ESCAPE"), "storage.cancel");
        getActionMap().put("storage.cancel", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { cancelPickup(); }
        });
    }
```

4. Add accessors and the arrange path. After `int box() { return box; }` and `Gen3Pokemon selected() { return selected; }` add:

```java
    void setArranging(boolean on) {
        arranging = on;
        picked = null;
        pickedPlace = null;
        repaint();
    }

    boolean arranging() { return arranging; }

    Gen3Pokemon picked() { return picked; }

    /** Drops whatever was picked up without moving anything. */
    void cancelPickup() {
        picked = null;
        pickedPlace = null;
        repaint();
    }
```

5. In `click(int x, int y)`, after the two arrow lines, insert the arranging branch before the existing select code:

```java
        if (arranging) { arrangeAt(x, y); return; }
```

6. Add the arrange path (after `click`, before `turn`):

```java
    private void arrangeAt(int x, int y) {
        StorageEdit.Place place = null;
        boolean occupied = false;
        int slot = slotAt(x, y);
        if (slot >= 0) {
            place = new StorageEdit.Place(false, box, slot);
            occupied = save.boxed(storage, box, slot) != null;
        } else {
            int index = partyAt(x, y);
            if (index >= 0) {
                place = new StorageEdit.Place(true, -1, index);
                occupied = index < party.size();
            }
        }
        if (place == null) { cancelPickup(); return; }
        if (picked == null) {
            if (!occupied) return;                       // nothing there to pick up
            picked = occupiedAt(place);
            pickedPlace = place;
            repaint();
            return;
        }
        if (pickedPlace.equals(place)) { cancelPickup(); return; }   // same spot = change of mind
        var from = pickedPlace;
        cancelPickup();
        if (onArrange != null) onArrange.accept(from, place);
    }

    private Gen3Pokemon occupiedAt(StorageEdit.Place place) {
        if (place.party()) return place.slot() < party.size() ? party.get(place.slot()) : null;
        return save.boxed(storage, place.box(), place.slot());
    }
```

7. In `paintComponent`, after the box name and arrows are drawn (after `drawArrow(g, nextArrow(), false);`), add the arranging hint and the picked marker:

```java
        if (arranging) {
            g.setFont(getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(Theme.CYAN);
            g.drawString(picked == null ? "PICK A POKÉMON" : "CHOOSE A SPOT", gx + 10, gy - 8);
        }
        if (picked != null) {
            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            g.setColor(Theme.GOLD);
            String who = picked.isEgg() ? "Egg" : GameView.name(picked);
            int w = g.getFontMetrics().stringWidth(who);
            g.drawString("MOVING  " + who, gx + boxWidth() - w - 46, PAD + 18);
        }
```

- [ ] **Step 2: Compile**

Run: `./build.sh && javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes src/main/java/dev/yoru/ui/StorageScreen.java`
Expected: compiles.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/yoru/ui/StorageScreen.java
git commit -m "Give the storage screen a pick-and-place arranging mode (#44)

While arranging, a click picks a Pokémon up, a second click places or swaps
it, clicking it again or pressing Escape cancels. The page decides what the
moves do; the screen only reports from and to.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: Collection page controls — Arrange, Rename box, Wallpaper

**Files:**
- Modify: `src/main/java/dev/yoru/ui/CollectionPage.java`

- [ ] **Step 1: Wire the buttons and the edit callbacks**

Read `CollectionPage.java` first. In `storage(Gen3Save save)`:

1. Replace the body between the `POKÉMON STORAGE` label and the wallpaper-hint `if` with a version that carries the buttons and an arranging-capable screen. The current block is:

```java
        var details = stack();
        details.setPreferredSize(new Dimension(240, StorageScreen.boxHeight()));
        if (box < 0) box = Gen3Save.currentBox(save.storage());
        var screen = new StorageScreen(save, box, mon -> describe(details, mon), turned -> box = turned);
        var body = new JPanel(new BorderLayout(20, 0));
        body.setOpaque(false);
        body.setAlignmentX(0);
        body.add(screen, BorderLayout.WEST);
        body.add(details, BorderLayout.CENTER);
        describe(details, null);
        c.add(body);
```

Replace it with:

```java
        boolean gameOpen = shell.game().running();
        var controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.setAlignmentX(0);
        var arrange = button(gameOpen ? "The game is running" : "Arrange", gameOpen ? () -> { } : null);
        arrange.setName("collection.arrange");
        arrange.setEnabled(!gameOpen);
        controls.add(arrange);
        var rename = button("Rename box…", this::renameBox);
        rename.setName("collection.rename");
        rename.setEnabled(!gameOpen);
        controls.add(rename);
        var paper = button("Wallpaper", this::nextWallpaper);
        paper.setName("collection.wallpaper");
        paper.setEnabled(!gameOpen);
        controls.add(paper);
        gap(c, 8);
        c.add(controls);

        var details = stack();
        details.setPreferredSize(new Dimension(240, StorageScreen.boxHeight()));
        if (box < 0) box = Gen3Save.currentBox(save.storage());
        var screen = new StorageScreen(save, box, mon -> describe(details, mon), turned -> box = turned,
            (from, to) -> shell.perform(() -> {
                var before = shell.tracker().state().game().bytes();
                shell.tracker().editSave(before, StorageEdit.move(before, from, to));
            }));
        var body = new JPanel(new BorderLayout(20, 0));
        body.setOpaque(false);
        body.setAlignmentX(0);
        body.add(screen, BorderLayout.WEST);
        body.add(details, BorderLayout.CENTER);
        describe(details, null);
        c.add(body);

        final var arrangeToggle = arrange;
        final var theScreen = screen;
        arrangeToggle.addActionListener(e -> {
            theScreen.setArranging(!theScreen.arranging());
            arrangeToggle.setText(theScreen.arranging() ? "Done arranging" : "Arrange");
        });
```

Note: `Theme.button(text, Runnable)` takes a Runnable; passing `null` for the disabled case would NPE on click registration — pass `() -> { }` as above. Import `dev.yoru.game.StorageEdit` at the top of the file.

2. Add the two handlers to the class, after `openEncounter()`:

```java
    /** Renames the open box, written through the same verified transaction as delivery. */
    private void renameBox() {
        var state = shell.tracker().state();
        var save = GameView.save(state);
        if (save == null) return;
        String current = save.boxName(save.storage(), box).strip();
        String name = Dialogs.input(shell.owner(), "Rename this box: 1 to 8 characters, the game's own limit.", "Rename box", current);
        if (name == null || name.strip().equals(current) || name.isBlank()) return;
        shell.perform(() -> {
            var before = shell.tracker().state().game().bytes();
            shell.tracker().editSave(before, StorageEdit.renameBox(before, box, name));
        });
    }

    /** Cycles the open box's wallpaper to the next one the game ships. */
    private void nextWallpaper() {
        var state = shell.tracker().state();
        var save = GameView.save(state);
        if (save == null) return;
        int current = save.boxWallpaper(save.storage(), box);
        shell.perform(() -> {
            var before = shell.tracker().state().game().bytes();
            shell.tracker().editSave(before, StorageEdit.wallpaper(before, box, current + 1));
        });
    }
```

- [ ] **Step 2: Compile and run the UI suite**

Run: `./build.sh && javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes src/main/java/dev/yoru/ui/CollectionPage.java && java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.StorageTest && java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.UiTest`
Expected: both PASS (the page renders with the new controls; existing behavior unchanged).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/yoru/ui/CollectionPage.java
git commit -m "Arrange, rename and wallpaper controls on the Collection page (#44)

The controls exist only while the game is closed — the running banner already
told players why, and now the buttons say it too. Every edit goes through
shell.perform, which commits via Tracker.editSave and rebuilds the page on
failure as well as success.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: UI coverage and the arranging preview

**Files:**
- Modify: `src/test/java/dev/yoru/ui/StorageTest.java`
- Modify: `src/test/java/dev/yoru/ui/Preview.java`

- [ ] **Step 1: Extend `StorageTest` with pick-and-place through real MouseEvents**

Read `StorageTest.java` first (it already has `save()`, `click(...)`, geometry helpers). Append before `main`:

```java
    /** In arranging mode, pick up and place, through the clicks a player makes. */
    private static void arrangingPicksUpAndPlaces() {
        var requests = new ArrayList<String>();
        var screen = new StorageScreen(save(), BOX, mon -> { }, box -> { }, (from, to) ->
            requests.add(from + " -> " + to));
        screen.setArranging(true);
        int cell = StorageScreen.CELL;

        // Pick up the boxed Pokémon, then place it in the party's first empty cell.
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4);
        check(screen.picked() != null && screen.picked().nationalDex() == BOXED, "the boxed Pokémon is picked up");
        click(screen, partyX(), StorageScreen.partyY() + 2 * cell + 4);
        check(requests.size() == 1 && requests.get(0).equals("Place[party=false, box=" + BOX + ", slot=" + SLOT
            + "] -> Place[party=true, box=-1, slot=2]"), "the page hears from and to, got " + requests);
        check(screen.picked() == null, "and the pickup is cleared after placing");

        // Clicking the picked Pokémon again cancels instead of reporting.
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4);
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4);
        check(requests.size() == 1, "clicking the picked Pokémon again cancels, got " + requests);

        // Turning arranging off drops the pickup.
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4);
        screen.setArranging(false);
        check(screen.picked() == null, "leaving arranging mode drops the pickup");
    }
```

Add `arrangingPicksUpAndPlaces();` to `main` and update the println to mention arranging.

- [ ] **Step 2: Extend `StorageTest` with the end-to-end edit through the tracker**

Append after `arrangingPicksUpAndPlaces`:

```java
    /** The full trip: clicks pick up and place, the vault's save changes, nothing else does. */
    private static void aMoveEditsTheSave() throws IOException {
        var repo = new MemoryRepo();
        var tracker = new dev.yoru.application.Tracker(repo, java.time.Clock.systemUTC());
        tracker.gameSaved(save().bytes());
        var screen = new StorageScreen(dev.yoru.game.Gen3Save.read(tracker.state().game().bytes()), BOX,
            mon -> { }, box -> { }, (from, to) -> {
                try {
                    var before = tracker.state().game().bytes();
                    tracker.editSave(before, dev.yoru.game.StorageEdit.move(before, from, to));
                } catch (IOException e) { throw new RuntimeException(e); }
            });
        screen.setArranging(true);
        int cell = StorageScreen.CELL;
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + (SLOT / StorageScreen.COLUMNS) * cell + 4);
        click(screen, StorageScreen.gridX() + (SLOT % StorageScreen.COLUMNS) * cell + 4,
            StorageScreen.gridY() + ((SLOT + 1) % Gen3Save.PER_BOX / StorageScreen.COLUMNS) * cell + 4);
        var after = dev.yoru.game.Gen3Save.read(tracker.state().game().bytes());
        check(after.boxed(after.storage(), BOX, SLOT) == null, "the source slot emptied in the vault's save");
        int toSlot = SLOT + 1;
        check(after.boxed(after.storage(), BOX, toSlot) != null, "and the neighbour slot holds it");
    }

    private static final class MemoryRepo implements dev.yoru.application.Repository {
        dev.yoru.domain.Model.State state = dev.yoru.domain.Model.State.empty();
        public dev.yoru.domain.Model.State load() { return state; }
        public void save(dev.yoru.domain.Model.State next) { state = next; }
        public void close() { }
    }
```

Add `aMoveEditsTheSave();` to `main` and the `throws Exception` already on `main` covers it.

- [ ] **Step 3: Render the arranging state in `Preview`**

Read `Preview.java`. After the Collection page PNG is written (the page loop), add a second capture: find the Arrange button, toggle it, repaint. Insert after the page loop, before the calendar block:

```java
                // The arranging state of the Collection page: pick-up marker and
                // hint visible, so the mode is reviewed as an image too.
                var collectionTab = button(app, "Collection");
                if (collectionTab != null) {
                    collectionTab.doClick();
                    var arrange = button(app, "Arrange");
                    if (arrange != null) {
                        arrange.doClick();
                        layout(app);
                        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                        var g = image.createGraphics();
                        app.paint(g);
                        g.dispose();
                        ImageIO.write(image, "png", out.resolve("collection-arranging.png").toFile());
                    }
                }
```

- [ ] **Step 4: Run the affected suites, then the whole suite**

Run: `./test.sh`
Expected: green, `StorageTest` count up by ~10.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/yoru/ui/StorageTest.java src/test/java/dev/yoru/ui/Preview.java
git commit -m "Drive arranging with real clicks, and render it for review (#44)

The pick/place/cancel flow is exercised through synthesized MouseEvents —
the TaskBoardTest pattern — and one test goes the whole trip: clicks move a
Pokémon and the vault's save changes. Preview writes collection-arranging.png
so the mode is looked at, not just compiled.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: Docs, push, PR, close #44

**Files:**
- Modify: `docs/ROADMAP.md`
- Modify: `docs/CURRENT-STATE.md`

- [ ] **Step 1: Bring the roadmap up to date**

In `docs/ROADMAP.md`, change the #44 plan line to mark it done:

```markdown
3. #44 The save is the collection: no starter picker; the party and PC come from the save and can be rearranged while the game is closed; vault schema 11. *(done)*
```

And in the "Where things stand" table, the Collection row already says "The save itself…"; extend it: `The save itself: the party and PC come from the game's save, rearranged from Yoru while the game is closed, no starter picker, vault schema 11`.

- [ ] **Step 2: Update the handoff doc**

In `docs/CURRENT-STATE.md`, in the "Current direction — September 11, 2026" section, replace the "Next:" sentence with:

```markdown
#44 is complete: the Collection page arranges Pokémon between boxes and the
party, renames boxes and changes wallpapers from Yoru while the game is
closed, through the same verified transaction as delivery and with a vault
backup before each edit. Next: #46 (the game's own songs as study music),
then the tracker and release tickets in ROADMAP.md order.
```

- [ ] **Step 3: Full suite, push, PR**

Run: `./test.sh` (must be green — record the PASS total). Then:

```bash
git add docs/ROADMAP.md docs/CURRENT-STATE.md
git commit -m "Mark #44 done: the roadmap and the handoff say what is here

Co-Authored-By: Claude <noreply@anthropic.com>"
git push origin claude/storage-management
gh pr create --repo Rabadakku/yoru --base main --head claude/storage-management \
  --title "Storage management from Yoru: move, rename boxes, change wallpapers (#44)" \
  --body "$(cat <<'EOF'
The last piece of #44: while the game is closed, the Collection page can rearrange Pokémon between boxes and the party, rename boxes and change wallpapers — written to the save through the same verified transaction as delivery.

- `game/StorageEdit` is a pure planner: each op writes through `Gen3Save` (the game's next save, re-read back) and is then proven byte-for-byte to have changed only what it was asked to change.
- `Tracker.editSave` commits in one vault write, backs the vault up first, and refuses when the save changed underneath.
- The game and the tracker never write at the same time: the controls are disabled while the game runs, and the changed-save guard is the backstop.
- Covered by `StorageEditTest` (moves, swaps, reorder, last-member rule, renames, wallpapers, verifier refusals), `SaveEditTest` (one write, backup, changed-save refusal, two-vault isolation per #40), `StorageTest` (real clicks end to end), and `Preview` renders the arranging state.
- `./test.sh` green: NN,NNN assertions.

Closes #44.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Replace `NN,NNN` with the actual count from `./test.sh 2>&1 | grep -oE "PASS: [0-9]+" | grep -oE "[0-9]+" | paste -sd+ - | bc`.

- [ ] **Step 4: Watch CI, then merge**

Check `gh run list --repo Rabadakku/yoru --branch claude/storage-management` until green, then:

```bash
gh pr merge --repo Rabadakku/yoru --merge
```

Verify #44 closes; if GitHub needs a manual click (keyword in a commit rather than the PR body), close it manually with a comment linking the merge commit.

- [ ] **Step 5: Post-merge hygiene**

```bash
git fetch --all --prune && git checkout -B main origin/main && git branch -D claude/storage-management
```

---

## Self-review notes (already applied)

- Spec coverage: move/swaps/last-member (Tasks 1–2), rename/wallpaper (Task 3), verified transaction + verifiers (Tasks 3–4), `Tracker.editSave` + backup + isolation (Task 5), game-closed mutual exclusion (Task 7 controls + guard in Task 5), UI + clicks + preview (Tasks 6–8), docs/close (Task 9). ✓
- Placeholders: none; every step carries code or an exact command. ✓
- Type consistency: `StorageEdit.Place(boolean party, int box, int slot)` with the party as `Place(true, -1, index)` is used identically in planner, screen, page and tests; `identity(mon)` = `personality:otId:nationalDex:experience` matches `Gen3Pokemon`'s public fields. ✓
- The spec said "12 wallpapers" once; the game ships 16 (`Gen3Save.WALLPAPERS`), and the code follows the game.
