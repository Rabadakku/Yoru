# Collaborating on Yoru

For people and AI agents working this repository **at the same time**.

Agents do not share memory. Each one starts cold, cannot see what another is
mid-way through, and will happily rebuild a screen someone else finished an hour
ago — or overwrite it. Everything here exists to make the work visible in places
a cold reader will actually look: the remote, the issue tracker, and the commit
log.

---

## 0. Reference material already gathered

Start with **[PRODUCT-GOALS.md](PRODUCT-GOALS.md)**: what Yoru is and is not,
as the owner confirmed it. It supersedes earlier directions, including the
game, which was removed in #58.

Some questions have been answered once already; re-deriving them costs a session
and risks a different answer. Before researching, check:

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — the packages, the rules that hold
  them together, and how AI assistants reach the running app.
- **[DATA-MODEL.md](DATA-MODEL.md)** — the vault schema and every migration.
- **[FOCUSPOMO-NOTES.md](FOCUSPOMO-NOTES.md)** — FocusPomo walked screen by
  screen: the reference for the timer (#62) and for any "how do other trackers
  do this" question.
- **[history/](history/)** — how Yoru got here: the handoff log, the game-era
  notes and old plans. Read it for context; do not take it as current.

If you find one of these wrong, fix the document in the same commit as the code.
A stale note is worse than no note, because it gets believed.

## 1. Before you touch anything

Read [AGENTS.md](../AGENTS.md) first, above all its privacy rule: nothing
personal goes into the repository or onto GitHub, ever. Then four commands,
in this order. They take seconds and have already prevented one
loss of ~1000 lines.

```bash
# 1. Am I even in a git repo? An agent once worked for hours in a plain folder.
git rev-parse --is-inside-work-tree

# 2. What does the remote actually have? Do not trust "it was pushed".
gh api repos/Rabadakku/yoru/branches --jq '.[] | "\(.name) -> \(.commit.sha[0:8])"'

# 3. Is someone already on this?
gh issue list --state open

# 4. Is there a branch that looks like the thing I am about to build?
git fetch --all --prune && git branch -r
```

If your working copy is **not** a git repo, do not `git init`. Clone the real
history and copy your files onto it, or the branch can never merge cleanly.

---

## 2. Claim the work

Before writing code, say so on the issue — one line is enough:

> Starting this now, on `claude/task-board`. — Claude, 2026-09-09

Then create the branch immediately and push it **empty or nearly so**, so it
shows up in `git branch -r` for anyone who checks. An unpushed branch is
invisible and therefore does not exist.

Branch names carry the author: `claude/…`, `codex/…`, `owner/…`.

---

## 3. Split by area, not by file

The contention is concentrated in a few files. If two agents work at once,
divide so you do not both edit these:

| File | Why it is hot |
|---|---|
| `ui/YoruApp.java` | Every page hangs off it. The most likely conflict by far. |
| `domain/Model.java` | Any new field touches it. |
| `persistence/EncryptedVault.java` | Always changes with `Model`. |
| `ui/Theme.java` | Any palette or widget change. |
| `application/Tracker.java` | Every change to the vault passes through it. |

Safe to work on in parallel, because they are largely self-contained:
`ui/TasksPanel`, `ui/HabitsPanel`, `ui/ScheduleGrid`, `ui/PagesPage`,
`ai/*`, and anything under `src/test`.

**If your change needs a new stored field, say so on the issue first.** Two
agents adding fields to `Model` and `EncryptedVault` at once produces a schema
that decodes into garbage rather than an error, because the format is positional.

---

## 4. Push early and often

Push the branch as soon as something compiles. Not at the end.

The failure mode this prevents is specific and has happened: a session ends
abruptly, and finished work that was never committed exists only in a local
folder that nobody else can see and that the author believes is safe.

Commit messages explain **why**. The diff already says what. Note anything
surprising you hit — the next agent will hit it too, and the commit log is the
only place they will find it. Real examples from this repo:

- Aqua ignores `UIManager` colours, which is why the theme silently did nothing
- `JSpinner` under `PERSIST` keeps unparseable text, so reading `getText()` still crashed
- a constructor that silently drops a field

---

## 5. Handing off

When you stop — finished or not — leave the next reader a note **on the issue**,
not just in your own transcript:

```
Handoff:
- Done: ScheduleGrid renders sessions and planned blocks; drag to create/move/resize.
- Not done: recurring weekly blocks (needs a Model change, see #12).
- Watch out: State rejects overlapping blocks, so dragging onto an existing
  block surfaces an error dialog rather than silently merging.
- Branch: claude/schedule-grid @ 7a78e9b, tests green (127 checks).
```

If you discovered something that changes the plan — a feature that cannot work
as specified, a constraint nobody knew about — **write it into the docs**, not
just the chat. Two examples already recorded so nobody re-litigates them:

- A ChatGPT subscription grants no OpenAI API access (`README.md`, `ROADMAP.md`)
- Nothing is bundled: no third-party code, fonts, images or audio (`AGENTS.md`)

Chat scrollback is not documentation. The next agent will not have it.

---

## 6. When you collide

- **Someone pushed to your branch**: `git pull --rebase`, re-run `./test.sh`.
- **`main` moved under you**: rebase onto it, do not merge backwards.
- **Two branches changed `Model.java`**: whoever lands second re-applies their
  field by hand and re-runs `SchemaTest`. Do not resolve a positional binary
  format by accepting one side wholesale.
- **Never force-push a shared branch.** If history genuinely needs rewriting,
  say so on the issue first.

---

## 7. What "done" means here

A change is done when all of these hold:

1. `./test.sh` passes, and **new behaviour has a test**. Features recovered from
   an interrupted session shipped with zero coverage and two carried real bugs.
2. UI changes have been **looked at**, via `Preview` and — if dialogs are
   involved — `DialogPreview`. Rendering is not optional; both contrast
   regressions in this repo were invisible in the diff and obvious in the PNG.
3. The branch is pushed.
4. The issue is updated, and `docs/ROADMAP.md` reflects reality.

"It compiles" is not done. Neither is "it looks right in my head."
