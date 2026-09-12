package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Contact sheet for visual review; optional personal artwork stays in ignored build/. */
public final class BuddyPreview {
    public static void main(String[] args)throws Exception {
        Path target=Path.of(args.length==0?"build/buddy-preview.png":args[0]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        var image=new BufferedImage(850,4*222,BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(()->{
            var g=image.createGraphics();
            int y=0;
            for(var theme:ThemeId.values()) {
                Theme.apply(theme);g.setColor(Theme.BG);g.fillRect(0,y,850,222);
                g.setFont(Theme.mono(12));g.setColor(Theme.TEXT);g.drawString(theme.name()+" / RESTING · STUDYING · NO COMPANION",16,y+20);
                var scenes=new BuddyScene[]{new BuddyScene(0),new BuddyScene(2,true),new BuddyScene(-1)};
                for(int i=0;i<scenes.length;i++) {
                    if(i==1)for(int frame=0;frame<9;frame++)scenes[i].advance(true);
                    scenes[i].setSize(260,168);
                    var child=g.create(16+i*282,y+36,260,168);scenes[i].paint(child);child.dispose();
                }
                y+=222;
            }
            g.dispose();
        });
        ImageIO.write(image,"png",target.toFile());
    }
}
