# Yoru data model

> Decision record 003. Status: **proposed**.
> Read before changing `domain/Model.java` or `persistence/EncryptedVault.java`.

## Where we are

As of 2026-09-09, the app is in daily personal use. **Existing vaults
are user data and must be preserved.** The current implementation uses schema 5;
this document retains earlier design proposals, which are not all implemented.
Consult `Model` and `EncryptedVault` for the actual persisted shape.

Future format changes require a tested migration, a backup before replacement,
and fixtures proving old data survives. Never instruct users to delete a vault
to upgrade. Keep the companion key with password-free vault backups. This policy
supersedes the pre-user assumptions elsewhere in this historical proposal.

---

## 1. The one principle worth keeping now

**Store raw, derive rules.**

Recorded facts are immutable history: a session ran 09:00–09:03. Product rules
are derived at read time: sessions under five minutes don't count toward totals.

The reason this matters even with no users is that it keeps rule changes cheap.
When the 5-minute threshold becomes 3, or the daily goal moves from 4 hours to
6, nothing stored has to change — only the function that reads it. Bake a rule
into storage and every tweak becomes a migration.

This is why the 5-minute minimum is a constant in `Analytics`, not a filter at
write time, and not a `counts: boolean` column.

---

## 2. Target schema (v5)

One breaking change, covering everything currently on the roadmap. Additions and
changes against today's schema 4 are marked.

```
Settings                                                          [NEW]
  theme            enum { MIDNIGHT, <second masc>, SAKURA, LINEN }
  dailyGoalHours   int      (drives heat map thresholds; default 4)
  minSessionSecs   int      (default 300)

Activity
  id, name, targetMinutes
  colourId                                                        [NEW]

Tag                                                               [NEW]
  id, name, colour        // a class or section: "CS 240", "Japanese"

Session
  id, activityId, start, end, buddyId
  // unchanged. Sub-5-minute sessions are still stored, just not counted.

Task
  id, activityId, title, notes, due
  status    enum { TODO, DOING, DONE }        [CHANGED from done: boolean]
  tagId     nullable -> Tag                                       [NEW]
  order     int  (manual sort position)                           [NEW]
  createdAt Instant                                               [NEW]

RecurringBlock                                                    [NEW]
  id, activityId, dayOfWeek, startTime: LocalTime, endTime: LocalTime
  // replaces dated ScheduleBlock entirely

ScheduleBlock                                                    [KEPT]
  // one-off plans; see the corrected note below

Habit
  // unchanged

Capture
  id, species, caughtAt, shiny, evolvedAtSeconds
  nickname  nullable                                              [NEW]

CollectionState
  captures, buddyId, encountersUsed, rewardedSeconds
  partyIds  List<UUID>, max 6                                     [NEW]

GymRecord                                                         [NEW]
  id, gymId, defeatedAt
```

### Notes on the non-obvious choices

**`RecurringBlock` was going to replace `ScheduleBlock` outright — it does not.**
The original reasoning was "with no users, there is no reason to carry both
shapes". That premise expired: the app went into daily use with real vaults
before this was built, and deleting the dated shape would have destroyed
whatever was already planned in them.

Both are kept. `RecurringBlock` is the weekly template and `ScheduleBlock` is a
one-off, which is what a real timetable needs anyway — a lecture every Monday
*and* an exam next Thursday. Migrating the dated blocks into weekly repeats was
the other option considered and rejected: it would silently make that exam
recur forever.

Schema 6 reads schema 5 vaults forward with an empty template, so nothing that
already exists is disturbed.

It stores `LocalTime` + `dayOfWeek`, **not** `Instant`. A 09:00 class stays at
09:00 across a daylight-saving boundary, which is what "every Monday at nine"
actually means. Storing instants would drift it by an hour twice a year.

Adherence expands the template into concrete occurrences for whichever week is
on screen, then reuses the existing overlap maths against real sessions.

**`Task.done` becomes `Task.status`.** A three-state enum is needed for the
Notion-style board you described. `done()` survives as a derived accessor
(`status == DONE`) so the CSV export and existing call sites keep compiling.

**`Settings` lives in the vault, not in Java `Preferences`.** Theme and goals
travel with the workspace when it moves between machines. The only things that
stay in `Preferences` are machine-local: which vault was open last, remembered
by name rather than by path since #41, when Yoru took over choosing where
vaults are kept (`persistence.VaultStore`).

**`dailyGoalHours` drives the heat map.** Thresholds become fractions of the
goal rather than the hardcoded 15m/30m/1h/2h ladder, so the colours mean
something relative to your own target. Above 100% of goal is the rainbow tier.

---

## 3. Export — built

Not for migration. For three reasons that apply today:

1. **It is the debugging tool.** Reading a vault's actual contents as JSON beats
   inferring state from the UI when something looks wrong.
2. **It protects your own test data** while the schema churns.
3. **It is the fastest acceptance test.** Track for a week, export, read the
   numbers, confirm they match what the UI claimed.

Full JSON covering everything, user-initiated, unencrypted, with an explicit
warning in the dialog. Import validates through the same domain constructors as
a normal write and applies as one transaction or not at all.

`persistence.PortableVault` does this, and the Data page has both buttons.
Notes for anyone extending it:

- **Refusals name the field and the record index** — `tasks[1]: Missing "order"`.
  This is the tool people reach for when a vault already looks wrong, so
  "Invalid JSON" would be worse than useless.
- **Import builds the whole `State` before anything is written.** `Tracker.restore`
  takes an already-validated `State`, so a file that is wrong in its last record
  cannot leave the vault half-replaced.
- **The output is deterministic.** A habit's check-ins are a `Set`, whose
  iteration order is salted per JVM run, so they are sorted on the way out.
  Without that, exporting the same vault twice produces two different files and
  diffing one export against another stops working.
- **Colours are written as `#RRGGBB`**, being the one field a person reads back.
- `PortableVault.FORMAT` is the export version, not the storage schema. A file
  from a different format version is refused by number rather than guessed at.

---

## 4. What stays exactly as it is

The encryption envelope is sound and is not part of this change:
AES-256-GCM, fresh 96-bit nonce per save, PBKDF2-HMAC-SHA256 at 600k iterations,
random 128-bit salt, header authenticated as AAD, atomic temp-write-and-rename,
single-process file lock, fail-closed on bad password or corruption.

Validation in record constructors also stays. Making illegal states
unrepresentable at construction is why the janky UI has not corrupted a vault
yet — bad input throws before it reaches storage. The fix for the data-entry
jank belongs in the UI layer, catching input earlier and more legibly; it is not
a reason to loosen the domain.

---

## 5. When compatibility starts mattering

At **1.0** — defined as the first build handed to somebody other than its owner.

From that point:

- Schema version increments and each step gets a named, tested migration.
- A golden fixture vault is committed per released version.
- Prior encrypted file is copied to `<vault>.v<N>.bak` before the first
  upgraded write.
- Newer-vault-in-older-app opens read-only with a clear banner rather than
  refusing.

Until 1.0, none of that is built.

---

## 5a. Sessions recorded from Anki (1.0.15)

Anki study time is stored as ordinary `Session`s, with no new field and no
schema change. What marks one as Anki's is its id: a version-8 UUID (RFC 9562's
custom layout) that reads "Anki" in its first four bytes and carries the
sitting's first answer, as Anki's epoch-millisecond review id, in its low bits
(`AnkiTime.sittingId`). A random session id is version 4, so the two can never
collide. The id is also what makes recording idempotent: every refresh works
the sittings out again from Anki's log, and a sitting whose id is already in the
vault is left alone, even after it has been edited.

---

## 6. Open questions

- Second masculine theme: what direction? (Current `MIDNIGHT` is blue/cyan
  terminal; a warm amber CRT or a monochrome e-ink both contrast well.)
- Party of 6 — do all party members accrue buddy time, or only the active one?
  Splitting time across six is a materially different reward curve.
- Gym battles: fixed roster with a win condition, or a cosmetic milestone marker
  for hours studied?

## Pages storage (schema 14)

Schema 14 adds `Notes` (folders and Markdown pages) and task `pageIds`. Each
page has a stable UUID, optional folder UUID, title, raw Markdown, creation and
update timestamps, and an optional trash timestamp. Folders have stable IDs,
parents, names and trash timestamps. IDs remain stable when names or paths
change; links in Markdown are rewritten in the same save to retain meaning.
Older vaults load with empty notes and task links. Portable JSON carries notes
and links too. Migration and failed-write tests cover preserving existing data.
The existing whole-vault plaintext size limit still applies.
