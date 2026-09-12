#!/bin/sh
# Draws the mark out to the files the installers need: PNG, .ico and .icns (#57).
#
#   tools/export-logo-icons.sh [output-dir]        default: build/icons
#
# Nothing here is committed. The mark is source code
# (src/main/java/dev/yoru/ui/Logo.java, with the drawing in LogoExport); these
# are build products and they land under build/, which git ignores. Change the
# mark by changing the code, never by editing a file this writes.
#
# What it needs:
#   - a JDK 22 or later (javac, java) on the PATH — that is all the PNGs, the
#     .iconset and the .ico need, on any platform
#   - macOS's iconutil for the .icns step. It ships with macOS. Anywhere else
#     the script writes everything up to the .iconset and prints the one command
#     to run on a Mac, rather than failing.
set -eu
cd "$(dirname "$0")/.."

out="${1:-build/icons}"

./build.sh
# The exporter sits in the test tree with the other off-screen harnesses, so the
# shipped jar carries the drawing and not a second copy of what it drew
# (DistributionTest checks the jar for harnesses like this one).
javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes \
  src/test/java/dev/yoru/ui/LogoExport.java
java -Djava.awt.headless=true -cp build/classes dev.yoru.ui.LogoExport "$out"

if command -v iconutil >/dev/null 2>&1; then
  for variant in night moonlight; do
    iconutil --convert icns --output "$out/Yoru-$variant.icns" "$out/Yoru-$variant.iconset"
    echo "wrote $out/Yoru-$variant.icns"
  done
else
  echo "no iconutil on this machine — it is part of macOS, so no .icns here."
  echo "everything else is written; the iconsets are ready for one:"
  echo "  iconutil -c icns \"$out/Yoru-night.iconset\""
  echo "  iconutil -c icns \"$out/Yoru-moonlight.iconset\""
fi
