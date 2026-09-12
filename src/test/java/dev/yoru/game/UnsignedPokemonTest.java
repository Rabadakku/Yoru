package dev.yoru.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Independent wire-format oracle: positions transcribed from Emerald GetSubstruct. */
public final class UnsignedPokemonTest {
    private static final int[][] POSITIONS = {
        {0,1,2,3},{0,1,3,2},{0,2,1,3},{0,3,1,2},{0,2,3,1},{0,3,2,1},
        {1,0,2,3},{1,0,3,2},{2,0,1,3},{3,0,1,2},{2,0,3,1},{3,0,2,1},
        {1,2,0,3},{1,3,0,2},{2,1,0,3},{3,1,0,2},{2,3,0,1},{3,2,0,1},
        {1,2,3,0},{1,3,2,0},{2,1,3,0},{3,1,2,0},{2,3,1,0},{3,2,1,0}
    };

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /** No production encoder, permutation lookup, encryption or checksum helper. */
    private static byte[] wire(long personality) {
        byte[] bytes = new byte[100];
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int trainer = 0x12345678;
        b.putInt(0, (int) personality); b.putInt(4, trainer);
        Arrays.fill(bytes, 8, 18, (byte) 0xff);
        Arrays.fill(bytes, 20, 27, (byte) 0xff);
        b.put(18, (byte) 2); b.put(19, (byte) 2);
        int[] positions = POSITIONS[(int) (personality % 24)];
        int g = 32 + positions[0] * 12, a = 32 + positions[1] * 12;
        int e = 32 + positions[2] * 12, m = 32 + positions[3] * 12;
        b.putShort(g, (short) 277); b.putShort(g+2, (short) 13);
        b.putInt(g+4, 125); b.put(g+8, (byte) 3); b.put(g+9, (byte) 70);
        b.putShort(a, (short) 33); b.put(a+8, (byte) 35);
        b.put(e, (byte) 12); b.put(e+6, (byte) 7);
        b.put(m, (byte) 4); b.put(m+1, (byte) 9);
        b.putShort(m+2, (short) 0x1234); b.putInt(m+4, 0x01234567);
        b.putInt(m+8, 0x00012345);
        int sum = 0;
        for (int at=32; at<80; at+=2) sum += b.getShort(at) & 0xffff;
        b.putShort(28, (short) sum);
        for (int at=32; at<80; at+=4) b.putInt(at, b.getInt(at) ^ (int) personality ^ trainer);
        b.put(84, (byte) 5); b.putShort(86, (short) 20); b.putShort(88, (short) 21);
        return bytes;
    }

    private static void verify(long personality) {
        byte[] bytes = wire(personality);
        var mon = Gen3Pokemon.decodeFromParty(bytes, 0);
        check(mon.species==277 && mon.nationalDex()==252, "unsigned personality must select Growth: " + personality);
        check(mon.heldItem==13 && mon.experience==125 && mon.friendship==70, "Growth fields");
        check(mon.moves[0]==33 && mon.pp[0]==35, "Attacks fields at " + personality + ": " + mon.moves[0] + "/" + mon.pp[0]);
        check(mon.evs[0]==12 && mon.contest[0]==7, "EV fields");
        check(mon.pokerus==4 && mon.metLocation==9 && mon.origins==0x1234
            && mon.ivsEggAbility==0x01234567 && mon.ribbons==0x00012345, "Misc fields");
        check(mon.nature()==personality%25, "unsigned nature");
        check(mon.level()==5 && mon.currentHp==20 && mon.maxHp==21, "party fields");
        check(Gen3Pokemon.intact(bytes,0), "independent checksum");
        check(Arrays.equals(bytes, mon.encodeForParty()), "encoder agrees with independent wire record");
        check(Arrays.equals(Arrays.copyOf(bytes,80), Gen3Pokemon.decode(bytes,0).encode()), "boxed record");
    }

    public static void main(String[] args) {
        for (long base : new long[]{0, 0x80000000L})
            for (int i=0; i<24; i++) verify(base+i);
        verify(0x7fffffffL); verify(0xffffffffL);
        System.out.println("PASS: 50 independent unsigned Pokémon wire records, all permutations and sign boundaries");
    }
}
