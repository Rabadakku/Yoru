# Going public: how the history is made clean

Status: **decision recorded 2026-09-11**. This document records the
recommendation for [#49] and the exact steps to carry it out. The owner makes
the final call at release time, and nothing here has been done yet — no history
has been rewritten, no branch deleted, no remote changed.

[#49]: https://github.com/Rabadakku/yoru/issues/49

## The decision

**Publish a fresh repository from the scrubbed tree at `main`, rather than
rewriting the history of the existing one.**

The tree is already clean: `AGENTS.md` states the rules, `PrivacyTest` enforces
them on every tracked file, and the local `.privacy-denylist` catches the
owner's own identifiers. So the only open question was the history behind that
tree, and the recommendation is not to try to repair it.

## Why, and the trade-off

A history rewrite (`git filter-repo`, BFG) edits the objects in place, but the
old commits are still reachable — from forks, from pull-request refs, from
reflogs, from every clone anyone already has, and from GitHub's own caches
until Support purges them. A rewrite can be done correctly, but "correct" means
a coordinated force-push, asking every fork to delete and re-clone, and a
Support request; and even then the guarantee is "probably", not "provably".

A fresh repository has no old objects to reach at all: the first commit *is*
the whole history. That is the difference that matters here.

| | History rewrite | Fresh repository (**recommended**) |
|---|---|---|
| Old commits reachable | Forks, PR refs, reflogs, caches, clones | Do not exist |
| Guarantee | Probably clean | Provably clean |
| Force-push needed | Yes, on every shared branch | No |
| Issues, PRs, stars, watchers | Kept | Recreated or let go |
| Failure mode | A missed ref silently republishes the data | Nothing to miss |
| Owner's effort | High and ongoing | One export and one push |

The cost is real: the issues, pull requests and stars do not carry over. The
roadmap and this plan live in the tree, so the work is not lost — the tracker
is. That is the price of a guarantee rather than a best effort, and it is why
the owner, not an agent, makes this call.

## Before publishing

1. `./test.sh` is green on `main` — `PrivacyTest` above all.
2. The working tree is clean (`git status`), so the export matches the commit.
3. `.privacy-denylist` exists on this machine and lists the owner's own
   identifiers. It is gitignored and never leaves the machine; `PrivacyTest`
   checks the tracked files against it whenever it is present.
4. `LICENSE` and `NOTICE` are in place (#50).

## Steps

### 1. Export the clean tree

```sh
tools/export-clean-tree.sh
```

This writes `build/clean-tree/yoru-<sha>.tar.gz` — `git archive` of `HEAD`,
which contains tracked files only, so anything gitignored (vaults, saves, ROMs,
artwork, the denylist, build output) cannot be in it. It also writes the list of
paths it contains. The output is deterministic: the same commit always produces
the same bytes. It writes nothing outside `build/` and never deletes anything.

### 2. Verify the export

Unpack it somewhere temporary and run the suite inside it. This is the same
tree `main` already passes on, so it is a check that the export mechanism lost
nothing, not a new test.

```sh
tmp=$(mktemp -d)
tar -xzf build/clean-tree/yoru-<sha>.tar.gz -C "$tmp"
cd "$tmp" && ./test.sh
```

### 3. Make it a repository with one commit

`git init` inside the unpacked tree; the tree's own `.gitignore` still applies,
so ignored files stay out there too.

```sh
cd "$tmp"                       # the unpacked tree from step 2
git init -b main
git add -A
git commit -m "Yoru v1.0.0: initial public release"
git log --oneline               # exactly one commit
git rev-list --all --count      # 1
```

### 4. Push it to the new, empty repository

Create an empty repository (no README, no license, no template — anything
GitHub adds would be a second root) and push to it.

```sh
git remote add origin <new-repo-url>
git push -u origin main
```

Nothing from the old repository is pushed, so no pre-clean commit is reachable
from the new one.

### 5. Afterwards

- Tag `v1.0.0` in the new repository as part of #53.
- Turn on secret scanning and push protection, and add branch protection.
- The owner decides what happens to the old repository: keeping it private,
  archiving it, or deleting it. Deleting it is irreversible and is the owner's
  call alone; no agent should do it.

## If the owner chooses a rewrite instead

It is their call, and the steps would be: `git filter-repo` to strip the
offending paths, expire all reflogs and run `git gc --prune=now`, force-push
every branch and tag, ask every fork to delete and re-clone, and open a GitHub
Support request to purge cached views. The result is usually clean and never
provably so. If that path is taken, do it at release time, on a fresh clone,
with a backup of the old repository first.

## What this document does not do

It does not rewrite anything, delete any branch or reference, change any
remote, or publish anything. Those are release-time actions, and the last of
them is the owner's.
