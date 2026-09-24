# Yoru roadmap

What is being worked on, and what landed recently, newest first. The order of
the remaining work is issue [#65](https://github.com/Rabadakku/Yoru/issues/65);
what each feature does is in [FEATURES.md](FEATURES.md). Older entries, from
1.0.21 back to the first release, are in
[history/roadmap-to-1.0.21.md](history/roadmap-to-1.0.21.md).

## Next

1. **Tasks as a database, continued:** sub-tasks and checklists (#70), saved
   views (#69), the task side panel (#71), projects (#72), templates (#73).
2. **Planning:** drag tasks onto the week (#83), other calendars (#84),
   reminders (#76), the menu bar extra (#77).
3. **Seeing it all:** a dashboard (#75), reviews (#80), goals (#81), more kinds
   of habit (#82).
4. **The rest of the spring clean (#45):** split `Theme`, `YoruApp` and
   `TasksPanel` and `Tracker` into smaller classes, per-schema vault readers,
   and the shared test kit.
5. **Waiting on the owner:** the timer concept (#62), the logo (#64), sign-off
   on the redesign (#63) and a native-keyboard check on a real Mac (#49).

## AI assistants: Yoru as a tool for Claude (#47)

On `claude/wizardly-mayer-7jnqwb`. The owner wanted Claude in Yoru without an
API key, and Anthropic does not let third-party apps sign in with claude.ai.
So Claude's own apps call Yoru instead: Yoru is a Model Context Protocol
server that Claude Desktop, Claude Code or any other MCP client starts with
`Yoru --mcp`. That process opens no window or vault; it forwards each tool call
over a Unix-domain socket in an owner-only folder, with a per-launch token, to
the running and unlocked app. No port, no network code, no key.

- **Tools** (`ai.WorkspaceTools`): nine that read (overview, tasks, one task,
  page search, page list, one page, schedule, time totals, habits) and twelve
  that change (add a task, quick-add a line, edit a task, create a page, add to
  a page, replace a passage, link a task to a page, plan a block, record time,
  start and stop the timer, check off a habit). None deletes.
- **Switches** (`ui.Assistants`, per computer): reading off by default; changing
  a second switch. The first change in fifteen minutes backs the vault up; the
  page editor saves first and the page on screen redraws after; Settings lists
  what assistants changed. **Add Yoru to Claude Desktop** merges Yoru into
  Claude Desktop's settings file, keeping everything else and a copy of the
  original; Claude Code gets a command to copy.
- **Supersedes** the API-key plan in #47's first draft (providers, keys,
  budgets): the assistant's own app runs and pays for the model.
- Not done: AI inside the page editor (select text → rewrite) needs a model Yoru
  can call itself, so it waits for a decision on API keys or for MCP sampling
  in Claude's apps.

Validation: `McpTest` (68: handshake, versions, JSON-RPC errors),
`WorkspaceToolsTest` (433: every tool, refusals that name the choices, the
changes switch, hostile page text, a failed save), `BridgeTest` (24: owner-only
socket and token, a wrong token refused, a second window refused, a stale
socket replaced, end to end from standard input), `ClaudeSetupTest` (27) and
`AssistantsUiTest` (36: both switches off by default, a change drawn at once,
every theme). `test.sh` also runs `--mcp` from the named module, as the
installers do. The Settings card was reviewed at 100% and 200% text.

## Merged — repeat weeks, tasks as a database, quick add (#59, #68, #74; PR #109)

On `claude/outstanding-tickets-97unts`, working down the open tickets in the
order #65 sets. Everything below passed the full isolated suite, together with
PR #108, as a non-root user.

- **#59, one week of a weekly repeat (schema 21).** A weekly block can be
  skipped, moved or changed for one week and restored later, from the week
  grid or its card. `CrudCoverageTest` now runs in `./test.sh`: a table of
  every record the owner keeps, with the controls that create, view, edit and
  delete it, failing when a control goes missing. With #108's activity and
  tag order, every record in #59 can now be created, viewed, edited, deleted
  and reordered from the interface.
- **#68, tasks as a database (schema 22).** Priorities, statuses of the
  owner's own inside the three groups, and properties of every type, managed
  from Properties on the Tasks page. The table folds the extra columns under
  the title rather than scrolling sideways, which `TextFitTest` forbids.
  Notion imports map their columns to properties.
- **#74, quick add (schema 23).** One line makes a whole task:
  "Essay draft tomorrow 5pm #school !high every monday /classes". The parts
  it recognises are highlighted and listed under the line, and each can be
  kept as plain text. It works from the + New task row and from a new command
  palette (Cmd/Ctrl-K), which also goes to any page. Tasks can now be due at a
  time of day. A setting turns the parsing off. `QuickAddTest` checks 86
  phrases against a fixed date, and the rules for ambiguous ones are written
  in `application.QuickAdd`.

Next, in this order:

1. **Claude integration.** Claude integration is wanted without an API key.
   Anthropic does not allow third-party apps to offer claude.ai sign-in, so
   Yoru will not call Claude. Instead Claude's own apps call Yoru: Yoru becomes
   a local MCP server that Claude Desktop or Claude Code starts. It reaches the
   running, unlocked app over a connection only the same user account can
   open. It is off by default in Settings → Integrations, reads first, and
   makes changes only behind a second switch, through `Tracker` like any other
   edit. No network code in Yoru. Supersedes the API-key plan in #47.
2. The rest of #65: #70, #69, #71, #72, #73, then #83, #84, #76, #77, #75,
   #80, #81, #82, #79, #78, #60, #41, #47, #46 and #45.
3. An audit of the closed tickets against what shipped, allowing for the
   directions that changed since (the game removed in #58, Apple Health
   cancelled in #40, the decorative portrait withdrawn).

#49, #62, #63 and #64 wait on the owner and stay open.

## Merged — activity and tag order (#59, PR #108)

On `claude/reorder-activities-tags`. Each activity row on Data and each tag in
the tag manager has ↑ and ↓ arrows, turned off at either end. `Tracker.moveActivity`
and `Tracker.moveTag` swap an item with its neighbour, back up first, and write
nothing at an end. Sessions, blocks and tasks refer to activities and tags by id,
so only the order changes. After a move, focus returns to the same arrow on the
item's new row (`ui.Reorder`), so an item can be moved several places from the
keyboard. No stored fields or schema changes: the lists already kept their order.

Validation: `ReorderTest` (23 checks: ends, refusals, failed saves and backups,
both vault formats), plus arrow checks in `ActivityUiTest` and `TaskBoardTest`.
The Data page was reviewed at 100% and 200% text, and the tag manager in
`DialogPreview`.

## Merged — time-since in calendar units (PR #107)

On `claude/time-since-units`, carried over from uncommitted work on
`codex/time-since-units` that never reached the remote. The owner asked for
the time-since counter in minutes, hours, days, months and years. It now reads
"1y 2mo 3d 4h 5m": years, months and days on the calendar in the habit's zone,
then real elapsed hours and minutes. Every unit below the largest is written
even when zero, so the counter changes width only when it gains a unit. The
history list uses the same format, and screen readers hear whole words
("1 year, 2 months, …"). No stored fields or schema changes.

The longer counter did not fit. At 150% text the counter, Start again and ⋯
were wider than the Habits column, so moving them under the name still cut
off ⋯. The row's end now puts the counter on its own line and the controls
under it (`HabitsPanel.CounterEnd`). `EndOrUnder` treats that as a third
arrangement, so the row is measured again when it switches. Stacked `Columns`
now update each card's height at every layout instead of fixing it when they
stack, which had cut off the grown row at 125% in the smallest window.

Validation: `TimeSinceTest` covers leap years, month ends, both daylight-saving
changes, the live counter and history, spoken units, and narrow columns.
`TextFitTest` now also sets every time-since tracker to about eleven years
("10y 11mo 29d 23h 59m") and passes at every text size and minimum window.

## Merged — bulk session actions (#59, PR #106)

`codex/session-bulk-actions` adds multiple selection to recorded sessions on
Data, with Move to activity, Shift times (elapsed minutes) and confirmed Delete.
Single-session editing stays available for one row. Selection follows stable IDs
through sorting/filtering; Select shown and Escape help manage it.

The complete candidate timeline is validated before one backup/save. Running
sessions, future times, stale selections and overlaps refuse the entire action.
Failed saves retain selection for retry. Anki IDs and durations survive edits;
the delete confirmation explains possible reimport of recent Anki sittings.
No stored fields or schema changes. Other CRUD areas in #59 remain open.

Validation: full isolated suite passed in a clean checkout; 30 domain/persistence
checks and 22 UI checks cover sorting/filtering, failures, running timers, Anki
IDs and DST. Inspected controls in all five themes and both 100%/200% text sizes,
including move/shift dialogs. The preview inventory passed all 302 checks.

## Merged — habit order and daily time zone (#59, PR #105)

`codex/habit-controls` adds Move up/down to both kinds of habit and Time zone
to the daily habit menu. Each kind reorders only among its own neighbours; the
existing ordered list persists it. Changing zone preserves check-in dates and
the habit’s beginning, and only changes the local daily boundary. Both actions
validate before one backup/save, with no-ops at boundaries or an unchanged zone.
No schema change.

Validation: full isolated suite in a clean checkout, 19 domain/persistence
checks and 9 UI checks (including the final UTC label check). Changed order and
zone survive encrypted/portable roundtrips. The form was reviewed in all five
themes and at 200% text. The contributor rules now require complete CRUD controls
and coverage for new records.

## Merged — correcting habit history (#59, PR #104)

`codex/habit-history-fixes` opens the daily history beyond four weeks with paging and
a jump-to-date field. Old check-ins can be added or removed in place; corrections
are backed up before saving. Removing a check-in or renaming a habit preserves
its beginning, so consistency does not silently discard missed days. Creation
uses the tracker clock in the habit’s zone.

Time-since history has Add missed restart, inserting a minute-aligned boundary
without moving existing starts. Duplicate minutes and future starts are refused
before backup/save. No stored fields or schema changes. Reordering and the other
CRUD areas in #59 remain open.

Validation: the complete isolated suite passed in a clean checkout, including
30 history/persistence checks and 15 UI checks. All five themes and 200% text
were reviewed; the history remains scrollable within a bounded dialog. Existing
legacy vault fixtures and encrypted/portable roundtrips pass.

## Merged — automatic Anki streak (#100, PR #103)

`codex/anki-streak` adds a read-only Anki streak to Habits without creating a
manual habit. Any date with reviews counts, independent of imported timed
sessions. Daily aggregate history is retained beyond the chart’s month, so
longer runs survive offline and encrypted/portable roundtrips. Offline streaks
are explicitly dated as of the last sync; switching profiles replaces history.
Existing short caches fill in at the next successful refresh. No schema change.

Validation: full isolated suite passed in a clean checkout, including 15
streak/persistence and 10 UI checks. Automatic refresh retains 100 review days;
400-day summaries survive encrypted and portable roundtrips. Reviewed all five
themes and enlarged text. No live personal Anki profile was opened.

## Merged — bulk task actions (#59, PR #101)

`codex/bulk-task-actions` adds selection to the task table for status, list,
due date, planned day, tags and deletion. Each operation validates the whole
selection before one backup and one save. Repeating tasks use the same
completion rules as individual edits. Hidden tasks leave the selection; Escape
cancels it. No vault format changes. The other CRUD work in #59 remains open.

Validation: 40 batch checks, 55 selection/form checks, full isolated suite,
five-theme desktop/minimum renders and enlarged-text layout checks.

## Cancelled — Apple Health integration (#40)

The owner requires laptop-only automatic syncing and has declined an iPhone
bridge. macOS apps cannot read HealthKit records directly, so this integration
and its automatic exercise streak are out of scope. Do not substitute a manual
export or require a companion app. Automatic Anki streaks remain in scope.

## Merged — text shortcuts and Inbox guidance (PR #99)

On `codex/task-details`, from 1.0.21. For #49, Pages now receives the shared
Command-Delete action without replacing its own undo history. Shared deletion,
undo and redo obey read-only and disabled fields; deletion respects a selection.
`TextInputTest` reproduces the missing Pages action before the fix and covers
all six component types with simulated Mac clipboard bindings. It now passes
95 checks. Inbox explains that it contains tasks without a list, both in the
selected view and through its tooltip and accessibility description.

Validation: full isolated suite and named-module check pass; Inbox renders
reviewed in all five themes at 100% and 200% text. No vault schema changes.
The real Mac keyboard/clipboard acceptance for #49 remains open: desktop input
automation did not deliver input to the synthetic Java window, so no native
shortcut pass is claimed. `DesktopTextInputCheck` supplies an opt-in synthetic
window for that remaining check; it never opens a vault.

## Rules that do not change

- No personal information in the repository, in releases, or in anything written on GitHub ([AGENTS.md](../AGENTS.md)); nothing third-party bundled.
- Read [DATA-MODEL.md](DATA-MODEL.md) before changing storage, and never reset data as an upgrade.
- `./test.sh` passes before anything is pushed.
