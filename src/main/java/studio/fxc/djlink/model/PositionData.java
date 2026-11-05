package studio.fxc.djlink.model;

/** Where one player is in its track. */
public final class PositionData {
    public static final int REASON_BEAT = 0;
    public static final int REASON_MOVEMENT = 1;

    public final int trackId;
    /** Beat-grid beat, 1-based. */
    public final int beatNumber;
    public final boolean playing;
    public final boolean definitive;
    public final int reason;
    /** Playback position in milliseconds (since 1.1.0). */
    public final long positionMs;
    /** Track length in milliseconds from the track metadata, 0 when unknown (since 1.1.0). */
    public final long durationMs;
    /** Number of beats in the track's beat grid, 0 when unknown (since 1.1.0). */
    public final int beatCount;
    /** Playback speed multiplier, 1.0 = normal (since 1.1.0). */
    public final float pitch;

    public PositionData(int trackId, int beatNumber, boolean playing, boolean definitive, int reason) {
        this(trackId, beatNumber, playing, definitive, reason, 0, 0, 0, 1.0f);
    }

    public PositionData(int trackId, int beatNumber, boolean playing, boolean definitive, int reason,
                        long positionMs, long durationMs, int beatCount, float pitch) {
        this.trackId = trackId;
        this.beatNumber = beatNumber;
        this.playing = playing;
        this.definitive = definitive;
        this.reason = reason;
        this.positionMs = Math.max(0, positionMs);
        this.durationMs = Math.max(0, durationMs);
        this.beatCount = Math.max(0, beatCount);
        this.pitch = pitch;
    }
}
