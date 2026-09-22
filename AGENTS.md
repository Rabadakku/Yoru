# Rules for anyone working on Yoru

This applies to every AI agent — Claude, Codex or anything else — and to
people. Read it before you change anything. Tools that read an agents file
automatically will find it here; everyone else, this is the first stop.

## 1. Never put personal information in the repository

Yoru is going public, and everything committed stays in its history even
after the file is deleted.

- **No personal details about the owner or anyone else.** No names, email
  addresses, usernames, home-directory paths (`/Users/…`, `/home/…`),
  machine names, course names or codes, real task titles, habits, pages,
  health or medical details, schedules, locations, or screenshots of anyone's
  real data.
- **No personal files.** No vaults, exports (Notion, CSV, JSON, Markdown),
  keys or API tokens.
- **Refer to people by role:** "the owner", "a user", "the tester". Record
  what was decided and why, not who said it.
- **Tests use invented data only.** Build fixtures from scratch —
  and never copy values out of a real vault or export, even one you were shown
  to work from.
- **Everything you write on GitHub counts too:** commit messages, pull
  request descriptions, issues, comments and reviews.

`./test.sh` runs `PrivacyTest`, which fails the build on email addresses,
home-directory paths, personal file types and stray images in tracked files.
On a machine that has one, it also checks every term in `.privacy-denylist` —
a gitignored list, one term per line, that is never committed. If it fails,
remove the data. Do not weaken the test.

## 2. Nothing is bundled

Yoru ships no third-party code, fonts, images or audio: Swing and the JDK
only, with every picture drawn at runtime. Anything a user imports — music,
Markdown, an export — belongs to them and never enters the repository.

## 3. How work happens here

- Start with [docs/PRODUCT-GOALS.md](docs/PRODUCT-GOALS.md), then the release
  plan in [docs/ROADMAP.md](docs/ROADMAP.md) and the working agreement in
  [docs/COLLABORATION.md](docs/COLLABORATION.md).
- Work on a branch named for who you are (`claude/…`, `codex/…`). Never
  force-push a shared branch.
- `./test.sh` passes before you push, and new behaviour has a test.
- Commit messages say why; the diff already says what.
- When you stop, update the issue and `docs/ROADMAP.md` so the next person
  can pick up where you left off.
