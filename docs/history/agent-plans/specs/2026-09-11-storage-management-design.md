# Yoru-side storage management (#44 remainder)

Date: 2026-09-11. Branch: `claude/storage-management`. Approved by the owner.

## What and why

Issue #44 made the game save the collection, but one acceptance line remained:
rearranging Pokémon between boxes and the party, renaming boxes and changing
wallpapers **from Yoru while the game is closed**, written through the same
verified transaction as delivery. The Collection page today renders the save
read-only.

The owner's requirements, as confirmed while designing:

- Edits happen only while the game is closed; the game's own sync cycle stays
  as it is — Yoru writes at edit time, the game picks the save up at Play and
  its changes return at Close.
- The page should look and operate like the game's own Pokémon Storage System.
- Save corruption is a real fear: every edit is backed up before it commits.

## Architecture

**Mutual exclusion, stated plainly (the owner's rule):** the game and the
tracker never touch the save at the same time. While the game is open, the
tracker only reads — edit controls are disabled and the sync refuses to run.
While the game is closed, the tracker may write; the game picks the save up at
its next Play. There is no real-time file exchange between the two.

### `game/StorageEdit` — the pure planner

Bytes in, bytes out, no Swing, no I/O. Mirrors `GameDelivery.plan`'s shape:

- `move(byte[] save, Place from, Place to)` — box→box, box→party,
  party→box; the party is expressed as `Place(party=true, -1, index)`, a box
  slot as `Place(party=false, box, slot)`. Dropping on an empty slot moves;
  dropping on an occupied slot swaps (Gen 3 behavior). Refuses to empty the
  party: the last party member cannot be deposited.
- `renameBox(byte[] save, int box, String name)` — max 9 characters, written
  with `Gen3Text` (the game's own encoding); a box name region never holds
  anything else.
- `wallpaper(byte[] save, int box, int id)` — cycles the 12 wallpapers the
  game ships; id taken modulo `Gen3Save.WALLPAPERS`.

Each op applies its change to a `Gen3Save`, re-reads the result from its own
bytes, and runs `verify(before, after, touched)`: outside the party section,
the PC storage and the box-name/wallpaper region, every byte must match.
Inside them, every Pokémon not involved in the change must be byte-for-byte
where it was. A failing op changes nothing and throws `IllegalStateException`
with the reason, so the caller can show it.

### `Tracker.editSave` — the one-write commit

```java
public void editSave(byte[] before, byte[] after) throws IOException
```

- Refuses when `state.game()` is null or no longer `holds(before)` — the same
  guard `recordDelivery` uses, so an edit planned against one save is never
  written over another.
- Backs the vault up first (`repository.backup()`), the same protection
  `reset` and `replaceGameSave` already use.
- Commits `GameSave(after, now)` in one write. Rewards, campaign and the rest
  of the vault are untouched.

### UI — the game's storage, live

- `StorageScreen` gains an arranging mode (`setArranging(boolean)`). While
  arranging, clicks pick up and place instead of inspecting: click a Pokémon
  to pick it up (gold highlight), click a destination slot to move or swap,
  click the picked Pokémon again or press Escape to cancel. The details panel
  keeps click-to-inspect outside arranging mode.
- The Collection page storage card gains **Arrange** (toggle), **Rename
  box…** (`Dialogs.input`, 9-character limit stated in the prompt) and
  **Wallpaper** (cycles). All three only when the game is closed — the page
  already shows a banner while it runs.
- After each edit the page refreshes from the new save; a refused edit shows
  the reason in the existing error dialog.

## Rules

- The game and the tracker never write at the same time: edits only while the
  game is closed, and no sync while it runs. The changed-save guard in
  `editSave`/`recordDelivery` is the backstop for any race.
- The party can never be emptied (Gen 3 refuses this too).
- Eggs move like any other Pokémon; they hatch in the game.
- The running game is never edited behind its back — edits are disabled while
  it runs, and the changed-save guard covers the race anyway.

## Testing

- `game/StorageEditTest` — pure bytes: move box→box, box→party, party→box,
  swap onto an occupied slot, last-party-member refusal, rename round trip and
  9-character cap, wallpaper cycle, and `verify` refusing each kind of
  collateral change (mutation-checked, the GameDeliveryTest style).
- `Tracker.editSave` — changed-save refusal, backup-before-commit, one write,
  rewards untouched. Covered in the reliability/edit suites.
- `StorageScreen` click mapping driven with synthesized `MouseEvent`s (the
  repo's TaskBoardTest pattern); `Preview` renders the arranging state as a PNG.
- #40's isolation criterion: a test proving two vaults holding the same ROM
  save diverge independently through deliveries and edits.

## Out of scope

- Nicknames: renamed in the game, not from Yoru (the game's naming screen has
  rules this page does not reimplement).
- Deferring edits until Play: edits write immediately, which is what #44 says.
- Item storage: the save's bag is not part of this ticket.
