# Yoru roadmap

## Release 1.0.20 — habits at a glance, and open tickets closed out

On `claude/close-tickets`, from 1.0.19. Tickets whose work had reached main
without being closed were checked against their Done-when lists, and the gaps
filled: #52, #53, #54, #55, #67, #85 and #86. See [release details](RELEASE-1.0.20.md).

- **Habits (#52, #53):** each kind is one list of rows. Six daily habits and
  eight trackers fit side by side at 1280×900; at large text sizes a row's
  figures move under it rather than squeezing it. `Columns` now measures in the
  arrangement its width calls for, which also fixed stacked columns being drawn
  in the height of side-by-side ones.
- **Checklist (#54):** Up/Down/Space, each habit's day in its own zone from an
  injected clock, and `HabitChecklistTest`.
- **Task calendar (#67):** double-click a day for a task due that day; a click
  on a chip is no longer a drop on its own day.
- **Today (#86):** fits 1280×900 without scrolling, Anki's line on the title
  line; `VisualSystemTest` holds it.
- **Consistency (#55):** month-end and daylight-saving cases in `HabitStatsTest`,
  and an old vault's habit beginning on its first check-off.
- **Anki (#85):** failures carry a kind, and Settings explains each one.
  `AnkiSettingsTest` holds the explanations apart and looks for an invented key
  in preferences, printed output and the vault's bytes.
- **Text size in the drawn views:** the heat map, month calendar and week grid
  follow the text size, and the launcher is on the scale. This was PR #39,
  rebased onto main and carried here.
- Left open: #49's manual check with a real keyboard on a Mac (the input maps,
  undo and the menu are covered by `TextInputTest`). The README's screenshots
  are release assets, refreshed with each release. Clock times are still written two ways
  (24-hour in the schedule and agenda, 12-hour in fields) — the owner's call.

## Release 1.0.19 — interface detail polish (#63)

Quieter page tabs keep long-title close controls visible. Rounded scrollbars
and consistent button hover feedback refine the shared controls. Edit menus
reflect selection and actual undo/redo history, and sidebar selection is exposed
to accessibility tools. The README now reflects the released workspace and
Anki setup. See [release details](RELEASE-1.0.19.md). #63 remains open.

## Release 1.0.18 — minimal workspace foundation (#63)

A collapsible line-icon sidebar replaces the top tabs across the workspace.
System fonts, sentence-case headings, quieter surfaces and list separators
reduce visual noise. macOS uses its native menu bar and transparent title bar,
with optional system light/dark and accent following. The five vault palettes
remain available. Pages trades side panels at narrow widths to preserve room
for writing; Tasks wraps titles around fixed controls at enlarged sizes.

The shared foundation shipped in 1.0.18 through PR #94. The README now reflects
the released interface, Pages features and persistent Anki setup. #63 remains
open for owner visual approval and further page-specific controls, sheets,
popovers and animations. See [release details](RELEASE-1.0.18.md).

## Release 1.0.17 — Anki reconnects across tabs (#51)

The cached summary already added on main now has an encrypted restart test.
With Anki closed, the reopened vault shows its last counts and the date they
were fetched. The connection follows the open vault rather than Today’s
component lifetime, so leaving Today no longer stops automatic retries.
Disabling Anki, changing connection settings, closing and switching vaults
cancel or reject stale work. Failed cache writes keep the prior summary and
show a save error rather than silently implying the new counts were saved.

## Release 1.0.16 — time-since precision (#50)

Finishes the minute-start rule from `033a14a`: new restarts and edits normalize
before order validation, so a second restart in the same minute is refused
instead of silently retaining hidden seconds. Legacy starts sharing a minute
retain the entire pair unchanged; independent starts round down. Invalid
histories cannot become valid through rounding. A synthetic vault written by
the unmodified 1.0.15 writer verifies migration and reopening.

This release also packages the work already merged since 1.0.15: Pages, text
shortcuts, task-date defaults, the game removal, Anki settings and saved counts,
and Today/Habits layouts. Their broader tickets remain subject to individual
acceptance review; #46 is still a foundation checkpoint.

## In review — the Habits page (#53, #52, #55)

Both kinds of habit are on screen at once: daily habits on the left with their
streak, consistency, best run and week, and time-since trackers on the right as
a line each rather than a card each. The four-week grid moved into a history a
click away, and the page keeps the last seven days, which is what a correction
usually needs.

Consistency is the share of days kept since the habit began (`HabitStats`),
which is the number a streak cannot give: one missed day sets a streak to zero
however well the month went. A habit now records the day it began (schema 17,
export format 6); one from an older vault takes its first check-off.

## Shipped — Today at a glance, Anki in Settings (#85, #86, #54)

Today is what today is: the clock, the day's schedule, the tasks it wants and
the habits still to tick off, and one line of Anki. The 52-week heat map and
the activities table moved to Data, which is where the numbers they report
live. Daily habits are ticked off on the Tasks page as well, beside the tasks.

Anki is an integration now: switched on in **Settings → Integrations**, with
its API key stored in the encrypted vault and a Test connection button. Today
keeps one line — reviews, time tracked, when it was read — and the month of
history is drawn as a chart on Data. The counts are kept in the vault
(schema 16, export format 5), so the line still says something while Anki is
closed, which is most of the day.

## Shipped — the game removed (#58)

Yoru is a productivity tracker and planner; the game is gone in full. The Game
and Collection tabs, the emulator binding, the Generation III save handling,
encounters, rewards, artwork import and the sprites and scenes on Today were
deleted — about 9,700 lines of `src/main/java` and forty test files.

The vault is schema 15: the campaign, the reward ledger and the game's save are
no longer written, and an older vault is read past them so everything after
lands at the right offset. **A game save an older vault was holding is written
beside the vault** as `<name>-game-save.sav` the first time it opens, and Yoru
says so once, so removing the feature costs nobody their save. The portable
export is format 4 and ignores those keys in an older file.

Study music stays: it plays the owner's own audio files and belongs with focus
sessions. `docs/PRODUCT-GOALS.md` now describes the app Yoru is becoming, and
the plan it follows is issue #65.

## Shipped — Pages workspace (#46)

Merged to main on 22 September 2026 (PR #87). The finishing pass gave the
workspace its header (back, forward, the page's own title, reading toggle and
the ⋯ menu), tabs drawn in the theme rather than the look-and-feel's, the
connections panel (outline, linked from, tasks), embedded pages in reading view,
and the keyboard shortcuts for opening, searching, creating, closing and
stepping back and forward. The first usable workspace connects encrypted pages/folders and task
links to the explorer, tabbed source editor, reading view, search, backlinks,
outline, trash/restore and Markdown file/folder import/export. Autosave failures
retain the draft and block navigation, vault changes and closing.

This is a foundation checkpoint, not completion of every item in #46. Live
preview, attachments/embeds, persistent tab/layout preferences, tree-operation
undo, page snapshots, properties, daily notes/templates, split panes, canvas
and the remaining later-phase tools are still open. Markdown exchange imports
text only and exports into a new folder. See [Pages](PAGES.md) for current use
and limits. The AI integration remains separate in #47.

## Release 1.0.15 — Anki study time

Anki study time is tracked time. `application.AnkiTime` groups Anki's answers
into sittings (under ten minutes apart) and `Tracker.addAnkiTime` records each
finished sitting as a session. The length is the answer times added together,
which is Anki's own figure. Sittings go under an **Anki** activity, or whichever
activity the latest sitting was moved to. Each refresh works the sittings out
again, and a sitting is added once only: its session id is made from its first
answer (a version-8 UUID that marks it as Anki's), anything overlapping recorded
time is left out whole, and a sitting that might begin before the reach is left
for a longer read. No schema change. `AnkiConnect` gained `findCards` and
batched `getReviewsOfCards`, still read-only. The card catches up seven days on
connect, then reads one day per minute. Switching vaults disconnects it.

Validated with `AnkiTimeTest`, the extended `AnkiConnectTest` and
`AnkiCardTest`. Each rule was also broken on purpose to confirm a test caught
it. The client and tracker ran against the upstream AnkiConnect handler on a
synthetic collection. See [release notes](RELEASE-1.0.15.md).

Left alone: deleting a sitting from the last week does not stick while Anki is
connected (it would need a tombstone in the vault, which means a schema change).
Answers synced late that run into a recorded sitting are not added. Both are
documented.

## Release 1.0.14 — Anki reviews

[PR #42](https://github.com/Rabadakku/Yoru/pull/42) adds Anki review totals,
seven dates of history and the active profile to Today. Connection is explicit;
refresh runs in the background, with bounded responses, actionable failures,
stale-data timestamps and cancellation on disconnect. A profile switch during
refresh is rejected so collections cannot be mixed. Closing the vault clears
the snapshot and in-memory API key. No vault migration or reward changes.

Validation includes synthetic HTTP and UI tests, the full isolated suite, all
five themes, and the actual upstream AnkiConnect server with the installed
Anki 25.09.4 backend and a fresh synthetic collection. Personal profiles were
not opened. The tag workflow verifies Java 22 before building all three installers.
See [release notes](RELEASE-1.0.14.md) for setup and scope.

Follow-up integrations: [Apple Health #40](https://github.com/Rabadakku/Yoru/issues/40)
is limited to workouts and steps; [LeetCode #41](https://github.com/Rabadakku/Yoru/issues/41)
tracks coding practice.

## Release 1.0.13 — September 20, 2026

Built on `claude/ui-craft` from released 1.0.12, and released as
[1.0.13](https://github.com/Rabadakku/Yoru/releases/tag/v1.0.13).
Notes: [RELEASE-1.0.13.md](RELEASE-1.0.13.md).

A run of UI work, each change committed on its own green suite, and one defect
that was worth a release on its own: a control drawn outside the row that held
it could not be clicked.

| Gate | Evidence | State |
|---|---|---|
| Isolated suite | `./test.sh`, 78 groups, after every change | Passing |
| Every page, every theme, both sizes | `PreviewInventoryTest`: 100 renders, each read by eye | Passing |
| Every page at every text size | `TextFitTest`: 100, 125, 150, 200% | Passing |
| Rows hold their controls | `VisualSystemTest`: every page at the smallest window | Passing |
| The week starts where the vault says | `HabitGridTest`: all seven starts, grid and heat map | Passing |
| Installers | `release.yml` on `v1.0.13`: .dmg, .msi and .deb, after the suite on Java 22 | Built and attached |
| Clean-machine install, full campaign, real libretro core | By hand | Not verified |

- Done, page by page. Every page: one header line, the repeated controls
  quieted, and wrapping rows that start on the card's margin. Tasks: the
  toolbars fold at the window's minimum, and a row lights under the pointer.
  Habits: a daily habit's days stand in seven weekday columns four weeks deep,
  under the initials of the days, breaking the week where the vault's own
  setting breaks it. Today: the three figures carry the same signpost as every
  other card, the week behind the seven-day figure is a chart with its days
  named, and the agenda's last block is no longer ruled off from the card's
  edge. Data: the fortnight is drawn against the daily goal with a dashed line
  where the goal falls, and a day with nothing recorded no longer captions its
  hairline "0m". The year's heat map follows the same week start, names each
  month once where there is room for the word, and leaves the days still to
  come unpainted. Collection: the line that says what the vault holds stands on
  the card it vouches for. Settings: Appearance writes its captions over its
  controls, as Tracking already did.
- One defect, not a polish: at the window's minimum the focus card's Edit timer
  button was drawn outside the row that held it, which put it out of reach. A
  row that wraps now asks to be measured again the moment its width changes,
  and estimates from the room inside what holds it when it has no width yet.
- Checks: the full isolated suite (78 groups) after every change, including
  `TextFitTest` at 100, 125, 150 and 200%, and `PreviewInventoryTest`'s 100
  renders. Every page was read as a render in all five themes at both window
  sizes, and again at 150% text. Two new checks hold the wrapping-row fix —
  every page's rows contain their controls at the minimum window, and a row
  given less width asks for more height — and a new `HabitGridTest` holds the
  weekday columns and the week start for every one of the seven starts.
- Limits: nothing in the vault format, the reward economy or the game code was
  touched. The activity tables on Today and Data, which repeat three buttons a
  row, and the Game page's two setup cards were looked at and deliberately left
  alone: both are explicit and keyboard-reachable as they stand.

## UI improvements for 1.0.12 — September 20, 2026

On `codex/ui-polish`, based on released 1.0.11. Tracked in
[issue #34](https://github.com/Rabadakku/Yoru/issues/34) and
[PR #35](https://github.com/Rabadakku/Yoru/pull/35).
See [RELEASE-1.0.12.md](RELEASE-1.0.12.md) for the scope.

- Done: task search across titles, notes and tags, retained task view state,
  timer controls ahead of scenery, daily-goal progress, recording status across
  pages, navigation spacing, light-theme action contrast and rounded borders.
- Checks: full isolated suite passes, including 100 rendered views and 3,888
  long-name layout checks across all four text sizes. All five theme galleries
  were visually reviewed. Installer builds remain the release gate.
- Limits: no claim of a new full-source audit, clean-machine install or native
  campaign verification. No changes to the vault format or reward economy.

## Release candidate 1.0.11 — September 20, 2026

Built on `claude/code-review`. Notes: [RELEASE-1.0.11.md](RELEASE-1.0.11.md).
It carries the text size setting (#31), the withdrawal of the companion
artwork at the owner's request, and the fixes from a full review of the source.

The review read every file outside three UI areas — the main window and vault
launcher, the theme and dialog foundation, and the Today, Settings and task
pages — and put each finding to verifiers whose job was to refute it. Of 135
findings, 89 survived, 40 were refuted and 6 were left undecided. The confirmed
domain, application, save-format, vault and importer set is fixed here; each bug
arrived with a test that fails without its fix. The three unreviewed UI areas,
and the confirmed cleanups in the emulator, artwork and remaining UI code, are
the next pass.

| Gate | Evidence | State |
|---|---|---|
| Isolated suite | `./test.sh`, including the named-module check | Passing |
| Every page, every theme, both sizes | `PreviewInventoryTest`: 100 renders | Passing |
| Every page at every text size | `TextFitTest`: 100, 125, 150, 200% | Passing |
| Old vaults and saves still open | `LegacyVaultTest` opens a vault written by the 1.0.10 build | Passing |
| No personal data or game assets | `PrivacyTest`, `DistributionTest`: the jar now ships no images at all | Passing |
| Installers | `release.yml` on the version tag | Pending the release |
| Clean-machine install, full campaign, real libretro core | By hand | Not verified |

## After 1.0.10 — September 16, 2026

#9's acceptance asked for long names and 200% text, and neither had been
checked. #30 was merged from `claude/text-fit`; #31 is on `claude/text-size`.
Neither is released yet.

| Issue | State |
|---|---|
| [#30](https://github.com/Rabadakku/Yoru/issues/30) longest names | Done. `TextFitTest`, in the suite, checks every page with the longest names at both sizes. Long activity names shorten with a tooltip instead of hiding their figures, Today's focus controls wrap onto a line they make room for, and a planned block keeps its match figure. |
| Companion artwork removed | Done, at the owner's request. The Waifu theme, its five bundled portraits, the Settings picker, the Today panel and the page galleries are gone, and Yoru now ships no images at all. A vault that names the withdrawn theme opens as Moonlight; `LegacyVaultTest` opens a real 1.0.10 vault to prove it. |
| [#31](https://github.com/Rabadakku/Yoru/issues/31) text size setting | Done. Settings → Appearance offers 100, 125, 150 and 200%, kept per computer so vaults stay readable by older versions. Each page holds every size at the desktop size and at that size's smallest window, which grows with the navigation bar. `TextFitTest` checks all four. |

## Released 1.0.10 — September 15, 2026

[Version 1.0.10](https://github.com/Rabadakku/Yoru/releases/tag/v1.0.10) is
published from PR #27, built on `claude/close-out` from v1.0.9. It carried the
remaining audit issues, and every one of them is now closed; each issue's
comment lists the commits, the tests and what was not verified.

PR #28 followed the release with the README's six screenshots, which the
Screenshots section had always linked but which had never been committed.

| Issue | State |
|---|---|
| [#6](https://github.com/Rabadakku/Yoru/issues/6) artwork repair | Done. Per-kind status with a Failed state, generation retention, route visitors no longer counted as required scenery, and tests for tile flips, a decoy wallpaper table and one budget across nested archives. |
| [#7](https://github.com/Rabadakku/Yoru/issues/7) save durability | Done. Vault backups are pruned, Close during a start and saves behind a slow disk are tested, and an opt-in lifecycle check ran against a real core. |
| [#8](https://github.com/Rabadakku/Yoru/issues/8) Collection | Done. Party first as real controls, one rewards row, PC boxes with real controls, arranging as a worded mode, readable missing and damaged data, and place kept across rebuilds. |
| [#9](https://github.com/Rabadakku/Yoru/issues/9) visual system | Done, except a 200% text-scaling review. Titles, rounded cards, no breadcrumb, Today's order and partner card, Settings in four sections. |
| [#10](https://github.com/Rabadakku/Yoru/issues/10) test isolation and oracles | Done. The #4 regression was shown failing before its fix; native and desktop checks are documented and were run by hand. |
| [#12](https://github.com/Rabadakku/Yoru/issues/12) cleanup | Done. Today and Settings pages out of YoruApp, encounter tables scoped to a session, the artwork library split into staging and generations, unused code removed, and this table. |
| [#23](https://github.com/Rabadakku/Yoru/issues/23) illustrated theme | Done. A Waifu theme separate from Moonlight, five portraits within the package budget, a visual picker and page-varied galleries. |
| [#3](https://github.com/Rabadakku/Yoru/issues/3) companion placement | Placement shipped in 1.0.7; its art direction is carried by #23. |

### Release gates

| Gate | Evidence | State |
|---|---|---|
| Isolated suite | `./test.sh`, including the named-module check | Passing |
| Every page, every theme, both sizes | `PreviewInventoryTest`: 120 renders | Passing |
| Mechanical refactors change nothing on screen | Page renders compared pixel by pixel before and after each #12 move | Unchanged apart from animation |
| No personal data or game assets | `PrivacyTest`, `DistributionTest` | Passing |
| Real libretro core | `NativeLifecycleCheck`, run by hand | 26 checks passed |
| Keyboard focus on a real display | `DialogFocusTest` with a display | Passing |
| Installers | `release.yml` on the v1.0.10 tag | Built and attached: `.dmg`, `.msi`, `.deb` |
| Clean-machine install and a full campaign | By hand | Not verified |

## Archived history

Everything below is kept as history and is not a statement of current work.
Issue numbers in the v1.0 plan (#24 and #38–#57) refer to the original
repository, not this one.

## Released 1.0.9 — September 14, 2026

Built on `claude/open-issues` from v1.0.8. See the [release notes](RELEASE-1.0.9.md);
each issue's handoff comment records its commits, tests and limits.

- **Finished:** #20 updater command timeouts; #21 editing audit ([matrix](EDITING-AUDIT.md))
  and the gaps it found; and the owner's new #24, Notion-style date and time
  entry, and #25, the Tasks page as a table.
- **Progress, still open for native checks:** #6 per-category artwork status,
  repair and generation retention; #7 a close held up by a failed vault write
  now finishes once the retry saves, and start refuses a mismatched save; #10
  isolation guard, independent save-section oracle and render inventory. None
  of these was re-checked against a real game core.
- **Not started in this release:** #9 visual system, #8 Collection redesign, #3
  and #23 illustrated companion art, #12 cleanup (still last). The Waifu theme
  split and three unregistered portraits sit unmerged on `codex/audit-recovery`
  (7b26473, 221ccbe).

## Released 1.0.3 — September 13, 2026

[Version 1.0.3](https://github.com/Rabadakku/Yoru/releases/tag/v1.0.3) is
published from PR #13. Branch and pull-request checks passed, and GitHub built
and attached the macOS, Windows and Linux installers. Installer compilation
does not replace clean-machine or native campaign acceptance.

See [release notes](RELEASE-1.0.3.md). The repair branch now includes save
durability receipts and failed-write retries, unsigned record decoding,
transactional artwork imports, corrected wallpaper palettes, and initial UI
readability improvements. Full Collection interaction redesign, conservative
legacy gift assessment and final decomposition remain open. Historical
checkpoint notes below are retained with their original scope.

## Current audit overrides — September 2026

Latest review starts from v1.0.7: save read models, legacy gift assessment and
the updater are retained. Missing walking characters were traced to absent
locally imported trainer sheets, not removed animation code. Today now offers
an explicit scene-artwork recovery route, and artwork status counts installed
wallpapers correctly. Moonlight will be the only illustrated companion theme;
other themes retain saved portrait preferences without displaying the art.
Moonlight now includes original Amberglow artwork alongside Nightfall, with
a portrait gallery on its other pages. This supersedes the earlier policy
allowing companion art across every theme. Tests exercise all theme switches,
retained selections and the default illustration without a vault migration.
Release scope is now 1.0.8: route recovery, Moonlight-only illustrations,
retirement of bundled pixel portraits, and individual habit rename/delete.
Habit edits preserve identity and history; deletion targets one id, makes a
vault backup, and leaves all other study data intact. Remaining editing audit,
updater timeout correction, and larger redesign work are ticketed separately.

Post-release checkpoint on `codex/audit-recovery`: reject stale session
producers before queue coalescing so they cannot displace a current save (#7).
An exact-byte regression covers a valid save immediately followed by an old
session callback before the event queue drains.
Save notification failures are also separated from repository failures: once
the vault commits, the save remains acknowledged and no false retry is armed.
Both save fixes are merged in PR #14. The next Collection checkpoint adds
F6 party/box switching, party selection, keyboard pickup and placement,
reachable empty slots, cross-box moves and Escape cancellation through the
existing move callbacks (#8). Larger layout and missing-art work remain open.

Save health checkpoint on `claude/save-read-model` (#5): unreadable saves carry
a reason; pages read the vault's save once per render into `SaveRead`, with no
static cache; Collection says when the vault last saved, offers Retry when a
save failed, and shows one card with Export a copy and Open Game setup when the
save cannot be read. Codex has stopped; the remaining audit issues are owned by
Claude on `claude/…` branches.

1.0.4 shipped that save-health work. Updating from Settings (predecessor #69) is on
`claude/updater` for 1.0.5:

- it checks GitHub only when asked
- installers are kept only when the SHA-256 GitHub publishes matches
- on a Mac, the bundles are swapped after the game and vault close
- on Windows, the `.msi` runs after Yoru quits
- on Linux, Yoru verifies the `.deb` and names the `apt` command

1.0.5 shipped the updater. Legacy study gifts (#11) are on
`claude/legacy-gifts`. A gift the signed encoder wrote is repaired in place from
the Game page, only when it is byte-exact to a reconstructed legacy encoding and
after a vault backup. Anything the game has changed since is left alone and
reported.

1.0.6 shipped that repair. 1.0.7 keeps companion choice in Settings only. That
covers the placement part of #3; its art-direction request was not taken on, and
#3 stays open.

The historical plan below is retained as context, not evidence that the current
release is complete. The fresh repository's audit issues are **#4–#12**.
Read [the repair/design handoff](design/AUDIT-2026-09.md) and
[the visual reference](design/reference.html) before implementing them.

- **Implemented on `codex/audit-recovery`:** unsigned Pokémon decoding/encoding
  and nature (#4), independent wire-format regression, and test home/preferences
  isolation (part of #10). Existing genuine saves need no rewrite.
- **Additional repairs shipped in 1.0.3:** two-bank wallpaper decoding, staged
  artwork publication with rollback tests, saved-game access without emulator
  setup, occupied-record read warnings, storage before reward cards, and bounded
  save-queue retries with visible vault-save status. These are partial delivery
  of #5–#8. Authoritative session acknowledgements are also implemented;
  native lifecycle acceptance and full layout work remain.
- **Open:** save-health UX (#5), staged artwork and wallpaper repair (#6), durable
  acknowledgement/retry (#7), Collection redesign (#8), visual-system work (#9),
  remaining test coverage (#10), conservative legacy gift assessment (#11).
- **Last:** codebase decomposition and mechanical cleanup (#12), after behavior
  and visual changes land. Companion placement remains existing issue #3.
- **Validation:** full isolated suite and named-module check pass. Installed
  app observation and local read-only diagnosis confirmed the reported party
  display defect. Native full-campaign and multi-platform release checks remain.


Updated 2026-09-12. **v1.0 has shipped; v1.0.2 focuses on functional repairs.**

Completed in this repair: ROM sprite and PC wallpaper extraction, background
imports and bounded nested archives, imported-game discovery, settings
preservation, game input release on focus loss, and retry of failed vault saves.
The optional Moonlight companion is deliberately limited; functionality is the
priority. Validation includes the full synthetic suite, real local ROM import,
an installed-core boot check, and page/dialog renders. Full campaign completion
has not been re-verified in this repair.

The original v1.0 plan below is historical. It was
tracked on GitHub in the pinned [v1.0 release plan](https://github.com/Rabadakku/yoru/issues/55)
and the [v1.0 milestone](https://github.com/Rabadakku/yoru/milestone/1). Read
[AGENTS.md](../AGENTS.md) before contributing, [PRODUCT-GOALS.md](PRODUCT-GOALS.md)
for what the product is, and [CURRENT-STATE.md](CURRENT-STATE.md) for handoff history.

## The destination

A study workspace worth using every day — timers, tasks, schedule, habits and
analytics — with the full game playable inside its **Game** tab. Studying
replaces the grind: study time earns encounters, the Pokémon caught go into
the game, and the game's party and PC are there in the tracker while you study.
It installs like any other application and looks the part: a riced desktop's
character with an Apple-level finish.

## Where things stand

| Area | State |
|---|---|
| Tracking | Open-ended clock, corrections, minimum session, daily goal, heat map, focus distribution |
| Persistence | Encrypted local vaults in a folder Yoru chooses, managed by name in the app, with migrations, backups and JSON import and export |
| Tasks | Board, statuses, tags, sorting, drag to reorder, planned and due dates, calendar, reviewed paste import and Notion export import |
| Schedule | Plan and actual lanes, drag to create, move and resize, weekly repeats, configurable week start |
| Habits | Daily streaks and time-since trackers |
| The game | Runs inside the Game tab through the player's own RetroArch mGBA core and ROM |
| Saves | Read with the game's own slot rules; changes written as the game's next save, verified first, with rolling backups |
| Delivery | Study rewards go into the party, then the PC, as one crash-safe transaction, at Play and at Close |
| Battles | The game's own. The Java battle prototype was removed on 2026-09-11 |
| Collection | The save itself: the party and PC come from the game's save, rearranged from Yoru while the game is closed, no starter picker, vault schema 11 |
| Privacy | `AGENTS.md` rules and `PrivacyTest` keep personal data out of the repository; the history decision (#49) is recorded in [GOING-PUBLIC.md](GOING-PUBLIC.md) |
| License | [The Unlicense](../LICENSE), with a [legal notice](../NOTICE) |

## The v1.0 plan, in order

Each step is an issue in the milestone. Later steps build on earlier ones.

**The game, fully integrated with studying**
1. #42 Play and Close: the game runs only when you press Play and stops only when you close it. *(done, on `claude/collection-controls` #24)*
2. #43 Two-way save sync: study Pokémon go in at Play; the party, PC and progress come back at Close. *(done, on `claude/collection-controls` #24)*
3. #44 The save is the collection: no starter picker; the party and PC come from the save and can be rearranged while the game is closed; vault schema 11. *(done)*
4. #45 Study encounters follow the campaign: the ROM's own wild tables, opened up by badges. *(done, on `claude/collection-controls` #24)*
5. #46 The game's own songs as study music. *(deferred past 1.0 — in-progress work kept on `claude/music`)*
6. #40 Each vault gets its own game save. *(done, on `claude/collection-controls` #24)*

**The tracker**
7. #48 Import tasks from a Notion export. *(done, on `claude/notion` #48)*
8. #57 A new logo and app icon. *(done, on `claude/logo`)*
9. #47 The polish pass: a ricer aesthetic, an Apple-level finish, and a real home for the study buddy. *(a decorative waifu panel on the Today page — off by default, a bundled portrait chosen in Settings like a theme, schema 12 — is done on `claude/waifu`)*
10. #38 Manage study activities *(done, on `claude/activities`)*, and #41 manage vaults inside Yoru *(done — vaults are created, renamed, switched and deleted in the app, and a crash is survivable)*.

**Release**
11. #49 Privacy: the tree is clean; the history decision is recorded — **publish a fresh repository from the scrubbed tree rather than rewriting history**, because a rewrite leaves the old commits reachable from forks, reflogs and caches while a fresh repository is provably clean. Steps and the trade-off: [GOING-PUBLIC.md](GOING-PUBLIC.md). *(decided; the owner makes the final call at release time)*
12. #50 License and legal notices *(done)*, and #51 the README *(done)*.
13. #52 Codebase cleanup. *(deferred past 1.0)*
14. #56 Installers: a `.dmg` (and `.msi`, `.deb`) that installs a real app you can keep in the Dock.
15. #53 Verify on a clean machine and tag v1.0.0.

## After 1.0

Plugins (Anki, LeetCode, Apple Health), AI task extraction with an API key, and
the remaining FocusPomo ideas: #54 and #6. Not before the release.

Neither integration is reachable in 1.0: #47 removed the Plugins page, the
Settings entry point to it and the Tasks page's API import, and left the card
that says they are planned. The code comes back from git when the adapters do.

## Release gates

| Gate | Evidence |
|---|---|
| Study tools stand alone | Everything but the Game tab works with no game files |
| Play works | Play, save in the game, Close, reopen: nothing lost, nothing duplicated |
| Study replaces the grind | Study, clock out, encounter, catch, Play: the Pokémon is in the game |
| Both ways | A Pokémon caught in the game appears in Yoru after Close |
| Polish | Every page reviewed as a rendered image in every theme |
| Clean and legal | `PrivacyTest` passes, no game assets anywhere, license and notices in place |
| Installs like an app | Download, install and open from the Dock on a clean machine |

## Rules that do not change

- No personal information and no game assets in the repository, in releases, or in anything written on GitHub ([AGENTS.md](../AGENTS.md)).
- Read [DATA-MODEL.md](DATA-MODEL.md) before changing storage, and never reset data as an upgrade.
- `./test.sh` passes before anything is pushed.
