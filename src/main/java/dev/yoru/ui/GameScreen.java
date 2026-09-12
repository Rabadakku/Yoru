package dev.yoru.ui;

import dev.yoru.game.GameSession;
import dev.yoru.game.LibretroCore;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * The Game tab's picture: the running game, drawn and driven.
 *
 * Nothing here calls the core directly — {@link GameSession} owns that on its
 * own thread, and this only reads the last published frame and forwards key
 * presses. Keeping the boundary that sharp is what stops a repaint from
 * reaching into a single-threaded C library.
 *
 * Scaling is integer-only where it fits. A 240x160 picture stretched to a
 * fractional multiple turns crisp pixel art into a shimmer, and the whole point
 * of running the original is that it looks like the original.
 */
final class GameScreen extends JComponent {

    /** Keyboard to joypad, in the arrangement an emulator player expects. */
    static final Map<Integer,Integer> BINDINGS = Map.of(
        KeyEvent.VK_UP, LibretroCore.UP,
        KeyEvent.VK_DOWN, LibretroCore.DOWN,
        KeyEvent.VK_LEFT, LibretroCore.LEFT,
        KeyEvent.VK_RIGHT, LibretroCore.RIGHT,
        KeyEvent.VK_X, LibretroCore.A,
        KeyEvent.VK_Z, LibretroCore.B,
        KeyEvent.VK_ENTER, LibretroCore.START,
        KeyEvent.VK_SHIFT, LibretroCore.SELECT,
        KeyEvent.VK_A, LibretroCore.L,
        KeyEvent.VK_S, LibretroCore.R);

    private final GameSession session;
    private final Timer repainter;
    /**
     * Shown over the picture until the first key is pressed, then never again.
     *
     * Not until the picture takes the keyboard: it takes the keyboard as soon
     * as the page opens, so clearing it there erased the reminder before it
     * could be read. The first real press is the moment it has stopped being
     * news.
     */
    private String hint;

    GameScreen(GameSession session) {
        this.session = session;
        setOpaque(true);
        setFocusable(true);
        setPreferredSize(new Dimension(240 * 3, 160 * 3));
        setBackground(Color.BLACK);

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { hold(e, true); }
            @Override public void keyReleased(KeyEvent e) { hold(e, false); }
        });
        // Clicking the picture is how you give it the keyboard, which is worth
        // making explicit because a focusless emulator looks broken.
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); }
        });

        repainter = new Timer(16, e -> repaint());
        repainter.start();
    }

    private void hold(KeyEvent e, boolean down) {
        Integer button = BINDINGS.get(e.getKeyCode());
        if (button != null) {
            // A key that reaches the game is the reminder's last useful
            // moment; anything else (a modifier, a tab key) leaves it up.
            if (down && hint != null) { hint = null; repaint(); }
            session.press(button, down);
            e.consume();
        }
    }

    /** The largest whole-number scale that fits, never smaller than one. */
    static int scaleFor(int available, int natural) {
        return Math.max(1, available / Math.max(1, natural));
    }

    /** Where the picture sits, centred, at whole-pixel scale. */
    static Rectangle placement(int boxW, int boxH, int gameW, int gameH) {
        int scale = Math.min(scaleFor(boxW, gameW), scaleFor(boxH, gameH));
        int w = gameW * scale, h = gameH * scale;
        return new Rectangle((boxW - w) / 2, (boxH - h) / 2, w, h);
    }

    void stop() { repainter.stop(); }

    /**
     * The keyboard reminder, drawn over the picture until the first key press.
     *
     * A footer paragraph under the frame is read after the fact, if at all; a
     * caption on the thing you are about to type into is read before. A
     * newline in the text starts a second line, so the whole reminder fits the
     * picture on a narrow window rather than running off its edge.
     */
    void setHint(String hint) { this.hint = hint; repaint(); }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());

        var frame = session.frame();
        if (frame == null) {
            g.setColor(Theme.MUTED);
            g.setFont(Theme.headingFont());
            g.drawString("Starting…", Theme.SPACE_LG, Theme.SPACE_XL + Theme.SPACE_SM);
            g.dispose();
            return;
        }
        var at = placement(getWidth(), getHeight(), frame.getWidth(), frame.getHeight());
        // Nearest neighbour on purpose: this is pixel art at an exact multiple.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(frame, at.x, at.y, at.width, at.height, null);
        if (hint != null) {
            g.setFont(Theme.bodyFont());
            var metrics = g.getFontMetrics();
            var lines = hint.split("\n", -1);
            int lineHeight = metrics.getHeight();
            int widest = 0;
            for (var line : lines) widest = Math.max(widest, metrics.stringWidth(line));
            int w = widest + 2 * Theme.SPACE_MD;
            int h = lines.length * lineHeight + Theme.SPACE_SM;
            int x = Math.max(0, (getWidth() - w) / 2), y = Math.max(0, getHeight() - h - Theme.SPACE_LG);
            g.setColor(Theme.BG);
            g.fillRoundRect(x, y, w, h, Theme.RADIUS, Theme.RADIUS);
            g.setColor(Theme.LINE);
            g.drawRoundRect(x, y, w, h, Theme.RADIUS, Theme.RADIUS);
            g.setColor(Theme.TEXT);
            for (int i = 0; i < lines.length; i++)
                g.drawString(lines[i], x + Theme.SPACE_MD,
                    y + Theme.SPACE_XS + metrics.getAscent() + i * lineHeight);
        }
        g.dispose();
    }
}
