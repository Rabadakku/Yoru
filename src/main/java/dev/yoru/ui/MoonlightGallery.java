package dev.yoru.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/** Original portrait gallery, exclusive to the illustrated theme. No game assets. */
final class MoonlightGallery extends JPanel {
    private final BufferedImage first;
    private final BufferedImage second = WaifuCatalog.load("amberglow");

    MoonlightGallery(String choice) {
        first = WaifuCatalog.imagesFor(choice).getFirst();
        setName("moonlight.gallery");
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setPreferredSize(new Dimension(600, 180));
        setMinimumSize(new Dimension(0, 180));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        getAccessibleContext().setAccessibleName("Moonlight original companion illustrations");
    }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.clip(new java.awt.geom.RoundRectangle2D.Double(0,0,getWidth(),getHeight(),20,20));
            int half = (getWidth()-12)/2;
            portrait(g,first,0,half);
            portrait(g,second,half+12,getWidth()-half-12);
        } finally { g.dispose(); }
    }

    private void portrait(Graphics2D g,BufferedImage image,int x,int width) {
        if(image==null || width<=0)return;
        var pane=(Graphics2D)g.create(x,0,width,getHeight());
        try {
            pane.setColor(Theme.PANEL);pane.fillRect(0,0,width,getHeight());
            double scale=Math.max(width/(double)image.getWidth(),getHeight()/(double)image.getHeight());
            int w=(int)Math.ceil(image.getWidth()*scale),h=(int)Math.ceil(image.getHeight()*scale);
            pane.drawImage(image,(width-w)/2,(int)((getHeight()-h)*.12),w,h,null);
        } finally {pane.dispose();}
    }
}
