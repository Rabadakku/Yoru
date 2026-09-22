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
 * Nothing with an image suffix belongs in the jar — nor a save, ROM, vault or
 * key. Yoru bundled its own companion portraits until 1.0.11, which is why the
 * jar once carried images at all; it ships none now.
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

    public static void main(String[] args)throws Exception{
        Path jar=Path.of(args.length>0?args[0]:"build/yoru.jar");
        check(Files.exists(jar),"The build produced a jar at "+jar);

        var offenders=new ArrayList<String>();
        var classes=new HashSet<String>();
        long bytes=0;
        try(var file=new JarFile(jar.toFile())) {
            for(var entries=file.entries();entries.hasMoreElements();) {
                var entry=entries.nextElement();
                String name=entry.getName();
                if(entry.isDirectory()) continue;
                bytes+=Math.max(0,entry.getSize());
                if(name.endsWith(".class")) classes.add(name);
                String lower=name.toLowerCase(Locale.ROOT);
                for(String suffix:FORBIDDEN_SUFFIXES) if(lower.endsWith(suffix)) offenders.add(name);
                for(String prefix:FORBIDDEN_PREFIXES) if(lower.startsWith(prefix)) offenders.add(name);
            }
            var manifest=file.getManifest();
            check(manifest!=null,"The jar has a manifest");
            check("dev.yoru.ui.YoruApp".equals(manifest.getMainAttributes().getValue("Main-Class")),
                "The jar starts the application, not a test harness");
        }

        check(offenders.isEmpty(),"A shipped jar must carry no leaked images, imports or vaults — found "+offenders);
        check(!classes.isEmpty(),"The jar carries the application");

        // The application is all of itself, not a partial build.
        for(String required:new String[]{
            "dev/yoru/ui/YoruApp.class","dev/yoru/ui/VaultLauncher.class",
            "dev/yoru/persistence/EncryptedVault.class","dev/yoru/persistence/PortableVault.class",
            "dev/yoru/domain/Model.class","dev/yoru/application/Tracker.class",
            "dev/yoru/ui/Logo.class","dev/yoru/ui/PagesPage.class","dev/yoru/pages/Markdown.class"})
            check(classes.contains(required),"The jar carries "+required);

        // No test classes ride along.
        var tests=classes.stream().filter(n->n.endsWith("Test.class")||n.endsWith("Preview.class")).toList();
        check(tests.isEmpty(),"A shipped jar carries no tests or preview harnesses — found "+tests);

        // Sized like code. A jar carrying 386 sprites would be several megabytes,
        // so this catches a bundling accident even if it dodges the name checks.
        check(bytes<4_000_000,"The jar is code-sized, not asset-sized — uncompressed "+bytes+" bytes");


        System.out.println("PASS: "+checks+" distribution checks (no artwork, no game files, no tests, runnable)");
    }
}
