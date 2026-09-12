package dev.yoru.game;

/**
 * The six experience curves (#29).
 *
 * A Pokémon in a box stores no level — the game works it out from experience
 * every time it reads one. So these formulas decide what level a study-earned
 * companion actually appears at, and two of the six are piecewise: Erratic
 * changes shape three times and Fluctuating twice. A mis-transcribed boundary
 * is correct at both ends of the curve and wrong in the middle, which is the
 * hardest kind of error to notice by trying it.
 *
 * They are checked three ways: against the published level-100 totals, against
 * values worked out by hand near the boundaries, and structurally — every curve
 * must rise, and {@link Experience#levelFor} must invert
 * {@link Experience#required} at every one of the 600 (curve, level) pairs.
 */
public final class ExperienceTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    /** The totals every reference agrees on, which pin each curve end to end. */
    private static final int[] AT_HUNDRED = {
        1_000_000,   // Medium Fast
        600_000,     // Erratic
        1_640_000,   // Fluctuating
        1_059_860,   // Medium Slow
        800_000,     // Fast
        1_250_000,   // Slow
    };

    private static void matchesTheKnownTotals() {
        for (int curve = 0; curve < 6; curve++) {
            int got = Experience.required(curve, 100);
            check(got == AT_HUNDRED[curve], Experience.NAMES[curve]
                + " reaches " + AT_HUNDRED[curve] + " at level 100, got " + got);
            check(Experience.required(curve, 1) == 0, Experience.NAMES[curve] + " starts at zero");
        }
    }

    /**
     * Values computed by hand, including inside every piecewise branch.
     *
     * The level-100 totals alone would not catch a wrong boundary — both
     * Erratic and Fluctuating are on their final branch by then. These sit on
     * either side of each seam.
     */
    private static void matchesValuesWorkedOutByHand() {
        // Medium Slow: 6n^3/5 - 15n^2 + 100n - 140, at n=2 -> 9 - 60 + 200 - 140.
        check(Experience.required(Experience.MEDIUM_SLOW, 2) == 9,
            "Medium Slow level 2 is 9, got " + Experience.required(Experience.MEDIUM_SLOW, 2));

        // Erratic branch one, n<50: n^3(100-n)/50, at n=2 -> 8*98/50 = 15.
        check(Experience.required(Experience.ERRATIC, 2) == 15,
            "Erratic level 2 is 15, got " + Experience.required(Experience.ERRATIC, 2));
        // Erratic branch three, 68<=n<98: n^3*floor((1911-10n)/3)/500.
        // At n=68: 314432 * 410 / 500 = 257834.
        check(Experience.required(Experience.ERRATIC, 68) == 257_834,
            "Erratic level 68 is 257834, got " + Experience.required(Experience.ERRATIC, 68));

        // Fluctuating branch one, n<15: n^3(floor((n+1)/3)+24)/50, at n=2 -> 8*25/50 = 4.
        check(Experience.required(Experience.FLUCTUATING, 2) == 4,
            "Fluctuating level 2 is 4, got " + Experience.required(Experience.FLUCTUATING, 2));

        check(Experience.required(Experience.MEDIUM_FAST, 50) == 125_000, "Medium Fast is n cubed");
        check(Experience.required(Experience.FAST, 50) == 100_000, "Fast is four fifths of that");
        check(Experience.required(Experience.SLOW, 50) == 156_250, "Slow is five quarters of it");
    }

    /**
     * Where the piecewise curves join, and where they genuinely part.
     *
     * Two of the seams are continuous: Erratic's branches both give 125,000 at
     * level 50, and Fluctuating's both give 42,017 at level 35 and 46,656 at
     * 36. Moving those boundaries by one therefore changes nothing, which is
     * worth writing down — it explains why a mutation there survives without
     * that being a gap. The boundary starts to matter at level 37, and that is
     * asserted too, so a boundary moved far enough to be wrong is still caught.
     */
    private static void piecewiseSeamsJoinCleanly() {
        check(Experience.required(Experience.ERRATIC, 50) == 125_000,
            "Erratic's first seam is continuous at level 50");
        check(Experience.required(Experience.FLUCTUATING, 35) == 42_017,
            "Fluctuating agrees at level 35");
        check(Experience.required(Experience.FLUCTUATING, 36) == 46_656,
            "and at level 36");
        // Level 37 is the first place the two Fluctuating branches disagree:
        // (37+14)/50 gives 51,666 while (37/2+32)/50 gives 50,653.
        check(Experience.required(Experience.FLUCTUATING, 37) == 50_653,
            "and takes the third branch from level 37, got "
                + Experience.required(Experience.FLUCTUATING, 37));
        check(Experience.required(Experience.ERRATIC, 51) == 131_324,
            "Erratic's second branch is in force by level 51, got "
                + Experience.required(Experience.ERRATIC, 51));
    }

    /**
     * Every curve rises, at every step.
     *
     * A branch written with the wrong comparison or a copied constant usually
     * shows up as a dip at the seam, which this catches wherever it happens
     * rather than only where a hand-checked value was chosen.
     */
    private static void everyCurveRises() {
        for (int curve = 0; curve < 6; curve++) {
            int previous = -1;
            for (int level = 1; level <= Experience.MAX_LEVEL; level++) {
                int now = Experience.required(curve, level);
                check(now > previous, Experience.NAMES[curve] + " rises at level " + level
                    + ": " + previous + " then " + now);
                previous = now;
            }
        }
    }

    /**
     * levelFor inverts required, everywhere.
     *
     * This is the direction the game itself reads, so it decides what an
     * injected companion looks like. One below a threshold must give the level
     * below — a boundary that is inclusive on the wrong side puts a Pokémon
     * one level off, which looks like a rounding quirk rather than a bug.
     */
    private static void levelForInvertsRequired() {
        for (int curve = 0; curve < 6; curve++) {
            for (int level = 1; level <= Experience.MAX_LEVEL; level++) {
                int exact = Experience.required(curve, level);
                check(Experience.levelFor(curve, exact) == level,
                    Experience.NAMES[curve] + " reads level " + level + " back from " + exact
                        + ", got " + Experience.levelFor(curve, exact));
                if (level > 1)
                    check(Experience.levelFor(curve, exact - 1) == level - 1,
                        Experience.NAMES[curve] + " one short of level " + level + " is level "
                            + (level - 1) + ", got " + Experience.levelFor(curve, exact - 1));
            }
            check(Experience.levelFor(curve, 0) == 1, "nothing is level one");
            check(Experience.levelFor(curve, Integer.MAX_VALUE) == 100, "beyond the top is level 100");
        }
    }

    /** A species uses its own curve, not a default. */
    private static void speciesUseTheirOwnCurve() {
        check(SpeciesIds.growthOf(1) == Experience.MEDIUM_SLOW, "Bulbasaur is Medium Slow");
        check(SpeciesIds.growthOf(10) == Experience.MEDIUM_FAST, "Caterpie is Medium Fast");
        check(SpeciesIds.growthOf(129) == Experience.SLOW, "Magikarp is Slow");
        check(SpeciesIds.growthOf(113) == Experience.FAST, "Chansey is Fast");

        int bulbasaur = Experience.requiredFor(1, 20), caterpie = Experience.requiredFor(10, 20);
        check(bulbasaur != caterpie,
            "two species on different curves need different experience at the same level");
        check(bulbasaur == Experience.required(Experience.MEDIUM_SLOW, 20),
            "and each uses the curve its own base stats name");

        // A companion delivered at a level must read back at that level.
        for (int national : new int[]{1, 10, 25, 113, 129, 252, 282, 386})
            for (int level : new int[]{5, 20, 50, 100}) {
                int curve = SpeciesIds.growthOf(national);
                check(Experience.levelFor(curve, Experience.requiredFor(national, level)) == level,
                    "National " + national + " delivered at level " + level + " reads back as "
                        + Experience.levelFor(curve, Experience.requiredFor(national, level)));
            }
    }

    /** Levels outside 1-100 and unknown curves are refused. */
    private static void refusesNonsense() {
        for (int[] bad : new int[][]{{0, 0}, {0, 101}, {0, -1}, {6, 5}, {-1, 5}}) {
            boolean rejected = false;
            try { Experience.required(bad[0], bad[1]); }
            catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "curve " + bad[0] + " level " + bad[1] + " is refused");
        }
    }

    public static void main(String[] args) {
        matchesTheKnownTotals();
        matchesValuesWorkedOutByHand();
        piecewiseSeamsJoinCleanly();
        everyCurveRises();
        levelForInvertsRequired();
        speciesUseTheirOwnCurve();
        refusesNonsense();
        System.out.println("PASS: "+checks+" experience checks (six curves, boundaries, inversion)");
    }
}
