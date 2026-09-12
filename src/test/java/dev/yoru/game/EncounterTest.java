package dev.yoru.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Study encounters follow the campaign (#45).
 *
 * The ROM's tables are replaced here by a synthetic image with the same layout,
 * each area holding one species of its own, so every roll can be traced back
 * to the area it came from. The real game's tables never enter the repository.
 */
public final class EncounterTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** One synthetic area: where it is, and the one species its land table holds. */
    private record Spec(int group, int number, int national, int low, int high, boolean land, boolean water, boolean fishing) { }

    /** Species used for the special tables, so they can be told apart from land Pokémon. */
    private static final int WATER = 72, OLD_ROD = 129, GOOD_ROD = 118, SUPER_ROD = 119;

    private static final List<Spec> AREAS = List.of(
        new Spec(0, 16, 261, 2, 3, true, false, false),    // Route 101, 0 badges
        new Spec(0, 17, 263, 3, 4, true, false, false),    // Route 102
        new Spec(0, 18, 265, 2, 3, true, false, false),    // Route 103
        new Spec(0, 19, 270, 3, 5, true, false, true),     // Route 104, with fishing
        new Spec(0, 31, 293, 6, 8, true, false, false),    // Route 116
        new Spec(24, 11, 285, 5, 6, true, false, false),   // Petalburg Woods
        new Spec(24, 4, 294, 5, 8, true, false, false),    // Rusturf Tunnel
        new Spec(24, 7, 296, 6, 10, true, false, false),   // Granite Cave 1F, 1 badge
        new Spec(24, 8, 304, 9, 11, true, false, false),   // Granite Cave B1F: the same place
        new Spec(0, 25, 309, 12, 13, true, false, false),  // Route 110, 2 badges
        new Spec(0, 32, 313, 13, 14, true, false, false),  // Route 117
        new Spec(0, 27, 322, 14, 16, true, false, false),  // Route 112, 3 badges
        new Spec(0, 28, 327, 14, 16, true, false, false),  // Route 113
        new Spec(0, 29, 333, 15, 18, true, false, false),  // Route 114
        new Spec(24, 14, 324, 14, 16, true, false, false), // Fiery Path
        new Spec(0, 26, 328, 19, 22, true, false, false),  // Route 111, 4 badges
        new Spec(0, 33, 352, 24, 27, true, false, false),  // Route 118
        new Spec(0, 34, 357, 24, 27, true, false, false),  // Route 119, 5 badges
        new Spec(0, 35, 359, 25, 27, true, false, false),  // Route 120, 6 badges
        new Spec(24, 43, 42, 36, 40, true, false, false),  // Victory Road, 8 badges
        new Spec(0, 20, 0, 0, 0, false, true, false),      // Route 105: water only, needs Surf
        new Spec(24, 39, 150, 30, 36, true, false, false), // an unused map: never offered
        new Spec(0, 45, 151, 5, 5, true, false, false)     // Route 130's land is Mirage Island: never offered
    );

    /** A 1 MB image with the header table, its tables and their slots laid out as the game has them. */
    static byte[] image() {
        var rom = new byte[1 << 20];
        int slots = 0x1000, tables = 0x40000, at = 0x80000;
        for (var spec : AREAS) {
            rom[at] = (byte) spec.group();
            rom[at + 1] = (byte) spec.number();
            if (spec.land()) {
                int start = slots;
                slots = fill(rom, slots, 12, spec.national(), spec.low(), spec.high());
                tables = table(rom, at + 4, tables, start, 20);
            }
            if (spec.water()) {
                int start = slots;
                slots = fill(rom, slots, 5, WATER, 10, 30);
                tables = table(rom, at + 8, tables, start, 4);
            }
            if (spec.fishing()) {
                int start = slots;
                slots = fill(rom, slots, 2, OLD_ROD, 5, 10);
                slots = fill(rom, slots, 3, GOOD_ROD, 10, 30);
                slots = fill(rom, slots, 5, SUPER_ROD, 25, 45);
                tables = table(rom, at + 16, tables, start, 30);
            }
            at += WildEncounters.HEADER;
        }
        rom[at] = (byte) 0xFF;
        rom[at + 1] = (byte) 0xFF;
        return rom;
    }

    /** Writes count slots of one species and returns the offset after them. */
    private static int fill(byte[] rom, int at, int count, int national, int low, int high) {
        for (int i = 0; i < count; i++) {
            rom[at] = (byte) low;
            rom[at + 1] = (byte) high;
            int internal = SpeciesIds.internalOf(national);
            rom[at + 2] = (byte) internal;
            rom[at + 3] = (byte) (internal >>> 8);
            at += 4;
        }
        return at;
    }

    /** Writes an 8-byte table pointing at slots, and its pointer into the header. Returns the next free table offset. */
    private static int table(byte[] rom, int pointerAt, int tableAt, int slotsAt, int rate) {
        put(rom, pointerAt, Rom.BASE + tableAt);
        rom[tableAt] = (byte) rate;
        put(rom, tableAt + 4, Rom.BASE + slotsAt);
        return tableAt + 8;
    }

    private static void put(byte[] rom, int at, long value) {
        for (int i = 0; i < 4; i++) rom[at + i] = (byte) (value >>> (8 * i));
    }

    private static UUID id(int n) { return new UUID(0x5EED_0000_0000_4000L | n, 0x8000_0000_0000_0000L | n * 7919L); }

    private static void theTableIsFoundAndRead() {
        var rom = Rom.of(image());
        check(WildEncounters.find(rom) == 0x80000, "the header table is found by its shape, got 0x" + Integer.toHexString(WildEncounters.find(rom)));
        var areas = WildEncounters.read(rom);
        check(areas.size() == AREAS.size(), "every area is read, got " + areas.size());
        var route101 = areas.getFirst();
        check(route101.group() == 0 && route101.number() == 16, "in the ROM's order");
        var land = route101.tables().getFirst();
        check(land.method() == WildEncounters.Method.LAND && land.slots().size() == 12 && land.rate() == 20,
            "Route 101 has a twelve-slot land table");
        check(land.slots().getFirst().national() == 261 && land.slots().getFirst().minLevel() == 2
            && land.slots().getFirst().maxLevel() == 3, "whose slots decode to the species and levels written");
        var route104 = areas.get(3);
        check(route104.tables().size() == 2 && route104.tables().get(1).method() == WildEncounters.Method.FISHING
            && route104.tables().get(1).slots().size() == 10, "and Route 104 has a ten-slot fishing table beside its land one");

        var noTerminator = image();
        noTerminator[0x80000 + AREAS.size() * WildEncounters.HEADER] = 0;
        boolean refused = false;
        try { WildEncounters.read(Rom.of(noTerminator)); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "a table without its terminator is not recognised");
    }

    /**
     * A pointer to slots that run past the end of the file is refused, not read.
     *
     * A header whose table sits just inside the ROM but whose slot table does not
     * used to be accepted as part of the run, and reading its twelve slots walked
     * off the end of the array. A malformed file must come back as "no table Yoru
     * recognises", the same answer a file with no table gets.
     */
    private static void aTableThatRunsPastTheEndIsRefused() {
        int areas = WildEncounters.MIN_AREAS, at = 0x1000;
        var rom = new byte[at + areas * WildEncounters.HEADER + 0x100];
        int table = rom.length - 12, slots = rom.length - 4;
        // The run is the only shape in the file: the bytes just before it are not
        // zeros a header could be read out of at one of the three earlier offsets.
        for (int k = 1; k <= WildEncounters.HEADER; k++) rom[at - k] = (byte) 0xEE;
        for (int i = 0; i < areas; i++) {
            rom[at + 1] = (byte) i;                  // a distinct map number each
            put(rom, at + 4, Rom.BASE + table);      // land: the only pointer set
            at += WildEncounters.HEADER;
        }
        rom[at] = (byte) 0xFF;
        rom[at + 1] = (byte) 0xFF;
        rom[table] = 20;
        put(rom, table + 4, Rom.BASE + slots);       // room for one slot, twelve read

        check(WildEncounters.find(Rom.of(rom)) == -1, "a slot table past the end is not part of the table");
        boolean refused = false;
        String message = null;
        try { WildEncounters.read(Rom.of(rom)); }
        catch (IllegalArgumentException e) { refused = true; message = e.getMessage(); }
        catch (RuntimeException e) { throw new AssertionError("a malformed pointer crashed with " + e); }
        check(refused, "so the file is refused as one Yoru does not recognise");
        check(message != null && message.contains("no wild encounter table"), "and says so, got " + message);
    }

    /** What each level of progress can offer, over many rolls. */
    private static Set<Integer> species(List<WildEncounters.Area> areas, int badges, int rolls) {
        var seen = new HashSet<Integer>();
        for (int n = 0; n < rolls; n++) {
            var found = StudyEncounter.roll(id(n), areas, new Progression.Progress(badges, false));
            seen.add(found.national());
            check(found.level() >= 1 && found.level() <= 100, "a level in range");
        }
        return seen;
    }

    private static void theCampaignGatesWhatAppears() {
        var areas = WildEncounters.read(Rom.of(image()));
        var start = species(areas, 0, 3000);
        check(start.equals(Set.of(261, 263, 265, 270, 293, 285, 294)),
            "with no badges, only the areas the opening reaches, got " + start);
        var one = species(areas, 1, 3000);
        check(one.contains(296) && one.contains(OLD_ROD), "the first badge opens Granite Cave and the Old Rod");
        check(!one.contains(GOOD_ROD) && !one.contains(WATER), "but not the Good Rod, nor Surf");
        var four = species(areas, 4, 3000);
        check(four.contains(GOOD_ROD) && !four.contains(SUPER_ROD) && !four.contains(WATER),
            "four badges bring the Good Rod, not yet the Super Rod or Surf");
        var five = species(areas, 5, 3000);
        check(five.contains(WATER) && five.contains(357), "five bring Surf, and Route 119");
        check(!five.contains(42), "Victory Road waits for the eighth badge");
        var eight = species(areas, 8, 3000);
        check(eight.contains(42) && eight.contains(SUPER_ROD), "which opens it, with the Super Rod long since");
        check(!eight.contains(150), "and an area the game never uses is never offered");
        check(!species(areas, 8, 3000).contains(151) && !species(new java.util.ArrayList<>(areas), 8, 1).contains(151),
            "nor Mirage Island, which appears only on rare days");
    }

    private static void rollsAreStableAndUseTheSlotsLevels() {
        var areas = WildEncounters.read(Rom.of(image()));
        var progress = new Progression.Progress(3, false);
        for (int n = 0; n < 200; n++) {
            var first = StudyEncounter.roll(id(n), areas, progress);
            var again = StudyEncounter.roll(id(n), areas, progress);
            check(first.equals(again), "the same encounter is the same Pokémon every time it is opened");
        }
        var ranges = new HashMap<Integer, int[]>();
        for (var spec : AREAS) if (spec.land()) ranges.put(spec.national(), new int[]{spec.low(), spec.high()});
        for (int n = 0; n < 2000; n++) {
            var found = StudyEncounter.roll(id(n), areas, progress);
            var range = ranges.get(found.national());
            if (range != null) check(found.level() >= range[0] && found.level() <= range[1],
                "levels come from the slot's own range, got " + found.level() + " for " + found.national());
        }
        var names = new HashSet<String>();
        for (int n = 0; n < 500; n++) names.add(StudyEncounter.roll(id(n), areas, Progression.Progress.start()).area());
        check(names.contains("Route 101") && names.contains("Petalburg Woods"), "and each names the area it came from");
    }

    /** The newest tier of progress is where most encounters happen, as grinding would be. */
    private static void recentAreasComeFirst() {
        var areas = WildEncounters.read(Rom.of(image()));
        Map<Integer, Integer> counts = new HashMap<>();
        int rolls = 6000;
        for (int n = 0; n < rolls; n++) counts.merge(StudyEncounter.roll(id(n), areas, new Progression.Progress(2, false)).national(), 1, Integer::sum);
        // At two badges: Route 110 and Route 117 are the newest tier, Granite Cave the one before,
        // and the seven opening areas are older, weighted a third as much.
        int recent = counts.getOrDefault(309, 0), older = counts.getOrDefault(261, 0);
        check(recent > older * 2, "a newest-tier area comes up well over twice as often as an opening one, got "
            + recent + " against " + older);
        check(older > rolls / 100, "but the opening areas still appear, got " + older);
    }

    /** A place is one place however many floors it has, and grass is where most of the grind is. */
    private static void placesAndMethodsAreWeighed() {
        var areas = WildEncounters.read(Rom.of(image()));
        Map<Integer, Integer> counts = new HashMap<>();
        for (int n = 0; n < 8000; n++) counts.merge(StudyEncounter.roll(id(n), areas, new Progression.Progress(2, false)).national(), 1, Integer::sum);
        int cave = counts.getOrDefault(296, 0) + counts.getOrDefault(304, 0), route = counts.getOrDefault(309, 0);
        check(counts.getOrDefault(304, 0) > 0, "both floors of Granite Cave are visited");
        check(cave < route * 3 / 2 && route < cave * 3 / 2,
            "but the two-floored cave is about as likely as a one-map route, got " + cave + " against " + route);

        counts.clear();
        for (int n = 0; n < 8000; n++) counts.merge(StudyEncounter.roll(id(n), areas, new Progression.Progress(1, false)).national(), 1, Integer::sum);
        int grass = counts.getOrDefault(270, 0), rod = counts.getOrDefault(OLD_ROD, 0);
        check(rod > 0 && grass > rod * 3, "on Route 104 the grass comes up far more often than the Old Rod, got " + grass + " against " + rod);
    }

    private static void namesAndGates() {
        check("Route 101".equals(Progression.name(0, 16)) && Progression.badges(0, 16) == 0, "Route 101 is open from the start");
        check("Granite Cave".equals(Progression.name(24, 8)) && Progression.badges(24, 8) == 1, "Granite Cave after the first badge");
        check(Progression.badges(24, 43) == 8, "Victory Road after the eighth");
        check(Progression.badges(26, 12) == Progression.HALL_OF_FAME, "the Safari Zone's new areas after the Hall of Fame");
        check(Progression.name(24, 39) == null && Progression.badges(24, 39) == -1, "unused maps are left out");
        check(!Progression.open(0, WildEncounters.Method.WATER, 0, 4) && Progression.open(0, WildEncounters.Method.WATER, 0, 5),
            "Surf needs the fifth badge");
        check(!Progression.open(0, WildEncounters.Method.ROCK_SMASH, 0, 2) && Progression.open(0, WildEncounters.Method.ROCK_SMASH, 0, 3),
            "Rock Smash the third");
        check(Progression.open(0, WildEncounters.Method.FISHING, 1, 1) && !Progression.open(0, WildEncounters.Method.FISHING, 2, 3)
            && !Progression.open(0, WildEncounters.Method.FISHING, 5, 6) && Progression.open(0, WildEncounters.Method.FISHING, 9, 7),
            "the rods their own badges");
        check(new Progression.Progress(8, true).level() == Progression.HALL_OF_FAME, "the Hall of Fame counts above eight badges");
    }

    public static void main(String[] args) {
        theTableIsFoundAndRead();
        aTableThatRunsPastTheEndIsRefused();
        theCampaignGatesWhatAppears();
        rollsAreStableAndUseTheSlotsLevels();
        recentAreasComeFirst();
        placesAndMethodsAreWeighed();
        namesAndGates();
        System.out.println("PASS: " + checks + " encounter checks (the ROM's tables, progression gates, stable rolls, recent areas)");
    }
}
