package dev.yoru.update;

import dev.yoru.json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Updating Yoru from its own GitHub releases (#69 in the predecessor repository).
 *
 * No test here reaches the internet: a loopback HTTP server stands in for GitHub,
 * with invented releases and bytes. What is pinned is the part that decides
 * whether anything gets installed — the version comparison, which installer a
 * platform takes, that nothing is trusted from anywhere but the Yoru releases,
 * and that a download whose size or SHA-256 disagrees with the release never
 * becomes a file an installer could run.
 */
public final class UpdateTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, Object> asset(String name, long size, String digest, String url) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("size", size);
        m.put("digest", digest);
        m.put("browser_download_url", url);
        return m;
    }

    private static String releaseJson(String tag, List<Map<String, Object>> assets) {
        var m = new LinkedHashMap<String, Object>();
        m.put("tag_name", tag);
        m.put("html_url", "https://github.com/Rabadakku/Yoru/releases/tag/" + tag);
        m.put("body", "What changed in " + tag);
        m.put("draft", false);
        m.put("prerelease", false);
        m.put("assets", assets);
        return Json.write(m);
    }

    /**
     * A minimal HTTP/1.1 server on loopback, java.base only: the tests compile
     * inside the dev.yoru module, which does not read jdk.httpserver, and the
     * application should not start requiring it for a test.
     */
    private static final class Local implements AutoCloseable {
        private final ServerSocket socket;
        private final Map<String, Object[]> routes = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

        Local() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            var thread = new Thread(this::serve, "update-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        String base() { return "http://127.0.0.1:" + socket.getLocalPort(); }

        void route(String path, int status, byte[] body) { routes.put(path, new Object[] {status, body}); }

        int hits(String path) { return hits.computeIfAbsent(path, p -> new AtomicInteger()).get(); }

        private void serve() {
            while (!socket.isClosed()) {
                try (var client = socket.accept()) {
                    var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String requestLine = in.readLine();
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) { }
                    String path = requestLine == null || requestLine.split(" ").length < 2 ? "" : requestLine.split(" ")[1];
                    hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
                    Object[] route = routes.getOrDefault(path, new Object[] {404, new byte[0]});
                    byte[] body = (byte[]) route[1];
                    var out = client.getOutputStream();
                    out.write(("HTTP/1.1 " + route[0] + " Test\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.flush();
                } catch (IOException e) {
                    if (socket.isClosed()) return;
                }
            }
        }

        @Override
        public void close() throws IOException { socket.close(); }
    }

    private static void versions() {
        check(Version.parse("1.0.4").equals(Version.parse("v1.0.4")), "a leading v is the tag's, not the version's");
        check(Version.parse("1.0.10").compareTo(Version.parse("1.0.9")) > 0, "1.0.10 is newer than 1.0.9, not older");
        check(Version.parse("2.0.0").compareTo(Version.parse("1.9.9")) > 0, "a major version outranks everything below it");
        check(Version.parse("v1.0.5").toString().equals("1.0.5"), "and prints without the v");
        for (String bad : new String[] {"1.0", "v1.0.4-rc1", "one", "", "1.0.4.1"}) {
            try { Version.parse(bad); check(false, "\"" + bad + "\" is refused"); }
            catch (IllegalArgumentException expected) { }
        }
        System.clearProperty("jpackage.app-version");
        check(Version.running() == null, "a copy built from source has no installed version");
        System.setProperty("jpackage.app-version", "1.0.2");
        check(Version.parse("1.0.2").equals(Version.running()), "an installed copy reads the version its launcher was given");
        System.clearProperty("jpackage.app-version");
    }

    private static void parsing() {
        String hex = "c9d2428bf41294d1b3221e0af1b8878971ed9b5b785c8ed0bbdd77a2a42d2435";
        var release = ReleaseFeed.parse(releaseJson("v1.0.5", List.of(
            asset("Yoru-1.0.5.dmg", 31234504, "sha256:" + hex, "https://github.com/Rabadakku/Yoru/releases/download/v1.0.5/Yoru-1.0.5.dmg"),
            asset("Yoru-1.0.5.msi", 29638254, null, "https://github.com/Rabadakku/Yoru/releases/download/v1.0.5/Yoru-1.0.5.msi"),
            asset("yoru_1.0.5_amd64.deb", 24650180, "sha1:abcd", "https://github.com/Rabadakku/Yoru/releases/download/v1.0.5/yoru_1.0.5_amd64.deb"))));
        check(release.version().equals(Version.parse("1.0.5")) && release.tag().equals("v1.0.5"), "the tag gives the version");
        check(release.page().toString().endsWith("/releases/tag/v1.0.5"), "the release page is kept for What's new");
        check(release.notes().equals("What changed in v1.0.5"), "and so are its notes");
        check(release.assets().size() == 3, "every asset is read");
        check(hex.equals(release.assets().get(0).sha256()), "a sha256 digest is kept as hex");
        check(release.assets().get(1).sha256() == null, "a missing digest is absent, not guessed");
        check(release.assets().get(2).sha256() == null, "and a digest that is not SHA-256 is not trusted as one");

        check(Updates.platform("Mac OS X", "aarch64") == Updates.Platform.MAC, "Apple silicon takes the .dmg");
        check(Updates.platform("Mac OS X", "x86_64") == Updates.Platform.UNSUPPORTED, "an Intel Mac has no build to take");
        check(Updates.platform("Windows 11", "amd64") == Updates.Platform.WINDOWS, "Windows takes the .msi");
        check(Updates.platform("Linux", "amd64") == Updates.Platform.LINUX, "64-bit Intel and AMD Linux takes the .deb");
        check(Updates.platform("Linux", "aarch64") == Updates.Platform.UNSUPPORTED, "ARM Linux has no build to take");
        check(Updates.assetFor(release, Updates.Platform.MAC).orElseThrow().name().equals("Yoru-1.0.5.dmg"), "the Mac asset is the .dmg");
        check(Updates.assetFor(release, Updates.Platform.WINDOWS).orElseThrow().name().endsWith(".msi"), "the Windows asset is the .msi");
        check(Updates.assetFor(release, Updates.Platform.LINUX).orElseThrow().name().endsWith("_amd64.deb"), "the Linux asset is the .deb");
        check(Updates.assetFor(release, Updates.Platform.UNSUPPORTED).isEmpty(), "and an unsupported platform gets none");

        check(Download.GITHUB.test(URI.create("https://github.com/Rabadakku/Yoru/releases/download/v1.0.5/Yoru-1.0.5.dmg")),
            "a Yoru release download is trusted");
        for (String url : new String[] {
                "http://github.com/Rabadakku/Yoru/releases/download/v1.0.5/Yoru-1.0.5.dmg",
                "https://github.com/someone/Yoru/releases/download/v1.0.5/Yoru-1.0.5.dmg",
                "https://github.com.example.net/Rabadakku/Yoru/releases/download/v1.0.5/Yoru-1.0.5.dmg",
                "https://github.com/Rabadakku/Yoru/archive/refs/tags/v1.0.5.zip"})
            check(!Download.GITHUB.test(URI.create(url)), url + " is not trusted");
    }

    private static void overHttp() throws Exception {
        byte[] payload = new byte[70_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        try (var server = new Local()) {
            String base = server.base();
            server.route("/Yoru-9.9.9.dmg", 200, payload);
            String latest = releaseJson("v9.9.9", List.of(asset("Yoru-9.9.9.dmg", payload.length, "sha256:" + sha256(payload), base + "/Yoru-9.9.9.dmg")));
            server.route("/ok/releases/latest", 200, latest.getBytes(StandardCharsets.UTF_8));
            server.route("/busy/releases/latest", 403, new byte[0]);

            var release = new ReleaseFeed(URI.create(base + "/ok/releases/latest")).latest();
            check(release.version().equals(Version.parse("9.9.9")), "the latest release is read from the feed");
            try { new ReleaseFeed(URI.create(base + "/none/releases/latest")).latest(); check(false, "no release is an error"); }
            catch (IOException e) { check(e.getMessage().contains("No release"), "that says there is no release yet: " + e.getMessage()); }
            try { new ReleaseFeed(URI.create(base + "/busy/releases/latest")).latest(); check(false, "a rate limit is an error"); }
            catch (IOException e) { check(e.getMessage().contains("try again later"), "that says to try again later: " + e.getMessage()); }

            java.util.function.Predicate<URI> local = u -> u.toString().startsWith(base + "/");
            var dir = Files.createTempDirectory("yoru-update-test");
            var progress = new AtomicLong();
            var good = release.assets().getFirst();
            Path file = Download.fetch(good, dir, local, progress::set);
            check(java.util.Arrays.equals(Files.readAllBytes(file), payload), "a verified download is exactly the served bytes");
            check(progress.get() == payload.length, "and progress reaches the whole size, got " + progress.get());
            check(!Files.exists(dir.resolve(good.name() + ".part")), "with no partial file left behind");

            var dir2 = Files.createTempDirectory("yoru-update-test");
            var wrongDigest = new ReleaseFeed.Asset(good.name(), good.size(), sha256(new byte[] {1, 2, 3}), good.url());
            refused(() -> Download.fetch(wrongDigest, dir2, local, p -> { }), "did not match", dir2, good.name());
            var noDigest = new ReleaseFeed.Asset(good.name(), good.size(), null, good.url());
            refused(() -> Download.fetch(noDigest, dir2, local, p -> { }), "no SHA-256", dir2, good.name());
            var wrongSize = new ReleaseFeed.Asset(good.name(), good.size() - 1, good.sha256(), good.url());
            refused(() -> Download.fetch(wrongSize, dir2, local, p -> { }), "size", dir2, good.name());

            int before = server.hits("/Yoru-9.9.9.dmg");
            refused(() -> Download.fetch(good, dir2, Download.GITHUB, p -> { }), "not a Yoru release", dir2, good.name());
            check(server.hits("/Yoru-9.9.9.dmg") == before, "an untrusted address is never even requested");
        }
    }

    private interface Fetch { Path run() throws Exception; }

    private static void refused(Fetch fetch, String says, Path dir, String name) throws Exception {
        try {
            fetch.run();
            check(false, "a download that should be refused was accepted");
        } catch (IOException e) {
            check(e.getMessage().contains(says), "the refusal says \"" + says + "\": " + e.getMessage());
        }
        check(!Files.exists(dir.resolve(name)) && !Files.exists(dir.resolve(name + ".part")),
            "and leaves no file an installer could run");
    }

    private static void macScript() {
        check(Path.of("/Applications/Yoru.app").equals(MacInstall.bundleOf(Path.of("/Applications/Yoru.app/Contents/MacOS/Yoru"))),
            "the bundle is found from the launcher inside it");
        check(MacInstall.bundleOf(Path.of("/usr/bin/java")) == null, "a launcher outside a bundle has none");

        var staged = Path.of("/tmp/it's staged/Yoru.app");
        var installed = Path.of("/Applications/Yoru.app");
        String script = MacInstall.swapScript(4242, staged, installed, true);
        check(script.contains("kill -0 4242"), "the script waits for this copy of Yoru to quit");
        check(script.contains("'/tmp/it'\\''s staged/Yoru.app'"), "a path with an apostrophe is quoted for the shell:\n" + script);
        check(script.indexOf("mv \"$installed\" \"$previous\"") < script.indexOf("mv \"$staged\" \"$installed\""),
            "the old copy is moved aside before the new one moves in");
        check(script.contains("mv \"$previous\" \"$installed\""), "and put back if the new one cannot move in");
        check(script.contains("open \"$installed\""), "then Yoru is reopened");
        check(!MacInstall.swapScript(4242, staged, installed, false).contains("open \"$installed\""), "unless told not to");
    }

    public static void main(String[] args) throws Exception {
        versions();
        parsing();
        overHttp();
        macScript();
        System.out.println("PASS: " + checks + " update checks (versions, feed, platforms, trusted downloads, checksums, Mac swap script)");
    }
}
