package dev.yoru.update;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * is Yoru at the version expected. The image is detached whatever happens.
     */
    public static Path stage(Path dmg, Path work, Version expected) throws IOException, InterruptedException {
        Path mount = Files.createDirectories(work.resolve("mount"));
        run(List.of("/usr/bin/hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen",
            "-mountpoint", mount.toString(), dmg.toString()), true);
        try {
            Path app;
            try (Stream<Path> entries = Files.list(mount)) {
                app = entries.filter(p -> p.getFileName().toString().endsWith(".app")).findFirst()
                    .orElseThrow(() -> new IOException("The update's disk image holds no app."));
            }
            Path staged = work.resolve(app.getFileName().toString());
            run(List.of("/bin/rm", "-rf", staged.toString()), true);
            run(List.of("/usr/bin/ditto", app.toString(), staged.toString()), true);
            String id = plist(staged, "CFBundleIdentifier"), version = plist(staged, "CFBundleShortVersionString");
            if (!"dev.yoru".equals(id)) throw new IOException("The update's app is not Yoru.");
            if (!expected.toString().equals(version))
                throw new IOException("The update's app is version " + version + ", not " + expected + ".");
            return staged;
        } finally {
            run(List.of("/usr/bin/hdiutil", "detach", mount.toString(), "-force"), false);
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

    private static String plist(Path app, String key) throws IOException, InterruptedException {
        return run(List.of("/usr/libexec/PlistBuddy", "-c", "Print :" + key,
            app.resolve("Contents/Info.plist").toString()), true);
    }

    private static String run(List<String> command, boolean required) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException(Path.of(command.getFirst()).getFileName() + " did not finish.");
        }
        if (required && process.exitValue() != 0)
            throw new IOException(Path.of(command.getFirst()).getFileName() + " failed: " + output);
        return output;
    }
}
