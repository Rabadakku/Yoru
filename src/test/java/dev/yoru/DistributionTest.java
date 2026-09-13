package dev.yoru;

import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/**
 * What the shipped jar must not contain (#16).
 *
 * SHARED_WORKSPACE.md says release jars must not carry the personal artwork or
 * the game files. "We did not mean to include them" is not a guarantee, so this
 * opens the jar the build actually produced and looks.
 *
 * The one exception is the app's own bundled portraits under
 * {@code dev/yoru/waifu/}: original art made for Yoru, not the owner's files and
 * not game sprites, so they are meant to ship. Everything else with an image
 * suffix — or a save, ROM, vault or key — is still a mistake, not a feature.
 *
 * The failure this prevents is quiet and expensive: a jar handed to someone else
 * with a few hundred sprites inside it, which is a licence problem rather than a
 * bug, and which no amount of testing the application would ever surface.
 */
public final class DistributionTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    /** Anything here in a shipped jar is a mistake, not a feature. */
    private static final List<String> FORBIDDEN_SUFFIXES=
        List.of(".png",".jpg",".jpeg",".gif",".webp",".bmp",
                ".gba",".sav",".rom",".zip",
                ".vault",".local-key",".bak",
                ".mp3",".ogg",".wav");
    private static final List<String> FORBIDDEN_PREFIXES=List.of("art/","game-files/","shiny/","pc/");
    /** The app's own bundled portraits — original art that is meant to ship. */
    private static final String BUNDLED_WAIFU = "dev/yoru/waifu/";

    /** A bundled portrait is the one kind of image the jar is supposed to carry. */
    private static boolean bundledWaifu(String name) {
        return name.startsWith(BUNDLED_WAIFU) && name.endsWith(".png");
    }

    public static void main(String[] args)throws Exception{
        Path jar=Path.of(args.length>0?args[0]:"build/yoru.jar");
        check(Files.exists(jar),"The build produced a jar at "+jar);

        var offenders=new ArrayList<String>();
        var classes=new HashSet<String>();
        long bytes=0,portraitBytes=0;
        try(var file=new JarFile(jar.toFile())) {
            for(var entries=file.entries();entries.hasMoreElements();) {
                var entry=entries.nextElement();
                String name=entry.getName();
                if(entry.isDirectory()) continue;
                if(bundledWaifu(name))portraitBytes+=Math.max(0,entry.getSize());
                else bytes+=Math.max(0,entry.getSize());
                if(name.endsWith(".class")) classes.add(name);
                if(bundledWaifu(name)) continue;   // the app's own art is a feature, not a leak
                String lower=name.toLowerCase(Locale.ROOT);
                for(String suffix:FORBIDDEN_SUFFIXES) if(lower.endsWith(suffix)) offenders.add(name);
                for(String prefix:FORBIDDEN_PREFIXES) if(lower.startsWith(prefix)) offenders.add(name);
            }
            var manifest=file.getManifest();
            check(manifest!=null,"The jar has a manifest");
            check("dev.yoru.ui.YoruApp".equals(manifest.getMainAttributes().getValue("Main-Class")),
                "The jar starts the application, not a test harness");
        }

        check(offenders.isEmpty(),"A shipped jar must carry no leaked artwork, game files or vaults — found "+offenders);
        check(!classes.isEmpty(),"The jar carries the application");

        // The whitelist is exact: a portrait is allowed only as a PNG in the
        // one bundled directory, never as a sprite, save or other suffix.
        check(bundledWaifu("dev/yoru/waifu/hikari.png"),"a bundled portrait is allowed");
        check(!bundledWaifu("dev/yoru/waifu/hikari.jpg"),"only PNG portraits are bundled");
        check(!bundledWaifu("emerald/route101.png"),"a game sprite is not a bundled portrait");
        check(!bundledWaifu("dev/yoru/waifu/save.sav"),"a save is never a portrait");

        // The application is all of itself, not a partial build.
        for(String required:new String[]{
            "dev/yoru/ui/YoruApp.class","dev/yoru/ui/VaultLauncher.class",
            "dev/yoru/persistence/EncryptedVault.class","dev/yoru/persistence/PortableVault.class",
            "dev/yoru/domain/Model.class","dev/yoru/application/Tracker.class",
            "dev/yoru/ui/Logo.class","dev/yoru/application/Encounters.class"})
            check(classes.contains(required),"The jar carries "+required);

        // No test classes ride along.
        var tests=classes.stream().filter(n->n.endsWith("Test.class")||n.endsWith("Preview.class")).toList();
        check(tests.isEmpty(),"A shipped jar carries no tests or preview harnesses — found "+tests);

        // Sized like code. A jar carrying 386 sprites would be several megabytes,
        // so this catches a bundling accident even if it dodges the name checks.
        check(bytes<4_000_000,"The jar is code-sized, not asset-sized — uncompressed "+bytes+" bytes");
        check(portraitBytes<8_000_000,"Original companion art stays within its separate package budget");

        // And it degrades honestly with no artwork at all, which is how it ships.
        var report=new dev.yoru.assets.ArtworkLibrary.Report(0,0,0,0,0);
        check(report.empty(),"No artwork reads as empty");
        check(report.summary().toLowerCase(Locale.ROOT).contains("dex numbers"),
            "The empty state says what happens instead, not just that something is missing");

        System.out.println("PASS: "+checks+" distribution checks (bundled art only, no game files, no tests, runnable)");
    }
}
