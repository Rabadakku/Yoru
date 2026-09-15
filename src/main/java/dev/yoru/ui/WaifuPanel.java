package dev.yoru.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The decorative waifu panel on the Today page.
 *
 * Tracker-only ornament: Settings picks one of the bundled portraits, or
 * rotates through them all, and this shows the choice beside the timer. The
 * art is original and ships with the app, so the stored choice travels with
 * the vault and every machine shows the same picture. Nothing here is game
 * artwork, and nothing is copied into the vault — the id alone points at the
 * bundled image.
 */
final class WaifuPanel extends JPanel {
    /** One image stays up for a few seconds: long enough to read, short enough to notice. */
    private static final int INTERVAL_MS = 60000;
    /** What the panel says when no waifu is chosen, in every empty case. */
    static final String HINT = "Choose a waifu in Settings";
    static final String HINT_NAME = "waifu.hint";
    static final String PANEL_NAME = "waifu.panel";
    static final String ART_NAME = "waifu.art";

    private final List<BufferedImage> images;
    private final Timer cycle;
    private final Art art = new Art();
    private int index;

    WaifuPanel(String choice) {
        super(new BorderLayout());
        setOpaque(false);
        setAlignmentX(0);
        setName(PANEL_NAME);
        getAccessibleContext().setAccessibleName("Waifu panel");
        images = WaifuCatalog.imagesFor(choice);
        cycle = new Timer(INTERVAL_MS, event -> advance());
        cycle.setRepeats(true);

        var card = Theme.card();
        card.add(Theme.sectionHeader("YOUR COMPANION"));
        Theme.gap(card, Theme.SPACE_MD);
        if (images.isEmpty()) {
            var hint = Theme.label(HINT, Theme.TYPE_BODY, Theme.MUTED);
            hint.setName(HINT_NAME);
            card.add(hint);
        } else {
            card.setBorder(BorderFactory.createEmptyBorder());
            card.removeAll();
            card.add(art);
            // Only a choice with somewhere to go offers the click, so the
            // cursor never promises a change that cannot happen.
            if (images.size() > 1) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText("Click for the next image");
                var next = new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent event) { advance(); }
                };
                addMouseListener(next);
                art.addMouseListener(next);
                setFocusable(true);
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "next");
                getActionMap().put("next", new AbstractAction() {
                    public void actionPerformed(java.awt.event.ActionEvent e) { advance(); }
                });
            }
        }
        add(card, BorderLayout.CENTER);
        // The first image is already decoded: bundled art is small and read up
        // front, so there is nothing to wait for.
        if (!images.isEmpty()) show(index);
    }

    /** Whether the timer is running, so a test can see that a removed panel stopped. */
    boolean cycling() { return cycle.isRunning(); }

    /** What the panel is painting now, or null when nothing is chosen. */
    BufferedImage showing() { return art.image; }

    @Override public void addNotify() {
        super.addNotify();
        if (images.size()>1) cycle.start();
    }

    @Override public void removeNotify() {
        // Pages are rebuilt wholesale, so a panel taken off the page is thrown
        // away: its timer must not outlive the window with it.
        cycle.stop();
        super.removeNotify();
    }

    private void advance() {
        if (images.isEmpty()) return;
        index = (index + 1) % images.size();
        show(index);
    }

    private void show(int at) { art.set(images.get(at)); }

    /**
     * The art: scaled down to the card, centred, with the shared hairline and
     * never enlarged — a 48 px sprite stretched to fill a card is a smear.
     */
    private static final class Art extends JPanel {
        private BufferedImage image;

        Art() {
            setOpaque(false);
            setAlignmentX(0);
            setName(ART_NAME);
        }

        void set(BufferedImage next) {
            image = next;
            revalidate();
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            return image == null ? new Dimension(0, 0) : new Dimension(360, 440);
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null || getWidth() <= 0 || getHeight() <= 0) return;
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.clip(new java.awt.geom.RoundRectangle2D.Double(0,0,getWidth(),getHeight(),20,20));
                g.setColor(Theme.PANEL); g.fillRect(0,0,getWidth(),getHeight());
                boolean illustrated=image.getWidth()>256;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, illustrated
                    ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                double scale=illustrated ? Math.max(getWidth()/(double)image.getWidth(),getHeight()/(double)image.getHeight())
                    : Math.min(getWidth()*.65/image.getWidth(),getHeight()*.65/image.getHeight());
                int width=(int)Math.ceil(image.getWidth()*scale),height=(int)Math.ceil(image.getHeight()*scale);
                int x=(getWidth()-width)/2,y=illustrated?(int)((getHeight()-height)*.18):(getHeight()-height)/2;
                g.drawImage(image,x,y,width,height,null);
                g.setPaint(new GradientPaint(0,getHeight()*.52f,new Color(0,0,0,0),0,getHeight(),new Color(0,0,0,220)));
                g.fillRect(0,getHeight()/2,getWidth(),getHeight());
                g.setColor(Color.WHITE);
                g.setFont(Theme.sans(Theme.TYPE_CAPTION));g.drawString("Y O U R   Q U I E T   H O U R",24,getHeight()-66);
                g.setFont(Theme.sans(Theme.TYPE_FIGURE));g.drawString(illustrated?"Your next chapter.":"Here for your next chapter.",24,getHeight()-30);
                if(isFocusOwner()) {
                    g.setColor(Theme.CYAN);g.setStroke(new BasicStroke(3));
                    g.drawRoundRect(2,2,getWidth()-5,getHeight()-5,20,20);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
