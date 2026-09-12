package dev.yoru.assets;

import dev.yoru.game.SpeciesIds;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;

/** Decodes the player's own Emerald sprites; no game graphics are bundled.
 * Table pointers are the GFRomHeader API, not offsets into a particular art build.
 * Reference: https://github.com/pret/pokeemerald/blob/master/src/rom_header_gf.c
 */
public final class EmeraldArtwork {
    private EmeraldArtwork() { }

    public static void extract(byte[] rom, Path destination) throws IOException {
        if (rom.length < 0x138 || rom[0xac]!='B' || rom[0xad]!='P' || rom[0xae]!='E' || rom[0xaf]!='E')
            throw new IOException("Artwork extraction needs an English Emerald game file.");
        int fronts=pointer(rom,0x128), normal=pointer(rom,0x130), shiny=pointer(rom,0x134);
        // Decode every entry before writing: a damaged table must not leave half a pack.
        var images=new BufferedImage[2][386];
        for(int n=1;n<=386;n++) {
            int internal=SpeciesIds.internalOf(n);
            byte[] tiles=lz77(rom,pointer(rom,fronts+internal*8));
            if(tiles.length<2048)throw new IOException("The game has an incomplete front sprite.");
            for(int variant=0;variant<2;variant++) {
                byte[] palette=lz77(rom,pointer(rom,(variant==0?normal:shiny)+internal*8));
                if(palette.length<32 || palette.length%32!=0)throw new IOException("The game has an unsupported sprite palette.");
                images[variant][n-1]=render(tiles,palette);
            }
        }
        Files.createDirectories(destination.resolve("shiny"));
        for(int variant=0;variant<2;variant++)for(int n=1;n<=386;n++)
            ImageIO.write(images[variant][n-1],"png",destination.resolve((variant==0?"":"shiny/")+n+".png").toFile());
    }

    static BufferedImage render(byte[] tiles,byte[] palette) {
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<64;y++)for(int x=0;x<64;x++) {
            int offset=((y/8)*8+x/8)*32+(y%8)*4+(x%8)/2;
            int index=(tiles[offset]>>((x%2)*4))&15;
            int color=(palette[index*2]&255)|((palette[index*2+1]&255)<<8);
            int r=(color&31)*255/31,g=((color>>5)&31)*255/31,b=((color>>10)&31)*255/31;
            image.setRGB(x,y,index==0?0:0xff000000|(r<<16)|(g<<8)|b);
        }
        return image;
    }

    static int pointer(byte[] rom,int offset) throws IOException {
        if(offset<0||offset>rom.length-4)throw new IOException("The game has a truncated artwork table.");
        long value=0;for(int i=0;i<4;i++)value|=(long)(rom[offset+i]&255)<<(i*8);
        long target=value-0x08000000L;
        if(target<0||target>=rom.length)throw new IOException("The game has an invalid artwork pointer.");
        return (int)target;
    }

    /** Bounded GBA type-0x10 LZ77, including overlapping back references. */
    static byte[] lz77(byte[] rom,int at) throws IOException {
        if(at<0||at>rom.length-4||rom[at]!=0x10)throw new IOException("Invalid compressed artwork.");
        int size=(rom[at+1]&255)|((rom[at+2]&255)<<8)|((rom[at+3]&255)<<16);
        if(size==0||size>32768)throw new IOException("Unsupported artwork size.");
        byte[] out=new byte[size];int p=at+4,count=0;
        try {
            while(count<size) {
                int flags=rom[p++]&255;
                for(int bit=7;bit>=0&&count<size;bit--) {
                    if((flags&(1<<bit))==0)out[count++]=rom[p++];
                    else {
                        int a=rom[p++]&255,b=rom[p++]&255;
                        int length=(a>>4)+3,distance=((a&15)<<8|b)+1;
                        if(distance>count)throw new IOException("Invalid artwork back reference.");
                        for(int i=0;i<length&&count<size;i++,count++)out[count]=out[count-distance];
                    }
                }
            }
        } catch(IndexOutOfBoundsException e) { throw new IOException("Truncated compressed artwork.",e); }
        return out;
    }
}
