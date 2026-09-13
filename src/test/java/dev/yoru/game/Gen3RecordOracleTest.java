package dev.yoru.game;

/** The oracle itself, pinned to hand arithmetic, before anything relies on it. */
public final class Gen3RecordOracleTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) {
        // 2^31 = 89,478,485 x 24 + 8; as a signed int it is -2^31, and floorMod(-2^31, 24) = 16.
        check(Gen3RecordOracle.unsignedOrder(0x80000000) == 8, "unsigned 0x80000000 is order 8");
        check(Gen3RecordOracle.signedOrder(0x80000000) == 16, "the old signed encoder chose order 16");
        check(Gen3RecordOracle.unsignedOrder(7) == 7 && Gen3RecordOracle.signedOrder(7) == 7, "below the sign bit they agree");

        var mon = new Gen3Pokemon();
        mon.personality = 0x80000000;
        mon.otId = 0;
        mon.species = 277;
        // Order 8 puts Growth third; order 16 puts it third as well but Attacks fourth.
        // With a zero trainer id the key only touches each word's top byte, so the
        // species' two low bytes sit in the clear at 0x20 + 2 x 12.
        var correct = Gen3RecordOracle.box(mon, 8, 0x02);
        check((correct[0x38] & 0xFF) == 0x15 && (correct[0x39] & 0xFF) == 0x01, "order 8: species at 0x38");
        check((correct[0x13] & 0xFF) == 0x02, "the header flag byte is what was asked for");
        check(Gen3Pokemon.decode(correct, 0).species == 277, "and the fixed decoder reads the species back");

        System.out.println("PASS: " + checks + " record oracle checks (orders either side of the sign bit, layout, flags)");
    }
}
