# Yoru 1.0.6

This update repairs Pokémon earned by studying that older versions of Yoru put
into your game in a layout the game reads wrongly.

## Fixed

- Before 1.0.3, Yoru wrote about half of all study gifts with their data in the
  wrong internal order: those whose personality value falls in the upper half of
  its range. The game accepted them but showed a different Pokémon.
- If your save holds any of these gifts, the **Game** page now lists them by
  species, level and box slot. **Repair** rewrites each one exactly as Yoru writes
  gifts today, keeping its identity, its trainer and where it is.
- Repair backs up your vault first, and it runs only while the game is closed.
- Yoru rewrites a gift only when it can prove the gift is byte for byte what the
  older version wrote. Moving it to another box doesn't matter.
- A gift the game has changed in any way is left exactly as it is, and the Game
  page names it. That includes a gift that gained experience or was renamed.
- Running the repair again changes nothing.

## Updating

- **If you have 1.0.5:** open **Settings → Updates → Check for updates**. This is
  the first release the updater can install.
- **Otherwise:** install the new application over the previous version and keep
  your existing workspace.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated test suite passes. The tests never build the old records with
Yoru's own encoder: they use an independent encoder written from the game's own
definition. They cover:

- gifts that can be repaired, gifts the game has changed, duplicated and missing
  gifts, and gifts in the party
- a full PC
- refusing an interrupted save
- refusing any repair result that changed something else

No real save was used.

Still tracked separately:

- remaining artwork repair options (#6)
- the visual system (#9)
- the Collection redesign (#8)
- companion placement (#3)
- final cleanup (#12)

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics or game music. Original Yoru
companion illustrations are included. Study data stays in your local workspace.
