package dev.yoru.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;

/**
 * The Gen 3 Pokémon record (#29).
 *
 * A round trip on its own is a weak test here: encode and decode would agree
 * with each other even if both misunderstood the format in the same way, and
 * the game would then reject every companion Yoru wrote. So the layout is also
 * pinned against records built by hand.
 *
 * The trick that makes that possible is choosing the keys. With personality and
 * trainer id both zero the encryption key is zero, so the 48 data bytes are
 * plaintext; and personality 0 selects the first substructure order, which is
 * Growth, Attacks, EVs, Misc in their natural positions. Those two facts turn
 * an encrypted, shuffled structure into something that can be written out with
 * a hex editor and asserted field by field.
 */
public final class Gen3FormatTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static Gen3Pokemon sample(int personality,int otId) {
        var p = new Gen3Pokemon();
        p.personality = personality;
        p.otId = otId;
        p.nickname = "Sparky";
        p.otName = "TESTER";
        p.language = 2;
        p.markings = 3;
        p.species = 25;                 // Pikachu
        p.heldItem = 13;
        p.experience = 125000;
        p.ppBonuses = 0b01010101;
        p.friendship = 200;
        p.moves[0]=84; p.moves[1]=45; p.moves[2]=86; p.moves[3]=98;
        p.pp[0]=30; p.pp[1]=40; p.pp[2]=20; p.pp[3]=30;
        for (int i=0;i<6;i++) { p.evs[i]=i*17; p.contest[i]=i*3; }
        p.pokerus = 0x14;
        p.metLocation = 88;
        p.origins = 0x3D41;
        p.ivsEggAbility = 0;
        p.setIvs(new int[]{31,30,29,28,27,26});
        p.ribbons = 0x00010203;
        return p;
    }

    /** Everything written comes back, for every one of the 24 orders. */
    private static void roundTripsInEveryOrder() {
        for (int order = 0; order < 24; order++) {
            int personality = 0x51A20000 + order;   // varies the order and the key together
            var original = sample(personality, 0x1234ABCD);
            var bytes = original.encode();
            check(bytes.length==Gen3Pokemon.BOX_SIZE,"a box entry is 80 bytes");
            var back = Gen3Pokemon.decode(bytes, 0);

            boolean same = back.personality==original.personality && back.otId==original.otId
                && back.nickname.equals(original.nickname) && back.otName.equals(original.otName)
                && back.species==original.species && back.heldItem==original.heldItem
                && back.experience==original.experience && back.friendship==original.friendship
                && back.ppBonuses==original.ppBonuses && back.pokerus==original.pokerus
                && back.metLocation==original.metLocation && back.origins==original.origins
                && back.ivsEggAbility==original.ivsEggAbility && back.ribbons==original.ribbons
                && back.markings==original.markings && back.language==original.language
                && java.util.Arrays.equals(back.moves,original.moves)
                && java.util.Arrays.equals(back.pp,original.pp)
                && java.util.Arrays.equals(back.evs,original.evs)
                && java.util.Arrays.equals(back.contest,original.contest);
            check(same,"order "+Math.floorMod(personality,24)+" round trips every field");
            check(Gen3Pokemon.intact(bytes,0),"and the checksum it wrote agrees with the data");
        }
    }

    /**
     * The layout, pinned without going through encode.
     *
     * Zero keys mean no encryption and the natural substructure order, so these
     * bytes are readable by hand — which is what makes this an independent
     * check rather than an agreement between two halves of the same idea.
     */
    private static void layoutMatchesTheFormat() {
        var bytes = new byte[Gen3Pokemon.BOX_SIZE];
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x00, 0);              // personality: key 0, order 0 (Growth, Attacks, EVs, Misc)
        b.putInt(0x04, 0);              // trainer id
        // Growth occupies 0x20..0x2B
        b.putShort(0x20, (short) 260);  // species
        b.putShort(0x22, (short) 7);    // held item
        b.putInt(0x24, 99999);          // experience
        b.put(0x28, (byte) 0x0F);       // pp bonuses
        b.put(0x29, (byte) 70);         // friendship
        // Attacks occupies 0x2C..0x37
        b.putShort(0x2C, (short) 33);
        b.put(0x34, (byte) 35);         // pp of the first move
        // EVs occupies 0x38..0x43
        b.put(0x38, (byte) 252);        // hp ev
        // Misc occupies 0x44..0x4F
        b.put(0x44, (byte) 0xFF);       // pokerus
        b.putInt(0x48, 0x3FFFFFFF);     // ivs: all 31, not an egg

        var p = Gen3Pokemon.decode(bytes, 0);
        check(p.species==260,"species is the first halfword of Growth, got "+p.species);
        check(p.heldItem==7,"held item follows it");
        check(p.experience==99999,"experience is the word after that, got "+p.experience);
        check(p.ppBonuses==0x0F&&p.friendship==70,"pp bonuses and friendship close Growth");
        check(p.moves[0]==33,"the first move opens Attacks");
        check(p.pp[0]==35,"and its pp sits eight bytes into it");
        check(p.evs[0]==252,"the hp ev opens EVs");
        check(p.pokerus==0xFF,"pokerus opens Misc");
        check(java.util.Arrays.equals(p.ivs(),new int[]{31,31,31,31,31,31}),
            "all-ones packs to six 31s, got "+java.util.Arrays.toString(p.ivs()));
        check(!p.isEgg(),"bit 30 clear is not an egg");
    }

    /** The shuffle is real, and every order is a permutation. */
    private static void ordersArePermutations() {
        for (int order = 0; order < Gen3Pokemon.ORDERS.length; order++) {
            var seen = new HashSet<Integer>();
            for (int slot : Gen3Pokemon.ORDERS[order]) seen.add(slot);
            check(seen.size()==4&&seen.contains(0)&&seen.contains(1)&&seen.contains(2)&&seen.contains(3),
                "order "+order+" places each substructure exactly once");
        }
        check(Gen3Pokemon.ORDERS.length==24,"there are 24 orders");

        // Distinct rows, or two personalities would decode the same bytes
        // differently while claiming the same layout.
        var rows = new HashSet<String>();
        for (var order : Gen3Pokemon.ORDERS) rows.add(java.util.Arrays.toString(order));
        check(rows.size()==24,"and all 24 are distinct, got "+rows.size());

        // Growth does not sit at offset 0 for every personality — if it did,
        // the shuffle would be decoration.
        var offsets = new HashSet<Integer>();
        for (int p = 0; p < 24; p++) offsets.add(Gen3Pokemon.offsetOf(p, 0));
        check(offsets.size()==4,"Growth appears in all four slots across the orders");
    }

    /** Encryption is its own inverse, and it actually changes the bytes. */
    private static void encryptionIsSymmetric() {
        var data = new byte[Gen3Pokemon.DATA_SIZE];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7);
        var original = data.clone();
        Gen3Pokemon.encrypt(data, 0x11112222, 0x33334444);
        check(!java.util.Arrays.equals(data,original),"a non-zero key changes the data");
        Gen3Pokemon.encrypt(data, 0x11112222, 0x33334444);
        check(java.util.Arrays.equals(data,original),"and applying it again restores it");
    }

    /** A tampered record fails its checksum — that is what the game checks. */
    private static void tamperingIsDetected() {
        var bytes = sample(0x51A20007, 0x1234ABCD).encode();
        check(Gen3Pokemon.intact(bytes,0),"a freshly encoded record is intact");
        bytes[0x24] ^= 0x40;                      // flip a bit inside the data
        check(!Gen3Pokemon.intact(bytes,0),"flipping a data bit breaks the checksum");
    }

    /** Party entries carry stats on top of the box record. */
    private static void partyEntriesCarryStats() {
        var p = sample(0x51A2000B, 0x1234ABCD);
        p.level=54; p.currentHp=132; p.maxHp=160; p.attack=110; p.defense=95;
        p.speed=180; p.spAttack=120; p.spDefense=100; p.status=0; p.mail=0xFF;
        var bytes = p.encodeForParty();
        check(bytes.length==Gen3Pokemon.PARTY_SIZE,"a party entry is 100 bytes");
        var back = Gen3Pokemon.decodeFromParty(bytes,0);
        check(back.level==54&&back.currentHp==132&&back.maxHp==160&&back.attack==110
            &&back.defense==95&&back.speed==180&&back.spAttack==120&&back.spDefense==100,
            "every stat survives the trip");
        check(back.species==p.species,"and the box half is untouched");
    }

    /** Derived values that are not stored anywhere. */
    private static void derivedValues() {
        var p = new Gen3Pokemon();
        p.otId = 0; p.personality = 0;
        check(p.shiny(),"zero everything is shiny — the xor is zero, which is under eight");
        p.personality = 0x00000100;
        check(!p.shiny(),"and a personality far from the id is not");
        p.personality = 25 * 40 + 7;
        check(p.nature()==7,"nature is the personality modulo 25, got "+p.nature());

        p.ivsEggAbility = 0;
        p.setIvs(new int[]{1,2,3,4,5,6});
        check(java.util.Arrays.equals(p.ivs(),new int[]{1,2,3,4,5,6}),
            "ivs pack and unpack, got "+java.util.Arrays.toString(p.ivs()));
        p.ivsEggAbility |= (1 << 30);
        check(p.isEgg(),"bit 30 marks an egg");
        check(java.util.Arrays.equals(p.ivs(),new int[]{1,2,3,4,5,6}),"without disturbing the ivs");
    }

    /** The game's character set, including the ways a name can go wrong. */
    private static void namesUseTheGamesAlphabet() {
        var buffer = new byte[10];
        Gen3Text.write("PIKACHU", buffer, 0, 10);
        check(Gen3Text.read(buffer,0,10).equals("PIKACHU"),"a plain name round trips");
        check((buffer[7]&0xFF)==Gen3Text.TERMINATOR,"and is terminated rather than padded");

        // A shorter name over a longer one must not leave the old tail behind.
        Gen3Text.write("ABC", buffer, 0, 10);
        check(Gen3Text.read(buffer,0,10).equals("ABC"),"a shorter name replaces the whole field, got "
            +Gen3Text.read(buffer,0,10));

        Gen3Text.write("Mr. Mime 2", buffer, 0, 10);
        check(Gen3Text.read(buffer,0,10).equals("Mr. Mime 2"),"mixed case, punctuation and digits survive");

        // Letters are not ASCII here: A is 0xBB, a is 0xD5, 0 is 0xA1.
        check(Gen3Text.encode('A')==0xBB&&Gen3Text.encode('a')==0xD5&&Gen3Text.encode('0')==0xA1,
            "the alphabet sits where the games put it");
        check(Gen3Text.encode(' ')==0x00,"space is zero, not 0x20");

        Gen3Text.write("Ω", buffer, 0, 10);
        check(Gen3Text.read(buffer,0,10).equals("?"),
            "an unmappable character becomes a question mark rather than a stray byte");

        // Overlong names are cut to the field rather than running past it.
        var narrow = new byte[7];
        Gen3Text.write("ABCDEFGHIJ", narrow, 0, 7);
        check(Gen3Text.read(narrow,0,7).equals("ABCDEFG"),"an overlong name is cut to the field");
    }

    /**
     * Every byte the table knows reads as its own character and writes back as
     * itself. The game's ellipsis and curly quotes (charmap B0-B4) once read as
     * plain '.', '"' and '\'', which other bytes also stand for, so a name that
     * held one was written back as a different name.
     */
    private static void everyKnownByteWritesBackAsItself() {
        check(Gen3Text.encode('…') == 0xB0 && Gen3Text.encode('“') == 0xB1 && Gen3Text.encode('”') == 0xB2
            && Gen3Text.encode('‘') == 0xB3 && Gen3Text.encode('’') == 0xB4,
            "the ellipsis and the four curly quotes sit where the game's charmap puts them");
        check(Gen3Text.encode('\'') == 0xB4, "a typed apostrophe is the game's own, 0xB4, as in FARFETCH'D");
        check(Gen3Text.encode('"') == 0xB1, "and a typed double quote opens a quotation");
        check(Gen3Text.encode('.') == 0xAD, "a full stop is 0xAD, not the ellipsis");

        var one = new byte[2];
        for (int value = 0; value < Gen3Text.TERMINATOR; value++) {
            one[0] = (byte) value;
            one[1] = (byte) Gen3Text.TERMINATOR;
            String text = Gen3Text.read(one, 0, 2);
            if (text.equals("?") && value != 0xAC) continue;      // a byte the table has no character for
            check(Gen3Text.bytes(text, 1)[0] == (byte) value,
                "byte 0x" + Integer.toHexString(value) + " reads as '" + text + "' and writes back as itself");
        }

        // '\0' marks a byte with no character; it is never a character itself,
        // or a pasted NUL would be stored as 0x01.
        check(Gen3Text.encode('\0') < 0, "NUL has no byte, got " + Gen3Text.encode('\0'));
    }

    /** Every species' default name is stored in the game's own letters, with nothing lost as '?'. */
    private static void everySpeciesNameIsStoredWhole() {
        for (int national = 1; national <= SpeciesNames.COUNT; national++) {
            String name = StudyGift.defaultName(national);
            for (int i = 0; i < name.length(); i++)
                check(Gen3Text.encode(name.charAt(i)) >= 0,
                    name + " (" + national + ") has no byte for '" + name.charAt(i) + "'");
        }
        byte[] farfetchd = Gen3Text.bytes(StudyGift.defaultName(83), 10);
        check((farfetchd[8] & 0xFF) == 0xB4, "FARFETCH'D's apostrophe is the game's 0xB4, got 0x"
            + Integer.toHexString(farfetchd[8] & 0xFF));
        check(Gen3Text.read(farfetchd, 0, 10).equals("FARFETCH’D"), "and reads back as the name it is");
    }

    /**
     * Byte 0x13 is the game's flag bitfield, not free space.
     *
     * Bit 0 marks a bad egg, bit 1 says a species is present, bit 2 says it is
     * an egg. The game sets bit 1 whenever it gives a Pokémon a species, so a
     * record with it clear is one the game never wrote. This was wrong before:
     * the byte was treated as an "egg-name flag" and written as zero, and every
     * round trip passed, because reading back a zero that was written as a
     * zero proves nothing. So these bytes are checked directly.
     */
    private static void flagsFollowTheGame() {
        var bytes = sample(0x51A20003, 0x1234ABCD).encode();
        check((bytes[0x13] & 0xFF) == 0x02, "a Pokémon with a species has exactly the has-species bit, got 0x"
            + Integer.toHexString(bytes[0x13] & 0xFF));

        var egg = sample(0x51A20003, 0x1234ABCD);
        egg.ivsEggAbility |= 1 << 30;
        check((egg.encode()[0x13] & 0x04) != 0, "an egg sets the flag's egg bit to match its substructure");

        var nothing = new Gen3Pokemon();
        nothing.personality = 5;
        nothing.otId = 9;
        check((nothing.encode()[0x13] & 0x02) == 0, "no species means no has-species bit");

        // Bad egg and the high bits belong to the game; decoding then encoding
        // must carry them through rather than silently clearing them.
        var marked = sample(0x51A20003, 0x1234ABCD).encode();
        marked[0x13] = (byte) 0x0B;          // bad egg, has species, bit 3
        var back = Gen3Pokemon.decode(marked, 0);
        check(back.badEgg(), "a bad egg is read as one");
        check((back.encode()[0x13] & 0xFF) == 0x0B, "and re-encoding keeps what the game set, got 0x"
            + Integer.toHexString(back.encode()[0x13] & 0xFF));
    }

    public static void main(String[] args) {
        roundTripsInEveryOrder();
        layoutMatchesTheFormat();
        ordersArePermutations();
        encryptionIsSymmetric();
        tamperingIsDetected();
        partyEntriesCarryStats();
        derivedValues();
        namesUseTheGamesAlphabet();
        everyKnownByteWritesBackAsItself();
        everySpeciesNameIsStoredWhole();
        flagsFollowTheGame();
        System.out.println("PASS: "+checks+" Gen 3 format checks (layout, shuffle, encryption, checksum, names)");
    }
}
