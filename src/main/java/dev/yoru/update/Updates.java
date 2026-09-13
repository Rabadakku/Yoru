package dev.yoru.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Which installer a platform takes from a release, and how Windows runs one. */
public final class Updates {
    private Updates() { }

    /**
     * The release builds a .dmg for Apple silicon, an .msi for Windows and a .deb
     * for 64-bit Intel and AMD Linux. Anything else has no installer to take.
     */
    public enum Platform { MAC, WINDOWS, LINUX, UNSUPPORTED }

    public static Platform platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT), arch = osArch.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) return arch.equals("aarch64") || arch.equals("arm64") ? Platform.MAC : Platform.UNSUPPORTED;
        if (os.startsWith("windows")) return Platform.WINDOWS;
        if (os.startsWith("linux")) return arch.equals("amd64") || arch.equals("x86_64") ? Platform.LINUX : Platform.UNSUPPORTED;
        return Platform.UNSUPPORTED;
    }

    public static Platform current() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static Optional<ReleaseFeed.Asset> assetFor(ReleaseFeed.Release release, Platform platform) {
        String suffix = switch (platform) {
            case MAC -> ".dmg";
            case WINDOWS -> ".msi";
            case LINUX -> "_amd64.deb";
            case UNSUPPORTED -> null;
        };
        if (suffix == null) return Optional.empty();
        return release.assets().stream().filter(asset -> asset.name().endsWith(suffix)).findFirst();
    }

    /**
     * Starts the verified .msi as an upgrade. The installer carries a fixed
     * upgrade code, so it replaces the installed copy rather than sitting beside
     * it; Yoru quits so its files are free to replace.
     */
    public static void launchWindowsInstaller(Path msi) throws IOException {
        new ProcessBuilder("msiexec", "/i", msi.toString(), "/passive").start();
    }
}
