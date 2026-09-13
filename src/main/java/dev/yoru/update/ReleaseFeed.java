package dev.yoru.update;

import dev.yoru.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The newest published Yoru release, read from GitHub only when someone asks.
 *
 * One GET to the releases API, which never returns drafts or pre-releases.
 * Nothing about the person or their vault is sent: the request carries the
 * User-Agent GitHub requires and nothing else.
 */
public final class ReleaseFeed {
    public static final URI LATEST = URI.create("https://api.github.com/repos/Rabadakku/Yoru/releases/latest");
    private static final Pattern SHA256 = Pattern.compile("sha256:([0-9a-f]{64})");

    /** One file attached to a release. {@code sha256} is lowercase hex, or null when GitHub gives none. */
    public record Asset(String name, long size, String sha256, URI url) { }

    public record Release(Version version, String tag, URI page, String notes, List<Asset> assets) { }

    private final URI endpoint;

    public ReleaseFeed() { this(LATEST); }

    public ReleaseFeed(URI endpoint) { this.endpoint = endpoint; }

    public Release latest() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Yoru-updater")
                .GET().build();
            HttpResponse<byte[]> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                throw new IOException("Yoru could not reach GitHub. Check your internet connection and try again.", e);
            }
            switch (response.statusCode()) {
                case 200 -> { }
                case 404 -> throw new IOException("No release of Yoru has been published yet.");
                case 403, 429 -> throw new IOException("GitHub is limiting requests right now; try again later.");
                default -> throw new IOException("GitHub answered with an error (HTTP " + response.statusCode() + "); try again later.");
            }
            byte[] body = response.body();
            if (body.length > 1_000_000) throw new IOException("GitHub's answer was too large to be a release.");
            try {
                return parse(new String(body, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IOException("GitHub's answer was not a release Yoru can read.", e);
            }
        }
    }

    /** A releases-API response. A digest that is not SHA-256 is treated as no digest at all. */
    public static Release parse(String json) {
        var root = Json.object(Json.read(json));
        String tag = Json.string(root.get("tag_name"));
        var version = Version.parse(tag);
        URI page = URI.create(Json.string(root.get("html_url")));
        String notes = root.get("body") instanceof String text ? text : "";
        var assets = new ArrayList<Asset>();
        if (root.get("assets") != null) {
            for (Object item : Json.array(root.get("assets"))) {
                var asset = Json.object(item);
                if (!(asset.get("size") instanceof Number size)) throw new IllegalArgumentException("An asset has no size.");
                String sha256 = null;
                if (asset.get("digest") instanceof String digest) {
                    var match = SHA256.matcher(digest);
                    if (match.matches()) sha256 = match.group(1);
                }
                assets.add(new Asset(Json.string(asset.get("name")), size.longValue(), sha256,
                    URI.create(Json.string(asset.get("browser_download_url")))));
            }
        }
        return new Release(version, tag, page, notes, List.copyOf(assets));
    }
}
