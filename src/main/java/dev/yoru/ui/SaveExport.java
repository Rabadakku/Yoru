package dev.yoru.ui;

import java.nio.file.Files;

/** Writes a copy of the vault's game save to a file the user chooses, from any page that shows it. */
final class SaveExport {
    private SaveExport() { }

    static void export(Shell shell) {
        var game = shell.tracker().state().game();
        if (game == null) return;
        var rom = GameFiles.rom();
        var file = Dialogs.saveFile(shell.owner(), "Export game save", (rom == null ? "game" : GameFiles.stem(rom)) + ".sav");
        if (file == null) return;
        shell.perform(() -> {
            Files.write(file, game.bytes());
            Dialogs.info(shell.owner(), "A copy of your game is saved in " + file.getFileName() + ".\n\n"
                + "Emulators load a save that sits beside the game file under the same name. RetroArch looks for .srm.");
        });
    }
}
