package dev.yoru;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps personal information out of the repository.
 *
 * Yoru is public, and anything committed stays in its history even after the
 * file is removed. So this runs with the rest of the suite and fails the build,
 * rather than trusting every contributor — person or agent — to remember.
 * Only tracked content is checked, via {@code git ls-files}: untracked files
 * never leave the machine.
 *
 * The generic checks run everywhere: email addresses, home-directory paths,
 * personal file types and images outside the release media folder. The owner's
 * own identifiers cannot be written here without publishing them, so they live
 * in {@code .privacy-denylist} — gitignored, one term per line — and are
 * checked whenever that file exists.
 */
public final class PrivacyTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    static final Path DENYLIST = Path.of(".privacy-denylist");
    static final String MEDIA = "docs/media/";

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    /** Addresses that identify nobody: the commit trailer, and documentation examples. */
    static final Pattern HARMLESS_EMAIL = Pattern.compile("noreply@anthropic\\.com|[A-Za-z0-9._%+-]+@example\\.(?:com|org|net)");
    static final Pattern HOME = Pattern.compile("(?:/Users/|/home/|[A-Za-z]:\\\\Users\\\\)([A-Za-z0-9._-]+)");
    /** Account names a document may use as a placeholder rather than a real account. */
    static final Set<String> PLACEHOLDERS = Set.of("you", "yourname", "your-name", "username", "user", "me",
        "example", "shared", "runner", "name");
    /** Files that are personal by nature: saves, vaults, ROMs, BIOS images, exports, keys and screenshots. */
    static final Pattern PERSONAL_FILE = Pattern.compile(
        "(?i)(?:^|/)(?:screenshot[^/]*|[^/]*bios[^/]*\\.bin|[^/]+\\.(?:srm|sav|sa1|state|ss\\d|gba|gbc|gb|nds|vault|local-key|key|zip|bak))$");
    static final Pattern IMAGE = Pattern.compile("(?i)\\.(?:png|jpe?g|gif|webp|bmp|heic|mov|mp4)$");

    /** Every problem in one tracked file's name and content. */
    static List<String> problems(String name, String text, List<String> denied) {
        var out = new ArrayList<String>();
        if (PERSONAL_FILE.matcher(name).find()) out.add(name + ": a personal file type is tracked");
        if (IMAGE.matcher(name).find() && !name.startsWith(MEDIA))
            out.add(name + ": images belong in " + MEDIA + ", and only release media belongs there");
        if (text == null) return out;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var email = EMAIL.matcher(lines[i]);
            while (email.find())
                if (!HARMLESS_EMAIL.matcher(email.group()).matches()) out.add(name + ":" + (i + 1) + ": an email address");
            var home = HOME.matcher(lines[i]);
            while (home.find())
                if (!PLACEHOLDERS.contains(home.group(1).toLowerCase(Locale.ROOT)))
                    out.add(name + ":" + (i + 1) + ": a home-directory path");
            String lower = lines[i].toLowerCase(Locale.ROOT);
            // The term itself is never printed: CI logs are public, and it is the
            // very thing being kept out of view.
            for (String term : denied)
                if (lower.contains(term)) out.add(name + ":" + (i + 1) + ": a term from .privacy-denylist");
        }
        return out;
    }

    /**
     * The checks themselves, against invented data assembled at run time.
     *
     * Assembled so that this file never contains a real-looking address or path
     * of its own. A guard that silently stopped matching would pass forever.
     */
    private static void theChecksCatchWhatTheyShould() {
        String at = "@";
        check(!problems("a.md", "write to someone" + at + "mail.test today", List.of()).isEmpty(), "an email address is caught");
        check(problems("a.md", "Co-Authored-By: Claude <noreply" + at + "anthropic.com>", List.of()).isEmpty(),
            "the commit trailer's address is allowed");
        check(problems("a.md", "e.g. you" + at + "example.com", List.of()).isEmpty(), "and documentation examples");
        check(!problems("a.md", "open /" + "Users/somebody/Desktop", List.of()).isEmpty(), "a home path is caught");
        check(!problems("a.md", "C:\\" + "Users\\somebody\\save.srm", List.of()).isEmpty(), "a Windows home path is caught");
        check(problems("a.md", "like /" + "Users/you/Documents", List.of()).isEmpty(), "a placeholder account is allowed");
        check(!problems("notes.md", "met with dr. placeholder", List.of("dr. placeholder")).isEmpty(), "a denylisted term is caught");
        check(!problems("emerald.srm", null, List.of()).isEmpty(), "a save file is caught by its name");
        check(!problems("Screenshot 2026-01-01.png", null, List.of()).isEmpty(), "a screenshot is caught by its name");
        check(!problems("docs/notes/board.png", null, List.of()).isEmpty(), "an image outside the media folder is caught");
        check(problems(MEDIA + "today.png", null, List.of()).isEmpty(), "release media is allowed");
        check(!problems("src/main/resources/dev/yoru/waifu/portrait.png", null, List.of()).isEmpty(),
            "the app bundles no images of its own any more, so one under src is caught like any other");
        check(!problems("src/main/resources/dev/yoru/game/sprite.png", null, List.of()).isEmpty(), "an image elsewhere is still caught");
        check(problems("src/Main.java", "int x = 1;", List.of()).isEmpty(), "ordinary code passes");
    }

    static List<String> denylist() throws IOException {
        if (!Files.isRegularFile(DENYLIST)) return List.of();
        return Files.readAllLines(DENYLIST).stream().map(s -> s.strip().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
    }

    /** Tracked paths, or null when this is not a git checkout. */
    static List<String> tracked() {
        try {
            var process = new ProcessBuilder("git", "ls-files", "-z").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) return null;
            return List.of(out.split("\0")).stream().filter(s -> !s.isEmpty()).toList();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static boolean binary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8000); i++) if (bytes[i] == 0) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        theChecksCatchWhatTheyShould();
        var files = tracked();
        if (files == null) { System.out.println("PASS: privacy self-checks only (not a git checkout)"); return; }
        var denied = denylist();
        var found = new ArrayList<String>();
        int scanned = 0;
        for (String name : files) {
            Path path = Path.of(name);
            String text = null;
            if (Files.isRegularFile(path)) {
                byte[] bytes = Files.readAllBytes(path);
                if (!binary(bytes)) { text = new String(bytes, StandardCharsets.UTF_8); scanned++; }
            }
            found.addAll(problems(name, text, denied));
        }
        if (!found.isEmpty()) {
            found.forEach(System.err::println);
            throw new AssertionError(found.size() + " privacy problem(s) in tracked files. Remove the data; do not weaken this test.");
        }
        System.out.println("PASS: " + checks + " privacy self-checks, " + scanned + " tracked files clean"
            + (denied.isEmpty() ? "" : ", including " + denied.size() + " local denylist terms"));
    }
}
