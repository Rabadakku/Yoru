package dev.yoru.assets;

import java.nio.file.*;
import java.util.Comparator;
import java.util.zip.*;

/**
 * Artwork import: filing rules and the zip path.
 *
 * The importer accepts whatever shape a user's files arrive in, so the mapping
 * from a source name to a library slot is where a silent mis-file would happen.
 */
public final class ArtworkTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
    private static void maps(String from,String to) {
        var actual=ArtworkLibrary.destinationFor(from);
        check(to==null?actual==null:to.equals(actual),from+" -> "+actual+" (expected "+to+")");
    }

    private static byte[] image() throws Exception {
        var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16,16,2),"png",out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        maps("1.png","1.png");
        maps("386.png","386.png");
        maps("007.png","7.png");                      // zero padding is common in dumps
        maps("025.PNG","25.png");                     // and so is upper case
        maps("sprites/emerald/150.png","150.png");    // nesting is ignored
        maps("shiny/25.png","shiny/25.png");
        maps("Art/Shiny/0025.png","shiny/25.png");    // the shiny folder can be anywhere, any case
        maps("brendan.png","brendan.png");
        maps("may-running.png","may-running.png");
        maps("tree.png","tree.png");

        maps("readme.txt",null);
        maps("0.png",null);                           // dex numbers start at one
        maps("387.png",null);                         // and stop at 386
        maps("pikachu.png",null);                     // names are not resolved to numbers
        maps("../escape/1.png",null);                 // an archive path must never climb out
        maps("shiny/../../1.png",null);
        maps(".png",null);

        // A remembered failure is shown in Settings, so it must read as product copy (#6).
        check(ArtworkLibrary.reason(new java.nio.file.NoSuchFileException("/invented/folder/art.zip"))
            .equals("Yoru could not read or write part of the artwork library."),"a file system error never shows its path");
        check(ArtworkLibrary.reason(new java.io.IOException("Choose an Emerald game file, a folder, or a zip of artwork."))
            .equals("Choose an Emerald game file, a folder, or a zip of artwork."),"the library's own messages are kept");

        // A zip of mixed content installs only the artwork, and reports the rest as skipped.
        Path dir=Files.createTempDirectory("yoru-art-test-");
        Path zip=dir.resolve("art.zip");
        try(var out=new ZipOutputStream(Files.newOutputStream(zip))) {
            for(String name:new String[]{"1.png","002.png","shiny/1.png","brendan.png"}) {
                out.putNextEntry(new ZipEntry(name));
                out.write(image());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("notes.txt"));
            out.write("not artwork".getBytes());
            out.closeEntry();
            // A renamed non-image must not land in the library.
            out.putNextEntry(new ZipEntry("9.png"));
            out.write("MZ not really a png".getBytes());
            out.closeEntry();
        }

        String home=System.getProperty("user.home");
        Path sandbox=Files.createTempDirectory("yoru-art-home-");
        System.setProperty("user.home",sandbox.toString());
        try {
            var report=ArtworkLibrary.install(zip);
            check(report.species()==2,"two numbered sprites installed, got "+report.species());
            check(report.shiny()==1,"one shiny installed");
            check(report.sheets()==1,"one overworld sheet installed");
            check(report.skipped()>=2,"non-artwork and non-PNG entries skipped");
            check(Files.isRegularFile(ArtworkLibrary.root().resolve("2.png")),"zero padding normalised on disk");
            check(!Files.exists(ArtworkLibrary.root().resolve("9.png")),"a renamed non-PNG is rejected");
            check(ArtworkLibrary.survey().species()==2,"survey agrees with the install");
            Path active=ArtworkLibrary.root();
            byte[] original=Files.readAllBytes(active.resolve("1.png"));
            Path broken=dir.resolve("broken.zip");
            try(var out=new ZipOutputStream(Files.newOutputStream(broken))) {
                out.putNextEntry(new ZipEntry("3.png"));
                out.write(image());out.closeEntry();
                out.putNextEntry(new ZipEntry("unsupported.gba"));
                out.write(new byte[32]);out.closeEntry();
            }
            check(ArtworkLibrary.lastFailure()==null,"no failure is remembered before one happens");
            try { ArtworkLibrary.install(broken);throw new AssertionError("invalid game accepted"); }
            catch(java.io.IOException expected) {
                // Remembered on disk (#6), so Settings can still say so after a restart.
                var failure=ArtworkLibrary.lastFailure();
                check(failure!=null&&failure.reason().equals(ArtworkLibrary.reason(expected)),"the failed import is remembered: "+failure);
                check(Files.isRegularFile(sandbox.resolve(".yoru/art/last-failure.properties")),"in the library folder, not in memory");
            }
            try { ArtworkLibrary.install(dir.resolve("no-such-art.zip"));throw new AssertionError("a missing source accepted"); }
            catch(java.io.IOException expected) {
                check("That file or folder no longer exists.".equals(ArtworkLibrary.lastFailure().reason()),"the newest failure replaces the one before");
            }
            check(ArtworkLibrary.root().equals(active),"failure after a staged image preserves the active generation");
            check(java.util.Arrays.equals(original,Files.readAllBytes(active.resolve("1.png"))),"previous artwork survives byte for byte");
            check(!Files.exists(active.resolve("3.png")),"a failed import publishes no partial files");
            Path extra=dir.resolve("4.png");Files.write(extra,image());
            ArtworkLibrary.install(extra);
            check(ArtworkLibrary.lastFailure()==null,"a successful import clears the remembered failure");
            check(!ArtworkLibrary.root().equals(active),"successful import publishes a new generation");
            check(ArtworkLibrary.survey().species()==3,"incremental import preserves existing images");
            check(Files.exists(active.resolve("1.png")),"previous generation remains recoverable");

            // Retention (#6): the active generation and the one it replaced stay; older ones go.
            Path second=ArtworkLibrary.root();
            Path generations=second.getParent();
            Path unrelated=Files.createDirectories(generations.resolve("keep-me"));
            Path fifth=dir.resolve("5.png");Files.write(fifth,image());
            var before=ArtworkLibrary.survey();
            var third=ArtworkLibrary.install(fifth);
            check(third.changesSince(before).equals("Added 1 Pokémon picture."),"an import says what it added: "+third.changesSince(before));
            try(var entries=Files.list(generations)) {
                var names=entries.map(p->p.getFileName().toString()).sorted().toList();
                check(names.size()==3,"the active generation, the one it replaced and an unrelated folder remain: "+names);
            }
            check(Files.isDirectory(second)&&Files.exists(second.resolve("4.png")),"the replaced generation stays as the rollback");
            check(!Files.exists(active),"an older generation is removed, so imports stop piling up full copies");
            check(Files.isDirectory(unrelated),"a folder not named like a generation is never touched");
            var unchanged=ArtworkLibrary.survey();
            var again=ArtworkLibrary.install(fifth);
            check(again.changesSince(unchanged).startsWith("Updated 1 file"),"re-importing a file already there adds nothing: "+again.changesSince(unchanged));

            var categories=ArtworkLibrary.survey().categories();
            check(categories.stream().map(ArtworkLibrary.Category::key).toList().equals(java.util.List.of("game","pokemon","backgrounds","scenery")),
                "four kinds of artwork, in order");
            check(categories.get(0).readiness()==ArtworkLibrary.Readiness.MISSING&&categories.get(0).describe().equals("Missing"),"no game file is missing");
            check(categories.get(1).readiness()==ArtworkLibrary.Readiness.PARTIAL&&categories.get(1).describe().equals("Partial · 4 of 386 normal, 1 shiny"),
                "a few sprites are partial, with counts: "+categories.get(1).describe());
            check(categories.get(2).readiness()==ArtworkLibrary.Readiness.MISSING,"no box backgrounds are missing");
            check(categories.get(3).readiness()==ArtworkLibrary.Readiness.PARTIAL&&categories.get(3).describe().equals("Partial · 1 of 7 sheets"),
                "one scenery sheet is partial: "+categories.get(3).describe());
            var complete=new ArtworkLibrary.Report(386,386,10,0,1,16,0,java.util.List.of());
            check(complete.categories().stream().allMatch(c->c.readiness()==ArtworkLibrary.Readiness.READY),"a complete library is ready everywhere");

            // Route visitor sheets are extras, not part of the scene (#6). A pack with
            // the scene's seven sheets read "Partial · 7 of 10" for want of them.
            var visited=new ArtworkLibrary.Report(386,386,9,0,1,16,0,java.util.List.of(),2).categories().get(3);
            check(visited.describe().equals("Ready · 7 of 7 sheets · 2 route visitors"),"visitors are counted as extras: "+visited.describe());
            check(new ArtworkLibrary.Report(0,0,3,0,0,0,0,java.util.List.of(),3).categories().get(3).readiness()==ArtworkLibrary.Readiness.MISSING,
                "visitors alone are not a scene");
            Path scenePack=Files.createDirectories(dir.resolve("scene"));
            for(String sheet:new String[]{"brendan","brendan-running","may","may-running","tree","rock","grass","route-cyclist"})
                Files.write(scenePack.resolve(sheet+".png"),image());
            ArtworkLibrary.install(scenePack);
            var installed=ArtworkLibrary.survey();
            check(installed.scene()==7&&installed.cameos()==1,"an installed scene and its visitor are told apart: "+installed);
            check(installed.categories().get(3).describe().equals("Ready · 7 of 7 sheets · 1 route visitor"),
                "so a complete scene reads Ready: "+installed.categories().get(3).describe());
        } finally {
            System.setProperty("user.home",home);
            try(var walk=Files.walk(sandbox)) {
                for(var p:walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        try(var walk=Files.walk(dir)) {
            for(var p:walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
        String supplied = System.getProperty("yoru.test.game-folder");
        if (supplied != null) {
            Path gameHome = Files.createTempDirectory("yoru-game-home-");
            String previousHome = System.getProperty("user.home");
            System.setProperty("user.home", gameHome.toString());
            try {
                var game = ArtworkLibrary.install(Path.of(supplied));
                check(game.games() == 1, "duplicate nested copies resolve to one game");
                check(game.species()==386 && game.shiny()==386,"the supplied game produces all normal and shiny sprites");
                check(Files.isRegularFile(ArtworkLibrary.root().resolve("games/emerald-national-dex.gba")),"archives retain a usable game filename");
                try (var games = Files.list(ArtworkLibrary.root().resolve("games"))) {
                    check(games.findAny().isPresent(), "the accepted game is retained in the local library");
                }
            } finally {
                System.setProperty("user.home", previousHome);
                try(var walk=Files.walk(gameHome)) {
                    for(var p:walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        }
        System.out.println("PASS: "+checks+" artwork checks (naming, nesting, path escapes, zip import)");
    }
}
