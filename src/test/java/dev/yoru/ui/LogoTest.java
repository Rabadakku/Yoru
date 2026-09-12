package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;

/**
 * The mark (#57).
 *
 * "Works on all four themes, two of them light" and "reads at 16px and holds up
 * at 1024" are requirements, so they are measured rather than admired. The
 * contrast between the ink and each theme's ground is computed and asserted;
 * judging that by eye is how a mark ends up invisible on the one palette nobody
 * screenshotted. The shape is pinned by geometry instead: how much of its own
 * box the crescent fills (a disc would fill 0.79, a crescent cannot), how many
 * pieces it comes to at every size (one, or it has broken into crumbs), where
 * its weight sits (away from the mouth), and that its two halves mirror.
 */
public final class LogoTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    /** Every size the app asks for, plus the ones the installers export. */
    private static final int[] SIZES={16,20,22,24,26,32,48,64,128,256,512,1024};

    /** The bite points up the diagonal, so the mark's mass sits opposite it. */
    private static final double UX=Math.cos(-Math.PI/4), UY=Math.sin(-Math.PI/4);

    /** Draws the bare mark on transparency, so ink can be counted by alpha. */
    private static BufferedImage draw(int size) {
        var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();
        Logo.mark(g,0,0,size,Color.WHITE);
        g.dispose();
        return image;
    }
    private static int alpha(BufferedImage image,int x,int y){return (image.getRGB(x,y)>>>24)&0xFF;}
    private static boolean inked(BufferedImage image,int x,int y){return alpha(image,x,y)>128;}
    private static int ink(BufferedImage image,int fromY,int toY) {
        int count=0;
        for(int y=Math.max(0,fromY);y<Math.min(image.getHeight(),toY);y++)
            for(int x=0;x<image.getWidth();x++)
                if(inked(image,x,y)) count++;
        return count;
    }
    private static int ink(BufferedImage image){return ink(image,0,image.getHeight());}
    /** Ink in a vertical band, for telling a lockup's two halves apart. */
    private static int inkColumns(BufferedImage image,int fromX,int toX) {
        int count=0;
        for(int x=Math.max(0,fromX);x<Math.min(image.getWidth(),toX);x++)
            for(int y=0;y<image.getHeight();y++)
                if(inked(image,x,y)) count++;
        return count;
    }

    /**
     * Separate ink shapes, four-connected. A mark that reads at 16px is one
     * piece: two pieces is a horn that fell off, and more is a spray of crumbs.
     */
    private static int shapes(BufferedImage image) {
        int w=image.getWidth(), h=image.getHeight();
        boolean[] seen=new boolean[w*h];
        int found=0;
        for(int start=0;start<w*h;start++) {
            if(seen[start]||!inked(image,start%w,start/w)) continue;
            found++;
            var queue=new java.util.ArrayDeque<Integer>();
            queue.add(start); seen[start]=true;
            while(!queue.isEmpty()) {
                int at=queue.poll(), x=at%w, y=at/w;
                int[][] around={{x-1,y},{x+1,y},{x,y-1},{x,y+1}};
                for(var next:around) {
                    int nx=next[0], ny=next[1];
                    if(nx<0||ny<0||nx>=w||ny>=h) continue;
                    int index=ny*w+nx;
                    if(seen[index]||!inked(image,nx,ny)) continue;
                    seen[index]=true; queue.add(index);
                }
            }
        }
        return found;
    }

    /** The mark's own bounding box, as a cropped frame. */
    private record Frame(int x,int y,int w,int h){}
    private static Frame frame(BufferedImage image) {
        int w=image.getWidth(), h=image.getHeight();
        int minX=w,maxX=-1,minY=h,maxY=-1;
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) if(inked(image,x,y)) {
            minX=Math.min(minX,x); maxX=Math.max(maxX,x);
            minY=Math.min(minY,y); maxY=Math.max(maxY,y);
        }
        return new Frame(minX,minY,maxX-minX+1,maxY-minY+1);
    }

    /**
     * How much of its own bounding box the mark fills.
     *
     * A disc fills pi/4, about 0.79. A crescent is concave and fills far less,
     * so this is what separates "a moon" from "a circle" — without it the mark
     * could lose its bite and every other assertion here would still pass.
     */
    private static double fill(BufferedImage image) {
        var box=frame(image);
        return ink(image)/(double)(box.w()*box.h());
    }

    /**
     * Where the mark's weight sits, projected on the bite's direction: a disc
     * has its centroid on its centre, a crescent's is pushed away from the mouth.
     * Returned in units of the mark's box, so it means the same at any size.
     */
    private static double weight(BufferedImage image) {
        return (centroid(image,0)-(image.getWidth()-1)/2.0)*UX
            +(centroid(image,1)-(image.getWidth()-1)/2.0)*UY;
    }

    /**
     * The same weight projected across the axis. The mark is symmetric about the
     * line the bite points along, so this is zero however big the mark is — which
     * is a stronger statement than mirroring the pixels, because it cannot be
     * satisfied by a lopsided shape that happens to rasterise evenly.
     */
    private static double across(BufferedImage image) {
        return (centroid(image,0)-(image.getWidth()-1)/2.0)*-UY
            +(centroid(image,1)-(image.getWidth()-1)/2.0)*UX;
    }

    /** The ink's centre of mass, on one axis. */
    private static double centroid(BufferedImage image,int axis) {
        int size=image.getWidth();
        long total=0; int count=0;
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) if(inked(image,x,y)) {
            total+=axis==0?x:y; count++;
        }
        return total/(double)count;
    }

    /**
     * The largest alpha difference between a pixel and its mirror image across
     * the anti-diagonal. The crescent is symmetric about its own axis, and the
     * bite is placed on that diagonal, so a perfect mark mirrors exactly.
     */
    private static int mirrorError(BufferedImage image) {
        int size=image.getWidth(), worst=0;
        for(int y=0;y<size;y++) for(int x=0;x<size;x++)
            worst=Math.max(worst,Math.abs(alpha(image,x,y)-alpha(image,size-1-y,size-1-x)));
        return worst;
    }

    /** WCAG relative luminance. */
    private static double luminance(Color c) {
        double[] channel=new double[3];
        int[] raw={c.getRed(),c.getGreen(),c.getBlue()};
        for(int i=0;i<3;i++){
            double v=raw[i]/255.0;
            channel[i]=v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);
        }
        return 0.2126*channel[0]+0.7152*channel[1]+0.0722*channel[2];
    }
    private static double luminance(int rgb){return luminance(new Color(rgb));}
    private static double contrast(Color a,Color b) {
        double la=luminance(a), lb=luminance(b);
        return (Math.max(la,lb)+0.05)/(Math.min(la,lb)+0.05);
    }
    private static boolean near(Color a,Color b,int tolerance) {
        return Math.abs(a.getRed()-b.getRed())<=tolerance
            && Math.abs(a.getGreen()-b.getGreen())<=tolerance
            && Math.abs(a.getBlue()-b.getBlue())<=tolerance;
    }
    /** Mean luminance of the pixels that are actually painted. */
    private static double litLuminance(BufferedImage image) {
        double total=0; int count=0;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int argb=image.getRGB(x,y);
            if(((argb>>>24)&0xFF)<200) continue;
            total+=luminance(argb&0xFFFFFF); count++;
        }
        return count==0?-1:total/count;
    }

    /**
     * The icon pixel covering a body coordinate 0..1 across. Floored, not
     * rounded, so it is the pixel whose own centre is nearest the point.
     */
    private static int pixel(int size,double bodyAt) {
        double inset=size*(1-Logo.BODY)/2;
        return (int)Math.floor(inset+bodyAt*size*Logo.BODY);
    }
    /** What a colour the sky is drawn through looks like once it is painted. */
    private static Color over(Color ground,Color ink) {
        double a=ink.getAlpha()/255.0;
        return new Color(
            (int)Math.round(ground.getRed()*(1-a)+ink.getRed()*a),
            (int)Math.round(ground.getGreen()*(1-a)+ink.getGreen()*a),
            (int)Math.round(ground.getBlue()*(1-a)+ink.getBlue()*a));
    }

    public static void main(String[] args)throws Exception{
        // One mark, the same shape at every size. The coverage band is narrow on
        // purpose: a crescent that suddenly filled half its box would be a disc,
        // and one that dipped below would be a sliver.
        for(int size:SIZES) {
            var image=draw(size);
            double coverage=ink(image)/(double)(size*size);
            check(ink(image)>0,"The mark draws ink at "+size+"px");
            check(coverage>0.15&&coverage<0.45,
                "The mark covers a crescent's share of its box at "+size+"px — covered "
                    +String.format("%.2f",coverage));
            check(shapes(image)==1,"The mark is one connected shape at "+size+"px, found "
                +shapes(image)+" — a horn has come off, or crumbled");
            double fill=fill(image);
            check(fill>0.25&&fill<0.55,"The mark is a crescent, not a disc or a sliver, at "
                +size+"px — fills "+String.format("%.2f",fill)+" of its box");
            check(weight(image)/size<-0.10,"The mark's weight sits away from its mouth at "+size
                +"px — offset "+String.format("%.2f",weight(image)/size));
            check(Math.abs(across(image))/size<0.01,"The mark's weight sits on its own axis at "
                +size+"px — "+String.format("%.4f",across(image)/size)+" of the box off it");
        }

        // And the halves really do mirror. Below the pixel cutoff that is exact —
        // the same samples decide both halves. Above it, Java2D's scanline fill is
        // not bit-symmetric under a transpose, so the bar there is that nothing
        // shifts by a visible amount; the axis itself is pinned by the check above.
        for(int size:new int[]{16,20,22}) {
            check(mirrorError(draw(size))==0,"The "+size+"px mark mirrors exactly, pixel for pixel");
        }
        for(int size:new int[]{24,32,48,64,128,256,512,1024}) {
            int error=mirrorError(draw(size));
            check(error<=24,"The "+size+"px mark mirrors to within the rasteriser's own jitter — "
                +"worst alpha difference "+error);
        }

        // Crisp below the cutoff, smooth above it. Half-lit pixels at icon size are
        // the grey mush the pixel path exists to avoid; none at 256 would mean the
        // big sizes are being rasterised on the grid too.
        for(int size:new int[]{16,20,22}) {
            var image=draw(size);
            int partial=0;
            for(int y=0;y<size;y++) for(int x=0;x<size;x++)
                if(alpha(image,x,y)!=0&&alpha(image,x,y)!=255) partial++;
            check(partial==0,"The "+size+"px mark is drawn on the pixel grid — "+partial
                +" half-lit pixels, which is what turns a horn into a smudge");
        }
        for(int size:new int[]{24,64,256,1024}) {
            var image=draw(size);
            int partial=0;
            for(int y=0;y<size;y++) for(int x=0;x<size;x++)
                if(alpha(image,x,y)!=0&&alpha(image,x,y)!=255) partial++;
            check(partial>0,"The "+size+"px mark is antialiased, so its edge is smooth at "
                +"1024 — found "+partial+" partial pixels");
        }

        // The bite is a bite: the mouth is empty and the far side is solid, at the
        // point along the diagonal where a disc would be ink on both.
        for(int size:new int[]{16,64,256}) {
            var image=draw(size);
            int cx=size/2, cy=size/2, step=(int)Math.round(size*0.30);
            check(!inked(image,cx+(int)Math.round(step*UX),cy+(int)Math.round(step*UY)),
                "The crescent's mouth is empty at "+size+"px");
            check(inked(image,cx-(int)Math.round(step*UX),cy-(int)Math.round(step*UY)),
                "The crescent is solid opposite its mouth at "+size+"px");
        }

        // The actual requirement: legible on all four grounds, two of them light.
        for(var id:ThemeId.values()) {
            var palette=Theme.palette(id);
            double ratio=contrast(palette.accent(),palette.bg());
            check(ratio>=3.0,id+" needs 3:1 between the mark and its ground, has "
                +String.format("%.2f",ratio));
            double edge=contrast(palette.line(),palette.bg());
            check(edge>=1.1,id+" needs a visible icon edge, has "+String.format("%.2f",edge));
        }
        check(java.util.Arrays.stream(ThemeId.values()).anyMatch(id->Theme.palette(id).dark()),"Some themes are dark");
        check(java.util.Arrays.stream(ThemeId.values()).anyMatch(id->!Theme.palette(id).dark()),"Some themes are light");

        // The app icon: Apple's grid, on the pixel. The body is 824/1024 of the
        // canvas, so the first painted column is a tenth of the way in, the corner
        // at that inset is cut away, and the middle of the edge is not.
        Theme.apply(ThemeId.MIDNIGHT);
        for(int size:new int[]{16,64,256,1024}) {
            for(boolean dark:new boolean[]{true,false}) {
                var icon=Logo.appIcon(size,dark);
                String variant=dark?"night":"moonlight";
                check(icon.getWidth()==size&&icon.getHeight()==size,
                    "The "+variant+" icon is the size it was asked for");
                int inset=-1;
                for(int x=0;x<size;x++) if(alpha(icon,x,size/2)>128){inset=x;break;}
                int want=(int)Math.round(size*(1-Logo.BODY)/2);
                check(Math.abs(inset-want)<=1,"The "+variant+" icon sits on Apple's grid at "
                    +size+"px — body starts at "+inset+", expected "+want);
                check(alpha(icon,0,0)<128,"The "+variant+" icon's corner is rounded away at "+size+"px");
                if(size>=64) {
                    // How much of the body's square the corner curve cuts off. A
                    // square body cuts none of it, a disc cuts a fifth, and the
                    // macOS corner cuts about a twentieth — which is what says
                    // this is Apple's shape and not either of those.
                    int cut=0;
                    for(int y=inset;y<inset+(size-inset-inset);y++)
                        for(int x=inset;x<inset+(size-inset-inset);x++)
                            if(alpha(icon,x,y)<128) cut++;
                    double share=cut/(double)((size-inset-inset)*(size-inset-inset));
                    check(share>0.02&&share<0.10,"The "+variant+" icon's corner is a continuous "
                        +"curve at "+size+"px, cutting "+String.format("%.3f",share)+" of the body");
                }
                check(alpha(icon,size/2,inset+1)>200,"The "+variant+" icon's top edge is solid at "+size+"px");
                check(alpha(icon,size/2,size-inset-2)>200,"and its bottom edge at "+size+"px");
                // The sky runs deeper at the top than at the bottom, which is what
                // makes it a sky rather than a fill. Read below the sheen, whose
                // fade is wider than a small icon's whole body.
                if(size>=64) {
                    int column=inset+Math.max(1,(int)Math.round(size*Logo.BODY*0.06));
                    double top=luminance(icon.getRGB(column,inset+(int)Math.round(size*Logo.BODY*0.25))&0xFFFFFF);
                    double bottom=luminance(icon.getRGB(column,inset+(int)Math.round(size*Logo.BODY*0.75))&0xFFFFFF);
                    check(dark?top<bottom-0.01:top>bottom+0.01,
                        "The "+variant+" sky is deeper at the top at "+size+"px — "
                            +String.format("%.3f",top)+" then "+String.format("%.3f",bottom));
                }
                // The mark is in there, in the icon's own ink.
                var wanted=Logo.skyInk(dark);
                int painted=0;
                for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
                    int argb=icon.getRGB(x,y);
                    if(((argb>>>24)&0xFF)>200&&near(new Color(argb&0xFFFFFF),wanted,16)) painted++;
                }
                check(painted>size,"The "+variant+" icon carries the crescent at "+size
                    +"px — "+painted+" pixels in its ink");
                // The painted sky is the sky the theme asks for, read at the middle
                // of the body where the sheen has faded out.
                int sampleX=inset, sampleY=inset+(int)Math.round(size*Logo.BODY*0.5);
                double down=((sampleY+0.5)-size*(1-Logo.BODY)/2)/(size*Logo.BODY);
                var sky=new Color(icon.getRGB(sampleX,sampleY)&0xFFFFFF);
                check(near(sky,Logo.skyGround(dark,down),6),
                    "The "+variant+" icon's sky is the colour the theme asks for at "+size
                        +"px — painted "+sky+", wanted "+Logo.skyGround(dark,down));
            }
        }

        // The icon has to stay legible on its own ground, in every theme, at both
        // ends of the gradient — the top of the sky is where the ink is thinnest.
        for(var id:ThemeId.values()) {
            Theme.apply(id);
            double light=litLuminance(Logo.appIcon(64,false));
            double dark=litLuminance(Logo.appIcon(64,true));
            check(light-dark>0.30,id+" needs the two variants to be distinguishable, not "
                +String.format("%.2f",light-dark)+" apart");
            for(boolean variant:new boolean[]{true,false}) {
                for(double at:new double[]{0,0.25,0.5,0.75,1}) {
                    double ratio=contrast(Logo.skyInk(variant),Logo.skyGround(variant,at));
                    check(ratio>=4.5,(variant?"night":"moonlight")+" on "+id+" needs 4.5:1 at "
                        +at+" down the sky, has "+String.format("%.2f",ratio));
                }
            }
        }

        // Switching theme moves the icon with it: four palettes, four skies.
        Theme.apply(ThemeId.MIDNIGHT);
        var midnight=Logo.skyGround(true,0.5);
        var others=new java.util.ArrayList<Color>();
        for(var id:ThemeId.values()) {
            Theme.apply(id);
            others.add(Logo.skyGround(true,0.5));
        }
        check(others.stream().distinct().count()==ThemeId.values().length,
            "Each theme tints the night sky differently");
        check(!near(midnight,Theme.palette(ThemeId.MIDNIGHT).bg(),4),
            "The icon's sky is its own colour, not the window's background");

        // Stars: painted where the drawing says, in the star ink over the sky, and
        // left out at icon size, where one pixel is a speck of dirt rather than a
        // star. Measured against the colour the pair composites to, so a star
        // cannot pass by being some other bright thing (the crescent, say).
        Theme.apply(ThemeId.MIDNIGHT);
        check(Logo.stars().length>=2,"The night sky has more than one star");
        var night=Logo.appIcon(256,true);
        for(var star:Logo.stars()) {
            var found=new Color(night.getRGB(pixel(256,star[0]),pixel(256,star[1]))&0xFFFFFF);
            check(luminance(found)-luminance(Logo.skyGround(true,star[1]))>0.15,
                "The star at "+String.format("%.2f, %.2f",star[0],star[1])+" is brighter than the "
                    +"sky it sits on — "+found+" against "+Logo.skyGround(true,star[1]));
        }
        // Big enough for a star to cover a whole pixel: then it is exactly the
        // star ink over the sky, and not some other bright thing that got there.
        var big=Logo.appIcon(512,true);
        for(var star:Logo.stars()) {
            var wanted=over(Logo.skyGround(true,star[1]),Logo.starInk(true));
            var found=new Color(big.getRGB(pixel(512,star[0]),pixel(512,star[1]))&0xFFFFFF);
            check(near(found,wanted,16),"The star at "+String.format("%.2f, %.2f",star[0],star[1])
                +" is drawn in the icon's star ink — found "+found+", wanted "+wanted);
        }
        for(int size:new int[]{16,22}) {
            var small=Logo.appIcon(size,true);
            for(var star:Logo.stars()) {
                var found=new Color(small.getRGB(pixel(size,star[0]),pixel(size,star[1]))&0xFFFFFF);
                check(luminance(found)<0.30,"No star at "+size+"px: the sky there is "+found);
            }
        }
        var moon=Logo.appIcon(256,false);
        var moonStar=new Color(moon.getRGB(pixel(256,Logo.stars()[0][0]),pixel(256,Logo.stars()[0][1]))&0xFFFFFF);
        check(luminance(moonStar)<luminance(Logo.skyGround(false,Logo.stars()[0][1]))-0.15,
            "The moonlight sky's stars are dark on it");

        // The window and dock icons come from the same drawing, one per size.
        Theme.apply(ThemeId.MIDNIGHT);
        var icons=Theme.appIcons();
        check(icons.size()==7,"The app offers an icon per export size, has "+icons.size());
        for(var image:icons) {
            int size=image.getWidth(null);
            check(size==image.getHeight(null),"An app icon is square");
            check(((BufferedImage)image).getRGB(0,0)>>>24<128,"The "+size+"px app icon is rounded");
        }
        check(icons.getLast().getWidth(null)==1024,"The last app icon is the Retina size");

        // The lockup measures itself, so a layout can place it, and carries the
        // wordmark as well as the mark.
        Theme.apply(ThemeId.MIDNIGHT);
        var lockup=Logo.lockup(26,Theme.CYAN);
        var size=lockup.getPreferredSize();
        check(size.height==26,"The lockup is the height it was asked for");
        check(size.width>26*2,"The lockup leaves room for the word beside the mark");
        lockup.setSize(size);
        var strip=new BufferedImage(size.width,size.height,BufferedImage.TYPE_INT_ARGB);
        var g=strip.createGraphics();
        lockup.paint(g);
        g.dispose();
        check(ink(strip)>0,"The lockup paints");
        check(ink(strip)>ink(draw(26)),"The lockup carries more than the mark alone");
        check(inkColumns(strip,0,26)>0,"The mark is in the lockup, in its own box");
        check(inkColumns(strip,26,size.width)>0,"and the wordmark is beside it");

        // The .ico the exporter writes is hand-rolled, so it is read back rather
        // than trusted: a directory of PNG entries, each decoding to the size its
        // header claims, laid out one after another. A 16-byte header with a
        // wrong byte count in it is the kind of thing no eye ever catches.
        var scratch=Files.createTempDirectory("yoru-logo");
        try {
            var file=scratch.resolve("night.ico");
            LogoExport.ico(file,true);
            byte[] ico=Files.readAllBytes(file);
            check(ico.length>0&&little(ico,0,2)==0&&little(ico,2,2)==1,"The .ico declares itself an icon");
            check(little(ico,4,2)==LogoExport.ICO_SIZES.length,"The .ico carries "
                +little(ico,4,2)+" images, one per size");
            int offset=6+16*LogoExport.ICO_SIZES.length;
            for(int i=0;i<LogoExport.ICO_SIZES.length;i++) {
                int at=6+16*i, entry=LogoExport.ICO_SIZES[i];
                int width=ico[at]&0xFF, height=ico[at+1]&0xFF;
                check(width==(entry==256?0:entry)&&height==width,
                    "The .ico's "+entry+"px entry writes its size the way the format does");
                check(little(ico,at+4,2)==1&&little(ico,at+6,2)==32,"The "+entry
                    +"px entry is 32-bit and not a cursor");
                check(little(ico,at+12,4)==offset,"The "+entry+"px entry is written where it says");
                var image=ImageIO.read(new java.io.ByteArrayInputStream(ico,
                    little(ico,at+12,4),little(ico,at+8,4)));
                check(image!=null&&image.getWidth()==entry&&image.getHeight()==entry,
                    "The .ico's "+entry+"px entry decodes to a "+entry+"px image");
                offset+=little(ico,at+8,4);
            }
            check(offset==ico.length,"The .ico ends where its last entry does");
        } finally {
            try(var files=Files.walk(scratch)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path->path.toFile().delete());
            }
        }

        System.out.println("PASS: "+checks+" logo checks (one mark at every size, in every theme, and the app icon)");
    }

    /** A little-endian field. The .ico is the one file here that is not Java's. */
    private static int little(byte[] bytes,int at,int width) {
        int value=0;
        for(int i=width-1;i>=0;i--) value=(value<<8)|(bytes[at+i]&0xFF);
        return value;
    }
}
