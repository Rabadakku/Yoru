package dev.yoru.ui;

import javax.swing.*;
import java.awt.*;

/** A short, non-flashing reveal. The deterministic encounter is supplied by the caller. */
final class EncounterScene extends JPanel {
    private final BuddyScene buddy;
    private final Timer animation;
    private final Runnable onReveal;
    private final boolean reducedMotion;
    private int frame;
    private long started;
    EncounterScene(int species,boolean shiny,boolean reducedMotion,Runnable onReveal){
        this.reducedMotion=reducedMotion;this.onReveal=onReveal;
        buddy=new BuddyScene(species,shiny);
        setPreferredSize(new Dimension(360,200));
        getAccessibleContext().setAccessibleName("Encounter reveal");
        frame=reducedMotion?24:0;
        animation=new Timer(50,event->{
            advanceTo((int)((System.nanoTime()-started)/50_000_000));
            if(frame>=24)animationStop();
        });
    }
    private void animationStop(){animation.stop();}
    void advanceTo(int next){
        boolean revealed=frame>=24;
        frame=Math.max(frame,Math.min(24,next));
        if(!revealed&&frame==24)onReveal.run();
        repaint();
    }
    @Override public void addNotify(){
        super.addNotify();started=System.nanoTime();
        if(reducedMotion||frame>=24)onReveal.run();else animation.start();
    }
    @Override public void removeNotify(){animation.stop();super.removeNotify();}
    @Override protected void paintComponent(Graphics graphics){
        var g=(Graphics2D)graphics.create();
        try{
            buddy.setSize(getSize());buddy.paint(g);
            int curtain=(int)(getHeight()/2.0*(1-frame/24.0));
            if(curtain>0){
                g.setColor(Theme.ROUTE_LEAF);g.fillRect(0,0,getWidth(),curtain);
                g.fillRect(0,getHeight()-curtain,getWidth(),curtain);
                var grass=SpriteAssets.load("grass.png");
                if(grass!=null)for(int x=-8;x<getWidth();x+=28)
                    g.drawImage(grass,x,getHeight()-curtain-16,x+32,getHeight()-curtain+16,0,0,16,16,null);
            }
        }finally{g.dispose();}
    }
}
