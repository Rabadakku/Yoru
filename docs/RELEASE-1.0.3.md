# Yoru 1.0.3

This update repairs saved Pokémon decoding and artwork imports, and improves
readability across the study workspace.

## Fixed

- Pokémon with high-bit personality values now decode correctly. This fixes
  genuine saved starters appearing missing or unreadable in Yoru. Existing
  genuine game saves need no conversion or reset.
- Box backgrounds use both of Emerald's wallpaper palettes.
- Artwork imports validate images and publish a complete staged library.
  Failed imports leave the previous library intact.
- Saving has a separate durability acknowledgement. Failed vault writes retain
  the newest snapshot for retry; closing waits for persistence before releasing
  the session. Game save-format mismatches stop startup instead of allowing
  play with saving disabled.
- Saved-game details and export stay accessible when the emulator core or
  game file is missing.

## Interface

- Plain page titles, larger body text, and consistent sans-serif labels;
  timers and headline numbers retain monospace.
- Long instructions wrap instead of truncating.
- Collection puts storage before rewards, uses a more compact box grid,
  and shows the party lead's details immediately.
- The Game page shows vault-save status and offers a retry after write failure.

## Updating

Install the new application over the previous version. Keep your existing
workspace; do not reset it. If box backgrounds were imported with an older
decoder, import your supported game file again from Settings to regenerate
them. Game files and extracted graphics remain local.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated test suite covers save formats, delayed/stale acknowledgements,
write-failure recovery, artwork rollback, vault migration, storage edits and the
named-module UI. Synthetic page renders cover five themes at desktop and
minimum window dimensions.

The broader Collection redesign, legacy study-gift assessment, artwork and vault
backup retention, and final codebase decomposition remain tracked separately.
This release does not automatically rewrite study gifts produced by the older
signed encoder, and does not claim full-campaign or clean-machine acceptance on
all platforms.

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics or game music. Original Yoru
companion illustrations are included. Study data stays in your local workspace.
