# Yoru 1.0.8

## Habits

- Rename or delete an individual daily or time-since habit from its card.
- Renaming preserves its history and identity. Deleting affects only that
  habit and its history, with a vault backup made before the change.
- Invalid names and failed writes leave the previous state intact.

## Moonlight

- Moonlight is now the dedicated illustrated companion theme. Other themes
  preserve your portrait preference without displaying companion artwork.
- Original Nightfall and Amberglow illustrations appear beside the timer and
  in galleries across Moonlight's other pages. Pokémon features remain intact.
- Pixel portraits are removed from the application and picker. Old portrait
  selections resolve to Nightfall without requiring a vault reset.

## Scene artwork

- A missing trainer now has a clear recovery action on Today.
- Settings correctly counts installed box backgrounds.
- Trainer walking/running and scenery require your locally supplied scene
  sheets. Import your earlier artwork folder in Settings if they are missing.
  Importing a game currently extracts Pokémon pictures and box backgrounds,
  not the overworld trainer sheets. No game artwork is bundled.

## Updating

Keep your existing workspace and artwork library. Install over the previous
application or use Settings to check for updates. Installers include Java:
Apple silicon macOS `.dmg`, Windows `.msi`, and amd64 Linux `.deb`.
The macOS application is unsigned; macOS may require Open Anyway on first use.

## Validation and remaining work

The full isolated suite covers habit history, failed writes, theme isolation,
legacy portrait selections, artwork packaging and prior save repairs. Page
renders cover five themes at desktop and minimum sizes. The restored scene
was also checked in the running desktop app.

The broader editing audit is tracked in #21, updater command timeout handling
in #20, and remaining Collection/layout work in #8–#9. Full campaign and
clean-machine acceptance across all platforms remain separate work.
