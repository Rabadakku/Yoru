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
        palette=Arrays.copyOf(palette,64);
        for(int i=1;i<cells.length;i+=2) cells[i]=0x10;
        var wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(0,0)==0xffff0000);
        cells[1]=0x14;wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(7,0)==0xffff0000);
        check(wall.getRGB(0,0)==0xff000000);
        // A vertical flip reads the tile's first row last, and both flips its first pixel last (#6).
        cells[1]=0x18;wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(0,7)==0xffff0000);
        check(wall.getRGB(0,0)==0xff000000);
        cells[1]=0x1c;wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(7,7)==0xffff0000);
        check(wall.getRGB(0,0)==0xff000000);
        cells[0]=(byte)255;cells[1]=0x13;
        final byte[] wallpaperPalette = palette;
        rejects(()->EmeraldArtwork.wallpaper(tiles,cells,wallpaperPalette));
        cells[0]=0; cells[1]=0x20;
        palette[34]=(byte)0xe0; palette[35]=3; // bank 2, index 1: green
        wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(0,0)==0xff00ff00);
        cells[1]=0; wall=EmeraldArtwork.wallpaper(tiles,cells,palette);
        check(wall.getRGB(0,0)==0); // frame uses the native UI, not the wrong palette
        cells[1]=0x30;
        rejects(()->EmeraldArtwork.wallpaper(tiles,cells,wallpaperPalette));
        rejects(()->EmeraldArtwork.extract(new byte[100],Path.of("unused")));
        aMisleadingTableIsPassedOver();
        // Optional local integration check. Output and input stay outside version control.
        if(args.length==2) {
            EmeraldArtwork.extract(Files.readAllBytes(Path.of(args[0])),Path.of(args[1]));
            try(var files=Files.walk(Path.of(args[1]))) { check(files.filter(p->p.toString().endsWith(".png")).count()==788); }
        }
        System.out.println("PASS: "+checks+" artwork decoder checks");
    }

    /**
     * The wallpaper search takes the first table that looks right from its
     * first entry, so a table that looks right and is not must be rejected
     * whole and the search must go on (#6). The decoy comes first, paints red
     * and has a broken second entry; the real table after it paints green.
     */
    private static void aMisleadingTableIsPassedOver() throws Exception {
        byte[] rom=new byte[0x4000];
        int at=0x1000;
        byte[] tile=new byte[32];
        Arrays.fill(tile,(byte)0x11);                          // every pixel is colour 1
        int tiles=place(rom,at,compressed(tile)); at+=64;
        byte[] map=new byte[720];
        for(int i=1;i<map.length;i+=2) map[i]=0x10;            // bank 1, tile 0, no flips
        int cells=place(rom,at,compressed(map)); at+=0x400;
        int shortCells=place(rom,at,compressed(new byte[700])); at+=0x400;
        byte[] green=new byte[64]; green[2]=(byte)0xe0; green[3]=3;
        byte[] red=new byte[64]; red[2]=31;
        int greenAt=place(rom,at,green); at+=64;
        int redAt=place(rom,at,red);
        int decoy=0x100, real=0x400;
        for(int i=0;i<16;i++) {
            entry(rom,decoy+i*12,tiles,i==1?shortCells:cells,redAt);
            entry(rom,real+i*12,tiles,cells,greenAt);
        }
        var found=EmeraldArtwork.wallpapers(rom);
        check(found.length==16);
        check(found[0].getRGB(0,0)==0xff00ff00);               // the real table's green, not the decoy's red
        check(found[15].getRGB(159,143)==0xff00ff00);
        for(int i=0;i<16;i++) entry(rom,real+i*12,0,0,0);     // with only the decoy left, nothing is accepted
        rejects(()->EmeraldArtwork.wallpapers(rom));
    }

    /** A GBA LZ77 stream of literal blocks only: eight bytes to each flag byte. */
    private static byte[] compressed(byte[] data) {
        var out=new java.io.ByteArrayOutputStream();
        out.write(0x10); out.write(data.length&255); out.write((data.length>>8)&255); out.write((data.length>>16)&255);
        for(int i=0;i<data.length;i+=8) {
            out.write(0);
            out.write(data,i,Math.min(8,data.length-i));
        }
        return out.toByteArray();
    }

    private static int place(byte[] rom,int at,byte[] bytes) { System.arraycopy(bytes,0,rom,at,bytes.length); return at; }

    private static void entry(byte[] rom,int at,int tiles,int cells,int palette) {
        for(int field=0;field<3;field++) {
            int target=new int[]{tiles,cells,palette}[field];
            long value=target==0?0:0x08000000L+target;
            for(int b=0;b<4;b++) rom[at+field*4+b]=(byte)(value>>(b*8));
        }
    }
}
