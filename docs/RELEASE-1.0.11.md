# Yoru 1.0.11

A text size for people who need one, the companion artwork withdrawn, and a
long list of fixes from a full review of the code — including one that changed
what the game thinks of every Pokémon your study time has earned.

## New

- **Text size.** Settings → Appearance draws every page at 100%, 125%, 150% or
  200%. The choice belongs to this computer rather than your workspace, so it
  never travels with a vault and older versions still open it. Every page is
  checked at every size, with the longest names any field accepts.
- **Long names give way.** A name too long for its row is shortened with "…"
  and says the whole of itself when you hover, instead of pushing the figures
  beside it out of sight.

## Changed

- **The Waifu theme and its portraits are gone**, at the owner's request, along
  with the picker in Settings and the pictures that appeared beside the timer
  and on other pages. Yoru now ships no images at all. A workspace saved with
  that theme opens as Moonlight and keeps everything else; nothing is lost.

## Fixed

### Your game

- **Study Pokémon were recorded as traded** if your player name used one of the
  game's own symbols — the ellipsis, or curly quotes. Yoru rewrote the name
  through a table that could not spell those, so the name on the gift no longer
  matched yours and the game treated it as someone else's: it could disobey you
  above your badge level and it earned traded experience. Gifts now carry your
  save's own name, byte for byte. Pokémon already delivered keep working; ones
  affected by this now read as changed rather than correct, which is the safe
  direction.
- **Repairing older gifts was refused on real saves.** Emptying a party slot
  erased a byte the game writes there, so Yoru's own check saw the save change
  underneath it.
- **A Pokémon put into a box kept its used PP.** The game restores PP on every
  box placement, and Yoru now does the same for deposits, swaps and moves
  between boxes.
- **Names with apostrophes**, such as Farfetch'd, were written as "FARFETCH?D".

### Your study data

- **Resetting only "Tasks" also deleted every tag.** It never said so. Tags are
  their own section and survive.
- **A task you typed could silently vanish** if its title matched one already
  stored. Typing it is meant; it is always added now.
- **Notion imports.**
  - Two classes setting an assignment of the same name on the same day now
    import as two tasks; the second used to be dropped without a word.
  - An impossible date such as 2/30 is refused with its row number instead of
    being moved to the 28th.
  - Two pages sharing a title no longer give both rows the first one's notes.
  - A 1000-row export imports; it used to be refused at exactly that size.
  - A tab or line break in a title no longer stops the whole import.
- **Deleting a weekly repeat now takes a backup**, like every other deletion.
- **"% matched" counted sessions too short to count anywhere else**, so a block
  could read "20% matched" beside an empty grid.

### Your vault

- **Removing a password could lock you out of the vault.** The unlock key was
  published before the vault was re-encrypted, so a crash or a full disk left a
  key that did not open it — and Yoru then never asked for the password again.
- **The unlock key is now flushed to disk** before the vault is re-encrypted
  under it. A power cut in between could leave the key empty and the vault
  unopenable.
- **Saving a vault after closing it** wrote it under an all-zero key. It is
  refused.
- **Renaming or deleting a vault another window has open** is refused rather
  than splitting it from its backups and key.
- **Exports refused to save** when any name or note held half of a character
  pair — text cut in the middle of an emoji, for example.
- Vault errors no longer show folder paths from inside your home directory.

## Updating

- **If you have 1.0.5 or later:** open **Settings → Updates → Check for updates**.
- **Otherwise:** install the new application over the previous version and keep
  your existing workspace.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated suite passes, and every page renders in all five themes at
desktop and minimum sizes. Each fix above arrived with a test that fails
without it. The review behind this release read every source file except three
UI areas — the main window and vault launcher, the theme and dialog foundation,
and the Today, Settings and task pages — which are reviewed in the next pass.

Not verified for this release: a clean-machine install, a full campaign against
a real core, and native checks against a real libretro core were not repeated.

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics, game music or artwork of any
kind. Study data stays in your local workspace.
