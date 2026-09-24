# Yoru architecture · decision record 003

Local Java 22+ desktop modular monolith. No server, account or shared database —
distribution scales by each installation owning its own vault, not by hosting
anything.

**Runtime dependencies are allowed** (decided by the owner, 2026-09-10), but
Yoru has none: it is Swing and the JDK, and every picture is drawn at runtime.
The one thing that ever needed a native library — the game — was removed in
full (#58).

What "allowed" does *not* license:

- **The app still needs nothing.** Tracking, tasks, scheduling, habits, pages,
  analytics and vaults must keep working with no network and nothing installed.
  A missing dependency degrades a feature; it never stops the app from opening.
- **Personal files stay external.** Vaults, imports and keys remain out of Git
  and out of releases.
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
| `application` | `Tracker`, the only mutation boundary. `Pages` for the notes tree, `Analytics` for derived views, `AnkiTime` for Anki sittings, and the `Repository` port. |
| `persistence` | `EncryptedVault` (AES-256-GCM, backups and their retention), `VaultStore` (the vault folder, names and moves), `PortableVault` (JSON import and export), `LocalAccess` (password-free key). |
| `pages` | `Markdown` (the parser, keeping exact offsets), `Links` (what a link means and how renames keep it), `PageIndex` (backlinks and search), `MarkdownDirectory` (import and export). |
| `assets` | `MusicLibrary`: study music imported from the owner's own audio files. |
| `importer` | `NotionImport`. |
| `update` | `ReleaseFeed`, `Download`, `Updates`, `MacInstall`, `Version`. |
| `json` | `Json`, the bounded codec. |
| `ai` | AI assistants over the Model Context Protocol (#47): `Mcp` (the protocol, no I/O), `WorkspaceTools` (the tools, run against `Tracker`), `Bridge` (the owner-only socket between `Yoru --mcp` and the running app), `McpMain` (the `--mcp` process) and `ClaudeSetup` (the command and Claude Desktop's settings). `OpenAiTasks` keeps the paste-a-reply task import. |
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
ticker that drives the clock. Pages are rebuilt
wholesale on navigation — cheap at this data size, and it removes a whole class
of stale-view bugs — and a rebuild of the page on screen keeps its scroll.

Pages are their own classes and reach the window only through `Shell`:
`TodayPage`, `TasksPanel`, `HabitsPanel`, `PagesPage` and `SettingsPage`. The
Schedule and Data pages are still built in `YoruApp`. What several pages share,
and what needs the open vault, stays in the window behind `Shell`: the time
editors, a new activity, applying settings, the vault's controls and quitting
for an update.

| Component | Role |
|---|---|
| `Theme` | The palette, as mutable statics so `apply()` can swap every theme without touching call sites. Also the shared widgets and the type and spacing scale. |
| `Dialogs` | Every dialog. Suppresses stock Java icons and keeps wording consistent. |
| `DateText`, `DateField`, `DateTimeField` | Dates and times typed in a forgiving form or picked from `CalendarPanel`; unreadable text is refused, never saved. |
| `PageEditor`, `PageReader`, `PageExplorer` | The Markdown editor, the reading view and the file tree, each reaching `PagesPage` through its own host interface. |
| `ScheduleGrid` | The week: hour rules, recorded sessions, planned blocks, drag to create/move/resize. |
| `TextInput` | The platform's copy, paste and undo on every text field, and the right-click menu. |

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

## Scaling path

Keep the domain independent of Swing and of file storage. Before very large
histories, move serialized saves off the UI thread and consider an embedded
transactional database behind the existing `Repository` port. Before any sync,
design the conflict model, key recovery, deletion and ownership first — a local
file is not a multi-user backend, and quietly adding a server would break the
central promise of the app.

## AI assistants

Yoru has no AI client. An AI app starts `Yoru --mcp` (the same launcher, which
checks its first argument before touching the window system) and talks JSON-RPC
on standard input and output. That process answers the handshake and the tool
list itself, and forwards each tool call over `Bridge` to the running app, one
connection per call, so it survives the app being opened, closed or relocked.

```
AI app ⇄ stdio ⇄ McpMain ─ Bridge (Unix socket + token) ─▶ ui.Assistants ─▶ WorkspaceTools ─▶ Tracker
```

`ui.Assistants` owns the switches, the bridge's lifetime (with the vault, not
the window, so a palette rebuild keeps it) and the host side of a change: tool
calls run on the event thread with `invokeAndWait`, the page editor flushes
first, a backup is taken before the first change in fifteen minutes, and the
page on screen is rebuilt after. `WorkspaceTools` has no Swing and no sockets,
so it is tested against an in-memory tracker; the tools only add and edit,
through the same `Tracker` methods as the interface.

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
