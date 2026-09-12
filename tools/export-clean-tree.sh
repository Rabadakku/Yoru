#!/bin/sh
# Exports the tree at HEAD as a clean archive, for publishing (#49).
#
#   tools/export-clean-tree.sh [output-dir]        default: build/clean-tree
#
# `git archive HEAD` writes the commit's tracked files and nothing else, so
# anything gitignored — vaults, saves, ROMs, artwork, the local denylist, build
# output — cannot be in the archive even by accident. That structural property,
# not a filter, is what makes the result provably clean.
#
# Nothing outside the output directory is touched, and nothing is ever deleted:
# this reads the repository and writes two files of its own under build/, which
# git already ignores. The same commit always produces the same bytes, so
# running it again is a no-op rather than a change.
#
# See docs/GOING-PUBLIC.md for what to do with the archive.
set -eu
cd "$(dirname "$0")/.."

out="${1:-build/clean-tree}"

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "not a git checkout — run this from the repository" >&2
  exit 1
fi

sha=$(git rev-parse HEAD)
short=$(git rev-parse --short HEAD)
subject=$(git log -1 --pretty=%s)

mkdir -p "$out"
archive="$out/yoru-$short.tar.gz"
listing="$out/yoru-$short.files.txt"

# --format=tar.gz is deterministic here: git pins the entry times to the commit
# and leaves the gzip header unstamped, so the checksum is a property of the
# tree rather than of when it was exported.
git archive --format=tar.gz -o "$archive" "$sha"
git ls-tree -r --name-only "$sha" > "$listing"

files=$(wc -l < "$listing" | tr -d ' ')
members=$(tar -tzf "$archive" | awk '!/\/$/ { n++ } END { print n + 0 }')
size=$(wc -c < "$archive" | tr -d ' ')

if [ "$files" -ne "$members" ]; then
  echo "warning: the commit has $files tracked files but the archive lists $members" >&2
fi

echo "clean tree at $short — $subject"
echo "  $files tracked files  ->  $archive  ($size bytes)"
echo "  the same paths, listed ->  $listing"
echo
echo "everything in the archive is tracked at $short; gitignored and untracked"
echo "files are absent by construction, not by a filter that could miss one."
echo
echo "to check it before publishing (unpacks into a temp dir, changes nothing):"
echo "  tmp=\$(mktemp -d) && tar -xzf '$archive' -C \"\$tmp\" && (cd \"\$tmp\" && ./test.sh)"
echo
echo "next: docs/GOING-PUBLIC.md — publish this tree as a fresh repository."
