# Yoru 1.0.9

Dates and times you can read and type, a Tasks page laid out like a table, and
fixes to editing, saving, updating and artwork.

## New

- **Dates and times read like Notion's.** Log time, Edit tracked time, planned
  blocks, Clock out and habit start dates show one box: `Sep 14, 2026 | 11:00 AM`.
  Type straight into either half — `9/14`, `sep 14`, `tomorrow`, `9:30p`,
  `21:30` and `noon` all work — or pick a day from the calendar button.
  Up and Down step a day or a quarter hour. Task due and plan-for dates work the
  same way, and blank means no date.
- **The Tasks page is a table.** View tabs and one New button on top; sort, tags
  and Import underneath; then aligned columns for done, status, task name, tag
  and due date. Tick a task off from its checkbox, click a title to open it, and
  find Edit, Track time, Move up, Move down and the new Delete in each row's ⋯
  menu (or right-click the row). Long titles wrap instead of being cut off.
- **More things can be edited.** An activity's daily target (Target, on Today
  and Data), any weekly repeat (Edit, in the weekly template), and any earlier
  period of a time-since tracker (Edit and Delete, in History).
- **Artwork status in Settings.** The game file, Pokémon pictures, box
  backgrounds and study scenery each say Ready, Partial or Missing, with the
  action that fixes that one. After an import, Yoru tells you what it added.

## Fixed

- The date and time boxes in Log time could appear empty. They showed nothing
  because the text had no room inside its own padding.
- A date or time Yoru cannot read is now refused with a message saying which
  part, instead of being quietly replaced by the previous value and saved.
- If saving to your vault failed while the game was closing, the game stayed
  locked even after the automatic retry saved it, until Close was pressed again.
- A step of a macOS update that stalled could hang the update with no time
  limit. It now stops after five minutes, and a half-copied update is removed.
- Deleting a session, planned block or tag that was already gone looked as if it
  worked. It now says it no longer exists.
- Each artwork import kept a full copy of the previous library, game file
  included, forever. Yoru now keeps the current library and the one before it.
- The task editor no longer closes and loses what you typed when a date is refused.

## Updating

- **If you have 1.0.5 or later:** open **Settings → Updates → Check for updates**.
- **Otherwise:** install the new application over the previous version and keep
  your existing workspace.

Save using the game's own Save command before closing the game. Yoru stores
battery saves, not unsaved gameplay or emulator save states.

## Validation and remaining work

The full isolated test suite passes. It now also checks that tests cannot reach
your real home folder or preferences; it reads saves against the game's
documented format rather than Yoru's own writer, and fails if any page render
is missing. The new pickers, the Tasks table and Settings were rendered and
inspected in dark and light themes at desktop and minimum sizes. Native desktop
checks against a real game core were not repeated for this release.

Still tracked separately:

- the Collection redesign (#8)
- the visual system for Today and Settings (#9)
- final cleanup (#12)
- the illustrated companion theme and its artwork (#3, #23)

## Install

Each installer includes Java. macOS uses the Apple silicon `.dmg`, Windows the
`.msi`, and Linux the amd64 `.deb`. The macOS build is unsigned; use
Control-click → Open or System Settings → Privacy & Security → Open Anyway
if macOS asks you to confirm the first launch.

Yoru includes no game, BIOS, Pokémon graphics or game music. Original Yoru
companion illustrations are included. Study data stays in your local workspace.
