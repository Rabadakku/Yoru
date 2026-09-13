# Yoru roadmap

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

Post-release checkpoint on `codex/audit-recovery`: reject stale session
producers before queue coalescing so they cannot displace a current save (#7).
An exact-byte regression covers a valid save immediately followed by an old
session callback before the event queue drains.
Save notification failures are also separated from repository failures: once
the vault commits, the save remains acknowledged and no false retry is armed.

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
