package dev.yoru.update;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Installing an update over a macOS app bundle.
 *
 * A running bundle cannot replace itself, so the new Yoru.app is copied out of
 * the verified disk image while this copy keeps running, and a small shell
 * script waits for this process to end before swapping the bundles and opening
 * the new one. The old bundle is moved aside, not deleted, until the new one is
 * in place, and moved back if it cannot be.
 */
public final class MacInstall {
    private MacInstall() { }

    /** How long one staging command may run before it is stopped. */
    static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    /** The most of a command's output an error message keeps: its end, where failures explain themselves. */
    static final int OUTPUT_LIMIT = 16 * 1024;

    /** The .app bundle a launcher runs from, or null when it is not inside one. */
    public static Path bundleOf(Path launcher) {
        for (Path p = launcher; p != null; p = p.getParent())
            if (p.getFileName() != null && p.getFileName().toString().endsWith(".app")) return p;
        return null;
    }

    /** This copy's bundle: jpackage's app path when it gives one, else the process's own executable. */
    public static Path runningBundle() {
        String given = System.getProperty("jpackage.app-path");
        if (given != null) return bundleOf(Path.of(given));
        return ProcessHandle.current().info().command().map(command -> bundleOf(Path.of(command))).orElse(null);
    }

    /**
     * Copies the app out of a verified disk image into {@code work} and checks it
     * is Yoru at the version expected. The image is detached whatever happens, and
     * a copy that fails any step is removed, so nothing half-staged can be swapped in.
     */
    public static Path stage(Path dmg, Path work, Version expected) throws IOException, InterruptedException {
        Path mount = Files.createDirectories(work.resolve("mount"));
        run(List.of("/usr/bin/hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen",
            "-mountpoint", mount.toString(), dmg.toString()), true, work, COMMAND_TIMEOUT);
        Path staged = null;
        try {
            Path app;
            try (Stream<Path> entries = Files.list(mount)) {
                app = entries.filter(p -> p.getFileName().toString().endsWith(".app")).findFirst()
                    .orElseThrow(() -> new IOException("The update's disk image holds no app."));
            }
            staged = work.resolve(app.getFileName().toString());
            run(List.of("/bin/rm", "-rf", staged.toString()), true, work, COMMAND_TIMEOUT);
            run(List.of("/usr/bin/ditto", app.toString(), staged.toString()), true, work, COMMAND_TIMEOUT);
            String id = plist(work, staged, "CFBundleIdentifier"), version = plist(work, staged, "CFBundleShortVersionString");
            if (!"dev.yoru".equals(id)) throw new IOException("The update's app is not Yoru.");
            if (!expected.toString().equals(version))
                throw new IOException("The update's app is version " + version + ", not " + expected + ".");
            return staged;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (staged != null) Updates.discard(staged);
            throw e;
        } finally {
            detach(mount, work);
        }
    }

    /**
     * The script that finishes an update. {@code reopen} is false only in tests,
     * which must not open an app on the machine running them.
     */
    public static String swapScript(long pid, Path staged, Path installed, boolean reopen) {
        return "#!/bin/sh\n"
            + "# Written by Yoru to finish an update once the running copy has quit.\n"
            + "staged=" + quote(staged) + "\n"
            + "installed=" + quote(installed) + "\n"
            + "previous=\"$installed.previous\"\n"
            + "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done\n"
            + "rm -rf \"$previous\"\n"
            + "mv \"$installed\" \"$previous\" || { " + (reopen ? "open \"$installed\"; " : "") + "exit 1; }\n"
            + "if mv \"$staged\" \"$installed\"; then\n"
            + "  rm -rf \"$previous\"\n"
            + "else\n"
            + "  mv \"$previous\" \"$installed\"\n"
            + "fi\n"
            + (reopen ? "open \"$installed\"\n" : "");
    }

    /** Writes the script into {@code work} and starts it, detached from this process's input and output. */
    public static void launch(String script, Path work) throws IOException {
        Path file = work.resolve("finish-update.sh");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        new ProcessBuilder("/bin/sh", file.toString())
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
            .redirectOutput(work.resolve("finish-update.log").toFile())
            .redirectErrorStream(true)
            .start();
    }

    static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    private static String plist(Path work, Path app, String key) throws IOException, InterruptedException {
        return run(List.of("/usr/libexec/PlistBuddy", "-c", "Print :" + key,
            app.resolve("Contents/Info.plist").toString()), true, work, COMMAND_TIMEOUT);
    }

    /** Best effort: a detach that fails or hangs must not hide why staging stopped. */
    private static void detach(Path mount, Path work) {
        try {
            run(List.of("/usr/bin/hdiutil", "detach", mount.toString(), "-force"), false, work, COMMAND_TIMEOUT);
        } catch (IOException ignored) {
            // The image stays mounted read-only until the next restart; nothing else depends on it.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs one command with its timeout counting from the start. Output goes to a
     * log file in {@code logs} rather than a pipe: a pipe has to be read before
     * waiting, and a command that never closes it would block that read with no
     * timeout running at all. The log is deleted afterwards.
     */
    static String run(List<String> command, boolean required, Path logs, Duration timeout)
            throws IOException, InterruptedException {
        String name = Path.of(command.getFirst()).getFileName().toString();
        Path log = Files.createTempFile(logs, "command-", ".log");
        try {
            Process process = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start();
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                stop(process);
                throw e;
            }
            if (!finished) {
                stop(process);
                String said = tail(log);
                throw new IOException(name + " did not finish." + (said.isEmpty() ? "" : " It last said: " + said));
            }
            String output = tail(log);
            if (required && process.exitValue() != 0) throw new IOException(name + " failed: " + output);
            return output;
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /**
     * Stops a command and everything it started. Only this command's own
     * descendants: they are listed before it is killed, while they still are.
     */
    private static void stop(Process process) throws InterruptedException {
        List<ProcessHandle> started = process.descendants().toList();
        process.destroyForcibly();
        started.forEach(ProcessHandle::destroyForcibly);
        process.waitFor(10, TimeUnit.SECONDS);
    }

    private static String tail(Path log) throws IOException {
        try (var file = new RandomAccessFile(log.toFile(), "r")) {
            long size = file.length(), from = Math.max(0, size - OUTPUT_LIMIT);
            byte[] bytes = new byte[(int) (size - from)];
            file.seek(from);
            file.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8).strip();
            return from > 0 ? "…" + text : text;
        }
    }
}
