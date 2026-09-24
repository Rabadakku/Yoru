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

### Anki summary lifecycle (#51)

Schema 16’s `AnkiSnapshot` persists profile name, today’s count, daily counts
and fetch time. Reopening reads this summary before contacting Anki; failed
refreshes never erase it. A cache-save failure is displayed and retains the
previous saved summary. Automatic retries belong to the open vault and stop
when disabled or closed, independent of the visible tab. No schema change in
1.0.17.

Automatic Anki streaks retain the complete supplied daily-count map (up to the
vault limit of 100,000 entries) rather than trimming to 30 entries. The chart
still shows a month. This changes neither schema 20 nor portable format 9: the
same count/date/value entries are written, and existing short summaries load
unchanged. Earlier history is restored at the next successful refresh. Opening
and saving in an older Yoru build will trim the cache again; Anki remains its
source. No derived streak value is stored.

## Several tags a task (schema 18, #66)

Schema 18 replaces a task's one optional tag (`tagId`) with a list of tags
(`tagIds`), in the order they were given, once each, at most
`Task.MAX_TAGS` (20). In the vault it is a count followed by that many UUIDs
where schemas 5 to 17 wrote a present-flag and one UUID; an older vault reads
each task's single tag as a list of one, and an untagged task as an empty
list. `LegacyVaultTest` opens a schema 17 vault written by 1.0.19 and holds
every task to the tag it had.

The portable export is format 7: `"tagIds": [...]` where formats up to 6
wrote `"tagId"`. Both are read. Deleting a tag takes only that tag off the
tasks that carry it; a task keeps its others. A tag typed into the task form
is created in the same write as the task (`Tracker.saveTask`), so a failed
save cannot leave a tag behind for a task that was never kept. A Notion
multi-select column gives a task every tag it lists.

## Task lists (schema 19, #56)

Schema 19 adds `TaskList(id, name, colour, order)` to the state, after the
Anki integration, and `listId` to each task, after its page links: a
present-flag and a UUID, with no list meaning the Inbox. List names are unique
whatever their case, 1 to 40 characters; a task may only be in a list that
exists. Older vaults arrive with no lists and every task in the Inbox.

Deleting a list takes a backup, then either moves its tasks to the Inbox or
deletes them with it, as the owner chooses. "Make list" on a tag makes a list
with the tag's name and colour holding every task the tag is on; the tag stays.
Resetting lists files every task in the Inbox. Manual order stays one total
order over every task: reordering inside a list swaps its tasks among the
places they already hold, so other lists never move.

The portable export is format 8: `"lists"` at the top level and `"listId"` on
each task. A file without them reads with every task in the Inbox.

## Repeating tasks (schema 20, #57)

Schema 20 adds two parts to each task, after its list: an optional `Repeat`
rule and the `Occurrence`s behind it. A rule is a unit (day, week, month,
year), an interval, the weekdays of a weekly rule (one bit each in the vault,
names in the export), a day of the month or an nth weekday (-1 for the last),
the start it counts from, whether the next date comes from the schedule or
from the day it was finished, and an optional last day or number of times.
An occurrence is the day it was due, when it was done or skipped, and which.

The rule's dates are worked out, never stored (`application.Repeats`): the
31st falls on a shorter month's last day, the 29th of February on the 28th in
other years, weeks and fortnights break where the owner's week starts, and a
task finished late is next due today at the earliest rather than on the days
it missed. The task's due date is the occurrence now in front of it, which is
what "edit this occurrence" edits; finishing or skipping records it and moves
the due date on, and when the rule runs out the task is simply done. A
repeating task always has a due date. Older vaults arrive with nothing
repeating. The export is format 9.

## One week of a weekly repeat (schema 21, #59)

Schema 21 lets one week of a weekly repeat differ from its rule without
changing the rule: skipped, or held at another time — and, within six days of
its own, on another day. Each `RecurringBlock` keeps a list of `RepeatChange`s,
one a week at most and at most `RecurringBlock.MAX_CHANGES` (1,040), keyed by
the date the rule would have put that week's block on. A change is that date,
then either nothing (skipped) or the day it moved to and its start and end as
local times, like the rule's own. The rule's dates are still worked out rather
than stored (`Analytics.occurrences`): a changed week is left off its own day
and drawn where it moved to, and a week moved across the edge of the week shown
is drawn by the week it landed in.

Overlaps are checked as the rules are, on each date a change reaches: the
rules' blocks that day, less the weeks moved away or skipped, plus the weeks
moved onto it. Changing a rule's time keeps its changed weeks; moving it to
another day puts them back, since there is no block left on the dates they
name, and the form says so first. A week put back where the rule has it keeps
no change.

In the vault the changes follow the task lists: for each rule, in the order the
rules were written, a count and then each change (the date as an epoch day, a
moved flag, and if moved the day and the two times as seconds of the day). An
older vault arrives with every week following its rule; `LegacyVaultTest` opens
a schema 20 vault written by the unmodified build. The portable export is
format 10: `"changes"` on each repeat, with `"week"`, `"skipped"` and, when
moved, `"day"`, `"startTime"` and `"endTime"`. A file without them reads with
no changed weeks.

## Tasks as a database (schema 22, #68)

Schema 22 gives every task a `Details` part and the vault a `TaskDatabase`
part. Later database features (views, sub-tasks, projects, templates) add to
these two rather than to `Task`'s and `State`'s own arguments.

`Details` is a task's `Priority` (none, low, medium, high, urgent), the id of
one of the owner's `StatusOption`s or null for its group's own status, the
values of its properties keyed by property id, and `editedAt`. A value is one
of `Value.Text` (text and links), `Value.Amount` (an exact decimal, compared
with trailing zeros stripped, so 2.50 is 2.5), `Value.Choice`, `Value.Choices`,
`Value.Day` or `Value.Tick`; an empty value is no entry, and an unticked
checkbox is not stored. `editedAt` is a raw fact stamped by `Tracker.commit`
on every task whose content changed — not its place in the manual order, not
a task just added, and not a whole-vault import, which keeps the times it
carries. A task from before it was kept reads its creation time.

`TaskDatabase` holds the owner's `StatusOption`s (a name and colour inside one
of the three `TaskStatus` groups; names unique, the groups' own "To do",
"Doing" and "Done" included) and `Property` definitions: a name, a
`PropertyType`, the options of a select or multi-select, the list it belongs to
or null for every task, and whether it is hidden from the table. The two
`PropertyType`s CREATED and EDITED hold no values; they read the task's times.
State refuses a status in the wrong group, a value its property does not
accept, a value for a property that is gone, and a property for a list that is
gone. Deleting a list gives its properties to every task, and resetting lists
does the same; resetting "Task properties and statuses" clears the database
and the values and statuses on tasks, leaving priorities.

In the vault each task's details follow its history: the priority's name, the
status id (flagged), `editedAt` (flagged), and the values as a count of
property id, a kind byte and that kind's payload, in id order. The statuses
and properties follow the repeat changes of schema 21. `LegacyVaultTest`
opens a schema 21 vault written by `610a59d`. The portable export is format
11: `"statuses"` and `"properties"` at the top level, and on each task
`"priority"`, `"statusId"`, `"editedAt"` and `"values"`, each value an object
naming its kind (`"text"`, `"number"` as a string, `"option"`, `"options"`,
`"date"`, `"checked"`). A file without them reads with no database.

## A time of day on the due date (schema 23, #74)

Schema 23 adds `dueTime` to a task's `Details`: a `LocalTime` to the minute,
or null. It only means something beside a due date, so `Task` refuses a time
without one, and clearing the date clears the time. A repeating task keeps its
time as it moves on to its next date, and resetting task properties leaves due
times, since, like the priority, the time is the task's own.

In the vault the time follows a task's property values: a flag, then the
second of the day. `LegacyVaultTest` opens a schema 22 vault written by
`31c3fe1`. The portable export is format 12, with `"dueTime"` as `"HH:mm"` on
a task that has one; a file without it reads with no times.

Quick add (`application.QuickAdd`) is where most times come from. It reads a
typed line into a title, due date and time, tags, a list, a priority and a
repeat, and its rules are written at the top of the class: a bare weekday is
the next one after today, "at 1" to "at 7" mean the afternoon, the first date
and the first time win, and nothing is taken if it would leave no title.
