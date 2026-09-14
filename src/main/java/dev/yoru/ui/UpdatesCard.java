package dev.yoru.ui;

import dev.yoru.update.Download;
import dev.yoru.update.MacInstall;
import dev.yoru.update.ReleaseFeed;
import dev.yoru.update.Updates;
import dev.yoru.update.Version;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import static dev.yoru.ui.Theme.*;

/**
 * Settings → Updates: find a newer Yoru on GitHub and install it (#69 in the
 * predecessor repository).
 *
 * Nothing reaches the network until Check for updates is pressed. An installer is
 * fetched only from the Yoru releases and kept only when its size and SHA-256
 * match the release. On a Mac the new app is staged while this one runs, and a
 * script swaps them once Yoru has closed its game and vault and quit; on Windows
 * the .msi runs after Yoru quits; installing a .deb needs an administrator, so on
 * Linux Yoru verifies it and says the one command to run. A copy built from
 * source is never overwritten.
 */
final class UpdatesCard extends JPanel {

    interface Feed { ReleaseFeed.Release latest() throws Exception; }

    interface Host {
        boolean gameRunning();
        /** Closes the game and the vault as quitting does, runs the step, then ends the process. */
        void quitThen(Runnable afterVaultClosed);
        Component owner();
    }

    private final Version running;
    private final Updates.Platform platform;
    private final Feed feed;
    private final Host host;
    private final JPanel card = card();

    UpdatesCard(Version running, Updates.Platform platform, Feed feed, Host host) {
        super(new BorderLayout());
        this.running = running;
        this.platform = platform;
        this.feed = feed;
        this.host = host;
        setOpaque(false);
        setAlignmentX(0);
        setName("settings.updates");
        add(card, BorderLayout.CENTER);
        begin();
        detail("Yoru asks GitHub for a newer release only when you press this.");
        actions(checkButton("Check for updates"));
        end();
    }

    void check() {
        begin();
        status("Checking GitHub for a newer Yoru…", MUTED);
        end();
        background("yoru-update-check", () -> {
            try {
                var latest = feed.latest();
                SwingUtilities.invokeLater(() -> show(latest));
            } catch (Exception e) {
                String said = e instanceof IOException && e.getMessage() != null ? e.getMessage()
                    : "Yoru could not check for updates. Try again later.";
                SwingUtilities.invokeLater(() -> failed(said));
            }
        });
    }

    void show(ReleaseFeed.Release latest) {
        begin();
        String next = latest.version().toString();
        if (running == null) {
            status("Yoru " + next + " is the latest release.", TEXT);
            detail("This copy was built from source, so Yoru will not replace it. Update it with git pull, then ./build.sh.");
            actions(notesButton(latest), checkButton("Check again"));
        } else if (latest.version().compareTo(running) <= 0) {
            status("Yoru " + running + " is the latest version.", TEXT);
            actions(checkButton("Check again"));
        } else {
            status("Yoru " + next + " is available. You have " + running + ".", ACCENT_TEXT);
            var asset = Updates.assetFor(latest, platform);
            if (asset.isEmpty()) {
                detail("There is no installer for this computer in that release. Its page on GitHub lists what was built.");
                actions(notesButton(latest), checkButton("Check again"));
            } else {
                boolean gameOpen = host.gameRunning();
                String how = switch (platform) {
                    case MAC -> "Yoru downloads it, checks it against the release's SHA-256, then closes its game and vault, replaces itself and reopens.";
                    case WINDOWS -> "Yoru downloads it, checks it against the release's SHA-256, then closes its game and vault so the installer can replace it.";
                    default -> "Yoru downloads it and checks it against the release's SHA-256. Installing a .deb needs your administrator password, so you run one command.";
                };
                detail(gameOpen ? how + " Close the game first." : how);
                var install = accentButton((platform == Updates.Platform.LINUX ? "Download " : "Download and install ") + next,
                    () -> download(latest, asset.get()));
                install.setName("updates.install");
                install.setEnabled(!gameOpen);
                actions(install, notesButton(latest));
            }
        }
        end();
    }

    void failed(String message) {
        begin();
        status(message, DANGER);
        actions(checkButton("Check again"));
        end();
    }

    private void download(ReleaseFeed.Release release, ReleaseFeed.Asset asset) {
        if (host.gameRunning()) { show(release); return; }
        begin();
        status("Downloading Yoru " + release.version() + "…", TEXT);
        var progress = label("Starting…", TYPE_CAPTION, MUTED);
        progress.setName("updates.progress");
        card.add(progress);
        end();
        var shown = new AtomicInteger(-1);
        background("yoru-update-download", () -> {
            final Path work, file;
            try {
                work = Files.createTempDirectory("yoru-update-");
                file = Download.fetch(asset, work, Download.GITHUB, received -> {
                    int percent = (int) (received * 100 / Math.max(1, asset.size()));
                    if (shown.getAndSet(percent) != percent)
                        SwingUtilities.invokeLater(() -> progress.setText(percent + "% of " + megabytes(asset.size())));
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> failed(e.getMessage()));
                return;
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> failed("The download did not finish. Try again."));
                return;
            }
            Path staged = null;
            if (platform == Updates.Platform.MAC) {
                try {
                    staged = MacInstall.stage(file, work, release.version());
                } catch (Exception e) {
                    // Nothing staged survives a failure, so there is nothing to swap in;
                    // the downloaded image goes too, since this path offers no retry from it.
                    Updates.discard(work);
                    SwingUtilities.invokeLater(() -> failed("Yoru verified the update but could not open its disk image. "
                        + "Download it from the release page instead."));
                    return;
                }
            }
            final Path ready = staged;
            SwingUtilities.invokeLater(() -> ready(release, work, file, ready));
        });
    }

    private void ready(ReleaseFeed.Release release, Path work, Path file, Path staged) {
        begin();
        String next = release.version().toString();
        switch (platform) {
            case MAC -> {
                Path installed = MacInstall.runningBundle();
                if (installed == null) {
                    status("Yoru " + next + " is verified, but Yoru cannot tell where it is installed.", DANGER);
                    detail("Download it from the release page and drag it to Applications instead.");
                    actions(notesButton(release));
                } else {
                    status("Yoru " + next + " is downloaded and verified.", TEXT);
                    detail("Yoru will close its game and vault, replace itself and reopen.");
                    var restart = accentButton("Restart to update", () -> restartMac(staged, installed, work));
                    restart.setName("updates.restart");
                    actions(restart);
                }
            }
            case WINDOWS -> {
                status("Yoru " + next + " is downloaded and verified.", TEXT);
                detail("Yoru will close its game and vault, then the installer replaces it. Open Yoru again when the installer finishes.");
                var restart = accentButton("Close and install", () -> closeAndRun(() -> {
                    // The window is gone by now, so there is nowhere left to report a failure;
                    // the installed copy is untouched and the installer can be run by hand.
                    try { Updates.launchWindowsInstaller(file); } catch (IOException ignored) { }
                }));
                restart.setName("updates.restart");
                actions(restart);
            }
            default -> {
                Path kept = file;
                try {
                    Path downloads = Files.createDirectories(Path.of(System.getProperty("user.home"), "Downloads"));
                    kept = Files.move(file, downloads.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // Left where it was verified; the command below names the file either way.
                }
                status("Yoru " + next + " is downloaded and verified.", TEXT);
                detail("Install it from a terminal opened in its folder: sudo apt install ./" + kept.getFileName());
                Path folder = kept.getParent();
                var reveal = button("Show in folder", () -> open(folder));
                reveal.setName("updates.showFile");
                actions(reveal);
            }
        }
        end();
    }

    /**
     * Starts the swap script before quitting. It waits for this process to end, so
     * a failure to start it leaves Yoru running and says so. If quitting is then
     * refused — a game that will not stop — the script finishes the update
     * whenever Yoru does quit.
     */
    private void restartMac(Path staged, Path installed, Path work) {
        if (host.gameRunning()) { Dialogs.info(host.owner(), "Close the game first. Yoru closes to install the update."); return; }
        if (!Dialogs.confirm(host.owner(), "Close Yoru and install the update?\n\n"
                + "Your game and vault are closed first, then Yoru replaces itself and reopens.", "Install update", "Restart"))
            return;
        try {
            MacInstall.launch(MacInstall.swapScript(ProcessHandle.current().pid(), staged, installed, true), work);
        } catch (IOException e) {
            failed("Yoru could not start the update. Nothing was changed; try again.");
            return;
        }
        host.quitThen(() -> { });
    }

    private void closeAndRun(Runnable step) {
        if (host.gameRunning()) { Dialogs.info(host.owner(), "Close the game first. Yoru closes to install the update."); return; }
        if (!Dialogs.confirm(host.owner(), "Close Yoru and install the update?\n\nYour game and vault are closed first.",
                "Install update", "Close and install"))
            return;
        host.quitThen(step);
    }

    /** Clears the card down to its heading and the version that is running. */
    private void begin() {
        card.removeAll();
        card.add(sectionHeader("UPDATES"));
        gap(card, SPACE_SM);
        var version = label(running == null ? "This copy of Yoru was built from source." : "Yoru " + running, TYPE_LABEL, TEXT);
        version.setName("updates.version");
        card.add(version);
        gap(card, SPACE_SM);
    }

    private void end() {
        card.revalidate();
        card.repaint();
    }

    private void status(String text, Color colour) {
        var line = label(text, TYPE_BODY, colour);
        line.setName("updates.status");
        card.add(line);
        gap(card, SPACE_XS);
    }

    private void detail(String text) {
        JLabel line = bodyLabel(text);
        line.setName("updates.detail");
        card.add(line);
        gap(card, SPACE_MD);
    }

    private void actions(JComponent... controls) { card.add(flushRow(controls)); }

    private JButton checkButton(String text) {
        var button = button(text, this::check);
        button.setName("updates.check");
        return button;
    }

    private JButton notesButton(ReleaseFeed.Release release) {
        var button = button("What's new", () -> browse(release));
        button.setName("updates.notes");
        return button;
    }

    private void browse(ReleaseFeed.Release release) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(release.page());
                return;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Falls through to saying where the notes are.
        }
        Dialogs.info(host.owner(), "What's new in Yoru " + release.version() + " is on its release page:\n\n" + release.page());
    }

    private void open(Path folder) {
        try {
            if (Desktop.isDesktopSupported()) { Desktop.getDesktop().open(folder.toFile()); return; }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Falls through to naming the folder.
        }
        Dialogs.info(host.owner(), "The installer is in " + folder);
    }

    private static String megabytes(long bytes) { return String.format("%.1f MB", bytes / 1_048_576.0); }

    private static void background(String name, Runnable work) {
        var thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }
}
