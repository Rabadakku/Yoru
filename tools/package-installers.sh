#!/bin/sh
# Builds one installer with jpackage: a macOS .dmg, a Windows .msi or a Linux
# .deb (#56).
#
#   tools/package-installers.sh <dmg|msi|deb> <version> [output-dir]
#   tools/package-installers.sh dmg 1.0.0     -> build/installers/Yoru-1.0.0.dmg
#
# Run it on the platform being packaged for: jpackage only builds for the
# machine it runs on, and the .msi needs the WiX toolset on the PATH. The
# release workflow does exactly that, one runner per installer.
#
# What it needs:
#   - a JDK 22 or later, with jpackage and jlink. jpackage runs jlink itself and
#     copies the runtime it builds into the installer, so the JRE is bundled —
#     the machine Yoru is installed on needs no Java of its own.
#   - macOS's iconutil, for the .icns the .dmg wants. It ships with macOS and
#     tools/export-logo-icons.sh invokes it, so only the .dmg depends on it.
#   - the WiX toolset, for the .msi. Nothing else does.
#
# Nothing here is committed: the icons and the installers are build products
# under build/, which git ignores. Change the mark in code, never in a file
# this writes.
#
# Where Yoru keeps its data: the vault lives in the user's own folder
# (~/Yoru/<name>.vault, or wherever the first run points it) and the caches
# under ~/.yoru — see ui/VaultLauncher and the assets libraries. The installer
# therefore prepares no shared data directory and needs no elevation to write
# one; study data is never inside the installed app.
set -eu
cd "$(dirname "$0")/.."

type="${1:-}"
version="${2:-}"
out="${3:-build/installers}"

if [ -z "$type" ] || [ -z "$version" ]; then
  echo "usage: tools/package-installers.sh <dmg|msi|deb> <version> [output-dir]" >&2
  exit 2
fi
case "$type" in
  dmg|msi|deb) ;;
  *) echo "$type is not one of dmg, msi, deb — jpackage builds only its own platform's" >&2; exit 2 ;;
esac
# jpackage wants the version in digits and dots, and refuses anything else. A
# pre-release tag therefore has to arrive here as 1.0.0, not v1.0.0-rc1; the
# release workflow strips both before calling this.
case "$version" in
  *[!0-9.]*|.*) echo "$version is not a jpackage version (digits and dots only)" >&2; exit 2 ;;
esac

./build.sh
tools/export-logo-icons.sh build/icons

# jpackage takes the main module off the module path. build/ holds both the jar
# and the exploded classes it was made from, and both carry module-info.class
# for dev.yoru — the same module twice on one path is an error, so stage the
# jar on its own and point jpackage there.
module_path="build/jpackage/modules"
rm -rf "$module_path"
mkdir -p "$module_path" "$out"
cp build/yoru.jar "$module_path/"

case "$type" in
  dmg) icon=build/icons/Yoru-night.icns ;;
  msi) icon=build/icons/yoru-night.ico ;;
  deb) icon=build/icons/yoru-night-256.png ;;
esac
if [ ! -f "$icon" ]; then
  echo "$icon is missing: the icon export did not produce what the $type needs" >&2
  exit 1
fi

# The same app on every platform. There is no company behind Yoru, so the
# vendor is the project rather than a person — nothing in this repository names
# anybody (AGENTS.md).
set -- \
  --type "$type" \
  --name Yoru \
  --app-version "$version" \
  --vendor "The Yoru project" \
  --description "A study timer that turns time into a game" \
  --copyright "The Yoru project" \
  --dest "$out" \
  --module-path "$module_path" \
  --module dev.yoru/dev.yoru.ui.YoruApp \
  --add-modules java.desktop,java.prefs,java.net.http \
  --java-options "--enable-native-access=dev.yoru,ALL-UNNAMED" \
  --icon "$icon"

# --add-modules mirrors module-info.java; the launcher also bundles java.base
# and whatever else the module graph pulls in.
#
# --enable-native-access: the Game tab runs the game through the mGBA libretro
# core, which Yoru loads and calls through the FFM API. That is a restricted
# method, so the launcher grants the app module native access up front instead
# of the runtime warning about it on every start. ALL-UNNAMED covers anything
# the app loads outside its own module.

case "$type" in
  dmg)
    # Unsigned on purpose: no Apple Developer certificate is used, so the
    # bundle carries no signature and macOS asks the user to confirm the first
    # open by hand. The release notes say so.
    set -- "$@" \
      --mac-package-identifier dev.yoru \
      --mac-package-name Yoru \
      --mac-app-category public.app-category.education
    ;;
  msi)
    # A fixed upgrade UUID so a later .msi replaces this one instead of sitting
    # beside it, and a per-user install: no elevation prompt, and the Start menu
    # entry and shortcut come with it.
    set -- "$@" \
      --win-menu \
      --win-shortcut \
      --win-dir-chooser \
      --win-upgrade-uuid 8b3f1a1c-6a1e-4e0e-9d2f-3b6c2a7d5e10
    ;;
  deb)
    # Debian wants a lower-case package name and a maintainer address. The
    # address is the reserved documentation one, not a person's: a real mailbox
    # does not belong in a public repository (AGENTS.md).
    set -- "$@" \
      --linux-package-name yoru \
      --linux-menu-group Education \
      --linux-shortcut \
      --linux-deb-maintainer "The Yoru project <maintainers@example.com>"
    ;;
esac

echo "jpackage: $type of Yoru $version"
jpackage "$@"

echo "installer(s) in $out:"
ls -l "$out"
