package dev.yoru.assets;

import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Study music, from the user's own files (#14). Nothing is ever bundled.
 *
 * The same shape as {@link ArtworkLibrary}: point it at a folder or a zip, it
 * copies what it can use into its own directory, and reports what it skipped.
 *
 * <h2>Formats, honestly</h2>
 * The JDK plays WAV, AIFF and AU and nothing else. MP3, M4A, FLAC and OGG need
 * a codec this project will not take on — {@code docs/ARCHITECTURE.md} opens
 * with "Swing and the JDK only, no runtime dependencies", and a music feature is
 * not a good reason to break that. Most people's libraries are MP3, so this is a
 * real limitation rather than a footnote, and {@link #supportedFormats()} exists
 * so the interface can say so before someone picks a folder and finds nothing
 * imported.
 */
public final class MusicLibrary {
    private MusicLibrary() { }

    /** Extensions the JDK can actually decode, lower-case, without the dot. */
    private static final Set<String> PLAYABLE=Set.of("wav","wave","aif","aiff","au","snd");
    /** Extensions people will try, so the skip message can name them. */
    private static final Set<String> KNOWN_UNPLAYABLE=Set.of("mp3","m4a","aac","flac","ogg","opus","wma","alac");
    private static final long MAX_TRACK_BYTES=200L*1024*1024;
    private static final int MAX_TRACKS=500;

    public static Path root() {
        return Path.of(System.getProperty("user.home"),".yoru","music");
    }

    /** What the library holds, or what an import found. */
    public record Report(int tracks,int skipped,int unplayable) {
        public boolean empty() { return tracks==0; }
        public String summary() {
            if(empty()&&unplayable>0) return unplayable+" file"+(unplayable==1?"":"s")
                +" could not be used: "+supportedFormats()+".";
            if(empty()) return "No music installed.";
            return tracks+" track"+(tracks==1?"":"s")
                +(unplayable>0?"  ·  "+unplayable+" skipped, "+supportedFormats():"")
                +(skipped>0?"  ·  "+skipped+" other file"+(skipped==1?"":"s")+" skipped":"");
        }
    }

    /** Named rather than hardcoded in three messages. */
    public static String supportedFormats() {
        return "this build plays WAV, AIFF and AU only";
    }

    public static boolean playable(String name) {
        return PLAYABLE.contains(extension(name));
    }
    public static boolean knownUnplayable(String name) {
        return KNOWN_UNPLAYABLE.contains(extension(name));
    }
    private static String extension(String name) {
        int dot=name.lastIndexOf('.');
        return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
    }

    /** Tracks currently installed, in a stable order so a playlist does not shuffle itself. */
    public static List<Path> tracks() {
        Path root=root();
        if(!Files.isDirectory(root)) return List.of();
        try(var files=Files.list(root)) {
            return files.filter(Files::isRegularFile)
                .filter(p->playable(p.getFileName().toString()))
                .sorted(Comparator.comparing(p->p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch(IOException e) { return List.of(); }
    }

    public static Report survey() {
        return new Report(tracks().size(),0,0);
    }

    public static Report install(Path source) throws IOException {
        if(!Files.exists(source)) throw new IOException("That file or folder no longer exists.");
        Path root=root();
        Files.createDirectories(root);
        int[] skipped={0}, unplayable={0};

        if(Files.isDirectory(source)) {
            try(var walk=Files.walk(source)) {
                for(Path file:walk.filter(Files::isRegularFile).toList()) copyOne(file,root,skipped,unplayable);
            }
        } else if(extension(source.getFileName().toString()).equals("zip")) {
            unpack(source,root,skipped,unplayable);
        } else {
            copyOne(source,root,skipped,unplayable);
        }

        var found=new Report(tracks().size(),skipped[0],unplayable[0]);
        if(found.empty()) throw new IOException(unplayable[0]>0
            ? "Nothing could be used — "+supportedFormats()+"."
            : "No audio found. Choose a folder, a .zip, or a single file; "+supportedFormats()+".");
        return found;
    }

    private static void copyOne(Path file,Path root,int[] skipped,int[] unplayable) throws IOException {
        String name=file.getFileName().toString();
        if(knownUnplayable(name)) { unplayable[0]++; return; }
        if(!playable(name)) { skipped[0]++; return; }
        if(Files.size(file)>MAX_TRACK_BYTES) { skipped[0]++; return; }
        if(tracks().size()>=MAX_TRACKS) { skipped[0]++; return; }
        // Decode-checked before it is kept: a .wav that is not a wav is worse
        // than a file that was skipped, because it only fails at play time.
        try { AudioSystem.getAudioFileFormat(file.toFile()); }
        catch(Exception unreadable) { skipped[0]++; return; }
        Files.copy(file,root.resolve(safeName(name)),StandardCopyOption.REPLACE_EXISTING);
    }

    private static void unpack(Path zip,Path root,int[] skipped,int[] unplayable) throws IOException {
        try(var in=new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            for(var entry=in.getNextEntry();entry!=null;entry=in.getNextEntry()) {
                if(entry.isDirectory()) continue;
                String name=Path.of(entry.getName()).getFileName().toString();
                if(knownUnplayable(name)) { unplayable[0]++; continue; }
                if(!playable(name)||entry.getSize()>MAX_TRACK_BYTES) { skipped[0]++; continue; }
                Path target=root.resolve(safeName(name));
                // Never outside the library, whatever the archive claims.
                if(!target.normalize().startsWith(root.normalize())) { skipped[0]++; continue; }
                Files.copy(in,target,StandardCopyOption.REPLACE_EXISTING);
                try { AudioSystem.getAudioFileFormat(target.toFile()); }
                catch(Exception unreadable) { Files.deleteIfExists(target); skipped[0]++; }
            }
        }
    }

    /** Strips any path the source tried to carry, so an archive cannot escape. */
    static String safeName(String name) {
        String base=name.replace('\\','/');
        base=base.substring(base.lastIndexOf('/')+1);
        base=base.replaceAll("[^A-Za-z0-9._ -]","_").strip();
        if(base.isEmpty()||base.equals(".")||base.equals("..")) base="track";
        return base.length()>120?base.substring(base.length()-120):base;
    }

    /** Removes everything the library holds. Explicit, never automatic. */
    public static int clear() throws IOException {
        int removed=0;
        for(Path track:tracks()) { Files.deleteIfExists(track); removed++; }
        return removed;
    }
}
