package dev.yoru.ui;

import dev.yoru.assets.ArtworkLibrary;
import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.Comparator;

/** Route proof sheet using original geometric visitors; optional external scenery stays local. */
public final class RouteCameoPreview {
    public static void main(String[] args)throws Exception {
        var output=Path.of(args.length==0?"build/cameo-preview.png":args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        var temp=Files.createTempDirectory("yoru-cameo-preview-");String oldHome=System.getProperty("user.home");
        try {
            System.setProperty("user.home",temp.toString());CameoFixtures.write(ArtworkLibrary.root());SpriteAssets.refresh();
            var image=new BufferedImage(840,5*235,BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(()->{
                var g=image.createGraphics();
                for(int row=0;row<5;row++) {
                    Theme.apply(ThemeId.values()[row%4]);int kind=row==4?0:row%3;
                    if(row==4) {
                        try {Files.delete(ArtworkLibrary.root().resolve("route-trainer.png"));SpriteAssets.refresh();}
                        catch(Exception e){throw new RuntimeException(e);}
                    }
                    g.setColor(Theme.BG);g.fillRect(0,row*235,840,235);
                    g.setColor(Theme.TEXT);g.setFont(Theme.mono(12));
                    g.drawString(ThemeId.values()[row%4]+" / "+RouteCameos.Kind.values()[kind]+" / "+(row==2?"NIGHT":"DAY")+(row==4?" / NATIVE TRAINER FALLBACK":" / TEST ARTWORK"),12,row*235+20);
                    var scene=new TrainerScene("brendan",Clock.fixed(Instant.parse(row==2?"2026-09-09T23:00:00Z":"2026-09-09T12:00:00Z"),ZoneOffset.UTC));
                    for(long i=0;i<RouteCameoTest.moment(kind,55);i++)scene.advance(true,60);
                    scene.setSize(840,200);var child=g.create(0,row*235+30,840,200);scene.paint(child);child.dispose();
                }
                g.dispose();
            });
            ImageIO.write(image,"png",output.toFile());
        } finally {
            System.setProperty("user.home",oldHome);SpriteAssets.refresh();
            try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        }
    }
}
