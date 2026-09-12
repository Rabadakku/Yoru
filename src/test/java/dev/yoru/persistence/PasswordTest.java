package dev.yoru.persistence;

import dev.yoru.domain.Model.Activity;
import dev.yoru.domain.Model.State;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;

/**
 * Taking the password off a vault that has one (#41).
 *
 * The trade is the whole of this feature: everything in the vault is kept and
 * stays encrypted, and the secret that opens it moves from somebody's head into
 * a file beside it. What is checked here is that the move is complete — the old
 * password stops working, the key beside it starts working, nothing is lost —
 * and that a failure leaves neither half behind.
 */
public final class PasswordTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final String PASSWORD = "a-long-enough-password";

    private static State withOneActivity() {
        return State.empty().withCore(List.of(new Activity(UUID.randomUUID(), "Reading", 30)),
            List.of(), List.of());
    }

    /** The password comes off, and the vault opens afterwards with no password at all. */
    private static void thePasswordComesOff(Path dir) throws Exception {
        var store = new VaultStore(dir);
        State saved = withOneActivity();
        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(saved); }

        check(!store.passwordless("School"), "the vault starts out wanting a password");

        char[] secret;
        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            secret = store.removePassword("School", vault, vault.load());
        }

        check(store.passwordless("School"), "and opens without one afterwards");
        check(Files.isRegularFile(Path.of(store.path("School") + ".local-key")), "with its unlock key beside it");
        check(secret.length == 64, "and a session secret to keep");

        // Everything that was in it is still in it.
        try (var vault = store.open("School")) {
            check(vault.load().equals(saved), "with everything that was in the vault still in it");
        }
        // And the key beside it is the key that opens it.
        try (var vault = store.open("School", store.secretOf("School"))) {
            check(vault.load().equals(saved), "the key beside the vault is the one that opens it");
        }
    }

    /** The password that used to open it does not open it any more. */
    private static void theOldPasswordStops(Path dir) throws Exception {
        var store = new VaultStore(dir);
        try (var vault = store.create("Work", PASSWORD.toCharArray())) { vault.save(State.empty()); }
        try (var vault = store.open("Work", PASSWORD.toCharArray())) {
            store.removePassword("Work", vault, vault.load());
        }
        check(!EncryptedVault.opens(store.path("Work"), PASSWORD.toCharArray()),
            "the password that used to open the vault does not open it any more");
        try {
            store.open("Work", PASSWORD.toCharArray()).close();
            check(false, "opening with the old password should have failed");
        } catch (Exception expected) {
            check(true, "opening with the old password is refused");
        }
    }

    /** A vault that never had a password is left alone, and says so. */
    private static void nothingToRemove(Path dir) throws Exception {
        var store = new VaultStore(dir);
        store.createPasswordless("Free").close();
        try (var vault = store.open("Free")) {
            store.removePassword("Free", vault, vault.load());
            check(false, "a vault with no password should refuse to have one removed");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("already opens without a password"),
                "and says that it already opens without one");
        }
        check(store.passwordless("Free"), "and is left exactly as it was");
    }

    /** A name nothing is filed under is refused rather than half acted on. */
    private static void noSuchVault(Path dir) throws Exception {
        var store = new VaultStore(dir);
        try (var vault = store.create("Only", PASSWORD.toCharArray())) {
            vault.save(State.empty());
            try {
                store.removePassword("Missing", vault, State.empty());
                check(false, "a vault that is not there should be refused");
            } catch (java.io.IOException expected) {
                check(true, "a vault that is not there is refused");
            }
        }
        check(!Files.exists(Path.of(store.path("Missing") + ".local-key")),
            "and no unlock key is left behind for a vault that does not exist");
    }

    /**
     * A re-encryption that fails puts everything back.
     *
     * A key beside a vault it cannot open is the one shape the store refuses to
     * let anything else land on: it would be listed as password-free and never
     * open. The failure is a folder nothing can be written into, which is where
     * a full disk or a read-only volume puts it.
     */
    private static void aFailureLeavesNoOrphanKey(Path dir) throws Exception {
        var store = new VaultStore(dir);
        State saved = withOneActivity();
        try (var vault = store.create("Fragile", PASSWORD.toCharArray())) { vault.save(saved); }

        var locked = PosixFilePermissions.fromString("r-x------");
        var normal = Files.getPosixFilePermissions(store.root());

        // changeSecret itself: the new key is derived, the save cannot land, and
        // the vault is left opening with exactly the password it had.
        try (var vault = store.open("Fragile", PASSWORD.toCharArray())) {
            Files.setPosixFilePermissions(store.root(), locked);
            try {
                vault.changeSecret("0123456789abcdef".toCharArray(), saved);
                check(false, "a re-encryption that cannot be written should fail");
            } catch (java.io.IOException expected) {
                check(true, "a re-encryption that cannot be written fails");
            } finally {
                Files.setPosixFilePermissions(store.root(), normal);
            }
        }
        check(EncryptedVault.opens(store.path("Fragile"), PASSWORD.toCharArray()),
            "and the password the vault had still opens it");

        // And the whole removal: no key is left beside a vault it cannot open.
        try (var vault = store.open("Fragile", PASSWORD.toCharArray())) {
            Files.setPosixFilePermissions(store.root(), locked);
            try {
                store.removePassword("Fragile", vault, saved);
                check(false, "a removal that cannot be written should fail");
            } catch (java.io.IOException expected) {
                check(true, "a removal that cannot be written fails");
            } finally {
                Files.setPosixFilePermissions(store.root(), normal);
            }
        }
        check(!Files.exists(Path.of(store.path("Fragile") + ".local-key")),
            "and no unlock key is left beside a vault it cannot open");
        check(!store.passwordless("Fragile"), "so the vault still wants its password");
        try (var reopened = store.open("Fragile", PASSWORD.toCharArray())) {
            check(reopened.load().equals(saved), "and still opens with it, with everything still in it");
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("yoru-password-test");
        thePasswordComesOff(dir.resolve("a"));
        theOldPasswordStops(dir.resolve("b"));
        nothingToRemove(dir.resolve("c"));
        noSuchVault(dir.resolve("d"));
        aFailureLeavesNoOrphanKey(dir.resolve("e"));
        System.out.println("PASS: " + checks + " password-removal checks (key beside it, old password dead, "
            + "nothing lost, no orphan key on failure)");
    }
}
