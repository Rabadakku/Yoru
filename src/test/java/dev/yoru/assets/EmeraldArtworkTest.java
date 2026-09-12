package dev.yoru.assets;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

/** Synthetic graphics fixtures: no ROM or real sprite is part of this test. */
public final class EmeraldArtworkTest {
    private static int checks;
    static void check(boolean ok) { checks++; if(!ok)throw new AssertionError(); }
    interface Bad { void run() throws Exception; }
    static void rejects(Bad run) throws Exception { try { run.run();throw new AssertionError("accepted malformed data"); }catch(IOException expected){checks++;} }
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(EmeraldArtwork.lz77(new byte[]{16,6,0,0,0x40,65,0x20,0},0),new byte[]{65,65,65,65,65,65}));
        rejects(()->EmeraldArtwork.lz77(new byte[]{16,4,0,0,(byte)128,0,0},0));
        rejects(()->EmeraldArtwork.lz77(new byte[]{16,4,0,0,0,1},0));
        rejects(()->EmeraldArtwork.lz77(new byte[]{16,0,0,1},0));
        rejects(()->EmeraldArtwork.pointer(new byte[4],0));
        byte[] tiles=new byte[2048],palette=new byte[32];
        tiles[0]=0x21;palette[2]=31;palette[5]=0x7c;
        tiles[32]=1;
        var image=EmeraldArtwork.render(tiles,palette);
        check(image.getRGB(0,0)==0xffff0000);
        check(image.getRGB(1,0)==0xff0000ff);
        check(image.getRGB(2,0)==0);
        check(image.getRGB(8,0)==0xffff0000);
        check(image.getRGB(0,8)==0);
        byte[] cells=new byte[720];
        var wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(0,0)==0xffff0000);
        cells[1]=4;wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(7,0)==0xffff0000);
        check(wall.getRGB(0,0)==0xff000000);
        cells[0]=(byte)255;cells[1]=3;
        rejects(()->EmeraldArtwork.wallpaper(tiles,cells,palette));
        rejects(()->EmeraldArtwork.extract(new byte[100],Path.of("unused")));
        // Optional local integration check. Output and input stay outside version control.
        if(args.length==2) {
            EmeraldArtwork.extract(Files.readAllBytes(Path.of(args[0])),Path.of(args[1]));
            try(var files=Files.walk(Path.of(args[1]))) { check(files.filter(p->p.toString().endsWith(".png")).count()==788); }
        }
        System.out.println("PASS: "+checks+" artwork decoder checks");
    }
}
