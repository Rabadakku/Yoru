<p align="center">
  <img src="docs/media/hero.jpg" alt="Yoru: study time that turns into play. The Today page, with a focus timer running, over a moonlit lake." width="100%">
</p>

<p align="center">
  <b>A local-first workspace for the work you actually have to do.</b><br>
  Track focus and time, plan your week, keep tasks and habits, and write linked Markdown pages —
  in one app, on your own machine, in an encrypted vault.
</p>

<p align="center">
  <a href="https://github.com/Rabadakku/Yoru/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Rabadakku/Yoru?style=for-the-badge&label=release&color=8FD9DA&labelColor=0B1020"></a>
  <a href="#install"><img alt="macOS, Windows and Linux" src="https://img.shields.io/badge/macOS%20%C2%B7%20Windows%20%C2%B7%20Linux-8FD9DA?style=for-the-badge&labelColor=0B1020"></a>
  <a href="LICENSE"><img alt="Public domain: the Unlicense" src="https://img.shields.io/badge/license-Unlicense-8FD9DA?style=for-the-badge&labelColor=0B1020"></a>
  <a href="#private-by-design"><img alt="Your data stays on your machine" src="https://img.shields.io/badge/data-stays%20on%20your%20machine-8FD9DA?style=for-the-badge&labelColor=0B1020"></a>
</p>

<p align="center">
  <a href="https://github.com/Rabadakku/Yoru/releases/latest"><b>Download</b></a> ·
  <a href="#features">Features</a> ·
  <a href="#install">Install</a> ·
  <a href="#faq">FAQ</a>
</p>

---

## How it works

Clock in when you start working and out when you stop. That is the whole ritual. Everything
else — the week's plan, the tasks due today, the habits you are keeping, the pages you are
writing, the Anki reviews you did — hangs off the time you actually spent.

Nothing leaves your computer. There is no account, no server and no sync: one encrypted file
you can back up like any other.

## Features

### ⏱️ A focus timer that stays out of your way

<img src="docs/media/today.png" alt="The Today page: a running focus timer, the daily goal bar and today's plan" width="100%">

Clock in, clock out, and correct either one afterwards. There is no forced pomodoro. A running
timer survives closing the app, the daily goal fills as you go, and you can track as many
activities as you like. Beside the clock is the day itself: what you planned, and what is
left.

### 📊 A year at a glance

<img src="docs/media/heatmap.png" alt="Today, last 7 days and current streak tiles above a 52-week heat map" width="100%">

A 52-week heat map whose colours scale to *your* daily goal, with a rainbow tier for days that
beat it. Streaks, the last seven days and a breakdown of where your time went sit alongside it.

### 🧠 Anki counts too <sup>new in 1.0.15</sup>

<p align="center">
  <img src="docs/media/anki.png" alt="The Anki card: 120 reviews today, 19 minutes studied in Anki and 19 minutes in tracked time" width="70%">
</p>

Connect Anki and every finished sitting is added to your tracked time, at the length Anki
itself reports. It reaches your totals, heat map and streak like any other session.
Yoru only reads from Anki, and never counts the same minute twice.
[Set it up →](#connect-anki)

### 🗓️ Plan the week, then see how it went

<img src="docs/media/schedule.png" alt="The week grid, with a plan lane and an actual lane for each day" width="100%">

A real week grid with a plan lane and an actual lane for every day. Drag on empty space to plan
a block, drag it to move it, drag its edges to resize it, and repeat it every week. The week
starts on whichever day you choose.

### ✅ Tasks and habits

<table>
  <tr>
    <td width="50%"><img src="docs/media/tasks.png" alt="The Tasks table with statuses, tags and due dates"></td>
    <td width="50%"><img src="docs/media/habits.png" alt="Habits with streaks and a four-week check-off grid"></td>
  </tr>
</table>

**Tasks** have due dates, a *To do → Doing → Done* status, colour tags, search, drag-to-reorder
and a month calendar you can drag them onto. Paste a list from an AI chat or import a Notion
export, and review it before anything is added. **Habits** have daily check-offs, streaks, an
editable 28-day history, and "time since" trackers for something you have quit.

### 🎨 Make it yours

<img src="docs/media/themes.png" alt="The Today page in all five themes: Midnight, Ember, Sakura, Linen and Moonlight" width="100%">

Five themes, text at 100–200%, and study music from your own WAV, AIFF or AU files while the
timer runs.

### 🔒 Private by design

- **No account, no server, no sync.** Everything lives in an encrypted vault (AES-256-GCM) on
  your own computer, with an optional password.
- **Portable.** Export the whole vault as readable JSON, or your sessions as CSV, whenever you like.
- **Offline unless you ask.** The only time Yoru goes online is when you press
  *Check for updates*. Anki is read on your own computer, at `127.0.0.1`.

[SECURITY.md](SECURITY.md) explains what the vault does, and does not, protect.

## Install

| Platform | Download | Then |
|---|---|---|
| **macOS** (Apple silicon) | [Latest `.dmg`](https://github.com/Rabadakku/Yoru/releases/latest) | Open it and drag Yoru into Applications. |
| **Windows** | [Latest `.msi`](https://github.com/Rabadakku/Yoru/releases/latest) | Double-click it. No admin rights needed. |
| **Linux** (64-bit Intel/AMD) | [Latest `.deb`](https://github.com/Rabadakku/Yoru/releases/latest) | `sudo apt install ./<the file you downloaded>` |

Each installer carries its own Java runtime, so there is nothing else to install. On first
launch, create a workspace. A password is optional. Without one, a random key is kept beside
the vault, which is convenience rather than protection.

<details>
<summary><b>macOS says it can't verify who made Yoru</b></summary>

The macOS build is not signed with a paid Apple Developer certificate, so macOS cannot verify
who built it the first time you open it. That is expected, and it does not mean the download is
damaged. Open it once with **Control-click → Open**, then **Open** again in the dialog, or allow
it from **System Settings → Privacy & Security → Open Anyway**. After that it opens like any
other app. The whole source is right here if you would rather build it yourself.

</details>

<details>
<summary><b>Updating</b></summary>

**Settings → Updates → Check for updates** asks GitHub for the newest release, and only when you
press it. On a Mac, Yoru downloads the `.dmg`, checks it against the release's SHA-256, closes
your vault, replaces itself and reopens. On Windows it runs the verified `.msi` after
closing. On Linux it verifies the `.deb` and gives you the one `sudo apt install` command to run.
Versions before 1.0.5 have no updater, so install 1.0.5 or later by hand once. Your workspace
stays as it is: vaults are migrated forward and never reset.

</details>

## Connect Anki

1. In Anki, open **Tools → Add-ons → Get Add-ons**, enter **2055492159** (AnkiConnect) and
   restart Anki. See the [official AnkiConnect instructions](https://git.sr.ht/~foosoft/anki-connect).
2. Keep Anki open on the profile you want to track.
3. In Yoru, scroll to **Today → Anki reviews** and click **Connect Anki**. Enter an API key only
   if you set one in AnkiConnect.

<details>
<summary><b>How Anki time is counted</b></summary>

Answers less than ten minutes apart are one *sitting*. Once nothing has been answered for ten
minutes, the sitting is added as a session under an activity called **Anki**. It lasts as long
as the answer times Anki logged, which is the figure Anki itself reports as time studied.
Connecting catches up on the last seven days. A sitting is never added twice, and one that
overlaps time you clocked in Yoru, or is shorter than your minimum session, is left out. The
card shows Anki's minutes for today beside how many of them are in your tracked time.

Rename the Anki activity, or move a sitting to another activity, and later sittings follow the
latest one. Edit a sitting rather than deleting it: while Anki is connected, a sitting deleted
from the last week is read again and put back.

The card refreshes every minute while Today is on screen, or when you press **Refresh**. If Anki
is unavailable, the last successful snapshot stays on screen, marked with its time. Switching
Anki profiles during a refresh is refused, so two profiles' counts are never mixed. **Disconnect** clears
the snapshot and the key, and so do closing or switching the vault.

Yoru reads the active profile name, review totals and the time of each answer from AnkiConnect
on `127.0.0.1:8765`. It never changes anything in Anki. The only Anki data kept in the vault is
the start and end of each sitting: no card content, answers or deck names. The API key stays in
memory for as long as the window is open. Custom ports and remote Anki instances are not
supported.

</details>

## FAQ

<details>
<summary><b>Where is my data, and does it survive updates?</b></summary>

In an encrypted vault on your own computer: AES-256-GCM, with an optional password, and no
account or server. Vaults are migrated forward and never reset, and migration is tested against
vaults written by older builds. If you want an extra escape hatch before upgrading, export the
vault as JSON, which anything can read.

</details>

<details>
<summary><b>Why Java 22?</b></summary>

Yoru is built against the Java 22 language and library level and ships as a Swing app with no
third-party libraries. The installers carry their own runtime, so this only matters if you
build from source.

</details>

## Build from source

Requires **JDK 22 or later**. Swing and the JDK only: no build system and no network access.

```bash
./run.sh      # build and launch
./build.sh    # compile and jar only
./test.sh     # the whole suite, headless
```

On Windows, run `build.cmd`, then `java -jar build/yoru.jar`.

<details>
<summary><b>More for contributors</b></summary>

The build produces `build/yoru.jar`, with `dev.yoru.ui.YoruApp` as its entry point.
`tools/package-installers.sh dmg|msi|deb <version>` builds an installer for the platform it
runs on. The suite covers the domain, persistence and schema migration, the Markdown parser and
link rules, Anki, and the rendered interface. `PrivacyTest` fails the build if personal data or
a stray image reaches a tracked file.

One check needs a real display, so `./test.sh` does not run it. After it has built the tests:

```bash
# Keyboard focus in real dialogs; skipped headless.
java -ea -cp build/classes dev.yoru.ui.DialogFocusTest
```

`./test.sh` already checks every page at every text size. To look at the pages as PNGs,
`-Dtextfit.size=200` limits the run to one size:

```bash
java -Djava.awt.headless=true -Duser.home=path/to/empty-dir \
     -cp build/classes dev.yoru.ui.TextFitTest path/to/render-dir
```

The pictures in this README are drawn by `dev.yoru.ui.ReadmeMedia` from an invented vault:

```bash
java -Djava.awt.headless=true -Duser.home=path/to/empty-dir -Duser.timezone=UTC \
     -cp build/classes dev.yoru.ui.ReadmeMedia docs/media
```

</details>

## Contributing

Several people and AI agents work on this repository at once. **Read
[CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md) before pushing:** work on a
branch, never commit straight to `main`, and run `./test.sh` before you push.

- [docs/ROADMAP.md](docs/ROADMAP.md): the plan, status and what is next
- [docs/PRODUCT-GOALS.md](docs/PRODUCT-GOALS.md): what Yoru is for, and what it is not
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): module boundaries and the rules that keep them
- [docs/DATA-MODEL.md](docs/DATA-MODEL.md): storage; read it before touching the vault
- [SECURITY.md](SECURITY.md): the vault, and what it does not protect

## License and legal

Yoru is released into the public domain under the **[Unlicense](LICENSE)**: anyone may use it
for anything, with no conditions.

Yoru bundles no third-party code, no fonts and no images: it is Swing and the JDK, and every
picture in the interface is drawn at runtime. Music and any files you import stay yours and
stay on your machine.

### Markdown pages

The Pages workspace keeps Markdown notes in the encrypted vault, with folders,
reading mode, search, page links and linked tasks. See [Pages](docs/PAGES.md)
for usage, import/export and the current feature limits.
