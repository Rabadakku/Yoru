package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Optional on-device check; headless CI cannot verify the macOS screen menu. */
public final class NativeDesktopTest {
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless() || !DesktopChrome.mac()) {
            System.out.println("SKIP: native desktop check requires a Mac display"); return;
        }
        try {
            DesktopChrome.prepare();
            var window = new AtomicReference<JFrame>(); var workspace = new AtomicReference<YoruApp>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Theme.apply(ThemeId.LINEN);
                    var app = Preview.trackerApp(ThemeId.LINEN,1280,900);
                    var frame = new JFrame("Yoru preview"); frame.setContentPane(app);
                    DesktopChrome.install(frame,app); frame.pack(); frame.setVisible(true);
                    window.set(frame); workspace.set(app);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Thread.sleep(700);
            SwingUtilities.invokeAndWait(() -> {
                var frame = window.get();
                assert frame.getMenuBar() != null : "Menu did not reach the native screen menu bar";
                assert frame.getJMenuBar().getHeight() == 0 : "Menu is still drawn inside the window";
                assert Boolean.TRUE.equals(frame.getRootPane().getClientProperty("apple.awt.fullWindowContent"));
                workspace.get().closeVault();
            });
            System.out.println("PASS: macOS native screen menu, unified title bar and safe close");
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
}
