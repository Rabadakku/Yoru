package dev.yoru.persistence;

import dev.yoru.domain.Model.State;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The folder Yoru keeps its vaults in, and everything done to one (#41).
 *
 * A vault is a name in this folder, never a path in the interface: the app
 * chooses the folder, so no screen has to ask anybody where a vault lives or
 * where to save one. That is also why the location is supplied rather than
 * hard-coded here — the tests run the whole thing in a temporary folder, and
 * nothing outside this class needs to know where the real one is.
 *
 * Everything that destroys is two-phase. A rename moves the parts in an order
 * that can be walked back, and moves the unlock key before the vault it opens,
 * so the worst a kill can leave is an orphan key rather than a vault nothing can
 * open. A delete copies — and proves, and flushes — the copy before it removes
 * anything, and puts the copies back if a removal fails, so a failed deletion
 * leaves the vault exactly as it was rather than half gone. Both hold the
 * vault's lock while they work, so a vault open in another window is refused
 * rather than moved out from under it.
 *
 * A kill between two moves cannot be walked back in process, so {@link
 * #reconcile()} recognises the shapes one leaves — an unfinished delete, a key
 * separated from its vault, and a password removal that stopped part way — and
 * puts them right before the vaults are listed.
 */
public class VaultStore {

    /** The suffix every vault file carries. */
    public static final String SUFFIX = ".vault";
    /**
     * What a new unlock key is called the rest of until the vault it is for has
     * been re-encrypted under it. Nothing reads a file with this ending as a key.
     */
    private static final String PENDING = ".pending";
    /** The folder Yoru chose for this machine's vaults: its own, under the user's Yoru folder. */
    private static final String FOLDER = "vaults";
    /** Where a deletion stages its copies until the original is gone. */
    private static final String STAGING = ".deleting";
    /** A vault name: letters, digits, spaces, underscores and dashes, 1 to 60. */
    private static final String VALID = "[\\p{L}\\p{N} _-]{1,60}";
    /**
     * The most bytes a new name may take on the disk. File systems allow 255 in
     * one name, and the longest one Yoru files beside a vault — a migration
     * backup set aside as stale — adds 71 to the vault's own name.
     */
    private static final int MAX_NAME_BYTES = 180;

    private final Path root;

    public VaultStore(Path root) {
        this.root = root.toAbsolutePath();
    }

    /** The folder Yoru chose for this machine's vaults. */
    public static VaultStore managed() {
        return new VaultStore(Path.of(System.getProperty("user.home"), "Yoru", FOLDER));
    }

    /** Where vaults made before Yoru managed them were left, and where the migration looks. */
    public static Path legacyRoot() {
        return Path.of(System.getProperty("user.home"), "Yoru");
    }

    /** The name a vault file is filed under. */
    public static String nameOf(Path vault) {
        String file = vault.getFileName().toString();
        return file.endsWith(SUFFIX) ? file.substring(0, file.length() - SUFFIX.length()) : file;
    }

    /**
     * A vault name that cannot become a path or a surprise: what the create and
     * rename dialogs accept, and what everything else here assumes.
     */
    public static String validate(String name) {
        String clean = name == null ? "" : name.strip();
        if (!valid(clean))
            throw new IllegalArgumentException("Use 1 to 60 letters, numbers, spaces, underscores or hyphens.");
        return clean;
    }

    /**
     * Whether a name is one {@link #validate} accepts unchanged.
     *
     * Throwing is right for a name somebody typed and wrong for a name read off
     * the disk, where the only sane answers are "list it" and "leave it alone".
     */
    private static boolean valid(String name) {
        return name != null && name.equals(name.strip()) && name.matches(VALID);
    }

    /**
     * Refuses a name the disk can hold as a vault but not as everything filed
     * beside one.
     *
     * Sixty letters is a length, not a size: a letter outside the basic planes
     * takes four bytes, and forty-eight of them leave a vault whose every backup
     * fails for a name that is too long — and with it every change that takes a
     * backup first. Only a name being taken for the first time is held to this.
     * {@link #validate} is not, because a vault already filed under a longer
     * name has to keep being listed and opened on the disk that holds it.
     */
    private static void requireStorable(String name) {
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES)
            throw new IllegalArgumentException("That name is too long for this disk; use fewer or simpler characters.");
    }

    public Path root() {
        return root;
    }

    public Path path(String name) {
        return root.resolve(validate(name) + SUFFIX);
    }

    /** The encrypted backups kept for a vault, oldest name first. */
    public Path[] backups(String name) {
        String prefix = validate(name) + SUFFIX;
        if (!Files.isDirectory(root)) return new Path[0];
        try {
            return backupsIn(root, prefix);
        } catch (IOException e) {
            return new Path[0];
        }
    }

    /** The encrypted backups in a folder that start with this prefix, oldest name first. */
    private static Path[] backupsIn(Path dir, String prefix) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                .map(Path::getFileName)
                .filter(file -> {
                    String text = file.toString();
                    return text.startsWith(prefix) && text.endsWith(".bak");
                })
                .sorted()
                .map(dir::resolve)
                .toArray(Path[]::new);
        }
    }

    public boolean exists(String name) {
        return Files.isRegularFile(path(name));
    }

    /**
     * The names of the vaults here, in order.
     *
     * Only names every other method can be handed. A file whose name Yoru could
     * not use — a dot copied in from elsewhere, an accent written as two
     * characters — is left exactly where it is and never listed, because the
     * interface shows names and every call it makes with one validates it:
     * listing such a name would throw the moment somebody clicked it.
     */
    public List<String> names() throws IOException {
        var out = new ArrayList<String>();
        for (Path file : vaultFiles()) {
            String name = nameOf(file);
            if (valid(name)) out.add(name);
        }
        return List.copyOf(out);
    }

    /** Every vault file here, whatever its name is worth. */
    private List<Path> vaultFiles() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                .filter(file -> {
                    String text = file.getFileName().toString();
                    return text.endsWith(SUFFIX) && !text.startsWith(".");
                })
                .sorted()
                .toList();
        }
    }

    // ---- creating and opening ------------------------------------------------

    /** Creates a vault under a name nothing is using yet. */
    public EncryptedVault create(String name, char[] password) throws IOException {
        name = validate(name);
        requireStorable(name);
        requireFree(name);
        Files.createDirectories(root);
        return new EncryptedVault(path(name), password);
    }

    /**
     * Creates a vault that opens without a password. The unlock secret lives
     * beside the vault, which is the whole of that mode's trade: anyone who can
     * read the files can open it.
     */
    public EncryptedVault createPasswordless(String name) throws IOException {
        name = validate(name);
        requireStorable(name);
        requireFree(name);
        Files.createDirectories(root);
        Path vault = path(name);
        char[] secret = LocalAccess.create(vault);
        try {
            return new EncryptedVault(vault, secret);
        } catch (IOException | RuntimeException e) {
            // Only a vault that was written and flushed may keep its key, so any
            // failure here takes it back: a key with no vault beside it is exactly
            // the trap that makes passwordless() promise an opening that does not
            // exist (#41). The vault itself is left alone — EncryptedVault writes
            // it through a temporary file and a move, so it is either not there or
            // complete, and the path is not ours to delete blindly.
            deleteQuietly(LocalAccess.keyPath(vault));
            throw e;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /** Deletes if it is there; a refusal is something the next attempt will meet again. */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public EncryptedVault open(String name, char[] password) throws IOException {
        Path vault;
        try {
            vault = requireVault(name);
        } catch (IOException | RuntimeException e) {
            // EncryptedVault wipes the password whatever happens to it, so a
            // refusal before it is reached has to wipe it too: the callers hand
            // over a copy and never see it again.
            Arrays.fill(password, '\0');
            throw e;
        }
        return new EncryptedVault(vault, password);
    }

    /** Opens a vault that has no password: its unlock key is beside it. */
    public EncryptedVault open(String name) throws IOException {
        if (!passwordless(name)) throw new IOException("That vault needs its password.");
        return open(name, LocalAccess.read(path(name)));
    }

    public boolean passwordless(String name) {
        return LocalAccess.enabled(path(name));
    }

    /**
     * The unlock secret of a vault that has no password, for a session that may
     * have to open it again — the key is beside the vault, never in the app.
     */
    public char[] secretOf(String name) throws IOException {
        return LocalAccess.read(requireVault(name));
    }

    /** The vault filed under a name, or the refusal every operation here gives when there is none. */
    private Path requireVault(String name) throws IOException {
        Path vault = path(name);
        if (!Files.isRegularFile(vault)) throw new IOException("There is no vault called \"" + validate(name) + "\".");
        return vault;
    }

    /** Where a vault's new unlock key waits while the vault is re-encrypted under it. */
    private static Path pendingKey(Path vault) {
        return Path.of(LocalAccess.keyPath(vault) + PENDING);
    }

    /**
     * Takes the password off a vault that has one, so that it opens without
     * being asked for anything (#41).
     *
     * The vault is handed in already open. Only a vault somebody has opened has
     * proved its password, and a change made while the app is running has to
     * write the state that is on screen rather than the one last read off the
     * disk.
     *
     * The one shape this class refuses to let anything else land on is a key
     * beside a vault it cannot open: the vault would be listed as password-free
     * and never again asked for the password that does open it. So the new key
     * is written and flushed under a name nothing reads as a key, the vault is
     * re-encrypted under it, and only then is it moved to the name that says the
     * vault has no password. A failure before that takes the key back, and the
     * vault still opens with the password it had. A kill leaves the key under
     * its waiting name, which {@link #reconcile()} finishes on the next start.
     *
     * @return the vault's new unlock secret, for the session that has to keep it
     */
    public char[] removePassword(String name, EncryptedVault vault, State state) throws IOException {
        name = validate(name);
        Path file = requireVault(name);
        if (passwordless(name)) throw new IOException("\"" + name + "\" already opens without a password.");
        Path pending = pendingKey(file);
        // Never written over: if an earlier removal's re-encryption landed, the
        // key waiting there is the only thing that opens the vault.
        if (Files.exists(pending))
            throw new IOException("An earlier attempt to take the password off \"" + name + "\" did not finish. "
                + "Restart Yoru so it can finish that first.");
        char[] secret = null;
        try {
            secret = LocalAccess.writeKey(pending);
            vault.changeSecret(secret.clone(), state);
        } catch (IOException | RuntimeException e) {
            // The vault still opens with the password it always had: changeSecret
            // puts its own key back, and the file was never written.
            deleteQuietly(pending);
            if (secret != null) Arrays.fill(secret, '\0');
            throw e;
        }
        // From here the vault opens with this key and nothing else, so it is
        // never taken back. A move that fails leaves it under its waiting name,
        // where the next start puts it in place, and this session keeps the
        // secret it needs in the meantime.
        try {
            move(pending, LocalAccess.keyPath(file));
        } catch (IOException | RuntimeException notYet) {
            return secret;
        }
        forceDir(root);
        return secret;
    }

    /**
     * Refuses a name that is already spoken for: by a vault, or by the unlock key
     * of one that is not there.
     *
     * A key with no vault beside it must never be written over or renamed onto.
     * The vault that landed on that name would be listed as password-free — the
     * key is right there — while `passwordless()` sent every attempt through a
     * branch that cannot open it, which is a vault nobody can ever open (#41).
     */
    private void requireFree(String name) throws IOException {
        if (exists(name)) throw new IOException("There is already a vault called \"" + name + "\".");
        if (LocalAccess.enabled(path(name)))
            throw new IOException("An unlock key for \"" + name + "\" is here without its vault, so nothing else may take "
                + "that name: it would be listed as password-free and never open. Remove the leftover key file ("
                + name + SUFFIX + LocalAccess.SUFFIX + "), or use another name.");
    }

    /**
     * Takes a vault's lock — the one an open vault holds — for as long as its
     * files are being moved, so a vault open in another Yoru window is refused
     * rather than split in two: that window would go on saving under the old
     * name. Closing the channel gives the lock up.
     */
    private static FileChannel lock(Path vault, String name) throws IOException {
        var channel = FileChannel.open(EncryptedVault.lockPath(vault), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock held;
            try {
                held = channel.tryLock();
            } catch (OverlappingFileLockException openHere) {
                held = null;
            }
            if (held == null) throw new IOException("\"" + name + "\" is open in another Yoru window. Close it there first.");
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    // ---- renaming ------------------------------------------------------------

    /**
     * Renames a vault, and everything filed under its old name with it.
     *
     * The unlock key moves first, then the vault, then the rest, and a step that
     * fails walks the earlier moves back — so a vault is never left half renamed,
     * and never both. The order is the safety argument: a kill in the middle of a
     * rename is possible whatever the order, and an orphan key costs nothing
     * while a vault with no key can never be opened. Moving the key first leaves
     * the key already at the new name and the vault at the old one — a shape
     * {@link #reconcile()} recognises and puts right — where moving the vault
     * first would leave a vault that nothing can pair with a dead key.
     *
     * The vault must be closed: the name is how it is found, and an open vault
     * still writes to the name it was opened under. One open in another window
     * is refused.
     */
    public void rename(String from, String to) throws IOException {
        from = validate(from);
        to = validate(to);
        if (from.equals(to)) return;
        Path source = requireVault(from);
        Path target = path(to);
        // A disk that does not tell letter case apart — the default on macOS and
        // Windows — finds "study" already at "Study", because it is the same
        // file. That is the vault being renamed, not one in the way, and the
        // moves below change the case.
        boolean caseOnly = from.equalsIgnoreCase(to) && Files.exists(target) && Files.isSameFile(source, target);
        if (!caseOnly) requireFree(to);
        requireStorable(to);

        try (var held = lock(source, from)) {
            var moves = new ArrayList<Path[]>();
            try {
                Path key = LocalAccess.keyPath(source);
                if (Files.isRegularFile(key)) {
                    Path moved = LocalAccess.keyPath(target);
                    move(key, moved);
                    moves.add(new Path[] { key, moved });
                }
                // A key still waiting to be put in place may be the only thing
                // that opens the vault, so it goes where the vault goes, and
                // before it for the same reason the key does.
                Path waiting = pendingKey(source);
                if (Files.isRegularFile(waiting)) {
                    Path moved = pendingKey(target);
                    move(waiting, moved);
                    moves.add(new Path[] { waiting, moved });
                }
                move(source, target);
                moves.add(new Path[] { source, target });
                for (Path backup : backups(from)) {
                    Path moved = root.resolve(to + SUFFIX + backup.getFileName().toString().substring((from + SUFFIX).length()));
                    move(backup, moved);
                    moves.add(new Path[] { backup, moved });
                }
            } catch (IOException | RuntimeException e) {
                walkBack(moves);
                throw new IOException("Renaming \"" + from + "\" failed, so it keeps its old name. " + reason(e), e);
            }
        }
        // After the rename, never inside it: an empty lock file that will not go
        // is no reason to undo a rename that worked. On a disk that does not tell
        // case apart this is the new name's lock file too, which costs nothing:
        // it is made again whenever the vault is opened.
        try {
            Files.deleteIfExists(EncryptedVault.lockPath(source));
        } catch (IOException ignored) {
        }
    }

    /**
     * Undoes moves in reverse: the vault first, then its key. A kill in the
     * middle of walking back leaves the same shape a kill in the middle of moving
     * does, and the next start puts that right — as it does a step that fails here.
     */
    private void walkBack(List<Path[]> moves) {
        for (int i = moves.size() - 1; i >= 0; i--) {
            try {
                move(moves.get(i)[1], moves.get(i)[0]);
            } catch (IOException ignored) {
            }
        }
    }

    // ---- deleting ------------------------------------------------------------

    /**
     * Deletes a vault, once a proven copy of it is in hand.
     *
     * The order is the whole safety argument: every file the vault owns is
     * copied, flushed to the disk and verified first, and only then removed. The
     * flush is what makes it hold against a power cut rather than only against an
     * exception: a copy in the page cache is not a copy when the machine stops.
     * A removal that fails has the copies put back and is reported, so a failed
     * deletion leaves the vault exactly as it was — never half deleted, and never
     * the only copy gone. On success the staging copy goes too, because the
     * confirmation promised the data was being removed.
     *
     * A kill before the removal loop finished leaves the copies in `.deleting/`
     * and is undone by {@link #reconcile()} on the next start, and until then a
     * second delete of the same vault is refused: what is staged may be the only
     * copy of something the first could not put back. The vault must be closed,
     * and one open in another window is refused.
     */
    public void delete(String name) throws IOException {
        name = validate(name);
        Path vault = requireVault(name);
        Path staging = root.resolve(STAGING).resolve(name);
        if (holdsAnything(staging))
            throw new IOException("An earlier deletion of \"" + name + "\" left its copy in place. "
                + "Restart Yoru so it can be put back first.");
        try (var held = lock(vault, name)) {
            List<Path> present = belongingTo(name);
            try {
                Files.createDirectories(staging);
                for (Path file : present) copyAndVerify(file, staging);
                forceDir(staging);
            } catch (IOException | RuntimeException e) {
                deleteTree(staging);
                throw new IOException("Deleting \"" + name + "\" failed before anything was removed, so it is untouched. "
                    + reason(e), e);
            }
            try {
                for (Path file : present) removeFile(file);
                // The removals reach the disk before the copies that stand for them go,
                // so a kill cannot bring back a vault that was deleted.
                forceDir(root);
            } catch (IOException | RuntimeException e) {
                throw putBack(name, present, staging, e);
            }
            deleteTree(staging);
        }
        // The lock goes last, once it is given up: a lock file that is held
        // cannot be removed on Windows, and it holds nothing — it is made again
        // whenever a vault by this name is opened.
        try {
            Files.deleteIfExists(EncryptedVault.lockPath(vault));
        } catch (IOException ignored) {
        }
    }

    /** Whether a folder is there with anything in it. */
    private static boolean holdsAnything(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var files = Files.list(dir)) {
            return files.findAny().isPresent();
        }
    }

    /** Removes one file. Overridable so a test can prove the restore path. */
    void removeFile(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    /**
     * Every file a vault owns, the vault itself first: its unlock key, a key
     * still waiting to be put in place, and its backups. Not its lock, which
     * the deletion is holding.
     */
    private List<Path> belongingTo(String name) {
        var out = new ArrayList<Path>();
        Path vault = path(name);
        if (Files.isRegularFile(vault)) out.add(vault);
        for (Path key : List.of(LocalAccess.keyPath(vault), pendingKey(vault)))
            if (Files.isRegularFile(key)) out.add(key);
        out.addAll(List.of(backups(name)));
        return out;
    }

    private static void copyAndVerify(Path file, Path staging) throws IOException {
        Path copy = staging.resolve(file.getFileName());
        Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
        force(copy);
        if (Files.mismatch(file, copy) != -1)
            throw new IOException("The copy of " + file.getFileName() + " did not match, so nothing was deleted.");
    }

    /**
     * Flushes a file to the disk rather than the page cache — the same thing
     * {@link EncryptedVault#save} does before a vault it wrote is real. A
     * deletion's promise rests on its copy, so the copy has to be on the disk
     * before the original is taken off it; and a copy put back has to be there
     * before the one it came from goes.
     */
    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Flushes a directory's own entries, best effort: Java has no portable way to
     * open a directory, and a platform that refuses gains nothing from being told
     * twice. The files themselves are what has to be durable. Used for a
     * deletion's removals, and for the rename that publishes a saved vault.
     */
    static void forceDir(Path dir) {
        try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * What went wrong, in words that do not say where the vaults are (#41).
     *
     * The file system's messages are the paths it failed on, and these reach a
     * dialog people screenshot when they ask for help — so its reason is kept
     * and its paths are not. The exception stays the cause, with every detail.
     */
    static String reason(Throwable e) {
        if (e instanceof AccessDeniedException) return "Permission was refused.";
        if (e instanceof NoSuchFileException) return "A file it needed was not there.";
        if (e instanceof FileAlreadyExistsException) return "A file was already in the way.";
        if (e instanceof DirectoryNotEmptyException) return "A folder in the way was not empty.";
        if (e instanceof FileSystemException fs) {
            String why = fs.getReason();
            return why == null || why.isBlank() ? "The disk refused." : why.strip() + ".";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank() || message.contains("/") || message.contains("\\"))
            return "The disk refused.";
        return message;
    }

    /** Puts the verified copies back, and says what happened if even that fails. */
    private IOException putBack(String name, List<Path> present, Path staging, Exception failure) {
        try {
            for (Path file : present) {
                Path copy = staging.resolve(file.getFileName());
                if (Files.isRegularFile(copy)) {
                    Files.copy(copy, file, StandardCopyOption.REPLACE_EXISTING);
                    force(file);
                }
                if (!Files.isRegularFile(file) || Files.mismatch(file, copy) != -1)
                    throw new IOException("the copy of " + file.getFileName() + " could not be put back");
            }
            // The copies are the only other copy, so what they put back is on the
            // disk — names and all — before they go.
            forceDir(root);
        } catch (IOException restore) {
            // Nothing is thrown away here: the copies are still in the staging folder.
            return new IOException("Deleting \"" + name + "\" failed and it could not be put back. The untouched copy "
                + "Yoru made first is still there. " + reason(restore), failure);
        }
        deleteTree(staging);
        return new IOException("Deleting \"" + name + "\" failed, so it was put back exactly as it was. "
            + reason(failure), failure);
    }

    // ---- what a kill left behind ---------------------------------------------

    /** What a recovery put right: the vaults an unfinished delete had removed, and the keys put back with their vault. */
    public record Recovery(List<String> undeleted, List<String> rekeyed) {
        public boolean empty() { return undeleted.isEmpty() && rekeyed.isEmpty(); }
    }

    /**
     * Puts right what a kill left half-done, before the vaults are listed (#41).
     *
     * Three things here move files, and a kill between two moves cannot be walked
     * back in process. Each leaves a shape this recognises:
     *
     * <ul>
     *   <li>An unfinished delete. Everything the vault owns was copied, flushed
     *       and verified before a single original was removed, so the staged copy
     *       is complete: if any original is still there the delete had not
     *       finished and every missing one goes back — nothing lost, and the same
     *       delete can be asked for again. If every original is gone the removal
     *       loop had finished and only the staged copy was left, so the deletion
     *       the person asked for is completed rather than undone.</li>
     *   <li>A password removal that stopped part way. Its key waits under a name
     *       nothing reads as a key until the vault has been re-encrypted under
     *       it. One that opens its vault is one whose re-encryption landed, and
     *       is put where it belongs; one that does not is one whose re-encryption
     *       never happened, beside a vault that still opens with its password,
     *       and goes.</li>
     *   <li>A key separated from its vault. A rename or an adoption killed after
     *       the key moved and before the vault did leaves a vault with no key and
     *       a key with no vault, and that is the one shape that would otherwise
     *       stay broken: the migration has nothing to re-pair it with, because
     *       the vault is no longer in the old folder. The key is tried against
     *       every vault here that has none and given back to the one it opens —
     *       the vault's own encryption is the proof, so it can never go back to
     *       the wrong vault.</li>
     * </ul>
     *
     * A key that opens no vault here is left exactly where it is. It may belong
     * to a vault Yoru has not been shown yet, or one it cannot read right now,
     * and discarding it would be the only irreversible act in a path whose whole
     * purpose is to lose nothing. The name it sits on is not handed out by
     * {@link #create} or {@link #rename}, and the refusal says why.
     */
    public Recovery reconcile() throws IOException {
        // The vaults go back before the keys are matched, so a vault an unfinished
        // delete had removed is here to be matched like any other.
        var undeleted = rollBackDeletes();
        var rekeyed = new ArrayList<String>();
        var keys = new ArrayList<>(orphanKeys());
        for (Path pending : filesEndingWith(SUFFIX + LocalAccess.SUFFIX + PENDING)) {
            Path vault = root.resolve(without(pending, LocalAccess.SUFFIX + PENDING));
            // A waiting key whose vault has moved on — renamed in the session that
            // could not put the key in place — is matched like any other.
            if (!Files.isRegularFile(vault)) keys.add(pending);
            else if (finishRemoval(pending, vault)) rekeyed.add(nameOf(vault));
        }
        if (!keys.isEmpty()) {
            var keyless = new ArrayList<Path>();
            for (Path vault : vaultFiles()) if (!LocalAccess.enabled(vault)) keyless.add(vault);
            for (Path key : keys) {
                Path vault = null;
                for (int i = 0; i < keyless.size(); i++) {
                    if (keyOpens(key, keyless.get(i))) { vault = keyless.remove(i); break; }
                }
                if (vault == null) continue;
                move(key, LocalAccess.keyPath(vault));
                rekeyed.add(nameOf(vault));
            }
        }
        return new Recovery(undeleted, List.copyOf(rekeyed));
    }

    /**
     * Finishes a password removal a kill stopped part way, or clears away one
     * that never reached the vault.
     *
     * Only a definite answer acts. A vault open in another window may be in the
     * middle of that very removal, and one that cannot be read right now may be
     * the vault this key alone opens, so either leaves the key where it is for
     * the next start. A key file cut short while it was written goes: the vault
     * was not touched until the key was whole and on the disk.
     *
     * @return whether the vault now has its key beside it
     */
    private boolean finishRemoval(Path pending, Path vault) {
        // Not a shape a removal leaves — it never writes over a key — so there
        // is nothing certain to act on.
        if (LocalAccess.enabled(vault)) return false;
        try (var held = lock(vault, nameOf(vault))) {
            char[] secret;
            try {
                secret = LocalAccess.readKey(pending);
            } catch (LocalAccess.Malformed cutShort) {
                Files.delete(pending);
                return false;
            }
            boolean opens;
            try {
                opens = EncryptedVault.decrypts(vault, secret);
            } finally {
                Arrays.fill(secret, '\0');
            }
            if (!opens) {
                Files.delete(pending);
                return false;
            }
            move(pending, LocalAccess.keyPath(vault));
            forceDir(root);
            return true;
        } catch (IOException | RuntimeException unsure) {
            return false;
        }
    }

    /**
     * Whether the unlock key in this file is the one a vault was encrypted with.
     *
     * The key is read from the file itself, so it need not sit beside the vault
     * it is tried against: a staged copy, a key a kill separated from its vault,
     * a key still waiting to be put in place. A file that is not a key opens
     * nothing.
     */
    private static boolean keyOpens(Path key, Path vault) {
        char[] secret;
        try {
            secret = LocalAccess.readKey(key);
        } catch (IOException malformed) {
            return false;
        }
        try {
            return EncryptedVault.opens(vault, secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /** Unlock keys here whose vault is not beside them. */
    private List<Path> orphanKeys() throws IOException {
        return filesEndingWith(SUFFIX + LocalAccess.SUFFIX).stream()
            .filter(key -> !Files.isRegularFile(root.resolve(without(key, LocalAccess.SUFFIX))))
            .toList();
    }

    /** The files here whose names end this way, in order. */
    private List<Path> filesEndingWith(String ending) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(ending))
                .sorted()
                .toList();
        }
    }

    /** A file's name without an ending it is known to have. */
    private static String without(Path file, String ending) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ending.length());
    }

    /**
     * Undoes an unfinished delete, or finishes one that had already removed
     * everything, and clears the staging folder.
     */
    private List<String> rollBackDeletes() throws IOException {
        Path staging = root.resolve(STAGING);
        if (!Files.isDirectory(staging)) return List.of();
        var undone = new LinkedHashSet<String>();
        try (var names = Files.list(staging)) {
            for (Path dir : names.filter(Files::isDirectory).sorted().toList()) {
                var copies = filesIn(dir);
                boolean everyOriginalGone = !copies.isEmpty();
                for (Path copy : copies) {
                    if (Files.exists(root.resolve(copy.getFileName().toString()))) {
                        everyOriginalGone = false;
                        break;
                    }
                }
                // The removal loop runs to the end or not at all: every original
                // gone means the delete had finished and only its copy was left.
                if (everyOriginalGone) continue;
                for (Path copy : copies) {
                    Path original = root.resolve(copy.getFileName().toString());
                    if (Files.exists(original)) continue;
                    if (!mayReturn(copy)) continue;
                    Files.copy(copy, original, StandardCopyOption.COPY_ATTRIBUTES);
                    force(original);
                    undone.add(dir.getFileName().toString());
                }
            }
        }
        // What went back is on the disk, names and all, before the only other
        // copy of it goes.
        if (!undone.isEmpty()) forceDir(root);
        deleteTree(staging);
        return List.copyOf(undone);
    }

    /**
     * Whether a staged copy may go back under the name it was taken from.
     *
     * Almost always it may: the copy is the undo of a removal, and the name is
     * free because that is what the removal did. An unlock key is the exception,
     * and the reason is the same one {@link #requireFree} refuses a name for. The
     * name a key answers to is a vault's, and if a different vault has taken that
     * name since — a delete that had finished, the name made free and used again
     * — putting the key back would list that vault as password-free and hand
     * every attempt a secret that does not open it, which is a vault nobody can
     * ever open (#41). So a key goes back only beside the vault it opens, and the
     * vault's own encryption is the proof, exactly as it is when an orphaned key
     * is given back to its owner. Anything else is left in the staging folder,
     * which is cleared with it.
     */
    private boolean mayReturn(Path copy) {
        if (!copy.getFileName().toString().endsWith(SUFFIX + LocalAccess.SUFFIX)) return true;
        Path vault = root.resolve(without(copy, LocalAccess.SUFFIX));
        if (!Files.isRegularFile(vault)) return true;
        return keyOpens(copy, vault);
    }

    /** The files directly in a folder, in order. */
    private static List<Path> filesIn(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    // ---- migration -----------------------------------------------------------

    /** What a migration did: the names now in managed storage, and what it had to leave. */
    public record Migration(List<String> adopted, List<String> left) {
        public boolean empty() { return adopted.isEmpty() && left.isEmpty(); }
    }

    /**
     * Adopts the vaults an older build left behind, without losing any of them.
     *
     * Idempotent by construction: the source is only removed once the copy in
     * managed storage is proven, so a run that is interrupted leaves the vault
     * where it was and the next run finishes the job. A name already in use is
     * never overwritten — two vaults with the same name are two vaults, so the
     * older one is filed under the next free name.
     *
     * @return the names adopted, and the files it could not move (left where they are)
     */
    public Migration migrate(Path legacyDir) throws IOException {
        var adopted = new ArrayList<String>();
        var left = new ArrayList<String>();
        if (legacyDir == null || !Files.isDirectory(legacyDir)) return new Migration(List.of(), List.of());
        try (var files = Files.list(legacyDir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (!file.getFileName().toString().endsWith(SUFFIX)) continue;
                try {
                    adopted.add(adopt(file));
                } catch (IOException | RuntimeException e) {
                    left.add(file.getFileName().toString());
                }
            }
        }
        return new Migration(List.copyOf(adopted), List.copyOf(left));
    }

    /**
     * Adopts one vault from elsewhere on this machine, by the same rules.
     *
     * The move is walked back if any part of it fails, so an adopted vault
     * never ends up separated from its unlock key or its backups — the one
     * shape of a partial migration that would leave a vault unopenable. The key
     * goes first for the same reason it does in a rename, and a kill between the
     * two moves is finished by the next run rather than left as a vault with no
     * key. A move between two disks copies and verifies before it removes the
     * original, so an interrupted adoption leaves a duplicate rather than a hole.
     */
    public String adopt(Path vaultFile) throws IOException {
        String base = validate(nameOf(vaultFile));
        if (!Files.isRegularFile(vaultFile)) throw new IOException("There is no vault at " + nameOf(vaultFile) + ".");
        if (vaultFile.toAbsolutePath().getParent() != null && vaultFile.toAbsolutePath().getParent().equals(root))
            return base;
        String name = freeName(base, vaultFile);
        Files.createDirectories(root);
        Path target = path(name);

        var moves = new ArrayList<Path[]>();
        try {
            Path key = LocalAccess.keyPath(vaultFile);
            Path keyTarget = LocalAccess.keyPath(target);
            // Skipped when the name already holds this vault's key: that is an
            // earlier adoption killed after the key had gone and before the vault
            // had, and all that is left to do is move the vault onto it. The copy
            // left in the old folder costs nothing.
            if (Files.isRegularFile(key) && !Files.isRegularFile(keyTarget)) {
                move(key, keyTarget);
                moves.add(new Path[] { key, keyTarget });
            }
            move(vaultFile, target);
            moves.add(new Path[] { vaultFile, target });
            for (Path backup : backupsBeside(vaultFile, base)) {
                Path backupTarget = root.resolve(name + SUFFIX + backup.getFileName().toString().substring((base + SUFFIX).length()));
                move(backup, backupTarget);
                moves.add(new Path[] { backup, backupTarget });
            }
        } catch (IOException | RuntimeException e) {
            walkBack(moves);
            throw new IOException("Adopting \"" + base + "\" failed, so it was left where it was. " + reason(e), e);
        }
        // An empty lock file; it is made again when the vault is next opened.
        try {
            Files.deleteIfExists(EncryptedVault.lockPath(vaultFile));
        } catch (IOException ignored) {
        }
        return name;
    }

    /** The encrypted backups an older vault kept beside it. */
    private static Path[] backupsBeside(Path vaultFile, String name) throws IOException {
        Path dir = vaultFile.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) return new Path[0];
        return backupsIn(dir, name + SUFFIX);
    }

    /** The name itself when it is free, otherwise the next one: nothing is overwritten. */
    private String freeName(String base, Path vaultFile) throws IOException {
        if (usable(base, vaultFile)) return base;
        for (int n = 2; n < 1000; n++) {
            String candidate = base + " " + n;
            if (usable(candidate, vaultFile)) return candidate;
        }
        throw new IllegalStateException("There is no free name left beside " + base + ".");
    }

    /**
     * Whether a vault may be adopted under this name: nothing vault-shaped is
     * there, and no unlock key is there that this vault does not open.
     *
     * A key that does open it is the one an adoption killed between its moves
     * left behind, and adopting onto it finishes that adoption. A key that does
     * not is a stranger's, and giving it to this vault would list a vault as
     * password-free that its own key cannot open, so the name is passed over.
     */
    private boolean usable(String name, Path vaultFile) {
        Path vault = root.resolve(name + SUFFIX);
        if (Files.exists(vault)) return false;
        if (!LocalAccess.enabled(vault)) return true;
        return keyOpens(LocalAccess.keyPath(vault), vaultFile);
    }

    /**
     * Moves a file, copying it first when the two places are on different disks.
     * A copy is verified and flushed before the original goes, so a move never
     * loses one — not to a failure, and not to a power cut that took a copy
     * still in the page cache. Overridable so a test can prove the walk-back
     * after a move fails.
     */
    void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                if (Files.mismatch(from, to) != -1)
                    throw new IOException("The copy of " + from.getFileName() + " did not match, so the original stays.");
                force(to);
                forceDir(to.toAbsolutePath().getParent());
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(to);
                throw failed;
            }
            Files.delete(from);
        }
    }

    private void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        } catch (IOException ignored) {
            // A leftover staging file costs nobody anything; the vault is what matters.
        }
        // The parent goes once it is empty, so no trace of a finished deletion is
        // left — but the vault folder itself is Yoru's, not part of a deletion, and
        // goes with the last vault in it and not with the last staging file.
        try {
            Path parent = dir.getParent();
            if (parent != null && !parent.equals(root) && Files.isDirectory(parent)) {
                try (var files = Files.list(parent)) {
                    if (files.findAny().isEmpty()) Files.delete(parent);
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ---- what deleting one would remove --------------------------------------

    /**
     * What a vault holds, in the words the delete confirmation uses.
     *
     * Counts only: the point is that the person about to delete a vault sees
     * exactly what goes — study, game progress, backups, reward ledger — and
     * not a vague "this workspace".
     */
    public record Contents(int activities, int sessions, int tasks, int habits, int rewards,
                           int pending, boolean gameSave, int backups) {

        public static Contents of(State state, int backups) {
            int pending = (int) state.rewards().stream().filter(r -> !r.delivered()).count();
            return new Contents(state.activities().size(), state.sessions().size(), state.tasks().size(),
                state.habits().size(), state.rewards().size(), pending, state.game() != null, backups);
        }

        /** What deleting this vault removes, one line each, with the numbers. */
        public List<String> lines() {
            var out = new ArrayList<String>();
            boolean study = sessions + tasks + habits + activities > 0;
            out.add("Study data — " + (study
                ? sessions + " recorded session" + (sessions == 1 ? "" : "s") + ", "
                    + tasks + " task" + (tasks == 1 ? "" : "s") + ", "
                    + habits + " tracker" + (habits == 1 ? "" : "s") + ", "
                    + activities + " activit" + (activities == 1 ? "y" : "ies")
                : "nothing recorded yet"));
            out.add("Game progress and saves — " + (gameSave
                ? "a game save kept in step with the game"
                : "no game save yet"));
            out.add("Backups — " + (backups == 0
                ? "none kept yet"
                : backups + " encrypted backup" + (backups == 1 ? "" : "s")));
            out.add("Reward ledger — " + (rewards == 0
                ? "no Pokémon earned yet"
                : rewards + " Pokémon earned" + (pending > 0 ? ", " + pending + " not yet delivered" : "")));
            return List.copyOf(out);
        }
    }

    /** What deleting this vault would remove, in those words. */
    public Contents contents(String name, State state) {
        return Contents.of(state, backups(name).length);
    }
}
