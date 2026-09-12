package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.domain.Model.ThemeId;
import dev.yoru.importer.NotionFixture;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;

/** Renders the Notion import review in every theme, for the polish pass to look at. */
public final class NotionImportPreview {
    private static void layout(Container c) {c.doLayout();for(var child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args.length==0?"build/notion-preview":args[0]);Files.createDirectories(out);
        SwingUtilities.invokeAndWait(()->{
            try {
                for(var theme:ThemeId.values()) {
                    Theme.apply(theme);
                    var form=new NotionImportPanel(new Tracker(vault(),Clock.systemUTC()));
                    form.load(NotionFixture.zip(),"Study Tasks.zip");
                    var pane=new JOptionPane(form,JOptionPane.PLAIN_MESSAGE,JOptionPane.DEFAULT_OPTION,null,new String[]{"Import tasks","Cancel"},"Import tasks");
                    pane.setSize(pane.getPreferredSize());layout(pane);
                    var image=new BufferedImage(pane.getWidth(),pane.getHeight(),BufferedImage.TYPE_INT_RGB);
                    var g=image.createGraphics();pane.paint(g);g.dispose();ImageIO.write(image,"png",out.resolve(theme.name()+".png").toFile());
                }
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }

    /** A vault that goes nowhere: the preview renders the review, it does not keep it. */
    private static Repository vault() {
        return new Repository() {
            private State state=State.empty();
            public State load(){return state;}
            public void save(State next){state=next;}
            public void close()throws IOException{}
        };
    }
}
