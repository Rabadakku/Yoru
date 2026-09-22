# Yoru 1.0.16

## Time-since trackers (#50)

New periods begin on whole minutes, so trackers started together agree. Creating,
restarting and editing use the same precision. A second restart in the same
minute is refused instead of creating a period with hidden seconds.

Older histories are normalized on opening. Distinct starts already sharing one
minute retain their exact timestamps together, preserving their order and
history. Other starts round down; the migration remains stable after reopening.

## Also included from main

This release packages the Pages workspace and reading view, platform text
shortcuts and context menus, today's default task due date, Anki setup in
Settings with saved counts, and the redesigned Today and Habits pages.

Yoru is now a productivity workspace; the game has been removed. When an older
vault contains a game save, opening it extracts that save beside the vault.
Pages remains the first usable checkpoint described in `docs/PAGES.md`;
advanced features in #46 are not all implemented.

## Validation

The full isolated suite covers minute boundaries, refused duplicate restarts,
both edit paths, invalid-history rejection and migration from a synthetic vault
written by 1.0.15, alongside existing persistence and UI checks.
