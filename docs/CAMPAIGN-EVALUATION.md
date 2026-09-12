# Full-game integration — #29

## Product decision, September 10, 2026

The owner clarified that the entire game must be playable **inside Yoru**, with
battles behaving like the original. Study replaces repetitive encounter and
training grinding; the resulting Pokémon must be usable in that same campaign.
[PRODUCT-GOALS.md](PRODUCT-GOALS.md) is the authoritative product description.

The previous evaluation recommended launching RetroArch separately and granting
play-time credits. That recommendation is superseded: it neither puts gameplay
inside Yoru nor replaces grinding. Its “perfect fidelity, by construction” claim
also overstated what had been verified. No complete campaign was tested.

## Reference and available foundations

Initial target: Emerald Hoenn + National Dex Edition, identified
in [PRODUCT-GOALS.md](PRODUCT-GOALS.md). This is a variant; verify
its differences rather than silently mixing it with vanilla Emerald data.

The earlier extraction report recorded 854 trainer entries, 1,695 trainer-party
members and 354 moves. These are table counts, not a verified inventory of
reachable fights or proof of complete campaign coverage. Keep a coverage map
of story conditions, optional paths, doubles, rematches and endgame battles.

Yoru currently has 386 species' base data, 56 move definitions and a simplified
Java battle prototype. It has type/damage calculations, some statuses and
stat stages, plus party, switching and replacement logic at the engine layer.
At review baseline d1d766a, the UI still used a single fighter and only the
first gym opponent. It did not expose that new party logic.

Absent or incomplete: original learnsets and gym rosters, many move effects,
abilities, held items, bag/inventory, doubles, weather, original trainer AI,
world maps, NPCs and story/progression gates. Existing tests passing does not
establish original battle behavior.

> **2026-09-11:** the Java battle prototype described above was removed. Yoru
> embeds the game itself (the first option below), so its battles are the
> original ones.

## Implementation options that meet the goal

### Embed the original game engine in Yoru

Use an emulator core to execute the user-supplied ROM, with video, sound and
controls hosted by the Game tab. The game's own maps, trainers, menus and battle
logic can then run inside Yoru rather than being recreated as an approximation.

The mGBA libretro core is a candidate. The
[libretro interface](https://docs.libretro.com/development/cores/developing-cores/)
exposes video, audio and input callbacks as well as save-state operations;
a frontend can be Yoru rather than RetroArch. See the
[mGBA core implementation](https://github.com/mgba-emu/mgba/blob/master/src/platform/libretro/libretro.c).

This is the approach under investigation. It still needs verified rendering,
controls, frame/audio timing, pause/resume, saves, error recovery and packaging
on supported desktop platforms. An installed core on one developer's Mac is
not a distributable runtime. Keep native/runtime dependencies explicit and
keep user game files external.

The study bridge is separate required work: map campaign progress to eligible
encounter/training rewards; make study-earned Pokémon playable; preserve
original challenge and story gates; and persist consumption without duplication.
Simply showing an unmodified game in a tab leaves this part unfinished.

### Recreate the campaign natively

This can also satisfy the product goal if it covers the actual world,
progression, trainer inventory and battle mechanics. It requires much more
than extending the existing eight gym dialogs. Every approximation needs a
documented gap and evidence-based parity tests. Do not silently reduce scope
because a faithful implementation is substantial.

## Work order and acceptance

1. **Embedded Game tab:** user-supplied game loads inside Yoru; movement, menus,
   audio and original battles work; switching tabs safely pauses it.
2. **Campaign saves:** resume across closing/reopening; safe writes, backup and
   clear separation from practice badges and existing emulator saves.
3. **Study-to-campaign bridge:** bank eligible study, grant progression-appropriate
   encounters/training, use earned Pokémon in the actual campaign, and atomically
   consume rewards. Define migration for existing captures before changing them.
4. **Progression and fidelity verification:** first study-to-boss sequence,
   trainer/boss inventory, original rosters, optional and late-game paths,
   doubles, items, abilities, statuses and rematches against the exact reference.
5. **Packaging and acceptance:** clean-machine setup, missing-file handling,
   independent study tools, vault migration and save recovery, then the owner's
   gameplay review. Do not close the epic on a successful title-screen demo.

The legacy collection's all-species lottery and time-based evolution remain
existing behavior, not approved campaign rules. Never transfer late-game
rewards into an early campaign without defining how progression is preserved.
