package dev.yoru.game;

import java.util.Arrays;
import java.util.UUID;

/**
 * Party stats, as the game calculates them when a Pokémon joins the party.
 *
 * A Pokémon placed straight into the party carries its stats inside the record,
 * and the game shows those until its next level-up — so a formula slip here
 * would be on screen for levels at a time. Checked against values worked out
 * by hand and against the published maximums, never against the formula
 * itself.
 */
public final class StatsTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** Bulbasaur at level 5 with nothing extra, worked through by hand. */
    private static void levelFiveByHand() {
        var zero = new int[6];
        // Base 45/49/49/65/65/45 (HP/Atk/Def/SpA/SpD/Spe):
        //   HP  = (2*45*5)/100 + 5 + 10 = 4 + 15 = 19
        //   Atk = (2*49*5)/100 + 5 = 4 + 5 = 9, and Def the same
        //   Spe = (2*45*5)/100 + 5 = 9
        //   SpA = (2*65*5)/100 + 5 = 6 + 5 = 11, and SpD the same
        // stored in the party record's order: HP, Atk, Def, Spe, SpA, SpD.
        check(Arrays.equals(Gen3Pokemon.stats(1, 5, zero, zero, 0), new int[]{19, 9, 9, 9, 11, 11}),
            "Hardy Bulbasaur at 5, got " + Arrays.toString(Gen3Pokemon.stats(1, 5, zero, zero, 0)));
        // Adamant (3) raises Attack and lowers Sp. Atk: 9*110/100 = 9 and 11*90/100 = 9.
        check(Arrays.equals(Gen3Pokemon.stats(1, 5, zero, zero, 3), new int[]{19, 9, 9, 9, 9, 11}),
            "a raised stat still truncates, got " + Arrays.toString(Gen3Pokemon.stats(1, 5, zero, zero, 3)));
        // The neutral natures change nothing.
        for (int neutral : new int[]{0, 6, 12, 18, 24})
            check(Arrays.equals(Gen3Pokemon.stats(1, 5, zero, zero, neutral), new int[]{19, 9, 9, 9, 11, 11}),
                "nature " + neutral + " is neutral");
    }

    /** The published maximums, which exercise every term at full size. */
    private static void thePublishedMaximums() {
        int[] max = {31, 31, 31, 31, 31, 31};
        int[] hp = {252, 0, 0, 0, 0, 0}, attack = {0, 252, 0, 0, 0, 0}, defense = {0, 0, 252, 0, 0, 0};
        check(Gen3Pokemon.stats(113, 100, max, hp, 0)[0] == 704, "Chansey's maximum HP is 704");
        check(Gen3Pokemon.stats(242, 100, max, hp, 0)[0] == 714, "Blissey's is 714");
        check(Gen3Pokemon.stats(150, 100, max, attack, 3)[1] == 350, "Adamant Mewtwo's maximum Attack is 350");
        check(Gen3Pokemon.stats(213, 100, max, defense, 5)[2] == 614, "Bold Shuckle's maximum Defense is 614");
        check(Gen3Pokemon.stats(Gen3Pokemon.SHEDINJA, 100, max, hp, 0)[0] == 1, "and Shedinja has 1 HP whatever else");
    }

    /** Every nature raises and lowers the stats gNatureStatTable says it does. */
    private static void naturesMatchTheTable() {
        // Rows of gNatureStatTable, as +1 / -1 per Attack, Defense, Speed, Sp. Atk, Sp. Def.
        int[][] table = {
            {0,0,0,0,0}, {1,-1,0,0,0}, {1,0,-1,0,0}, {1,0,0,-1,0}, {1,0,0,0,-1},
            {-1,1,0,0,0}, {0,0,0,0,0}, {0,1,-1,0,0}, {0,1,0,-1,0}, {0,1,0,0,-1},
            {-1,0,1,0,0}, {0,-1,1,0,0}, {0,0,0,0,0}, {0,0,1,-1,0}, {0,0,1,0,-1},
            {-1,0,0,1,0}, {0,-1,0,1,0}, {0,0,-1,1,0}, {0,0,0,0,0}, {0,0,0,1,-1},
            {-1,0,0,0,1}, {0,-1,0,0,1}, {0,0,-1,0,1}, {0,0,0,-1,1}, {0,0,0,0,0},
        };
        for (int nature = 0; nature < 25; nature++)
            for (int stat = 1; stat <= 5; stat++) {
                int effect = table[nature][stat - 1];
                int expected = effect == 1 ? 110 : effect == -1 ? 90 : 100;
                check(Gen3Pokemon.byNature(nature, 100, stat) == expected,
                    "nature " + nature + " on stat " + stat + " gives " + expected);
            }
    }

    /**
     * Retail Emerald keeps the nature product in sixteen bits, so a raised stat
     * above 595 (or a lowered one above 728) would wrap there. No Emerald stat
     * gets that high — Shuckle's 559 Defense is the most a nature touches — so
     * Yoru keeps the whole product, as pokeemerald's BUGFIX build does, rather
     * than dividing a wrapped remainder into a stat of single digits.
     */
    private static void theNatureMultiplierDoesNotWrap() {
        // Adamant (3) raises Attack and lowers Sp. Atk; Modest (15) raises Sp. Atk
        // and lowers Attack. 600 * 110 / 100 is 660 and 600 * 90 / 100 is 540;
        // a sixteen-bit product would have made the raised one 4.
        check(Gen3Pokemon.byNature(3, 600, 1) == 660,
            "a raised stat is not truncated, got " + Gen3Pokemon.byNature(3, 600, 1));
        check(Gen3Pokemon.byNature(3, 600, 4) == 540,
            "nor a lowered one, got " + Gen3Pokemon.byNature(3, 600, 4));
        check(Gen3Pokemon.byNature(15, 600, 4) == 660 && Gen3Pokemon.byNature(15, 600, 1) == 540,
            "and the same for a nature that raises the other stat");
        check(Gen3Pokemon.byNature(3, 1000, 1) == 1100, "the multiplier stays the game's plain *110/100");
    }

    /** Joining the party is a withdrawal: the box part unchanged, stats calculated, full health. */
    private static void joiningThePartyIsAWithdrawal() {
        var trainer = new Gen3Save.Trainer("TESTER", 0, 12345, 54321, 6);
        var mon = StudyGift.build(UUID.fromString("11111111-2222-3333-4444-555555555555"), 252, 5, trainer, null, 0);
        byte[] box = mon.encode();
        byte[] party = Gen3Pokemon.toParty(box, 0);
        check(party.length == Gen3Pokemon.PARTY_SIZE, "a party record is 100 bytes");
        check(Arrays.equals(party, 0, 80, box, 0, 80), "the box part is carried unchanged");
        var back = Gen3Pokemon.decodeFromParty(party, 0);
        int[] expected = Gen3Pokemon.stats(252, 5, mon.ivs(), mon.evs, mon.nature());
        check(back.level == 5, "the level is the one its experience gives");
        check(back.maxHp == expected[0] && back.currentHp == back.maxHp, "at full health, as a withdrawal leaves it");
        check(back.attack == expected[1] && back.defense == expected[2] && back.speed == expected[3]
            && back.spAttack == expected[4] && back.spDefense == expected[5], "with the stats in the game's order");
        check(back.status == 0 && back.mail == 0xFF, "no status and no mail");
        check(Gen3Pokemon.intact(party, 0), "and the record still checksums");
    }

    public static void main(String[] args) {
        levelFiveByHand();
        thePublishedMaximums();
        naturesMatchTheTable();
        theNatureMultiplierDoesNotWrap();
        joiningThePartyIsAWithdrawal();
        System.out.println("PASS: " + checks + " stat checks (hand-worked, published maximums, natures, withdrawal)");
    }
}
