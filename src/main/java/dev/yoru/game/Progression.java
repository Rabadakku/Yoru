package dev.yoru.game;

import java.util.HashMap;
import java.util.Map;

/**
 * Where a player can be, by the badges they hold — the hand-written half of
 * study encounters (#45).
 *
 * The ROM says which Pokémon live where; it does not say when a player can
 * get there. That comes from the story: Rock Smash works outside battle after
 * the third badge, Surf after the fifth, and each town opens the next stretch
 * of routes. This table records, for every area with wild Pokémon, the badges
 * a player holds by the time Emerald's story reliably takes them into it. 9
 * means after entering the Hall of Fame. Areas the game never uses are left
 * out, so they are never offered.
 *
 * It is deliberately conservative: an area is listed where the story brings a
 * player, not the earliest a determined player could slip into it, so study
 * never offers a Pokémon ahead of the campaign. Names are the ones the game's
 * own map shows.
 */
public final class Progression {

    public static final int HALL_OF_FAME = 9;
    /** Badges each way of meeting Pokémon needs, beyond reaching the area. */
    public static final int ROCK_SMASH = 3, SURF = 5, OLD_ROD = 1, GOOD_ROD = 4, SUPER_ROD = 7;

    private record Place(String name, int badges) { }

    private static final Map<Integer, Place> PLACES = new HashMap<>();

    private static void put(int group, int number, String name, int badges) {
        PLACES.put(group << 8 | number, new Place(name, badges));
    }

    static {
        // Towns and routes, map group 0.
        put(0, 0, "Petalburg City", 0);
        put(0, 1, "Slateport City", 2);
        put(0, 5, "Lilycove City", 6);
        put(0, 6, "Mossdeep City", 7);
        put(0, 7, "Sootopolis City", 7);
        put(0, 8, "Ever Grande City", 8);
        put(0, 11, "Dewford Town", 1);
        put(0, 15, "Pacifidlog Town", 7);
        put(0, 16, "Route 101", 0);
        put(0, 17, "Route 102", 0);
        put(0, 18, "Route 103", 0);
        put(0, 19, "Route 104", 0);
        put(0, 20, "Route 105", SURF);
        put(0, 21, "Route 106", 1);
        put(0, 22, "Route 107", SURF);
        put(0, 23, "Route 108", SURF);
        put(0, 24, "Route 109", 2);
        put(0, 25, "Route 110", 2);
        put(0, 26, "Route 111", 4);
        put(0, 27, "Route 112", 3);
        put(0, 28, "Route 113", 3);
        put(0, 29, "Route 114", 3);
        put(0, 30, "Route 115", 4);
        put(0, 31, "Route 116", 0);
        put(0, 32, "Route 117", 2);
        put(0, 33, "Route 118", 4);
        put(0, 34, "Route 119", 5);
        put(0, 35, "Route 120", 6);
        put(0, 36, "Route 121", 6);
        put(0, 37, "Route 122", 6);
        put(0, 38, "Route 123", 6);
        put(0, 39, "Route 124", 6);
        for (int route = 125; route <= 134; route++) put(0, route - 85, "Route " + route, 7);
        put(0, 50, "Underwater Route 124", 7);
        put(0, 51, "Underwater Route 126", 7);

        // Caves and dungeons, map group 24.
        put(24, 0, "Meteor Falls", 3);
        for (int map : new int[]{1, 2, 3}) put(24, map, "Meteor Falls", 8);
        put(24, 4, "Rusturf Tunnel", 0);
        for (int map = 7; map <= 10; map++) put(24, map, "Granite Cave", 1);
        put(24, 11, "Petalburg Woods", 0);
        put(24, 13, "Jagged Pass", 3);
        put(24, 14, "Fiery Path", 3);
        for (int map = 15; map <= 22; map++) put(24, map, "Mt. Pyre", 6);
        for (int map = 27; map <= 35; map++) put(24, map, "Seafloor Cavern", 7);
        put(24, 37, "Cave of Origin", 7);
        put(24, 38, "Cave of Origin", 7);
        for (int map = 43; map <= 45; map++) put(24, map, "Victory Road", 8);
        for (int map : new int[]{46, 47, 48, 49, 83}) put(24, map, "Shoal Cave", 7);
        put(24, 52, "New Mauville", SURF);
        put(24, 53, "New Mauville", SURF);
        put(24, 58, "Abandoned Ship", SURF);
        put(24, 65, "Abandoned Ship", 7);
        for (int map : new int[]{79, 81, 84}) put(24, map, "Sky Pillar", 7);
        for (int map = 86; map <= 93; map++) put(24, map, "Magma Hideout", 6);
        for (int map = 94; map <= 97; map++) put(24, map, "Mirage Tower", 4);
        put(24, 98, "Desert Underpass", HALL_OF_FAME);
        put(24, 99, "Artisan Cave", HALL_OF_FAME);
        put(24, 100, "Artisan Cave", HALL_OF_FAME);
        put(24, 106, "Altering Cave", SURF);
        put(24, 107, "Meteor Falls", HALL_OF_FAME);

        // The Safari Zone, map group 26; its last two areas open after the Hall of Fame.
        for (int map = 0; map <= 3; map++) put(26, map, "Safari Zone", 6);
        put(26, 12, "Safari Zone", HALL_OF_FAME);
        put(26, 13, "Safari Zone", HALL_OF_FAME);
    }

    private Progression() { }

    /** A player's standing, as their save records it. */
    public record Progress(int badges, boolean hallOfFame) {
        public Progress {
            if (badges < 0 || badges > 8) throw new IllegalArgumentException("Badges run from 0 to 8.");
        }
        /** Badges held, or 9 once the player has entered the Hall of Fame. */
        public int level() { return hallOfFame ? HALL_OF_FAME : badges; }
        public static Progress start() { return new Progress(0, false); }
    }

    /** Badges needed to reach an area, or -1 for one study never offers. */
    public static int badges(int group, int number) {
        var place = PLACES.get(group << 8 | number);
        return place == null ? -1 : place.badges();
    }

    /** The area's name as the game's map shows it, or null for one study never offers. */
    public static String name(int group, int number) {
        var place = PLACES.get(group << 8 | number);
        return place == null ? null : place.name();
    }

    /** Whether one slot of an area's table can be met at this level of progress. */
    public static boolean open(int areaBadges, WildEncounters.Method method, int slot, int level) {
        if (areaBadges < 0 || level < areaBadges) return false;
        int needed = switch (method) {
            case LAND -> 0;
            case WATER -> SURF;
            case ROCK_SMASH -> ROCK_SMASH;
            case FISHING -> slot < 2 ? OLD_ROD : slot < 5 ? GOOD_ROD : SUPER_ROD;
        };
        return level >= needed;
    }

    /**
     * Tables study never draws from, even once their area is open: Route 130's
     * land is Mirage Island, which appears only on rare days — and in the
     * supported variant it holds legendary Pokémon at level 5.
     */
    public static boolean rare(int group, int number, WildEncounters.Method method) {
        return group == 0 && number == 45 && method == WildEncounters.Method.LAND;
    }

    /** Which rod a fishing slot belongs to: 0 for the Old Rod, 1 the Good Rod, 2 the Super Rod. */
    static int rod(int slot) { return slot < 2 ? 0 : slot < 5 ? 1 : 2; }
}
