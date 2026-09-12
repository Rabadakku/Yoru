package dev.yoru.ui;

import dev.yoru.assets.ArtworkLibrary;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.function.Consumer;

/** One background import path shared by Settings, file drops and Game setup. */
final class ArtworkImport {
    private static boolean busy;
    static void start(Component owner, Path source, Runnable ready, Consumer<Exception> error) {
        if(busy)return;
        busy=true;
        var progress=new JProgressBar();progress.setIndeterminate(true);
        var content=Theme.stack();content.add(Theme.bodyLabel("Extracting and adding your artwork…"));
        Theme.gap(content,Theme.SPACE_MD);content.add(progress);
        var dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),"Adding artwork",Dialog.ModalityType.MODELESS);
        content.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_XL,Theme.SPACE_XL,Theme.SPACE_XL,Theme.SPACE_XL));
        dialog.setContentPane(content);dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.pack();dialog.setLocationRelativeTo(owner);dialog.setVisible(true);
        new SwingWorker<ArtworkLibrary.Report,Void>() {
            protected ArtworkLibrary.Report doInBackground() throws Exception { return ArtworkLibrary.install(source); }
            protected void done() {
                busy=false;dialog.dispose();
                try { var report=get();SpriteAssets.refresh();ready.run();Dialogs.info(owner,"Artwork ready",report.summary()); }
                catch(Exception e) { error.accept(e.getCause() instanceof Exception cause?cause:e); }
            }
        }.execute();
    }
}
