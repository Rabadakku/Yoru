package dev.yoru.ui;

import dev.yoru.anki.AnkiConnect;
import dev.yoru.anki.AnkiConnect.Failure;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.prefs.Preferences;
import javax.swing.*;

/**
 * Anki's section in Settings (#85): what Test connection says for each way a
 * read can fail, and that the key lives in the vault and nowhere else.
 *
 * The source is a fake, so every failure can be made on demand; the key is an
 * invented one, looked for afterwards in this computer's preferences, in what
 * the app printed, and in the bytes of an encrypted vault that holds it.
 */
public final class AnkiSettingsTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final String KEY = "synthetic-anki-key-4f1c9e";

    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (var child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) { var found = named(nested, name, type); if (found != null) return found; }
        }
        return null;
    }

    private static JButton titled(Container root, String text) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) return b;
            if (child instanceof Container nested) { var found = titled(nested, text); if (found != null) return found; }
        }
        return null;
    }

    /** Presses Test connection with {@code source} behind it and returns what the section says. */
    private static String test(YoruApp app, AnkiCard.Source source) throws Exception {
        var card = new AtomicReference<JPanel>();
        SwingUtilities.invokeAndWait(() -> {
            card.set(AnkiSettings.card(app, source));
            named(card.get(), "anki.key", JPasswordField.class).setText(KEY);
            named(card.get(), "anki.test", JButton.class).doClick();
        });
        var said = new AtomicReference<String>();
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> said.set(named(card.get(), "anki.result", JLabel.class).getText()));
            if (!said.get().contains("Asking Anki")) return said.get();
            Thread.sleep(10);
        }
        throw new AssertionError("Test connection never answered");
    }

    private static void everyValue(Preferences node, List<String> into) throws Exception {
        for (var key : node.keys()) { into.add(key); into.add(node.get(key, "")); }
        for (var child : node.childrenNames()) everyValue(node.node(child), into);
    }

    public static void main(String[] args) throws Exception {
        var printed = new ByteArrayOutputStream();
        var out = System.out;
        var err = System.err;
        var tee = new PrintStream(printed, true, StandardCharsets.UTF_8);
        System.setOut(tee);
        System.setErr(tee);
        try {
            run();
        } catch (Throwable failed) {
            System.setOut(out);
            System.setErr(err);
            failed.printStackTrace();
            System.exit(1);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        check(!printed.toString(StandardCharsets.UTF_8).contains(KEY), "the key is never printed or logged");
        System.out.println("PASS: " + checks + " Anki settings checks (every failure said its own way, the key only in the vault)");
        // The window's timers would keep the event thread, and so the JVM, alive.
        System.exit(0);
    }

    private static void run() throws Exception {
        var app = new AtomicReference<YoruApp>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.apply(ThemeId.MIDNIGHT);
                app.set(Preview.trackerApp(ThemeId.MIDNIGHT, 1280, 900));
                app.get().tracker().anki(app.get().tracker().state().anki().enabled(true));
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        // Each way a read can fail says something of its own.
        var said = new LinkedHashMap<String, String>();
        for (var kind : Failure.Kind.values())
            said.put(kind.name(), test(app.get(), (key, days) -> { throw new Failure(kind, "internal wording"); }));
        said.put("something else", test(app.get(), (key, days) -> { throw new IOException("boom"); }));
        check(new HashSet<>(said.values()).size() == said.size(), "every failure says something different: " + said);
        for (var entry : said.entrySet())
            check(!entry.getValue().contains("internal wording") && !entry.getValue().contains("boom"),
                entry.getKey() + " is explained in Settings' own words, not the transport's");
        check(said.get("NOT_ANSWERING").contains("add-on"), "Anki not answering points at Anki and its add-on");
        check(said.get("KEY_REJECTED").contains("key"), "a refused key says it was the key");
        check(said.get("NO_PROFILE").contains("profile"), "no open profile says so");
        for (var entry : said.entrySet())
            check(!entry.getValue().contains(KEY), entry.getKey() + " never repeats the key");

        // A working connection names the profile, and was asked with the key as typed.
        var asked = new AtomicReference<String>();
        var connected = test(app.get(), (key, days) -> {
            asked.set(key);
            return new AnkiConnect.Snapshot("Synthetic", 12, new TreeMap<>(), Instant.parse("2026-09-23T10:00:00Z"));
        });
        check(connected.contains("Synthetic") && connected.contains("12 reviews"), "a working test names the profile: " + connected);
        check(KEY.equals(asked.get()), "the test asks with the key in the field, saved or not");

        // Saving puts the key in the vault's state, and only there.
        SwingUtilities.invokeAndWait(() -> {
            var card = AnkiSettings.card(app.get(), (key, days) -> { throw new IOException("unused"); });
            named(card, "anki.key", JPasswordField.class).setText(KEY);
            titled(card, "Save key").doClick();
        });
        check(KEY.equals(app.get().tracker().state().anki().key()), "Save key keeps the key in the vault");
        var stored = new ArrayList<String>();
        everyValue(Preferences.userRoot(), stored);
        everyValue(Preferences.systemRoot(), stored);
        check(stored.stream().noneMatch(v -> v.contains(KEY)), "the key is in none of this computer's preferences");

        // And the vault it is kept in is encrypted: the key is not in its bytes.
        var folder = Files.createTempDirectory("yoru-anki-key");
        try {
            var file = folder.resolve("synthetic.yoru");
            var vault = new EncryptedVault(file, "correct horse battery staple".toCharArray());
            vault.save(app.get().tracker().state());
            vault.close();
            byte[] bytes = Files.readAllBytes(file);
            check(!new String(bytes, StandardCharsets.ISO_8859_1).contains(KEY), "the vault on disk does not show the key");
            var reopened = new EncryptedVault(file, "correct horse battery staple".toCharArray());
            check(KEY.equals(reopened.load().anki().key()), "and it is there when the vault is opened again");
            reopened.close();
        } finally {
            try (var files = Files.walk(folder)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
