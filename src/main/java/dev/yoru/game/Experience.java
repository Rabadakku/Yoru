package dev.yoru.game;

/**
 * The six experience curves, and the level they imply.
 *
 * A Pokémon in a box has no level field. The game derives it from experience
 * every time it reads one, so writing a level-20 companion into a save means
 * writing the exact experience total that level 20 requires <em>for that
 * species' curve</em>. Get it wrong by one and the game shows a different
 * level than intended; get the curve wrong and it can be many levels out.
 *
 * The formulas are the published ones, and every step of integer division
 * truncates where the game truncates. They are checked against the level-100
 * totals, which are the standard reference values for each curve — a curve
 * that lands on those is not subtly wrong somewhere in the middle.
 */
public final class Experience {

    /** Indices are the ROM's own enum, not an ordering chosen here. */
    public static final int MEDIUM_FAST = 0, ERRATIC = 1, FLUCTUATING = 2,
        MEDIUM_SLOW = 3, FAST = 4, SLOW = 5;

    public static final int MAX_LEVEL = 100;

    /** The names, for anything that has to say which curve it used. */
    public static final String[] NAMES = {
        "Medium Fast", "Erratic", "Fluctuating", "Medium Slow", "Fast", "Slow"
    };

    private Experience() { }

    /**
     * Total experience needed to be at a level on a curve.
     *
     * Level 1 is always zero. Everything else follows the curve exactly,
     * including the piecewise boundaries, which are the part most often
     * mis-transcribed — Erratic changes shape three times and Fluctuating
     * twice.
     */
    public static int required(int curve, int level) {
        if (level < 1 || level > MAX_LEVEL)
            throw new IllegalArgumentException("Level " + level + " is outside 1-" + MAX_LEVEL);
        if (level == 1) return 0;
        long n = level, cube = n * n * n;
        return switch (curve) {
            case MEDIUM_FAST -> (int) cube;
            case FAST -> (int) (4 * cube / 5);
            case SLOW -> (int) (5 * cube / 4);
            case MEDIUM_SLOW -> (int) (6 * cube / 5 - 15 * n * n + 100 * n - 140);
            case ERRATIC -> {
                if (n < 50) yield (int) (cube * (100 - n) / 50);
                if (n < 68) yield (int) (cube * (150 - n) / 100);
                if (n < 98) yield (int) (cube * ((1911 - 10 * n) / 3) / 500);
                yield (int) (cube * (160 - n) / 100);
            }
            case FLUCTUATING -> {
                if (n < 15) yield (int) (cube * ((n + 1) / 3 + 24) / 50);
                if (n < 36) yield (int) (cube * (n + 14) / 50);
                yield (int) (cube * (n / 2 + 32) / 50);
            }
            default -> throw new IllegalArgumentException("No growth curve " + curve);
        };
    }

    /**
     * The level a given experience total sits at on a curve.
     *
     * This is the direction the game itself works in, so it is what decides
     * whether an injected companion shows up at the level intended. Walking
     * down from the top rather than solving anything: the curves are not all
     * monotonic in a form worth inverting, and a hundred comparisons costs
     * nothing.
     */
    public static int levelFor(int curve, int experience) {
        for (int level = MAX_LEVEL; level >= 1; level--)
            if (experience >= required(curve, level)) return level;
        return 1;
    }

    /** Total experience for a National Dex species to reach a level. */
    public static int requiredFor(int nationalDexNumber, int level) {
        return required(SpeciesIds.growthOf(nationalDexNumber), level);
    }
}
