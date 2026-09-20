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
        Path key = Path.of(store.path("School") + ".local-key");
        check(Files.isRegularFile(key), "with its unlock key beside it");
        check(Files.readString(key).matches("[0-9a-f]{64}") && Files.size(key) == 64,
            "which is 64 lowercase hexadecimal characters and nothing else");
        if (Files.getFileStore(key).supportsFileAttributeView("posix"))
            check(Files.getPosixFilePermissions(key).equals(PosixFilePermissions.fromString("rw-------")),
                "that only its owner can read");
        check(!Files.exists(Path.of(key + ".pending")), "and nothing is left waiting under another name");
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

        // The password handed in is a copy the caller never sees again, so a
        // refusal before it reaches the vault wipes it as the vault would have.
        char[] handed = PASSWORD.toCharArray();
        try {
            store.open("Missing", handed).close();
            check(false, "opening a vault that is not there should be refused");
        } catch (java.io.IOException expected) {
            check(new String(handed).chars().allMatch(c -> c == 0), "and the password handed in is wiped");
        }
        char[] misnamed = PASSWORD.toCharArray();
        try {
            store.open("a/b", misnamed).close();
            check(false, "opening a name that is not a name should be refused");
        } catch (IllegalArgumentException expected) {
            check(new String(misnamed).chars().allMatch(c -> c == 0), "and so is one handed in with a name that is not one");
        }
    }

    /** Where a removal's new key waits until the vault has been re-encrypted under it. */
    private static Path waiting(VaultStore store, String name) {
        return Path.of(store.path(name) + ".local-key.pending");
    }

    /**
     * A removal killed before the vault was re-encrypted leaves its new key
     * waiting beside a vault that still opens with its password. The vault is
     * not listed as password-free for it, a second removal will not write over
     * it, and the next start clears it away — as it does a key cut short while
     * it was written — so the password can be taken off again.
     */
    private static void aRemovalKilledBeforeItsSaveIsCleared(Path dir) throws Exception {
        var store = new VaultStore(dir);
        State saved = withOneActivity();
        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(saved); }
        Path waiting = waiting(store, "School");
        var written = LocalAccess.writeKey(waiting);
        var keyBytes = Files.readAllBytes(waiting);

        check(!store.passwordless("School"), "a waiting key does not make the vault password-free");
        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            store.removePassword("School", vault, saved);
            check(false, "a removal while an earlier one is unfinished should be refused");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("School"), "a second removal is refused, and says which vault");
        }
        check(java.util.Arrays.equals(Files.readAllBytes(waiting), keyBytes), "and the waiting key is not written over");
        check(!store.passwordless("School"), "nor put in place");

        var recovered = store.reconcile();
        check(recovered.empty(), "a removal that never reached the vault is nothing to report, got " + recovered);
        check(!Files.exists(waiting), "the next start clears away the key the vault was never sealed under");
        check(!store.passwordless("School"), "the vault still wants its password");
        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            check(vault.load().equals(saved), "and opens with it, with everything in it");
            java.util.Arrays.fill(written, '\0');
            store.removePassword("School", vault, vault.load());
        }
        check(store.passwordless("School"), "so the password can be taken off after all");

        // A key cut short while it was written opens nothing, and goes too.
        try (var vault = store.create("Work", PASSWORD.toCharArray())) { vault.save(saved); }
        Files.writeString(waiting(store, "Work"), "0123456789abcdef");
        store.reconcile();
        check(!Files.exists(waiting(store, "Work")), "a waiting key cut short is cleared away");
        try (var vault = store.open("Work", PASSWORD.toCharArray())) {
            check(vault.load().equals(saved), "and the vault it was for still opens with its password");
        }
    }

    /**
     * A removal killed after the vault was re-encrypted, before its key was put
     * in place, leaves a vault that only the waiting key opens. The next start
     * puts the key where it belongs; until then a rename or a delete carries it
     * with the vault, and a vault open elsewhere is left for that window.
     */
    private static void aRemovalKilledAfterItsSaveIsFinished(Path dir) throws Exception {
        var store = new VaultStore(dir);
        State saved = withOneActivity();
        try (var vault = store.create("School", PASSWORD.toCharArray())) {
            vault.save(saved);
            vault.changeSecret(LocalAccess.writeKey(waiting(store, "School")), saved);
        }
        check(!EncryptedVault.opens(store.path("School"), PASSWORD.toCharArray()), "the password no longer opens it");
        check(!store.passwordless("School"), "and it is not yet listed as password-free");

        store.rename("School", "College");
        check(Files.exists(waiting(store, "College")) && !Files.exists(waiting(store, "School")),
            "a rename takes the waiting key with the vault it opens");

        var secret = LocalAccess.readKey(waiting(store, "College"));
        try (var vault = store.open("College", secret)) {
            store.reconcile();
            check(Files.exists(waiting(store, "College")) && !store.passwordless("College"),
                "a vault open in another window is left for that window to finish with");
        }

        var recovered = store.reconcile();
        check(recovered.rekeyed().equals(List.of("College")), "the next start puts the key in place, got " + recovered);
        check(!Files.exists(waiting(store, "College")), "from where it was waiting");
        check(store.passwordless("College"), "so the vault opens without a password");
        try (var vault = store.open("College")) {
            check(vault.load().equals(saved), "with everything that was in it");
        }
        check(store.reconcile().empty(), "and a second start has nothing left to do");

        // And a delete takes a waiting key with everything else the vault owns.
        try (var vault = store.create("Work", PASSWORD.toCharArray())) { vault.save(saved); }
        LocalAccess.writeKey(waiting(store, "Work"));
        store.delete("Work");
        try (var files = Files.list(dir)) {
            check(files.noneMatch(f -> f.getFileName().toString().startsWith("Work")),
                "a deleted vault leaves no waiting key behind");
        }
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
        aRemovalKilledBeforeItsSaveIsCleared(dir.resolve("f"));
        aRemovalKilledAfterItsSaveIsFinished(dir.resolve("g"));
        System.out.println("PASS: " + checks + " password-removal checks (key beside it, old password dead, "
            + "nothing lost, no orphan key on failure, a killed removal finished or cleared)");
    }
}
