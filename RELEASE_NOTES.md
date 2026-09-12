# Yoru 1.0.0

The first public release. Yoru is a local-first desktop study workspace: timers,
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
