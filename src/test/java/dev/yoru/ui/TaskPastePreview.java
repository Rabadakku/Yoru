package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

public final class TaskPastePreview {
    private static void layout(Container c) {c.doLayout();for(var child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args.length==0?"build/paste-preview":args[0]);Files.createDirectories(out);
        SwingUtilities.invokeAndWait(()->{
            try {
                for(var theme:ThemeId.values()) {
                    Theme.apply(theme);
                    var form=new TaskPastePanel();
                    var pane=new JOptionPane(form,JOptionPane.PLAIN_MESSAGE,JOptionPane.DEFAULT_OPTION,null,new String[]{"Review tasks","Cancel"},"Review tasks");
                    pane.setSize(pane.getPreferredSize());layout(pane);
                    var image=new BufferedImage(pane.getWidth(),pane.getHeight(),BufferedImage.TYPE_INT_RGB);
                    var g=image.createGraphics();pane.paint(g);g.dispose();ImageIO.write(image,"png",out.resolve(theme.name()+".png").toFile());
                }
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }
}
