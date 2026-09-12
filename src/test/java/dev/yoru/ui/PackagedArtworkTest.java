package dev.yoru.ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URLClassLoader;
import java.nio.file.*;

/** Uses an isolated jar loader: discovery must work independently of the shell's cwd. */
public final class PackagedArtworkTest {
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("--check-local-pack")) {
            var report=SpriteAssets.survey();
            if(report.species()!=386 || report.shiny()!=386 || report.sheets()!=7)
                throw new AssertionError(report.summary());
            var canvas=new BufferedImage(560,180,BufferedImage.TYPE_INT_RGB);
            var normal=new BuddyScene(2,false);
            var shiny=new BuddyScene(2,true);
            normal.setSize(280,180);shiny.setSize(280,180);
            var g=canvas.createGraphics();normal.paint(g);g.translate(280,0);shiny.paint(g);g.dispose();
            ImageIO.write(canvas,"png",Path.of("build/artwork-preview/normal-and-shiny.png").toFile());
            System.out.println("PASS: all 779 local images decode; normal and shiny companions rendered");
            return;
        }
        Path sandbox=Files.createTempDirectory("yoru-artwork-test-");
        String home=System.getProperty("user.home");
        String override=System.getProperty("yoru.art.dir");
        try {
            System.setProperty("user.home",sandbox.resolve("home").toString());
            System.clearProperty("yoru.art.dir");
            Path art=Files.createDirectories(sandbox.resolve("art/shiny"));
            var normal=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
            normal.setRGB(8,8,0xffff0000);
            ImageIO.write(normal,"png",art.getParent().resolve("1.png").toFile());
            normal.setRGB(8,8,0xff0000ff);
            ImageIO.write(normal,"png",art.resolve("1.png").toFile());
            for(String relative:new String[]{"Yoru.jar","build/yoru.jar","Yoru.app/Contents/Resources/Yoru.jar"}) {
                Path jar=sandbox.resolve(relative);
                Files.createDirectories(jar.getParent());
                Files.copy(Path.of("build/yoru.jar"),jar);
                try(var loader=new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                    var method=loader.loadClass("dev.yoru.ui.SpriteAssets").getDeclaredMethod("load",String.class);
                    method.setAccessible(true);
                    var regular=(BufferedImage)method.invoke(null,"1.png");
                    var shiny=(BufferedImage)method.invoke(null,"shiny/1.png");
                    if(regular==null || regular.getRGB(8,8)!=0xffff0000)
                        throw new AssertionError("Missing normal artwork: "+relative);
                    if(shiny==null || shiny.getRGB(8,8)!=0xff0000ff)
                        throw new AssertionError("Missing shiny artwork: "+relative);
                }
            }
            System.out.println("PASS: normal and shiny artwork from standalone jar, build folder and macOS bundle");
        } finally {
            System.setProperty("user.home",home);
            if(override==null)System.clearProperty("yoru.art.dir");else System.setProperty("yoru.art.dir",override);
            try(var paths=Files.walk(sandbox)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }
    }
}
