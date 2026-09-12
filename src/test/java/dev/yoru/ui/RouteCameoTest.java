package dev.yoru.ui;

import dev.yoru.assets.ArtworkLibrary;
import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.*;

public final class RouteCameoTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static long moment(int cycle,int wantedAge) {
        for(long f=(long)cycle*RouteCameos.PERIOD;f<(long)(cycle+1)*RouteCameos.PERIOD;f++)
            if(RouteCameos.age(f)==wantedAge)return f;
        throw new AssertionError("No sighting in cycle");
    }
    private static BufferedImage render(TrainerScene scene,int width) {
        scene.setSize(width,TrainerScene.HEIGHT);
        var image=new BufferedImage(width,TrainerScene.HEIGHT,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();scene.paint(g);g.dispose();return image;
    }
    private static int[] pixels(BufferedImage image) {return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());}
    private static boolean contains(BufferedImage image,Color color) {return Arrays.stream(pixels(image)).anyMatch(p->p==color.getRGB());}
    public static void main(String[] args)throws Exception {
        for(int cycle=0;cycle<12;cycle++) {
            int visible=0;boolean overlaps=false;
            for(long f=(long)cycle*6000;f<(long)(cycle+1)*6000;f++) {
                if(RouteCameos.age(f)>=0) {visible++;overlaps|=TrainerScene.sightingFrame(f)>=0;}
            }
            check(visible==180,"One bounded ground cameo per cycle");
            check(!overlaps,"Ground cameo never crowds the legendary flyby");
            long start=moment(cycle,0),end=moment(cycle,179);
            check(RouteCameos.age(start-1)==-1&&RouteCameos.age(end+1)==-1,"No visitor outside interval");
            for(int width:new int[]{120,480,840}) {
                int from=RouteCameos.x(start,width),to=RouteCameos.x(end,width);
                check(Math.min(from,to)<=-64&&Math.max(from,to)>=width,"Enters and leaves off-screen at every size");
            }
        }
        check(!RouteCameos.validStrip(new BufferedImage(33,32,2)),"Reject partial animation frames");
        check(!RouteCameos.validStrip(new BufferedImage(64,64,2)),"Reject ambiguous two-row sheets");
        check(RouteCameos.validStrip(new BufferedImage(32,32,2)),"Accept still image");
        check(RouteCameos.validStrip(new BufferedImage(256,32,2)),"Accept eight-frame strip");
        var temp=Files.createTempDirectory("yoru-cameos-");
        String oldHome=System.getProperty("user.home"),oldArt=System.getProperty("yoru.art.dir");
        try {
            var fixtures=temp.resolve("fixtures");CameoFixtures.write(fixtures);
            System.setProperty("user.home",temp.resolve("home").toString());
            System.setProperty("yoru.art.dir",fixtures.toString());SpriteAssets.refresh();
            var zip=temp.resolve("cameos.zip");
            try(var out=new ZipOutputStream(Files.newOutputStream(zip))) {
                for(String name:CameoFixtures.NAMES) {
                    out.putNextEntry(new ZipEntry("nested/"+name.toUpperCase(java.util.Locale.ROOT)));
                    out.write(Files.readAllBytes(fixtures.resolve(name)));out.closeEntry();
                }
            }
            var report=ArtworkLibrary.install(zip);
            check(report.sheets()==3,"Folder names normalised on ZIP import");
            check(SpriteAssets.survey().sheets()==3,"Settings counts all three renderable sheets");
            for(String name:CameoFixtures.NAMES) {
                check(Files.isRegularFile(ArtworkLibrary.root().resolve(name)),"Imported visitor installed");
                check((SpriteAssets.load(name).getRGB(0,0)>>>24)==0,"Background remains transparent");
            }
            SwingUtilities.invokeAndWait(()->{
                for(var theme:ThemeId.values()) {
                    Theme.apply(theme);var cameos=new RouteCameos("brendan");
                    check(cameos.kind(0)==null,"No cameo at startup");
                    for(int cycle=0;cycle<3;cycle++) {
                        long f=moment(cycle,90);
                        check(cameos.kind(f)==RouteCameos.Kind.values()[cycle],"Available visitors rotate");
                        var scene=new TrainerScene("brendan",Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"),ZoneOffset.UTC));
                        for(long i=0;i<f;i++)scene.advance(true,60);
                        var image=render(scene,480);
                        check(contains(image,CameoFixtures.COLORS[cycle]),"Visitor rendered in actual route");
                        check(!scene.getAccessibleContext().getAccessibleDescription().isEmpty(),"Visitor identified accessibly");
                        scene.advance(false,60);var paused=pixels(render(scene,480));long travelled=scene.distance();
                        for(int i=0;i<50;i++)scene.advance(false,60);
                        check(Arrays.equals(paused,pixels(render(scene,480)))&&scene.distance()==travelled,"Pause/reduced motion freezes visitor and route");
                        for(int i=0;i<6;i++)scene.advance(true,60);
                        check(!Arrays.equals(paused,pixels(render(scene,480))),"Resume continues movement");
                        check(contains(render(scene,840),CameoFixtures.COLORS[cycle]),"Resize retains active visitor");
                    }
                    check(cameos.description(moment(2,90)).contains("Cosmetic"),"Rocket appearance does not claim a battle");
                }
            });
            // Missing and malformed optional files do not introduce placeholders or failures.
            for(String name:CameoFixtures.NAMES)Files.delete(fixtures.resolve(name));
            for(String name:CameoFixtures.NAMES)Files.delete(ArtworkLibrary.root().resolve(name));
            ImageIO.write(new BufferedImage(17,19,2),"png",fixtures.resolve("route-cyclist.png").toFile());
            SpriteAssets.refresh();var missing=new RouteCameos("brendan");
            check(missing.kind(moment(0,90))==null,"No malformed/missing visitors rendered");
            check(SpriteAssets.survey().sheets()==0,"Malformed visitor sheet is not counted as usable artwork");
            // Reuse the other trainer from an existing art pack when no custom strip exists.
            var nativeSheet=new BufferedImage(144,32,2);nativeSheet.setRGB(33,2,Color.BLUE.getRGB());
            ImageIO.write(nativeSheet,"png",fixtures.resolve("may.png").toFile());SpriteAssets.refresh();
            check(new RouteCameos("brendan").kind(moment(0,90))==RouteCameos.Kind.TRAINER,"Native trainer fallback available");
        } finally {
            System.setProperty("user.home",oldHome);
            if(oldArt==null)System.clearProperty("yoru.art.dir");else System.setProperty("yoru.art.dir",oldArt);
            SpriteAssets.refresh();
            try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        }
        System.out.println("PASS: "+checks+" cameo checks (rarity, themes, pause, resize, import and missing artwork)");
    }
}
