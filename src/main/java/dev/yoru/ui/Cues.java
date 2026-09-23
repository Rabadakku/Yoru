package dev.yoru.ui;

import dev.yoru.application.Pomodoro.Cue;
import java.util.prefs.Preferences;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;

/**
 * The sounds a pomodoro makes when an interval ends (#61).
 *
 * Made here, a few tones summed and faded, because Yoru ships no audio: the
 * end of work falls, the end of a break rises, so the next phase is clear
 * without looking. Whether they play, which voice, and how loud are this
 * computer's own, like the study music's volume and apart from it.
 *
 * Playing never gets in the way of timing: it happens on a thread of its own,
 * and a computer with no sound out, or one that refuses the line, simply
 * stays quiet.
 */
final class Cues {
    private Cues() { }

    private static final Preferences PREFERENCES = Preferences.userRoot().node("dev/yoru/desktop");
    private static final String ON = "pomodoro.sound.on", VOICE = "pomodoro.sound.voice", VOLUME = "pomodoro.sound.volume";

    /** A voice for the cues: the same two phrases in different timbres. */
    enum Voice {
        BELL("Bell"), CHIME("Chime"), SOFT("Soft");
        final String label;
        Voice(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    static final float RATE = 44_100f;
    static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    static boolean on() { return PREFERENCES.getBoolean(ON, true); }
    static void on(boolean value) { PREFERENCES.putBoolean(ON, value); }
    static Voice voice() {
        try { return Voice.valueOf(PREFERENCES.get(VOICE, Voice.BELL.name())); }
        catch (IllegalArgumentException unknown) { return Voice.BELL; }
    }
    static void voice(Voice value) { PREFERENCES.put(VOICE, value.name()); }
    /** 0..100. */
    static int volume() { return Math.max(0, Math.min(100, PREFERENCES.getInt(VOLUME, 70))); }
    static void volume(int value) { PREFERENCES.putInt(VOLUME, Math.max(0, Math.min(100, value))); }

    /** Plays the cue as set, unless the cues are off or silent. Returns whether anything was asked to play. */
    static boolean play(Cue cue) {
        if (!on() || volume() == 0) return false;
        play(pcm(cue, voice(), volume()));
        return true;
    }

    /** Plays a cue in a given voice and volume, for Settings' previews. */
    static void preview(Cue cue, Voice voice, int volume) {
        if (volume > 0) play(pcm(cue, voice, volume));
    }

    /** The notes of each phrase, in hertz: falling to rest, rising to work. */
    static double[] notes(Cue cue) {
        return cue == Cue.WORK_DONE ? new double[]{880.0, 659.26} : new double[]{659.26, 880.0};
    }

    /**
     * A cue as 16-bit mono samples at {@link #RATE}. Each note is a sine with
     * a partial or two, faded out; the voices differ in their partials, their
     * length, and the soft one sits an octave down with a slow start.
     */
    static byte[] pcm(Cue cue, Voice voice, int volume) {
        double gap = voice == Voice.CHIME ? 0.16 : 0.24;
        double ring = switch (voice) { case BELL -> 1.1; case CHIME -> 0.7; case SOFT -> 1.3; };
        double octave = voice == Voice.SOFT ? 0.5 : 1.0;
        var notes = notes(cue);
        double seconds = gap * (notes.length - 1) + ring;
        int samples = (int) (seconds * RATE);
        var mix = new double[samples];
        for (int n = 0; n < notes.length; n++) {
            int from = (int) (n * gap * RATE);
            double f = notes[n] * octave;
            for (int i = from; i < samples; i++) {
                double t = (i - from) / RATE;
                if (t > ring) break;
                double attack = voice == Voice.SOFT ? Math.min(1, t / 0.08) : Math.min(1, t / 0.005);
                double decay = Math.exp(-t * (voice == Voice.CHIME ? 6.0 : 3.5));
                double tone = Math.sin(2 * Math.PI * f * t);
                if (voice == Voice.BELL) tone += 0.35 * Math.sin(2 * Math.PI * f * 2.0 * t) + 0.15 * Math.sin(2 * Math.PI * f * 3.01 * t);
                if (voice == Voice.CHIME) tone += 0.2 * Math.sin(2 * Math.PI * f * 4.0 * t);
                mix[i] += tone * attack * decay;
            }
        }
        double peak = 0;
        for (double v : mix) peak = Math.max(peak, Math.abs(v));
        double gain = peak == 0 ? 0 : (volume / 100.0) * 0.8 * Short.MAX_VALUE / peak;
        var out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int v = (int) Math.round(mix[i] * gain);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /** The loudest sample, for the tests: how loud a cue actually is. */
    static int peak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) peak = Math.max(peak, Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8))));
        return peak;
    }

    /**
     * Where a cue's samples go: the sound out, unless a test has put its own
     * ear here. A test suite that chimed through the speakers of whoever ran
     * it would be a poor thing to leave running in the background.
     */
    static java.util.function.Consumer<byte[]> out = Cues::speak;

    private static void play(byte[] pcm) { out.accept(pcm); }

    private static void speak(byte[] pcm) {
        Thread.ofPlatform().daemon().name("yoru-cue").start(() -> {
            try (var line = AudioSystem.getSourceDataLine(FORMAT)) {
                line.open(FORMAT);
                line.start();
                line.write(pcm, 0, pcm.length);
                line.drain();
            } catch (Exception | LinkageError quiet) {
                // No sound out, or none to be had: the phase has still changed,
                // and the window says so.
            }
        });
    }
}
