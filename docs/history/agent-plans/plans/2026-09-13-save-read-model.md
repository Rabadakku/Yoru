# Save Read Model (#5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every page that shows the vault's game save reads it once, knows whether it is absent, unreadable (and why) or readable, and says so plainly — with export and setup reachable when the save cannot be read.

**Architecture:** `Gen3Save.read` keeps throwing `IllegalArgumentException`, but as a typed subclass carrying an `Unreadable` reason. `GameView.read(State)` turns the vault's `GameSave` into an immutable `SaveRead` (kind, reason, party/PC counts, saved time, edit block) once per page render; no static cache. Collection gains a one-line save-status strip and a proper unavailable card; the Game page uses the same read model, and export moves into a shared helper.

**Tech Stack:** Java 22, Swing, JDK only. Tests are plain `main` classes with `check(boolean,String)`, run by `test.sh`.

**Spec:** GitHub issue #5 and `docs/design/AUDIT-2026-09.md` (State matrix; Collection and Game screen specifications).

## Global Constraints

- `javac --release 22`; Swing and the JDK only, no runtime dependencies (README, ARCHITECTURE).
- Colours and fonts through `Theme`; dialogs through `Dialogs` (CONTRIBUTING §4).
- Tests use invented data only; build saves with `dev.yoru.game.Gen3Fixture` (AGENTS.md §1).
- No vault schema change; never rewrite a genuine save's bytes to display it (AUDIT, #4).
- No machine paths, script names or exception strings in ordinary product copy (#9).
- No static mutable caches in `GameView` for save data (#12): read per render and pass it down.
- Work on `claude/save-read-model`; `./test.sh` passes before push; commit messages say why and end with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

### Running one suite

`test.sh` isolates every JVM with an empty home and in-memory preferences. To run one suite the same way:

```bash
./build.sh && find src/test/java -name '*.java' > build/tests.txt \
  && javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes @build/tests.txt \
  && java -Duser.home="$(mktemp -d)" -Djava.util.prefs.PreferencesFactory=dev.yoru.ui.TestPreferencesFactory \
       -Djava.awt.headless=true -ea -cp build/classes <SuiteClass>
```

A compile error naming the missing symbol counts as the expected RED for a test written against an API that does not exist yet.

---

### Task 1: Typed reasons for an unreadable save

**Files:**
- Modify: `src/main/java/dev/yoru/game/Gen3Save.java:124-148` (`read`), and add `boxedCount()` after `boxed` (~line 416)
- Create: `src/test/java/dev/yoru/game/Gen3SaveReasonsTest.java`
- Modify: `test.sh` (register the suite after `dev.yoru.game.Gen3SaveTest`)

**Interfaces:**
- Produces: `public enum Gen3Save.Unreadable { WRONG_SIZE, NEVER_SAVED, DAMAGED, INCOMPLETE_SLOT }`
- Produces: `public static final class Gen3Save.UnreadableSave extends IllegalArgumentException { public Unreadable reason(); }`
- Produces: `public int Gen3Save.boxedCount()` — occupied PC slots across all boxes.

- [ ] **Step 1: Write the failing test**

```java
package dev.yoru.game;

import java.util.Arrays;
import java.util.List;

/**
 * Why a save cannot be read, as a reason a page can explain (#5).
 *
 * Every refusal stays an IllegalArgumentException, because delivery, storage
 * edits and import already catch that; the reason is what lets Collection say
 * "the game has never saved to it" instead of pretending there is no save.
 */
public final class Gen3SaveReasonsTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static Gen3Save.Unreadable reasonFor(byte[] raw) {
        try {
            Gen3Save.read(raw);
        } catch (Gen3Save.UnreadableSave e) {
            check(e instanceof IllegalArgumentException, "a refusal is still an IllegalArgumentException");
            return e.reason();
        }
        return null;
    }

    public static void main(String[] args) {
        check(reasonFor(new byte[1000]) == Gen3Save.Unreadable.WRONG_SIZE, "a 1000-byte file is the wrong size");

        var blank = new byte[Gen3Save.SIZE];
        Arrays.fill(blank, (byte) 0xFF);
        check(reasonFor(blank) == Gen3Save.Unreadable.NEVER_SAVED, "an erased cartridge has never been saved to");

        // Counter 1 lives in slot 1; flipping a checksummed byte leaves no complete slot.
        var damaged = Gen3Fixture.save(1, 0);
        damaged[Gen3Save.SLOT] ^= 0x01;
        check(reasonFor(damaged) == Gen3Save.Unreadable.DAMAGED, "a slot with a broken sector is damaged");

        // A sound slot 0 whose counter (3) sends the game to slot 1, which is empty.
        var misrouted = Gen3Fixture.saveInSlot(0, 3, 0);
        check(reasonFor(misrouted) == Gen3Save.Unreadable.INCOMPLETE_SLOT,
            "a counter pointing at an incomplete slot is refused as such");

        check(reasonFor(Gen3Fixture.save(2, 0)) == null, "a complete save reads");

        // Occupied PC slots are counted without decoding a page's worth of records.
        var raw = Gen3Fixture.save(2, 0);
        var save = Gen3Save.read(raw);
        check(save.boxedCount() == 0, "a new save's PC is empty");
        var storage = save.storage();
        var member = Gen3Fixture.member(raw, 25, 5, 1);
        System.arraycopy(member, 0, storage, Gen3Save.slotOffset(2, 5), Gen3Pokemon.BOX_SIZE);
        System.arraycopy(member, 0, storage, Gen3Save.slotOffset(13, 29), Gen3Pokemon.BOX_SIZE);
        save.storage(storage);
        check(Gen3Save.read(save.bytes()).boxedCount() == 2, "two boxed Pokémon count as two, wherever they are");

        System.out.println("PASS: " + checks + " unreadable-save reason checks (size, never saved, damaged, misrouted, PC count)");
    }
}
```

Register it in `test.sh` directly after the `dev.yoru.game.Gen3SaveTest` line:

```sh
java -ea -cp build/classes dev.yoru.game.Gen3SaveReasonsTest
```

- [ ] **Step 2: Run it to verify it fails**

Run: the one-suite command with `dev.yoru.game.Gen3SaveReasonsTest`.
Expected: compile failure `cannot find symbol ... UnreadableSave` / `boxedCount`.

- [ ] **Step 3: Implement the reasons and the count**

In `Gen3Save.java`, above `read`:

```java
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
```

Replace the three `throw new IllegalArgumentException(...)` statements in `read` with the same messages:

```java
        if (raw.length != SIZE)
            throw new UnreadableSave(Unreadable.WRONG_SIZE, "A Gen 3 save is " + SIZE + " bytes, got " + raw.length);
        ...
        else if (first.status() == Status.EMPTY && second.status() == Status.EMPTY)
            throw new UnreadableSave(Unreadable.NEVER_SAVED, "This save is empty: the game has never saved to it.");
        else throw new UnreadableSave(Unreadable.DAMAGED, "Neither save slot holds a complete, checksummed save.");
        ...
        if (loaded.status() != Status.OK)
            throw new UnreadableSave(Unreadable.INCOMPLETE_SLOT, "The game would load save slot " + (slot + 1)
                + ", which is not a complete save, so it cannot be read reliably.");
```

After `boxed`:

```java
    /** How many PC slots hold a Pokémon, across every box. */
    public int boxedCount() {
        var storage = storage();
        int count = 0;
        for (int box = 0; box < BOXES; box++)
            for (int index = 0; index < PER_BOX; index++)
                if (!empty(storage, slotOffset(box, index))) count++;
        return count;
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: the one-suite command with `dev.yoru.game.Gen3SaveReasonsTest`, then with `dev.yoru.game.Gen3SaveTest`.
Expected: `PASS: ... unreadable-save reason checks`, and `Gen3SaveTest` still passes (messages unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/yoru/game/Gen3Save.java src/test/java/dev/yoru/game/Gen3SaveReasonsTest.java test.sh
git commit -m "Give unreadable saves a reason pages can explain

Collection turned every refusal into 'no save', so an erased cartridge,
a damaged slot and a misrouted counter all read as a new game (#5). The
refusal stays an IllegalArgumentException so delivery, storage edits and
import are unchanged; the reason is for the read model.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 2: Relative saved time

**Files:**
- Create: `src/main/java/dev/yoru/ui/Ago.java`
- Create: `src/test/java/dev/yoru/ui/AgoTest.java`
- Modify: `test.sh` (register after `dev.yoru.ui.InputTest`)

**Interfaces:**
- Produces: `static String Ago.describe(Instant then, Instant now, ZoneId zone)`

- [ ] **Step 1: Write the failing test**

```java
package dev.yoru.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** "Saved in your vault · 5 minutes ago": when, the way a person says it. */
public final class AgoTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static void says(String expected, String then, String now, ZoneId zone) {
        String got = Ago.describe(Instant.parse(then), Instant.parse(now), zone);
        check(expected.equals(got), then + " seen at " + now + " reads \"" + expected + "\", got \"" + got + "\"");
    }

    public static void main(String[] args) {
        var utc = ZoneOffset.UTC;
        String now = "2026-09-13T12:00:00Z";
        says("just now", "2026-09-13T12:00:00Z", now, utc);
        says("just now", "2026-09-13T11:59:01Z", now, utc);
        says("just now", "2026-09-13T12:00:30Z", now, utc);          // a clock slightly ahead
        says("1 minute ago", "2026-09-13T11:59:00Z", now, utc);
        says("5 minutes ago", "2026-09-13T11:55:00Z", now, utc);
        says("59 minutes ago", "2026-09-13T11:01:00Z", now, utc);
        says("1 hour ago", "2026-09-13T11:00:00Z", now, utc);
        says("23 hours ago", "2026-09-12T13:00:00Z", now, utc);
        says("yesterday", "2026-09-12T08:00:00Z", now, utc);
        says("Tue 8 Sep", "2026-09-08T09:00:00Z", now, utc);

        // Across midnight an hour is still an hour, not "yesterday".
        says("1 hour ago", "2026-09-12T23:30:00Z", "2026-09-13T00:30:00Z", utc);

        // Which day it was depends on where the reader is.
        says("Fri 11 Sep", "2026-09-11T23:00:00Z", "2026-09-13T03:00:00Z", utc);
        says("yesterday", "2026-09-11T23:00:00Z", "2026-09-13T03:00:00Z", ZoneId.of("America/New_York"));

        System.out.println("PASS: " + checks + " relative time checks (minutes, hours, yesterday, date, zones)");
    }
}
```

Register in `test.sh` after `dev.yoru.ui.InputTest`:

```sh
java -ea -cp build/classes dev.yoru.ui.AgoTest
```

- [ ] **Step 2: Run it to verify it fails**

Run: the one-suite command with `dev.yoru.ui.AgoTest`.
Expected: compile failure `cannot find symbol ... Ago`.

- [ ] **Step 3: Implement**

```java
package dev.yoru.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** When something happened, as a person says it: "just now", "5 minutes ago", "yesterday", "Tue 8 Sep". */
final class Ago {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private Ago() { }

    static String describe(Instant then, Instant now, ZoneId zone) {
        long seconds = Duration.between(then, now).getSeconds();
        // Under a minute, or a clock a little ahead of this one.
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        long hours = minutes / 60;
        if (hours < 24) return hours == 1 ? "1 hour ago" : hours + " hours ago";
        var day = then.atZone(zone).toLocalDate();
        if (day.equals(now.atZone(zone).toLocalDate().minusDays(1))) return "yesterday";
        return then.atZone(zone).format(DAY);
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the one-suite command with `dev.yoru.ui.AgoTest`.
Expected: `PASS: 13 relative time checks ...`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/yoru/ui/Ago.java src/test/java/dev/yoru/ui/AgoTest.java test.sh
git commit -m "Say when the vault last saved the way a person would

The save-status strip needs 'Saved in your vault · 5 minutes ago' (#5).
Hours are counted before dates so an hour across midnight is not called
yesterday, and the day is judged in the reader's own zone.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 3: The SaveRead read model

**Files:**
- Modify: `src/main/java/dev/yoru/ui/GameView.java:24-47` (replace `SaveHealth`/`health`/`save`)
- Create: `src/test/java/dev/yoru/ui/SaveReadTest.java`
- Modify: `test.sh` (register after `dev.yoru.ui.AgoTest`)

**Interfaces:**
- Consumes: `Gen3Save.UnreadableSave`, `Gen3Save.Unreadable`, `Gen3Save.boxedCount()` (Task 1)
- Produces:
  - `enum GameView.SaveKind { ABSENT, UNREADABLE, READABLE }` (unchanged)
  - `record GameView.SaveRead(SaveKind kind, GameSave game, Gen3Save save, Gen3Save.Unreadable problem, int partyCount, int boxedCount)` with `Instant savedAt()`, `String headline()`, `String detail()`, `String editBlock(boolean gameRunning)`
  - `static SaveRead GameView.read(State state)`
  - `static Gen3Save GameView.save(State state)` kept, now `read(state).save()`
  - `GameView.health` and `GameView.SaveHealth` are removed.

- [ ] **Step 1: Write the failing test**

```java
package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.game.Gen3Fixture;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * The vault's save as the pages see it (#5): absent, unreadable with a reason,
 * or readable with its party and PC counted, and never changed by being read.
 */
public final class SaveReadTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        boolean failing;
        public State load() { return state; }
        public void save(State next) throws IOException {
            if (failing) throw new IOException("The disk is full.");
            state = next;
        }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());

        var absent = GameView.read(tracker.state());
        check(absent.kind() == GameView.SaveKind.ABSENT && absent.save() == null && absent.savedAt() == null,
            "no save is absent, with nothing to show");
        check(absent.headline().equals("No game save yet"), "and says so");
        check(absent.detail().equals("Save once inside the game to show your party here."), "with the next step");
        check(absent.editBlock(false) != null, "and there is nothing to edit");

        byte[] wrongSize = new byte[1000];
        Arrays.fill(wrongSize, (byte) 7);
        tracker.replaceGameSave(wrongSize);
        var unreadable = GameView.read(tracker.state());
        check(unreadable.kind() == GameView.SaveKind.UNREADABLE, "bytes that are not a save are unreadable, not absent");
        check(unreadable.problem() == dev.yoru.game.Gen3Save.Unreadable.WRONG_SIZE, "and carry their reason");
        check(unreadable.headline().equals("Your save is here, but Yoru cannot read it."), "the headline keeps the save's presence first");
        check(!unreadable.detail().contains("bytes") && !unreadable.detail().contains("Exception"),
            "the detail is a sentence, not an internal message: " + unreadable.detail());
        check(unreadable.savedAt().equals(tracker.state().game().updatedAt()), "its saved time is still the vault's");
        check(unreadable.editBlock(false) != null, "an unreadable save is never edited");
        check(tracker.state().game().holds(wrongSize), "reading it changed nothing");

        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 0), "TESTER", 0, 12345, 54321);
        raw = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 258, 5, 1), Gen3Fixture.member(raw, 25, 7, 2)));
        tracker.replaceGameSave(raw);
        var readable = GameView.read(tracker.state());
        check(readable.kind() == GameView.SaveKind.READABLE && readable.save() != null, "a complete save reads");
        check(readable.partyCount() == 2 && readable.boxedCount() == 0, "with its party and PC counted, got "
            + readable.partyCount() + " / " + readable.boxedCount());
        check(!readable.save().hasStarter(), "this fixture never set the starter flag");
        check(readable.editBlock(false) == null, "a closed, consistent save can be edited");
        check(readable.editBlock(true) != null, "but not while the game runs");

        // A failed write leaves the last durable time where it was.
        var before = tracker.state().game().updatedAt();
        repo.failing = true;
        try {
            tracker.replaceGameSave(Gen3Fixture.save(4, 0));
            check(false, "a failing disk refuses the write");
        } catch (IOException expected) { }
        check(GameView.read(tracker.state()).savedAt().equals(before), "the saved time does not advance on a failed write");
        check(GameView.read(tracker.state()).partyCount() == 2, "and the page still reads the save that was kept");

        System.out.println("PASS: " + checks + " save read model checks (absent, unreadable reason, counts, edit block, failed write)");
    }
}
```

Register in `test.sh` after `dev.yoru.ui.AgoTest`:

```sh
java -ea -cp build/classes dev.yoru.ui.SaveReadTest
```

- [ ] **Step 2: Run it to verify it fails**

Run: the one-suite command with `dev.yoru.ui.SaveReadTest`.
Expected: compile failure `cannot find symbol ... read(State)` / `SaveRead`.

- [ ] **Step 3: Implement the read model**

Replace lines 17-47 of `GameView.java` (the class comment through `health`) with:

```java
/**
 * The vault's game save, read for the pages that show it.
 *
 * A page reads it once per render with {@link #read} and passes the result
 * down. Nothing is cached here: a static cache kept another vault's records
 * alive after a switch, and a Gen3Save is a working copy with setters, not
 * something pages should share.
 */
final class GameView {

    enum SaveKind { ABSENT, UNREADABLE, READABLE }

    /** One reading of the vault's save (#5). The bytes stay in the vault's GameSave, untouched. */
    record SaveRead(SaveKind kind, GameSave game, Gen3Save save, Gen3Save.Unreadable problem,
                    int partyCount, int boxedCount) {

        Instant savedAt() { return game == null ? null : game.updatedAt(); }

        String headline() {
            return switch (kind) {
                case ABSENT -> "No game save yet";
                case UNREADABLE -> "Your save is here, but Yoru cannot read it.";
                case READABLE -> "Saved in your vault";
            };
        }

        String detail() {
            if (kind == SaveKind.ABSENT) return "Save once inside the game to show your party here.";
            if (kind == SaveKind.READABLE) return "";
            return switch (problem) {
                case WRONG_SIZE -> "It is not the size of a Pokémon Emerald save. Export a copy before trying anything else.";
                case NEVER_SAVED -> "The game has never written to it. Open the game and save once.";
                case DAMAGED -> "Neither of its save slots is complete. Export a copy before trying anything else.";
                case INCOMPLETE_SLOT -> "The slot the game would load is incomplete. Export a copy before trying anything else.";
            };
        }

        /** Why this save cannot be changed from Yoru right now, or null when it can. */
        String editBlock(boolean gameRunning) {
            if (kind == SaveKind.ABSENT) return "There is no save in this vault yet.";
            if (kind == SaveKind.UNREADABLE) return "Yoru cannot read this save, so it will not change it.";
            if (gameRunning) return "Close the game first. While it runs it keeps its own copy of the save.";
            return save.whyNotEditable();
        }
    }

    private GameView() { }

    static SaveRead read(State state) {
        var game = state.game();
        if (game == null) return new SaveRead(SaveKind.ABSENT, null, null, null, 0, 0);
        try {
            var save = Gen3Save.read(game.bytes());
            return new SaveRead(SaveKind.READABLE, game, save, null, save.partyCount(), save.boxedCount());
        } catch (Gen3Save.UnreadableSave e) {
            return new SaveRead(SaveKind.UNREADABLE, game, null, e.reason(), 0, 0);
        }
    }

    /** The decoded save, or null when there is none or it cannot be read. */
    static Gen3Save save(State state) { return read(state).save(); }
```

Add imports `dev.yoru.domain.Model.GameSave` and `java.time.Instant`.

Update the two `health` callers so the build compiles (their behaviour is finished in Tasks 4-5):
- `CollectionPage.java:120-123` — `var read = GameView.read(shell.tracker().state());` then use `read.kind()` / `read.detail()` in place of `health`.
- `GamePage.java:82` — `hero.add(bodyLabel(GameView.read(state).detail()));`

- [ ] **Step 4: Run it to verify it passes**

Run: the one-suite command with `dev.yoru.ui.SaveReadTest`, then `./test.sh`.
Expected: `PASS: ... save read model checks`; full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/yoru/ui/GameView.java src/main/java/dev/yoru/ui/CollectionPage.java src/main/java/dev/yoru/ui/GamePage.java src/test/java/dev/yoru/ui/SaveReadTest.java test.sh
git commit -m "Read the vault's save once into a model pages can trust

Pages asked GameView for a Gen3Save three or four times a render and got
null for both 'no save' and 'cannot read it' (#5). SaveRead keeps the kind,
the reason, the party and PC counts, the durable time and why editing is
blocked, and is read per render rather than cached, so a vault switch can
never show another vault's Pokémon.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 4: Collection status strip and unavailable card

**Files:**
- Create: `src/main/java/dev/yoru/ui/SaveExport.java` (moved from `GamePage.exportSave`)
- Modify: `src/main/java/dev/yoru/ui/Theme.java` (move `flushRow` there from `GamePage`, beside `tightRow` ~line 995)
- Modify: `src/main/java/dev/yoru/ui/CollectionPage.java:36-55` (`view`), `:118-127` (`empty` → `unavailable`), add `saveStatus`
- Modify: `src/main/java/dev/yoru/ui/GamePage.java:103-106,258-269` (use `SaveExport`, name the export button)
- Create: `src/test/java/dev/yoru/ui/SaveStatusUiTest.java`
- Modify: `test.sh` (register after `dev.yoru.ui.StorageTest`)

**Interfaces:**
- Consumes: `GameView.read`, `GameView.SaveRead` (Task 3); `Ago.describe` (Task 2); `GameController.saveStatus()`, `GameController.retrySave()`
- Produces: `static JPanel Theme.flushRow(JComponent... controls)` (moved unchanged from `GamePage`)
- Produces: `static void SaveExport.export(Shell shell)`; component names `collection.saveStatus`, `collection.retrySave`, `collection.unavailable`, `collection.export`, `collection.setup`, `collection.openGame`, `game.export`

- [ ] **Step 1: Write the failing test**

```java
package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.game.Gen3Fixture;
import java.awt.*;
import java.time.Clock;
import java.util.List;
import javax.swing.*;

/**
 * Collection and Game with no core and no game file (#5): an absent save, an
 * unreadable one and a readable pre-starter one each say what they are, and
 * export and setup stay reachable when the save cannot be read.
 */
public final class SaveStatusUiTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    private static YoruApp app;

    private interface Action { void run() throws Exception; }

    private static void onEdt(Action action) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { action.run(); } catch (Exception e) { throw new IllegalStateException(e); }
            });
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { var found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static void open(String page) { onEdt(() -> ((JButton) find(app, page)).doClick()); }

    private static String text(String name) {
        var c = find(app, name);
        return c instanceof JLabel l ? l.getText() : c instanceof AbstractButton b ? b.getText() : null;
    }

    public static void main(String[] args) throws Exception {
        var repo = new Memory();
        var tracker = new Tracker(repo, Clock.systemUTC());
        tracker.addActivity("Study", 0);
        onEdt(() -> { app = new YoruApp(tracker, repo); app.setSize(900, 640); });

        open("Collection");
        check("No save in this vault yet".equals(text("collection.saveStatus")), "an empty vault says it has no save, got "
            + text("collection.saveStatus"));
        check(find(app, "collection.openGame") != null, "and offers the game");
        check(find(app, "collection.arrange") == null, "and shows no storage to arrange");

        byte[] notASave = new byte[1000];
        onEdt(() -> tracker.replaceGameSave(notASave));
        open("Collection");
        check("Saved in your vault · cannot be read".equals(text("collection.saveStatus")),
            "an unreadable save is still in the vault, got " + text("collection.saveStatus"));
        var export = (JButton) find(app, "collection.export");
        check(export != null && export.isEnabled(), "an unreadable save can be exported from Collection");
        check(find(app, "collection.setup") != null, "and Game setup is one click away");
        check(find(app, "collection.arrange") == null, "and nothing offers to edit it");
        check(tracker.state().game().holds(notASave), "showing it changed nothing");

        open("Game");
        var gameExport = (JButton) find(app, "game.export");
        check(gameExport != null && gameExport.isEnabled(), "with no core or game file, the Game page still exports the save");

        var raw = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 0), "TESTER", 0, 12345, 54321);
        byte[] party = Gen3Fixture.withParty(raw, List.of(Gen3Fixture.member(raw, 258, 5, 1)));
        onEdt(() -> tracker.replaceGameSave(party));
        open("Collection");
        String status = text("collection.saveStatus");
        check(status != null && status.startsWith("Saved in your vault · "), "a readable save says when it was saved, got " + status);
        check(find(app, "collection.arrange") != null, "and its storage is shown even though the starter flag is unset");
        check(find(app, "collection.unavailable") == null, "with no unavailable card beside it");

        open("Game");
        check("TESTER".equals(text("game.saveHeadline")),
            "a save with a party is not called a new adventure, got " + text("game.saveHeadline"));

        onEdt(() -> ((JButton) find(app, "Lock & close")).doClick());
        System.out.println("PASS: " + checks + " save status UI checks (absent, unreadable, pre-starter party, no core)");
    }
}
```

Register in `test.sh` after `dev.yoru.ui.StorageTest`:

```sh
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.SaveStatusUiTest
```

- [ ] **Step 2: Run it to verify it fails**

Run: the one-suite command with `dev.yoru.ui.SaveStatusUiTest`.
Expected: `AssertionError: an empty vault says it has no save, got null`.

- [ ] **Step 3: Implement**

Move `GamePage.flushRow` into `Theme` unchanged (package-private `static JPanel flushRow(JComponent... controls)`) and delete `GamePage`'s copy. `Theme.tightRow()` leaves `SPACE_SM` before its first control, which would put the status line 8 px right of the page header — the zigzag `flushRow` was written to remove.

`SaveExport.java` — the body of `GamePage.exportSave`, unchanged in behaviour:

```java
package dev.yoru.ui;

import java.nio.file.Files;

/** Writes a copy of the vault's game save to a file the user chooses, from any page that shows it. */
final class SaveExport {
    private SaveExport() { }

    static void export(Shell shell) {
        var game = shell.tracker().state().game();
        if (game == null) return;
        var rom = GameFiles.rom();
        var file = Dialogs.saveFile(shell.owner(), "Export game save", (rom == null ? "game" : GameFiles.stem(rom)) + ".sav");
        if (file == null) return;
        shell.perform(() -> {
            Files.write(file, game.bytes());
            Dialogs.info(shell.owner(), "A copy of your game is saved in " + file.getFileName() + ".\n\n"
                + "Emulators load a save that sits beside the game file under the same name. RetroArch looks for .srm.");
        });
    }
}
```

In `GamePage`: delete `exportSave`; create the button as `var export = button("Export save…", () -> SaveExport.export(shell)); export.setName("game.export");`. In `idle`, read once and name the headline:

```java
        var state = shell.tracker().state();
        var read = GameView.read(state);
        var save = read.save();
        var hero = card();
        hero.add(sectionHeader("SAVED GAME")); gap(hero, SPACE_MD);
        JLabel headline;
        if (read.kind() == GameView.SaveKind.ABSENT) {
            headline = label("No save yet", TYPE_HEADING, TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(bodyLabel("Press Play to begin. Once you choose your starter and save in the game, your vault keeps every save it makes."));
        } else if (read.kind() == GameView.SaveKind.UNREADABLE) {
            headline = label(read.headline(), TYPE_HEADING, GOLD_TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(bodyLabel(read.detail()));
        } else if (!save.hasStarter() && read.partyCount() == 0) {
            headline = label("A new adventure", TYPE_HEADING, TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(bodyLabel("Choose your starter and save in the game. It becomes your study companion."));
        } else {
            var trainer = save.trainer();
            int badges = save.badges(), minutes = trainer.playTimeMinutes();
            headline = label(trainer.name(), TYPE_HEADING, TEXT);
            hero.add(headline); gap(hero, SPACE_SM);
            hero.add(label(Theme.plural(badges, "badge") + " · " + save.ownedCount() + " caught · "
                + Analytics.report(minutes * 60L) + " played" + (save.gameClear() ? " · Hall of Fame" : ""), TYPE_BODY, TEXT));
        }
        headline.setName("game.saveHeadline");
```

(add `import javax.swing.JLabel;`)

In `CollectionPage.view`, read once, add the status strip, and choose storage or the unavailable card:

```java
    JPanel view() {
        var state = shell.tracker().state();
        var read = GameView.read(state);
        var p = stack();
        p.add(YoruApp.pageHeaderFor("Collection",
            "YOUR GAME'S POKÉMON · ONE ENCOUNTER FOR EVERY 30 MINUTES RECORDED"));
        p.add(saveStatus(read));
        gap(p, SPACE_LG);
        if (shell.game().running()) {
            p.add(label("The game is running. This is its last save; what you catch now appears when it saves again.", TYPE_BODY, GOLD_TEXT));
            gap(p, SPACE_LG);
        }
        p.add(read.kind() == GameView.SaveKind.READABLE ? storage(read.save()) : unavailable(read));
        gap(p, SPACE_XL);
        var top = new JPanel(new GridLayout(1, 2, SPACE_LG, 0));
        top.setOpaque(false);
        top.setAlignmentX(0);
        top.add(encounters(state));
        top.add(onTheirWay(shell));
        p.add(top);
        return p;
    }

    /** One line saying whether the Pokémon below are what the vault durably holds. */
    private JComponent saveStatus(GameView.SaveRead read) {
        var status = shell.game().saveStatus();
        JLabel line;
        if (status == GameController.SaveStatus.FAILED) {
            line = label("Could not save to your vault", TYPE_CAPTION, DANGER);
        } else if (status == GameController.SaveStatus.PENDING) {
            line = label("Saving to your vault…", TYPE_CAPTION, MUTED);
        } else if (read.kind() == GameView.SaveKind.READABLE) {
            line = label("Saved in your vault · " + Ago.describe(read.savedAt(), java.time.Instant.now(),
                java.time.ZoneId.systemDefault()), TYPE_CAPTION, MUTED);
        } else if (read.kind() == GameView.SaveKind.UNREADABLE) {
            line = label("Saved in your vault · cannot be read", TYPE_CAPTION, GOLD_TEXT);
        } else {
            line = label("No save in this vault yet", TYPE_CAPTION, MUTED);
        }
        line.setName("collection.saveStatus");
        if (status != GameController.SaveStatus.FAILED) return flushRow(line);
        var retry = button("Retry save", () -> { shell.game().retrySave(); shell.show("Collection"); });
        retry.setName("collection.retrySave");
        return flushRow(line, retry);
    }

    /** No save, or one Yoru cannot read: say which, and keep the way out in reach. */
    private JPanel unavailable(GameView.SaveRead read) {
        var c = card();
        c.setName("collection.unavailable");
        c.add(label(read.headline(), TYPE_HEADING, TEXT));
        gap(c, SPACE_SM);
        c.add(bodyLabel(read.detail()));
        gap(c, SPACE_LG);
        if (read.kind() == GameView.SaveKind.UNREADABLE) {
            var export = accentButton("Export a copy", () -> SaveExport.export(shell));
            export.setName("collection.export");
            var setup = button("Open Game setup", () -> shell.show("Game"));
            setup.setName("collection.setup");
            c.add(flushRow(export, setup));
        } else {
            var game = accentButton("Open the game", () -> shell.show("Game"));
            game.setName("collection.openGame");
            c.add(flushRow(game));
        }
        return c;
    }
```

Delete the old `empty(Gen3Save)` method.

- [ ] **Step 4: Run it to verify it passes**

Run: the one-suite command with `dev.yoru.ui.SaveStatusUiTest`, then `./test.sh`.
Expected: `PASS: ... save status UI checks`; full suite green.

- [ ] **Step 5: Render and look**

```bash
java -Duser.home="$(mktemp -d)" -Djava.util.prefs.PreferencesFactory=dev.yoru.ui.TestPreferencesFactory \
  -cp build/classes dev.yoru.ui.Preview build/preview 900 640 MIDNIGHT
```

Open `build/preview/collection*.png` and `build/preview/game*.png`; confirm the status line sits under the header, nothing is clipped at 900×640, and repeat for `LINEN` (a light theme).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/yoru/ui/SaveExport.java src/main/java/dev/yoru/ui/CollectionPage.java src/main/java/dev/yoru/ui/GamePage.java src/test/java/dev/yoru/ui/SaveStatusUiTest.java test.sh
git commit -m "Show whether Collection is the vault's real save, and keep export in reach

An unreadable save offered only 'Open the game', and a readable one never
said when it was saved (#5). Collection now leads with one status line —
saved, saving, could not save with Retry, cannot be read, or no save — and
an unreadable save gets Export a copy and Open Game setup. The Game page no
longer calls a save with a party 'a new adventure' because its starter
flag is unset.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

**Changed after visual review.** The rendered absent and unreadable states put "No save in this vault yet" directly above "No game save yet", and "Saved in your vault · cannot be read" above "Your save is here, but Yoru cannot read it." — the same message twice. As shipped, `saveStatus` returns null (and `view` adds nothing) unless the save is readable or the controller reports PENDING/FAILED; `SaveStatusUiTest` asserts the card is present and `collection.saveStatus` is absent for those two states instead of the strings above.

### Task 5: Record the handoff

**Files:**
- Modify: `docs/ROADMAP.md` ("Current audit overrides" section)
- Modify: `docs/CURRENT-STATE.md` (new dated section at the top)

- [ ] **Step 1:** Add under "Current audit overrides": `#5 save read model on claude/save-read-model: typed unreadable reasons, SaveRead read once per render (no static cache), Collection status strip with Retry, unavailable card with Export a copy / Open Game setup, pre-starter party shown as the trainer's game.` State the remaining limitation: the reward cards still precede nothing larger than a card; Collection layout proper is #8.
- [ ] **Step 2:** `./test.sh` once more; commit docs with a message saying why; push the branch; open a PR referencing #5 whose body lists defect, behaviour, tests, rendered states reviewed and limitations (AUDIT "Evidence and release gates").
