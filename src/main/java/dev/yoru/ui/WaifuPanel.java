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
    private static final int INTERVAL_MS = 6000;
    /** Eight grid steps tall at most: bounded so the page never reflows around it. */
    private static final int MAX_ART_HEIGHT = Theme.SPACE_XXL * 8;
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
        card.add(Theme.sectionHeader("WAIFU"));
        Theme.gap(card, Theme.SPACE_MD);
        if (images.isEmpty()) {
            var hint = Theme.label(HINT, Theme.TYPE_BODY, Theme.MUTED);
            hint.setName(HINT_NAME);
            card.add(hint);
        } else {
            card.add(art);
            // Only a choice with somewhere to go offers the click, so the
            // cursor never promises a change that cannot happen.
            if (images.size() > 1) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText("Click for the next image");
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent event) { advance(); }
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
        if (!images.isEmpty()) cycle.start();
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
            if (image == null) return new Dimension(0, 0);
            int width = image.getWidth(), height = image.getHeight();
            if (height > MAX_ART_HEIGHT) {
                width = width * MAX_ART_HEIGHT / height;
                height = MAX_ART_HEIGHT;
            }
            return new Dimension(Math.max(1, width), Math.max(1, height));
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null || getWidth() <= 0 || getHeight() <= 0) return;
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                double scale = Math.min(1.0, Math.min(getWidth() / (double) image.getWidth(),
                    getHeight() / (double) image.getHeight()));
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                int x = (getWidth() - width) / 2, y = (getHeight() - height) / 2;
                g.drawImage(image, x, y, width, height, null);
                g.setColor(Theme.LINE);
                g.setStroke(new BasicStroke(Theme.HAIRLINE));
                g.drawRect(x, y, width - 1, height - 1);
            } finally {
                g.dispose();
            }
        }
    }
}
