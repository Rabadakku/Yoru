package dev.yoru.assets;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;

/**
 * The study-music library (#14).
 *
 * Builds real WAV files rather than shipping fixtures, so the decode check is
 * actually exercised: a file that merely ends in .wav is not a wav, and that is
 * the failure that would otherwise only appear at play time.
 */
public final class MusicTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private interface Action {void run()throws Exception;}
    private static void rejects(Action action,String why)throws Exception{
        try{action.run();}catch(IOException expected){checks++;return;}
        throw new AssertionError(why);
    }

    /** A fraction of a second of quiet, written as a real WAV. */
    private static void writeWav(Path file,double seconds)throws IOException {
        var format=new AudioFormat(8000f,16,1,true,false);
        int frames=(int)(8000*seconds);
        var data=new byte[frames*2];
        try(var stream=new AudioInputStream(new ByteArrayInputStream(data),format,frames)) {
            AudioSystem.write(stream,AudioFileFormat.Type.WAVE,file.toFile());
        }
    }

    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("yoru-music-home-");
        String realHome=System.getProperty("user.home");
        System.setProperty("user.home",home.toString());
        Path source=Files.createTempDirectory("yoru-music-src-");
        try {
            check(MusicLibrary.tracks().isEmpty(),"An empty library holds nothing");
            check(MusicLibrary.survey().empty(),"and reports itself empty");
            check(MusicLibrary.survey().summary().contains("No music"),"and says so");

            // What the JDK can and cannot decode, named rather than assumed.
            check(MusicLibrary.playable("study.wav"),"WAV is playable");
            check(MusicLibrary.playable("Study.AIFF"),"AIFF is playable, case-insensitively");
            check(MusicLibrary.playable("tone.au"),"AU is playable");
            check(!MusicLibrary.playable("track.mp3"),"MP3 is not");
            check(MusicLibrary.knownUnplayable("track.mp3"),"and is recognised so the message can say why");
            check(MusicLibrary.knownUnplayable("song.m4a"),"as is M4A");
            check(MusicLibrary.knownUnplayable("album.flac"),"and FLAC");
            check(!MusicLibrary.playable("notes.txt"),"A text file is not audio");
            check(MusicLibrary.supportedFormats().contains("WAV"),"The format message names what does work");

            writeWav(source.resolve("b-second.wav"),0.2);
            writeWav(source.resolve("a-first.wav"),0.2);
            Files.writeString(source.resolve("readme.txt"),"not audio");
            Files.writeString(source.resolve("song.mp3"),"not really an mp3 either");
            // A lie: the right extension, the wrong contents.
            Files.writeString(source.resolve("broken.wav"),"this is not a wav file");

            var report=MusicLibrary.install(source);
            check(report.tracks()==2,"Both real tracks were imported, found "+report.tracks());
            check(report.unplayable()==1,"The MP3 is counted as unplayable, not merely skipped");
            check(report.skipped()>=2,"The text file and the fake wav were skipped");
            check(report.summary().contains("2 track"),"The summary counts what was imported");

            var tracks=MusicLibrary.tracks();
            check(tracks.size()==2,"The library lists both");
            check(tracks.getFirst().getFileName().toString().startsWith("a-"),
                "Tracks come out in a stable order, so a playlist does not reshuffle itself");
            for(var track:tracks) AudioSystem.getAudioFileFormat(track.toFile());
            checks++;
            check(tracks.stream().noneMatch(t->t.getFileName().toString().equals("broken.wav")),
                "A file that only claims to be a wav is not kept");

            // Nothing playable at all is an error rather than a silent no-op.
            Path onlyMp3=Files.createTempDirectory("yoru-music-mp3-");
            Path emptyDir=Files.createTempDirectory("yoru-music-empty-");
            Files.writeString(onlyMp3.resolve("a.mp3"),"x");
            try {
                MusicLibrary.clear();
                rejects(()->MusicLibrary.install(onlyMp3),"A folder of MP3s is refused with a reason");
                rejects(()->MusicLibrary.install(emptyDir),"An empty folder is refused");
                rejects(()->MusicLibrary.install(home.resolve("nope")),"A missing path is refused");
            } finally {
                delete(onlyMp3); delete(emptyDir);
            }

            // Archive entries cannot climb out of the library.
            check(MusicLibrary.safeName("../../escape.wav").equals("escape.wav"),"A relative path is stripped");
            check(MusicLibrary.safeName("a/b/c.wav").equals("c.wav"),"Directories are stripped");
            check(MusicLibrary.safeName("..").equals("track"),"A name that is only dots becomes a name");

            var zip=source.resolve("bundle.zip");
            var temp=source.resolve("temp.wav");
            writeWav(temp,0.2);
            try(var out=new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new java.util.zip.ZipEntry("../../escaped.wav"));
                out.write(Files.readAllBytes(temp));
                out.closeEntry();
            }
            MusicLibrary.clear();
            var zipped=MusicLibrary.install(zip);
            check(zipped.tracks()==1,"The zip entry was imported");
            check(MusicLibrary.tracks().getFirst().getParent().equals(MusicLibrary.root()),
                "and landed inside the library, not above it");
            check(Files.notExists(MusicLibrary.root().getParent().resolve("escaped.wav")),
                "A zip cannot write outside the library");

            int removed=MusicLibrary.clear();
            check(removed==1,"Clearing removes what was there");
            check(MusicLibrary.tracks().isEmpty(),"and leaves the library empty");
        } finally {
            System.setProperty("user.home",realHome);
            delete(home); delete(source);
        }
        System.out.println("PASS: "+checks+" music checks (formats, decode check, path escapes, ordering)");
    }

    private static void delete(Path dir)throws IOException {
        if(!Files.exists(dir)) return;
        try(var files=Files.walk(dir)) {
            for(var path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
