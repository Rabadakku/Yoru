package dev.yoru.ui;
import dev.yoru.application.Repository;
import dev.yoru.domain.Model.State;
import dev.yoru.persistence.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;
import static dev.yoru.ui.Theme.*;

/**
 * Creating, opening, renaming, deleting and switching vaults (#41).
 *
 * Yoru keeps its vaults in a folder of its own choosing, so nothing here asks
 * anybody to find a file: a vault is a name in a list, and where it is stored
 * is Yoru's business. Vaults an older build left behind are adopted into that
 * folder before the list is first shown, without loss and without asking.
 *
 * The unlock secret of the vault that is open is kept for the session. Every
 * vault change is two-phase — a copy before a rename or a delete, a load
 * before a switch — and putting the vault back exactly as it was after a
 * failure means opening it again without the password; the app zeroes the
 * secret when it closes.
 */
final class VaultLauncher {

    private static final Preferences PREFERENCES = Preferences.userRoot().node("dev/yoru/desktop");
    /** The name of the vault last opened, which is about this machine, not the vault. */
    private static final String LAST = "vaultName";
    /** What older builds remembered instead: a path, which Yoru no longer keeps. */
    private static final String OLD_RECENT = "recentVault";

    private VaultLauncher() { }

    /** An open vault: the name it is filed under, the repository, and this session's secret. */
    record Opened(VaultStore store, String name, EncryptedVault vault, char[] secret) { }

    /**
     * The welcome flow: pick a vault, make one, manage them, or leave.
     *
     * Runs the migration first, so a person who has vaults from an older build
     * sees them in the list rather than being asked where they put them.
     *
     * @return the open vault, or null when the person chose to quit
     */
    static Opened open() {
        var store = VaultStore.managed();
        String notice = adoptOlder(store);
        String recovered = recover(store);
        if (recovered != null) notice = notice == null ? recovered : notice + " " + recovered;
        while (true) {
            List<String> names;
            try { names = store.names(); }
            catch (Exception e) { Dialogs.error(null, "Yoru could not read its vault folder", e.getMessage()); return null; }

            var welcome = card();
            welcome.add(Logo.lockup(34, CYAN)); gap(welcome, 14);
            welcome.add(label("Your vaults", 18, TEXT)); gap(welcome, 12);
            if (names.isEmpty()) {
                welcome.add(label("Yoru keeps your vaults in its own folder on this computer,", 13, TEXT));
                welcome.add(label("so there is no file to find and no save path to choose.", 13, TEXT));
            } else {
                welcome.add(label(names.size() + " vault" + (names.size() == 1 ? "" : "s")
                    + " on this computer, kept by Yoru.", 13, TEXT));
                gap(welcome, 8);
                welcome.add(label("Open, rename or delete one here. Nothing asks you for a file.", 12, MUTED));
            }
            gap(welcome, 12);
            welcome.add(label("Your data stays on this computer. No account required.", 12, MUTED));
            if (notice != null) { gap(welcome, 10); welcome.add(label(notice, 12, CYAN)); }
            notice = null;

            String last = lastUsed(names);
            String[] options = names.isEmpty()
                ? new String[] { "Create a vault", "Quit" }
                : new String[] { "Open " + last, "Another vault…", "New vault…", "Rename or delete…", "Quit" };
            int choice = Dialogs.choose(null, welcome, "Yoru", options);
            if (choice < 0 || choice == options.length - 1) return null;
            Opened opened = null;
            if (names.isEmpty()) opened = create(null, store);
            else switch (choice) {
                case 0 -> opened = unlock(null, store, last);
                case 1 -> { String pick = pick(null, store, last); if (pick != null) opened = unlock(null, store, pick); }
                case 2 -> opened = create(null, store);
                default -> manage(null, store);
            }
            if (opened != null) {
                remember(opened.name());
                return opened;
            }
        }
    }

    /**
     * Adopts the vaults older builds left behind: the old folder, and the one
     * path an older launcher remembered. Runs before anything else, is
     * idempotent, and never overwrites a name that is already in use.
     *
     * Only the one remembered path is reachable. A vault an older build was
     * pointed at through its Browse… chooser, and never opened last, is left
     * where the person put it: Yoru no longer asks anybody for a path, so it has
     * no way to find one, and looking for it would mean guessing. Whether that
     * deserves a way back is the owner's call, not this class's.
     */
    private static String adoptOlder(VaultStore store) {
        var adopted = new ArrayList<String>();
        var left = new ArrayList<String>();
        try {
            var migration = store.migrate(VaultStore.legacyRoot());
            adopted.addAll(migration.adopted());
            left.addAll(migration.left());
            String remembered = PREFERENCES.get(OLD_RECENT, "");
            if (!remembered.isBlank()) {
                Path old = Path.of(remembered);
                if (Files.isRegularFile(old) && !store.root().equals(old.toAbsolutePath().getParent()))
                    adopted.add(store.adopt(old));
                // Kept as a path for nobody: from here a vault is remembered by name.
                PREFERENCES.remove(OLD_RECENT);
            }
        } catch (Exception e) {
            return "Older vaults could not all be moved: " + e.getMessage();
        }
        if (adopted.isEmpty() && left.isEmpty()) return null;
        var parts = new ArrayList<String>();
        if (!adopted.isEmpty())
            parts.add(adopted.size() + " older vault" + (adopted.size() == 1 ? "" : "s") + " moved into Yoru's folder: "
                + String.join(", ", adopted) + ".");
        if (!left.isEmpty())
            parts.add(left.size() + " could not be moved and " + (left.size() == 1 ? "was" : "were")
                + " left exactly where " + (left.size() == 1 ? "it was" : "they were") + ": " + String.join(", ", left) + ".");
        return String.join(" ", parts);
    }

    /**
     * Puts right what a crash left half-done — a delete that never finished, a
     * rename killed between its key and its vault — and says so, because a vault
     * that came back is worth knowing about.
     *
     * Runs after the migration, so a vault an older build left behind is here —
     * with its key — before anything decides that a key belongs to no vault.
     */
    private static String recover(VaultStore store) {
        try {
            var recovery = store.reconcile();
            if (recovery.empty()) return null;
            var parts = new ArrayList<String>();
            if (!recovery.undeleted().isEmpty()) {
                parts.add("A deletion of " + quoted(recovery.undeleted()) + " was interrupted, so "
                    + (recovery.undeleted().size() == 1 ? "the vault was" : "the vaults were")
                    + " put back exactly as " + (recovery.undeleted().size() == 1 ? "it was" : "they were") + ".");
            }
            if (!recovery.rekeyed().isEmpty()) {
                parts.add("The unlock key of " + quoted(recovery.rekeyed()) + " was found away from its vault and put back.");
            }
            return String.join(" ", parts);
        } catch (Exception e) {
            return "What Yoru was doing when it last stopped could not all be put right: " + e.getMessage();
        }
    }

    /** Vault names as the notice above reads them. */
    private static String quoted(List<String> names) {
        return names.size() == 1 ? "\"" + names.getFirst() + "\""
            : names.stream().map(name -> "\"" + name + "\"").collect(java.util.stream.Collectors.joining(", "));
    }

    /** Which vault to open, by name. */
    private static String pick(Component parent, VaultStore store, String skip) {
        List<String> names;
        try { names = new ArrayList<>(store.names()); }
        catch (Exception e) { Dialogs.error(parent, "Yoru could not read its vault folder", e.getMessage()); return null; }
        names.remove(skip);
        if (names.isEmpty()) {
            Dialogs.info(parent, "There is no other vault yet. Make one with New vault.");
            return null;
        }
        return Dialogs.select(parent, "Which vault?", "Choose a vault", names);
    }

    /** Asks which vault to move to, by name, and opens it. */
    static Opened choose(Component parent, VaultStore store, String current) {
        String pick = pick(parent, store, current);
        return pick == null ? null : unlock(parent, store, pick);
    }

    /**
     * Opens a vault, asking for its password only when it has one.
     *
     * The secret comes back with the vault because the session may have to open
     * it again — after a rename, or after a deletion that failed and put the
     * vault back — and asking for the password a second time to undo something
     * the person did not ask for would be the wrong way round.
     */
    static Opened unlock(Component parent, VaultStore store, String name) {
        boolean local;
        try {
            local = store.passwordless(name);
        } catch (IllegalArgumentException e) {
            // A name Yoru cannot use reaches here only from outside the app — a
            // file copied into the folder. It is a report, not a crash on the
            // event thread, and the names the store lists never get this far (#41).
            Dialogs.error(parent, "That vault cannot be opened",
                "\"" + name + "\" is not a name Yoru can use. " + e.getMessage());
            return null;
        }
        if (local) {
            char[] secret = null;
            try {
                secret = store.secretOf(name);
                return new Opened(store, name, store.open(name, secret.clone()), secret);
            } catch (Exception e) {
                if (secret != null) Arrays.fill(secret, '\0');
                Dialogs.error(parent, "That vault could not be opened", e.getMessage());
                return null;
            }
        }
        var pass = new JPasswordField(24);
        var form = stack();
        form.add(label(name, 20, CYAN)); gap(form, 12);
        form.add(label("Password · 12 or more characters", 12, TEXT)); form.add(pass);
        while (true) {
            if (!Dialogs.confirm(parent, form, "Unlock vault", "Unlock")) return null;
            char[] secret = pass.getPassword();
            if (secret.length < 12) { Arrays.fill(secret, '\0'); Dialogs.error(parent, "That password is too short."); continue; }
            try {
                var vault = store.open(name, secret.clone());
                pass.setText("");
                return new Opened(store, name, vault, secret);
            } catch (Exception e) {
                Arrays.fill(secret, '\0');
                Dialogs.error(parent, "\"" + name + "\" would not open",
                    "That password did not open this vault. Nothing was changed.");
            }
        }
    }

    /**
     * Makes a new vault: a name, and a password only if the person wants one.
     * Yoru picks the location — there is nothing here to browse for.
     */
    static Opened create(Component parent, VaultStore store) {
        String name = Dialogs.input(parent, "Name this vault — 1 to 60 letters, numbers, spaces, underscores or hyphens.",
            "New vault");
        if (name == null) return null;
        try { name = VaultStore.validate(name); }
        catch (IllegalArgumentException e) { Dialogs.error(parent, e.getMessage()); return null; }
        if (store.exists(name)) {
            if (!Dialogs.confirm(parent, "A vault called \"" + name + "\" already exists.\n\nOpen that one instead?",
                "Vault already exists", "Open it")) return null;
            return unlock(parent, store, name);
        }

        var pass = new JPasswordField(24);
        var repeat = new JPasswordField(24);
        // Two explicit choices rather than a box one has to notice is off: the
        // vault made with one keystroke is the password-free one, and "no
        // password" is spelled out as a choice instead of being the box's
        // unchecked meaning.
        var noPassword = new JRadioButton("No password", true);
        var protect = new JRadioButton("Require a password", false);
        noPassword.setOpaque(false);
        protect.setOpaque(false);
        noPassword.setForeground(TEXT);
        protect.setForeground(TEXT);
        var group = new ButtonGroup();
        group.add(noPassword);
        group.add(protect);
        var form = stack();
        form.add(label(name, 20, CYAN)); gap(form, 12);
        form.add(noPassword);
        form.add(protect);
        form.add(label("Without a password, anyone who can read your files can open this vault.", 11, MUTED));
        form.add(label("Yoru keeps the unlock key beside it, and keeps both together.", 11, MUTED));
        gap(form, 12);
        form.add(label("Password · 12 or more characters", 12, TEXT)); form.add(pass);
        form.add(label("Repeat password", 12, TEXT)); form.add(repeat);
        // Emptied as well as disabled, and focused the moment it is asked for: a
        // password typed before the choice was changed is not the password of the
        // vault that gets made, and leaving it on screen says that it is.
        Runnable wanted = () -> {
            boolean on = protect.isSelected();
            pass.setEnabled(on);
            repeat.setEnabled(on);
            if (!on) { pass.setText(""); repeat.setText(""); }
            else pass.requestFocusInWindow();
        };
        noPassword.addActionListener(e -> wanted.run());
        protect.addActionListener(e -> wanted.run());
        wanted.run();
        while (true) {
            if (!Dialogs.confirm(parent, form, "New vault", "Create")) return null;
            boolean local = !protect.isSelected();
            char[] secret = pass.getPassword();
            char[] again = repeat.getPassword();
            boolean valid = local || (secret.length >= 12 && Arrays.equals(secret, again));
            Arrays.fill(again, '\0');
            if (!valid) {
                Arrays.fill(secret, '\0');
                Dialogs.error(parent, "That password cannot be used",
                    "Use at least 12 characters, and make both password fields match.\n\n"
                    + "A password is optional. Choose \"No password\" to make a vault that "
                    + "opens without one.");
                continue;
            }
            try {
                EncryptedVault vault;
                if (local) {
                    Arrays.fill(secret, '\0');
                    vault = store.createPasswordless(name);
                    secret = store.secretOf(name);
                } else {
                    vault = store.create(name, secret.clone());
                }
                pass.setText("");
                repeat.setText("");
                return new Opened(store, name, vault, secret);
            } catch (Exception e) {
                Arrays.fill(secret, '\0');
                Dialogs.error(parent, "That vault could not be made", e.getMessage());
                return null;
            }
        }
    }

    /** Renames or deletes a vault that is not open, from the welcome screen. */
    private static void manage(Component parent, VaultStore store) {
        String name = pick(parent, store, null);
        if (name == null) return;
        boolean hasPassword = !store.passwordless(name);
        String[] actions = hasPassword
            ? new String[] { "Rename", "Remove password", "Delete", "Cancel" }
            : new String[] { "Rename", "Delete", "Cancel" };
        int action = Dialogs.choose(parent, "What would you like to do with \"" + name + "\"?", name, actions);
        if (action < 0) return;
        switch (actions[action]) {
            case "Rename" -> rename(parent, store, name);
            case "Remove password" -> removePassword(parent, store, name);
            case "Delete" -> delete(parent, store, name);
            default -> { }
        }
    }

    /** Renames a closed vault. */
    static void rename(Component parent, VaultStore store, String name) {
        String next = Dialogs.input(parent, "Rename \"" + name + "\".", "Rename vault", name);
        if (next == null || next.strip().equals(name)) return;
        try {
            store.rename(name, next);
            Dialogs.info(parent, "\"" + name + "\" is now \"" + VaultStore.validate(next) + "\".");
        } catch (Exception e) {
            Dialogs.error(parent, "The vault was not renamed", e.getMessage());
        }
    }

    /**
     * Deletes a vault, showing exactly what goes before it does.
     *
     * The vault is opened first — you cannot delete what you cannot open — so
     * the counts in the confirmation are the vault's own, not a guess.
     */
    static void delete(Component parent, VaultStore store, String name) {
        var opened = unlock(parent, store, name);
        if (opened == null) return;
        State state;
        try { state = opened.vault().load(); }
        catch (Exception e) {
            close(opened.vault());
            Dialogs.error(parent, "That vault could not be read", e.getMessage());
            return;
        }
        if (!confirmDelete(parent, name, store.contents(name, state))) {
            close(opened.vault());
            return;
        }
        close(opened.vault());
        try {
            store.delete(name);
            Dialogs.info(parent, "\"" + name + "\" is deleted, with everything that was in it.");
        } catch (Exception e) {
            // The store puts the vault back before it throws: this is a report, not a loss.
            Dialogs.error(parent, "Nothing was lost", e.getMessage());
        }
    }

    /**
     * Takes the password off a vault, so that it opens without asking (#41).
     *
     * The password is asked for first and the vault opened with it: a vault
     * nobody can open is not one whose password anybody may take off, and the
     * unlock the store needs is the same unlock that proves the right to do it.
     *
     * @return the vault's new unlock secret when it was taken off, or null
     */
    static char[] removePassword(Component parent, VaultStore store, String name) {
        if (store.passwordless(name)) {
            Dialogs.info(parent, "\"" + name + "\" already opens without a password.");
            return null;
        }
        if (!confirmRemovePassword(parent, name)) return null;
        var opened = unlock(parent, store, name);
        if (opened == null) return null;
        try {
            char[] secret = store.removePassword(name, opened.vault(), opened.vault().load());
            Dialogs.info(parent, "\"" + name + "\" now opens without a password.\n\n"
                + "Its unlock key is kept beside it, so anyone who can read your files can open it.");
            return secret;
        } catch (Exception e) {
            Dialogs.error(parent, "The password was not removed",
                e.getMessage() + "\n\n\"" + name + "\" still opens with the password it had.");
            return null;
        } finally {
            close(opened.vault());
            Arrays.fill(opened.secret(), '\0');
        }
    }

    /**
     * What taking a password off costs, said before it is taken off.
     *
     * Not reversible by this screen — there is no way back to a password from
     * here — and it turns a vault that needed something somebody knew into one
     * that needs only the files, so it is asked the way deletions are asked.
     */
    static boolean confirmRemovePassword(Component parent, String name) {
        var message = stack();
        message.add(label("\"" + name + "\" will open without a password.", 14, TEXT));
        gap(message, 10);
        message.add(label("·  Yoru keeps the unlock key in a file beside the vault.", 12, MUTED));
        message.add(label("·  Anyone who can read your files can then open it.", 12, MUTED));
        message.add(label("·  Everything in the vault is kept, and stays encrypted on disk.", 12, MUTED));
        gap(message, 12);
        message.add(label("Yoru cannot put the password back from this screen.", 11, MUTED));
        return Dialogs.confirmDestructive(parent, message, "Remove the password from \"" + name + "\"",
            "Remove it");
    }

    /**
     * The confirmation every deletion goes through: what is in the vault, named
     * one line at a time, and the fact that this cannot be undone.
     */
    static boolean confirmDelete(Component parent, String name, VaultStore.Contents contents) {
        var message = stack();
        message.add(label("Everything in \"" + name + "\" goes:", 14, TEXT));
        gap(message, 10);
        for (String line : contents.lines()) message.add(label("·  " + line, 12, MUTED));
        gap(message, 12);
        message.add(label("The vault file itself goes too. Yoru takes a verified copy first and puts", 11, MUTED));
        message.add(label("it back if any part of the deletion fails — a failure costs nothing.", 11, MUTED));
        return Dialogs.confirmDestructive(parent, message, "Delete \"" + name + "\"", "Delete it");
    }

    private static void close(Repository vault) {
        try { vault.close(); } catch (Exception ignored) { }
    }

    private static String lastUsed(List<String> names) {
        String remembered = PREFERENCES.get(LAST, "");
        if (names.contains(remembered)) return remembered;
        return names.isEmpty() ? "" : names.getFirst();
    }

    static void remember(String name) {
        try { PREFERENCES.put(LAST, name); } catch (RuntimeException ignored) { }
    }
}
