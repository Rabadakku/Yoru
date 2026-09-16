# Development handoff

## After 1.0.10 — September 16, 2026

Branch `claude/text-fit`, from main after the release.

- **#30: the fit test.** `TextFitTest` lengthens every name in the `Preview` fixture to the model's limit. At 1280×900 and 900×640 it checks every page for:
  - text Swing shortens without a tooltip saying all of it
  - components running past whatever holds them

  It settles the layout before checking. A wrapping label is measured against a width its container only learns in the same pass, and a shown window lays out again when that width arrives.
- **#30: the fixes.**
  - `Theme.shortenable` names give way in `ActivityManager.activityTable` once its rows are too wide.
  - `Theme.wrappingRow` (`WrapFlowLayout`) holds Today's focus controls.
  - Schedule's planned list keeps the match figure apart from the name.

  All 120 page renders are unchanged apart from time-dependent text.
- **#31: open.** `Theme.textScale` scales every role. Run `TextFitTest` with `-Dtextfit.scale=2` for the 200% review; it finds 96 places today, grouped by kind in the issue.

## Released 1.0.10 — September 15, 2026

Branch `claude/close-out`, from v1.0.9, merged as PR #27 and published as
[v1.0.10](https://github.com/Rabadakku/Yoru/releases/tag/v1.0.10) with all three
installers attached. The full isolated suite passes; each issue's comment lists
commits and limits.

- #7: `EncryptedVault.expired` keeps the ten newest reset backups, each recent
  day's first, and any dated in the future; small deletions now back up first.
  `GameLifecycleTest` covers Close during a start and saves behind a slow disk.
  `NativeLifecycleCheck` is the opt-in check against a real core.
- #6: `ArtworkLibrary.lastFailure` feeds a Failed state in Settings; route
  visitor sheets are extras, not scenery. Decoder tests cover vertical flips
  and a decoy table; one budget holds across nested archives.
- #8: `CollectionPage` leads with `PartyStrip`; `Arrangement` carries a move
  between the party and `StorageScreen`; `CollectionLayoutTest` covers layout,
  missing data and rebuilds.
- #9: bold 28 px titles, `Theme.CardPanel` rounded cards, no breadcrumb, the
  agenda before statistics, the partner card without a second clock, Settings
  in four sections; `VisualSystemTest`.
- #23: the Waifu theme (from `codex/audit-recovery` 7b26473), five portraits at
  768 × 1152 within the 8 MB budget, a thumbnail picker and page-varied galleries.
  A vault saved with the Waifu theme cannot be opened by 1.0.9 or earlier.
- #12: `TodayPage` and `SettingsPage` through `Shell`; `EncounterTables` per game
  controller; `ArtworkStaging` and `ArtworkGenerations` behind `ArtworkLibrary`;
  unused code removed. Page renders were compared before and after each move.
- #10: the #4 regression test fails on the build before its fix; README lists
  the checks run by hand.

Not verified: a 200% text-scaling review (#9), a clean-machine install, and a
full campaign against a real core.

After the release, PR #28 added the six screenshots the README links under
`docs/media/`: they had never been committed, so GitHub showed broken images.
They are crops of `Preview` renders in Midnight at 1280 px wide, made with an
empty `user.home` and `-Duser.timezone=UTC`, so no installed artwork, no
preferences and no machine location can reach a published image. Regenerate
them the same way, and look at each crop before committing it.

## Released 1.0.9 — September 14, 2026

Branch `claude/open-issues`, from v1.0.8. Notes: `RELEASE-1.0.9.md`. The full
isolated suite passes; each issue's handoff comment lists commits and limits.

- #20: updater commands log to a file, so the timeout starts at once; a stalled
  command and its children are stopped; a failed stage removes its copy.
- #24: `DateText`, `DateField`, `DateTimeField` and `CalendarPanel` give
  Notion-style date and time entry. The blank Log time boxes were the spinner
  editor's padding exceeding its height. Unreadable text is refused, never reverted.
- #25: the Tasks page is a table with a row menu (edit, track, move, delete).
  `TasksPanel.Columns` shares widths; the table drops BoxLayout's size cache when
  its width changes, or wrapped titles keep stale heights.
- #21: `EDITING-AUDIT.md`. Activity targets, repeat editing, time-since periods
  by start instant, and stale delete refusals, covered by `EditingTest`.
- #10: `IsolationTest` runs first; `Gen3SectionOracleTest` checks saves against
  literal layout; `PreviewInventoryTest` requires all 100 renders.
- #7: `GameController.finishStuckClose` ends a close that only waited on the
  vault; `GameStartTest` covers the save-size refusal.
- #6: `ArtworkStatus` rows in Settings; `ArtworkLibrary.prune` keeps the active
  and previous generations; the import dialog says what was added.

Open: #9, #8, #3/#23 (the illustrated theme work on `codex/audit-recovery` was
not merged), #12. Native checks against a real core were not repeated.

## Released 1.0.8

Based on v1.0.7, retaining the other contributor's save read model, legacy gift
repair and updater. Adds individual habit rename/delete with backup and
failure preservation, Moonlight-only companion art, original Amberglow, and
retirement of bundled pixel portraits. Missing local trainer sheets caused
the reported walking disappearance; importing existing scene artwork restored
the live route. The code now exposes recovery guidance and correct wallpaper
counts. See RELEASE-1.0.8.md. Remaining editing and updater work is in #20–#21.

## Companion placement — September 13, 2026, #3

Branch `claude/companion-settings`, released as 1.0.7. Legacy gift repair (#11)
shipped in 1.0.6.

- Today shows no companion panel, hint or recommendation unless a companion in
  the roster is chosen. The "Try Moonlight" invitation is gone.
- Choosing a theme keeps `Settings.waifu` as it was; the Moonlight card no longer
  switches on Nightfall. Theme card buttons are named `settings.theme.<ID>`, so a
  test can press a specific one.
- `WaifuUiTest` now ends the process on failure, the same `YoruApp` ticker trap as
  the other UI tests.

Not done from #3: its art-direction section asks for new portraits with
minimal, revealing, fan-service clothing. That part was deliberately not taken
on. The bundled portraits are unchanged, and #3 stays open for the owner to
decide.

Left for #12: `WaifuPanel`'s no-choice hint branch (`HINT`, `HINT_NAME`) no
longer has a caller in the application.

This is a stopping point. Remaining audit work: #6 artwork repair options, #7
native save-acknowledgement acceptance, #8 Collection redesign, #9 visual
system, #12 cleanup.

## Legacy study gifts — September 13, 2026, #11

Branch `claude/legacy-gifts`. The plan this work followed is
`docs/superpowers/plans/2026-09-13-legacy-gift-repair.md`.

Builds from the first delivery (predecessor `e1839ff`, 2026-09-11) until 1.0.3
wrote every gift with a high-bit personality in the signed substructure order.
The checksum still passes, so the corrected reader shows another Pokémon.
`game.LegacyGifts` repairs only what it can prove.

- **Proof.** For each delivered reward, `GameDelivery.companionFor` rebuilds
  today's gift; the builder has not changed what it chooses since delivery
  began. `signedLayout` moves its substructures to the signed order, with flag
  byte 0x02 or 0x00, and a party record gets the tail `toParty` wrote with the
  signed nature. A record is REPAIRABLE only when it is the one record carrying
  the reward's personality and equals one of those byte for byte.
- **Left alone.** CHANGED (anything the game touched since), AMBIGUOUS (a
  personality held twice) and NOT_FOUND. High-bit CHANGED or AMBIGUOUS gifts
  delivered before 1.0.3 are named on the Game page as left alone.
- **Repair.** Each gift is rewritten in place from today's builder, through
  `Tracker.editSave`, which backs up the vault first. A save `whyNotEditable`
  refuses is refused. `verify` compares the save section by section: only the
  repaired slots may differ, every repaired gift must now assess CORRECT, and
  every other verdict must be unchanged. A second run returns the save as it
  was, so no schema field records the repair.

The 32-bit nature multiplier change (`d313777`) needs no variant. It only
differs for a pre-nature stat above 595, and a gift has no EVs.

Watch out: never count test reward ids in their low bits. `personalityFor`
folds the two halves of the UUID, so sequential ids keep one sign bit for about
two billion steps and share a personality. The first run of `LegacyGiftsTest`
failed as "ambiguous" for exactly that reason. `Gen3Fixture.highBitRewardId`
hashes a name instead. `Gen3RecordOracle` builds either order without
`Gen3Pokemon`.

No player save or real reward ledger was examined. A player whose save holds
such gifts sees the offer on the Game page.

Next: the remaining #6 artwork repair options, #9 visual system, #8 Collection
redesign, #3 companion placement, #7 native acceptance, #12 cleanup.

## 1.0.4 released, and updating from Settings — September 13, 2026

1.0.4 is published from PR #16 (save health, #5) with all three installers. Its
text is `docs/RELEASE-1.0.4.md`. `release.yml` writes generic notes only when no
release exists yet, so a release created right after its tag is pushed keeps its
own notes and still receives the installers when they finish.

Branch `claude/updater`, target 1.0.5: Settings → Updates, which the predecessor
repository tracked as #69 and never built. `dev.yoru.update` has no Swing:

- `Version` reads `jpackage.app-version`, which the jpackage launcher passes in.
  It is absent in a source build, and a source build is never replaced.
- `ReleaseFeed` makes one GET to `releases/latest`, and only when asked.
- `Updates` maps the platform to its installer: an Apple silicon `.dmg`, a Windows
  `.msi`, or an amd64 `.deb`.
- `Download` fetches only Yoru release URLs, streams to `name.part`, and renames
  it only when both size and the SHA-256 digest GitHub publishes match.
- `MacInstall` stages the app out of the image and checks it is `dev.yoru` at the
  expected version. A script then waits for this process, swaps the bundles and
  reopens, putting the old bundle back if the new one cannot move in.

`UpdatesCard` lives as long as the window, so rebuilding Settings does not lose a
check or a download. `YoruApp.closeForUpdate` closes the game and the vault as
quitting does, runs the installer step and exits. It refuses while a game is
stuck, unlike an ordinary quit.

Watch out: tests compile inside the `dev.yoru` module, because `build/classes`
holds `module-info`, so a test cannot import `com.sun.net.httpserver`. `UpdateTest`
serves HTTP from a `java.base` `ServerSocket` instead. `MacInstallTest` builds a
real disk image with `hdiutil`, never touches /Applications, and skips off macOS.

Not yet exercised on a real install:

- the Windows `msiexec` path
- a Mac update from one published release to the next

Their first real run is 1.0.5 to the release after it. Copies older than 1.0.5
have no updater, so 1.0.5 is installed by hand once.

## Save health — September 13, 2026, #5

Branch `claude/save-read-model`. Codex has stopped; Claude owns the remaining
audit issues and releases, on `claude/…` branches. The plan this work followed
is `docs/superpowers/plans/2026-09-13-save-read-model.md`.

`Gen3Save.read` now refuses with `UnreadableSave` — still an
`IllegalArgumentException`, so delivery, storage edits and import are unchanged —
carrying why: wrong size, never saved, damaged, or a counter pointing at an
incomplete slot. `GameView.read(State)` turns the vault's save into a `SaveRead`
once per render: kind, reason, party and PC counts, durable time, and why editing
is blocked. Pages pass it down. There is deliberately no cache — #12 flags static
caches, and a per-render read cannot show one vault's Pokémon in another.

Collection leads with "Saved in your vault · 5 minutes ago" (`Ago`), or "Saving
to your vault…" / "Could not save to your vault" with Retry. No save and an
unreadable save each get one card; an unreadable save offers Export a copy and
Open Game setup. The status line is left off those two states because, rendered,
it only repeated the card. The Game page names the trainer rather than "a new
adventure" whenever the party holds Pokémon, whatever the starter flag says.
Export lives in `SaveExport`; `flushRow` moved into `Theme`.

Watch out: a UI test that constructs `YoruApp` must end the process itself. The
ticker keeps the JVM alive, so a failed check hangs the suite instead of failing
it. `SaveStatusUiTest.main` shows the pattern.

Reviewed as renders in all five themes at 900×640, including no save, a
wrong-size save and an erased cartridge. Left for #8: the "On their way" card
clips its sentence at 900 wide, and party slots and the portrait still show dex
numbers and "?" when artwork is missing. Left for #9: the unavailable card
reserves a second body line when its sentence fits on one.

Next: #11 (gifts delivered with a high-bit personality before 1.0.3 are still in
the old layout in players' saves), then the remaining #6 artwork repair UX, the
#9/#8/#3 design work, #7 native acceptance and #12 cleanup.

## Released 1.0.3 — September 13, 2026

PR #13 is merged and v1.0.3 is published with all three installers. Both
GitHub test runs passed before merge; all platform packaging jobs passed.
The release notes document upgrade steps and remaining acceptance limits.

The current repair branch adds per-snapshot durability receipts. Shutdown
retains the session until the latest receipt is acknowledged; the controller
acknowledges after the vault write, retains failed snapshots and automatically
finishes closing after recovery. SaveTransferTest covers delayed/stale receipts
and rejected dispatch, and GameSaveFailureTest covers vault failure and stale
session callbacks.

UI changes include larger sans-serif body and control labels, plain page
titles, wrapping instructions, compact storage and initial party-lead details.
See `RELEASE-1.0.3.md` for the release scope and remaining work. Older notes
below describe previous checkpoints.

## Audit recovery — September 12, 2026

Current work is on `codex/audit-recovery`. Read `docs/design/AUDIT-2026-09.md`
for current defects, recovered product decisions and the implementation order.
The predecessor's issue bodies and key campaign/storage discussions have been
reviewed. Its completion labels do not supersede the current audit findings.

The unsigned Pokémon codec correction restores decoding of genuine high-bit
records without rewriting saves. Artwork imports now publish a validated
generation atomically and retain the previous generation on failure. Wallpapers
use both palettes. Game setup no longer hides the saved campaign or its export.
Collection now places storage before rewards and labels unreadable records.
Save retries retain and coalesce snapshots, but the authoritative session
acknowledgement protocol in current issue #7 is still outstanding.

The full Collection redesign, visual system, legacy gift assessment and final
codebase decomposition remain open. No release completion is claimed here.

## Functional repairs — September 12, 2026

Branch `codex/theme-artwork-repair`, release target v1.0.2. The importer now
decodes the supplied ROM using its graphics header, expands all normal/shiny
sprites and finds the PC wallpaper table. Game setup shares the background
importer and discovers the stable retained game filename. No game assets ship.

Settings retain companion choices. GameScreen releases held inputs on focus
loss and teardown. GameController retains a failed asynchronous save and blocks
normal close/vault changes until retry persists it. Disk-failure tests use a
synthetic save; local integration checks use external files only.

Moonlight and one illustrated companion are optional. The owner prioritized
functional repairs over further theme design.


## Current direction — September 11, 2026

The v1.0 release plan is pinned in [ROADMAP.md](ROADMAP.md) and tracked on
GitHub in issue #55 and the v1.0 milestone. `claude/collection-controls`
(PR #24) carries the full game integration: the embedded game behind Play and
Close (#42), two-way save sync (#43), the save as the collection with no
starter picker (#44), study encounters from the ROM's own wild tables (#45)
and one save per vault (#40). The legacy tracker collection, starter picker,
party editor, evolutions and the practice battle engine are gone; study rewards
travel as `Model.Reward` records and land in the save through
`GameDelivery.plan` + `GameSync.deliver`, committed in one vault write.

The build was broken at `376c039` — its commit message said so; the tests for
the removed API were left for the next pass. They have since been rewritten
against the new model, including a `GameDeliveryTest` rebuilt around the pure
planner and the one-write `GameSync` path. `./test.sh` is green again.

#44 is complete: the Collection page arranges Pokémon between boxes and the
party, renames boxes and changes wallpapers from Yoru while the game is
closed, through the same verified transaction as delivery and with a vault
backup before each edit. The game and the tracker never write at the same
time — the controls disable while the game runs.

#41 is complete: vaults are names in a folder Yoru chooses, and creating,
opening, renaming, switching and deleting one all happen in the app (#41,
below). Next: #46 (the game's own songs as study music), then the tracker and
release tickets in ROADMAP.md order. The notes below are chronological history;
older “next work” lists and branch names are not current instructions or
evidence that a feature is complete.

## Vaults managed inside Yoru — 2026-09-11, #41

A vault is a name now, not a file. `persistence.VaultStore` owns the single
folder Yoru keeps vaults in, and every screen addresses a vault by name:
create, open, rename, switch and delete happen in the app, and no screen asks
anybody to find a vault or choose a save path. The welcome screen lists the
vaults on this machine; the Data page has a **Vaults** card for the one that is
open. `Repository.name()` is the whole of what the rest of the app knows about
where a vault lives.

Everything that removes is two-phase, because that is what this ticket is
about. A rename moves the vault, then its unlock key, then its backups, and
walks the moves back if one fails, so a vault is never half renamed.
A delete copies every file the vault owns, proves each copy byte-for-byte with
`Files.mismatch`, and only then removes anything; a removal that fails has the
copies put back and is reported. `VaultStoreTest` runs the store's own delete
step as a step that always fails, and asserts that the vault's bytes, its key
and its backups are exactly what they were and that it still opens with
everything in it.

Switching has the same shape one level up. `Tracker.switchTo` loads the next
vault in full before it lets go of the current one, so a vault that will not
open — a wrong password, a file that is not there — leaves the app exactly
where it was; the vault left behind is closed, which is what releases its lock.
A switch, a rename and a delete all wait for a running game to stop first,
because it holds the save of the vault it was playing:
`VaultLauncher.whenGameStopped` queues the change behind the game's own close,
and `VaultUiTest` drives that guard with a game whose phase is set to running
by hand.

Older vaults are adopted rather than abandoned. `VaultStore.migrate` moves the
vaults an earlier build left in the old folder — with their `.local-key`
sidecars and their encrypted backups — into managed storage, and adopts the one
path an older launcher remembered in `Preferences`. It is idempotent: a source
is only removed once the copy is proven, so an interrupted run finishes next
time. It never overwrites a name in use — the older vault is filed under the
next free name, because two vaults with the same name are two vaults.

The secret of the open vault is kept for the session so that undoing a failed
vault change never means typing the password again; it is zeroed when the vault
closes.

## The mark again, and the app icon — 2026-09-11, #57

`ui/Logo` draws one crescent now, not a crescent over three blocks. The blocks
said "streak", which the heat map already says better; the name says night, and
the mark is the shape the name means. It is still one ink, so a theme tints it
by swapping one colour, and the app icon is the same crescent on a night sky.

Where the geometry comes from: the bite's radius (`0.80` of the outer radius)
and its offset (`0.38`) are the only two numbers typed in. The opening angle,
the horn tips, the thickness at the waist and the mark's own bounding box all
follow from them, and the box is measured from the circles rather than written
down, so moving the bite cannot leave the mark off-centre inside a hand-copied
frame. The bite sits on the diagonal, which puts the mark's weight on its own
axis and leaves the open side up and to the right — the part of the app icon
that is sky.

Below 24px it is rasterised on the pixel grid: sixteen samples a pixel, lit at
half coverage, no antialiasing at all. That is not a style, it is the only way
the horns survive. Antialiased at 20px they grey out and, at some sizes, come
off the body as separate specks. It is vector-filled from 24px up, which is what
keeps 1024px smooth.

The app icon is on Apple's grid: a superellipse body of 824/1024 of the canvas,
centred, with continuous corners rather than arcs, a sky that runs from night to
a horizon glow (moonlight to dusk in the light variant), three pixel stars, and
the crescent at 0.74 of the body. Two variants, because a Dock is not always
dark. Both are tinted by the active palette, so the icon moves when the theme
does. Stars are left out below 32px, where a star is a speck.

`tools/export-logo-icons.sh [out]` runs `build.sh`, runs the `LogoExport`
harness from the test tree — so the shipped jar carries the drawing and not a
copy of what it drew — and writes PNGs at 16 to 1024, the two `.iconset`
folders, and a multi-image `.ico`. On macOS it then calls `iconutil` for the
`.icns`; anywhere else it writes everything up to the iconset and prints the one
command to finish on a Mac. Nothing it writes is committed: it all lands in
`build/`, which is ignored, and the code is the source of truth.

`LogoTest` is 283 checks, up from 66, and still measures rather than admires.
Per size it asserts the mark is one connected shape, that its coverage stays in
a crescent's band, that it fills far less of its own box than the pi/4 a disc
fills, and that its weight sits off its mouth. Below the cutoff the two halves
mirror exactly; above it, to within the rasteriser's own jitter, with the axis
itself pinned by where the centroid sits. Per theme it asserts the ink's
contrast against every height of the icon's sky at 4.5:1. The `.ico` is read
back entry by entry, which is how the first version was caught writing
big-endian — a file that looks right in a hex dump and is garbage to Windows.

The app uses it in the header lockup, on the welcome card and as the window,
Dock and taskbar icon, at 1024 as well as the small sizes.

## Earlier handoffs — September 9 onward

The shared tracker is [Yoru — Development](https://github.com/users/Rabadakku/projects/1).
Existing open issues have been imported; newly opened repository issues are added
automatically. Issue #21 tracks this usable-build pass; #20 tracks regression coverage.

## Source of truth

This work builds on `claude/v4-recovery` at `273ac26`, including the other
contributors' schema 5, schedule grid, themes, dialogs and artwork importer.
The implementation branch is `codex/reliable-tracking-route`. `main` was older
when this work started; do not overwrite the recovery branch with main.

## Delivered in this pass

- Trainer remains horizontally centered and faces one direction. Scenery scrolls
  continuously, pauses in place and resumes without jumping back to the beginning.
- Blue sky, layered trees, flowers, a textured path and optional local grass sheet.
- Evolution and session reset preserve companion nicknames.
- Collection reset uses the same eligible-time calculation as encounters, avoiding
  an invisible reward delay caused by previously excluded short sessions.
- Regression coverage for edits, persistence failure, evolution, shiny odds,
  date validation, reset backups, camera positioning and all four themes.

## Validation and use

`./test.sh`: 213 assertions plus seven UI pages at two widths. Populated Today and
workspace welcome previews inspected. Tests use synthetic vaults, never the user's
personal vault. The jar is built with Java 21 compatibility and no runtime libraries.
Native macOS interaction and a long real study session still need user acceptance.

Run `./run.sh`, or `java -Dyoru.art.dir=/path/to/art -jar build/yoru.jar`.
Close another Yoru instance before opening the same workspace. Existing schema 5
vaults remain compatible. Password-free vaults require their companion key file.
Keep copies of both together. Do not reset data as an upgrade procedure.

The default five-minute minimum excludes shorter completed sessions from totals
and rewards. Change the minimum in Settings if every short session should count.
Manual time and corrections are available on Today. Daily habits and time-since
tracking already exist; this pass prioritizes reliable ordinary time tracking.

## Collection controls — 2026-09-09, second pass

Party (#12) and nickname (#13) interfaces, recovered from an interrupted session
that left them uncommitted in this checkout. They did not compile: `PartyEditor`
read `PARTY_LIMIT` under `import dev.yoru.domain.Model.*`, which imports nested
types only, never static fields. Static members need their own import.

**Settled: only the active buddy accrues evolution time.** The open question on
#12 was whether all six members do — a 6x difference in pace. They do not. The
editor and the Collection card both say so on screen; do not re-open it silently.

`CaptureDisplay` now shows only what the name drops. Its first version appended
the dex number unconditionally, so every card read `#258` directly above its own
`NATIONAL DEX / #258` line. That was invisible in the diff and obvious in the
PNG, which is why `Preview` now catches four companions and nicknames one, and
`DialogPreview` renders the party editor and the nickname prompt.

Watch out: constructing `YoruApp` in a test starts the 70ms ticker, and the JVM
will not exit while it runs. `CollectionUiTest` ends by clicking "Lock & close",
as `UiTest` does. Without it the suite hangs after printing PASS.

`./test.sh` is 262 assertions, up from 223.

## Regression back-fill — 2026-09-09, #20

`#20`'s list is now covered between `ReliabilityTest` and the new
`EvolutionTest`. Most of it was already closed by the route pass: the two-hour
cost, the second stage costing two more, shiny and nickname survival, the
10/8192 rate, rolls not reregenerating on reopen, pre-capture exclusion and the
three edit paths. What was missing was **which species a companion may become**
and **whose recorded time pays for it**.

Both new areas were mutation-checked rather than assumed. That was worth doing:
the first draft's "cannot become another line's evolution" assertions passed
against a build with the target check deleted, because by then the companion had
already evolved and the two-hour gate was refusing them instead. They now run
while the companion still has time banked, so only the target check can refuse.

`Evolutions` resolves 184 edges through a National Dex table that is a
permutation, not an identity — species index 0 is Treecko, #252. Eight species
branch (Gloom, Poliwhirl, Slowpoke, Eevee, Tyrogue, Wurmple, Nincada,
Clamperl); the full set is asserted, so a duplicated edge shows up as an extra
branch instead of silently offering a wrong evolution.

`./test.sh` is 295 assertions.

## Task board and tags — 2026-09-09, #2 and #3

The board replaces one card per task with one row per task: a status chip that
cycles TODO → DOING → DONE on click, the title, the due date, and the tag's own
colour. Four views (All, Due today, Open, Completed) and four sorts (My order,
due date, title, status). Tags are created, renamed, recoloured and deleted
behind **Tags…**, which is where #3 landed.

Three decisions the issues left open:

- **Due today includes overdue.** A view that hides work you have already missed
  is worse than useless on the one screen meant to catch it.
- **Manual order is the default and any sort overrides it**, so ↑/↓ are enabled
  only in All + My order. Manual order is a total order over every task;
  reordering four of nine visible rows would renumber those four and interleave
  them with the five the filter is hiding.
- **Reordering is ↑/↓, not pointer drag.** Buttons are testable headlessly and
  match PartyEditor. Pointer drag is genuinely not implemented — do not read
  "reorder" on the board as closing that part of #2.

Watch out: the short seven-argument `Task` constructor drops `tagId` and resets
`order` and `createdAt`. Every edit went through it, so editing a task would
silently untag it and move it to the top of the manual order. `TasksPanel.merged`
now rebuilds the record field by field and is extracted precisely so a test can
reach it without driving a modal dialog. The AI import path had the same defect,
giving every imported task order 0 — above everything already on the board.

`./test.sh` is 347 assertions.

## Counting the suite — read this before quoting a number

Sum the numbers `./test.sh` actually prints; do not add to the figure the last
handoff quoted. Two of the twelve suites report a description rather than a
count, and every assertion total written down before this line was ten low,
because they were all derived from an earlier note that said 213 when the suite
was really 223.

```sh
./test.sh 2>&1 | grep -oE "PASS: [0-9]+" | grep -oE "[0-9]+" | paste -sd+ - | bc
```

At the time of writing that prints **359**.

## Portable vault export and import — 2026-09-09, #1

`persistence.PortableVault` writes the whole vault as indented JSON and reads it
back; the Data page has **Export vault JSON** and **Import vault JSON…**. Phase 1
is now complete.

`Json` moved to its own leaf package on the way. The export is a second
serialization of `State` so it belongs beside `EncryptedVault`, but the only
codec lived in `ai`, and `persistence` depending on `ai` points the wrong way.

Three things worth keeping if you touch this:

- **Determinism is load-bearing.** A habit's check-ins are a `Set`, and `Set.of`
  iteration order is salted per JVM run. They are sorted on the way out, and the
  test that guards it asserts that exporting the same vault twice produces the
  same bytes. Remove the sort and that test fails — which is the only reason it
  is written that way round.
- **Refusals name the field and the record index.** A vault export is what you
  reach for when something already looks wrong; a bare "Invalid JSON" would be
  worse than nothing.
- **Import is all or nothing** because parsing completes before `Tracker.restore`
  is called, not because restore is clever.

Every claim here is mutation-checked: dropping `order` from the export, ignoring
the stored task status on import, or exporting check-ins unsorted each fail
`PortableVaultTest`.

`./test.sh` is 409 assertions.

## Weekly repeating schedule — 2026-09-09, #4

Schema 6. `RecurringBlock(id, activityId, dayOfWeek, startTime, endTime)` stores
`LocalTime`, and `Analytics.occurrences` expands it onto whichever week is on
screen. **Repeats weekly…** on the Schedule page manages the template; the grid
draws occurrences dashed, and they are deliberately not draggable — moving one
would have to edit the rule for every week, which is a different action from
nudging one Thursday.

**The spec was wrong and was corrected rather than followed.** Both this issue
and `docs/DATA-MODEL.md` §2 said `RecurringBlock` replaces dated `ScheduleBlock`
outright, on the stated grounds that "with no users, there is no reason to carry
both shapes". That premise expired — the app went into daily use before this was
built. Both shapes are kept: a timetable has a Monday lecture *and* a one-off
exam, and migrating dated blocks into weekly repeats would have made that exam
recur forever. `docs/DATA-MODEL.md` now records the corrected decision.

Schema 6 reads schema 5 vaults forward with an empty template, so nothing that
already exists is disturbed.

Two things worth knowing:

- **Daylight saving is the whole point, and it is asserted.** Two consecutive
  Mondays either side of 8 March 2026 are both 09:00 local and one hour apart in
  UTC. Mutating `Analytics.occurrences` to use one fixed offset for the week —
  which is what storing instants amounts to — fails that test.
- **A block inside the skipped hour is genuinely shorter that week.** A
  02:30–03:45 rule starts at 03:30 on the transition day, because 02:30 did not
  happen. Asserted rather than corrected; inventing a duration-preserving rule
  for 2am study blocks would be a worse answer than the truthful one.

Also fixed in passing: `TagEditor`, `PartyEditor` and `WeeklyTemplate` build
their own `BoxLayout` rather than using `Theme.stack()`, which forces
`alignmentX` to 0 as it adds. Mixing 0.0 labels with default-0.5 panels centred
every heading in those dialogs. They now set it explicitly.

`./test.sh` is 470 assertions.

## Task calendar — 2026-09-09, #5

A month of tasks by due date, as a sixth view on the Tasks page rather than a
page of its own — it is the same data, and the tracker it replaces treats it
as a view too. Drag a task onto a day to move its due date, onto the strip
underneath to clear it.

- **Weeks start on Monday**, matching `ScheduleGrid`. The two calendars in the
  app should not disagree about where a week begins, even though the tracker
  this replaces starts on Sunday.
- **A drop that lands nowhere does nothing.** Releasing outside the component
  has to be how you change your mind, not how you silently lose a due date.
  Mutating that to fall through to "clear the date" fails the test.
- **The sort control is hidden on the calendar**, because a month grid is
  ordered by date already and the control would silently do nothing.
- Rescheduling goes through `TasksPanel.merged`, so it keeps the tag, order,
  status and creation time — the same trap the edit dialog fell into.

`TaskCalendar` exposes `relayout`, `dateAt`, `taskAt`, `centreOf` and
`pointOn` package-private so tests can aim a drag at a *date* rather than at a
pixel. `TaskCalendarTest` dispatches real `MouseEvent`s through those points;
it is the first test in this repo that drives a drag, so `ScheduleGrid`'s own
drag interaction is still uncovered and could be tested the same way.

`Preview` now writes `tasks-calendar.png` as well, because the calendar is a
view rather than a page and the page loop never reaches it.

`./test.sh` is 505 assertions.

## Reconciling the two lines of work — 2026-09-09, late

Two agents built on the same `main` in parallel and neither branch saw the
other. `main` took the companion battle box (#8), paste task import (#15) and
route cameos (#10) via PRs #27/#28/#30. `claude/collection-controls` had the
party and nickname screens, the task board, tags, portable export, the weekly
template and the task calendar, and was still open as PR #24.

Merged here, with one conflict: `ui/TasksPanel`. Both sides rewrote the same
region — one adding a **Paste task proposals…** button to the old card list,
the other replacing the card list with the dense board. Resolved by keeping the
board and carrying the paste button and the clearer `Import with API…` naming
onto it. Nothing was dropped: `importPaste`, `TaskPastePanel` and its 68 checks
came across intact.

Everything else auto-merged. Worth knowing why it was that easy: **the two lines
of work touched disjoint hot files.** The cameo and companion work stayed inside
`BuddyScene`, `TrainerScene`, `SpriteAssets` and new classes; this branch's
schema changes stayed inside `Model` and `EncryptedVault`, which the other line
never opened. `docs/COLLABORATION.md` §3 is the reason — splitting by area
rather than by file is what made a three-way parallel session survivable.

One near-miss: `Json` moved from `dev.yoru.ai` to its own package here while
`OpenAiTasks` was being extended there. Git merged the import cleanly, but that
is luck rather than design — a package move under a file someone else is editing
is exactly the kind of change that should be announced first.

`./test.sh` is 843 assertions across both lines of work.

**A real old vault is now a test fixture.** Before shipping the rebuilt jar to
the Desktop, the schema-5 jar that was sitting there was used to write a vault,
and this build was checked against it — activities, sessions, one-off blocks and
tasks all survive, and the schema-6 template arrives empty. Those bytes are now
`LegacyVaultTest`, so every future schema bump has to keep opening them.

The distinction matters: a migration test that writes its own input with today's
writer only proves the writer and reader agree with each other, which they always
will. Flipping the `schema>=6` guard to `>=5` fails this test and passes the
round-trip ones. **If you bump SCHEMA, do not regenerate that fixture — make it
load.**

The local `Yoru.jar` was rebuilt from this merge; the previous build is kept
beside it as `Yoru-previous-2026-09-09.jar`. Neither bundles artwork — both find
`art/` next to them, as they always did.

## Week start is a preference now — 2026-09-09, #26

Schema 7. `Settings.weekStartsOn` defaults to MONDAY so nothing existing moves,
and `Settings.weekOf(date)` is the one place that answers "which week is this".
`YoruApp` and `TaskCalendar` both use it; `ScheduleGrid` already derived its
columns from the `weekStart` it was handed and needed nothing.

This existed because of a mistake made earlier the same day. #5 hardcoded Monday
in `TaskCalendar` to match `ScheduleGrid`, on internal-consistency grounds, when
the tracker Yoru is replacing starts its weeks on **Sunday**. Consistency with
ourselves is not the same as being right.

Watch out: `Settings` has a four-argument convenience constructor that defaults
`weekStartsOn` away, and the theme and trainer pickers on the Settings page were
both using it — so choosing a theme would silently reset the week start. Same
shape as the `Task` seven-argument constructor that quietly dropped `tagId`.
**When a record gains a field, grep every construction of it, not just the ones
that fail to compile.**

`LegacyVaultTest` earned its place immediately: the schema-5 vault fixture
opened under schema 7 without changes, which is the whole reason it is bytes
from an older build rather than something this codebase writes for itself.

`./test.sh` is 850 assertions.

**The rollback jar is one-way, and only before the first save.** The Desktop now
holds `Yoru.jar` (schema 7) beside `Yoru-previous-2026-09-09.jar` (schema 5).
The new jar opens the old vault; the old jar refuses a vault the new one has
saved, with `Unsupported schema.` — verified, not assumed. So the previous jar
is a rollback only until the new one writes. Anyone who wants a genuine escape
hatch should take a **portable JSON export** first (Data → Export vault JSON),
which is readable by anything and is the reason #1 exists.

## Pointer drag on task rows — 2026-09-09, #23

Drag a row to reorder it, with a cyan insertion line above the slot it will land
in. The ↑/↓ buttons stay: they are keyboard reachable, and they are what a
headless test can drive without synthesising events.

Same gate as the arrows — All view under My order — because manual order is a
total order over every task and dragging inside a filter would renumber the
visible rows against ones it cannot see.

Two Swing details worth keeping:

- **Listeners go on the row *and* its non-interactive children.** Swing delivers
  a press to the deepest component under the cursor and does not walk up, so a
  press on the title label would never reach the row. Buttons are skipped
  deliberately — they consume their own presses, which is why pressing Edit does
  not start a drag.
- **The indicator swaps borders rather than repainting the container.** Rebuilding
  rows mid-drag would destroy the component the drag is running on.

`TaskBoardTest` drives real `MouseEvent`s, the same approach `TaskCalendarTest`
introduced. Removing the `reorderable()` gate, making release a no-op, or
inserting without lifting each fail it.

`./test.sh` is 861 assertions.

## The mark — 2026-09-09, #7

`ui/Logo` replaces the placeholder, which was the kanji 夜 in whatever
sans-serif the platform happened to have. Original geometry: a crescent for the
night over three rising blocks for showing up repeatedly, which is the thing the
heat map already measures.

Said plainly, because the issue asked for a wordmark too: **the mark is drawn,
the letterforms are not.** "yoru" is set in the interface's own mono face. What
is original is the mark and the lockup; a bespoke typeface is a different and
much larger job, and claiming otherwise would be a lie in a docs file.

Three things that are decisions rather than accidents:

- **Single ink.** Two of the four themes are light. A mark needing a different
  drawing per theme is a mark that drifts; this one adapts by swapping a colour.
- **It simplifies below 20px** to the crescent alone, grown to fill the box. At
  16px each block is under two pixels and the three read as one smudge. That is
  the usual answer for a two-element mark at favicon size — simplify, do not
  shrink.
- **The crescent's three numbers are not free.** Thickness at the waist is
  `r + offset - bite`; too small an offset and the bite lands inside the disc,
  giving a ring, too large and it barely clips, giving a full moon.

`LogoTest` measures rather than admires. It counts connected shapes — four at
full size, one at 16px — and it checks the largest shape's fill against its own
bounding box, because a disc fills pi/4 and a crescent fills far less. Removing
the bite fails that at exactly 0.79, which is pi/4, so the metric is measuring
what it claims to. Contrast against each of the four grounds is computed with
the WCAG luminance formula and asserted at 3:1.

That last one exists because the first draft of this test passed against a mark
with no bite and against a theme whose accent equalled its background. Both were
weak assertions, not working code.

`./test.sh` is 927 assertions.

## Four bugs from actual use — 2026-09-09, #31 to #34

Reported by the owner running the app, not found by testing it. All four are worth
recording because of *why* the tests missed them.

**#31, disabled controls illegible.** The palette said 4.6:1 and the screen
showed 1.9:1. `BasicButtonUI.paintText` draws disabled text as
`getBackground().darker()` and ignores `Button.disabledText` entirely — on a
dark theme that lands *darker than the fill it sits on*. Raising the palette
value did nothing, twice, because the palette was never reaching the pixels.
`Theme.FlatButton` now paints disabled labels itself.

The test lesson is the durable part: a palette assertion cannot see this.
`ContrastTest` now renders a real disabled button and measures the ink, and
reverting the fix fails it at 1.83:1 — the same number the screenshot showed.
Muted text on both light themes was also under the bar (SAKURA 4.43, LINEN
4.12) and is fixed alongside.

**#32 and #33, the schedule grid.** Planned blocks could not be deleted and
recorded sessions could not be touched at all. The original reasoning —
"recorded time is history, not a plan" — was wrong in use: you notice a session
is wrong *while looking at the week*, and then had to go elsewhere to fix it.
`editableAt` now covers both; double-click opens an editor with Delete.
Repeating occurrences stay inert, because dragging one would edit every week.

**#34, short sessions.** Sessions under the minimum were excluded from every
total and from rewards, but still drawn on the grid and listed on Data — the
list disagreed with the numbers beside it, silently. Both views now apply
`Analytics.counts`, and Data says how many were left out and that they are still
in the vault and the JSON export.

**The owner's call, taken after the above was written: short sessions are not
recorded at all.** So `Tracker.stop` now discards a session under the minimum
instead of storing it, and returns `false` so the interface can say what
happened rather than letting time appear to vanish.

Only that one path discards on its own, because it is the one the clock takes
without being asked. `log` and `editSession` **refuse** with a message —
nobody should type a session deliberately and watch it disappear.

The read-time rule stays, because a State can still hold short sessions: an
older vault, or a JSON import. `Analytics.counts` still governs display, and
Data offers **Remove them** to clear any already stored. Nothing is deleted
without being asked for.

One bug caught by an existing test while doing this: the floor check originally
ran *before* the record's own validation, which turned "you clocked out before
you clocked in" into a silent delete. The candidate `Session` is now built
first, so validation happens before the floor does.

`docs/ARCHITECTURE.md` §3's "store raw, derive rules" now has an explicit
exception, recorded there rather than left as a contradiction.

`./test.sh` is 993 assertions.

## Plan and actual, side by side — 2026-09-09, #35

Each day on the week grid is now two lanes: the plan on the left, what was
actually tracked on the right.

They used to share one column with recorded time drawn *over* the plan, which
hid the plan in exactly the case worth looking at — the hour you meant to study
and the hour you did. The old comment said planned blocks sat "slightly wider so
a session drawn over one still shows its edges", which is a workaround
describing the problem rather than solving it.

- `laneX(day, planned)` and `laneWidth()` are the only places the split lives;
  `boundsOf` uses them and nothing else needs to know.
- Lane labels appear only when a column is at least 96px, so a narrow window
  drops them rather than printing overlapping text.
- The drag preview draws in the lane the result will land in.
- Creating by dragging still makes a *plan* whichever lane you start in, and the
  preview snapping to the plan lane is what says so.

Asserted by position rather than by eye: a plan and a session on the same day
must have the plan's x less than the session's. Reverting `boundsOf` to the
shared column fails that.

`./test.sh` is 996 assertions.

## Study music — 2026-09-09, #14

`assets/MusicLibrary` imports the user's own audio the way `ArtworkLibrary`
imports sprites; `ui/MusicPlayer` plays it while the timer runs. Nothing is
bundled, and nothing plays unless it is switched on.

**The format limit is real and is stated in the interface, not buried here.**
The JDK decodes WAV, AIFF and AU and nothing else. MP3, M4A, FLAC and OGG need a
codec, and `ARCHITECTURE.md` opens with "Swing and the JDK only, no runtime
dependencies" — a music feature is not a good reason to break that. Since most
libraries are MP3, this is a genuine limitation, so Settings says so *before*
anyone picks a folder rather than after they find nothing imported, and the
import counts unplayable files separately from skipped ones so the message can
explain which happened.

Three details worth keeping:

- **Files are decode-checked on import**, not just extension-checked. A text
  file renamed `.wav` is rejected at import; otherwise it would only fail at
  play time, in the middle of a session.
- **Playback syncs from the ticker**, not from the clock-in and clock-out
  buttons, so a session recovered when the vault opens — which passes through
  neither — still starts the music.
- **Volume is applied in decibels.** A linear percentage sounds wrong because
  loudness is logarithmic, and zero means silence rather than a faint hiss.

Enabled and volume live in `Preferences`, not the vault: which speakers are
attached is a fact about this machine, and a volume that travelled between a
laptop and a desk would be wrong at one end.

Marked 🟡 rather than ✅ in `FEATURES.md`. It works, but "study music" that
cannot play the format most music is in deserves the honest tick.

`./test.sh` is 1027 assertions.

## Battle data layer — 2026-09-09, #17

`battle/SpeciesData` and `battle/MoveData`: base stats and both types for all
386 species, and 56 moves with real power, accuracy and PP. No Swing anywhere
near it, as the issue asked.

**Source changed from the issue's plan, on the owner's call.** #17 said PokeAPI;
the data is extracted from the user's own Emerald ROM instead. `tools/extract-
battle-data.py` does it and is committed; the ROM is not, and never will be.
What ships is numbers and names.

**Nothing hardcodes a ROM offset.** Every table is found by searching for a
signature whose contents are already known — Bulbasaur's stat line confirmed by
Ivysaur following it, an identity run confirmed by Treecko, moves 1-3 by their
power/type/accuracy/PP — and then verified against values checkable without the
ROM. A wrong offset fails loudly rather than producing a plausible table of
noise.

That mattered twice, and both are the reason the checks are written the way they
are:

- The well-known "internal species = National number + 25" rule held for
  Bulbasaur through Blaziken and then **silently returned Breloom's stats under
  Gardevoir's name**. Species past the Hoenn starters are stored in *regional*
  dex order. The mapping now comes from the ROM's own species-to-National table.
- The move list included "Bug Bite", which is Gen 4. The extractor now asserts
  the type of *every* move against what that move must be, so a wrong id fails
  instead of quietly labelling some other move.

`BattleDataTest` guards the shipped file rather than the extractor, because the
extractor needs a ROM nobody else has. Its landmarks — Mewtwo 680, Blissey 255
HP, Shuckle 230/230, Gardevoir Psychic 518 — are independently known, so a
regeneration that drifts fails in CI.

Note for #18: the type-effectiveness chart is **not** here. Searching for it in
the ROM produced a 4424-entry false positive, and unlike 386 species of stats it
is a small, canonical, unchanging rule. It belongs with the engine.

`./test.sh` is 1079 assertions.

## Battle engine — 2026-09-09, #18

`battle/Types`, `battle/Battler`, `battle/Battle`. Pure logic, no Swing — the
package is grepped for `javax.swing` and `java.awt` and must stay empty of both.

Covered: the Gen 3 damage formula, the full type chart, STAB, criticals,
priority then speed then a coin flip for turn order, four moves with PP, stat
stages, four non-volatile statuses, and an opponent that picks by expected
damage. Out of scope, as the issue said: abilities, held items, the full
move-effect catalogue, doubles and weather. Moves outside the handful of
implemented effects are narrated honestly as having been used rather than
pretending to do something.

**The type chart is written out rather than extracted**, because searching the
ROM for it returned a 4424-entry false positive — random bytes that fit the
shape. Eighteen by eighteen is small enough to state and check directly, so
`BattleEngineTest` asserts it against relationships anyone can verify, plus a
sweep that every cell is one of 0/50/100/200.

**The order of operations in the damage formula is the specification, not a
detail.** Every step truncates, so applying burn, the +2, criticals, the
0.85-1.00 spread, STAB and type in a different sequence gives a different
answer. That is exactly how a real bug was caught here: the test computed the
same steps in a different order and disagreed by two points, and the engine —
not the test — turned out to be wrong. Burn was being applied after the +2 and
the spread after STAB.

Randomness is a `Random` the caller owns, so the same seed replays the same
fight, which the test asserts. Out of PP falls back to a Struggle-like attack so
two exhausted battlers cannot deadlock; that one is asserted with a 500-turn
guard rather than trusted.

`./test.sh` is 1141 assertions.

## Battle screen and gyms — 2026-09-09, #19

`battle/Gyms` holds the eight Hoenn rosters as fixed data; `ui/BattleScreen`
puts the engine on screen; the Collection page lists the gyms with their badges
and sends the active buddy to fight.

**Every rule stayed in `battle`.** The screen reads state and calls
`takeTurn`; it decides nothing about damage, order or who wins. That is why the
engine was fully tested before a window existed, and it is worth keeping.

**Levels come from study time.** `Movesets.levelFor` gives a level per thirty
minutes recorded with that companion — the same unit as an encounter. Yoru has
no experience points, so the training montage is the thing the app actually
measures. Movesets are derived from a species' own types, gated on level so a
level five companion is not handed Fire Blast.

That derivation is systematic rather than a real learnset, and it says so: a
per-species learnset is a table this project has no source for, and inventing
one would be worse than being obviously mechanical. What is guaranteed is that
every one of the 386 gets something it has STAB on, asserted across all of them.

Rosters are fixed rather than rolled. A gym that rerolled its team would make
losing feel arbitrary and winning feel unearned. Difficulty climbing across the
eight is asserted, so the badge order means something.

Losing costs nothing but the attempt. There is no fainting to recover from and
no progress to lose, because this is a study tracker with a game in it rather
than the other way round.

`./test.sh` is 1218 assertions.

## Planned day and deadline are separate now — 2026-09-09, #25

Schema 8. `Task.plannedFor` is a nullable second date: `due` stays the deadline,
`plannedFor` is the day you mean to sit down with it, and `workOn()` returns the
plan if there is one and the deadline otherwise.

This came out of a real Notion tracker, where the split had already
happened informally — task titles carrying `(actually due Friday, 11:59 PM)` while
the date column held the day he planned to work. A deadline written in prose
cannot be sorted on, coloured, or warned about.

- **Views and sorting use `workOn()`.** "Due today" answers what am I doing
  today, which is a question about the plan.
- **Dragging on the calendar moves the plan, never the deadline.** A deadline is
  not something you drag; it is changed deliberately, in the editor. The test
  asserts the deadline is untouched by a drag, which is the point of the split.
- **`scheduledLate()`** flags planning to start after the thing is due, shown in
  red on the row with both dates in the tooltip.
- A row whose two dates differ is marked with `→`, because the two *being*
  different is the thing worth noticing.

Added as the record's last component with a ten-argument convenience
constructor, so **not one existing construction site changed** — there were 38.
Same trick as `Settings.weekStartsOn`. Older vaults planned nothing, which is
exactly null, and `LegacyVaultTest`'s schema-5 fixture still opens.

`./test.sh` is 1231 assertions.

## Next work

Full portable export/import (#1), task status/tag UI (#2/#3), recurring schedules
(#4), native distribution (#16), and richer encounter/battle presentation remain
open. Anki, LeetCode and Apple Health are not live sync integrations. AI import
requires explicit setup and network access; ordinary tracking is local.

Game artwork and ROMs are personal external files, not repository contents.
Do not commit vaults, keys, class documents, API credentials or generated builds.

## Companion battle box — 2026-09-09, #8

`codex/buddy-battle-box` starts from `main` at `1713fc7`, in an isolated
checkout. The shared Desktop checkout and its uncommitted schema work were
not changed. Runtime changes are confined to `ui/BuddyScene`.

The companion now stands on an oval arena beneath a stepped nameplate, with a
framed dialogue panel showing the recording state. All colours come from
`Theme`; normal/shiny artwork remains external. No battle statistics are
invented. Missing artwork shows the National Dex number, and no companion
shows a starter prompt. Breathing, hops and sparkles still follow recording;
empty and resting states stay still. Non-square user artwork keeps its aspect
ratio. Screen readers receive the identity and current study status.

`./test.sh` passes, including 84 new companion assertions using synthetic
artwork. `BuddyPreview` renders normal, shiny and empty states in all four
themes; the contact sheet and full Today/Collection pages were visually
reviewed. Personal-art previews stay under ignored `build/` and are not shipped.

## Offline task proposals — 2026-09-09, #15

Tasks → **Paste task proposals…** supplies a copyable extraction prompt and
accepts plain JSON or one complete JSON code fence. The optional source label
travels with the tasks. **Review tasks** enters the existing API review table;
only its **Add tasks** command calls `Tracker.addTasks`. Cancelling either
dialog leaves the vault unchanged. No connection or API key is used by Yoru.

`OpenAiTasks` shares task validation between the two inputs. It refuses malformed
JSON, unknown/missing fields, invalid dates, excessive task counts and oversized
text. It does not guess dates or search prose for fragments of JSON. A bad row
refuses the whole proposal batch and identifies its row number; the paste form
keeps the text so it can be corrected. The format and instructions are in
`OpenAiTasks.pastePrompt()`. No schema change.

Validation: the full suite plus 68 paste checks passes. Tests cover code fences,
API/paste equivalence, limits, invalid second rows, source/evidence retention,
form retry, deduplication and failed-write atomicity. `TaskPastePreview` renders
the actual form inside a dialog in all four themes for visual review.

Branch: `codex/paste-task-import`. The shared Desktop checkout was not edited.

## Route visitors — 2026-09-09, #10 and #11

`codex/route-cameos` adds a separate cosmetic `RouteCameos` renderer to the
existing scene. Ground visitors rotate through locally available trainer,
cyclist and Team Rocket artwork. Their 180-frame appearance happens once per
6000 active frames and never overlaps the existing sky sighting. All motion
uses TrainerScene's frame count; no independent timer, persistence or reward
mutation is introduced. Clock-out and reduced motion freeze the scene.

Custom `route-trainer.png`, `route-cyclist.png` and `team-rocket.png` are
right-facing strips with 1–8 horizontal 32×32 frames. The importer accepts
them from folders or zip files; Settings counts only usable strip dimensions.
Absent/invalid strips are skipped. An existing personal pack can reuse the
other native trainer when the custom trainer strip is absent. Cyclists and
Rocket appearances require their corresponding optional files.

Team Rocket's brief caption is painted after night tint for readability.
The cameo is cosmetic, not a campaign NPC or a battle reward. #29 tracks
the separate original-game progression requirement.

Validation: full test suite including 167 cameo checks; original geometric
fixtures exercise imports, rarity, frame bounds, all themes, pause/resume,
resize, missing artwork and native trainer fallback. `RouteCameoPreview`
renders day/night visitors and the fallback with optional external scenery.
No artwork, vault changes or edits to the shared Desktop checkout are included.
