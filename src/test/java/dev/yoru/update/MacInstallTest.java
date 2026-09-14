package dev.yoru.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The Mac install path against a real disk image, built here from a stand-in app
 * holding only what staging reads. Nothing touches /Applications or opens an app:
 * the "installed" copy lives in a temporary folder and the swap never reopens.
 * The disk-image checks are skipped off macOS, where hdiutil does not exist; the
 * command checks (timeouts, output, cleanup) use only /bin/sh and run anywhere.
 */
public final class MacInstallTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static void run(List<String> command) throws IOException, InterruptedException {
        MacInstall.run(command, true, Path.of(System.getProperty("java.io.tmpdir")), Duration.ofMinutes(2));
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

    /** Waits for a child to write its pid, instead of guessing how long it takes to start. */
    private static long pid(Path file) throws Exception {
        for (long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos(); System.nanoTime() < deadline; Thread.sleep(20)) {
            if (Files.exists(file)) {
                String text = Files.readString(file).strip();
                if (!text.isEmpty()) return Long.parseLong(text);
            }
        }
        throw new AssertionError("the child never wrote " + file.getFileName());
    }

    /** A killed process can linger as a zombie until its new parent reaps it, so allow it a moment to go. */
    private static boolean gone(long pid) throws InterruptedException {
        for (long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos(); System.nanoTime() < deadline; Thread.sleep(20))
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) return true;
        return false;
    }

    private static boolean empty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) { return entries.findAny().isEmpty(); }
    }

    private static void commands() throws Exception {
        Path root = Files.createTempDirectory("yoru-commands"), logs = Files.createDirectories(root.resolve("logs"));
        Duration quick = Duration.ofSeconds(30);

        check("hello".equals(MacInstall.run(List.of("/bin/echo", "hello"), true, logs, quick)), "a command's output is returned");
        check("warned".equals(MacInstall.run(List.of("/bin/sh", "-c", "echo warned; exit 3"), false, logs, quick)),
            "an optional command that fails still returns what it said");
        try {
            MacInstall.run(List.of("/bin/sh", "-c", "echo disk is full >&2; exit 3"), true, logs, quick);
            check(false, "a required command that exits nonzero is refused");
        } catch (IOException expected) {
            check(expected.getMessage().equals("sh failed: disk is full"), "naming the command and its error output: " + expected.getMessage());
        }

        // Megabytes of output: more than any pipe buffer, kept only as its end.
        try {
            MacInstall.run(List.of("/bin/sh", "-c", "head -c 3000000 /dev/zero | tr '\\000' x; echo; echo the last line; exit 1"),
                true, logs, Duration.ofMinutes(1));
            check(false, "a failure after large output is refused");
        } catch (IOException expected) {
            check(expected.getMessage().endsWith("the last line"), "the error keeps the end of the output");
            check(expected.getMessage().length() < MacInstall.OUTPUT_LIMIT + 64, "and no more than a bounded amount of it: "
                + expected.getMessage().length());
        }

        // The defect in #20: output first, then a stall with the output still open. A
        // read-then-wait order blocks on that open output forever; the timeout must
        // start at once, and stop the command and what it started.
        Path shell = root.resolve("shell.pid"), child = root.resolve("child.pid");
        long started = System.nanoTime();
        try {
            MacInstall.run(List.of("/bin/sh", "-c", "echo $$ > '" + shell + "'; echo still working; sleep 600 & echo $! > '" + child + "'; wait"),
                true, logs, Duration.ofSeconds(2));
            check(false, "a command that stalls is stopped");
        } catch (IOException expected) {
            check(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 15, "promptly, not after the stall");
            check(expected.getMessage().equals("sh did not finish. It last said: still working"),
                "saying which command stalled and what it last said: " + expected.getMessage());
        }
        check(gone(pid(shell)), "the stalled command is no longer running");
        check(gone(pid(child)), "and neither is the process it started");

        // Interrupting the thread waiting on a command stops the command too.
        Path waiting = root.resolve("waiting.pid");
        var thrown = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                MacInstall.run(List.of("/bin/sh", "-c", "echo $$ > '" + waiting + "'; exec sleep 600"), true, logs, Duration.ofMinutes(5));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        long waitingPid = pid(waiting);
        worker.interrupt();
        worker.join(Duration.ofSeconds(15));
        check(!worker.isAlive(), "an interrupted wait returns promptly");
        check(thrown.get() instanceof InterruptedException, "as an interruption: " + thrown.get());
        check(gone(waitingPid), "and the command it was waiting on is stopped");

        check(empty(logs), "no command logs are left behind, whichever way a command ended");
        Updates.discard(root);
        check(!Files.exists(root), "a work folder is discarded with everything in it");
        Updates.discard(root);
        check(true, "and discarding one that is already gone is harmless");
    }

    public static void main(String[] args) throws Exception {
        commands();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
            System.out.println("PASS: " + checks + " update command checks (timeout, interruption, output, cleanup)");
            System.out.println("SKIP: Mac disk image checks need macOS and hdiutil");
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
        check(!Files.exists(other.resolve("Yoru.app")), "and the refused copy is removed, so it can never be swapped in");
        try (Stream<Path> left = Files.list(other)) {
            check(left.noneMatch(p -> p.getFileName().toString().endsWith(".log")), "nor are command logs left in the work folder");
        }

        Path installed = bundle(Files.createDirectories(root.resolve("Applications")).resolve("Yoru.app"), "1.0.4", "old");
        swap(work, staged, installed);
        check("new".equals(marker(installed)), "once Yoru has quit, the installed copy is the new one");
        check(!Files.exists(Path.of(installed + ".previous")), "the old copy is removed only after the new one is in");
        check(!Files.exists(staged), "and nothing staged is left behind");

        Path kept = bundle(root.resolve("Applications/Kept.app"), "1.0.4", "old");
        swap(work, work.resolve("Missing.app"), kept);
        check("old".equals(marker(kept)), "when the new copy cannot move in, the old one is put back");
        check(!Files.exists(Path.of(kept + ".previous")), "exactly where it was");

        System.out.println("PASS: " + checks + " Mac install checks (command timeout and cleanup, real disk image, version refusal, swap, rollback)");
    }
}
