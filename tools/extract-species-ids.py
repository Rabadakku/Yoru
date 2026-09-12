#!/usr/bin/env python3
"""
Extracts the National-dex-to-internal species mapping from a Gen 3 Emerald ROM.

    python3 tools/extract-species-ids.py "path/to/emerald.gba" > \
        src/main/java/dev/yoru/game/SpeciesIds.java

Why this table has to exist
---------------------------
Writing a Pokémon into a save means writing its *internal* species id, which is
not its National Dex number. For the first 251 the two agree. After that they
do not, and the difference is not a constant: the Hoenn species sit in the
ROM's own order, so the widely repeated "National + 25" rule is wrong for 109
of the 135 of them.

The failure is silent and specific. Gardevoir is National 282; +25 gives 307,
which is Breloom. Nothing errors — the save simply contains a different
Pokémon than the one that was asked for. That exact mistake was made earlier in
this project against the base-stats table, which is why the mapping is now read
from the ROM and checked against landmarks rather than computed.

How the table is found
----------------------
`sSpeciesToNationalPokedexNum` maps internal id to National number. Its first
251 entries are the identity, which makes a long, unambiguous signature; the
entry for internal 277 must then read 252, which is Treecko — the first Hoenn
species and the point where the two numbering schemes separate.
"""

import struct, sys, pathlib

HOENN_FIRST_INTERNAL = 277
LANDMARKS = {1: "Bulbasaur", 151: "Mew", 251: "Celebi",
             252: "Treecko", 255: "Torchic", 258: "Mudkip",
             282: "Gardevoir", 386: "Deoxys"}
SPECIES = 386


def find_all(rom, signature):
    out, at = [], 0
    while True:
        i = rom.find(signature, at)
        if i < 0:
            return out
        out.append(i)
        at = i + 1


def find_dex_map(rom):
    identity = b"".join(struct.pack("<H", i) for i in range(1, 26))
    for hit in find_all(rom, identity):
        if struct.unpack_from("<H", rom, hit + (HOENN_FIRST_INTERNAL - 1) * 2)[0] == 252:
            return hit
    raise SystemExit("Could not find the species-to-National table: no identity run "
                     "whose entry for internal 277 reads 252 (Treecko).")


def find_base_stats(rom):
    """The base-stats table, anchored on Bulbasaur followed by Ivysaur."""
    bulbasaur = bytes([45, 49, 49, 45, 65, 65])
    ivysaur = bytes([60, 62, 63, 60, 80, 80, 12, 3])
    for hit in find_all(rom, bulbasaur):
        if rom[hit + 28:hit + 36] == ivysaur:
            return hit - 28          # the table starts at species 0, which is unused
    raise SystemExit("Could not find the base-stats table.")


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    rom = pathlib.Path(sys.argv[1]).read_bytes()
    code = rom[0xAC:0xB0].decode("ascii", errors="replace")
    if code != "BPEE":
        print(f"warning: game code is {code!r}, expected BPEE.", file=sys.stderr)

    at = find_dex_map(rom)
    internal = {}
    for species in range(1, 412):
        national = struct.unpack_from("<H", rom, at + (species - 1) * 2)[0]
        if 1 <= national <= SPECIES and national not in internal:
            internal[national] = species

    missing = [n for n in range(1, SPECIES + 1) if n not in internal]
    if missing:
        raise SystemExit(f"No internal id for National {missing[:10]} "
                         f"({len(missing)} missing) — the table was misread.")

    for national in range(1, 252):
        if internal[national] != national:
            raise SystemExit(f"National {national} should be internal {national}, "
                             f"got {internal[national]}.")
    if internal[252] != HOENN_FIRST_INTERNAL:
        raise SystemExit(f"Treecko should be internal {HOENN_FIRST_INTERNAL}, got {internal[252]}.")

    wrong = sum(1 for n in range(252, SPECIES + 1) if internal[n] != n + 25)
    print(f"// Read from the ROM at 0x{at:06X}. "
          f"National+25 would be wrong for {wrong} of the {SPECIES - 251} Hoenn species.",
          file=sys.stderr)
    for national, name in LANDMARKS.items():
        print(f"//   {name} (National {national}) is internal {internal[national]}", file=sys.stderr)

    # The rest of what a valid Pokémon needs, read from the same ROM.
    # Base stats are 28 bytes per species, indexed by INTERNAL id:
    #   16 gender ratio, 18 base friendship, 19 growth rate, 22/23 abilities.
    stats_at = find_base_stats(rom)
    def field(national, offset):
        return rom[stats_at + internal[national] * 28 + offset]

    growth = {n: field(n, 19) for n in range(1, SPECIES + 1)}
    if max(growth.values()) > 5:
        raise SystemExit(f"Growth rate {max(growth.values())} is out of range 0-5; "
                         "the base-stats table was misread.")
    # The enum is MEDIUM_FAST 0, ERRATIC 1, FLUCTUATING 2, MEDIUM_SLOW 3,
    # FAST 4, SLOW 5 — worth writing down, because guessing MEDIUM_SLOW as 4 is
    # exactly the mistake this check caught while it was being written.
    # Four species with well-known and mutually different curves; if the table
    # were misaligned they would not all land correctly at once.
    expected = {1: 3, 10: 0, 113: 4, 129: 5}
    names = {1: "Bulbasaur/MediumSlow", 10: "Caterpie/MediumFast",
             113: "Chansey/Fast", 129: "Magikarp/Slow"}
    wrong_growth = {n: growth[n] for n, want in expected.items() if growth[n] != want}
    if wrong_growth:
        raise SystemExit("Growth rates misaligned: "
                         + ", ".join(f"{names[n]} read {got}, expected {expected[n]}"
                                     for n, got in wrong_growth.items()))

    def rows_of(values, width=16):
        out = []
        for start in range(1, SPECIES + 1, width):
            out.append("        " + ", ".join(str(values[n])
                       for n in range(start, min(start + width, SPECIES + 1))) + ",")
        return chr(10).join(out)

    rows = []
    for start in range(1, SPECIES + 1, 16):
        rows.append("        " + ", ".join(str(internal[n])
                    for n in range(start, min(start + 16, SPECIES + 1))) + ",")

    extra = f'''
    /** Growth rate, 0-5, indexed by National number minus one. */
    static final byte[] GROWTH = {{
{rows_of(growth)}
    }};

    /** Base friendship a freshly caught companion starts with. */
    static final short[] FRIENDSHIP = {{
{rows_of({n: field(n, 18) for n in range(1, SPECIES + 1)})}
    }};

    /** Gender ratio: 0 always male, 254 always female, 255 genderless. */
    static final short[] GENDER_RATIO = {{
{rows_of({n: field(n, 16) for n in range(1, SPECIES + 1)})}
    }};

    /** How this species grows, as an index into the six experience curves. */
    public static int growthOf(int nationalDexNumber) {{
        return GROWTH[check(nationalDexNumber) - 1];
    }}

    /** Base friendship for a newly caught companion. */
    public static int friendshipOf(int nationalDexNumber) {{
        return FRIENDSHIP[check(nationalDexNumber) - 1];
    }}

    /** Gender ratio byte, in the game's own encoding. */
    public static int genderRatioOf(int nationalDexNumber) {{
        return GENDER_RATIO[check(nationalDexNumber) - 1];
    }}

    private static int check(int nationalDexNumber) {{
        if (nationalDexNumber < 1 || nationalDexNumber > SPECIES)
            throw new IllegalArgumentException("No species " + nationalDexNumber + ".");
        return nationalDexNumber;
    }}
'''

    print(f'''package dev.yoru.game;

/**
 * National Dex number to the internal species id a save actually stores.
 *
 * Generated by tools/extract-species-ids.py from the reference ROM; do not edit
 * by hand. Read at 0x{at:06X}.
 *
 * The two numbering schemes agree for the first 251 and then diverge, and not
 * by a constant — the Hoenn species sit in the ROM's own order. The widely
 * repeated "National + 25" rule is wrong for {wrong} of the {SPECIES - 251} of them, and
 * wrong silently: Gardevoir is National 282, and 282 + 25 is Breloom's id. A
 * save written that way holds a different Pokémon than the one intended, with
 * nothing to indicate it.
 */
public final class SpeciesIds {{

    private SpeciesIds() {{ }}

    public static final int SPECIES = {SPECIES};

    /** Indexed by National number minus one. */
    static final short[] INTERNAL = {{
{chr(10).join(rows)}
    }};

    /** The internal species id for a National Dex number. */
    public static int internalOf(int nationalDexNumber) {{
        if (nationalDexNumber < 1 || nationalDexNumber > SPECIES)
            throw new IllegalArgumentException("No species " + nationalDexNumber + ".");
        return INTERNAL[nationalDexNumber - 1];
    }}

    /** The National Dex number for an internal species id, or 0 if unused. */
    public static int nationalOf(int internalId) {{
        for (int i = 0; i < INTERNAL.length; i++) if (INTERNAL[i] == internalId) return i + 1;
        return 0;
    }}
{extra}}}''')


if __name__ == "__main__":
    main()
