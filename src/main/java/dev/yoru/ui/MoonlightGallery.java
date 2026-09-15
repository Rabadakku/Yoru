package dev.yoru.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.swing.JPanel;

/**
 * The Waifu theme's portrait strip above every page but Today (#23). Original
 * art only; no game assets.
 *
 * It used to pair the chosen portrait with Amberglow on every page, so the same
 * two pictures followed the player everywhere. The chosen portrait still leads,
 * and its partner now depends on the page: each page has a composition of its
 * own, and the same one on every visit. Rotate varies the lead by page too.
 */
final class MoonlightGallery extends JPanel {
    private final BufferedImage first;
    private final BufferedImage second;

    MoonlightGallery(String choice, String page) {
        var roster = WaifuCatalog.all();
        int lead = lead(choice, page, roster);
        first = WaifuCatalog.load(roster.get(lead).id());
        second = roster.size() < 2 ? null : WaifuCatalog.load(roster.get(partner(lead, page, roster.size())).id());
        setName("moonlight.gallery");
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setPreferredSize(new Dimension(600, 180));
        setMinimumSize(new Dimension(0, 180));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        getAccessibleContext().setAccessibleName("Original companion illustrations");
    }

    /** The chosen portrait's place in the roster; Rotate picks one by page, and anything else is the default. */
    static int lead(String choice, String page, List<WaifuCatalog.Waifu> roster) {
        for (int i = 0; i < roster.size(); i++) if (roster.get(i).id().equals(choice)) return i;
        if (WaifuCatalog.ROTATE.equals(choice)) return Math.floorMod(page.hashCode(), roster.size());
        return 0;
    }

    /** Another portrait, chosen by the page, and never the one leading. */
    static int partner(int lead, String page, int size) {
        return (lead + 1 + Math.floorMod(page.hashCode(), size - 1)) % size;
    }

    /** What the strip draws, lead first. */
    List<BufferedImage> shown() { return Stream.of(first, second).filter(Objects::nonNull).toList(); }

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
