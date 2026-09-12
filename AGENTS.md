# Rules for anyone working on Yoru

This applies to every AI agent — Claude, Codex or anything else — and to
people. Read it before you change anything. Tools that read an agents file
automatically will find it here; everyone else, this is the first stop.

## 1. Never put personal information in the repository

Yoru is going public, and everything committed stays in its history even
after the file is deleted.

- **No personal details about the owner or anyone else.** No names, email
  addresses, usernames, home-directory paths (`/Users/…`, `/home/…`),
  machine names, trainer names or trainer IDs from a real save, course names
  or codes, real task titles, habits, health or medical details, schedules,
  locations, or screenshots of anyone's real data.
- **No personal files.** No vaults, game saves (`.srm`, `.sav`), ROMs, BIOS
  images, exports (Notion, CSV, JSON), keys, API tokens, sprite art, or
  recordings of the game.
- **Refer to people by role:** "the owner", "a user", "the tester". Record
  what was decided and why, not who said it.
- **Tests use invented data only.** Build fixtures from scratch —
  `Gen3Fixture` does it for saves — and never copy values out of a real save,
  vault or export, even one you were shown to work from.
- **Everything you write on GitHub counts too:** commit messages, pull
  request descriptions, issues, comments and reviews.

`./test.sh` runs `PrivacyTest`, which fails the build on email addresses,
home-directory paths, personal file types and stray images in tracked files.
On a machine that has one, it also checks every term in `.privacy-denylist` —
a gitignored list, one term per line, that is never committed. If it fails,
remove the data. Do not weaken the test.

## 2. No game assets

The user supplies their own game, BIOS and artwork; Yoru ships none of them.
ROMs, BIOS, sprites, music, maps and screenshots or recordings of the game
never enter the repository or a release. Tables of facts extracted by the
`tools/` scripts (species numbers, base stats, learnsets) are the exception,
because they are numbers rather than artwork.

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
