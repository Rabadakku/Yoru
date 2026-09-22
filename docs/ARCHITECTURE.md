# Yoru architecture · decision record 003

Local Java 22+ desktop modular monolith. No server, account or shared database —
distribution scales by each installation owning its own vault, not by hosting
anything.

**Runtime dependencies are allowed** (decided by the owner, 2026-09-10, superseding
the "Swing and the JDK only" rule this record used to open with). The reason it
went is concrete: [PRODUCT-GOALS.md](PRODUCT-GOALS.md) requires the original
game to run *inside* Yoru, and an emulator core is a native library. The old
rule would have forced either a worse product or a dishonest reading of it, and
between the two the rule is what should give.

What the change does *not* license:

- **The study workspace still needs nothing.** Tracking, tasks, scheduling,
  habits, analytics and vaults must keep working with no game files, no native
  core and no network. A missing dependency degrades a feature; it never stops
  the app from opening.
- **Personal files stay external.** ROMs, saves, artwork and keys remain out of
  Git and out of releases, exactly as before.
- **Every dependency is a decision with a reason.** Name it here, say what it
  buys, and prefer the JDK where the JDK is enough. "Allowed" is not "free".

The baseline moved from Java 21 to 22 because the Foreign Function & Memory API
(JEP 454) is final there, which is how Yoru talks to a native core without JNI
shims or a C toolchain in the build.

Storage decisions live in [DATA-MODEL.md](DATA-MODEL.md). Working alongside
other agents: [COLLABORATION.md](COLLABORATION.md).

---

## Module boundaries

```
ui  →  application  →  domain
              ↓
        persistence (implements the Repository port)
```

Dependencies point inward. `domain` knows nothing about Swing, files or the
clock.

| Package | Holds |
|---|---|
| `domain` | `Model`: immutable records and their validation. `Model.State` is the whole world. |
| `application` | `Tracker`, the only mutation boundary. `Analytics` for derived views, `Encounters` for the reward economy, `GameSync` for rewards into the save, and the `Repository` port. |
| `persistence` | `EncryptedVault` (AES-256-GCM, backups and their retention), `VaultStore` (the vault folder, names and moves), `PortableVault` (JSON import and export), `LocalAccess` (password-free key), `LegacyCollection`. |
| `game` | The save format (`Gen3Save`, `Gen3Pokemon`, `Gen3Text`), edits and delivery (`StorageEdit`, `GameDelivery`, `StudyGift`, `LegacyGifts`), encounters (`WildEncounters`, `StudyEncounter`, `Progression`), and the emulator (`LibretroCore`, `GameSession`, `EmulationLoop`, `SaveTransfer`, `SessionHandle`, `Rom`). |
| `assets` | `ArtworkLibrary`, the library's front: what it holds, what is complete, the last failed import. `ArtworkStaging` reads an import into a stage under one budget; `ArtworkGenerations` publishes it and keeps two generations. `EmeraldArtwork` decodes sprites from the player's own game. `MusicLibrary`. |
| `importer` | `NotionImport`. |
| `update` | `ReleaseFeed`, `Download`, `Updates`, `MacInstall`, `Version`. |
| `json` | `Json`, the bounded codec. |
| `ai`, `plugins` | Kept for later integrations; not reachable in the app. |
| `ui` | Swing. `YoruApp` is the window: navigation, the ticker and the vault. Pages reach it through `Shell`. |

### The three rules that hold it together

1. **`Tracker` persists before it publishes.** `commit` writes through the
   repository and only then replaces the in-memory `State`, so a failed write
   leaves the app exactly as it was. Nothing else may mutate state.
2. **Validation lives in domain constructors.** Illegal states are
   unrepresentable — overlapping sessions, a party member that was never caught,
   a task pointing at a deleted tag. The UI catches input earlier and more
   legibly; it is never the only guard.
3. **Store raw, derive rules — with one deliberate exception.** Recorded facts
   are immutable history. Product
   rules are read-time functions: the five-minute floor is
   `Analytics.counts`, heat tiers are `Analytics.heat(seconds, goal)`. Baking
   either into storage would turn a preference change into a migration.

   The exception is the minimum-session floor at clock-out: `Tracker.stop`
   discards a session under the minimum rather than storing one that counts for
   nothing anywhere. That is a write-time rule and it is deliberate — a row that
   disagrees with every number beside it was worse than not having the row. The
   floor remains a read-time rule everywhere else, because an older vault or a
   JSON import can still contain short sessions, and `log`/`editSession` refuse
   rather than discard so no deliberate entry is ever silently dropped.

---

## Time

UTC instants everywhere, half-open intervals `[start, end)`.

- One active session per vault. Closing the app leaves it running; reopening
  recovers it.
- Analytics splits sessions at **local** midnight, including across DST, so a
  23-hour day totals 23 hours.
- Habits store a fixed IANA zone per tracker, so a streak does not shift when
  travelling.
- The schedule grid stores planned blocks as instants but positions them by
  local time. `RecurringBlock` stores `LocalTime` + day-of-week precisely so a
  09:00 class stays at 09:00 across a DST boundary, and `Analytics.occurrences`
  expands it onto whichever week is on screen. On the day a zone skips an hour,
  a start inside the gap moves forward by the length of the gap; that block is
  genuinely shorter that week, because the hour did not exist.
- Converting a local time refuses DST gaps and ambiguities rather than guessing
  (`DateTimeField.toInstant`).

---

## UI structure

`YoruApp` owns the window shell, the nav bar, the open vault and a single 70 ms
ticker that drives the clock and the animated scenes. Pages are rebuilt
wholesale on navigation — cheap at this data size, and it removes a whole class
of stale-view bugs — and a rebuild of the page on screen keeps its scroll.

Pages are their own classes and reach the window only through `Shell`:
`TodayPage`, `TasksPanel`, `HabitsPanel`, `CollectionPage`, `GamePage` and
`SettingsPage`. The Schedule and Data pages are still built in `YoruApp`. What
several pages share, and what needs the open vault, stays in the window behind
`Shell`: the time editors, a new activity, applying settings, artwork imports,
the vault's controls and quitting for an update.

| Component | Role |
|---|---|
| `Theme` | The palette, as mutable statics so `apply()` can swap every theme without touching call sites. Also the shared widgets and the type and spacing scale. |
| `Dialogs` | Every dialog. Suppresses stock Java icons and keeps wording consistent. |
| `DateText`, `DateField`, `DateTimeField` | Dates and times typed in a forgiving form or picked from `CalendarPanel`; unreadable text is refused, never saved. |
| `GameController` | The game from Play to Close: the save queue, acknowledgement and retry, and the session's `EncounterTables`. |
| `StorageScreen`, `PartyStrip`, `Arrangement` | The Collection's box grid, party row and the move shared between them. |
| `ScheduleGrid` | The week: hour rules, recorded sessions, planned blocks, drag to create/move/resize. |
| `TrainerScene`, `BuddyCard` | The trainer walking beside the timer, and the partner card beside it. |
| `SpriteAssets` | Bounded, cached sprite decoding. |

### Swing constraints, learned the hard way

- Aqua ignores nearly every `UIManager` colour key and all `setBackground` on
  buttons. The app forces the **cross-platform LAF**; reverting that silently
  breaks every theme.
- Metal's combo, scrollbar and button delegates paint their own chrome. The
  **Basic** delegates are installed instead.
- Disabled controls read from separate keys and default to near-white.
- A theme swap only reaches components built afterwards, so changing theme
  **rebuilds the window** rather than repainting it.

---

## Rendering for review

UI changes are reviewed as images, not diffs. `ui/Preview` renders every page
headlessly at any size and theme; `ui/DialogPreview` renders the dialogs, which
the page preview cannot reach. Both contrast regressions in this project were
invisible in the diff and obvious in the PNG.

---

## Artwork

The repository contains **no** game assets. `assets/ArtworkLibrary` imports a
user-supplied folder or zip into `~/.yoru/art`, normalising names; `SpriteAssets`
resolves in order: the `yoru.art.dir` override, the imported library, then the
classpath. Missing artwork degrades to dex numbers rather than failing.

---

## Scaling path

Keep the domain independent of Swing and of file storage. Before very large
histories, move serialized saves off the UI thread and consider an embedded
transactional database behind the existing `Repository` port. Before any sync,
design the conflict model, key recovery, deletion and ownership first — a local
file is not a multi-user backend, and quietly adding a server would break the
central promise of the app.

## Markdown workspace

`application.Pages` owns page/folder/task-link mutations. `pages.Markdown`,
`Links` and `PageIndex` provide source offsets, link resolution and incremental
indexing. The current parser is in-tree and dependency-free; it is not a claim
of full Obsidian compatibility. `MarkdownDirectory` handles bounded UTF-8
interchange and stages exports before moving them into a new directory.

`ui.PagesPage` coordinates the explorer, persistent-in-session editor tabs,
reading view and details. Editors flush before navigation, mutations, closing
or vault changes. Failed saves retain dirty text. The workspace and its timers
are cleared on vault switches. All changes still use the existing whole-vault
save service; large-workspace background indexing/storage remains future work.
