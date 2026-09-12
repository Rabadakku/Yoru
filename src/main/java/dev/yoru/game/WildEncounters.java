package dev.yoru.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The wild Pokémon the player's own game puts in each area — the table the
 * game calls gWildMonHeaders.
 *
 * Read from the ROM rather than written out here, because the supported game
 * is a variant that changes them: its Route 101 holds Pokémon the original's
 * does not. Study encounters come from these tables, so every Pokémon study
 * earns is one the game itself could have offered in that place.
 *
 * <h2>Layout</h2>
 * <pre>
 *   header, 20 bytes:  u8 map group, u8 map number, u16 padding,
 *                      pointers to land, water, rock smash and fishing tables (0 for none)
 *   ...ended by a header whose group and number are both 0xFF.
 *   table, 8 bytes:    u8 encounter rate, padding, pointer to the slots
 *   slot, 4 bytes:     u8 lowest level, u8 highest level, u16 internal species id
 * </pre>
 * The header table is located by that shape — the longest run of well-formed
 * headers that ends in the terminator — rather than at a fixed address.
 */
public final class WildEncounters {

    /** How a Pokémon is met, with the game's chance for each slot, in percent. */
    public enum Method {
        LAND(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1),
        WATER(60, 30, 5, 4, 1),
        ROCK_SMASH(60, 30, 5, 4, 1),
        /** Slots 0-1 belong to the Old Rod, 2-4 to the Good Rod, 5-9 to the Super Rod. */
        FISHING(70, 30, 60, 20, 20, 40, 40, 15, 4, 1);

        final int[] chances;

        Method(int... chances) { this.chances = chances; }

        public int slots() { return chances.length; }
    }

    public record Slot(int minLevel, int maxLevel, int national) { }
    public record Table(Method method, int rate, List<Slot> slots) { }
    public record Area(int group, int number, List<Table> tables) { }

    static final int HEADER = 20, TERMINATOR = 0xFF, MAP_GROUPS = 34, MIN_AREAS = 16;

    /** The four methods in the header's pointer order, and the slot counts each one reads. */
    private static final Method[] METHODS = Method.values();

    private WildEncounters() { }

    /** Every area with wild Pokémon, in the ROM's order. Where a map repeats, its first header is the game's default. */
    public static List<Area> read(Rom rom) {
        int start = find(rom);
        if (start < 0) throw new IllegalArgumentException("This game file has no wild encounter table Yoru recognises.");
        var areas = new ArrayList<Area>();
        var seen = new HashSet<Integer>();
        for (int at = start; !terminator(rom, at); at += HEADER) {
            int group = rom.u8(at), number = rom.u8(at + 1);
            if (!seen.add(group << 8 | number)) continue;
            var tables = new ArrayList<Table>();
            for (int i = 0; i < METHODS.length; i++) {
                long info = rom.u32(at + 4 + 4 * i);
                if (info == 0) continue;
                int table = rom.offset(info);
                int slots = rom.offset(rom.u32(table + 4));
                // header() only ever points find() at tables whose slots are all
                // inside the ROM, which is what read() relies on here.
                var read = new ArrayList<Slot>();
                for (int s = 0; s < METHODS[i].slots(); s++) {
                    int slot = slots + 4 * s;
                    read.add(new Slot(rom.u8(slot), rom.u8(slot + 1), SpeciesIds.nationalOf(rom.u16(slot + 2))));
                }
                tables.add(new Table(METHODS[i], rom.u8(table), List.copyOf(read)));
            }
            areas.add(new Area(group, number, List.copyOf(tables)));
        }
        return List.copyOf(areas);
    }

    /** Where the header table starts, or -1 when there is no run long enough to be one. */
    static int find(Rom rom) {
        int best = -1, bestLength = 0;
        for (int start = 0; start + HEADER <= rom.size(); start += 4) {
            if (!header(rom, start)) continue;
            int length = 0, at = start;
            while (at + HEADER <= rom.size() && header(rom, at)) { length++; at += HEADER; }
            if (terminator(rom, at) && length > bestLength) { best = start; bestLength = length; }
            // Everything inside that run is a shorter copy of it; carry on past its end.
            start = at - 4;
        }
        return bestLength >= MIN_AREAS ? best : -1;
    }

    private static boolean terminator(Rom rom, int at) {
        return at + 2 <= rom.size() && rom.u8(at) == TERMINATOR && rom.u8(at + 1) == TERMINATOR;
    }

    /**
     * A plausible header: a real map group, zero padding, and at least one
     * pointer to a well-formed table.
     *
     * A table is only well-formed when the slots it points at are in the ROM as
     * well: a pointer near the end of a malformed file would otherwise be read
     * past it, one four-byte slot at a time. Refusing it here is what turns such
     * a file into the same clean "no wild encounter table Yoru recognises" as a
     * file with no table at all.
     */
    private static boolean header(Rom rom, int at) {
        if (rom.u8(at) >= MAP_GROUPS || rom.u8(at + 2) != 0 || rom.u8(at + 3) != 0) return false;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            long pointer = rom.u32(at + 4 + 4 * i);
            if (pointer == 0) continue;
            if (!rom.pointer(pointer)) return false;
            int table = rom.offset(pointer);
            if (!rom.within(table, 8) || rom.u8(table) > 100 || !rom.pointer(rom.u32(table + 4))) return false;
            int slots = rom.offset(rom.u32(table + 4));
            if (!rom.within(slots, 4 * METHODS[i].slots())) return false;
            any = true;
        }
        return any;
    }
}
