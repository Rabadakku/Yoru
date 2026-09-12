package dev.yoru.ui;
import javax.swing.*;
import java.awt.*;
/** Original code-drawn art, not a reproduction of a referenced image. */
final class PixelScene extends JPanel {
    private final boolean buddy;
    PixelScene(boolean buddy) {
        this.buddy=buddy;
        setPreferredSize(new Dimension(buddy?280:650,buddy?190:145));
        setMinimumSize(new Dimension(100,140));
        getAccessibleContext().setAccessibleName(buddy?"Mochi the pixel cat, resting beside a plant":"Pixel skyline beneath a crescent moon");
    }
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var g=(Graphics2D)graphics.create();
        int w=getWidth(),h=getHeight();
        g.setPaint(new GradientPaint(0,0,new Color(0x27344A),0,h,new Color(0x535267)));
        g.fillRect(0,0,w,h);
        g.setColor(new Color(0xF0D1A3));
        g.fillOval(w-100,20,34,34);
        g.setColor(new Color(0x2E3B50));
        g.fillOval(w-91,14,33,33);
        g.setColor(new Color(0xA4ADB9));
        for(int i=0;i<24;i++)g.fillRect((i*73+19)%Math.max(1,w),(i*31+8)%Math.max(1,h/2),2,2);
        for(int i=0,x=0;x<w;i++,x+=37) {
            int bh=24+(i*19%53);
            g.setColor(new Color(0x202A3A));
            g.fillRect(x,h-bh,33,bh);
            g.setColor(new Color(0xA6957B));
            for(int j=0;j<3;j++)if((i+j)%3==0)g.fillRect(x+7+j*7,h-bh+9,3,5);
        }
        g.setColor(new Color(0x151E2B));
        g.fillRect(0,h-22,w,22);
        if(buddy) {
            int s=5,x=w/2-40,y=h-94;
            String[] pixels= {
                "..22......22....","..232....232....","..2332222332....",".233333333332...",".233133313332...",".233333333332...","..2333433332....","...22222222.....","...25555552..22.","..2555555552222.","..222222222222.."
            }
            ;
            Color[] c= {
                Theme.BG,new Color(0x242838),new Color(0xB6B8CA),new Color(0xE0D6CF),new Color(0xC98193),new Color(0x8798BB)
            }
            ;
            for(int r=0;r<pixels.length;r++)for(int k=0;k<pixels[r].length();k++) {
                char v=pixels[r].charAt(k);
                if(v!='.') {
                    g.setColor(c[v-'0']);
                    g.fillRect(x+k*s,y+r*s,s,s);
                }
            }
            g.setColor(new Color(0x9BBDAB));
            g.fillRect(35,h-70,6,34);
            g.fillRect(23,h-64,14,6);
            g.fillRect(41,h-78,15,6);
            g.setColor(new Color(0xC48C7D));
            g.fillRect(24,h-40,29,18);
        }
        else {
            g.setColor(new Color(0xD3BBC0));
            g.setFont(Theme.bodyFont());
            g.drawString("夜  /  ONE SMALL STEP AT A TIME",22,h-32);
        }
        g.dispose();
    }
}
