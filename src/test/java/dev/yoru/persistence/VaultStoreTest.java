package dev.yoru.persistence;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import dev.yoru.game.Gen3Fixture;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Vaults as Yoru manages them (#41): one folder Yoru chose, vaults addressed by
 * name, and nothing above this class ever asking anybody where a file is.
 *
 * Everything here is invented — temporary folders, saves built by
 * {@link Gen3Fixture}, and a password that exists only in this file. No test
 * opens a real vault, because there is no real vault on this machine to open.
 */
public final class VaultStoreTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final String PASSWORD = "a-test-password";
    /** A well-formed unlock key that was made for no vault at all: 64 hex characters. */
    private static final String INVENTED_KEY = "0123456789abcdef".repeat(4);
    private static final Instant WHEN = Instant.parse("2026-09-11T12:00:00Z");

    /** A named vault's worth of invented data: study, a reward and a game save. */
    private static State contents(byte[] gameSave, String label, int sessionMinutes) throws IOException {
        var tracker = new Tracker(new Memory(), Clock.fixed(WHEN, ZoneOffset.UTC));
        tracker.addActivity(label, 30);
        var activity = tracker.state().activities().getFirst().id();
        tracker.log(activity, WHEN.minusSeconds(sessionMinutes * 60L), WHEN);
        tracker.addTasks(List.of(new Task(UUID.randomUUID(), activity, null, "Read " + label, "",
            null, TaskStatus.TODO, "test", WHEN, 0, null)));
        tracker.addHabit("Daily " + label, HabitKind.DAILY, ZoneOffset.UTC, null);
        tracker.bankReward(UUID.randomUUID(), 255, 5);
        if (gameSave != null) tracker.replaceGameSave(gameSave);
        return tracker.state();
    }

    private static final class Memory implements Repository {
        State state = State.empty();
        public State load() { return state; }
        public void save(State next) { state = next; }
        public void close() { }
    }

    /** Fails at the moment the delete step runs, to prove the restore path. */
    private static final class RefusingStore extends VaultStore {
        int refusals;
        RefusingStore(Path root) { super(root); }
        @Override void removeFile(Path path) throws IOException {
            refusals++;
            throw new IOException("the disk refused");
        }
    }

    /**
     * Fails one move and no other, to prove the walk-back, and records the moves
     * it was asked for so a test can prove what order they happened in.
     */
    private static final class InterruptedStore extends VaultStore {
        private final int failAt;
        private int calls;
        final List<String> moved = new ArrayList<>();
        InterruptedStore(Path root) { this(root, 2); }
        InterruptedStore(Path root, int failAt) { super(root); this.failAt = failAt; }
        @Override void move(Path from, Path to) throws IOException {
            moved.add(from.getFileName() + " -> " + to.getFileName());
            if (++calls == failAt) throw new IOException("the disk gave up");
            super.move(from, to);
        }
    }

    @FunctionalInterface
    private interface Attempt { void run() throws Exception; }

    private static void refused(Attempt run, String why) {
        try { run.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("accepted " + why);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static Path fresh(Path root, String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private static List<String> namesOf(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---- creating and reopening ---------------------------------------------

    /** A vault is created under a name, written to, and reopened by that name alone. */
    private static void createsAndReopens(Path dir) throws Exception {
        var store = new VaultStore(dir);
        check(store.names().isEmpty(), "a new store holds no vaults");
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);

        try (var vault = store.create("School", PASSWORD.toCharArray())) {
            vault.save(invented);
            check(vault.name().equals("School"), "the vault knows the name it is filed under");
        }
        check(store.exists("School"), "the vault exists under its name");
        check(store.names().equals(List.of("School")), "and it is the only one listed");
        check(!store.passwordless("School"), "a vault made with a password has no local key");

        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            check(vault.load().equals(invented), "reopening returns everything that was written");
            check(vault.load().game().bytes().length == Gen3Fixture.save(2, 4).length,
                "the game save came back whole");
        }

        // A password-free vault keeps its unlock key beside it and reopens with no password.
        try (var vault = store.createPasswordless("Personal")) { vault.save(contents(null, "Reading", 20)); }
        check(store.passwordless("Personal"), "a password-free vault has its key beside it");
        try (var vault = store.open("Personal")) {
            check(vault.load().activities().getFirst().name().equals("Reading"),
                "a password-free vault opens with no password at all");
        }

        // The wrong password is a refusal, not a new vault.
        refused(() -> store.open("School", "not-the-password".toCharArray()), "the wrong password");
        check(store.names().equals(List.of("Personal", "School")), "and it changed nothing");
    }

    /** A name is a name, not a path: anything that could escape the folder is refused. */
    private static void refusesNamesThatAreNotNames(Path dir) throws Exception {
        var store = new VaultStore(dir);
        check(store.create("A level 2 class", PASSWORD.toCharArray()) != null, "letters, digits, spaces and dashes are fine");
        for (String bad : List.of("", "   ", "..", "a/b", "a\\b", "vault.vault", "x".repeat(61))) {
            refused(() -> store.create(bad, PASSWORD.toCharArray()), "the name \"" + bad + "\"");
        }
        refused(() -> store.open("Nowhere", PASSWORD.toCharArray()), "a vault that does not exist");
        check(store.names().equals(List.of("A level 2 class")), "nothing else was created, got " + store.names());
        check(namesOf(dir).stream().noneMatch(n -> n.startsWith("x")), "and no name that was refused left a file");
    }

    /** Making a second vault under a name in use is refused; the first is untouched. */
    private static void refusesToCreateOverAVault(Path dir) throws Exception {
        var store = new VaultStore(dir);
        var invented = contents(null, "Study", 30);
        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(invented); }
        refused(() -> store.create("School", PASSWORD.toCharArray()), "creating over an existing vault");
        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            check(vault.load().equals(invented), "the vault that was there is exactly as it was");
        }
    }

    // ---- renaming ------------------------------------------------------------

    /** A rename takes the vault, its unlock key and its backups with it, and loses nothing. */
    private static void renamesEverythingThatBelongs(Path dir) throws Exception {
        var store = new VaultStore(dir);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 45);
        try (var vault = store.createPasswordless("School")) {
            vault.save(invented);
            vault.backup();
        }
        check(store.backups("School").length == 1, "the vault has an encrypted backup");

        store.rename("School", "University");
        check(!store.exists("School"), "the old name is gone");
        check(store.exists("University"), "the new name is there");
        check(store.passwordless("University"), "the unlock key moved with it");
        check(store.backups("University").length == 1, "and so did its backup");
        check(namesOf(dir).stream().noneMatch(n -> n.startsWith("School")), "nothing is left under the old name");
        try (var vault = store.open("University")) {
            check(vault.load().equals(invented), "the contents are exactly what they were");
        }

        // Renaming onto a name in use, or renaming nothing, is refused and changes nothing.
        try (var vault = store.create("Other", PASSWORD.toCharArray())) { vault.save(State.empty()); }
        refused(() -> store.rename("University", "Other"), "renaming onto a vault that exists");
        refused(() -> store.rename("Nowhere", "Anywhere"), "renaming a vault that is not there");
        try (var vault = store.open("University")) { check(vault.load().equals(invented), "still intact after refusals"); }
    }

    /**
     * A rename that fails part way leaves the vault under its old name with
     * every part of it — never half moved, never in two places.
     */
    private static void aFailedRenameKeepsTheOldName(Path dir) throws Exception {
        var invented = contents(null, "Study", 30);
        var good = new VaultStore(dir);
        try (var vault = good.createPasswordless("School")) {
            vault.save(invented);
            vault.backup();
        }
        var vaultBytes = Files.readAllBytes(good.path("School"));
        var backups = good.backups("School").length;

        var broken = new InterruptedStore(dir);
        refused(() -> broken.rename("School", "University"), "a rename the disk interrupts");
        check(good.exists("School"), "the vault keeps its old name");
        check(!good.exists("University"), "and is not under the new one either");
        check(Arrays.equals(Files.readAllBytes(good.path("School")), vaultBytes), "its bytes are what they were");
        check(good.passwordless("School"), "its unlock key came back with it");
        check(good.backups("School").length == backups, "and so did its backups");
        try (var vault = good.open("School")) {
            check(vault.load().equals(invented), "with everything in it");
        }
    }

    /**
     * A rename that fails after the unlock key has moved is walked back with the
     * key: it goes first, so a failure at any later step happens with it already
     * at the new name, and a vault left without it would be a dead one.
     */
    private static void aFailedRenamePutsTheKeyBackToo(Path dir) throws Exception {
        var invented = contents(null, "Study", 30);
        var good = new VaultStore(dir);
        try (var vault = good.createPasswordless("School")) {
            vault.save(invented);
            vault.backup();
        }
        // The key, then the vault: failing the second move fails with the key
        // already moved, which is the state the walk-back has to undo.
        var broken = new InterruptedStore(dir, 2);
        refused(() -> broken.rename("School", "University"), "a rename whose vault move fails");
        check(broken.moved.getFirst().equals("School.vault.local-key -> University.vault.local-key"),
            "the unlock key moved first, before the vault, got " + broken.moved);
        check(good.exists("School") && good.passwordless("School"),
            "and came back with the vault, so the vault is not left without it");
        check(!good.exists("University") && !Files.exists(LocalAccess.keyPath(good.path("University"))),
            "nothing of it is left under the new name, got " + namesOf(dir));
        try (var vault = good.open("School")) { check(vault.load().equals(invented), "with everything in it"); }

        // And failing the last move — the backup, with both the key and the vault
        // already moved — puts all three back.
        var later = new InterruptedStore(dir, 3);
        refused(() -> later.rename("School", "University"), "a rename whose last move fails");
        check(later.moved.get(0).equals("School.vault.local-key -> University.vault.local-key")
            && later.moved.get(1).equals("School.vault -> University.vault")
            && later.moved.get(2).startsWith("School.vault.") && later.moved.get(2).endsWith(".bak"),
            "the key, the vault and then the backup were moved, in that order, got " + later.moved);
        check(good.exists("School") && good.passwordless("School") && good.backups("School").length == 1,
            "a failure at the last step puts the vault, its key and its backup back, got " + namesOf(dir));
        try (var vault = good.open("School")) {
            check(vault.load().equals(invented), "with every session, task, reward and save intact");
        }
    }

    // ---- deleting ------------------------------------------------------------

    /** A deletion removes the vault, its key and its backups, and leaves nothing behind. */
    private static void deletesEverythingThatBelongs(Path dir) throws Exception {
        var store = new VaultStore(dir);
        try (var vault = store.createPasswordless("School")) {
            vault.save(contents(Gen3Fixture.save(2, 4), "Study", 30));
            vault.backup();
        }
        check(store.backups("School").length == 1, "there is a backup to remove");

        store.delete("School");
        check(!store.exists("School"), "the vault is gone");
        check(store.names().isEmpty(), "and nothing is listed");
        check(store.backups("School").length == 0, "its backups are gone too");
        check(namesOf(dir).stream().noneMatch(n -> n.startsWith("School")), "no part of it is left behind");
        check(namesOf(dir).stream().noneMatch(n -> n.startsWith(".deleting")), "and the staging area is clean");
        check(Files.isDirectory(dir), "and the folder Yoru keeps its vaults in is not part of what goes");
        refused(() -> store.delete("School"), "deleting a vault that is already gone");
    }

    /**
     * The one that matters: a deletion that fails leaves the vault exactly as it
     * was, byte for byte, with its key and its backups still there.
     */
    private static void aFailedDeleteLosesNothing(Path dir) throws Exception {
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        var good = new VaultStore(dir);
        try (var vault = good.createPasswordless("School")) {
            vault.save(invented);
            vault.backup();
        }
        var vaultBytes = Files.readAllBytes(good.path("School"));
        var keyBytes = Files.readAllBytes(LocalAccess.keyPath(good.path("School")));
        var backups = good.backups("School").length;

        var broken = new RefusingStore(dir);
        refused(() -> broken.delete("School"), "a deletion the disk refuses");
        check(broken.refusals > 0, "the failure happened at the delete step, after the copy was verified");

        check(good.exists("School"), "the vault is still there");
        check(Arrays.equals(Files.readAllBytes(good.path("School")), vaultBytes),
            "its bytes are exactly what they were");
        check(Arrays.equals(Files.readAllBytes(LocalAccess.keyPath(good.path("School"))), keyBytes),
            "its unlock key was put back");
        check(good.backups("School").length == backups, "its backups were put back");
        check(good.passwordless("School"), "and it still opens without a password");
        try (var vault = good.open("School")) {
            check(vault.load().equals(invented), "with every session, task, reward and save intact");
        }
        check(namesOf(dir).stream().noneMatch(n -> n.startsWith(".deleting")),
            "the staging copy was cleaned up once the vault was back");
    }

    // ---- what a kill leaves behind -------------------------------------------

    /** Stages what a delete killed before it removed anything would have staged: a copy of every part. */
    private static void stageEverything(Path dir, String name) throws IOException {
        Path staging = dir.resolve(".deleting").resolve(name);
        Files.createDirectories(staging);
        for (Path file : filesNamed(dir, name)) Files.copy(file, staging.resolve(file.getFileName()));
    }

    /** Every file in a folder whose name starts with this one. */
    private static List<Path> filesNamed(Path dir, String name) throws IOException {
        return namesOf(dir).stream().filter(n -> n.startsWith(name)).map(dir::resolve)
            .filter(Files::isRegularFile).toList();
    }

    /**
     * A delete killed before it removed anything is swept: the vault is exactly
     * as it was, and the staging copy it left goes. A delete that never finished
     * must not be allowed to look like one that did.
     */
    private static void anInterruptedDeleteIsSwept(Path dir) throws Exception {
        var store = new VaultStore(dir);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        try (var vault = store.createPasswordless("School")) {
            vault.save(invented);
            vault.backup();
        }
        var before = namesOf(dir);
        stageEverything(dir, "School");

        var recovered = new VaultStore(dir).reconcile();
        check(recovered.undeleted().isEmpty(), "nothing had to be put back, because nothing was removed");
        check(namesOf(dir).equals(before), "the vault, its key and its backup are where they were, got " + namesOf(dir));
        check(!Files.exists(dir.resolve(".deleting")), "and the half-made staging folder is gone");
        try (var vault = store.open("School")) {
            check(vault.load().equals(invented), "the vault opens with everything in it");
        }
    }

    /**
     * A delete killed part way — the vault gone, its key and its backup still
     * there — is put back on the next start. The staged copies are complete and
     * proven before anything is removed, so nothing is lost and the same delete
     * can be asked for again.
     */
    private static void anInterruptedDeleteIsPutBack(Path dir) throws Exception {
        var store = new VaultStore(dir);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        try (var vault = store.createPasswordless("School")) {
            vault.save(invented);
            vault.backup();
        }
        var vaultBytes = Files.readAllBytes(store.path("School"));
        stageEverything(dir, "School");
        Files.delete(store.path("School"));   // the vault is the first thing a delete removes

        var recovered = new VaultStore(dir).reconcile();
        check(recovered.undeleted().equals(List.of("School")),
            "the vault that had gone is reported put back, got " + recovered);
        check(store.exists("School"), "and it is there again");
        check(Arrays.equals(Files.readAllBytes(store.path("School")), vaultBytes), "byte for byte");
        check(store.passwordless("School"), "with its unlock key still beside it");
        check(store.backups("School").length == 1, "and its backup");
        check(!Files.exists(dir.resolve(".deleting")), "the staging copy is gone once the vault was put back");
        try (var vault = store.open("School")) {
            check(vault.load().equals(invented), "and it opens with every session, task, reward and save intact");
        }
    }

    /**
     * A delete killed after it had removed everything — only the staging copy was
     * left — is finished rather than undone: the person asked for the vault to go,
     * and the copies were only ever there to make the removal safe.
     */
    private static void anInterruptedDeleteThatHadFinishedIsFinished(Path dir) throws Exception {
        var store = new VaultStore(dir);
        try (var vault = store.createPasswordless("School")) {
            vault.save(contents(null, "Study", 30));
            vault.backup();
        }
        stageEverything(dir, "School");
        for (Path file : filesNamed(dir, "School")) Files.delete(file);

        var recovered = new VaultStore(dir).reconcile();
        check(recovered.empty(), "nothing was put back, got " + recovered);
        check(store.names().isEmpty(), "the vault is gone, as it was asked to be, got " + store.names());
        check(namesOf(dir).stream().noneMatch(n -> n.startsWith("School")), "and no part of it is left behind");
        check(!Files.exists(dir.resolve(".deleting")), "nor the staging copy that stood for it");
        check(Files.isDirectory(dir), "and the folder Yoru keeps its vaults in is still there");
    }

    /**
     * An unlock key staged by an unfinished delete is only ever put back beside
     * the vault it opens: a different vault that has taken the name since must
     * not be handed a key that cannot open it (#41).
     */
    private static void aStaleKeyIsNotPutBackBesideAnotherVault(Path dir) throws Exception {
        var store = new VaultStore(dir);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        try (var vault = store.createPasswordless("School")) {
            vault.save(invented);
            vault.backup();
        }
        stageEverything(dir, "School");
        // The delete had got as far as removing the vault and its key, then stopped.
        Files.delete(store.path("School"));
        Files.delete(LocalAccess.keyPath(store.path("School")));
        // The name is free again, and a different vault takes it.
        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(State.empty()); }

        new VaultStore(dir).reconcile();
        check(!Files.exists(LocalAccess.keyPath(store.path("School"))),
            "the staged key is not put back beside a vault it does not open, got " + namesOf(dir));
        check(!store.passwordless("School"), "and the new vault is not listed as password-free");
        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            check(vault.load().equals(State.empty()), "the new vault still opens with its own password");
        }
    }

    /**
     * A rename killed between its moves — the key gone to the new name, the vault
     * still under the old one — is put right on the next start.
     *
     * This is the one shape that would otherwise be permanent, because the vault
     * is a vault with no key, and the migration has nothing to re-pair it with.
     * The key is given back to the vault it opens, and the vault's own encryption
     * is the proof: it can never go back to the wrong one.
     */
    private static void anOrphanedKeyIsGivenBackToItsVault(Path dir) throws Exception {
        var store = new VaultStore(dir);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        try (var vault = store.createPasswordless("School")) { vault.save(invented); }
        Files.move(LocalAccess.keyPath(store.path("School")), dir.resolve("University.vault.local-key"));
        check(!store.passwordless("School"), "the vault is left without its key");
        check(new VaultStore(dir).passwordless("University"), "and the key under a name with no vault");

        var recovered = new VaultStore(dir).reconcile();
        check(recovered.rekeyed().equals(List.of("School")),
            "the key was given back to the vault it opens, got " + recovered);
        check(!Files.exists(dir.resolve("University.vault.local-key")),
            "and is no longer filed under the name its vault never reached");
        check(new VaultStore(dir).names().equals(List.of("School")), "the store lists one vault again");
        try (var vault = new VaultStore(dir).open("School")) {
            check(vault.load().equals(invented), "which opens with no password and everything in it");
        }
        check(new VaultStore(dir).reconcile().empty(), "and a second start has nothing left to put right");
    }

    /**
     * A key with no vault beside it must not be created over (#41): a new vault
     * there would be listed as password-free while its unlock key opened nothing,
     * and the welcome screen would take that branch every time. It must not be
     * renamed onto either.
     */
    private static void aLeftoverKeyIsRefusedRatherThanTrapped(Path dir) throws Exception {
        var store = new VaultStore(dir);
        Files.writeString(dir.resolve("Gone.vault.local-key"), INVENTED_KEY);
        refused(() -> store.create("Gone", PASSWORD.toCharArray()), "creating a vault over a leftover unlock key");
        refused(() -> store.createPasswordless("Gone"), "making a password-free vault over a leftover unlock key");
        check(!store.exists("Gone") && !Files.exists(dir.resolve("Gone.vault")), "and no vault was made");
        check(Files.exists(dir.resolve("Gone.vault.local-key")), "the key that was there is left alone");

        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(State.empty()); }
        String message = null;
        try { store.rename("School", "Gone"); } catch (Exception e) { message = e.getMessage(); }
        check(message != null, "renaming onto a leftover key is refused");
        check(message != null && message.contains("Gone"), "and says which name, got " + message);
        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            check(vault.load().equals(State.empty()), "the vault that was there is untouched");
        }
    }

    /**
     * A password-free vault that never came to be must not leave its key behind:
     * a key with no vault beside it is the same trap from the other side (#41),
     * so a failure part way through takes it back.
     */
    private static void aKeyMadeForAVaultThatNeverCameIsTakenBack(Path dir) throws Exception {
        var store = new VaultStore(dir);
        // Another session has the vault open, so the vault cannot be made here.
        try (var channel = FileChannel.open(dir.resolve("Personal.vault.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            refused(() -> store.createPasswordless("Personal"), "making a vault another session has open");
        }
        check(!LocalAccess.enabled(store.path("Personal")),
            "the key made for the vault that never came is gone");
        check(!store.exists("Personal"), "and no vault was left behind");
        check(store.createPasswordless("Personal") != null, "so the next attempt can still make it");
    }

    /**
     * The list the welcome screen shows holds only names every later call can be
     * handed. A name copied into the folder — a dot, or an accent written as two
     * characters — used to be listed and then throw on the click.
     */
    private static void onlyNamesTheAppCanUseAreListed(Path dir) throws Exception {
        var store = new VaultStore(dir);
        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(State.empty()); }
        Files.writeString(dir.resolve("a.b.vault"), "not a vault");
        // "Cafe" with a combining acute: a letter and a mark, which no name may hold.
        Files.writeString(dir.resolve("Cafe\u0301.vault"), "not a vault");

        List<String> names = store.names();
        check(names.equals(List.of("School")), "only names Yoru can use are listed, got " + names);
        for (String name : names) {
            boolean usable = true;
            try {
                store.passwordless(name);
                store.exists(name);
                store.backups(name);
            } catch (RuntimeException e) {
                usable = false;
            }
            check(usable, "the unlock path can ask about \"" + name + "\" without throwing");
        }
        check(Files.exists(dir.resolve("a.b.vault")) && Files.exists(dir.resolve("Cafe\u0301.vault")),
            "and a name Yoru cannot use is left where it was rather than deleted");
    }

    /**
     * An adoption killed between its two moves — the key in managed storage, the
     * vault still in the old folder — is finished by the next run, onto the key
     * that was already waiting for it.
     */
    private static void anAdoptionKilledBetweenTheKeyAndTheVaultFinishes(Path dir, Path legacy) throws Exception {
        Files.createDirectories(dir);
        Files.createDirectories(legacy);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        var secret = LocalAccess.create(legacy.resolve("School.vault"));
        try (var vault = new EncryptedVault(legacy.resolve("School.vault"), secret)) { vault.save(invented); }
        Files.move(LocalAccess.keyPath(legacy.resolve("School.vault")), dir.resolve("School.vault.local-key"));

        var store = new VaultStore(dir);
        var migration = store.migrate(legacy);
        check(migration.adopted().equals(List.of("School")),
            "the vault is adopted under the name its key was left at, got " + migration);
        check(store.passwordless("School"), "with the key that was already there");
        check(!Files.exists(legacy.resolve("School.vault")), "and the older copy is gone");
        try (var vault = store.open("School")) {
            check(vault.load().equals(invented), "the vault opens with everything it held");
        }
        check(new VaultStore(dir).reconcile().empty(), "and nothing is left for a recovery to do");
    }

    /**
     * A key that is not a vault's must never be handed to it — a password vault
     * with a stray key beside it would be listed as password-free and never open.
     * An adoption that meets a stranger's key leaves it where it is and takes the
     * next free name, and a recovery will not give it to the wrong vault.
     */
    private static void aStrangerKeyIsNotHandedToAVault(Path dir, Path legacy) throws Exception {
        Files.createDirectories(dir);
        Files.createDirectories(legacy);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        try (var vault = new VaultStore(dir).create("Work", PASSWORD.toCharArray())) { vault.save(State.empty()); }
        Files.writeString(dir.resolve("School.vault.local-key"), INVENTED_KEY);
        var secret = LocalAccess.create(legacy.resolve("School.vault"));
        try (var vault = new EncryptedVault(legacy.resolve("School.vault"), secret)) { vault.save(invented); }

        var store = new VaultStore(dir);
        var migration = store.migrate(legacy);
        check(migration.adopted().size() == 1, "the older vault was adopted, got " + migration);
        String name = migration.adopted().getFirst();
        check(!name.equals("School"), "under a name the stranger's key does not hold, got " + name);
        check(store.passwordless(name), "and it brought its own key, so it opens without a password");
        try (var vault = store.open(name)) {
            check(vault.load().equals(invented), "with everything it held");
        }

        var recovered = new VaultStore(dir).reconcile();
        check(recovered.empty(), "a key is not given to a vault it does not open, got " + recovered);
        check(!store.passwordless("Work"), "so the password vault is still a password vault");
        try (var vault = store.open("Work", PASSWORD.toCharArray())) {
            check(vault.load().equals(State.empty()), "which still opens with its password");
        }
    }

    // ---- what a deletion removes, in words -----------------------------------

    /** The confirmation's words name everything that goes, with the counts. */
    private static void contentsSayWhatWouldBeDeleted() throws Exception {
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        var lines = String.join("\n", VaultStore.Contents.of(invented, 2).lines()).toLowerCase(Locale.ROOT);
        for (String expected : List.of("study data", "1 recorded session", "1 task", "1 tracker",
            "game progress", "a game save", "backups", "2 encrypted backups", "reward ledger", "1 pokémon"))
            check(lines.contains(expected), "the confirmation mentions \"" + expected + "\", got: " + lines);

        var empty = String.join(" ", VaultStore.Contents.of(State.empty(), 0).lines());
        check(empty.contains("nothing recorded") && empty.contains("no game save") && empty.contains("none"),
            "an empty vault says so, got: " + empty);
    }

    // ---- migration -----------------------------------------------------------

    /**
     * Vaults an older build left in the old folder are adopted into managed
     * storage, with their keys and backups, and the originals are only removed
     * once the copies are proven.
     */
    private static void migratesOlderVaultsWithoutLoss(Path dir, Path legacy) throws Exception {
        Files.createDirectories(legacy);
        var store = new VaultStore(dir);
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);

        // A password-free vault made the old way: file, key beside it, a backup and a stale lock.
        var secret = LocalAccess.create(legacy.resolve("School.vault"));
        var old = new EncryptedVault(legacy.resolve("School.vault"), secret);
        old.save(invented);
        old.backup();
        old.close();
        Files.writeString(legacy.resolve("School.vault.lock"), "");
        // A password vault, and something in the folder that is not a vault.
        try (var second = new EncryptedVault(legacy.resolve("Personal.vault"), PASSWORD.toCharArray())) {
            second.save(contents(null, "Reading", 20));
        }
        Files.writeString(legacy.resolve("notes.txt"), "not a vault");

        var vaultBytes = Files.readAllBytes(legacy.resolve("School.vault"));
        var migration = store.migrate(legacy);
        check(migration.left().isEmpty(), "nothing failed to move, got " + migration.left());
        check(migration.adopted().size() == 2, "both vaults were adopted, got " + migration.adopted());
        check(store.names().equals(List.of("Personal", "School")), "they are in managed storage now, got " + store.names());

        try (var vault = store.open("School")) {
            check(vault.load().equals(invented), "the migrated vault holds everything it held");
        }
        check(Arrays.equals(Files.readAllBytes(store.path("School")), vaultBytes), "byte for byte, not re-written");
        check(store.passwordless("School"), "its unlock key came with it");
        check(store.backups("School").length == 1, "and so did its backup");
        try (var vault = store.open("Personal", PASSWORD.toCharArray())) {
            check(vault.load().activities().getFirst().name().equals("Reading"), "the password vault opens as before");
        }
        check(Files.notExists(legacy.resolve("School.vault")) && Files.notExists(legacy.resolve("Personal.vault")),
            "the old copies are gone once the new ones are proven");
        check(Files.notExists(legacy.resolve("School.vault.lock")), "and the stale lock went with them");
        check(Files.exists(legacy.resolve("notes.txt")), "nothing else in the folder was touched");
    }

    /** Running the migration again changes nothing: what is adopted stays adopted. */
    private static void migrationIsIdempotent(Path dir, Path legacy) throws Exception {
        Files.createDirectories(legacy);
        var store = new VaultStore(dir);
        try (var vault = new EncryptedVault(legacy.resolve("School.vault"), PASSWORD.toCharArray())) {
            vault.save(contents(null, "Study", 30));
        }
        check(store.migrate(legacy).adopted().equals(List.of("School")), "the vault is adopted once");
        var bytes = Files.readAllBytes(store.path("School"));
        var again = store.migrate(legacy);
        check(again.adopted().isEmpty() && again.left().isEmpty(), "the second run has nothing to do");
        check(store.names().equals(List.of("School")), "the vault is still the only one");
        check(Arrays.equals(Files.readAllBytes(store.path("School")), bytes), "and unchanged");
        check(store.migrate(null).adopted().isEmpty(), "a machine with no old folder has nothing to migrate");
    }

    /**
     * An adoption interrupted part way leaves the older vault wholly where it
     * was, and a later run adopts it with everything it had.
     */
    private static void aFailedAdoptionLosesNothing(Path dir, Path legacy) throws Exception {
        Files.createDirectories(legacy);
        var secret = LocalAccess.create(legacy.resolve("School.vault"));
        var invented = contents(Gen3Fixture.save(2, 4), "Study", 30);
        var old = new EncryptedVault(legacy.resolve("School.vault"), secret);
        old.save(invented);
        old.backup();
        old.close();
        var vaultBytes = Files.readAllBytes(legacy.resolve("School.vault"));

        var broken = new InterruptedStore(dir);
        var migration = broken.migrate(legacy);
        check(migration.adopted().isEmpty() && migration.left().size() == 1,
            "the interrupted adoption is reported as left, got " + migration);
        check(new VaultStore(dir).names().isEmpty(), "and nothing was adopted after all");
        check(Files.exists(legacy.resolve("School.vault")), "the vault is exactly where it was");
        check(Arrays.equals(Files.readAllBytes(legacy.resolve("School.vault")), vaultBytes), "byte for byte");
        check(Files.exists(LocalAccess.keyPath(legacy.resolve("School.vault"))), "with its unlock key beside it");
        check(broken.backups("School").length == 0, "and no part of it left behind in managed storage");

        var later = new VaultStore(dir).migrate(legacy);
        check(later.adopted().equals(List.of("School")), "a later run adopts it, got " + later.adopted());
        try (var vault = new VaultStore(dir).open("School")) {
            check(vault.load().equals(invented), "with every session, task, reward and save intact");
        }
    }

    /** Two vaults with the same name are two vaults: neither is overwritten. */
    private static void migrationKeepsBothVaultsOnANameClash(Path dir, Path legacy) throws Exception {
        Files.createDirectories(legacy);
        var store = new VaultStore(dir);
        var mine = contents(null, "Mine", 30);
        var older = contents(null, "Older", 45);
        try (var vault = store.create("School", PASSWORD.toCharArray())) { vault.save(mine); }
        try (var vault = new EncryptedVault(legacy.resolve("School.vault"), PASSWORD.toCharArray())) {
            vault.save(older);
        }
        var migration = store.migrate(legacy);
        check(migration.adopted().size() == 1, "the older vault was adopted");
        check(store.names().size() == 2, "both vaults are here, got " + store.names());
        String adopted = migration.adopted().getFirst();
        check(store.exists(adopted) && !adopted.equals("School"), "under the next free name, got " + adopted);
        try (var vault = store.open("School", PASSWORD.toCharArray())) {
            check(vault.load().equals(mine), "the vault that was already here was not overwritten");
        }
        try (var vault = store.open(adopted, PASSWORD.toCharArray())) {
            check(vault.load().equals(older), "and the older one kept everything it had");
        }
    }

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("yoru-store-");
        try {
            createsAndReopens(fresh(root, "create"));
            refusesNamesThatAreNotNames(fresh(root, "names"));
            refusesToCreateOverAVault(fresh(root, "over"));
            renamesEverythingThatBelongs(fresh(root, "rename"));
            aFailedRenameKeepsTheOldName(fresh(root, "rename-failed"));
            aFailedRenamePutsTheKeyBackToo(fresh(root, "rename-key-failed"));
            deletesEverythingThatBelongs(fresh(root, "delete"));
            aFailedDeleteLosesNothing(fresh(root, "failed"));
            anInterruptedDeleteIsSwept(fresh(root, "delete-killed"));
            anInterruptedDeleteIsPutBack(fresh(root, "delete-part"));
            anInterruptedDeleteThatHadFinishedIsFinished(fresh(root, "delete-done"));
            aStaleKeyIsNotPutBackBesideAnotherVault(fresh(root, "delete-new-owner"));
            anOrphanedKeyIsGivenBackToItsVault(fresh(root, "orphan-key"));
            aLeftoverKeyIsRefusedRatherThanTrapped(fresh(root, "leftover-key"));
            aKeyMadeForAVaultThatNeverCameIsTakenBack(fresh(root, "half-made"));
            onlyNamesTheAppCanUseAreListed(fresh(root, "listed-names"));
            contentsSayWhatWouldBeDeleted();
            migratesOlderVaultsWithoutLoss(fresh(root, "migrate"), fresh(root, "migrate-old"));
            migrationIsIdempotent(fresh(root, "idempotent"), fresh(root, "idempotent-old"));
            aFailedAdoptionLosesNothing(fresh(root, "adopt-failed"), fresh(root, "adopt-failed-old"));
            anAdoptionKilledBetweenTheKeyAndTheVaultFinishes(fresh(root, "adopt-killed"), fresh(root, "adopt-killed-old"));
            aStrangerKeyIsNotHandedToAVault(fresh(root, "stranger-key"), fresh(root, "stranger-key-old"));
            migrationKeepsBothVaultsOnANameClash(fresh(root, "clash"), fresh(root, "clash-old"));
        } finally {
            deleteTree(root);
        }
        System.out.println("PASS: " + checks + " vault store checks (create, reopen, rename, delete, failed delete, killed delete, orphaned key, migration)");
    }
}
