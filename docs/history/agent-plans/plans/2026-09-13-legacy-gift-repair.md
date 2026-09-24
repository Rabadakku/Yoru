# Legacy Study Gift Repair (#11) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find study gifts that an older Yoru wrote with the signed substructure order, prove each one is exactly what that build wrote, and rewrite only those — in place, verified, backed up — as a current delivery would have written them.

**Architecture:** A pure `game.LegacyGifts` class. `assess(save, rewards)` rebuilds, for every delivered reward, today's gift (`GameDelivery.companionFor`) and the byte-exact record the signed encoder produced from it, then compares both against the one record in the save carrying that reward's personality. Only an exact legacy match is `REPAIRABLE`; anything else is left alone. `repair` rewrites those slots with today's encoding, writes the game's next save, and verifies that nothing else changed and that every repaired record now assesses as `CORRECT`. The Game page shows a card only when the assessment finds something, and commits through `Tracker.editSave` (vault backup first).

**Tech Stack:** Java 22, JDK only; tests are `main` classes run by `test.sh`.

**Spec:** GitHub issue #11; `docs/design/AUDIT-2026-09.md` ("First repair"); history recorded below.

## Global Constraints

- No rewrite without provenance: personality = `StudyGift.personalityFor(reward.id())`, trainer id = the save's own, and the whole record byte-equal to a reconstructed legacy encoding. Never match on species or name.
- A genuine game record, a gift changed since delivery, and any personality held by more than one record are never written.
- No reward redelivery and no ledger change: `repair` touches only save bytes; rewards stay as they are.
- Repeated runs are no-ops: a repaired record assesses as `CORRECT`.
- Commit only through `Tracker.editSave` with the game closed; the vault is backed up before the first edit of a run.
- No vault schema change. Tests use invented data only.
- `javac --release 22`; JDK only. Branch `claude/legacy-gifts` from `claude/save-read-model` (it edits `GamePage`). Commits say why and end with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

### What older builds wrote (predecessor history, verified)

| Commit (2026-09-11 UTC) | Effect on a delivered gift |
|---|---|
| `e1839ff` 10:08 | Delivery introduced. Box records only; `StudyGift.build(id, dex, level, trainer, null, 0)`. Signed order. |
| `cdd6979` 10:08 | Byte 0x13 written as 0x02 (has species) instead of 0x00. Same second as `e1839ff`, so both flag values are accepted. |
| `b8519e7` 13:45 | Gifts may enter the party via `Gen3Pokemon.toParty(record)`: level from experience, status 0, mail 0xFF, full HP, stats with the (signed) nature. |
| `d313777` 22:13 | Nature multiplier divides in 32 bits instead of 16. Irrelevant to gifts: the 16-bit form differs only for a pre-nature stat above 595, and a gift has 0 EVs, so the largest Gen III value at level 100 is 559 (Shuckle's defences). |
| `8b027ba` 2026-09-12 | Unsigned order and nature. Released in 1.0.3 at 2026-09-13T10:41:03Z. |

`StudyGift.build` itself is unchanged apart from how the default name is looked up (same result), so today's builder reproduces every field an older build chose.

### Running one suite

```bash
./build.sh && find src/test/java -name '*.java' > build/tests.txt \
  && javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes @build/tests.txt \
  && java -Duser.home="$(mktemp -d)" -Djava.util.prefs.PreferencesFactory=dev.yoru.ui.TestPreferencesFactory \
       -Djava.awt.headless=true -ea -cp build/classes <SuiteClass>
```

---

### Task 1: An independent record oracle that takes the order explicitly

**Files:**
- Create: `src/test/java/dev/yoru/game/Gen3RecordOracle.java` (restored from the `claude/unsigned-personality` stash, generalised)
- Create: `src/test/java/dev/yoru/game/Gen3RecordOracleTest.java`
- Modify: `test.sh` (register after `dev.yoru.game.UnsignedPokemonTest`)

**Interfaces:**
- Produces: `static byte[] Gen3RecordOracle.box(Gen3Pokemon fields, int order, int flags)` — an 80-byte record laid out with pokeemerald's position table row `order`, header byte 0x13 = `flags`, checksum and XOR computed by hand. Reads only public field values from `fields`.
- Produces: `static int Gen3RecordOracle.unsignedOrder(int personality)` and `static int Gen3RecordOracle.signedOrder(int personality)` (`Math.floorMod`, what the old encoder did).

- [ ] **Step 1: Write the failing test**

```java
package dev.yoru.game;

/** The oracle itself, pinned to hand arithmetic, before anything relies on it. */
public final class Gen3RecordOracleTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) {
        // 2^31 = 89,478,485 x 24 + 8; as a signed int it is -2^31, and floorMod(-2^31, 24) = 16.
        check(Gen3RecordOracle.unsignedOrder(0x80000000) == 8, "unsigned 0x80000000 is order 8");
        check(Gen3RecordOracle.signedOrder(0x80000000) == 16, "the old signed encoder chose order 16");
        check(Gen3RecordOracle.unsignedOrder(7) == 7 && Gen3RecordOracle.signedOrder(7) == 7, "below the sign bit they agree");

        var mon = new Gen3Pokemon();
        mon.personality = 0x80000000;
        mon.otId = 0;
        mon.species = 277;
        // Order 8 puts Growth third; order 16 puts it third as well but Attacks fourth.
        // With a zero trainer id the key only touches each word's top byte, so the
        // species' two low bytes sit in the clear at 0x20 + 2 x 12.
        var correct = Gen3RecordOracle.box(mon, 8, 0x02);
        check((correct[0x38] & 0xFF) == 0x15 && (correct[0x39] & 0xFF) == 0x01, "order 8: species at 0x38");
        check((correct[0x13] & 0xFF) == 0x02, "the header flag byte is what was asked for");
        check(Gen3Pokemon.decode(correct, 0).species == 277, "and the fixed decoder reads the species back");

        System.out.println("PASS: " + checks + " record oracle checks (orders either side of the sign bit, layout, flags)");
    }
}
```

Register in `test.sh` after `dev.yoru.game.UnsignedPokemonTest`:

```sh
java -ea -cp build/classes dev.yoru.game.Gen3RecordOracleTest
```

- [ ] **Step 2: Run it to verify it fails**

Expected: compile failure `cannot find symbol ... Gen3RecordOracle`.

- [ ] **Step 3: Implement the oracle**

```java
package dev.yoru.game;

import java.util.Arrays;

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

    /** An 80-byte box record of these field values, in position-table row {@code order}. */
    static byte[] box(Gen3Pokemon f, int order, int flags) {
        var out = new byte[80];
        putU32(out, 0x00, unsigned(f.personality));
        putU32(out, 0x04, unsigned(f.otId));
        Gen3Text.write(f.nickname, out, 0x08, 10);     // names are not under test here
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
    private static void putU16(byte[] b, int at, int value) { b[at] = (byte) value; b[at + 1] = (byte) (value >>> 8); }
    private static void putU32(byte[] b, int at, long value) {
        putU16(b, at, (int) (value & 0xFFFF));
        putU16(b, at + 2, (int) (value >>> 16 & 0xFFFF));
    }
}
```

- [ ] **Step 4: Run it to verify it passes** — Expected: `PASS: 6 record oracle checks ...`
- [ ] **Step 5: Commit** — "Add a record oracle that builds either substructure order by hand" (why: #11 must prove a gift is byte-exact to what the signed encoder wrote, independently of the code that will rewrite it).

### Task 2: Assess delivered gifts without writing anything

**Files:**
- Create: `src/main/java/dev/yoru/game/LegacyGifts.java`
- Create: `src/test/java/dev/yoru/game/LegacyGiftsTest.java`
- Modify: `test.sh` (register after `dev.yoru.game.GameDeliveryTest`)

**Interfaces:**
- Consumes: `GameDelivery.companionFor(Reward, Gen3Save.Trainer)`, `StudyGift.personalityFor(UUID)`, `StudyGift.Location`, `Gen3Pokemon.decrypt/encrypt/offsetOf/ORDERS/stats/toParty`, `Gen3Save.read/trainer/partyRecord/partyCount/storage/slotOffset/empty`
- Produces:
  - `public enum LegacyGifts.Verdict { CORRECT, REPAIRABLE, CHANGED, AMBIGUOUS, NOT_FOUND }`
  - `public record LegacyGifts.Finding(Reward reward, Verdict verdict, List<StudyGift.Location> where)`
  - `public record LegacyGifts.Assessment(List<Finding> findings)` with `List<Finding> repairable()` and `List<Finding> leftAlone()` (high-bit personality, delivered before 1.0.3, verdict `CHANGED` or `AMBIGUOUS`)
  - `public static Assessment LegacyGifts.assess(byte[] save, List<Reward> rewards)`
  - `static byte[] LegacyGifts.signedLayout(byte[] correctBox, int flags)` (package-private, for tests)

- [ ] **Step 1: Write the failing test**

```java
package dev.yoru.game;

import dev.yoru.domain.Model.Reward;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Gifts written by the signed encoder (#11): which ones are provably untouched
 * since delivery, and which must be left alone. Every legacy record here is built
 * by Gen3RecordOracle in the signed order, never by the code that assesses it.
 */
public final class LegacyGiftsTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    static final Instant BEFORE_FIX = Instant.parse("2026-09-12T09:00:00Z");

    /** The first reward id from {@code start} whose personality does, or does not, have the high bit. */
    static UUID rewardId(boolean highBit, int start) {
        for (int i = start; ; i++) {
            var id = new UUID(0x5EED_0000_0000_4000L, 0x8000_0000_0000_0000L | i);
            if ((StudyGift.personalityFor(id) < 0) == highBit) return id;
        }
    }

    static Reward delivered(UUID id, int dex, int level) {
        return new Reward(id, dex, level, BEFORE_FIX.minusSeconds(3600), BEFORE_FIX);
    }

    static byte[] trainerSave() {
        return Gen3Fixture.withTrainer(Gen3Fixture.save(2, 0), "TESTER", 0, 12345, 54321);
    }

    /** What the signed encoder wrote for this reward, built by the oracle. */
    static byte[] legacyBox(byte[] save, Reward reward, int flags) {
        var mon = GameDelivery.companionFor(reward, Gen3Save.read(save).trainer());
        return Gen3RecordOracle.box(mon, Gen3RecordOracle.signedOrder(mon.personality), flags);
    }

    static byte[] withBoxed(byte[] save, int box, int slot, byte[] record) {
        var s = Gen3Save.read(save);
        var storage = s.storage();
        System.arraycopy(record, 0, storage, Gen3Save.slotOffset(box, slot), Gen3Pokemon.BOX_SIZE);
        s.storage(storage);
        return s.bytes();
    }

    /** A party record as b8519e7's toParty wrote it: stats from the signed nature. */
    static byte[] legacyParty(byte[] save, Reward reward) {
        var correctBox = GameDelivery.companionFor(reward, Gen3Save.read(save).trainer()).encode();
        var record = Gen3Pokemon.toParty(correctBox, 0);
        var mon = Gen3Pokemon.decode(correctBox, 0);
        int[] stats = Gen3Pokemon.stats(mon.nationalDex(), mon.levelFromExperience(), mon.ivs(), mon.evs,
            Math.floorMod(mon.personality, 25));
        for (int i = 0; i < 6; i++) { record[0x58 + i * 2] = (byte) stats[i]; record[0x59 + i * 2] = (byte) (stats[i] >>> 8); }
        record[0x56] = (byte) stats[0];
        record[0x57] = (byte) (stats[0] >>> 8);
        System.arraycopy(legacyBox(save, reward, 0x02), 0, record, 0, Gen3Pokemon.BOX_SIZE);
        return record;
    }

    static LegacyGifts.Verdict verdictOf(LegacyGifts.Assessment a, Reward r) {
        return a.findings().stream().filter(f -> f.reward().id().equals(r.id())).findFirst().orElseThrow().verdict();
    }

    public static void main(String[] args) {
        var save = trainerSave();
        var old = delivered(rewardId(true, 0), 258, 5);          // high bit: signed and unsigned orders differ
        var low = delivered(rewardId(false, 0), 252, 5);         // low bit: both orders agree
        var changed = delivered(rewardId(true, 1000), 255, 5);
        var gone = delivered(rewardId(true, 2000), 25, 5);
        var pending = new Reward(rewardId(true, 3000), 1, 5, BEFORE_FIX, null);

        check(!Arrays.equals(legacyBox(save, old, 0x02), GameDelivery.companionFor(old, Gen3Save.read(save).trainer()).encode()),
            "a high-bit gift's legacy record differs from today's");
        check(Arrays.equals(LegacyGifts.signedLayout(GameDelivery.companionFor(old, Gen3Save.read(save).trainer()).encode(), 0x02),
            legacyBox(save, old, 0x02)), "signedLayout reproduces the oracle's signed record byte for byte");

        save = withBoxed(save, 0, 0, legacyBox(save, old, 0x02));
        save = withBoxed(save, 0, 1, GameDelivery.companionFor(low, Gen3Save.read(save).trainer()).encode());
        var edited = legacyBox(save, changed, 0x02).clone();
        var editedMon = Gen3Pokemon.decode(GameDelivery.companionFor(changed, Gen3Save.read(save).trainer()).encode(), 0);
        editedMon.experience += 1;                                // the game gave it experience since
        edited = Gen3RecordOracle.box(editedMon, Gen3RecordOracle.signedOrder(editedMon.personality), 0x02);
        save = withBoxed(save, 3, 7, edited);
        // A genuine game record with a high-bit personality no reward owns.
        var wild = GameDelivery.companionFor(delivered(rewardId(true, 4000), 263, 3), Gen3Save.read(save).trainer()).encode();
        save = withBoxed(save, 5, 5, wild);

        var rewards = List.of(old, low, changed, gone, pending);
        var before = save.clone();
        var a = LegacyGifts.assess(save, rewards);
        check(Arrays.equals(before, save), "assessing writes nothing");
        check(verdictOf(a, old) == LegacyGifts.Verdict.REPAIRABLE, "an untouched signed-order gift is repairable");
        check(verdictOf(a, low) == LegacyGifts.Verdict.CORRECT, "a low-bit gift was always written correctly");
        check(verdictOf(a, changed) == LegacyGifts.Verdict.CHANGED, "a gift the game has changed is not repairable");
        check(verdictOf(a, gone) == LegacyGifts.Verdict.NOT_FOUND, "a gift no longer in the save is not found");
        check(a.findings().stream().noneMatch(f -> f.reward().id().equals(pending.id())), "an undelivered reward is not assessed");
        check(a.repairable().size() == 1 && a.repairable().getFirst().where().getFirst().box() == 0
            && a.repairable().getFirst().where().getFirst().slot() == 0, "and the repairable one is located exactly");
        check(a.leftAlone().size() == 1 && a.leftAlone().getFirst().reward().id().equals(changed.id()),
            "the changed high-bit gift is reported as left alone");

        // The legacy flag byte from the first delivery build is recognised too.
        var zeroFlag = withBoxed(trainerSave(), 1, 1, legacyBox(trainerSave(), old, 0x00));
        check(verdictOf(LegacyGifts.assess(zeroFlag, List.of(old)), old) == LegacyGifts.Verdict.REPAIRABLE,
            "a gift written with flag byte 0x00 is recognised as legacy");

        // Two records with the same personality: never guess which is the gift.
        var twice = withBoxed(withBoxed(trainerSave(), 0, 0, legacyBox(trainerSave(), old, 0x02)), 0, 1, legacyBox(trainerSave(), old, 0x02));
        check(verdictOf(LegacyGifts.assess(twice, List.of(old)), old) == LegacyGifts.Verdict.AMBIGUOUS, "a duplicated marker is ambiguous");

        // A legacy gift in the party, with its tail as toParty wrote it then.
        var partySave = trainerSave();
        partySave = Gen3Fixture.withParty(partySave, List.of(Gen3Fixture.member(partySave, 252, 5, 1), legacyParty(partySave, old)));
        var inParty = LegacyGifts.assess(partySave, List.of(old));
        check(verdictOf(inParty, old) == LegacyGifts.Verdict.REPAIRABLE && inParty.repairable().getFirst().where().getFirst().inParty(),
            "an untouched legacy party gift is repairable, and found in the party");

        // Delivered after 1.0.3 shipped: correct by construction, never "left alone".
        var late = new Reward(changed.id(), 255, 5, BEFORE_FIX, Instant.parse("2026-09-14T00:00:00Z"));
        check(LegacyGifts.assess(save, List.of(late)).leftAlone().isEmpty(), "a change to a gift delivered after 1.0.3 is not reported");

        System.out.println("PASS: " + checks + " legacy gift assessment checks (repairable, correct, changed, missing, ambiguous, party, flag 0x00)");
    }
}
```

Register in `test.sh` after `dev.yoru.game.GameDeliveryTest`:

```sh
java -ea -cp build/classes dev.yoru.game.LegacyGiftsTest
```

- [ ] **Step 2: Run it to verify it fails** — Expected: compile failure `cannot find symbol ... LegacyGifts`.

- [ ] **Step 3: Implement the assessment**

```java
package dev.yoru.game;

import dev.yoru.domain.Model.Reward;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Study gifts an older Yoru wrote in the wrong substructure order (#11).
 *
 * Before 1.0.3 the encoder chose the order with floor modulo on a signed int, so
 * every gift whose personality has its high bit set went into the game shuffled
 * wrong: the checksum still passed, and the game read another Pokémon's worth of
 * fields. Correcting the reader cannot fix those bytes, and rewriting every
 * high-bit record would damage genuine ones the game made.
 *
 * So a record is repaired only when it is provably the gift: it carries the
 * reward's personality, it is the only record that does, and all of its bytes
 * equal what the signed encoder produced from today's builder — which has not
 * changed what it chooses since delivery began. Anything the game has touched
 * since, however slightly, is left exactly as it is.
 */
public final class LegacyGifts {
    private LegacyGifts() { }

    /** 1.0.3 shipped the unsigned encoder at this instant; nothing delivered later is legacy. */
    static final Instant FIXED = Instant.parse("2026-09-13T10:41:03Z");

    public enum Verdict { CORRECT, REPAIRABLE, CHANGED, AMBIGUOUS, NOT_FOUND }

    public record Finding(Reward reward, Verdict verdict, List<StudyGift.Location> where) { }

    public record Assessment(List<Finding> findings) {
        public List<Finding> repairable() {
            return findings.stream().filter(f -> f.verdict() == Verdict.REPAIRABLE).toList();
        }

        /** High-bit gifts from before the fix that could not be proven untouched, and so were not written. */
        public List<Finding> leftAlone() {
            return findings.stream()
                .filter(f -> f.verdict() == Verdict.CHANGED || f.verdict() == Verdict.AMBIGUOUS)
                .filter(f -> StudyGift.personalityFor(f.reward().id()) < 0)
                .filter(f -> f.reward().deliveredAt().isBefore(FIXED))
                .toList();
        }
    }

    private record Held(StudyGift.Location where, byte[] record) { }

    public static Assessment assess(byte[] bytes, List<Reward> rewards) {
        var save = Gen3Save.read(bytes);
        var trainer = save.trainer();
        var storage = save.storage();
        var byMarker = new HashMap<Integer, List<Held>>();
        for (int i = 0; i < save.partyCount(); i++) {
            var record = save.partyRecord(i);
            byMarker.computeIfAbsent(personalityOf(record), k -> new ArrayList<>())
                .add(new Held(new StudyGift.Location(-1, i, true), record));
        }
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                int at = Gen3Save.slotOffset(box, slot);
                if (Gen3Save.empty(storage, at)) continue;
                var record = Arrays.copyOfRange(storage, at, at + Gen3Pokemon.BOX_SIZE);
                byMarker.computeIfAbsent(personalityOf(record), k -> new ArrayList<>())
                    .add(new Held(new StudyGift.Location(box, slot, false), record));
            }

        var findings = new ArrayList<Finding>();
        for (var reward : rewards) {
            if (!reward.delivered()) continue;
            int marker = StudyGift.personalityFor(reward.id());
            var held = byMarker.getOrDefault(marker, List.of());
            var where = held.stream().map(Held::where).toList();
            if (held.isEmpty()) { findings.add(new Finding(reward, Verdict.NOT_FOUND, where)); continue; }
            if (held.size() > 1) { findings.add(new Finding(reward, Verdict.AMBIGUOUS, where)); continue; }
            findings.add(new Finding(reward, verdict(held.getFirst(), reward, trainer), where));
        }
        return new Assessment(List.copyOf(findings));
    }

    private static Verdict verdict(Held held, Reward reward, Gen3Save.Trainer trainer) {
        byte[] correct = GameDelivery.companionFor(reward, trainer).encode();
        boolean party = held.where().inParty();
        byte[] box = Arrays.copyOf(held.record(), Gen3Pokemon.BOX_SIZE);
        if (Arrays.equals(box, correct) && (!party || Arrays.equals(held.record(), Gen3Pokemon.toParty(correct, 0))))
            return Verdict.CORRECT;
        if (personalityOf(correct) >= 0) return Verdict.CHANGED;     // both orders agree below the sign bit
        for (int flags : new int[] {0x02, 0x00}) {
            byte[] legacy = signedLayout(correct, flags);
            if (!Arrays.equals(box, legacy)) continue;
            if (!party || Arrays.equals(held.record(), legacyParty(correct, legacy))) return Verdict.REPAIRABLE;
        }
        return Verdict.CHANGED;
    }

    /**
     * Today's record with its substructures moved to where the signed encoder put
     * them. The checksum at 0x1C is kept: a permutation does not change a sum.
     */
    static byte[] signedLayout(byte[] correctBox, int flags) {
        var out = correctBox.clone();
        int personality = personalityOf(out), otId = u32(out, 4);
        byte[] plain = Gen3Pokemon.decrypt(out, 0x20, personality, otId);
        var shuffled = new byte[Gen3Pokemon.DATA_SIZE];
        for (int substructure = 0; substructure < 4; substructure++)
            System.arraycopy(plain, Gen3Pokemon.offsetOf(personality, substructure),
                shuffled, signedOffsetOf(personality, substructure), Gen3Pokemon.SUBSTRUCTURE);
        Gen3Pokemon.encrypt(shuffled, personality, otId);
        System.arraycopy(shuffled, 0, out, 0x20, Gen3Pokemon.DATA_SIZE);
        out[0x13] = (byte) flags;
        return out;
    }

    /** The party record toParty wrote for a legacy gift: the legacy box, stats from the signed nature. */
    static byte[] legacyParty(byte[] correctBox, byte[] legacyBox) {
        var record = Gen3Pokemon.toParty(correctBox, 0);
        var mon = Gen3Pokemon.decode(correctBox, 0);
        int[] stats = Gen3Pokemon.stats(mon.nationalDex(), mon.levelFromExperience(), mon.ivs(), mon.evs,
            Math.floorMod(mon.personality, 25));
        putU16(record, 0x56, stats[0]);
        for (int i = 0; i < 6; i++) putU16(record, 0x58 + i * 2, stats[i]);
        System.arraycopy(legacyBox, 0, record, 0, Gen3Pokemon.BOX_SIZE);
        return record;
    }

    private static int signedOffsetOf(int personality, int substructure) {
        int[] order = Gen3Pokemon.ORDERS[Math.floorMod(personality, Gen3Pokemon.ORDERS.length)];
        for (int slot = 0; slot < order.length; slot++)
            if (order[slot] == substructure) return slot * Gen3Pokemon.SUBSTRUCTURE;
        throw new IllegalArgumentException("No such substructure " + substructure);
    }

    private static int personalityOf(byte[] record) { return u32(record, 0); }
    private static int u32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }
    private static void putU16(byte[] b, int at, int value) { b[at] = (byte) value; b[at + 1] = (byte) (value >>> 8); }
}
```

Check: `Gen3Save.BOXES`, `PER_BOX`, `empty` and `Gen3Pokemon.DATA_SIZE`, `SUBSTRUCTURE` are visible in `dev.yoru.game` (package-private or public). `GameDelivery.companionFor` is package-private static.

- [ ] **Step 4: Run it to verify it passes** — Expected: `PASS: ... legacy gift assessment checks`.
- [ ] **Step 5: Commit** — "Recognise study gifts the signed encoder wrote, without writing anything".

### Task 3: Repair in place, verified

**Files:**
- Modify: `src/main/java/dev/yoru/game/LegacyGifts.java` (add `repair`, `verify`)
- Modify: `src/test/java/dev/yoru/game/LegacyGiftsTest.java` (add repair checks before the final `println`)

**Interfaces:**
- Produces: `public static byte[] LegacyGifts.repair(byte[] before, List<Reward> rewards)` — returns `before` unchanged when nothing is repairable; throws `IllegalStateException` when the save is not editable or verification fails.
- Produces: `static void LegacyGifts.verify(byte[] before, byte[] after, List<Reward> rewards)` (package-private, for refusal tests)

- [ ] **Step 1: Add the failing checks** (insert before `System.out.println` in `LegacyGiftsTest.main`)

```java
        // Repair rewrites exactly the provable gifts, as today's delivery would.
        var repaired = LegacyGifts.repair(save, rewards);
        var trainer = Gen3Save.read(repaired).trainer();
        var after = LegacyGifts.assess(repaired, rewards);
        check(verdictOf(after, old) == LegacyGifts.Verdict.CORRECT, "the repaired gift now assesses as correct");
        var fixed = Gen3Save.read(repaired).boxed(Gen3Save.read(repaired).storage(), 0, 0);
        var expected = GameDelivery.companionFor(old, trainer);
        check(Arrays.equals(Gen3RecordOracle.box(expected, Gen3RecordOracle.unsignedOrder(expected.personality), 0x02),
            Arrays.copyOfRange(Gen3Save.read(repaired).storage(), Gen3Save.slotOffset(0, 0), Gen3Save.slotOffset(0, 0) + 80)),
            "and the oracle, in the game's own order, agrees with it byte for byte");
        check(fixed.nationalDex() == 258 && fixed.personality == StudyGift.personalityFor(old.id()),
            "it is the Pokémon the reward promised, with its identity kept");
        check(verdictOf(after, changed) == LegacyGifts.Verdict.CHANGED, "the changed gift is still left alone");
        var keptChanged = Arrays.copyOfRange(Gen3Save.read(repaired).storage(), Gen3Save.slotOffset(3, 7), Gen3Save.slotOffset(3, 7) + 80);
        check(Arrays.equals(keptChanged, edited), "byte for byte");
        var keptWild = Arrays.copyOfRange(Gen3Save.read(repaired).storage(), Gen3Save.slotOffset(5, 5), Gen3Save.slotOffset(5, 5) + 80);
        check(Arrays.equals(keptWild, wild), "and a genuine high-bit record is untouched");
        check(Arrays.equals(LegacyGifts.repair(repaired, rewards), repaired), "a second run changes nothing");

        var partyFixed = LegacyGifts.repair(partySave, List.of(old));
        check(Arrays.equals(Gen3Save.read(partyFixed).partyRecord(1),
            Gen3Pokemon.toParty(GameDelivery.companionFor(old, Gen3Save.read(partyFixed).trainer()).encode(), 0)),
            "a repaired party gift carries the stats its real nature gives");
        check(Arrays.equals(Gen3Save.read(partyFixed).partyRecord(0), Gen3Save.read(partySave).partyRecord(0)),
            "and its party neighbour is untouched");

        // Refusals: a save the game might not load a change into, and a tampered result.
        try {
            LegacyGifts.repair(Gen3Fixture.interrupted(save), rewards);
            check(false, "an interrupted save is refused");
        } catch (IllegalStateException expectedRefusal) { }
        var tampered = withBoxed(repaired, 9, 9, wild);
        try {
            LegacyGifts.verify(save, tampered, rewards);
            check(false, "a result that changed another slot is refused");
        } catch (IllegalStateException expectedRefusal) { }

        // A full PC does not matter: repair never needs a free slot.
        var full = Gen3Save.read(save);
        var fullStorage = full.storage();
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                int at = Gen3Save.slotOffset(box, slot);
                if (Gen3Save.empty(fullStorage, at)) System.arraycopy(wild, 0, fullStorage, at, 80);
            }
        full.storage(fullStorage);
        var fullSave = full.bytes();
        check(verdictOf(LegacyGifts.assess(LegacyGifts.repair(fullSave, List.of(old)), List.of(old)), old)
            == LegacyGifts.Verdict.CORRECT, "a gift in a full PC is repaired in place");
```

(The full-PC fixture duplicates `wild`'s personality, which does not belong to `old`; `old`'s marker stays unique.)

- [ ] **Step 2: Run it to verify it fails** — Expected: compile failure `cannot find symbol ... repair`.

- [ ] **Step 3: Implement**

```java
    /**
     * Rewrites every repairable gift as a current delivery would have written it,
     * in place, and proves nothing else changed. Returns the input unchanged when
     * there is nothing to repair.
     */
    public static byte[] repair(byte[] before, List<Reward> rewards) {
        var fixes = assess(before, rewards).repairable();
        if (fixes.isEmpty()) return before;
        var save = Gen3Save.read(before);
        String refusal = save.whyNotEditable();
        if (refusal != null) throw new IllegalStateException(refusal);
        var trainer = save.trainer();
        var storage = save.storage();
        var party = new ArrayList<>(save.partyRecords());
        for (var fix : fixes) {
            byte[] correct = GameDelivery.companionFor(fix.reward(), trainer).encode();
            var where = fix.where().getFirst();
            if (where.inParty()) party.set(where.slot(), Gen3Pokemon.toParty(correct, 0));
            else System.arraycopy(correct, 0, storage, Gen3Save.slotOffset(where.box(), where.slot()), Gen3Pokemon.BOX_SIZE);
        }
        save.party(party);
        save.storage(storage);
        byte[] after = save.bytes();
        verify(before, after, rewards);
        return after;
    }

    /**
     * Proves a repair changed only the repaired gifts. Outside the party records
     * and PC storage every checksummed byte matches; inside them only the slots of
     * gifts that were repairable may differ; each of those now assesses CORRECT;
     * and every other reward's verdict is what it was.
     */
    static void verify(byte[] before, byte[] after, List<Reward> rewards) {
        var was = assess(before, rewards);
        var now = assess(after, rewards);
        var a = Gen3Save.read(before);
        var b = Gen3Save.read(after);
        var allowedStorage = new java.util.HashSet<Integer>();
        var allowedParty = new java.util.HashSet<Integer>();
        for (var fix : was.repairable()) {
            var where = fix.where().getFirst();
            if (where.inParty()) allowedParty.add(where.slot());
            else allowedStorage.add(Gen3Save.slotOffset(where.box(), where.slot()));
        }
        for (int id = 0; id < Gen3Save.SECTIONS; id++) {
            if (id >= Gen3Save.STORAGE_FIRST && id <= Gen3Save.STORAGE_LAST) continue;
            byte[] sa = a.section(id), sb = b.section(id);
            for (int i = 0; i < Gen3Save.CHECKSUMMED[id]; i++) {
                if (sa[i] == sb[i]) continue;
                int member = id == 1 && i >= Gen3Save.PARTY_AT ? (i - Gen3Save.PARTY_AT) / Gen3Pokemon.PARTY_SIZE : -1;
                if (member >= 0 && member < Gen3Save.PARTY_LIMIT && allowedParty.contains(member)) continue;
                throw new IllegalStateException("Section " + id + " changed at byte " + i + ", which this repair never touches.");
            }
        }
        byte[] sa = a.storage(), sb = b.storage();
        for (int i = 0; i < sa.length; i++) {
            if (sa[i] == sb[i]) continue;
            int slotStart = i - ((i - 4) % Gen3Pokemon.BOX_SIZE);
            if (i >= 4 && allowedStorage.contains(slotStart) && i - slotStart < Gen3Pokemon.BOX_SIZE) continue;
            throw new IllegalStateException("Storage changed at byte " + i + ", which this repair never touches.");
        }
        for (int i = 0; i < was.findings().size(); i++) {
            var w = was.findings().get(i);
            var n = now.findings().get(i);
            var expected = w.verdict() == Verdict.REPAIRABLE ? Verdict.CORRECT : w.verdict();
            if (n.verdict() != expected)
                throw new IllegalStateException("A gift assessed " + w.verdict() + " reads as " + n.verdict() + " after the repair.");
        }
    }
```

Check that `Gen3Save.PARTY_AT`, `PARTY_LIMIT`, `SECTIONS`, `STORAGE_FIRST`, `STORAGE_LAST`, `CHECKSUMMED` and `section(int)` are visible in the package (they are used by `Gen3Fixture` and `StorageEdit`). Slot offsets start at 4 (`slotOffset`), hence the `i - 4` alignment.

- [ ] **Step 4: Run it to verify it passes**; then `dev.yoru.game.GameDeliveryTest` and `dev.yoru.game.StorageEditTest`.
- [ ] **Step 5: Commit** — "Repair provable legacy gifts in place and prove nothing else changed".

### Task 4: Offer the repair on the Game page

**Files:**
- Modify: `src/main/java/dev/yoru/ui/GamePage.java` (`idle`: add `legacyGifts(p, read)` after the hero card)
- Create: `src/test/java/dev/yoru/ui/LegacyGiftUiTest.java`
- Modify: `test.sh` (after `dev.yoru.ui.SaveStatusUiTest`)

**Interfaces:**
- Consumes: `LegacyGifts.assess/repair`, `GameView.SaveRead`, `Tracker.editSave`, `Dialogs.confirm`
- Produces: component names `game.legacyGifts` (card), `game.repairGifts` (button), `game.legacyLeftAlone` (label)

- [ ] **Step 1: Write the failing test** — build a vault whose save holds one repairable legacy gift (reuse `LegacyGiftsTest`'s static helpers: `trainerSave`, `rewardId`, `legacyBox`, `withBoxed`) and whose ledger holds that reward as delivered (`Tracker.bankReward` then `Tracker.rewardDelivered`, or `tracker.replaceGameSave` + a `State` built with the reward). Open Game: assert `game.legacyGifts` present, `game.repairGifts` enabled and its text `"Repair 1 study gift"`; with no legacy gift in the save, assert `game.legacyGifts` absent. Exit the process explicitly as `SaveStatusUiTest` does.

- [ ] **Step 2: Run it to verify it fails** — Expected: `AssertionError` on `game.legacyGifts`.

- [ ] **Step 3: Implement**

```java
    /**
     * Study gifts an older Yoru wrote in the wrong order (#11). Shown only when
     * there is something to say; the repair goes through the same verified,
     * backed-up edit as the Collection page, and only while the game is closed.
     */
    private void legacyGifts(JPanel p, GameView.SaveRead read) {
        if (read.kind() != GameView.SaveKind.READABLE) return;
        var state = shell.tracker().state();
        var assessment = dev.yoru.game.LegacyGifts.assess(read.game().bytes(), state.rewards());
        var fixable = assessment.repairable();
        var left = assessment.leftAlone();
        if (fixable.isEmpty() && left.isEmpty()) return;
        gap(p, SPACE_LG);
        var c = card();
        c.setName("game.legacyGifts");
        c.add(sectionHeader("STUDY GIFTS FROM AN OLDER YORU", GOLD_TEXT)); gap(c, SPACE_MD);
        if (!fixable.isEmpty()) {
            c.add(bodyLabel(Theme.plural(fixable.size(), "study gift") + " went into your game in a layout it reads wrongly, "
                + "so the game shows the wrong Pokémon. Yoru can rewrite them exactly as it writes gifts now. "
                + "Your vault is backed up first."));
            gap(c, SPACE_SM);
            for (var f : fixable)
                c.add(label(SpeciesNames.of(f.reward().nationalDex()) + "  ·  Lv " + f.reward().level()
                    + "  ·  " + f.where().getFirst().describe(), TYPE_BODY, TEXT));
            gap(c, SPACE_MD);
            var repair = accentButton("Repair " + Theme.plural(fixable.size(), "study gift"), this::repairLegacyGifts);
            repair.setName("game.repairGifts");
            String blocked = read.editBlock(shell.game().running());
            repair.setEnabled(blocked == null);
            if (blocked != null) repair.setToolTipText(blocked);
            c.add(flushRow(repair));
        }
        if (!left.isEmpty()) {
            if (!fixable.isEmpty()) gap(c, SPACE_MD);
            var note = bodyLabel(Theme.plural(left.size(), "more gift") + " may have been written the same way but "
                + "changed in the game since, so Yoru cannot prove what it was and has left "
                + (left.size() == 1 ? "it" : "them") + " exactly as " + (left.size() == 1 ? "it is." : "they are."));
            note.setName("game.legacyLeftAlone");
            c.add(note);
        }
        p.add(c);
    }

    private void repairLegacyGifts() {
        if (!Dialogs.confirm(shell.owner(), "Rewrite these study gifts in the order the game reads?\n\n"
                + "Only gifts Yoru can prove are untouched are changed. Your vault is backed up first.",
                "Repair study gifts", "Repair"))
            return;
        shell.perform(() -> {
            if (shell.game().running())
                throw new IllegalStateException("Close the game first. While it runs it keeps its own copy of the save.");
            var state = shell.tracker().state();
            var before = state.game().bytes();
            shell.tracker().editSave(before, dev.yoru.game.LegacyGifts.repair(before, state.rewards()));
        });
    }
```

In `idle`, after `p.add(hero);`: `legacyGifts(p, read);`

- [ ] **Step 4: Run it to verify it passes**; then `./test.sh`.
- [ ] **Step 5: Render and look** — extend the scratch `StateRender` harness with a vault holding one repairable gift and one changed gift; review Game in all five themes at 900×640.
- [ ] **Step 6: Commit** — "Offer to repair legacy study gifts on the Game page".

### Task 5: Handoff

- [ ] `docs/CURRENT-STATE.md`: dated section — the history table above, the proof standard, what is deliberately left alone, and that no player save or real ledger was examined (the owner runs the assessment in their own app).
- [ ] `docs/ROADMAP.md`: #11 checkpoint line.
- [ ] Full `./test.sh`, push `claude/legacy-gifts`, PR "Closes #11" with defect, behaviour, tests, renders, limitations.
