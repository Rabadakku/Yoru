# Yoru

**A local desktop workspace for time, tasks, habits and Markdown notes.**

Plan your week, track focused work, review your Anki study time and keep linked
notes in one encrypted vault on your own machine. No account, no cloud sync.

[Download](https://github.com/Rabadakku/Yoru/releases/latest) ·
[Features](#features) · [Install](#install) · [Connect Anki](#connect-anki) ·
[Use Yoru with Claude](#use-yoru-with-claude) · [Build from source](#build-from-source)

![Yoru Today in the light theme, with a focus timer, schedule, tasks and habits](https://github.com/Rabadakku/Yoru/releases/download/v1.0.21/yoru-today-light.png)

*Screenshots use invented sample data. The interface shown is from version 1.0.21; 1.1.0 adds to it.*

## What's new in 1.1.0

Ask Claude about your week and have it plan with you: Yoru is now a tool for
Claude Desktop, Claude Code and other MCP apps, with no API key, off until you
switch it on, and never able to delete anything. Tasks gain priorities, your
own statuses and properties; "Essay draft tomorrow 5pm #school !high" makes a
whole task in one line; Cmd/Ctrl-K opens a command palette; and one week of a
weekly repeat can be skipped or moved.

See the [release notes](docs/releases/RELEASE-1.1.0.md), or
[everything that changed](CHANGELOG.md). Further work is tracked in the
[roadmap](docs/ROADMAP.md).

## Features

### Focus and time

Clock in when you start and clock out when you stop, or switch Today's timer to
a pomodoro with its own gentle sounds. A running timer survives closing the
app, and you can correct recorded sessions afterwards. Track multiple activities, set a daily goal and play your
own WAV, AIFF or AU music while the timer runs.

**Today** brings together the timer, today's schedule, tasks, habits and Anki
summary. **Data** holds the activity breakdown, 52-week heat map, recent totals
and Anki history. Heat-map colours scale to your daily goal. Put your activities
in the order you want there, and your tags in the tag manager. Select several
recorded sessions on Data to move them to an activity, shift their times by
minutes, or delete them together. Changes are checked as a whole and backed up
before saving.

### Weekly planning

Compare planned blocks with recorded time in a week grid. Drag to create,
move or resize a plan, add weekly repeats and choose which day starts the week.
Skip or move one week of a repeat without changing the rule.

### Tasks and habits

Tasks have due dates and times, **To do → Doing → Done** status with statuses
of your own, priorities, several colour tags each, properties you define,
search, manual ordering and a month calendar. File them in your own lists,
make them repeat, and drag them between lists. Type a whole task in one line,
"Essay draft tomorrow 5pm #school !high every monday", or press Cmd/Ctrl-K
anywhere. Select several tasks to change or delete them together. Paste a task
list or import a Notion export, then review it before adding anything. Link
tasks to Markdown pages.

Daily habits show their last seven days, streak and 30-day consistency on one
line, with the full history a click away. Time-since trackers show how long
they have run in years, months, days, hours and minutes, and keep every
previous period. Tick today's
habits off beside your tasks, from the mouse or the keyboard. In a daily habit’s
History, browse earlier weeks or use Show date to correct older check-ins. In
a time-since tracker’s History, Add missed restart records a past boundary
without moving the existing ones. History corrections are backed up first.
Use a habit’s menu to move it up or down in its list. Daily habits also offer
Time zone; changing it keeps recorded check-ins on their original dates.

![Yoru Habits in the dark theme, with daily habits and time-since trackers](https://github.com/Rabadakku/Yoru/releases/download/v1.0.21/yoru-habits-dark.png)

### Linked Markdown pages

Write notes in folders with open-page tabs, autosave, undo/redo, search,
reading mode, page links, backlinks, outlines and linked tasks. Import Markdown
files or folders and export notes as plain Markdown.

Pages currently provides a source editor and a separate reading view. Live
preview, attachment storage and several larger workspace features remain on
the roadmap. See [the Pages guide](docs/PAGES.md) for shortcuts and current limits.

![The Pages editor preserves writing space in a narrow window](https://github.com/Rabadakku/Yoru/releases/download/v1.0.21/yoru-pages-narrow.png)

### Anki integration

Read review counts and study time through AnkiConnect. Optionally add completed
study sittings to your tracked time. Saved counts remain available after
restarting Yoru with Anki closed, and automatic retries continue while you work
on other pages. Setup lives in **Settings → Integrations · Anki**.

[Connect Anki](#connect-anki)

### AI assistants

Ask Claude what is due this week, have it plan your study blocks around your
classes, or turn a syllabus or lecture notes into tasks and pages. Yoru works
as a tool for Claude Desktop, Claude Code or any other app that supports the
Model Context Protocol, using your own account with that app: Yoru needs no
API key. Assistants can read your tasks, pages, schedule, time and habits;
with a second switch they can add and edit them too. They can never delete
anything. Both switches are off until you turn them on in **Settings →
Integrations · AI assistants**.

[Use Yoru with Claude](#use-yoru-with-claude)

### Appearance and navigation

- Collapsible sidebar for Today, Tasks, Pages, Habits, Schedule, Data and Settings.
- Five themes: Midnight, Ember, Sakura, Linen and Moonlight.
- Text sizes from 100% to 200%, keyboard shortcuts and visible focus indicators.
- Native macOS menus and an optional **Follow macOS appearance and accent** setting.
  Choosing a theme switches automatic appearance off. Windows and Linux use an
  in-window menu bar.

## Private by design

- **Local storage.** Your workspace lives in an AES-256-GCM encrypted vault,
  with an optional password. No account or cloud sync is required.
- **Your exports.** Export the vault as readable JSON, sessions as CSV and notes
  as Markdown. These exports are plain text; a full vault export includes saved
  integration settings, including the Anki API key.
- **Explicit network access.** Checking for updates contacts GitHub; downloading
  an update retrieves its installer. Anki communication stays on your computer
  at `127.0.0.1:8765`. Yoru does not upload your workspace.
- **AI on your terms.** Yoru never calls an AI service. When you switch
  assistants on, an AI app on your computer can ask Yoru questions; what it
  reads goes to that app's provider as part of your conversation, as anything
  you paste into it would.

Without a password, a random key is kept beside the vault: convenient, but not
protection from someone who can read both files. [SECURITY.md](SECURITY.md)
explains the security model.

## Install

| Platform | Download | Install |
|---|---|---|
| macOS (Apple silicon) | [Latest `.dmg`](https://github.com/Rabadakku/Yoru/releases/latest) | Open it and drag Yoru into Applications. |
| Windows | [Latest `.msi`](https://github.com/Rabadakku/Yoru/releases/latest) | Double-click it. No admin rights needed. |
| Linux (64-bit Intel/AMD) | [Latest `.deb`](https://github.com/Rabadakku/Yoru/releases/latest) | Run `sudo apt install ./<downloaded-file>.deb`. |

Each installer includes its own Java runtime. On first launch, create a
workspace or open an existing vault. A password is optional.

### First launch on macOS

The macOS build is unsigned. If macOS blocks it, use **Control-click → Open**
where available, or **System Settings → Privacy & Security → Open Anyway**.
The source is available below if you prefer to build it yourself.

### Updating

Use **Settings → Updates → Check for updates**. On macOS, Yoru downloads and
verifies the installer, closes your vault, replaces the app and reopens it.
Windows launches the verified installer; Linux provides the install command.
You can also download a release manually. Your vault remains separate from the
installed app, and older vault formats are migrated forward.

## Connect Anki

1. In Anki, open **Tools → Add-ons → Get Add-ons**, enter **2055492159**
   (AnkiConnect), then restart Anki. See the
   [AnkiConnect instructions](https://git.sr.ht/~foosoft/anki-connect).
2. Keep Anki open on the profile you want to track.
3. In Yoru, open **Settings → Integrations · Anki** and switch the integration on.
4. If AnkiConnect requires an API key, enter it and choose **Save key**. Use
   **Test connection** to confirm the connection.
5. Choose whether to **Add my Anki study time to my tracked time**, and select
   a refresh interval of 1, 5 or 15 minutes.

Today shows the summary; Data shows the 30-day review chart. When Anki is closed,
Yoru displays the saved counts with their fetch time and retries automatically
while the vault is open, including when Today is not visible.

Habits automatically shows your Anki streak and best run. Any date with at least
one imported review counts, whether or not it becomes tracked study time. An
unfinished today keeps yesterday’s streak. Offline, the saved streak is dated
as of its last sync; unknown days do not count as missed reviews. History beyond
the 30-day chart is retained, and switching profiles replaces the history.
An older vault fills in earlier review days on its next successful Anki sync.

### How study time is counted

Answers less than ten minutes apart form a sitting. After ten minutes without
another answer, a completed sitting can be added under the **Anki** activity.
Its duration comes from Anki's recorded answer times. Yoru catches up on the
last seven days and avoids importing a sitting twice. Sittings that overlap
clocked time, or fall below your minimum session length, are skipped.

Rename the Anki activity, or move a sitting to another activity, and later
sittings follow the latest one. A deleted sitting from the last week can be
imported again while time importing remains enabled; edit it to correct it.

### What is stored

Yoru only reads from Anki. The encrypted vault stores the integration settings
and API key, the last profile/count summary and fetch time, and any imported
study sessions. It does not store card content, answers or deck names.
Closing the app preserves the saved summary and settings. Switching the
integration off stops automatic reads; **Clear key** removes the saved API key.
Custom ports and remote Anki instances are not supported.

## Use Yoru with Claude

1. In Yoru, open **Settings → Integrations · AI assistants** and switch it on.
   Tick **Let assistants make changes** if you want Claude to add and edit
   things, not only read them.
2. **Claude Desktop:** choose **Add Yoru to Claude Desktop**, then quit and
   reopen Claude Desktop. Yoru keeps everything else in Claude Desktop's
   settings and leaves a copy of them as they were. To do it by hand instead,
   choose **Copy settings** and paste into Claude Desktop's **Settings →
   Developer → Edit Config**.
3. **Claude Code:** choose **Copy command** and run it in a terminal. It is
   `claude mcp add yoru -- <path to Yoru> --mcp`.
4. Keep Yoru open with your vault unlocked while you chat. Claude asks before
   each tool it uses, unless you tell it to always allow one.

Try "What's due this week, and how much did I study?", "Plan tomorrow around
my classes", or "Turn these lecture notes into a page and tasks".

### What an assistant can do

| Reads | Changes, with the second switch |
|---|---|
| Today at a glance: the timer, tracked time, what is due, habits | Add a task, or one from a quick-add line |
| Tasks, filtered by status, list, tag, dates or words | Edit a task's fields, status and links |
| Pages: search, list and read | Create a page, add to it, or replace a passage |
| The schedule: plans, weekly repeats, recorded time | Plan a block, record time, start and stop the timer |
| Time totals by activity and day, and streaks | Check off a habit |
| Habits: streaks, consistency and the last week | |

Nothing can be deleted by an assistant. The vault is backed up before an
assistant's first change, and again every fifteen minutes of changes after
that. **Settings** lists the changes assistants made while Yoru was open.

### How it connects

Claude's app starts a second copy of Yoru with `--mcp`. That copy opens no
window and no vault: it passes each request to the Yoru you have open, through
a socket in a folder only your account can open, with a key that changes every
time Yoru starts. Nothing listens on the network. When Yoru is closed, locked,
or has assistants switched off, Claude is told so and how to fix it.

## Build from source

Requires **JDK 22 or later**. The application uses Swing and the JDK, with no
third-party libraries or dependency downloads.

```bash
./run.sh      # build and launch
./build.sh    # compile and package the jar
./test.sh     # run the isolated test suite
```

On Windows, run `build.cmd`, then `java -jar build/yoru.jar`.
To build an installer on its target platform, use
`tools/package-installers.sh dmg|msi|deb <version>`.

### Interface previews

After `./test.sh` has compiled the test tools, render synthetic sample pages:

```bash
java -Djava.awt.headless=true -Duser.home=path/to/empty-dir \
  -Djava.util.prefs.PreferencesFactory=dev.yoru.ui.TestPreferencesFactory \
  -cp build/classes dev.yoru.ui.Preview build/preview
```

Use `dev.yoru.ui.DialogPreview` to render representative dialogs.
`TextFitTest` checks layouts through 200% text. The optional `DialogFocusTest`
and macOS `NativeDesktopTest` need a real display; use the same isolated home
and preferences options, omit headless mode and add `-ea` when running them.

## Contributing

Read [AGENTS.md](AGENTS.md), [CONTRIBUTING.md](CONTRIBUTING.md) and the
[collaboration guide](docs/COLLABORATION.md) before making changes. Use a branch,
coordinate through the issue tracker and run `./test.sh` before pushing.
Never commit personal data, vaults, keys or screenshots of real workspaces.

- [Product goals](docs/PRODUCT-GOALS.md)
- [Roadmap](docs/ROADMAP.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Data model and migrations](docs/DATA-MODEL.md)
- [Security](SECURITY.md)

## License

Yoru is released into the public domain under the [Unlicense](LICENSE).
It bundles no third-party code, fonts, images or audio. Interface graphics are
drawn at runtime; imported files and music remain on your machine.
