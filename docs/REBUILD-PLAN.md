# Rebuilding Yoru in TypeScript: the plan

Status: **proposal**. Nothing is built until the owner has answered §3.
Written 2026-09-25 against Yoru 1.1.0.

This is the founding document for the rebuild. Once approved, it moves into the
new repository as its `docs/ARCHITECTURE.md`, and Yoru 1.x takes bug fixes only.

---

## 1. The verdict

**Rebuild it. The reason is Swing, not Java.** Claude writes Java well, so the
language alone doesn't justify a rewrite. TypeScript follows from choosing a
toolkit that can do what the owner wants.

Why the rebuild is worth it:

- **Swing can't reach the goal Yoru is judged by.** The goal is "like an app
  Apple would ship", with smooth, short animations (#63). Swing works against
  that. Aqua ignores Yoru's colours, so the app has to run Metal. Metal binds
  Ctrl instead of Cmd, so copy and paste broke (#49). Swing has no animation
  system, and changing the theme rebuilds the whole window. About 4,200 lines
  exist only to work around the toolkit (§2).
- **Everything left on the roadmap is interface work.** That means saved views,
  board and timeline layouts, a task side panel, dragging tasks onto the week,
  live-preview Markdown and a dashboard (#65). A web stack has a mature library
  for each of these. In Swing, each one is a component built from scratch.
- **Claude can check its own work.** Claude Code sessions run in Linux
  containers. A web interface can run there under Playwright and be
  screenshotted, so a session can see what it built. Yoru 1.x had to write its
  own renderer (`ui/Preview`) to approximate this. A SwiftUI app couldn't even
  be compiled there.
- **The storage format is fragile.** The vault is a positional binary format
  with 23 schema readers. Two branches that each add a field produce a vault
  that decodes into garbage instead of failing with an error
  (COLLABORATION.md §3).
- **Now is the cheapest time.** There is one user, the git history covers about
  two weeks, and a complete JSON export already exists to carry the data across
  (§5.3).

What the current code gets right, and the rebuild keeps as ideas rather than as
code:

- One place mutates state, and it persists before it publishes.
- Validation lives in the domain.
- Raw facts are stored and rules are derived from them.
- Time is stored as UTC instants, except where the meaning is local: a habit's
  zone, and "every Monday at nine".
- DST gaps are refused, not guessed.
- The MCP bridge: owner-only socket, per-launch token, two switches and no
  delete tools.
- Every record can be created, viewed, edited and deleted from the interface.
- Backups are taken before anything destructive.
- No personal data goes in the repository.

## 2. Is 31,000 lines too many?

For what Yoru does, the total isn't outrageous. It covers a timer, a week
planner, a tasks database, natural-language quick add, habits, a Markdown
workspace, encryption, Anki, Notion import, an MCP server and an updater. The
real problem is how many of those lines were spent fighting the tools.

| Where the 31,648 lines of `src/main` go | Lines |
|---|---|
| Comments / blank lines / imports | 5,688 / 2,359 / 997 |
| Code | 22,604 |
| — of which Swing UI | 14,259 |
| — of which make Swing behave: theme and widgets, text shortcuts, layouts, date pickers, dialogs, drawn icons | ≈ 4,200 |
| — of which the editor, a separate reading view and a styler (CodeMirror does all three) | ≈ 1,500 |
| — of which hand-rolled infrastructure: the binary vault and its 23 schemas, a JSON codec, a Markdown parser | ≈ 1,600 |

There are also leftovers from the removed game:

- the ROM-extraction scripts in `tools/`;
- the `ROUTE_*` colours in `Theme`;
- the readers that skip old game collections.

**Target for the rebuild:** about half the size at feature parity, roughly
12,000–18,000 lines of our own code. This is an estimate. The savings come from
libraries doing the toolkit work, not from cutting features.

## 3. Decisions the owner makes first

Each decision has a recommendation. None of them is Claude's to make.

1. **Stack: Electron + React + TypeScript** (§4). The alternatives were
   considered and set aside:
   - **Tauri:** smaller, but the backend is Rust, and on a Mac it renders in
     WebKit, not the Chromium that Claude tests in.
   - **SwiftUI:** the most native option, but Mac-only, and it can't be built
     or run in a cloud session.

   Notion, Obsidian, Linear and Claude Desktop are all Electron apps.
2. **Replace "nothing is bundled" with a dependency policy.** No
   JavaScript stack can meet the old rule. Proposed policy:
   - permissive licences only (MIT, Apache-2.0, BSD, ISC);
   - every dependency named in `docs/DEPENDENCIES.md` with its reason;
   - a committed lockfile and a generated notice file;
   - still no bundled fonts, images or audio. Type comes from the system, and
     sounds are synthesised as they are today.
3. **Name.** Decide before scaffolding, because the name is built into the
   bundle id, the vault file extension and the MCP server's name. Keeping
   "Yoru" is a fine answer.
4. **Themes.** Apple's model is Light, Dark or System, plus an accent colour
   that follows the system (recommended). The alternative is to keep the five
   named themes. They could come back later as tints.
5. **Platforms.** Recommended: design for the Mac first, and keep Windows and
   Linux builds that work but get less polish. Electron makes those two
   builds nearly free.
6. **Apple Developer Program (a yearly fee).** Signing and notarisation remove
   the Control-click → Open step on first launch, and they're required for
   Electron's standard auto-update on macOS. Without them, updates work the
   way 1.x's do: download, verify the SHA-256, replace the app.
7. **AI inside the editor (#111).** One option is the owner's own Claude API
   key, paid per use, which is the only way Yoru can call Claude itself. The
   other is to wait until Claude's apps support MCP sampling. The rest of the
   plan works either way.
8. **What waits until after parity.** These can come across later:
   - Notion import (a one-time migration that's already done);
   - the paste-an-AI-reply import (Claude now adds tasks directly);
   - study music.

   LeetCode (#41) and meal planning (#60) were never built, so they stay on
   the roadmap after parity.

## 4. Stack

| Layer | Choice | Why |
|---|---|---|
| Shell | Electron, packaged with electron-builder | Native menus, tray, notifications, keychain and window materials on all three platforms, from one codebase |
| Language | TypeScript, `strict` everywhere | Types across main, renderer and MCP. Strong in AI-assisted work |
| UI | React + Vite | The largest ecosystem, and the one Claude knows best |
| Styling | Tailwind CSS v4 over CSS-variable tokens | Tokens live in one place. Lint bans arbitrary values, so pages can't invent sizes |
| Behaviour | Radix UI primitives | Accessible menus, popovers, dialogs and toggles with no imposed look |
| Motion | Motion (formerly Framer Motion) | Springs, layout and exit animations. Respects reduced motion |
| Editor | CodeMirror 6 + Lezer Markdown | The editor Obsidian is built on. Live preview replaces the separate reading view |
| Palette, drag, long lists | cmdk, dnd-kit, TanStack Virtual | The command palette, reordering and time-blocking, and large lists |
| Data fetching | TanStack Query | Caching, plus invalidation when the core reports a change |
| Storage | SQLite with SQLCipher (`better-sqlite3-multiple-ciphers`), FTS5 | An encrypted, transactional store with full-text search built in |
| Validation | Zod | One schema per command. It validates input from the UI and from Claude, and generates the MCP tool schemas |
| Time | Temporal (polyfill until Electron's runtime ships it) | Maps one-to-one onto the `java.time` concepts the current rules use |
| Icons | Lucide (ISC) | Line icons close to SF Symbols. SF Symbols can't be bundled |
| MCP | The official TypeScript SDK | The reference implementation of the protocol |
| Tests | Vitest, Playwright, fast-check, axe-core | Unit, end-to-end with screenshots, property-based time maths, accessibility |
| Rules as code | ESLint (typescript-eslint strict), Prettier, dependency-cruiser | The layering in §5.1 is enforced, not described |

## 5. Architecture

```
┌──────────────────────── Yoru.app ────────────────────────┐
│ main process (Node)                                      │
│   windows · native menus · tray · notifications · update │
│   core: commands · queries · SQLite vault · change log   │
│   bridge: owner-only socket for assistants               │
│            ▲ typed IPC (contextBridge, sandboxed)        │
│ renderer (Chromium): React UI                            │
└──────────────────────────────────────────────────────────┘
             ▲ Unix socket / named pipe + per-launch token
 yoru-mcp (stdio)  ◀── Claude Desktop · Claude Code
```

### 5.1 Packages and the rules between them

```
packages/core      domain, commands, queries, storage. No Electron, DOM or React.
packages/ui        React pages and components. Reaches data only through a Client.
packages/mcp       the stdio MCP server. Its tools come from core's command registry.
apps/desktop       Electron main and preload: wires core, ui, bridge and menus.
apps/harness       development only. Runs core in Node and ui in a browser over a
                   WebSocket, seeded with invented data, for Playwright.
```

`ui` never imports storage, and `core` never imports `ui`, Electron or React.
dependency-cruiser fails the build when either rule breaks. The renderer's
`Client` has two transports: IPC in the app and a WebSocket in the harness.
Everything above the transport runs the same code. So a cloud session can
drive the whole interface in the preinstalled Chromium and screenshot it.

### 5.2 The core

1. **Every change is a command.** A command is declared once, with:
   - a name;
   - a Zod input schema;
   - a handler that runs inside one transaction;
   - a one-line summary of what it did;
   - whether assistants may read, change or never touch it.

   Everything calls the same commands: the UI, the command palette, keyboard
   shortcuts, the menu bar, importers and Claude. This replaces `Tracker`'s 75
   public methods and `WorkspaceTools`' second copy of the tool definitions.
2. **Persist before publish** (kept). A command commits its transaction and only
   then tells the UI what changed. If it fails, nothing changed.
3. **Validate in the domain** (kept). Schemas check shape, and invariants run
   inside the transaction: no overlapping sessions, no task pointing at a
   deleted tag.
4. **Store raw, derive rules** (kept). Totals, streaks, heat tiers and the
   minimum-session floor are queries and pure functions, never stored columns.
5. **Undo works the same way for every command (new).** Storage records a
   before and after image of each changed row into `change_log`, tagged with
   where the change came from: `ui`, `assistant` or `import`. Undo and redo
   replay those images. Settings → Activity lists what assistants changed,
   and each change can be undone on its own.

Reads are named, typed queries, such as `today.overview` or `tasks.inView`. After a
commit the core reports which tables changed, and the renderer invalidates the
queries that read them.

### 5.3 Storage

- **One file per vault.** It's a SQLite database encrypted by SQLCipher, and
  the password is optional. A password-free vault keeps its key in the OS
  keychain through Electron's `safeStorage`, not in a file beside the vault.
  This fixes the weakness SECURITY.md admits today.
- **Migrations are numbered SQL files, forward-only.** Each one is tested
  against a vault from the release before. Adding a column can't corrupt a
  read.
- **Backups** use SQLite's online backup, to an encrypted copy. One is taken
  before anything destructive or bulk, and one a day. Old copies are pruned as
  they are today.
- **Exports:** the whole vault as versioned JSON, pages as a Markdown folder
  with Obsidian-compatible names, and sessions as CSV.
- **Manual order** uses fractional index keys. A reorder rewrites one row, so
  tasks can be reordered inside a filtered view. Today's integer order can't
  allow that.
- **Ids are UUIDv7**, ordered by time. **Time:** facts are UTC instants.
  Local meanings are stored as a plain date or time plus an IANA zone.

**Coming from Yoru 1.x.** The new app reads one thing: 1.x's JSON export,
format 12. It never opens the binary vault, so none of the 23 schema readers
come across. The import finishes with a check that has to pass:

- tracked time per activity per day;
- the number of tasks, habits, check-ins and pages.

Both must match the export exactly. The old vault is never touched.

**Tables**, grouped by area:

| Area | Tables |
|---|---|
| Time | `activities`, `sessions`, `settings` |
| Week | `blocks` (one-off), `weekly_blocks`, `weekly_block_changes` (one week skipped or moved) |
| Tasks | `tasks` (with `parent_id`, ready for sub-tasks #70), `lists`, `tags`, `task_tags`, `statuses`, `task_history` (repeats) |
| Properties and views | `properties`, `property_options`, `property_values`, `views`. Tasks use this engine first. Collections (#79) reuse it later instead of getting a second one |
| Habits | `habits`, `habit_checkins`, `habit_restarts` |
| Pages | `folders`, `pages` (Markdown body), `task_pages`, plus derived `page_links` and `pages_fts` |
| Integrations | `anki_days`, `anki_state` |
| System | `change_log`, `meta` |

### 5.4 The interface

- **Layout:** a translucent sidebar, the content, and an inspector on the right
  that opens on demand. The toolbar sits in the title bar with the traffic
  lights inset.
- **The same seven places:** Today, Tasks, Schedule, Habits, Pages, Data,
  Settings.
- **Keyboard:** every action is a command id. Shortcuts, the palette (Cmd-K)
  and the native menu bar all point at the same ids.
- **Text editing is native.** The Edit menu uses Electron's standard roles, and
  context menus are the system's own (`Menu.popup`). Cmd-C/V/X/Z, spell check
  and Mac text navigation work in every field with no code of ours. The #49
  class of bug goes away.

### 5.5 Claude

1. **The MCP server keeps 1.x's design.** Claude's app starts `yoru-mcp`, which
   forwards each call over the bridge to the running, unlocked app. The design
   carries no port, no network code and no key. It's off by default, with one
   switch for reading and a second for changing. There are no delete tools.
   All 21 current tools come across, generated from the commands marked for
   assistants.
2. **New: MCP prompts,** such as "Plan my week", "Weekly review" and "Turn this
   syllabus into tasks". Claude's apps list them as slash commands. Today's
   overview is offered as a resource.
3. **New: every assistant change is undoable** from Settings → Activity (§5.2).
4. **Installing it:**
   - Claude Desktop: an MCP Bundle (`.mcpb`), which installs in one click and
     runs on Claude Desktop's own Node.
   - Claude Code: a `claude mcp add` command to copy.

   Check both against the current Claude apps at the start of M7, before
   building.
5. **In-editor AI, if the owner chooses an API key (#111).**
   - The Anthropic TypeScript SDK runs in the main process only. The key lives
     in the keychain and never reaches the renderer.
   - The feature is off by default and results stream in.
   - Results are suggestions: Replace, Insert below, Try again or Discard.
     Accepting one is a single undo step.
   - The model is a setting. Its default is the current recommended model,
     looked up with the `claude-api` skill when this is built.
   - A monthly budget comes from the usage the API reports.

### 5.6 Electron security

- `contextIsolation` on, the renderer sandboxed, and no `nodeIntegration`.
- A strict Content Security Policy and no remote content.
- `window.open` and navigation are denied.
- Every IPC message is checked for its sender and validated with Zod.
- External links open through `shell.openExternal`, for `http`, `https` and
  `mailto` only.
- Markdown is never rendered as raw HTML.
- Electron fuses are set at build time. `RunAsNode` stays on only if the MCP
  launcher needs it, which is decided in M7.

## 6. Design system: "Apple would ship it"

- **Type:** the system font (`system-ui`), which is SF Pro on a Mac and Segoe
  UI on Windows. Nothing is bundled. The scale follows the macOS guidelines:
  13 px body in the interface, larger reading text in Pages. The text-size
  setting (100–200 %) scales every `rem`.
- **Colour:** semantic tokens that mirror macOS roles (label, secondary label,
  separator, window and control backgrounds, accent), defined for light and
  dark. The accent follows `systemPreferences.getAccentColor()` unless the
  owner overrides it.
- **Materials:** sidebar vibrancy and a `hiddenInset` title bar on macOS, and
  Mica on Windows 11.
- **Space and shape:** a 4-point grid, with a few corner radii and hairline
  separators instead of boxed cards.
- **Motion:**
  - Animation only explains where something came from or went. Nothing
    animates for decoration.
  - Movement uses springs, with a 150–300 ms feel.
  - Every animation is named in one file: `sheet`, `popover`, `disclosure`,
    `reorder`, `check`, `pageSwitch`.
  - With reduced motion on, movement becomes a fade.
- **References, for ideas only:** Apple's Notes, Reminders and Calendar;
  Things for tasks; Notion for databases; Obsidian for pages; Linear for
  keyboard speed. No names, assets or copy are taken from any of them.
- **Review:** every screen is screenshotted in light and dark, at 100 % and
  200 % text. axe-core reports no violations. The owner approves mock-ups of
  Today, Tasks and Habits, running in the harness, before the rest is built
  (#63's process).

## 7. Built to work with Claude

- **Rules live in tools, not prose.** `npm run check` runs types, lint,
  layering, the privacy scan, unit tests and end-to-end tests. It replaces
  `./test.sh`. CLAUDE.md stays short: commands, the package map and the rules
  in §1 of AGENTS.md.
- **Cloud sessions start ready.** A SessionStart hook installs dependencies.
  `npm run harness` shows the whole app in a browser with invented data.
- **Skills for repeated work:** add a command, add a migration, review a
  screen from screenshots.
- **A docs budget.** ARCHITECTURE.md stays under about 200 lines, and each
  decision gets a short record. Handoffs go on issues and PRs, not in `docs/`.
- **Port the test cases, not the tests.** These hold hard-won edge cases and
  come across as inputs and expected outputs:
  - `QuickAddTest` (86 phrases);
  - time and DST: `AnkiTimeTest`, `TimeSinceTest`, `RecurringTest`, `RepeatWeekTest`;
  - `RepeatTest`, `HabitStatsTest`, `AnkiStreakTest`;
  - `LinksTest`, `MarkdownTest`;
  - the refusals in `WorkspaceToolsTest`;
  - `PortableVaultTest`, which becomes the 1.x import test.

## 8. Milestones

Each milestone ends usable, reviewed and green. Nothing new gets built before
the cut-over in M8, except the design work that is the point of the rebuild.

| | Milestone | Done when |
|---|---|---|
| M0 | Foundations | New repo, workspace, CI, harness, tokens and about ten core components. Today, Tasks and Habits mock-ups are approved by the owner |
| M1 | Vault | SQLCipher store, migrations, commands, queries, change log, undo, backups, lock screen. The 1.x import passes its totals check. Installers build on all three platforms from here on |
| M2 | Time | Timer and pomodoro, logging and correcting sessions, activities, the Today glance, Data (heat map, charts) and CSV |
| M3 | Tasks | Lists, tags, statuses, priorities, properties, repeats, quick add and the palette, table and month calendar, bulk actions and reordering |
| M4 | Week | Schedule grid with drag to create, move and resize. Weekly repeats and one-week changes. Today's schedule |
| M5 | Habits | Daily and time-since habits, history corrections, zones, the Anki streak |
| M6 | Pages | Live-preview editor, explorer, tabs, links, backlinks, outline, full-text search, quick switcher, trash, Markdown import and export, task links |
| M7 | Claude and Anki | Bridge, MCP server, tools, prompts, switches, Activity, the Desktop bundle and Code setup, AnkiConnect |
| M8 | Cut-over | Signed installers if chosen, updates, docs. The owner uses it alongside 1.x for a week, then 1.x freezes |

After M8, the #65 order resumes. Saved views (#69) come first, because the
property-and-view engine already exists. Sub-tasks (#70), the side panel
(#71), time-blocking (#83), reminders (#76) and the menu bar extra (#77)
follow.

## 9. Risks

| Risk | Mitigation |
|---|---|
| The rebuild stalls at 70 % and leaves two half-apps | 1.x stays the daily app until M8. No new features before the cut-over. Reassess honestly after M3 |
| Data is lost in the move | The import reads a copy of the export and never the vault. The totals check must pass. 1.x stays installed for a month |
| Electron's weight | A larger download and more memory than 1.x. The same trade Notion, Obsidian and Claude Desktop make |
| The native SQLite module fails on one platform | CI builds all three installers from M1, not M8. Confirm FTS5 in the SQLCipher build in M1's first spike |
| An unsigned Mac app | Gatekeeper's first-launch step and a custom update flow. See decision 6 |

## 10. What does not come across

- The game's leftovers: `tools/extract-*.py`, the `ROUTE_*` colours and the
  readers for old game collections.
- The binary vault and its 23 schema readers (§5.3).
- Swing workarounds: the forced look and feel, key-binding rewrites, text
  fitting and the preview renderer.
- The separate reading view, which live preview replaces.
- Anything deferred under decision 8, until the owner says otherwise.
- Handoff logs and history documents. Git and the issues keep that record.

## 11. Next steps once approved

1. The owner answers §3.
2. Create the new repository. Carry over the privacy rules in AGENTS.md, the
   licence, and this plan as `docs/ARCHITECTURE.md`.
3. Start M0.
4. Yoru 1.x takes bug fixes only. Its README points to the new app once M8 ships.
