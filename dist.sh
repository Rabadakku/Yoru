#!/bin/sh
# Builds a folder that can be handed to someone else (#16).
#
# Deliberately not the personal build: no vault and nothing imported.
# DistributionTest asserts that about the jar this produces, because "we did not
# mean to include it" is not a guarantee.
set -eu
cd "$(dirname "$0")"

./build.sh

stamp=$(date +%Y-%m-%d)
out="build/dist/Yoru-$stamp"
rm -rf "$out"
mkdir -p "$out"
cp build/yoru.jar "$out/Yoru.jar"

cat > "$out/Start Yoru.command" <<'LAUNCH'
#!/bin/sh
# Double-click to run. Needs Java 21 or later.
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 or later is required. Install it from https://adoptium.net and try again."
  read -r _ 2>/dev/null || true
  exit 1
fi
exec java -jar Yoru.jar
LAUNCH
chmod +x "$out/Start Yoru.command"

cat > "$out/Start Yoru.bat" <<'LAUNCH'
@echo off
rem Double-click to run. Needs Java 21 or later.
cd /d "%~dp0"
where java >nul 2>nul || (
  echo Java 21 or later is required. Install it from https://adoptium.net and try again.
  pause
  exit /b 1
)
java -jar Yoru.jar
LAUNCH

cat > "$out/READ ME FIRST.txt" <<'READ'
Yoru — a local study workspace
==============================

Running it
----------
macOS / Linux : double-click "Start Yoru.command"
Windows       : double-click "Start Yoru.bat"
Anywhere      : java -jar Yoru.jar

Java 21 or later is required. https://adoptium.net

Your data
---------
Everything lives in a vault file you choose on first run, on this computer.
There is no account, no server and no sync. If you pick the password-free
option, a companion ".local-key" file is written next to the vault — keep the
two together, because either one alone is useless.

Back it up by copying the vault (and its key file, if you have one). There is
also Data -> Export vault JSON, which writes everything as plain readable text.
That export is NOT encrypted, so put it somewhere you are happy for it to be.

What this build does not do
---------------------------
No sync between machines. No installer and no code signing, so macOS will warn
that it came from an unidentified developer. No automatic updates.
READ

printf '%s\n' "$out"
