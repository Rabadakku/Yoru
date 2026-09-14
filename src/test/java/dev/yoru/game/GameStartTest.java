package dev.yoru.game;

import java.util.Arrays;

/**
 * A save the core cannot hold is refused before play begins (#7).
 *
 * The alternative the audit found was a session started with saving switched
 * off: the game plays, the player saves in it, and nothing reaches the vault.
 * GameSession.start checks the size first and closes the core when it refuses,
 * so this holds the check itself to the cases, and to leaving the save alone.
 * Invented bytes only.
 */
public final class GameStartTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private interface Action { void run(); }
    private static String refuses(Action action, String why) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return expected.getMessage(); }
        throw new AssertionError(why);
    }

    public static void main(String[] args) {
        byte[] save = Gen3Fixture.save(1, 3), original = save.clone();

        GameSession.validateSaveSize(save, Gen3Save.SIZE);
        check(true, "a save the size of the core's save memory is accepted");
        GameSession.validateSaveSize(null, Gen3Save.SIZE);
        check(true, "and so is no save at all, for a game never saved");

        String message = refuses(() -> GameSession.validateSaveSize(save, Gen3Save.SIZE / 2),
            "a save larger than the core's save memory is refused");
        check(message.contains("left untouched") && message.contains("compatible core"),
            "the refusal says the save is safe and what to do: " + message);
        refuses(() -> GameSession.validateSaveSize(save, Gen3Save.SIZE * 2L), "a save smaller than the core's is refused");
        refuses(() -> GameSession.validateSaveSize(save, 0), "a core with no save memory is refused with a save");
        refuses(() -> GameSession.validateSaveSize(null, 0), "and without one, since nothing could ever be saved");
        refuses(() -> GameSession.validateSaveSize(null, -1), "a core that cannot report its save memory is refused");

        check(Arrays.equals(save, original), "checking a save never changes it");
        System.out.println("PASS: " + checks + " game start checks (save size refused before play, save untouched)");
    }
}
