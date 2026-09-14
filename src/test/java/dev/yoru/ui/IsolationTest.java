package dev.yoru.ui;

import dev.yoru.assets.ArtworkLibrary;
import dev.yoru.assets.MusicLibrary;
import dev.yoru.persistence.VaultStore;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Every test JVM runs with a home and preferences of its own (#10).
 *
 * test.sh gives each JVM an empty temporary user.home and in-memory preferences,
 * so no test can read or change a real vault, preference, game file or artwork
 * library. This fails if that wrapper is bypassed or stops working, by asking
 * production code where it would read and write rather than trusting the
 * property: the vault folder, the artwork and music libraries and the game's
 * work folder must all resolve inside the test's home.
 *
 * It runs first, so a broken wrapper stops the suite before any other test can
 * touch the machine.
 */
public final class IsolationTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String real = System.getenv("HOME");
        check(real == null || !home.equals(Path.of(real).toAbsolutePath().normalize()),
            "tests must not run in the real home; run them through ./test.sh, which gives each JVM its own");

        for (Path place : List.of(VaultStore.legacyRoot(), ArtworkLibrary.root(), MusicLibrary.root(), GameFiles.workDirectory()))
            check(place.toAbsolutePath().normalize().startsWith(home), "production code reads and writes inside the test home: " + place);

        check(TestPreferencesFactory.class.getName().equals(System.getProperty("java.util.prefs.PreferencesFactory")),
            "preferences come from the in-memory factory");
        var node = Preferences.userRoot().node("dev/yoru/isolation-check");
        node.put("written", "by a test");
        check(Preferences.userRoot().getClass().getEnclosingClass() == TestPreferencesFactory.class,
            "and a preference a test writes lands in memory, not in the platform's store");
        node.removeNode();

        System.out.println("PASS: " + checks + " isolation checks (own home, production paths inside it, in-memory preferences)");
    }
}
