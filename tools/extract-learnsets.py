#!/usr/bin/env python3
"""
Extracts level-up learnsets and move PP from a Gen 3 Emerald ROM.

    python3 tools/extract-learnsets.py "path/to/emerald.gba" > \
        src/main/java/dev/yoru/game/Learnsets.java

Why this is needed
------------------
A Pokemon written into a real save carries four move ids and their PP. Giving
it moves it could not legally know is not a cosmetic problem: the moves are
what the player then battles with, and a companion holding an impossible set is
the clearest sign something was injected rather than caught.

The project already has `dev.yoru.battle.Movesets`, but that file says plainly
that it is not a real learnset — it picks by type for the practice battles. It
is fine there and wrong here.

How the table is found
----------------------
`gLevelUpLearnsets` is an array of ROM pointers, one per internal species.
Each points at a run of u16 entries packing `(level << 9) | move`, ending with
0xFFFF. That shape is distinctive enough to find by scanning for a long run of
consecutive pointers whose targets all parse as learnsets, and it is then
checked against three species whose early moves are widely known.

Move PP comes from the battle-move table, 12 bytes per move with PP at
offset 4, anchored the same way `tools/extract-battle-data.py` anchors it.
"""

import struct, sys, pathlib

BASE = 0x08000000
SPECIES = 386
INTERNAL_MAX = 411
MOVES = 354
TERMINATOR = 0xFFFF

# Early moves everybody can check: Bulbasaur, Charmander, Treecko.
LANDMARKS = {
    1: [(1, 33), (4, 45), (7, 73), (10, 22)],
    4: [(1, 10), (1, 45), (7, 52)],
    277: [(1, 1), (1, 43), (6, 71)],
}


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    rom = pathlib.Path(sys.argv[1]).read_bytes()
    size = len(rom)
    words = struct.unpack_from(f"<{size // 4}I", rom, 0)

    def target(word):
        return word - BASE if BASE <= word < BASE + size else -1

    def parses(at):
        if at < 0 or at + 2 > size:
            return False
        count = 0
        while at + 2 <= size and count < 40:
            entry = struct.unpack_from("<H", rom, at)[0]
            if entry == TERMINATOR:
                return count > 0
            level, move = entry >> 9, entry & 0x1FF
            if not (1 <= level <= 100 and 1 <= move <= 400):
                return False
            count += 1
            at += 2
        return False

    best, i = None, 0
    while i < len(words) - 1:
        if parses(target(words[i])):
            start, run = i, 0
            while i < len(words) and parses(target(words[i])):
                run += 1
                i += 1
            if run >= 300 and (best is None or run > best[1]):
                best = (start, run)
        else:
            i += 1
    if best is None:
        raise SystemExit("Could not find gLevelUpLearnsets: no run of 300+ pointers parsed as learnsets.")
    table, entries_found = best
    print(f"// learnset table at 0x{table * 4:06X} with {entries_found} entries", file=sys.stderr)

    def learnset(internal):
        at = target(words[table + internal])
        out = []
        while True:
            entry = struct.unpack_from("<H", rom, at)[0]
            if entry == TERMINATOR:
                return out
            out.append((entry >> 9, entry & 0x1FF))
            at += 2

    for internal, expected in LANDMARKS.items():
        got = learnset(internal)[:len(expected)]
        if got != expected:
            raise SystemExit(f"Internal species {internal} learns {got}, expected {expected} — "
                             "the table was misread.")
        print(f"//   internal {internal} starts {got}", file=sys.stderr)

    # The species-to-National map, so the output can be indexed the way the
    # rest of Yoru indexes species.
    identity = b"".join(struct.pack("<H", n) for n in range(1, 26))
    dex_at = None
    at = 0
    while True:
        hit = rom.find(identity, at)
        if hit < 0:
            break
        if struct.unpack_from("<H", rom, hit + 276 * 2)[0] == 252:
            dex_at = hit
            break
        at = hit + 1
    if dex_at is None:
        raise SystemExit("Could not find the species-to-National table.")
    internal_of = {}
    for species in range(1, INTERNAL_MAX + 1):
        national = struct.unpack_from("<H", rom, dex_at + (species - 1) * 2)[0]
        if 1 <= national <= SPECIES and national not in internal_of:
            internal_of[national] = species

    # Move PP: the battle-move table, 12 bytes each, PP at offset 4.
    pound = bytes([0, 40, 0, 100, 35])
    moves_at = None
    at = 0
    while True:
        hit = rom.find(pound, at)
        if hit < 0:
            break
        # Karate Chop is move 2: power 50, type Fighting (1), accuracy 100, pp 25.
        if rom[hit + 12 + 1] == 50 and rom[hit + 12 + 3] == 100 and rom[hit + 12 + 4] == 25:
            moves_at = hit - 12          # move 0 is unused
            break
        at = hit + 1
    if moves_at is None:
        raise SystemExit("Could not find the battle-move table.")
    pp = [rom[moves_at + move * 12 + 4] for move in range(MOVES + 1)]
    if pp[33] != 35 or pp[52] != 25:
        raise SystemExit(f"Tackle PP {pp[33]} (expected 35), Ember PP {pp[52]} (expected 25) — "
                         "move table misaligned.")
    print(f"// move table at 0x{moves_at:06X}", file=sys.stderr)

    # Flatten every learnset, indexed by National number.
    flat, offsets = [], [0]
    for national in range(1, SPECIES + 1):
        for level, move in learnset(internal_of[national]):
            flat.append((level << 9) | move)
        offsets.append(len(flat))

    def rows(values, width, fmt=str):
        out = []
        for start in range(0, len(values), width):
            out.append("        " + ", ".join(fmt(v) for v in values[start:start + width]) + ",")
        return "\n".join(out)

    print(f'''package dev.yoru.game;

/**
 * Level-up learnsets and move PP, read from the reference ROM.
 *
 * Generated by tools/extract-learnsets.py; do not edit by hand.
 * Learnsets read at 0x{table * 4:06X}, move table at 0x{moves_at:06X}.
 *
 * A companion delivered into a real save carries four move ids and their PP.
 * Giving it moves it could not legally know is not cosmetic — those moves are
 * what the player then battles with, and an impossible set is the clearest
 * sign something was injected rather than caught. {len(flat)} entries across
 * {SPECIES} species.
 */
public final class Learnsets {{

    private Learnsets() {{ }}

    /** Packed as (level &lt;&lt; 9) | move, in the order the species learns them. */
    private static final short[] ENTRIES = {{
{rows(flat, 16, lambda v: str(v - 65536 if v > 32767 else v))}
    }};

    /** Where each National number's entries start; the next entry ends them. */
    private static final int[] OFFSET = {{
{rows(offsets, 20)}
    }};

    /** Base PP for a move id. */
    private static final byte[] PP = {{
{rows(pp, 20)}
    }};

    public static int pp(int moveId) {{
        return moveId >= 0 && moveId < PP.length ? PP[moveId] & 0xFF : 0;
    }}

    /** Every (level, move) pair a species learns, in order. */
    public static int[][] all(int nationalDexNumber) {{
        if (nationalDexNumber < 1 || nationalDexNumber > {SPECIES})
            throw new IllegalArgumentException("No species " + nationalDexNumber + ".");
        int from = OFFSET[nationalDexNumber - 1], to = OFFSET[nationalDexNumber];
        var out = new int[to - from][2];
        for (int i = from; i < to; i++) {{
            int packed = ENTRIES[i] & 0xFFFF;
            out[i - from][0] = packed >>> 9;
            out[i - from][1] = packed & 0x1FF;
        }}
        return out;
    }}

    /**
     * The four moves a species would actually know at a level.
     *
     * The game keeps the four most recently learned, discarding the oldest as
     * new ones arrive, so this walks the list in order and keeps a window of
     * four — which is what a Pokemon raised to that level would be holding.
     * Duplicates are skipped, because the same move can appear twice in a
     * learnset and a Pokemon never knows one twice.
     */
    public static int[] movesAt(int nationalDexNumber, int level) {{
        var known = new int[4];
        int count = 0;
        for (var pair : all(nationalDexNumber)) {{
            if (pair[0] > level) break;
            int move = pair[1];
            boolean already = false;
            for (int i = 0; i < count; i++) if (known[i] == move) already = true;
            if (already) continue;
            if (count < 4) known[count++] = move;
            else {{
                known[0] = known[1]; known[1] = known[2]; known[2] = known[3];
                known[3] = move;
            }}
        }}
        return known;
    }}
}}''')


if __name__ == "__main__":
    main()
