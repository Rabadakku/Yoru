package dev.yoru.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * What a study encounter turns out to be (#45).
 *
 * Every thirty recorded minutes earns an encounter, and what appears comes from
 * the player's own game. A place their badges have opened is chosen, then a way
 * of meeting Pokémon there, then a slot by the game's own chances, and a level
 * from that slot's range — so it is always a Pokémon that place could really
 * have offered at that point in the story.
 *
 * <h2>How the choice is weighted</h2>
 * <ul>
 * <li>By place, not by map: Mt. Pyre's eight floors are one place, so a
 *     many-floored dungeon is no likelier than a single route.</li>
 * <li>Most of the grind happens walking through grass, so land counts six times
 *     as much as each rod, and surfing twice as much.</li>
 * <li>Places from the newest tier of progress count three times as much as
 *     older ones, the way a player mostly trains where they now are. Older
 *     places still turn up, so nothing becomes unreachable.</li>
 * </ul>
 *
 * <h2>Stable, not rerolled</h2>
 * The randomness comes from the encounter's reward id, which the campaign fixes
 * in advance. Closing an encounter and opening it again shows the same Pokémon;
 * only progress in the game can change what an unopened one will be.
 */
public final class StudyEncounter {

    public record Found(UUID reward, int national, int level, String area) { }

    /** How much likelier a place from the latest tiers of progress is than an older one. */
    static final int RECENT = 3;

    private StudyEncounter() { }

    /** How much each way of meeting Pokémon counts; each rod counts separately. */
    static int weight(WildEncounters.Method method) {
        return switch (method) {
            case LAND -> 6;
            case WATER -> 2;
            case ROCK_SMASH, FISHING -> 1;
        };
    }

    /** One way of meeting Pokémon at one place: its method, and the rod when fishing. */
    private record Way(String place, WildEncounters.Method method, int rod) { }

    /** One map's table for that way, with the slots open at this progress. */
    private record Choice(WildEncounters.Table table, int[] slots, boolean recent) { }

    /**
     * The encounter carrying this reward id, for a player at this progress, or
     * null when nothing in these tables is open — which only happens with tables
     * this build does not recognise.
     */
    public static Found roll(UUID reward, List<WildEncounters.Area> areas, Progression.Progress progress) {
        int level = progress.level();
        var ways = new LinkedHashMap<Way, List<Choice>>();
        for (var area : areas) {
            int needed = Progression.badges(area.group(), area.number());
            if (needed < 0 || needed > level) continue;
            String place = Progression.name(area.group(), area.number());
            boolean recent = needed >= level - 1;
            for (var table : area.tables()) {
                if (Progression.rare(area.group(), area.number(), table.method())) continue;
                boolean fishing = table.method() == WildEncounters.Method.FISHING;
                for (int rod = 0; rod < (fishing ? 3 : 1); rod++) {
                    var open = new ArrayList<Integer>();
                    for (int s = 0; s < table.slots().size(); s++)
                        if ((!fishing || Progression.rod(s) == rod) && table.slots().get(s).national() != 0
                            && Progression.open(needed, table.method(), s, level)) open.add(s);
                    if (!open.isEmpty())
                        ways.computeIfAbsent(new Way(place, table.method(), rod), way -> new ArrayList<>())
                            .add(new Choice(table, open.stream().mapToInt(Integer::intValue).toArray(), recent));
                }
            }
        }
        if (ways.isEmpty()) return null;

        var random = new SplittableRandom(reward.getMostSignificantBits() ^ Long.rotateLeft(reward.getLeastSignificantBits(), 17));
        var keys = new ArrayList<>(ways.keySet());
        var weights = new long[keys.size()];
        long total = 0;
        for (int i = 0; i < keys.size(); i++) {
            boolean recent = ways.get(keys.get(i)).stream().anyMatch(Choice::recent);
            weights[i] = (long) weight(keys.get(i).method()) * (recent ? RECENT : 1);
            total += weights[i];
        }
        long pick = random.nextLong(total);
        Way way = keys.getLast();
        for (int i = 0; i < keys.size(); i++) {
            pick -= weights[i];
            if (pick < 0) { way = keys.get(i); break; }
        }
        var choices = ways.get(way);
        var choice = choices.get(random.nextInt(choices.size()));

        int[] chances = choice.table().method().chances;
        int sum = 0;
        for (int s : choice.slots()) sum += chances[s];
        int roll = random.nextInt(sum), slotIndex = choice.slots()[0];
        for (int s : choice.slots()) {
            roll -= chances[s];
            if (roll < 0) { slotIndex = s; break; }
        }
        var slot = choice.table().slots().get(slotIndex);
        int low = Math.min(slot.minLevel(), slot.maxLevel()), high = Math.max(slot.minLevel(), slot.maxLevel());
        int at = Math.max(1, Math.min(Experience.MAX_LEVEL, low + random.nextInt(high - low + 1)));
        return new Found(reward, slot.national(), at, way.place());
    }
}
