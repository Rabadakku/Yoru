package dev.yoru.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Cosmetic route visitors. Owns no clock, timer, reward ledger or saved game state. */
final class RouteCameos {
    static final int PERIOD=6000, DURATION=180, EDGE=32, SCALE=2;
    enum Kind { TRAINER, CYCLIST, ROCKET }
    private record Actor(Kind kind,BufferedImage sheet,boolean nativeTrainer) {}
    private final List<Actor> actors;

    RouteCameos(String character) {
        var found=new ArrayList<Actor>();
        var trainer=SpriteAssets.load("route-trainer.png");
        if(validStrip(trainer))found.add(new Actor(Kind.TRAINER,trainer,false));
        else {
            // Existing personal packs can supply a passer-by without a new download.
            var other=SpriteAssets.load("may".equals(character)?"brendan.png":"may.png");
            if(other!=null&&other.getWidth()>=144&&other.getHeight()>=32)
                found.add(new Actor(Kind.TRAINER,other,true));
        }
        add(found,Kind.CYCLIST,"route-cyclist.png");
        add(found,Kind.ROCKET,"team-rocket.png");
        actors=List.copyOf(found);
    }
    private static void add(List<Actor> actors,Kind kind,String name) {
        var sheet=SpriteAssets.load(name);
        if(validStrip(sheet))actors.add(new Actor(kind,sheet,false));
    }
    static boolean isArtwork(String name) {
        return name.equals("route-trainer.png")||name.equals("route-cyclist.png")||name.equals("team-rocket.png");
    }
    static boolean validStrip(BufferedImage sheet) {
        return sheet!=null&&sheet.getHeight()==EDGE&&sheet.getWidth()>=EDGE
            &&sheet.getWidth()<=EDGE*8&&sheet.getWidth()%EDGE==0;
    }
    /** One ground visitor per seven minutes of active frames, clear of the sky sighting. */
    static int age(long frame) {
        long cycle=Math.floorDiv(frame,PERIOD);
        int start=900+(int)Math.floorMod(cycle*137,1200);
        long age=Math.floorMod(frame,PERIOD)-start;
        return age>=0&&age<DURATION?(int)age:-1;
    }
    private Actor actor(long frame) {
        return age(frame)<0||actors.isEmpty()?null:actors.get((int)Math.floorMod(Math.floorDiv(frame,PERIOD),actors.size()));
    }
    Kind kind(long frame) {var actor=actor(frame);return actor==null?null:actor.kind();}
    static boolean eastbound(long frame) {return Math.floorMod(Math.floorDiv(frame,PERIOD),2)==0;}
    static int x(long frame,int width) {
        int age=age(frame);
        long position=(width+128L)*age/(DURATION-1)-64;
        return (int)(eastbound(frame)?position:width-64-position);
    }
    void paint(Graphics2D g,long frame,int width,int feet) {
        var actor=actor(frame);if(actor==null)return;
        int x=x(frame,width),age=age(frame);
        g.setColor(Theme.ROUTE_SHADOW);g.fillOval(x+12,feet-5,40,7);
        int sx,sourceWidth;
        if(actor.nativeTrainer()) {
            int[] gait={7,2,8,2};sourceWidth=16;sx=gait[(age/3)%gait.length]*16;
        } else {sourceWidth=EDGE;sx=((age/5)%(actor.sheet().getWidth()/EDGE))*EDGE;}
        // All custom strips face right. The supplied native trainer side frames face left.
        boolean flip=eastbound(frame)==actor.nativeTrainer();
        int drawnWidth=sourceWidth*SCALE;
        int left=x+(64-drawnWidth)/2;
        g.drawImage(actor.sheet(),flip?left+drawnWidth:left,feet-64,
            flip?left:left+drawnWidth,feet,sx,0,sx+sourceWidth,EDGE,null);
    }
    String description(long frame) {
        var kind=kind(frame);
        return kind==null?"":switch(kind) {
            case TRAINER -> "A trainer is passing on the route.";
            case CYCLIST -> "A cyclist is passing on the route.";
            case ROCKET -> "Team Rocket: Wrong route again! Cosmetic cameo only.";
        };
    }
    /** Paint after night tint so the brief joke stays readable in every theme. */
    void caption(Graphics2D g,long frame,int width,int feet) {
        int age=age(frame);
        if(kind(frame)!=Kind.ROCKET||age<35||age>145||width<220)return;
        String text="Wrong route again!";
        g.setFont(Theme.captionFont());int boxWidth=g.getFontMetrics().stringWidth(text)+20;
        int left=Math.max(6,Math.min(width-boxWidth-6,x(frame,width)+32-boxWidth/2));
        int top=feet-101;
        g.setColor(Theme.PANEL);g.fillRoundRect(left,top,boxWidth,32,Theme.RADIUS,Theme.RADIUS);
        g.setColor(Theme.LINE);g.drawRoundRect(left,top,boxWidth,32,Theme.RADIUS,Theme.RADIUS);
        g.setColor(Theme.CYAN);g.setFont(Theme.captionFont());g.drawString("TEAM ROCKET",left+10,top+12);
        g.setColor(Theme.TEXT);g.setFont(Theme.captionFont());g.drawString(text,left+10,top+25);
    }
}
