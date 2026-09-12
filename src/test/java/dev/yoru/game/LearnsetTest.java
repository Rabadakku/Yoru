package dev.yoru.game;

/**
 * Learnsets, and the four moves a companion would actually hold (#29).
 *
 * The table itself is generated from the ROM and checked there against species
 * whose early moves are widely known. What is hand-written, and so what is
 * tested here, is {@link Learnsets#movesAt}: the game keeps the four most
 * recently learned moves and drops the oldest as new ones arrive, so a
 * companion delivered at level 15 must be holding what it learned at 7, 10 and
 * 15 — not what it learned at 1.
 *
 * Getting that backwards produces a Pokemon that is legal but obviously wrong:
 * a level-40 starter still knowing Tackle and Growl.
 */
public final class LearnsetTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final int TACKLE = 33, GROWL = 45, LEECH_SEED = 73, VINE_WHIP = 22,
        POISON_POWDER = 77, SLEEP_POWDER = 79;

    /** The table agrees with what everyone knows about the first starter. */
    private static void theTableIsTheRealOne() {
        var all = Learnsets.all(1);
        check(all[0][0] == 1 && all[0][1] == TACKLE, "Bulbasaur opens with Tackle at level 1");
        check(all[1][0] == 4 && all[1][1] == GROWL, "then Growl at 4");
        check(all[2][0] == 7 && all[2][1] == LEECH_SEED, "then Leech Seed at 7");
        check(all[3][0] == 10 && all[3][1] == VINE_WHIP, "then Vine Whip at 10");

        // Levels never go backwards — the window logic depends on that order.
        for (int national : new int[]{1, 25, 129, 252, 282, 386}) {
            int last = 0;
            for (var pair : Learnsets.all(national)) {
                check(pair[0] >= last, "National " + national + " learns in level order, "
                    + last + " then " + pair[0]);
                last = pair[0];
                check(pair[1] > 0 && pair[1] <= 354, "and every move id is real, got " + pair[1]);
            }
        }
    }

    /** Below the first threshold, only what has actually been learned. */
    private static void earlyLevelsHoldFewMoves() {
        var five = Learnsets.movesAt(1, 5);
        check(five[0] == TACKLE && five[1] == GROWL, "at level 5 Bulbasaur has Tackle and Growl");
        check(five[2] == 0 && five[3] == 0, "and two empty slots, not padding with something else");

        var one = Learnsets.movesAt(1, 1);
        check(one[0] == TACKLE && one[1] == 0, "at level 1 it knows only Tackle");
    }

    /**
     * Past four moves, the oldest is dropped.
     *
     * Bulbasaur at 15 has learned Tackle, Growl, Leech Seed, Vine Whip,
     * PoisonPowder and Sleep Powder — six. It should be holding the last four.
     */
    private static void theOldestMovesAreDropped() {
        var fifteen = Learnsets.movesAt(1, 15);
        check(fifteen[0] == LEECH_SEED && fifteen[1] == VINE_WHIP
            && fifteen[2] == POISON_POWDER && fifteen[3] == SLEEP_POWDER,
            "at 15 it holds the four most recent, got " + java.util.Arrays.toString(fifteen));
        for (int move : fifteen)
            check(move != TACKLE && move != GROWL, "and no longer the two it started with");
    }

    /** No species ever holds the same move twice. */
    private static void noDuplicates() {
        for (int national = 1; national <= 386; national++)
            for (int level : new int[]{5, 20, 50, 100}) {
                var moves = Learnsets.movesAt(national, level);
                for (int i = 0; i < 4; i++)
                    for (int j = i + 1; j < 4; j++)
                        if (moves[i] != 0)
                            check(moves[i] != moves[j], "National " + national + " at level " + level
                                + " holds " + moves[i] + " twice");
            }
    }

    /** Every species has at least one move at every level it can exist at. */
    private static void everySpeciesCanAttack() {
        int empty = 0;
        for (int national = 1; national <= 386; national++) {
            var moves = Learnsets.movesAt(national, 5);
            if (moves[0] == 0) empty++;
        }
        check(empty == 0, empty + " species would arrive at level 5 with no moves at all");

        for (int national = 1; national <= 386; national++) {
            var moves = Learnsets.movesAt(national, 100);
            check(moves[0] != 0, "National " + national + " knows something at level 100");
            for (int move : moves)
                if (move != 0) check(Learnsets.pp(move) > 0,
                    "move " + move + " on National " + national + " has real PP");
        }
    }

    /** PP comes from the move table, not a guess. */
    private static void ppIsTheGames() {
        check(Learnsets.pp(TACKLE) == 35, "Tackle has 35 PP, got " + Learnsets.pp(TACKLE));
        check(Learnsets.pp(GROWL) == 40, "Growl has 40, got " + Learnsets.pp(GROWL));
        check(Learnsets.pp(LEECH_SEED) == 10, "Leech Seed has 10, got " + Learnsets.pp(LEECH_SEED));
        check(Learnsets.pp(52) == 25, "Ember has 25, got " + Learnsets.pp(52));
        check(Learnsets.pp(0) == 0, "an empty move slot has no PP");
    }

    /** A species outside the dex is refused rather than silently empty. */
    private static void refusesUnknownSpecies() {
        for (int bad : new int[]{0, 387, -1}) {
            boolean rejected = false;
            try { Learnsets.all(bad); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "National " + bad + " is refused");
        }
    }

    public static void main(String[] args) {
        theTableIsTheRealOne();
        earlyLevelsHoldFewMoves();
        theOldestMovesAreDropped();
        noDuplicates();
        everySpeciesCanAttack();
        ppIsTheGames();
        refusesUnknownSpecies();
        System.out.println("PASS: "+checks+" learnset checks (real table, four-move window, PP)");
    }
}
