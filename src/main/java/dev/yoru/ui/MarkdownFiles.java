package dev.yoru.ui;

import dev.yoru.pages.MarkdownDirectory;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** File pickers around the bounded Markdown interchange service. */
final class MarkdownFiles {
    private MarkdownFiles() { }
    static void importInto(Shell shell, BooleanSupplier flush, Runnable refresh) {
        if (!flush.getAsBoolean()) return;
        var picker = new JFileChooser(); picker.setDialogTitle("Import Markdown file or folder");
        picker.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (picker.showOpenDialog(shell.owner()) != JFileChooser.APPROVE_OPTION) return;
        try {
            var imported = MarkdownDirectory.read(picker.getSelectedFile().toPath());
            if (!Dialogs.confirm(shell.owner(), "Import " + imported.pages().size() + " Markdown pages into a new folder?\n\nImages, other attachments and hidden configuration files are not imported in this version.", "Import pages", "Import")) return;
            shell.tracker().pages().importNotes(imported);
            refresh.run();
        } catch (Exception e) { shell.error(e); }
    }
    static void exportFrom(Shell shell, BooleanSupplier flush) {
        if (!flush.getAsBoolean()) return;
        var picker = new JFileChooser(); picker.setDialogTitle("Export Markdown to a new folder");
        picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (picker.showSaveDialog(shell.owner()) != JFileChooser.APPROVE_OPTION) return;
        if (!Dialogs.confirm(shell.owner(), "Export live pages as plain Markdown?\n\nExported files are not encrypted. Pages in the trash are excluded.", "Export pages", "Export")) return;
        try {
            var folder = MarkdownDirectory.write(shell.tracker().state().notes(), picker.getSelectedFile().toPath());
            Dialogs.info(shell.owner(), "Pages exported to " + folder.getFileName() + ".");
        } catch (Exception e) { shell.error(e); }
    }
}
