# Yoru 1.0.5

Yoru can now update itself from Settings.

## New: updates from Settings

- **Settings → Updates → Check for updates** asks GitHub for the newest Yoru
  release. Nothing reaches the network until you press it.
- **On a Mac with Apple silicon,** **Download and install** fetches the new
  `.dmg` and checks it against the SHA-256 GitHub publishes for the release.
  Yoru then closes your game and vault, replaces itself and reopens.
- **On Windows,** Yoru runs the verified `.msi` after it closes. Open Yoru again
  when the installer finishes.
- **On Linux,** Yoru downloads and verifies the `.deb` into your Downloads folder
  and shows the one `sudo apt install` command to run.
- A download whose size or checksum does not match the release is deleted and
  never run. A copy of Yoru built from source is never replaced.
- Installing waits until the game is closed, because Yoru closes to finish the
  update.

Earlier versions have no updater. Install 1.0.5 by hand once; later versions can
then be installed from Settings.

## Also included

Everything in 1.0.4:

- Collection and the Game page say when your vault last saved.
- A save Yoru cannot read is explained, with **Export a copy** and **Open Game
  setup**.

## Updating

Install the new application over the previous version and keep your existing
workspace. Nothing in your vault or game save is converted.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated test suite passes. The updater's tests never reach the
internet; a local server stands in for GitHub. They cover:

- version comparison
- reading the release feed and its errors
- which installer each platform takes
- refusing downloads from anywhere but the Yoru releases
- deleting downloads whose size or SHA-256 disagree

On macOS, a real disk image is staged and a wrong version is refused. The bundle
swap and its rollback also run against a stand-in app.

Not yet exercised on a real install:

- the Windows installer path
- a full update from one published release to the next

Both will first run when updating from 1.0.5 to the next release.

Still tracked separately:

- repairing study gifts that versions before 1.0.3 wrote with the older signed encoder (#11)
- the Collection redesign (#8)
- the visual system (#9)
- the remaining artwork repair options (#6)
- final cleanup (#12)

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics or game music. Original Yoru
companion illustrations are included. Study data stays in your local workspace.
