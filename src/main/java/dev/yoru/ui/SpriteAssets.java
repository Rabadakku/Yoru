package dev.yoru.ui;
import dev.yoru.assets.ArtworkLibrary;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * Bounded shared sprite decoder.
 *
 * Resolution order: the yoru.art.dir override, the user's imported library,
 * artwork beside the application, then the classpath. The cache key includes the source folder, so
 * importing new artwork naturally invalidates what was cached from before it.
 */
final class SpriteAssets {
    private static final long MAX_BYTES=512*1024;
    private static final int MAX_EDGE=256;
    /** Sheets ripped from the overworld carry a flat background to key out. */
    private static final Set<String> KEYED=Set.of(
        "brendan.png","brendan-running.png","may.png","may-running.png","tree.png","rock.png","grass.png",
        "route-trainer.png","route-cyclist.png","team-rocket.png");

    private static final Map<String,BufferedImage> CACHE=new LinkedHashMap<>(64,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<String,BufferedImage> entry){return size()>800;}
    };

    /** Clears the cache so newly imported artwork is picked up without a restart. */
    static synchronized void refresh() { CACHE.clear(); }

    /** Report artwork the renderer can actually use, including portable installations. */
    static ArtworkLibrary.Report survey() {
        int normal=0,shiny=0,sheets=0,wallpapers=0;
        for(int i=1;i<=ArtworkLibrary.SPECIES;i++) {
            if(load(i+".png")!=null)normal++;
            if(load("shiny/"+i+".png")!=null)shiny++;
        }
        for(String name:KEYED) {
            var sheet=load(name);
            if(sheet!=null&&(!RouteCameos.isArtwork(name)||RouteCameos.validStrip(sheet)))sheets++;
        }
        for(int i=0;i<16;i++) if(load(String.format(Locale.ROOT,"pc/wallpaper-%02d.png",i))!=null)wallpapers++;
        return new ArtworkLibrary.Report(normal,shiny,sheets,0,ArtworkLibrary.survey().games(),wallpapers,0,List.of());
    }

    private static List<Path> candidates(String name) {
        var paths=new ArrayList<Path>();
        String override=System.getProperty("yoru.art.dir");
        if(override!=null&&!override.isBlank()) paths.add(Path.of(override,name));
        paths.add(ArtworkLibrary.root().resolve(name));
        // A downloaded jar can carry an adjacent `art/` folder. Resolve it from
        // the jar/classes location so double-clicking the app does not depend
        // on the process working directory or a shell flag.
        try {
            Path location=Path.of(SpriteAssets.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Path base=Files.isRegularFile(location)?location.getParent():location;
            if(base!=null) {
                paths.add(base.resolve("art").resolve(name));
                if(base.getParent()!=null) paths.add(base.getParent().resolve("art").resolve(name));
                // A macOS bundle keeps its jar in App.app/Contents/Resources.
                if(base.getFileName().toString().equals("Resources") && base.getParent()!=null
                    && base.getParent().getFileName().toString().equals("Contents")) {
                    Path bundle=base.getParent().getParent();
                    if(bundle!=null && bundle.getParent()!=null)
                        paths.add(bundle.getParent().resolve("art").resolve(name));
                }
            }
        } catch(Exception ignored) { }
        return paths;
    }

    static synchronized BufferedImage load(String name) {
        String key=System.getProperty("yoru.art.dir","")+"|"+ArtworkLibrary.root()+"|"+name;
        if(CACHE.containsKey(key))return CACHE.get(key);
        BufferedImage image=null;
        try {
            java.io.InputStream stream=null;
            for(Path path:candidates(name)) {
                if(Files.isRegularFile(path)&&Files.size(path)<=MAX_BYTES) { stream=Files.newInputStream(path); break; }
            }
            if(stream==null) {
                var resource=SpriteAssets.class.getResource("/emerald/"+name);
                if(resource!=null) stream=resource.openStream();
            }
            if(stream!=null)try(var bytes=stream;var input=ImageIO.createImageInputStream(bytes)) {
                var readers=ImageIO.getImageReaders(input);
                if(readers.hasNext()) {
                    var reader=readers.next();
                    try {
                        reader.setInput(input);
                        if(reader.getWidth(0)<=MAX_EDGE && reader.getHeight(0)<=MAX_EDGE) image=reader.read(0);
                    } finally { reader.dispose(); }
                }
            }
            if(image!=null && KEYED.contains(name)) image=keyOut(image);
        } catch(java.io.IOException|RuntimeException ignored) { }
        CACHE.put(key,image);
        return image;
    }

    /** Makes the top-left colour transparent, which is how these sheets mark their ground. */
    private static BufferedImage keyOut(BufferedImage source) {
        var out=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
        int background=source.getRGB(0,0);
        for(int y=0;y<source.getHeight();y++)
            for(int x=0;x<source.getWidth();x++) {
                int pixel=source.getRGB(x,y);
                out.setRGB(x,y,pixel==background?0:pixel);
            }
        return out;
    }
}
