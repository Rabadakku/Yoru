package dev.yoru.game;

import java.util.Arrays;

/**
 * Why a save cannot be read, as a reason a page can explain (#5).
 *
 * Every refusal stays an IllegalArgumentException, because delivery, storage
 * edits and import already catch that; the reason is what lets Collection say
 * "the game has never saved to it" instead of pretending there is no save.
 */
public final class Gen3SaveReasonsTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static Gen3Save.Unreadable reasonFor(byte[] raw) {
        try {
            Gen3Save.read(raw);
        } catch (Gen3Save.UnreadableSave e) {
            check(e instanceof IllegalArgumentException, "a refusal is still an IllegalArgumentException");
            return e.reason();
        }
        return null;
    }

    public static void main(String[] args) {
        check(reasonFor(new byte[1000]) == Gen3Save.Unreadable.WRONG_SIZE, "a 1000-byte file is the wrong size");

        var blank = new byte[Gen3Save.SIZE];
        Arrays.fill(blank, (byte) 0xFF);
        check(reasonFor(blank) == Gen3Save.Unreadable.NEVER_SAVED, "an erased cartridge has never been saved to");

        // Counter 1 lives in slot 1; flipping a checksummed byte leaves no complete slot.
        var damaged = Gen3Fixture.save(1, 0);
        damaged[Gen3Save.SLOT] ^= 0x01;
        check(reasonFor(damaged) == Gen3Save.Unreadable.DAMAGED, "a slot with a broken sector is damaged");

        // A sound slot 0 whose counter (3) sends the game to slot 1, which is empty.
        var misrouted = Gen3Fixture.saveInSlot(0, 3, 0);
        check(reasonFor(misrouted) == Gen3Save.Unreadable.INCOMPLETE_SLOT,
            "a counter pointing at an incomplete slot is refused as such");

        check(reasonFor(Gen3Fixture.save(2, 0)) == null, "a complete save reads");

        // Occupied PC slots are counted without decoding a page's worth of records.
        var raw = Gen3Fixture.save(2, 0);
        var save = Gen3Save.read(raw);
        check(save.boxedCount() == 0, "a new save's PC is empty");
        var storage = save.storage();
        var member = Gen3Fixture.member(raw, 25, 5, 1);
        System.arraycopy(member, 0, storage, Gen3Save.slotOffset(2, 5), Gen3Pokemon.BOX_SIZE);
        System.arraycopy(member, 0, storage, Gen3Save.slotOffset(13, 29), Gen3Pokemon.BOX_SIZE);
        save.storage(storage);
        check(Gen3Save.read(save.bytes()).boxedCount() == 2, "two boxed Pokémon count as two, wherever they are");

        System.out.println("PASS: " + checks + " unreadable-save reason checks (size, never saved, damaged, misrouted, PC count)");
    }
}
