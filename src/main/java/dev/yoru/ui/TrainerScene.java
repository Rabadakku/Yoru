package dev.yoru.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Fixed camera on a layered pixel route; world spacing is independent of window width. */
final class TrainerScene extends JPanel {
    private static final int FRAME_W=16,FRAME_H=32,SCALE=2,FACE_WEST=2;
    static final int HEIGHT=200;
    private static final int[] SIDE_CYCLE={7,FACE_WEST,8,FACE_WEST};
    static final long RUN_AFTER_SECONDS=25*60;
    private final BufferedImage walkSheet,runSheet,tree,rock,grass;
    private long frame,travel;
    private boolean moving,running;
    private int lightLevel=-1;
    private final java.time.Clock clock;
    private final BufferedImage[] visitors;
    private final RouteCameos cameos;
    private String cameoDescription="";

    TrainerScene(){this("brendan");}
    TrainerScene(String character){this(character,java.time.Clock.systemDefaultZone());}
    TrainerScene(String character,java.time.Clock clock){
        this.clock=clock;
        cameos=new RouteCameos(character);
        visitors=new BufferedImage[]{SpriteAssets.load("144.png"),SpriteAssets.load("145.png"),
            SpriteAssets.load("146.png"),SpriteAssets.load("249.png"),SpriteAssets.load("250.png")};
        walkSheet=SpriteAssets.load(character+".png");runSheet=SpriteAssets.load(character+"-running.png");
        tree=SpriteAssets.load("tree.png");rock=SpriteAssets.load("rock.png");grass=SpriteAssets.load("grass.png");
        setOpaque(true);setPreferredSize(new Dimension(360,HEIGHT));
        setMinimumSize(new Dimension(120,HEIGHT));setMaximumSize(new Dimension(Integer.MAX_VALUE,HEIGHT));
        getAccessibleContext().setAccessibleName("Centered trainer on a woodland trail; scenery moves only while recording");
    }
    void advance(boolean active,long elapsedSeconds){
        boolean nextRunning=active&&elapsedSeconds>=RUN_AFTER_SECONDS;
        boolean changed=moving!=active||running!=nextRunning;
        int nextLight=Math.round(nightOpacity(java.time.LocalTime.now(clock))*255);
        changed|=lightLevel!=nextLight;lightLevel=nextLight;
        moving=active;running=nextRunning;
        if(active){frame++;travel+=running?5:3;}
        String description=cameos.description(frame);
        if(!description.equals(cameoDescription)) {
            cameoDescription=description;getAccessibleContext().setAccessibleDescription(description);
        }
        if(active||changed)repaint();
    }
    static int trainerX(int width){return (width-FRAME_W*SCALE)/2;}
    long distance(){return travel;}
    boolean hasTrainerArtwork() {
        return walkSheet != null && walkSheet.getWidth() >= FRAME_W * 9 && walkSheet.getHeight() >= FRAME_H;
    }

    private static int variation(long cell,int salt,int bound){
        long bits=(cell+salt)*0x9E3779B97F4A7C15L;
        bits=(bits^(bits>>>30))*0xBF58476D1CE4E5B9L;
        return (int)Math.floorMod(bits^(bits>>>27),bound);
    }
    private void object(Graphics2D g,BufferedImage sheet,int x,int feet,int scale){
        if(sheet==null)return;
        // These sheets contain a 16x16 intact object followed by break frames.
        int edge=Math.min(16,Math.min(sheet.getWidth(),sheet.getHeight()));
        g.drawImage(sheet,x,feet-edge*scale,x+edge*scale,feet,0,0,edge,edge,null);
    }
    private void clouds(Graphics2D g,int width){
        long offset=travel/18;
        for(long cell=Math.floorDiv(offset,210)-1;cell*210-offset<width;cell++){
            int x=(int)(cell*210-offset),y=18+variation(cell,8,3)*8;
            g.setColor(Theme.ROUTE_HAZE);g.fillRect(x+6,y+16,68,4);
            g.setColor(Theme.ROUTE_CLOUD);g.fillRect(x,y+10,74,8);
            g.fillRect(x+10,y+4,52,12);g.fillRect(x+24,y,24,6);
        }
    }
    private void hills(Graphics2D g,int width,int horizon,int speed,Color color,int seed){
        long offset=travel/speed;g.setColor(color);
        for(int x=-4;x<width;x+=4){
            double world=(x+offset)*.008;
            int top=horizon+(int)(Math.sin(world+seed)*9+Math.sin(world*.47+seed)*8);
            top=Math.floorDiv(top,4)*4;g.fillRect(x,top,4,getHeight()-top);
        }
    }
    private void grove(Graphics2D g,int width,int baseline){
        long offset=travel/3;
        for(long cell=Math.floorDiv(offset,64)-1;cell*64-offset<width;cell++){
            if(variation(cell,17,5)==0)continue;
            int x=(int)(cell*64-offset)+variation(cell,2,14),y=baseline-variation(cell,4,3)*4;
            g.setColor(Theme.ROUTE_FOREST);g.fillRect(x+12,y-42,24,6);
            g.fillRect(x+4,y-36,40,12);g.fillRect(x,y-24,48,16);g.fillRect(x+8,y-8,32,4);
            g.setColor(Theme.ROUTE_TRUNK);g.fillRect(x+22,y-4,4,12);
        }
        offset=travel/2;
        for(long cell=Math.floorDiv(offset,82)-1;cell*82-offset<width;cell++){
            if(variation(cell,22,5)==0)continue;
            int x=(int)(cell*82-offset)+variation(cell,13,20);
            object(g,tree,x,baseline+14+variation(cell,19,3)*2,3);
        }
    }
    static float nightOpacity(java.time.LocalTime time){
        double hour=time.toSecondOfDay()/3600.0;
        if(hour<5||hour>=21)return .52f;
        if(hour<8)return (float)((8-hour)/3*.52);
        if(hour>=18)return (float)((hour-18)/3*.52);
        return 0;
    }
    /** One short sighting per seven-minute active cycle, with a varied offset; no rewards. */
    static int sightingFrame(long frame){
        long period=6000,cycle=Math.floorDiv(frame,period);
        int start=3400+variation(cycle,41,2100);
        long age=Math.floorMod(frame,period)-start;
        return age>=0&&age<110?(int)age:-1;
    }
    private void visitor(Graphics2D g,int width){
        int age=sightingFrame(frame);if(age<0)return;
        var sprite=visitors[variation(frame/6000,43,visitors.length)];if(sprite==null)return;
        int x=(int)((width+96L)*age/109)-80;
        int y=26+(int)(Math.sin(age*.07)*5);
        g.drawImage(sprite,x,y,64,64,null);
    }
    private void meadow(Graphics2D g,int width,int top,int bottom,long offset){
        for(long cell=Math.floorDiv(offset,32)-1;cell*32-offset<width;cell++){
            int x=(int)(cell*32-offset),y=top+variation(cell,9,Math.max(1,bottom-top-4));
            g.setColor(Theme.ROUTE_GRASS_LIGHT);g.fillRect(x,y,6,2);g.fillRect(x+12,y+4,4,2);
            g.setColor(Theme.ROUTE_GRASS_DARK);g.fillRect(x+20,y+2,2,4);g.fillRect(x+18,y,2,2);
        }
    }
    @Override protected void paintComponent(Graphics graphics){
        var g=(Graphics2D)graphics.create();
        try {
            int w=getWidth(),h=getHeight(),pathTop=h-56,pathBottom=h-22;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(Theme.ROUTE_SKY);g.fillRect(0,0,w,h);clouds(g,w);
            hills(g,w,79,12,Theme.ROUTE_HAZE,1);hills(g,w,98,7,Theme.ROUTE_HILL,4);
            visitor(g,w);
            g.setColor(Theme.ROUTE_MEADOW);g.fillRect(0,112,w,h-112);
            grove(g,w,pathTop-24);meadow(g,w,pathTop-18,pathTop,travel);
            g.setColor(Theme.ROUTE_GRASS_DARK);g.fillRect(0,pathTop-2,w,4);
            g.setColor(Theme.ROUTE_PATH_SHADE);g.fillRect(0,pathTop,w,pathBottom-pathTop);
            g.setColor(Theme.ROUTE_PATH);g.fillRect(0,pathTop+3,w,pathBottom-pathTop-6);
            long offset=travel;
            for(long cell=Math.floorDiv(offset,46)-1;cell*46-offset<w;cell++){
                int x=(int)(cell*46-offset),y=pathTop+8+variation(cell,7,16);
                g.setColor(Theme.ROUTE_PATH_LIGHT);g.fillRect(x,y,4,2);
                g.setColor(Theme.ROUTE_PATH_SHADE);g.fillRect(x+22,y+3,2,2);
                g.setColor(Theme.ROUTE_MEADOW);g.fillRect(x+8,pathTop,8,2);g.fillRect(x+30,pathBottom-2,6,2);
            }
            int x=trainerX(w),feet=pathTop+23;
            cameos.paint(g,frame,w,feet-6);
            g.setColor(Theme.ROUTE_SHADOW);g.fillRect(x+6,feet-4,22,4);g.fillRect(x+10,feet-6,14,2);
            var sheet=running&&runSheet!=null?runSheet:walkSheet;
            if(sheet!=null&&sheet.getWidth()>=FRAME_W*9&&sheet.getHeight()>=FRAME_H){
                int index=moving?SIDE_CYCLE[(int)((frame/2)%4)]:FACE_WEST;
                int sx=index*FRAME_W;
                // Native frames encode the gait; an extra vertical bob makes the feet skate.
                g.drawImage(sheet,x+32,feet-64,x,feet,sx,0,sx+16,32,null);
            }
            g.setColor(Theme.ROUTE_LEAF);g.fillRect(0,pathBottom,w,h-pathBottom);
            meadow(g,w,pathBottom+3,h,travel);
            for(long cell=Math.floorDiv(offset,116)-1;cell*116-offset<w;cell++){
                int plantX=(int)(cell*116-offset)+variation(cell,12,24);
                object(g,grass,plantX,h-2,2);
                object(g,grass,plantX+26,h+4,2);
                if(variation(cell,21,3)==0)object(g,rock,plantX+52,h-2,1);
                else object(g,tree,plantX+62,h+20,2);
            }
            float night=nightOpacity(java.time.LocalTime.now(clock));
            if(night>0){
                g.setComposite(AlphaComposite.SrcOver.derive(night));
                g.setColor(Theme.ROUTE_NIGHT);g.fillRect(0,0,w,h);
                g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1,night*2)));
                g.setColor(Theme.ROUTE_CLOUD);
                for(int i=0;i<w;i+=53)g.fillRect(i+variation(i,4,24),10+variation(i,5,40),2,2);
                g.fillRect(w-54,18,14,18);g.fillRect(w-58,22,22,10);
                g.setComposite(AlphaComposite.SrcOver);
            }
            cameos.caption(g,frame,w,pathTop+17);
        } finally {g.dispose();}
    }
}
