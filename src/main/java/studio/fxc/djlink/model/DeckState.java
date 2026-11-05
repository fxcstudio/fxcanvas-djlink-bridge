package studio.fxc.djlink.model;

import java.util.Objects;

/**
 * What a player's transport is doing: play state, loop and end of track.
 * Published on change only, so equality matters.
 */
public final class DeckState {
    public final int trackId;
    /** beat-link's PlayState1 name, lower case: "playing", "looping", "paused", "cued", "ended", ... */
    public final String playState;
    public final boolean looping;
    /** Active loop start and end in milliseconds (CDJ-3000 and similar); 0 when unknown. */
    public final long loopStartMs;
    public final long loopEndMs;
    /** Length of the active loop in beats when the player reports it; 0 when unknown. */
    public final int loopBeats;
    public final boolean atEnd;
    public final boolean cued;

    public DeckState(int trackId, String playState, boolean looping, long loopStartMs, long loopEndMs,
                     int loopBeats, boolean atEnd, boolean cued) {
        this.trackId = trackId;
        this.playState = playState == null ? "unknown" : playState;
        this.looping = looping;
        this.loopStartMs = Math.max(0, loopStartMs);
        this.loopEndMs = Math.max(0, loopEndMs);
        this.loopBeats = Math.max(0, loopBeats);
        this.atEnd = atEnd;
        this.cued = cued;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeckState)) return false;
        DeckState d = (DeckState) o;
        return trackId == d.trackId && looping == d.looping && loopStartMs == d.loopStartMs
                && loopEndMs == d.loopEndMs && loopBeats == d.loopBeats && atEnd == d.atEnd
                && cued == d.cued && playState.equals(d.playState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trackId, playState, looping, loopStartMs, loopEndMs, loopBeats, atEnd, cued);
    }
}
