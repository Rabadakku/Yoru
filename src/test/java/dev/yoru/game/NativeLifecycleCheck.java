package dev.yoru.game;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The game's lifecycle against a real libretro core (#7, #10).
 *
 * Not part of ./test.sh: it needs the player's own core and game, which never
 * enter the repository. Run it by hand after ./test.sh has built the tests:
 *
 *   java --enable-native-access=ALL-UNNAMED -cp build/classes dev.yoru.game.NativeLifecycleCheck \
 *        path/to/mgba_libretro path/to/game.gba path/to/an-empty-work-dir
 *
 * The game file is only read, and is checked unchanged at the end. No save of
 * the player's is used: the one it starts from is invented by Gen3Fixture, and
 * the work directory receives whatever the core writes. The game's sound plays
 * for a few seconds while it runs.
 *
 * The synthetic tests replace the core with a fake, so they cannot show that
 * the core's ABI is what LibretroCore assumes (API version, frame size, rate,
 * save memory size and an exact save memory round trip); that start refuses a
 * save of the wrong size before the first frame, leaves it untouched and closes
 * the core; that a running game which has not saved sends nothing to the vault
 * across the loop's periodic save check; that Close stops the emulation thread
 * and disposes the core; or that the native library opens again afterwards, as
 * switching vaults requires.
 *
 * It does not play to an in-game save. InGameCheck drives the menus for that,
 * and the acknowledgement and retry paths are covered synthetically.
 */
public final class NativeLifecycleCheck {
    private static int passed, failed;

    private static void report(boolean ok, String what) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS: " : "FAIL: ") + what);
    }

    /** Records what would reach the vault, and acknowledges it as a successful write would. */
    private static final class Sink implements GameSession.SaveSink {
        final List<byte[]> offers = Collections.synchronizedList(new ArrayList<>());
        @Override public void saved(byte[] bytes, Runnable acknowledged) { offers.add(bytes.clone()); acknowledged.run(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: NativeLifecycleCheck <core> <game> <work-dir>");
            System.exit(2);
        }
        Path corePath = Path.of(args[0]), rom = Path.of(args[1]), work = Path.of(args[2]);
        Files.createDirectories(work);
        byte[] gameBefore = sha256(rom);

        abi(corePath, rom, work);
        refusesAMismatchedSave(corePath, rom, work);
        runsAndCloses(corePath, rom, work);

        report(Arrays.equals(gameBefore, sha256(rom)), "the game file is unchanged");
        System.out.println((failed == 0 ? "PASS: " : "FAIL: ") + passed + " native lifecycle checks passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void abi(Path corePath, Path rom, Path work) throws Exception {
        try (var core = LibretroCore.open(corePath, RetroArchSetup.options("mGBA"), work)) {
            report(core.apiVersion() == 1, "the core speaks libretro API 1, got " + core.apiVersion());
            core.loadGame(rom);
            report(core.gameLoaded(), "the game loads");
            long size = core.memorySize(LibretroCore.MEMORY_SAVE_RAM);
            report(size == Gen3Save.SIZE, "save memory is the game's " + Gen3Save.SIZE + " bytes, got " + size);
            for (int i = 0; i < 600; i++) core.runFrame();
            report(core.frameArrived(), "frames arrive");
            report(core.width() == 240 && core.height() == 160, "frames are 240x160, got " + core.width() + "x" + core.height());
            report(Math.abs(core.fps() - 59.73) < 0.05, "the rate is the GBA's 59.73 fps, got " + core.fps());
            report(core.sampleRate() > 0, "sound has a sample rate, got " + core.sampleRate());
            report(drawn(core.image()), "the boot screen is drawn, not blank");
            byte[] invented = Gen3Fixture.save(2, 4);
            core.writeMemory(LibretroCore.MEMORY_SAVE_RAM, invented);
            report(Arrays.equals(core.memory(LibretroCore.MEMORY_SAVE_RAM), invented),
                "save memory reads back exactly what was written");
        }
    }

    private static void refusesAMismatchedSave(Path corePath, Path rom, Path work) throws Exception {
        var sink = new Sink();
        byte[] wrong = new byte[Gen3Save.SIZE / 2];
        Arrays.fill(wrong, (byte) 0x5A);
        byte[] kept = wrong.clone();
        try {
            GameSession.start(corePath, rom, wrong, sink, work).shutDown(5000);
            report(false, "a save the core cannot hold is refused");
        } catch (IllegalArgumentException refused) {
            report(true, "a save the core cannot hold is refused before play");
        }
        report(Arrays.equals(wrong, kept), "and that save is untouched");
        report(sink.offers.isEmpty(), "and nothing reached the vault");
    }

    private static void runsAndCloses(Path corePath, Path rom, Path work) throws Exception {
        var sink = new Sink();
        byte[] save = Gen3Fixture.save(2, 4);
        byte[] kept = save.clone();
        var session = GameSession.start(corePath, rom, save, sink, work);
        report(true, "after that refusal the core opens again, so the refusal closed it");
        long began = session.frameCount();
        // Longer than the loop's periodic save check, every 300 frames.
        Thread.sleep(6500);
        long ran = session.frameCount() - began;
        report(ran > 300, "the emulation thread runs past a periodic save check: " + ran + " frames in 6.5 s");
        report(session.frame() != null, "a frame is published for the page");
        String trouble = String.valueOf(session.trouble());
        report(!trouble.contains("stopped"), "the core did not stop on its own: " + trouble);
        report(sink.offers.isEmpty(), "a game that has not saved sends nothing to the vault");
        report(session.shutDown(5000), "Close stops the thread and disposes the core");
        report(session.closed(), "and the session says it is closed");
        long stopped = session.frameCount();
        Thread.sleep(500);
        report(session.frameCount() == stopped, "no frame runs after Close");
        report(sink.offers.isEmpty(), "closing a game that never saved sends nothing");
        report(Arrays.equals(save, kept), "the save handed to the game is not changed in place");

        var again = GameSession.start(corePath, rom, null, sink, work);
        Thread.sleep(1000);
        report(again.frameCount() > 0, "a second session runs after the first closed, as a vault switch needs");
        report(again.shutDown(5000) && again.closed(), "and closes too");
        report(sink.offers.isEmpty(), "a blank cartridge is not sent to the vault as a save");
    }

    private static boolean drawn(BufferedImage image) {
        int first = image.getRGB(0, 0);
        for (int y = 0; y < image.getHeight(); y += 4)
            for (int x = 0; x < image.getWidth(); x += 4)
                if (image.getRGB(x, y) != first) return true;
        return false;
    }

    private static byte[] sha256(Path file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
