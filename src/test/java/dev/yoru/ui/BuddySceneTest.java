package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Arrays;

/** Synthetic artwork keeps visual and animation checks independent of personal assets. */
public final class BuddySceneTest {
    private static int checks;
    private static final Color NORMAL=new Color(0x176BEE), SHINY=new Color(0xF72B98);
    private static void check(boolean ok,String message) { checks++;if(!ok)throw new AssertionError(message); }
    private static BufferedImage render(BuddyScene scene,int width,int height) {
        scene.setSize(width,height);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();scene.paint(g);g.dispose();return image;
    }
    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
    }
    private static Rectangle bounds(BufferedImage image,Color color) {
        int left=image.getWidth(),top=image.getHeight(),right=-1,bottom=-1;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)
            if(image.getRGB(x,y)==color.getRGB()) {left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        return right<0?new Rectangle():new Rectangle(left,top,right-left+1,bottom-top+1);
    }
    private static void fixture(Path file,Color color)throws Exception {
        Files.createDirectories(file.getParent());
        var image=new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();g.setColor(color);g.fillRect(0,0,64,32);g.dispose();
        ImageIO.write(image,"png",file.toFile());
    }
    public static void main(String[] args)throws Exception {
        Path fixtures=Files.createTempDirectory("yoru-buddy-test");
        String old=System.getProperty("yoru.art.dir");
        try {
            fixture(fixtures.resolve("252.png"),NORMAL);
            fixture(fixtures.resolve("shiny/252.png"),SHINY);
            // An undecodable explicit candidate prevents fallback to any personal artwork.
            Files.writeString(fixtures.resolve("253.png"),"missing artwork fixture");
            System.setProperty("yoru.art.dir",fixtures.toString());SpriteAssets.refresh();
            SwingUtilities.invokeAndWait(()->{
                for(var theme:ThemeId.values()) {
                    Theme.apply(theme);
                    var scene=new BuddyScene(252);
                    for(var size:new Dimension[]{new Dimension(150,140),new Dimension(260,168),new Dimension(420,240)}) {
                        var image=render(scene,size.width,size.height);
                        var sprite=bounds(image,NORMAL);
                        check(sprite.width>0,"Normal artwork renders");
                        check(Math.abs(sprite.width-2*sprite.height)<=1,"Non-square artwork keeps its aspect ratio");
                        check(sprite.y>30&&sprite.y+sprite.height<size.height-36,"Artwork stays between name and dialogue");
                        check(image.getRGB(8,size.height-32)==Theme.BG.getRGB(),"Dialogue uses theme background");
                    }
                    var still=pixels(render(scene,260,168));
                    for(int i=0;i<20;i++)scene.advance(false);
                    check(Arrays.equals(still,pixels(render(scene,260,168))),"Resting image stays still");
                    scene.advance(true);
                    check(!Arrays.equals(still,pixels(render(scene,260,168))),"Recording animates companion");
                    check(scene.getAccessibleContext().getAccessibleDescription().startsWith("Studying"),"Recording status accessible");
                    scene.advance(false);
                    var stopped=pixels(render(scene,260,168));
                    for(int i=0;i<20;i++)scene.advance(false);
                    check(Arrays.equals(stopped,pixels(render(scene,260,168))),"Clock-out stops motion");
                    var shiny=new BuddyScene(252,true);
                    check(!bounds(render(shiny,260,168),SHINY).isEmpty(),"Shiny variant is loaded");
                    check(shiny.getAccessibleContext().getAccessibleName().startsWith("Shiny Treecko"),"Shiny identity accessible");
                    var missing=new BuddyScene(1);
                    render(missing,150,140);
                    check(missing.getAccessibleContext().getAccessibleDescription().contains("National Dex number"),"Missing artwork fallback described");
                    var empty=new BuddyScene(0);
                    var emptyStill=pixels(render(empty,150,140));
                    empty.advance(true);
                    check(Arrays.equals(emptyStill,pixels(render(empty,150,140))),"Empty state remains still during recording");
                    check(empty.getAccessibleContext().getAccessibleDescription().equals("Choose your starter in the game."),"Empty state does not claim a companion");
                }
            });
            System.out.println("PASS: "+checks+" companion checks (themes, sizing, artwork, animation, accessibility)");
        } finally {
            if(old==null)System.clearProperty("yoru.art.dir");else System.setProperty("yoru.art.dir",old);
            SpriteAssets.refresh();
            try(var files=Files.walk(fixtures)) {
                for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);
            }
        }
    }
}
