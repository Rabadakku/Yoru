package dev.yoru.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The Mac install path against a real disk image, built here from a stand-in app
 * holding only what staging reads. Nothing touches /Applications or opens an app:
 * the "installed" copy lives in a temporary folder and the swap never reopens.
 * Skipped off macOS, where hdiutil does not exist.
 */
public final class MacInstallTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static void run(List<String> command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0)
            throw new IOException(String.join(" ", command) + " failed: " + output);
    }

    private static Path bundle(Path at, String version, String marker) throws IOException {
        Path contents = Files.createDirectories(at.resolve("Contents"));
        Files.writeString(contents.resolve("Info.plist"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
            <key>CFBundleIdentifier</key><string>dev.yoru</string>
            <key>CFBundleShortVersionString</key><string>%s</string>
            </dict></plist>
            """.formatted(version));
        Files.writeString(contents.resolve("marker.txt"), marker);
        return at;
    }

    private static String marker(Path app) throws IOException {
        return Files.readString(app.resolve("Contents/marker.txt"));
    }

    private static void swap(Path work, Path staged, Path installed) throws Exception {
        var gone = new ProcessBuilder("/usr/bin/true").start();
        gone.waitFor();
        Path script = work.resolve("finish-" + System.nanoTime() + ".sh");
        Files.writeString(script, MacInstall.swapScript(gone.pid(), staged, installed, false));
        run(List.of("/bin/sh", script.toString()));
    }

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
            System.out.println("SKIP: Mac install checks need macOS and hdiutil");
            return;
        }
        Path root = Files.createTempDirectory("yoru-mac-install");
        bundle(root.resolve("image/Yoru.app"), "9.9.9", "new");
        Path dmg = root.resolve("Yoru-9.9.9.dmg");
        run(List.of("/usr/bin/hdiutil", "create", "-quiet", "-volname", "Yoru", "-srcfolder", root.resolve("image").toString(),
            "-ov", "-format", "UDZO", dmg.toString()));

        Path work = Files.createDirectories(root.resolve("work it's"));
        Path staged = MacInstall.stage(dmg, work, Version.parse("9.9.9"));
        check(staged.equals(work.resolve("Yoru.app")) && "new".equals(marker(staged)), "the app is copied out of the image");
        check(!Files.exists(work.resolve("mount/Yoru.app")), "and the image is detached again");

        Path other = Files.createDirectories(root.resolve("work-wrong"));
        try {
            MacInstall.stage(dmg, other, Version.parse("9.9.8"));
            check(false, "an app at another version is refused");
        } catch (IOException expected) {
            check(expected.getMessage().contains("9.9.9"), "and the refusal names the version found: " + expected.getMessage());
        }
        check(!Files.exists(other.resolve("mount/Yoru.app")), "the image is detached after a refusal too");

        Path installed = bundle(Files.createDirectories(root.resolve("Applications")).resolve("Yoru.app"), "1.0.4", "old");
        swap(work, staged, installed);
        check("new".equals(marker(installed)), "once Yoru has quit, the installed copy is the new one");
        check(!Files.exists(Path.of(installed + ".previous")), "the old copy is removed only after the new one is in");
        check(!Files.exists(staged), "and nothing staged is left behind");

        Path kept = bundle(root.resolve("Applications/Kept.app"), "1.0.4", "old");
        swap(work, work.resolve("Missing.app"), kept);
        check("old".equals(marker(kept)), "when the new copy cannot move in, the old one is put back");
        check(!Files.exists(Path.of(kept + ".previous")), "exactly where it was");

        System.out.println("PASS: " + checks + " Mac install checks (real disk image, version refusal, swap, rollback)");
    }
}
