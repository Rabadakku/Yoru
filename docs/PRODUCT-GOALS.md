# Yoru product goals

Confirmed by the owner on September 10, 2026. This is the product direction for
contributors and AI agents. Read this before designing gameplay or changing
the study reward system. It supersedes the earlier recommendation to launch
a separate emulator and exchange study time for minutes of play.

## The application the owner wants

Yoru is a local desktop study workspace with the full Pokémon game playable
inside the application. Studying replaces repetitive encounter and training
grinding; after studying, the player enters the game and plays with the Pokémon
they earned and raised. The study tools and the game are both core parts of Yoru.

The owner's clarification:

> I basically want the entire game playable inside the application. Battles
> should be the same as playing the game.

## The study-to-play experience

1. Track a study session with Yoru's existing open-ended clock.
2. Clock out and bank eligible study progress, even if the player plays later.
3. Open a top-level **Game** tab to enter the actual gameplay space in Yoru.
4. Use study-earned encounters and training with the campaign's persistent team.
5. Explore, interact with NPCs, manage Pokémon and items, and play the original
   battles and story. Return to studying and resume the same journey later.

Study rewards must remove repetitive grinding while preserving the original
progression and challenge. Buying a limited number of emulator minutes leaves
the grinding intact and does not meet this requirement. The exact exchange
rates and the technical reward bridge still need implementation and validation;
do not silently turn this goal into a play-time allowance.

## What full gameplay includes

- A playable world inside Yoru: movement, maps, NPC interaction, menus, party,
  storage, inventory, encounters, story events, saving and continuing.
- Original campaign progression: early-game Pokémon in early areas, the
  corresponding trainer and boss fights, then later areas and encounters as
  their original conditions are met.
- All NPC trainer and boss fights in the supported game, including optional,
  story, double, late-game and rematch battles under their original conditions.
  Eight isolated gyms do not complete the campaign.
- Battles that behave like the supported original game: actual rosters,
  levels, moves and effects; party switching and faint replacement; items,
  abilities, statuses, weather, damage, turn order, AI and running rules.
- One coherent gameplay team and campaign history. Study-earned Pokémon must
  be usable in that game; a separate cosmetic collection alone is insufficient.

## Reference game and fidelity

The initial reference is **Emerald Hoenn + National Dex Edition**.
Keep exact file hashes and machine-specific game paths in local-only notes.
It is a variant, so do not assume every detail equals unmodified Emerald.
Document verified differences and key compatibility decisions. Do not replace
the reference with another game or invent trainer teams and learnsets.

Using an embedded emulator engine to run user-supplied game files is compatible
with this goal: the game must render and accept input in Yoru's Game tab. A
button that launches another application is not the requested experience.
An embedded engine is an implementation approach, not a substitute for the
study reward integration. A faithful native implementation is also possible,
but a simplified battle recreation does not establish original-game fidelity.

## Preserve the study workspace and the player's progress

- Keep tracking, tasks, tags, scheduling, habits, analytics and local vaults
  useful independently of gameplay. No game file should be required to study.
- Preserve existing study history, captures, nicknames and party membership.
  Specify and test migration before connecting them to a campaign save.
- Persist rewards and their consumption together safely. Closing, reopening,
  retrying or failing a write must not duplicate rewards or lose earned progress.
- Keep game saves safe when switching tabs, pausing, closing or upgrading.
  Campaign badges come from the game's own save; the practice-battle records
  and their gyms were removed on 2026-09-11.
- Keep personal ROMs, artwork, saves, vaults and keys out of Git and public
  releases. User-supplied files remain local.

## Current work is a foundation, not completion

The Java battle prototype and its eight gyms were removed on 2026-09-11 at
the owner's request ("forget having battle systems and everything outside the
game"): battles are the embedded game's own. The animated study route is
scenery, not an explorable
campaign. The current encounter lottery and two-hour evolution rule are legacy
collection rules; they must be reconciled with campaign progression before
being applied to the real game.

See [CAMPAIGN-EVALUATION.md](CAMPAIGN-EVALUATION.md) for implementation options,
[ROADMAP.md](ROADMAP.md) for work order, and [issue #29](https://github.com/Rabadakku/yoru/issues/29)
for the campaign acceptance criteria. Describe partial deliveries accurately.

## Completion criteria

A fresh player can study, clock out, enter Game, use their earned Pokémon and
play the first appropriate encounters and trainer/boss sequence. Saving and
reopening preserves both the game and the study ledger without duplication.
Later areas, all trainer/boss paths and original battle mechanics are verified
against the supported game. A working title screen, an emulator launch, or a
passing simplified-engine test suite alone does not satisfy this goal.
