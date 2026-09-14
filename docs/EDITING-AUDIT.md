# Editing controls audit (#21)

Audited September 14, 2026 on `claude/open-issues`, by reading every create,
edit and delete path from the page control down to `Tracker`, and by driving
the controls in the headless UI tests. Nothing here was inferred from a button
being visible.

## What every edit has to do

- Target a stable identity (an id, or the instant a period starts), never a
  label or a list position.
- Cancel writes nothing.
- A failed write leaves the state as it was.
- A control opened before its target changed or disappeared is refused with a
  reason, not applied to whatever now sits there.
- Every action has a keyboard path, not only a drag or a double-click.

## Matrix

| Surface | Create | Edit | Delete | Stale edit or delete | Keyboard path | Backup first |
|---|---|---|---|---|---|---|
| Tasks | New, New task row | Editor from the row menu or the title | Row menu (added in #25) | Refused (`updateTask`, `deleteTask`) | Checkbox, status pill and ⋯ menu are buttons | Yes, since #7 |
| Tags | Tag manager | Rename, recolour | Delete keeps the tasks, untagged | Refused (delete refusal added here) | Buttons | Yes, since #7 |
| Activities | New activity | Rename; **daily target added here** | Delete, keeping or deleting the time | Refused | Buttons on Today and Data | Yes |
| Recorded sessions | Log time, clock out | Grid double-click, Data table | Grid editor, Data table | Refused (delete refusal added here) | Data table's Edit and Delete selected | Yes, since #7 |
| Planned blocks | Plan block, drag on the grid | Grid double-click, Planned this week list | Same two places | Refused (delete refusal added here) | Planned this week list | Yes, since #7 |
| Weekly repeats | Weekly template | **Edit added here** | Remove | Refused | Buttons | No |
| Daily habits | New daily check-off | Rename, check off any of the last 28 days | Delete | Refused | Buttons, one per day | Yes |
| Time-since habits | New time-since | Rename; **any period's start, added here** | Delete; **any period, added here** | Refused, including a changed period | History dialog buttons | Yes, for deletes |
| Vaults | New vault | Rename, remove password | Delete | Refused by name | Launcher buttons | Delete confirms what is lost |

## Findings and what changed

1. **An activity's daily target could not be changed after creation.**
   Neither `Tracker` nor any page offered it. Added `Tracker.retargetActivity`
   and a Target control beside Rename on both the Today and Data pages. It
   refuses a stale id, validates through `Activity`, writes nothing when the
   target is already the one asked for, and takes a backup first, as a rename
   does.
2. **Weekly repeats could only be added or removed.** `Tracker.editRepeat`
   existed but nothing called it. Each entry now has Edit. The dialog reopens
   on a refusal with what was typed, and times are read as forgivingly as
   everywhere else (`11am`, `12:30p`, `21:30`) rather than only as `HH:MM`.
3. **Only the current period of a time-since tracker could be corrected.**
   A mistyped restart further back stayed wrong for good. History now has
   Edit and Delete on every period. `editHabitPeriod` and `deleteHabitPeriod`
   find a period by the instant it starts. They refuse a period that changed
   after the dialog opened, a start that would pass a neighbour, and deleting
   the last period, each with its own message; before, the record said only
   that the order was wrong.
4. **Deleting a session, block or tag that no longer existed looked like it
   worked.** Those three deletes filtered the id out and committed anyway.
   They now refuse, like every other delete.
5. **The Data page deleted by view row and edited by model row.** The table
   has no sorter today, so the two indexes are always equal and no wrong
   session could be deleted. Delete now converts the index the same way edit
   does, so adding a sorter later cannot make it pick the wrong session.
6. **Task due and plan dates only accepted `YYYY-MM-DD`, and a refused date
   closed the editor and lost the form.** Fixed in #24: dates are typed or
   picked, and the editor reopens with everything as typed.
7. **Tasks had no delete control.** Added to the row menu in #25, with a
   destructive confirmation.

Tests: `EditingTest` covers findings 1, 3 and 4 against the tracker, including
refusals, backups, failed writes and a reopen. `ScheduleUiTest` drives the
repeat editor's save path and forgiving times. `ActivityUiTest` finds the
Target control on both pages. `TaskBoardTest` covers the task row menu, and
`InputTest` the date fields.

## Deliberately unchanged, and why

- **Backups before small deletions** waited on a retention policy, since
  `EncryptedVault.backup` kept every file it wrote. That is now settled (#7):
  each backup keeps the ten newest, the first of each of the last thirty days
  and any dated in the future, and removes the rest. Migration backups and
  files whose names it cannot read are never pruned. Deleting a session,
  block, task or tag now takes a backup like every other deletion
  (`BackupRetentionTest`, `EditingTest`).
- **Daily check-ins older than 28 days** have no control. The grid shows the
  last four weeks, and older history is kept and counted. Nobody reported
  needing to correct further back.
- **Adding a password to a vault that has none** is not offered. That is a new
  feature rather than a broken edit.

## Not verified here

- Every dialog at the 900×640 minimum on a real desktop. The pickers, the
  Tasks page and the habit history were rendered headless; the activity,
  repeat and history dialogs were not.
- Native keyboard focus inside the modal dialogs. `DialogFocusTest` skips its
  on-screen check without a display.
