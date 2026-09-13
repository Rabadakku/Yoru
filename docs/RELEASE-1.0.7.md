# Yoru 1.0.7

Today stays focused on your timer and your game partner.

## Changed

- If you haven't chosen a companion, Today no longer shows a companion card or
  suggests one. The "Try Moonlight" suggestion is gone.
- Choosing the Moonlight theme no longer switches on the Nightfall companion as
  well. Themes and companions are chosen separately, in Settings.
- A companion you have already chosen is kept, and still appears beside the timer
  on Today.

## Updating

- **If you have 1.0.5 or later:** open **Settings → Updates → Check for updates**.
- **Otherwise:** install the new application over the previous version and keep
  your existing workspace.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated test suite passes. It covers Today with no companion, with an
unknown companion and with a chosen one. It also checks that changing the theme,
trainer or tracking settings keeps your companion choice.

Still tracked separately:

- remaining artwork repair options (#6)
- native checks of save acknowledgements (#7)
- the Collection redesign (#8)
- the visual system (#9)
- final cleanup (#12)

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics or game music. Original Yoru
companion illustrations are included. Study data stays in your local workspace.
