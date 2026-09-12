package dev.yoru.ui;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Original geometric test figures; no game artwork is part of these fixtures. */
final class CameoFixtures {
    static final String[] NAMES={"route-trainer.png","route-cyclist.png","team-rocket.png"};
    static final Color[] COLORS={new Color(0x247FE8),new Color(0xD7A013),new Color(0xE32D61)};
    static void write(Path folder)throws Exception {
        Files.createDirectories(folder);
        for(int kind=0;kind<NAMES.length;kind++) {
            var sheet=new BufferedImage(128,32,BufferedImage.TYPE_INT_ARGB);
            var g=sheet.createGraphics();
            for(int frame=0;frame<4;frame++) {
                int x=frame*32,step=frame%2*2;
                g.setColor(new Color(0x363341));
                if(kind==1) {
                    g.drawOval(x+2,22,9,9);g.drawOval(x+20,22,9,9);
                    g.drawLine(x+6,26,x+18,21);g.drawLine(x+18,21,x+25,26);
                } else {
                    g.fillRect(x+11-step,24,4,7);g.fillRect(x+19+step,24,4,7);
                }
                g.setColor(COLORS[kind]);g.fillRect(x+11,12,12,13);
                g.setColor(new Color(0xE7B58F));g.fillRect(x+13,5,10,9);g.fillRect(x+23,15,5,4);
                g.setColor(new Color(0x363341));g.fillRect(x+11,3,13,4);g.fillRect(x+23,7,2,2);
                if(kind==2) {g.setColor(Color.WHITE);g.fillRect(x+15,15,4,5);}
            }
            g.dispose();ImageIO.write(sheet,"png",folder.resolve(NAMES[kind]).toFile());
        }
    }
}
