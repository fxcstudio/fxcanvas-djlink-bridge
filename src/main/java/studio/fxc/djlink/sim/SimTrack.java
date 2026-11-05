package studio.fxc.djlink.sim;

import studio.fxc.djlink.model.PhraseData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A track a simulated deck can play: a constant-tempo beat grid and, unless it
 * stands for an unanalyzed track, a rekordbox-style phrase structure.
 */
public final class SimTrack {
    public final int trackId;
    public final String title;
    public final String artist;
    /** Track tempo (beats per minute at pitch 1.0). */
    public final float bpm;
    /** Time of the first beat, in milliseconds. */
    public final long firstBeatMs;
    /** Beats in the beat grid. */
    public final int beatCount;
    /** Song structure, or null for a track without phrase analysis. */
    public final PhraseData phrases;

    public SimTrack(int trackId, String title, String artist, float bpm, long firstBeatMs, int beatCount,
                    PhraseData phrases) {
        if (bpm <= 0) throw new IllegalArgumentException("bpm must be positive");
        if (beatCount < 1) throw new IllegalArgumentException("beatCount must be at least 1");
        this.trackId = trackId;
        this.title = title;
        this.artist = artist;
        this.bpm = bpm;
        this.firstBeatMs = Math.max(0, firstBeatMs);
        this.beatCount = beatCount;
        this.phrases = phrases;
    }

    /** Milliseconds into the track at a (0-based, fractional) beat position. */
    public long millisAt(double beatPosition) {
        return firstBeatMs + Math.round(beatPosition * 60000.0 / bpm);
    }

    public long durationMs() {
        return millisAt(beatCount);
    }

    /** The same track with another rekordbox Lighting bank. */
    public SimTrack withBank(String bank) {
        if (phrases == null) return this;
        return new SimTrack(trackId, title, artist, bpm, firstBeatMs, beatCount,
                new PhraseData(phrases.trackId, phrases.mood, bank, phrases.endBeat, phrases.entries));
    }

    // =========================================================================
    // Built-in demo tracks
    // =========================================================================

    /** One phrase of a demo layout: raw kind, label, length in bars, fill-in in its last 2 bars. */
    private static final class Part {
        final String rawKind;
        final String label;
        final int bars;
        final boolean fill;

        Part(String rawKind, String label, int bars, boolean fill) {
            this.rawKind = rawKind;
            this.label = label;
            this.bars = bars;
            this.fill = fill;
        }
    }

    private static Part p(String raw, String label, int bars) {
        return new Part(raw, label, bars, false);
    }

    private static Part f(String raw, String label, int bars) {
        return new Part(raw, label, bars, true);
    }

    private static SimTrack demo(int trackId, String title, float bpm, String mood, String bank, Part... parts) {
        List<PhraseData.Entry> entries = new ArrayList<>();
        int beat = 1;
        for (Part part : parts) {
            int length = part.bars * 4;
            int fillBeat = part.fill ? beat + length - 8 : 0;
            entries.add(new PhraseData.Entry(beat, part.rawKind, part.label, part.fill, fillBeat));
            beat += length;
        }
        int endBeat = beat;                 // first beat after the last phrase
        int beatCount = endBeat - 1 + 8;    // two bars of tail, like a real track
        return new SimTrack(trackId, title, "FXCanvas Simulator", bpm, 120, beatCount,
                new PhraseData(trackId, mood, bank, endBeat, entries));
    }

    /** Names accepted by {@link #builtIn(String)}. */
    public static final String[] BUILT_IN = {"a", "b", "c", "short", "plain"};

    /**
     * A built-in track.
     *
     * <ul>
     *   <li>{@code a}: 128 BPM, high mood, bank club_1: Intro, Up, Down, Chorus, Up, Chorus, Outro</li>
     *   <li>{@code b}: 124 BPM, mid mood, bank hot: Intro, Verse, Chorus, Bridge, Verse, Chorus, Outro</li>
     *   <li>{@code c}: 122 BPM, low mood, bank cool: Intro, Verse 1, Verse 2, Chorus, Outro</li>
     *   <li>{@code short}: 130 BPM, 4-bar phrases with fill-ins, for fast tests</li>
     *   <li>{@code plain}: 126 BPM, no phrase analysis</li>
     * </ul>
     *
     * @return the track, or null for an unknown name
     */
    public static SimTrack builtIn(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "a":
                return demo(9001, "Simulated Track A", 128f, "high", "club_1",
                        p("intro", "Intro 1", 16), f("up", "Up 1", 16), p("down", "Down", 8),
                        f("chorus", "Chorus 1", 16), p("up", "Up 2", 16), f("chorus", "Chorus 2", 16),
                        p("outro", "Outro 1", 16));
            case "b":
                return demo(9002, "Simulated Track B", 124f, "mid", "hot",
                        p("intro", "Intro", 16), f("verse_1", "Verse 1", 16), f("chorus", "Chorus", 16),
                        p("bridge", "Bridge", 8), p("verse_2", "Verse 2", 16), f("chorus", "Chorus", 16),
                        p("outro", "Outro", 16));
            case "c":
                return demo(9003, "Simulated Track C", 122f, "low", "cool",
                        p("intro", "Intro", 8), p("verse_1", "Verse 1", 16), f("verse_2", "Verse 2", 16),
                        f("chorus", "Chorus", 16), p("outro", "Outro", 8));
            case "short":
                return demo(9004, "Simulated Short Track", 130f, "high", "vivid",
                        f("intro", "Intro 1", 4), f("up", "Up 1", 4), f("chorus", "Chorus 1", 4),
                        f("down", "Down", 4), f("chorus", "Chorus 2", 4), p("outro", "Outro 1", 4));
            case "plain":
                return new SimTrack(9005, "Simulated Unanalyzed Track", "FXCanvas Simulator", 126f, 80,
                        96 * 4, null);
            default:
                return null;
        }
    }
}
