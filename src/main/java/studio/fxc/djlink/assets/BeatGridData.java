package studio.fxc.djlink.assets;

/** A track's beat grid: when each beat-grid beat (1-based) falls, its tempo and place in the bar. */
public final class BeatGridData {
    public final int trackId;
    /** Milliseconds into the track of beat {@code i + 1}. */
    public final int[] timesMs;
    /** Tempo at beat {@code i + 1}, in BPM times 100. */
    public final int[] tempo100;
    /** Beat {@code i + 1}'s place in its bar, 1-4. */
    public final int[] beatInBar;

    public BeatGridData(int trackId, int[] timesMs, int[] tempo100, int[] beatInBar) {
        if (timesMs.length != tempo100.length || timesMs.length != beatInBar.length) {
            throw new IllegalArgumentException("Beat grid arrays differ in length");
        }
        this.trackId = trackId;
        this.timesMs = timesMs;
        this.tempo100 = tempo100;
        this.beatInBar = beatInBar;
    }

    public int beatCount() {
        return timesMs.length;
    }
}
