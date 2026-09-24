# Yoru 1.0.4

This update makes the state of your game save clear on the Collection and Game
pages, so a save Yoru cannot read is never mistaken for an empty vault.

## Fixed

- An unreadable save no longer looks like no save at all. Collection says your
  save is there but cannot be read, gives the reason in plain words — not the
  size of an Emerald save, never saved to, damaged, or an incomplete slot — and
  offers **Export a copy** and **Open Game setup**.
- A save with Pokémon in its party is shown as your game, with the trainer's
  name, badges and play time, even when the game's starter flag is unset. It is
  no longer described as "A new adventure".
- A save interrupted part-way through writing is still shown, but Yoru will not
  change it until the game saves once more.

## Interface

- Collection now leads with when your vault last saved: "Saved in your vault ·
  5 minutes ago", "Saving to your vault…", or "Could not save to your vault"
  with **Retry**.
- With no save, Collection shows one card that says so and opens the game.
- Export is available from both Collection and the Game page, whether or not an
  emulator core or game file is set up.

## Updating

Install the new application over the previous version and keep your existing
workspace. Nothing in your vault or game save is converted.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated test suite passes. New checks cover:

- each unreadable-save reason
- saved-time wording across midnight and time zones
- the save read model, including a failed vault write leaving the saved time unchanged
- Collection and Game with no save, an unreadable save and a pre-starter party, with no emulator core or game file

Collection and Game were reviewed as rendered images in all five themes at the minimum window size.

Still tracked separately:

- repairing study gifts that versions before 1.0.3 wrote with the older signed encoder (#11)
- the Collection redesign (#8)
- the visual system (#9)
- the remaining artwork repair options (#6)
- final cleanup (#12)

This release does not claim full-campaign or clean-machine acceptance on all platforms.

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics or game music. Original Yoru
companion illustrations are included. Study data stays in your local workspace.
