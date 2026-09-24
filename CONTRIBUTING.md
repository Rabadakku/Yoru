# Working on Yoru

Read [AGENTS.md](AGENTS.md) first: nothing personal ever goes into the repository
or onto GitHub. Then [docs/PRODUCT-GOALS.md](docs/PRODUCT-GOALS.md). Yoru is a
local desktop productivity workspace for time, tasks, habits and Markdown notes.
The product goals supersede the retired game direction.

Written for whoever picks this up next — a person, or an AI agent working
alongside another one. Several agents have already worked this repository
concurrently, and that has cost real work once. Section 1 is why.

---

## 1. Working in parallel — read this first

**Multiple agents edit this repository at the same time, from separate working
copies.** Two of them pushing to `main` will overwrite each other and neither
will notice.

Rules:

1. **Check the remote before you start and again before you push.**
   ```bash
   gh api repos/Rabadakku/yoru/branches --jq '.[] | "\(.name) -> \(.commit.sha[0:8])"'
   ```
2. **Work on a branch, never straight on `main`.** Name it for who you are:
   `claude/task-board`, `codex/battle-engine`, `owner/theme-tweaks`.
3. **Confirm your working copy is actually a git repo** before writing anything.
   An agent once produced ~1000 lines into an unversioned folder while `main`
   sat two days stale, and everyone believed it was pushed.
   ```bash
   git -C . rev-parse --is-inside-work-tree
   ```
   If it is not, clone the real history and copy your files onto it rather than
   `git init` — an unrelated history cannot be merged cleanly.
4. **Claim work in the issue tracker** before starting, so two agents do not
   build the same screen twice. Comment on the issue, or assign yourself.
5. **Rebase before you push**, and never force-push a shared branch.

---

## 2. Build, test, look

```bash
./build.sh    # compile + jar
./test.sh     # full suite, headless, no credentials needed
./run.sh      # build then launch
```

Rendering the UI without launching it — how UI changes get reviewed:

```bash
# every page to PNG, at a given size and theme
java -cp build/classes dev.yoru.ui.Preview build/preview 1280 1000 MIDNIGHT

# the dialogs, which the page preview cannot reach
java -cp build/classes dev.yoru.ui.DialogPreview build/preview/dialogs
```

Compile tests into `build/classes` first:

```bash
find src/test/java -name '*.java' > build/tests.txt
javac --release 21 -encoding UTF-8 -cp build/classes -d build/classes @build/tests.txt
```

For a native keyboard and clipboard check on a desktop, after compiling tests:

```bash
java -Djava.util.prefs.PreferencesFactory=dev.yoru.ui.TestPreferencesFactory \
  -cp build/classes dev.yoru.ui.DesktopTextInputCheck
```

This opt-in window provides synthetic task, habit, search, password, date,
spinner, combo, notes, Pages and dialog inputs. Verify the platform's select-all,
copy, cut, paste, undo and redo shortcuts and the right-click menu in each field.
Passwords must accept paste and refuse copy/cut. In Pages, also check
Command-Delete and undo. It never opens a vault and restores the clipboard when
the window closes. The ordinary test suite stays headless.

**Look at the PNGs.** A contrast regression shipped once because the preview
only covered pages and not dialogs, and `JOptionPane` builds its own buttons
that bypass the theme helpers entirely.

---

## 3. Layout

```
domain/       immutable records + validation
application/  Tracker (the only mutation boundary), Analytics (derived views)
persistence/  EncryptedVault (AES-256-GCM), LocalAccess (password-free key)
collection/   Encounters (reward economy), Evolutions (species graph)
ui/           Swing; YoruApp is the shell, one class per page
ai/           optional OpenAI extraction, bring-your-own key
```

---

## 4. Rules that hold the design together

Breaking one of these is how the project rots. If you need to, change this file
and say why.

- **`Tracker` persists before it publishes.** A failed write must leave the
  in-memory state untouched. Never mutate `State` outside it.
- **Validation lives in domain constructors.** Illegal states should be
  unrepresentable. The UI's job is to catch bad input *earlier and more
  legibly*, never to be the only thing between a typo and the vault.
- **Store raw, derive rules.** Recorded facts are immutable history; product
  rules (a five-minute floor, a daily goal) are read-time functions over them.
  Baking a rule into storage turns every future tweak into a migration.
- **No game assets in this repository.** Ever. See the README and the
  [legal notice](NOTICE): Yoru bundles no game, BIOS, artwork or music, needs
  the player's own legally obtained copy, and is not affiliated with Nintendo,
  Game Freak or The Pokémon Company.
- **Palette through `Theme`.** No hardcoded hex in a page. Four themes exist,
  two of them light — a colour that only reads on dark is a bug.
- **Dialogs through `Dialogs`.** Calling `JOptionPane` directly reintroduces the
  Java mascot icon and drifts the wording.

### Swing traps already paid for

- **Aqua ignores `UIManager` colours and every `setBackground` on a button.**
  The app forces the cross-platform LAF; do not "fix" that by switching back.
- **Metal's combo, scrollbar and button delegates paint their own chrome** and
  ignore the palette. The Basic delegates are installed instead.
- **Disabled controls read from separate keys** (`ComboBox.disabledBackground`,
  `Button.disabledText`). Miss one and it renders near-white on a dark panel.
- **`JSpinner` with `PERSIST` focus keeps unparseable text.** `DateTimeField`
  uses `COMMIT_OR_REVERT` and reads the *model*, never the text.

---

## 5. Tests

`./test.sh` runs the isolated checks and page renders. Representative suites:

| Suite | Covers |
|---|---|
| `CoreTest` | timers, corrections, DST, analytics, the session floor, heat tiers, vault auth |
| `SchemaTest` | schema 5 round trip, tag deletion, reset scoping |
| `ExpansionTest` | encounter economy, task dedup, older-schema decode, AI response fixtures |
| `HabitsTest` | streaks, timezones, restart history, password-free vaults |
| `HabitHistoryTest`, `ui/HabitHistoryUiTest` | old daily corrections, retained beginning, missed restarts, paging, failures and persistence |
| `ui/InputTest` | revert-on-invalid, DST gaps and overlaps, range clamping |
| `ui/UiTest` | every page rendered headlessly at two widths |
| `ui/CrudCoverageTest` | every kind of record created, viewed, edited and deleted by the tracker and from named controls (#59) |
| `ArtworkTest` | artwork naming, nested folders, zip import and path escapes |
| `ReliabilityTest` | edits, evolution, shiny odds, vault reopen, backup failure and reset accounting |
| `ui/RouteTest` | centered camera, pause/resume, resize and all four themes |

Every new record must ship with create, view, edit and delete controls (#59),
reachable by mouse and keyboard and named for accessibility. Cover the tracker
operations and actual UI controls, including failed saves; take a backup before
destructive changes. No record should require editing the vault by hand.
`ui/CrudCoverageTest` holds every kind of record to this, row by row: add a row
there, with the controls' names, in the change that adds the record.

Add tests with the feature, not after. The features recovered from an
interrupted session shipped with zero coverage and two of them had real bugs —
a silently dropped party, and a reset that wiped settings it was not asked to
touch.

---

## 6. Commits

Explain **why**, not what — the diff already says what. Note anything surprising
you found, because the next agent will hit it too.

End commit messages with:

```
Co-Authored-By: <Your Model or Name> <noreply@example.com>
```
