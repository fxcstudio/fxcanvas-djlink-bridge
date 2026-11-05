package studio.fxc.djlink.assets;

import java.util.Locale;

/**
 * A track waveform in the bridge's normalized form, the same for every
 * rekordbox waveform style.
 *
 * Each segment is four bytes: {@code height, low, mid, high}, all 0-255 on
 * one scale, where 255 is the loudest value anywhere in the same waveform.
 * {@code height} is the column's overall level. When {@link #bands} is true,
 * {@code low}, {@code mid} and {@code high} are the levels of each band on
 * their own (not stacked), so {@code height} is about the largest of them.
 * Three-band waveforms carry real band levels; RGB waveforms carry
 * rekordbox's color channels (red = low, green = mid, blue = high), which are
 * band levels in the same sense; blue waveforms have no bands (all 0).
 */
public final class WaveformData {
    public enum Kind {
        /** The whole track in a few hundred to 1200 columns. */
        PREVIEW,
        /** 150 segments per second of audio. */
        DETAIL;

        public String oscName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Style {
        BLUE, RGB, THREE_BAND;

        public String oscName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Detail waveforms have 150 segments (half-frames) per second. */
    public static final float DETAIL_SEGMENTS_PER_SECOND = 150f;
    public static final int BYTES_PER_SEGMENT = 4;

    public final int trackId;
    public final Kind kind;
    public final Style style;
    public final boolean bands;
    /** Segments per second of audio, 0 when unknown (a preview of a track of unknown length). */
    public final float segmentsPerSecond;
    /** Track length the waveform covers, 0 when unknown. */
    public final long durationMs;
    /** {@code segmentCount() * 4} bytes. */
    public final byte[] segments;

    public WaveformData(int trackId, Kind kind, Style style, boolean bands, float segmentsPerSecond,
                        long durationMs, byte[] segments) {
        if (segments.length % BYTES_PER_SEGMENT != 0) {
            throw new IllegalArgumentException("Segment data must be a multiple of 4 bytes");
        }
        this.trackId = trackId;
        this.kind = kind;
        this.style = style;
        this.bands = bands;
        this.segmentsPerSecond = Math.max(0f, segmentsPerSecond);
        this.durationMs = Math.max(0L, durationMs);
        this.segments = segments;
    }

    public int segmentCount() {
        return segments.length / BYTES_PER_SEGMENT;
    }

    public int height(int segment) {
        return segments[segment * BYTES_PER_SEGMENT] & 0xff;
    }

    public int low(int segment) {
        return segments[segment * BYTES_PER_SEGMENT + 1] & 0xff;
    }

    public int mid(int segment) {
        return segments[segment * BYTES_PER_SEGMENT + 2] & 0xff;
    }

    public int high(int segment) {
        return segments[segment * BYTES_PER_SEGMENT + 3] & 0xff;
    }

    /**
     * Build a waveform from raw (unscaled) levels. Every value is scaled by
     * the same factor, so the loudest value anywhere becomes 255 and the
     * bands keep their relative levels.
     *
     * @param height column levels; with bands, pass null to use the largest band
     * @param low    band levels, or null (with mid and high) for a waveform without bands
     */
    public static WaveformData normalize(int trackId, Kind kind, Style style, float segmentsPerSecond,
                                         long durationMs, float[] height, float[] low, float[] mid, float[] high) {
        final boolean withBands = low != null && mid != null && high != null;
        final int n = height != null ? height.length : (withBands ? low.length : 0);
        float[] h = height;
        if (h == null) {
            h = new float[n];
            for (int i = 0; i < n; i++) h[i] = Math.max(low[i], Math.max(mid[i], high[i]));
        }
        float max = 0f;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, h[i]);
            if (withBands) max = Math.max(max, Math.max(low[i], Math.max(mid[i], high[i])));
        }
        final float scale = max > 0f ? 255f / max : 0f;
        byte[] out = new byte[n * BYTES_PER_SEGMENT];
        for (int i = 0; i < n; i++) {
            final int base = i * BYTES_PER_SEGMENT;
            out[base] = clampByte(h[i] * scale);
            if (withBands) {
                out[base + 1] = clampByte(low[i] * scale);
                out[base + 2] = clampByte(mid[i] * scale);
                out[base + 3] = clampByte(high[i] * scale);
            }
        }
        return new WaveformData(trackId, kind, style, withBands, segmentsPerSecond, durationMs, out);
    }

    private static byte clampByte(float v) {
        return (byte) Math.max(0, Math.min(255, Math.round(v)));
    }
}
