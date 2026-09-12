package dev.yoru.ui;
import dev.yoru.game.SpeciesNames;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * The companion studying beside the timer: the lead of the game's party.
 * Motion follows the recording state.
 */
final class BuddyScene extends JPanel {
    private final int national;
    private final boolean shiny;
    private final String name;
    private final BufferedImage sprite;
    private int frame;
    private boolean active;

    BuddyScene(int national) { this(national,false,null); }

    BuddyScene(int national,boolean shiny) { this(national,shiny,null); }

    /** A companion by National Dex number, or none when it is 0 or less; {@code name} is its nickname, if any. */
    BuddyScene(int national,boolean shiny,String name) {
        this.national=Math.max(0,national);
        this.shiny=this.national>0&&shiny;
        this.name=this.national==0?null:name!=null&&!name.isBlank()?name:SpeciesNames.of(this.national);
        sprite=GameView.sprite(this.national,this.shiny);
        setPreferredSize(new Dimension(260,168));
        setMinimumSize(new Dimension(150,140));
        getAccessibleContext().setAccessibleName(this.national==0?"No companion yet"
            :(this.shiny?"Shiny ":"")+this.name+", your companion");
        updateDescription();
    }

    void advance(boolean recording) {
        recording=recording&&national>0;
        boolean changed=active!=recording;
        active=recording;
        if(recording)frame=(frame+1)%100_000;
        if(changed)updateDescription();
        if(recording||changed)repaint();
    }

    private String message() {
        return national==0?"Choose your starter in the game.":active?"Studying together.":"Ready when you are.";
    }

    private void updateDescription() {
        getAccessibleContext().setAccessibleDescription(message()
            +(national>0&&sprite==null?" Artwork unavailable; showing National Dex number.":""));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var g=(Graphics2D)graphics.create();
        try {
            int w=getWidth(),h=getHeight();
            if(w<1||h<1)return;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int dialogueY=Math.max(32,h-36);

            g.setColor(Theme.BG);
            g.fillRect(0,0,w,h);
            g.setColor(Theme.PANEL);
            g.fillRect(1,32,Math.max(0,w-2),Math.max(0,dialogueY-32));
            g.setColor(Theme.LINE);
            g.drawLine(1,dialogueY-24,w-2,dialogueY-24);
            int cx=w*3/5,base=dialogueY-10;
            int arenaWidth=Math.min(168,Math.max(40,w-24));
            g.setColor(Theme.LINE);
            g.fillOval(cx-arenaWidth/2,base-12,arenaWidth,20);
            g.setColor(Theme.DISABLED_FILL);
            g.fillOval(cx-arenaWidth/2+4,base-12,arenaWidth-8,14);

            // Breathing and a small hop while studying; still while resting.
            int hopPhase=frame%70;
            int bob=active?(int)Math.round(Math.sin(frame*.30)*2):0;
            int hop=active&&hopPhase<12?-(int)Math.round(Math.sin(hopPhase/12.0*Math.PI)*8):0;
            int sway=active?(int)Math.round(Math.sin(frame*.14)*2):0;
            g.setColor(Theme.LINE);
            int shadow=Math.max(24,50-Math.abs(hop)*2);
            g.fillOval(cx-shadow/2+sway,base-4,shadow,7);

            int size=Math.max(1,Math.min(128,Math.min(w-32,dialogueY-52)));
            if(sprite!=null) {
                // Preserve the aspect ratio of user artwork, including non-square imports.
                double scale=Math.min((double)size/sprite.getWidth(),(double)size/sprite.getHeight());
                int sw=Math.max(1,(int)Math.round(sprite.getWidth()*scale));
                int sh=Math.max(1,(int)Math.round(sprite.getHeight()*scale));
                g.drawImage(sprite,cx-sw/2+sway,base-sh+bob+hop,sw,sh,null);
            } else {
                g.setColor(Theme.TEXT);
                g.setFont(Theme.mono(20));
                String text=national==0?"?":"#"+national;
                g.drawString(text,cx-g.getFontMetrics().stringWidth(text)/2+sway,base-14+bob+hop);
            }

            if(shiny) {
                g.setColor(Theme.GOLD);
                int offset=active?(frame/3)%3:0;
                for(int i=0;i<3;i++) {
                    int x=cx-size/2+i*size/2,y=base-size+12+((i+offset)%3)*12;
                    g.fillRect(x-3,y,7,1);
                    g.fillRect(x,y-3,1,7);
                }
            }

            int plateWidth=Math.min(w-8,188);
            var plate=new Polygon(new int[]{1,plateWidth,plateWidth-10,1},new int[]{1,1,30,30},4);
            g.setColor(Theme.BG);g.fillPolygon(plate);
            g.setColor(Theme.LINE);g.drawPolygon(plate);
            g.setColor(Theme.TEXT);g.setFont(Theme.mono(w<190?11:12));
            g.drawString(name==null?"YOUR COMPANION":name.toUpperCase(java.util.Locale.ROOT),10,20);
            g.setColor(Theme.CYAN);g.fillRect(1,1,3,28);

            g.setColor(Theme.BG);g.fillRect(0,dialogueY,w,h-dialogueY);
            g.setColor(Theme.LINE);g.drawRect(4,dialogueY+2,Math.max(0,w-9),Math.max(0,h-dialogueY-7));
            g.setColor(Theme.TEXT);g.setFont(Theme.mono(w<190?10:11));
            g.drawString(message(),12,dialogueY+21);
            g.setColor(Theme.LINE);g.drawRect(0,0,w-1,h-1);
        } finally {
            g.dispose();
        }
    }
}
