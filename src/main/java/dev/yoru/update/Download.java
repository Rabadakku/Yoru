package dev.yoru.update;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * Fetches one release installer and proves it is the file GitHub published.
 *
 * It streams to {@code name.part}, hashed as it arrives, and becomes {@code name}
 * only when its size and SHA-256 match the release. Anything else deletes it, so
 * an installer is only ever run from a file that passed.
 */
public final class Download {
    private Download() { }

    /** Only files attached to a Yoru release on GitHub, over HTTPS. */
    public static final Predicate<URI> GITHUB = url -> "https".equals(url.getScheme())
        && "github.com".equals(url.getHost()) && url.getPort() == -1
        && url.getRawPath() != null && url.getRawPath().startsWith("/Rabadakku/Yoru/releases/download/");

    public static Path fetch(ReleaseFeed.Asset asset, Path dir, Predicate<URI> trusted, LongConsumer progress)
            throws IOException, InterruptedException {
        if (!trusted.test(asset.url()))
            throw new IOException(asset.url() + " is not a Yoru release download, so Yoru will not fetch it.");
        if (asset.sha256() == null)
            throw new IOException("This release lists no SHA-256 checksum for " + asset.name() + ", so Yoru cannot verify it.");
        Path named = Path.of(asset.name()).getFileName();
        if (named == null || !named.toString().equals(asset.name()))
            throw new IOException("The release names a file Yoru will not write: " + asset.name());
        String name = named.toString();
        Path part = dir.resolve(name + ".part"), done = dir.resolve(name);
        Files.deleteIfExists(done);
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("This Java has no SHA-256, so Yoru cannot verify an update.", e);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder(asset.url()).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Yoru-updater").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long received = 0;
            try (var in = response.body(); OutputStream out = Files.newOutputStream(part)) {
                if (response.statusCode() != 200)
                    throw new IOException("GitHub answered with an error (HTTP " + response.statusCode() + ") for " + name + ".");
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    received += n;
                    if (received > asset.size())
                        throw new IOException(name + " is larger than its release says; its size does not match.");
                    sha256.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    progress.accept(received);
                }
            }
            if (received != asset.size())
                throw new IOException(name + " is " + received + " bytes but its release says " + asset.size() + "; its size does not match.");
            if (!HexFormat.of().formatHex(sha256.digest()).equals(asset.sha256()))
                throw new IOException("The SHA-256 of " + name + " did not match its release, so it was deleted.");
            Files.move(part, done, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return done;
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }
}
