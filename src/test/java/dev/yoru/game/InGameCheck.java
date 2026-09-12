package dev.yoru.game;

import dev.yoru.domain.Model.Reward;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Proves, inside the real game, that saves Yoru writes are accepted (#29).
 *
 * Not part of ./test.sh: it needs the user's own ROM and core, which never
 * enter the repository. Run it by hand:
 *
 *   java --enable-native-access=ALL-UNNAMED -cp build/classes dev.yoru.game.InGameCheck \
 *        path/to/emerald.gba path/to/a-COPY-of-a-save.srm path/to/work-dir
 *
 * It never touches the save it is given; everything happens on a copy.
 *
 * <h2>What it checks, and why each is evidence</h2>
 * Three Pokémon are written into the copy through {@link Gen3Save}, as the
 * game's next save: one in the first free PC slot, one in a slot that
 * straddles two sectors, and one in the party with the stats the game would
 * calculate. The game copies a sector into RAM only when its signature and
 * checksum pass, so finding the straddling record contiguous in the machine
 * state proves the loader accepted the sectors — and that it chose the slot
 * Yoru wrote. After continuing into the overworld, the game has read the
 * party and marked any member whose own checksum fails as a bad egg; finding
 * none proves the Pokémon's checksum too.
 *
 * Screenshots at each stage are written to the work directory.
 */
public final class InGameCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: InGameCheck <rom> <save-copy> <work-dir>");
            System.exit(2);
        }
        Path rom = Path.of(args[0]), source = Path.of(args[1]), work = Path.of(args[2]);
        Files.createDirectories(work);
        var save = Gen3Save.read(Files.readAllBytes(source));
        var trainer = save.trainer();
        System.out.println("save: " + trainer.name() + ", counter " + save.counter() + " in slot " + (save.loadedSlot() + 1)
            + ", party " + save.partyCount() + ", badges " + save.badges());

        var storage = save.storage();
        int[] free = save.firstFreeSlot(storage);
        if (free == null) throw new IllegalStateException("the PC is full");
        byte[] pcRecord = companion("5eed0001-0000-4000-8000-000000000001", 252, trainer);
        System.arraycopy(pcRecord, 0, storage, Gen3Save.slotOffset(free[0], free[1]), Gen3Pokemon.BOX_SIZE);

        // Box 2 slot 20 starts at storage byte 3924, and sector 5 carries only
        // 3968 bytes of the PC — so on flash these 80 bytes are split by a
        // footer. They are contiguous in RAM only if the game stitched them.
        int straddleAt = Gen3Save.slotOffset(1, 19);
        if (straddleAt >= Gen3Save.CHECKSUMMED[5] || straddleAt + Gen3Pokemon.BOX_SIZE <= Gen3Save.CHECKSUMMED[5])
            throw new IllegalStateException("slot choice does not straddle the sector boundary");
        byte[] straddleRecord = companion("5eed0003-0000-4000-8000-000000000003", 258, trainer);
        System.arraycopy(straddleRecord, 0, storage, straddleAt, Gen3Pokemon.BOX_SIZE);
        save.storage(storage);

        var party = new ArrayList<>(save.partyRecords());
        byte[] partyRecord = Gen3Pokemon.toParty(companion("5eed0002-0000-4000-8000-000000000002", 255, trainer), 0);
        boolean partyCheck = party.size() < Gen3Save.PARTY_LIMIT;
        if (partyCheck) party.add(partyRecord);
        save.party(party);

        byte[] written = save.bytes();
        if (find(written, straddleRecord, 0) >= 0)
            throw new IllegalStateException("the straddling record is contiguous on flash, so it would prove nothing");
        Path copy = work.resolve("check.srm");
        Files.write(copy, written);
        System.out.println("wrote " + copy + ": the next save, counter " + Gen3Save.read(written).counter());

        var options = RetroArchSetup.options("mGBA");
        Path system = RetroArchSetup.hasGbaBios() ? RetroArchSetup.systemDirectory() : work;
        try (var core = LibretroCore.open(LibretroCore.defaultCorePath(), options, system)) {
            core.loadGame(rom);
            core.writeMemory(LibretroCore.MEMORY_SAVE_RAM, written);

            run(core, 600);
            shot(core, work.resolve("1-boot.png"));
            byte[] state = core.saveState();
            report(find(state, straddleRecord, 0) >= 0,
                "the sector-straddling Pokémon is contiguous in the machine state — the loader accepted the slot Yoru wrote",
                "the sector-straddling Pokémon is not in the machine state after boot");
            report(find(state, pcRecord, 0) >= 0, "the PC Pokémon is in the machine state",
                "the PC Pokémon is not in the machine state after boot");

            // START skips the intro, START reaches PRESS START, A opens the menu
            // with CONTINUE selected, and A continues into the overworld.
            press(core, LibretroCore.START, 6); run(core, 240); shot(core, work.resolve("2-intro.png"));
            press(core, LibretroCore.START, 6); run(core, 180); shot(core, work.resolve("3-title.png"));
            press(core, LibretroCore.A, 6);     run(core, 480); shot(core, work.resolve("4-menu.png"));
            press(core, LibretroCore.A, 6);     run(core, 900); shot(core, work.resolve("5-overworld.png"));

            if (!partyCheck) { System.out.println("party full: party check skipped"); return; }
            byte[] after = core.saveState();
            byte[] prefix = java.util.Arrays.copyOf(partyRecord, 8);
            int seen = 0;
            boolean bad = false;
            for (int from = 0; (from = find(after, prefix, from)) >= 0; from++) {
                seen++;
                if ((after[from + 0x13] & 0x01) != 0) bad = true;
            }
            report(seen > 0 && !bad, "after continuing, no copy of the party Pokémon is a bad egg — its checksum was accepted",
                seen == 0 ? "the party Pokémon is not in the machine state" : "the game marked the party Pokémon a BAD EGG");
        }
    }

    private static byte[] companion(String id, int national, Gen3Save.Trainer trainer) {
        var reward = new Reward(UUID.fromString(id), national, 5, Instant.parse("2026-09-11T00:00:00Z"), null);
        return GameDelivery.companionFor(reward, trainer).encode();
    }

    private static void report(boolean ok, String pass, String fail) {
        System.out.println(ok ? "PASS: " + pass : "FAIL: " + fail);
    }

    private static void run(LibretroCore core, int frames) { for (int i = 0; i < frames; i++) core.runFrame(); }

    private static void press(LibretroCore core, int button, int frames) {
        core.press(button, true);
        run(core, frames);
        core.press(button, false);
    }

    private static void shot(LibretroCore core, Path file) throws Exception {
        BufferedImage image = core.image();
        ImageIO.write(image, "png", file.toFile());
    }

    /** Index of needle in haystack at or after from, or -1. */
    static int find(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
