# v1.0.20 — Habits at a glance

Each habit is one line, so a page of them fits on the screen; habits tick off
from the keyboard; the task calendar makes tasks on a day; Anki's connection
test says what went wrong; the drawn views follow the text size. See
[release details](docs/RELEASE-1.0.20.md). No vault migration.

# v1.0.19 — Interface detail polish

Quieter tabs, rounded scrollbars, consistent hover feedback and accurate Edit
menu states. See [release details](docs/RELEASE-1.0.19.md). No vault migration.

# v1.0.18 — A quieter workspace

Collapsible sidebar, system typography, native Mac menus and optional system
appearance. See [release details](docs/RELEASE-1.0.18.md). No vault migration.

# v1.0.15 — Anki study time counts

Finished Anki sittings are now added to your tracked time while Anki is
connected, at the length Anki itself reports, and never twice. See
[release notes](docs/RELEASE-1.0.15.md). No vault migration.

# v1.0.14 — Anki review tracking

Today now displays Anki review totals, seven dates of history and the active
profile through AnkiConnect. See [setup and release notes](docs/RELEASE-1.0.14.md).
No vault migration or game reward changes.

# v1.0.2 — functionality and artwork repairs

- Importing the supported Emerald game now extracts all 386 normal sprites,
  386 shiny sprites and 16 PC wallpapers locally. The game archive can be
  selected from Game setup or dropped onto the window; imported copies keep a
  stable filename that the Game tab discovers automatically.
- Artwork extraction runs in the background. Nested archives have shared size,
  entry and depth limits. Settings can re-extract from the current game.
- Game controls release when the window loses focus or the player leaves the
  Game tab, preventing stuck movement and buttons.
- Failed game-save writes remain available for retry. Closing and vault changes
  wait until the latest save reaches the vault, even after the emulator stops.
- Changing theme, trainer or tracking settings preserves the companion choice.
- Optional Moonlight palette and a full-size original anime companion. Existing
  themes and pixel portraits remain available.

Game artwork is extracted on the player's machine and is never in the release.
Older vaults open normally; choosing Moonlight requires this release or newer.

# Yoru 1.0.1

A patch for 1.0.0. It fixes one crash and changes nothing else.

## Fixed in 1.0.1: a crash with "JComboBox.getUI() is null"

Creating a vault — or opening the app — could crash with that message on macOS,
most often when an accessibility feature such as VoiceOver or Full Keyboard
Access was on. Yoru ships as a Java module, and the look-and-feel delegates it
installs for combo boxes, check boxes and sliders were not reachable from the
Swing toolkit inside that module, so each of those controls was built with no
UI. The macOS accessibility bridge is the first thing that touches a control
with no UI, and it threw. The module now exports the UI package the way the
look-and-feel needs, so the controls build correctly and the crash is gone.
Vaults are stored and encrypted exactly as before; nothing needs to be remade.

## Typing into a dialog now works

Every dialog in Yoru opened with the keyboard on one of its buttons rather than
on the field it was asking you to fill in. Typing a password into the unlock
dialog went to the **Unlock** button instead of the box, the box stayed empty,
and Yoru then refused a password that had never reached it. The same thing made
a new vault's optional password look mandatory — pressing **Create** on a form
you had not been able to type into asked for at least 12 characters — and made
renaming a vault look like nothing had happened.

The field a dialog asks you to fill in now has the keyboard when it opens, and
Enter finishes the dialog from inside the field. Destructive prompts are
unchanged: they still open on **Cancel**, so Enter cannot delete anything.

If you made a vault in 1.0 and could not get back into it, your password was
almost certainly right the whole time. Nothing about how vaults are encrypted
has changed, and no vault needs to be remade.

## A password is optional, and says so

A new vault now defaults to **no password**: the dialog offers two choices,
**No password** and **Require a password**, and **No password** is the one
selected. The vault you make with one keystroke opens without one; choose
**Require a password** to add one.

## Remove the password from a vault that has one

A vault whose password you no longer want can be opened
without one:

- **Welcome screen** — *Rename or delete…* → pick the vault → **Remove password**
- **Inside the app** — Data → Vaults → **Remove password…**

Yoru asks for the password once, re-encrypts the vault under a fresh unlock key
kept in a file beside it, and tells you what that costs: anyone who can read
your files can then open the vault. Everything in the vault is kept and stays
encrypted on disk. If any part of it fails, the key is taken back and the vault
still opens with the password it had.

Yoru is a local-first desktop study workspace: timers,
tasks, a week planner and habits in one window, plus a **Game** tab that plays
the copy of the game you already own. Studying is what earns you the encounters,
so the time you put in is the time you get back.

Everything runs on your machine. There is no account, no server and no sync, and
your study data lives in an encrypted vault in a folder you choose.

## Install

Download the file for your platform below. Each one is a real application
installer and carries its own Java runtime, so there is nothing else to install.

- **macOS** — the `.dmg`, for Apple silicon. Open it and drag Yoru into
  Applications.
- **Windows** — the `.msi`. Double-click it; no administrator rights needed.
- **Linux** — the `.deb`, for 64-bit Intel and AMD. Install it with
  `sudo apt install ./<the file you downloaded>`.

The macOS build is not signed with a paid Apple Developer certificate, so the
first time you open it macOS says it cannot verify who built it. That is
expected and does not mean the download is damaged: open it once with
**Control-click → Open**, then **Open** again in the dialog, or allow it from
**System Settings → Privacy & Security → Open Anyway**. After that it launches
like any other app.

## What you can do in 1.0

**Study time that adds up.** An open-ended clock in and out — no forced
pomodoro. Fix a clock-out you forgot, or add time you never tracked. Sessions
below a minimum you set are recorded but do not count towards totals. A 52-week
heat map shows the year at a glance, with the colour tiers scaled to your own
daily goal and a rainbow tier for the days that beat it. A focus breakdown shows
where the hours actually went.

**Tasks that behave.** An inbox with due dates, notes, status and colour-coded
tags. Sort it your way or drag rows into your own order, and switch between due
today, open and completed views. A month calendar lets you drag a task onto a
day to plan it, or off again when plans change. Bring work in from a paste, or
import a Notion export and map its columns onto Yoru's fields before anything is
saved.

**A week you can actually plan.** A real week grid with hour rules and a
now-line: drag on empty space to create a plan, drag it across days, drag its
edges to resize — snapping to quarter hours. Recorded time renders where it
happened, with planned blocks outlined behind it, so the plan and the week you
had sit in the same picture. A repeating schedule handles the weeks that look
the same, and the week can start on whichever day you choose.

**Habits, kept honestly.** Daily check-offs with streaks, an editable history
you can correct, and "time since" trackers for something you have quit — with a
backdatable start and restarts that keep the periods that came before.

**The game, inside the study app.** Press Play and the game runs in its own tab,
through your own emulator core and your own game file; Close ends it and the
session is written back. Study time earns encounters drawn from the campaign you
are actually in — the wild tables the game itself uses, opened up as you earn
badges — and the Pokémon you earn are delivered into that save. The party and
the PC come back into Yoru at Close, so the save *is* the collection: you can
rearrange boxes and the party while the game is closed, and each vault keeps its
own save. Nothing is written while the game is running, and every change is one
verified transaction with a backup taken first.

**Music while you work.** Point Yoru at your own audio and it plays while the
timer runs.

**A window that looks the part.** Four themes — a terminal blue, an amber CRT, a
cherry blossom and a warm paper — that travel with your vault. A new mark, drawn
in code, gives the app a real icon in the Dock, the taskbar and the application
menu. This release also carries a full polish pass: one type and spacing scale,
visible focus rings, and every page reviewed as a rendered image in every theme,
including the empty and error states.

## Your data

Your vault is a file you choose on first run, encrypted with AES-256-GCM, with
an optional password. Without one, a key file is written beside the vault —
convenient, but not protection, since anyone who can read both can open your
data. Back the vault up by copying it. **Data → Export vault JSON** writes
everything as plain readable text, so put that export somewhere you are happy
for it to be.

## What this release does not do

- No sync between machines and no automatic updates.
- The macOS build is unsigned, as described under Install.
- Study music accepts WAV, AIFF and AU. The JDK ships no MP3 decoder, so MP3 and
  similar formats will not play.
- Anki, LeetCode and Apple Health are planned, not connected.

## About the game

**Yoru is not affiliated with, endorsed by, or connected to Nintendo, Game Freak
or The Pokémon Company.** The game's name and related marks belong to their
owners and appear here only to describe what Yoru works with.

**Yoru ships no game, no BIOS, no artwork and no music.** No game file, save,
BIOS image, sprite or audio track is bundled with the application, this release
or the repository. Yoru drives your own installation of a separate emulator core
and the game you legally own, and those stay on your machine. A GBA BIOS is
optional. See the [legal notice](NOTICE) for the full wording.

Yoru's own code is released under [The Unlicense](LICENSE): anyone may use it for
anything, with no conditions.
