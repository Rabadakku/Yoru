package dev.yoru.game;

import java.util.HashSet;
import java.util.UUID;

/**
 * The companion a study session earns (#29).
 *
 * The game does not report a rejected field. It shows a Bad Egg, or quietly
 * holds a different Pokémon than the one intended — so every value written has
 * to be checked here, where a mistake is visible, rather than in the game,
 * where it is not.
 *
 * The two properties that carry the most weight are that the level a companion
 * is delivered at is the level it reads back as, and that a reward always
 * rebuilds the same companion. The second is what makes a retry after a crash
 * safe: an identical rebuild cannot become a second, subtly different Pokémon.
 */
public final class StudyGiftTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final UUID REWARD = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER  = UUID.fromString("11111111-2222-3333-4444-555555555556");

    private static Gen3Save.Trainer trainer() {
        return new Gen3Save.Trainer("TESTER", 0, 12345, 54321, 6);
    }

    /** A reward's marker is stable, non-zero, and its own. */
    private static void personalityIdentifiesTheReward() {
        int first = StudyGift.personalityFor(REWARD);
        check(first == StudyGift.personalityFor(REWARD), "the same reward always gives the same marker");
        check(first != 0, "and never zero, which is what an empty slot reads as");
        check(first != StudyGift.personalityFor(OTHER),
            "two rewards differing by one bit get different markers");

        // Across many rewards the markers must not collide, or delivery could
        // mistake one reward's companion for another's.
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 5000; i++)
            seen.add(StudyGift.personalityFor(new UUID(0x5EEDL * i, i * 31L + 7)));
        check(seen.size() == 5000, "5000 distinct rewards give 5000 distinct markers, got " + seen.size());
    }

    /** Every field the game reads comes out right. */
    private static void theCompanionIsWellFormed() {
        var t = trainer();
        var mon = StudyGift.build(REWARD, 252, 5, t, null, 0);

        check(mon.species == SpeciesIds.internalOf(252), "the internal species id is written, not the dex number");
        check(mon.species == 277, "Treecko is internal 277, got " + mon.species);
        check(mon.otId == t.otId(), "the save's own trainer id is carried, so it obeys");
        check(mon.otName.equals("TESTER"), "and the trainer's name");
        check(mon.nickname.equals("TREECKO"), "with the species name upper-cased, got " + mon.nickname);
        check(mon.language == StudyGift.ENGLISH, "language is set");
        check(mon.friendship == SpeciesIds.friendshipOf(252), "base friendship comes from the species");

        check(!mon.isEgg(), "the egg bit is clear — an egg with experience is a contradiction");
        check((mon.encode()[0x13] & 0xFF) == 0x02,
            "the flag byte says a species is present, as every game-made Pokémon's does");
        check(mon.heldItem == 0 && mon.pokerus == 0 && mon.ribbons == 0,
            "and nothing is carrying values it did not earn");

        check((mon.origins & 0x7F) == 5, "level met is the level it arrives at");
        check(((mon.origins >>> 7) & 0xF) == StudyGift.EMERALD, "the game of origin is Emerald");
        check(((mon.origins >>> 11) & 0xF) == StudyGift.POKE_BALL, "in a Poké Ball");
        check(((mon.origins >>> 15) & 1) == 0, "with the trainer's gender");

        for (int iv : mon.ivs()) check(iv >= 0 && iv <= 31, "every IV is in range, got " + iv);
    }

    /** Moves are the real ones for that species and level, with real PP. */
    private static void movesComeFromTheRealLearnset() {
        var mon = StudyGift.build(REWARD, 252, 5, trainer(), null, 0);
        var expected = Learnsets.movesAt(252, 5);
        check(java.util.Arrays.equals(mon.moves, expected),
            "the four moves match the learnset, got " + java.util.Arrays.toString(mon.moves));
        for (int i = 0; i < 4; i++)
            check(mon.pp[i] == Learnsets.pp(mon.moves[i]),
                "slot " + i + " carries that move's own PP");
        check(mon.moves[0] != 0, "and it can actually attack");

        // A higher level means the moves it would have by then, not the first four.
        var grown = StudyGift.build(REWARD, 1, 15, trainer(), null, 0);
        check(grown.moves[0] == 73, "Bulbasaur at 15 leads with Leech Seed, got " + grown.moves[0]);
    }

    /**
     * The level asked for is the level the game will show.
     *
     * Experience is what a box actually stores, so this is the assertion that
     * a delivered companion is not silently a different level.
     */
    private static void theLevelSurvivesTheRoundTrip() {
        for (int national : new int[]{1, 4, 7, 25, 129, 252, 255, 258, 282, 386})
            for (int level : new int[]{5, 12, 30, 60, 100}) {
                var mon = StudyGift.build(REWARD, national, level, trainer(), null, 0);
                var back = Gen3Pokemon.decode(mon.encode(), 0);
                int read = Experience.levelFor(SpeciesIds.growthOf(national), back.experience);
                check(read == level, "National " + national + " asked for level " + level
                    + " reads back as " + read);
                check(back.species == SpeciesIds.internalOf(national), "and as the right species");
            }
    }

    /** What is written survives encoding, and the checksum agrees. */
    private static void itEncodesToSomethingTheGameAccepts() {
        for (int national : new int[]{1, 25, 252, 282, 386}) {
            var mon = StudyGift.build(REWARD, national, 20, trainer(), null, 0);
            var bytes = mon.encode();
            check(bytes.length == Gen3Pokemon.BOX_SIZE, "an 80-byte record");
            check(Gen3Pokemon.intact(bytes, 0), "whose checksum agrees with its data");
            var back = Gen3Pokemon.decode(bytes, 0);
            check(back.personality == mon.personality && back.otId == mon.otId,
                "with the identity intact");
            check(back.nickname.equals(mon.nickname) && back.otName.equals(mon.otName),
                "and the names readable");
            check(java.util.Arrays.equals(back.ivs(), mon.ivs()), "and the IVs unchanged");
        }
    }

    /** Rebuilding a reward gives byte-identical results. */
    private static void rebuildingIsIdentical() {
        var first = StudyGift.build(REWARD, 252, 5, trainer(), null, 0).encode();
        var again = StudyGift.build(REWARD, 252, 5, trainer(), null, 0).encode();
        check(java.util.Arrays.equals(first, again),
            "the same reward rebuilds byte for byte, so a retry cannot make a second Pokémon");

        var different = StudyGift.build(OTHER, 252, 5, trainer(), null, 0).encode();
        check(!java.util.Arrays.equals(first, different), "and a different reward does not");
    }

    /** A delivered companion can be found again; an undelivered one cannot. */
    private static void deliveryIsDetectableInTheSave() {
        var save = Gen3Save.read(Gen3Fixture.save(2, 4));
        var storage = save.storage();
        check(!StudyGift.alreadyDelivered(save, storage, REWARD), "an empty PC holds nothing yet");

        var mon = StudyGift.build(REWARD, 252, 5, trainer(), null, 0);
        System.arraycopy(mon.encode(), 0, storage, Gen3Save.slotOffset(0, 0), Gen3Pokemon.BOX_SIZE);
        check(StudyGift.alreadyDelivered(save, storage, REWARD), "once written, the reward is found");
        check(!StudyGift.alreadyDelivered(save, storage, OTHER), "and only that reward");

        // Found wherever it is, not just in the first box — the player may have
        // moved it, and a retry that only looked in box 0 would deliver twice.
        var moved = save.storage();
        System.arraycopy(mon.encode(), 0, moved, Gen3Save.slotOffset(11, 27), Gen3Pokemon.BOX_SIZE);
        check(StudyGift.alreadyDelivered(save, moved, REWARD),
            "a companion moved to another box is still recognised");
    }

    /**
     * A gift's trainer name is the player's own bytes.
     *
     * The game counts a Pokémon as traded when its trainer name differs from the
     * player's by a single byte (IsOtherTrainer), and a traded one disobeys past
     * the badge cap. A name from the naming screen's symbol page — … ” ’ — once
     * read back as plain ASCII and was written as other bytes, and a letter
     * outside Yoru's table became '?'. The name is copied instead.
     */
    private static void theTrainerNameIsTheSavesOwnBytes() {
        var base = Gen3Fixture.withTrainer(Gen3Fixture.save(2, 4), "TESTER", 0, 12345, 54321);
        // A, B, the ellipsis, a closing double and single quote, a letter the
        // game has and Yoru's table does not (0x01), then the terminator.
        byte[] symbols = {(byte) 0xBB, (byte) 0xBC, (byte) 0xB0, (byte) 0xB2, (byte) 0xB4, 0x01,
            (byte) 0xFF, (byte) 0xFF};
        var trainer = Gen3Save.read(Gen3Fixture.withTrainerName(base, symbols)).trainer();
        check(trainer.name().equals("AB…”’?"), "the name reads as the game draws it, got " + trainer.name());
        var gift = StudyGift.build(REWARD, 252, 5, trainer, null, 0).encode();
        check(java.util.Arrays.equals(gift, 0x14, 0x1B, symbols, 0, 7),
            "the gift's trainer name is the save's own seven bytes, got "
                + java.util.HexFormat.of().formatHex(gift, 0x14, 0x1B));
        check(Gen3Pokemon.intact(gift, 0), "and the record still checksums");
        var again = Gen3Save.read(Gen3Fixture.withTrainerName(base, symbols)).trainer();
        check(trainer.equals(again) && trainer.hashCode() == again.hashCode(),
            "two reads of one save are the same trainer, stored bytes and all");
        check(!trainer.equals(Gen3Save.read(base).trainer()), "and a trainer with other name bytes is not");

        // Past the terminator the game compares nothing, and a name's field can
        // hold leftovers there. The gift pads with terminators instead, as every
        // earlier build did, so a gift already delivered still matches the one
        // rebuilt today.
        byte[] leftovers = {(byte) 0xBB, (byte) 0xBC, (byte) 0xFF, (byte) 0xC0, (byte) 0xC1, (byte) 0xC2,
            (byte) 0xFF, (byte) 0xFF};
        var padded = StudyGift.build(REWARD, 252, 5,
            Gen3Save.read(Gen3Fixture.withTrainerName(base, leftovers)).trainer(), null, 0).encode();
        check(java.util.Arrays.equals(padded, 0x14, 0x1B,
                new byte[]{(byte) 0xBB, (byte) 0xBC, -1, -1, -1, -1, -1}, 0, 7),
            "bytes after the terminator are padded, got " + java.util.HexFormat.of().formatHex(padded, 0x14, 0x1B));
        check(java.util.Arrays.equals(padded, 0x14, 0x1B, Gen3Text.bytes("AB", 7), 0, 7),
            "which is exactly what writing the name as text gives");
    }

    /** Impossible requests are refused rather than clamped. */
    private static void refusesWhatItCannotBuild() {
        for (int level : new int[]{0, 101, -1}) {
            boolean rejected = false;
            try { StudyGift.build(REWARD, 252, level, trainer(), null, 0); }
            catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "level " + level + " is refused");
        }
        boolean rejected = false;
        try { StudyGift.build(REWARD, 999, 5, trainer(), null, 0); }
        catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "an unknown species is refused");
    }

    public static void main(String[] args) {
        personalityIdentifiesTheReward();
        theCompanionIsWellFormed();
        movesComeFromTheRealLearnset();
        theLevelSurvivesTheRoundTrip();
        itEncodesToSomethingTheGameAccepts();
        rebuildingIsIdentical();
        deliveryIsDetectableInTheSave();
        theTrainerNameIsTheSavesOwnBytes();
        refusesWhatItCannotBuild();
        System.out.println("PASS: "+checks+" study gift checks (identity, fields, moves, level, detection, trainer name)");
    }
}
