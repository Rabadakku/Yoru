# Yoru 1.0.10

A Collection page that starts with your party, a Waifu theme of its own with
five portraits, a cleaner look across Today and Settings, and fixes to artwork,
backups and scrolling.

## New

- **Collection starts with your party.** Six slots with each Pokémon's picture,
  name and level, then one row for study encounters and Pokémon on their way,
  then the PC boxes with Previous, Next, a box list and how full the box is.
  At the smallest window your whole party is on screen without scrolling.
- **Arranging says what to do.** Arrange turns on a mode that reads "Choose a
  Pokémon, then choose its destination", shows which Pokémon you are moving and
  where from, and has Cancel move and Done. Moves work between the party and any
  box, by mouse or keyboard.
- **A Waifu theme.** The illustrated companions now have a theme of their own,
  with five original portraits: Nightfall, Amberglow, Rainbird, Solstice and
  Vermilion. Settings shows them as pictures to choose from, and the gallery on
  each page pairs your choice with a different portrait.
- **Settings in four sections:** appearance, tracking, game and artwork, and
  the vault, whose controls moved here from the Data page. Themes sit three
  across, and picking one keeps your place on the page.

## Changed

- **Moonlight no longer shows portraits.** It keeps its violet palette. If you
  used Moonlight for its companion, choose the Waifu theme in Settings; your
  portrait choice is kept.
- **Today is simpler.** One Clock in or Clock out, on the focus card. The
  partner card says your partner's level and species and when your vault last
  saved the game. The schedule comes before the statistics.
- **Page titles are larger and cards have rounded corners.**

## Fixed

- Study scenery read "Partial · 7 of 10 sheets" with every sheet the scene needs.
  The three route visitors are optional extras and are no longer counted as
  missing.
- An artwork import that failed left no sign once its message was closed.
  Settings now marks what is still missing as Failed, with the reason, until an
  import succeeds.
- A missing Pokémon picture showed a number such as "#252" where the name should
  be. It now shows a plain shape, with the name beside it.
- Vault backups were never removed. Yoru now keeps the ten newest, the first of
  each of the last thirty days, and removes the rest, so deleting a session,
  block, task or tag can take a backup too.
- Saving, editing or moving a Pokémon rebuilt the page and jumped back to the
  top. The page now keeps its place, and Collection keeps the box and the
  Pokémon on show.

## Updating

- **If you have 1.0.5 or later:** open **Settings → Updates → Check for updates**.
- **Otherwise:** install the new application over the previous version and keep
  your existing workspace.

A workspace saved while using the Waifu theme cannot be opened by 1.0.9 or
earlier. Switch to another theme first if you need to go back.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated test suite passes, and every page renders in all six themes at
desktop and minimum sizes. Moving the Today and Settings pages and the artwork
importer into their own classes was checked by comparing every page render
before and after. A lifecycle check against a real libretro core (26 checks)
and the dialog focus check on a real display were run by hand.

Not verified for this release: a clean-machine install, a full campaign against
a real core, and a review at 200% text size.

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics or game music. Original Yoru
companion illustrations are included. Study data stays in your local workspace.
