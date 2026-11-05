package studio.fxc.djlink.assets;

import org.deepsymmetry.beatlink.data.BeatGrid;
import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformFinder;
import org.deepsymmetry.beatlink.data.WaveformPreview;

import java.awt.Color;
import java.nio.ByteBuffer;

/**
 * beat-link waveforms and beat grids to the bridge's normalized forms.
 *
 * Band weights follow beat-link's own drawing code, so relative band levels
 * look the way rekordbox and the players draw them.
 */
public final class WaveformConverter {
    private WaveformConverter() {
    }

    static WaveformData.Style style(WaveformFinder.WaveformStyle style) {
        switch (style) {
            case THREE_BAND: return WaveformData.Style.THREE_BAND;
            case RGB: return WaveformData.Style.RGB;
            default: return WaveformData.Style.BLUE;
        }
    }

    public static WaveformData fromDetail(WaveformDetail detail, int trackId) {
        final int n = detail.getFrameCount();
        final WaveformData.Style style = style(detail.style);
        final long durationMs = detail.getTotalTime();
        final float sps = WaveformData.DETAIL_SEGMENTS_PER_SECOND;
        switch (detail.style) {
            case THREE_BAND: {
                final ByteBuffer raw = detail.getData();
                float[] low = new float[n];
                float[] mid = new float[n];
                float[] high = new float[n];
                for (int i = 0; i < n; i++) {
                    final int base = i * 3;
                    mid[i] = (raw.get(base) & 0xff) * 0.3f;
                    high[i] = (raw.get(base + 1) & 0xff) * 0.06f;
                    low[i] = (raw.get(base + 2) & 0xff) * 0.4f;
                }
                return WaveformData.normalize(trackId, WaveformData.Kind.DETAIL, style, sps, durationMs,
                        null, low, mid, high);
            }
            case RGB: {
                float[] height = new float[n];
                float[] low = new float[n];
                float[] mid = new float[n];
                float[] high = new float[n];
                for (int i = 0; i < n; i++) {
                    height[i] = detail.segmentHeight(i, 1);
                    final Color c = detail.segmentColor(i, 1);
                    low[i] = height[i] * c.getRed() / 255f;
                    mid[i] = height[i] * c.getGreen() / 255f;
                    high[i] = height[i] * c.getBlue() / 255f;
                }
                return WaveformData.normalize(trackId, WaveformData.Kind.DETAIL, style, sps, durationMs,
                        height, low, mid, high);
            }
            default: {
                float[] height = new float[n];
                for (int i = 0; i < n; i++) height[i] = detail.segmentHeight(i, 1);
                return WaveformData.normalize(trackId, WaveformData.Kind.DETAIL, style, sps, durationMs,
                        height, null, null, null);
            }
        }
    }

    /** @param durationMs track length (from its metadata), 0 when unknown */
    public static WaveformData fromPreview(WaveformPreview preview, int trackId, long durationMs) {
        final int n = preview.segmentCount;
        final WaveformData.Style style = style(preview.style);
        final float sps = durationMs > 0 ? n * 1000f / durationMs : 0f;
        final ByteBuffer raw = preview.getData();
        switch (preview.style) {
            case THREE_BAND: {
                float[] low = new float[n];
                float[] mid = new float[n];
                float[] high = new float[n];
                for (int i = 0; i < n; i++) {
                    final int base = i * 3;
                    mid[i] = (raw.get(base) & 0xff) * 0.32f;
                    high[i] = (raw.get(base + 1) & 0xff) * 0.25f;
                    low[i] = (raw.get(base + 2) & 0xff) * 0.49f;
                }
                return WaveformData.normalize(trackId, WaveformData.Kind.PREVIEW, style, sps, durationMs,
                        null, low, mid, high);
            }
            case RGB: {
                // Bytes 3-5 of each six-byte column are the red, green and blue levels.
                float[] height = new float[n];
                float[] low = new float[n];
                float[] mid = new float[n];
                float[] high = new float[n];
                for (int i = 0; i < n; i++) {
                    final int base = i * 6;
                    low[i] = raw.get(base + 3) & 0xff;
                    mid[i] = raw.get(base + 4) & 0xff;
                    high[i] = raw.get(base + 5) & 0xff;
                    height[i] = preview.segmentHeight(i, false);
                }
                return WaveformData.normalize(trackId, WaveformData.Kind.PREVIEW, style, sps, durationMs,
                        height, low, mid, high);
            }
            default: {
                float[] height = new float[n];
                for (int i = 0; i < n; i++) height[i] = preview.segmentHeight(i, true);
                return WaveformData.normalize(trackId, WaveformData.Kind.PREVIEW, style, sps, durationMs,
                        height, null, null, null);
            }
        }
    }

    public static BeatGridData fromBeatGrid(BeatGrid grid, int trackId) {
        final int n = grid.beatCount;
        int[] times = new int[n];
        int[] tempo = new int[n];
        int[] inBar = new int[n];
        for (int i = 0; i < n; i++) {
            final int beat = i + 1;
            times[i] = (int) Math.max(0, Math.min(Integer.MAX_VALUE, grid.getTimeWithinTrack(beat)));
            tempo[i] = grid.getBpm(beat);
            inBar[i] = grid.getBeatWithinBar(beat);
        }
        return new BeatGridData(trackId, times, tempo, inBar);
    }
}
