package dev.yoru.game;

import java.util.UUID;

/**
 * Builds the Pokémon a study session earned, in the form the real game accepts.
 *
 * Every field here is derived from something verified rather than chosen:
 * the internal species id from {@link SpeciesIds}, the experience total from
 * that species' own growth curve, the four moves from the real learnset, the
 * PP from the move table, and the trainer from the save itself. Nothing is
 * invented, because the game does not report a rejected field — it shows a
 * <b>Bad Egg</b>, or quietly holds a different Pokémon than the one intended.
 *
 * <h2>The personality carries the reward's identity</h2>
 * A reward's UUID is folded into the 32-bit personality value. That makes the
 * game save its own record of what has been delivered: asking "was this reward
 * already given?" is answered by looking for a companion whose personality
 * matches, with no separate bookkeeping to fall out of step. It also makes
 * delivery deterministic — the same reward always produces the same nature,
 * gender and stats, so a retry after a crash cannot produce a second, subtly
 * different Pokémon.
 *
 * Personality is load-bearing in Gen 3 beyond identity: it selects the
 * substructure order, the nature, the gender and (with the trainer id) whether
 * the companion is shiny. Deriving it from a UUID means all of those are
 * effectively random, which is exactly what a caught Pokémon looks like.
 */
public final class StudyGift {

    /** Emerald's own version id, written into a companion's origins. */
    public static final int EMERALD = 3;
    /** A plain Poké Ball. */
    public static final int POKE_BALL = 4;
    public static final int ENGLISH = 2;

    private StudyGift() { }

    /**
     * Folds a reward id into a personality value.
     *
     * Both halves of the UUID are mixed in, so two rewards differing only in
     * their low bits still land far apart. Zero is avoided because it is the
     * value an empty slot reads as, and a companion whose marker is zero could
     * not be told from an absent one.
     */
    public static int personalityFor(UUID reward) {
        long mixed = reward.getMostSignificantBits() * 31 + reward.getLeastSignificantBits();
        int value = (int) (mixed ^ (mixed >>> 32));
        return value == 0 ? 1 : value;
    }

    /**
     * Builds a companion of a species at a level, belonging to this save's own
     * trainer, identified by this reward.
     *
     * The trainer matters: a companion carrying a different id is a traded
     * Pokémon, which the game marks as met elsewhere and which disobeys past a
     * badge threshold. Reading the id from the save is what makes it the
     * player's own.
     */
    public static Gen3Pokemon build(UUID reward, int nationalDexNumber, int level,
                                    Gen3Save.Trainer trainer, String nickname, int metLocation) {
        if (level < 1 || level > Experience.MAX_LEVEL)
            throw new IllegalArgumentException("Level " + level + " is outside 1-100.");
        var mon = new Gen3Pokemon();
        mon.personality = personalityFor(reward);
        mon.otId = trainer.otId();
        mon.otName = trainer.name();
        mon.language = ENGLISH;
        mon.nickname = nickname != null && !nickname.isBlank()
            ? nickname : defaultName(nationalDexNumber);

        mon.species = SpeciesIds.internalOf(nationalDexNumber);
        mon.experience = Experience.requiredFor(nationalDexNumber, level);
        mon.friendship = SpeciesIds.friendshipOf(nationalDexNumber);
        mon.heldItem = 0;
        mon.ppBonuses = 0;
        mon.ribbons = 0;
        mon.pokerus = 0;
        mon.markings = 0;

        int[] moves = Learnsets.movesAt(nationalDexNumber, level);
        for (int i = 0; i < 4; i++) {
            mon.moves[i] = moves[i];
            mon.pp[i] = Learnsets.pp(moves[i]);
        }

        // Individual values from the personality, so the same reward always
        // rebuilds the same companion. Five bits each, spread across the whole
        // word rather than taken from one end, which would leave several stats
        // correlated for neighbouring rewards.
        int seed = mon.personality;
        var ivs = new int[6];
        for (int i = 0; i < 6; i++) {
            seed = seed * 1103515245 + 12345;
            ivs[i] = (seed >>> 16) & 0x1F;
        }
        mon.ivsEggAbility = 0;
        mon.setIvs(ivs);
        // Bit 30 is the egg flag and must stay clear: an egg with experience is
        // a contradiction the game does not expect. Bit 31 picks the ability
        // slot, which is a legitimate coin flip.
        mon.ivsEggAbility |= (mon.personality & 1) << 31;

        mon.metLocation = metLocation & 0xFF;
        // level met (0-6) | version (7-10) | ball (11-14) | trainer gender (15)
        mon.origins = (level & 0x7F)
            | (EMERALD << 7)
            | (POKE_BALL << 11)
            | ((trainer.gender() & 1) << 15);
        return mon;
    }

    /** The species' name, upper-cased as the games store an unnicknamed one. */
    public static String defaultName(int nationalDexNumber) {
        return SpeciesNames.of(nationalDexNumber).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether a save already holds the companion this reward would deliver.
     *
     * Looks for the reward's personality across every box. This is what makes
     * delivery idempotent: a crash between writing the save and recording the
     * reward as spent leaves the evidence in the save itself, so the retry can
     * see the work was already done rather than doing it twice.
     */
    public static boolean alreadyDelivered(Gen3Save save, byte[] storage, UUID reward) {
        return whereDelivered(save, storage, reward) != null;
    }

    /** Where a delivered companion is now: a PC slot, or a place in the party. */
    public record Location(int box, int slot, boolean inParty) {
        public String describe() {
            return inParty ? "in your party" : "box " + (box + 1) + ", slot " + (slot + 1);
        }
    }

    /**
     * Finds the companion a reward delivered, wherever the player has put it.
     *
     * The party is searched as well as every box. A player who withdrew the
     * companion and then saved has moved it out of the PC entirely; a search
     * of the boxes alone would conclude it had never arrived and deliver it a
     * second time.
     *
     * Personality is 32 bits, so a Pokémon caught in the wild could in
     * principle share a reward's marker. With the few hundred a save holds the
     * odds are below one in ten million, and the cost of a match is only that
     * one reward is recorded as delivered without being written — never a
     * duplicate and never a lost Pokémon.
     */
    public static Location whereDelivered(Gen3Save save, byte[] storage, UUID reward) {
        int wanted = personalityFor(reward);
        for (int box = 0; box < Gen3Save.BOXES; box++)
            for (int slot = 0; slot < Gen3Save.PER_BOX; slot++) {
                var occupant = save.boxed(storage, box, slot);
                if (occupant != null && occupant.personality == wanted) return new Location(box, slot, false);
            }
        var party = save.party();
        for (int i = 0; i < party.size(); i++)
            if (party.get(i).personality == wanted) return new Location(-1, i, true);
        return null;
    }
}
