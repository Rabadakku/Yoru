package dev.yoru;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds every test and runs each in a JVM of its own, several at once (#45).
 *
 * test.sh used to list eighty-odd test runs by hand, and a test left off the
 * list never ran at all. Every {@code *Test.java} under the test sources is a
 * test now; the few that need a real screen are named here and run by hand.
 *
 * Each JVM still gets what test.sh gave it: an empty home of its own, the
 * in-memory preferences and assertions on. A home each, rather than one shared,
 * is what lets them run side by side: two tests can no longer meet in the same
 * vault folder. {@code IsolationTest} runs first and alone, so a runner that
 * stopped isolating fails before any other test can touch the machine.
 */
public final class TestMain {
    private TestMain() { }

    /** Tests that need a real display and a person watching; see README → Interface previews. */
    static final Set<String> BY_HAND = Set.of("dev.yoru.ui.NativeDesktopTest");
    static final String FIRST = "dev.yoru.ui.IsolationTest";
    /** Longer than the slowest test takes on a slow runner, short enough that a hang is noticed. */
    static final long MINUTES_EACH = 15;

    record Outcome(String test, int exit, String output, long millis) { }

    public static void main(String[] args) throws Exception {
        var classes = Path.of(args[0]);
        var sources = Path.of(args[1]);
        var tests = discover(sources);
        if (!tests.remove(FIRST)) throw new IllegalStateException(FIRST + " is missing; nothing can run without it.");

        var first = run(classes, FIRST);
        report(first);
        if (first.exit() != 0) {
            System.out.println("FAIL: the isolation check failed, so no other test ran.");
            System.exit(1);
        }

        int jobs = jobs();
        var pool = Executors.newFixedThreadPool(jobs);
        var done = new ExecutorCompletionService<Outcome>(pool);
        for (var test : tests) done.submit(() -> run(classes, test));
        var failed = new ArrayList<String>();
        for (int i = 0; i < tests.size(); i++) {
            var outcome = done.take().get();
            report(outcome);
            if (outcome.exit() != 0) failed.add(outcome.test());
        }
        pool.shutdown();
        if (!failed.isEmpty()) {
            failed.sort(Comparator.naturalOrder());
            System.out.println("FAIL: " + failed.size() + " of " + (tests.size() + 1) + " test programs: " + String.join(", ", failed));
            System.exit(1);
        }
        System.out.println("PASS: all " + (tests.size() + 1) + " test programs, " + jobs + " at a time"
            + (BY_HAND.isEmpty() ? "" : " (run by hand: " + String.join(", ", BY_HAND) + ")"));
    }

    /** Every test class named for its source file, in name order, less the ones run by hand. */
    static List<String> discover(Path sources) throws IOException {
        try (var files = Files.walk(sources)) {
            return new ArrayList<>(files
                .filter(f -> f.getFileName().toString().endsWith("Test.java"))
                .map(f -> sources.relativize(f).toString().replace('\\', '/'))
                .map(f -> f.substring(0, f.length() - ".java".length()).replace('/', '.'))
                .filter(name -> !BY_HAND.contains(name))
                .sorted()
                .toList());
        }
    }

    static int jobs() {
        String asked = System.getenv("YORU_TEST_JOBS");
        if (asked != null && asked.matches("[1-9][0-9]?")) return Integer.parseInt(asked);
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    static Outcome run(Path classes, String test) throws IOException, InterruptedException {
        var home = Files.createTempDirectory("yoru-test-home");
        long start = System.nanoTime();
        try {
            var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var process = new ProcessBuilder(java, "-Duser.home=" + home,
                "-Djava.util.prefs.PreferencesFactory=dev.yoru.ui.TestPreferencesFactory",
                "-Djava.awt.headless=true", "-ea", "-cp", classes.toString(), test)
                .redirectErrorStream(true)
                .start();
            process.getOutputStream().close();
            var reading = Executors.newVirtualThreadPerTaskExecutor();
            var output = reading.submit(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            boolean finished = process.waitFor(MINUTES_EACH, TimeUnit.MINUTES);
            if (!finished) process.destroyForcibly().waitFor();
            String text;
            try { text = output.get(10, TimeUnit.SECONDS); } catch (Exception e) { text = ""; }
            reading.shutdownNow();
            if (!finished) text += "\nFAIL: " + test + " did not finish within " + MINUTES_EACH + " minutes.";
            return new Outcome(test, finished ? process.exitValue() : -1, text, (System.nanoTime() - start) / 1_000_000);
        } finally {
            try (var files = Files.walk(home)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /** One test's own output, whole, so parallel runs never interleave their lines. */
    static void report(Outcome outcome) {
        var lines = outcome.output().lines()
            .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS"))
            .toList();
        synchronized (System.out) {
            for (var line : lines) System.out.println(line);
            if (outcome.exit() != 0)
                System.out.println("FAIL: " + outcome.test() + " exited with " + outcome.exit()
                    + " after " + outcome.millis() / 1000 + " s");
        }
    }
}
