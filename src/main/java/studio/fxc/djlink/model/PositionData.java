package studio.fxc.djlink.model;

/** Where one player is in its track, in beat-grid beats. */
public final class PositionData {
    public static final int REASON_BEAT = 0;
    public static final int REASON_MOVEMENT = 1;

    public final int trackId;
    public final int beatNumber;
    public final boolean playing;
    public final boolean definitive;
    public final int reason;

    public PositionData(int trackId, int beatNumber, boolean playing, boolean definitive, int reason) {
        this.trackId = trackId;
        this.beatNumber = beatNumber;
        this.playing = playing;
        this.definitive = definitive;
        this.reason = reason;
    }
}
