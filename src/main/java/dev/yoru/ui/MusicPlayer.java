package dev.yoru.ui;

import dev.yoru.assets.MusicLibrary;
import javax.sound.sampled.*;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Background audio while the timer runs (#14).
 *
 * Plays the library in order and loops it. Deliberately dumb: no shuffle, no
 * queue, no now-playing. This is something to leave on, not something to
 * operate, and every control it does not have is one that cannot interrupt a
 * study session.
 *
 * Enabled and volume live in Preferences rather than the vault. Which speakers
 * are attached is a fact about this machine, not about the workspace, and a
 * volume that travelled between a laptop and a desk would be wrong at one end.
 */
final class MusicPlayer implements AutoCloseable {
    private static final Preferences PREFERENCES=Preferences.userRoot().node("dev/yoru/desktop");
    private static final String ENABLED="music.enabled", VOLUME="music.volume";

    private Clip clip;
    private int index;
    private boolean playing;

    static boolean enabled() { return PREFERENCES.getBoolean(ENABLED,false); }
    static void enabled(boolean value) { PREFERENCES.putBoolean(ENABLED,value); }
    /** 0..100. */
    static int volume() { return Math.max(0,Math.min(100,PREFERENCES.getInt(VOLUME,60))); }
    static void volume(int value) { PREFERENCES.putInt(VOLUME,Math.max(0,Math.min(100,value))); }

    /**
     * Brings playback in line with whether a session is running.
     *
     * Called from the ticker rather than from the clock-in and clock-out buttons,
     * so a session recovered on open — which never passes through either — still
     * starts the music.
     */
    void sync(boolean sessionRunning) {
        boolean want=sessionRunning&&enabled()&&!MusicLibrary.tracks().isEmpty();
        if(want==playing) { if(playing) applyVolume(); return; }
        if(want) start(); else stop();
    }

    private void start() {
        var tracks=MusicLibrary.tracks();
        if(tracks.isEmpty()) return;
        playing=true;
        play(tracks,index%tracks.size());
    }

    private void play(List<Path> tracks,int at) {
        stopClip();
        index=at;
        try {
            var stream=AudioSystem.getAudioInputStream(tracks.get(at).toFile());
            clip=AudioSystem.getClip();
            clip.open(stream);
            applyVolume();
            clip.addLineListener(event->{
                if(event.getType()!=LineEvent.Type.STOP||!playing) return;
                // Straight into the next track, looping the library. Queued on the
                // event thread because a line listener runs on the audio thread.
                javax.swing.SwingUtilities.invokeLater(()->{
                    if(!playing) return;
                    var current=MusicLibrary.tracks();
                    if(current.isEmpty()) { stop(); return; }
                    play(current,(at+1)%current.size());
                });
            });
            clip.start();
        } catch(Exception unplayable) {
            // One bad file should not silence the rest of the library.
            stopClip();
            var remaining=MusicLibrary.tracks();
            if(playing&&remaining.size()>1&&at+1<remaining.size()) play(remaining,at+1);
            else playing=false;
        }
    }

    private void applyVolume() {
        if(clip==null||!clip.isOpen()) return;
        if(!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        var gain=(FloatControl)clip.getControl(FloatControl.Type.MASTER_GAIN);
        // Decibels, not percent: a linear percentage sounds wrong because
        // loudness is logarithmic. Silence at zero rather than a faint hiss.
        float level=volume()/100f;
        gain.setValue(level<=0f?gain.getMinimum()
            :(float)Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),20*Math.log10(level))));
    }

    void stop() {
        playing=false;
        stopClip();
    }

    private void stopClip() {
        if(clip==null) return;
        try { clip.stop(); clip.close(); } catch(Exception ignored) { }
        clip=null;
    }

    boolean isPlaying() { return playing; }

    @Override public void close() { stop(); }
}
