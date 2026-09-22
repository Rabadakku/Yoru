# Yoru product goals

Confirmed by the owner on September 22, 2026. This is the product direction for
contributors and AI agents. Read it before designing a feature or changing how
an existing one behaves.

It replaces the earlier direction, which built the game into Yoru. The game was
removed in full (#58): Yoru is a productivity tracker and planner, and nothing
else competes for the room on screen.

## The application the owner wants

> I want this to replace Notion, Obsidian, and other trackers.

A local desktop workspace for the work someone actually has to do: their time,
their week, their tasks and habits, and their writing — in one app, on their own
machine, in one encrypted vault, with no account and no server.

It is judged against the tools it replaces. A feature that is worse than the
Notion or Obsidian equivalent is not finished.

## What Yoru is

1. **Time.** An open-ended clock, per activity, corrected after the fact.
   Totals, streaks, a year at a glance, and a daily goal. Anki study time counts
   like any other time.
2. **The week.** Planned blocks and weekly repeats beside what really happened,
   with tasks that can be dropped onto the day they will be done.
3. **Tasks.** Lists of your own, repeating tasks, properties, saved views,
   sub-tasks and projects — Notion's model, kept fast and keyboard-first.
4. **Habits.** Daily check-offs alongside the tasks you tick off, with streaks,
   consistency and history on their own page.
5. **Pages.** An Obsidian-style Markdown workspace in the vault: a file
   explorer, links between pages, backlinks, search, import and export. Tasks
   link to pages; pages are never forced to be tasks.
6. **Later, on the same rules:** meal planning, a pomodoro timer, goals,
   reviews, reminders, and an AI that can read what is in the vault.

## What Yoru is not

- Not a game, and not gamified. No points, streak-shaming or rewards economy.
- Not a cloud product. No account, no server, no telemetry, no sync.
- Not a mobile app. Yoru is a desktop application.
- Not a plugin platform. Everything ships in the app, built to the same rules.

## How it must feel

- **Like an app Apple would ship.** Calm, quiet, native on a Mac (#63).
- **Glanceable.** Today says what today is, at a glance; detail lives on the
  tab that owns it (#86).
- **Keyboard-first.** Everything reachable without the mouse, with the
  platform's own shortcuts (#49).
- **Honest with data.** Everything you record can be created, edited and
  deleted from the interface (#59), and the vault can be exported as plain
  readable JSON or Markdown at any time.

## The rules that do not move

- **Local and encrypted.** One vault file, AES-256-GCM, optional password,
  backed up before anything destructive.
- **Yoru bundles nothing.** Swing and the JDK only; every picture is drawn at
  runtime. What a user imports stays theirs.
- **No personal data in the repository**, in code, fixtures, docs or GitHub.
  See [AGENTS.md](../AGENTS.md).
- **Store raw data, derive the rest.** See [ARCHITECTURE.md](ARCHITECTURE.md).

## Where the plan lives

The current plan, in order, is issue
[#65](https://github.com/Rabadakku/Yoru/issues/65); [ROADMAP.md](ROADMAP.md)
records what has shipped.
