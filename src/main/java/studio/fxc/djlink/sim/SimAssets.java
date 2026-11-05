package studio.fxc.djlink.sim;

import studio.fxc.djlink.assets.BeatGridData;
import studio.fxc.djlink.assets.WaveformData;
import studio.fxc.djlink.model.PhraseData;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Waveforms, beat grids and cover art for simulated tracks, so a client's
 * track display can be tested without players.
 *
 * The waveform follows the track's phrases: quiet intros and breakdowns, full
 * choruses, a kick on every beat (low band), off-beat hats (high band), and
 * kick-free fill-ins. Everything is deterministic per track.
 */
public final class SimAssets {
    /** Columns in a generated preview (the players' color previews use 1200). */
    public static final int PREVIEW_SEGMENTS = 1200;
    public static final int ART_SIZE = 240;

    private SimAssets() {
    }

    public static BeatGridData beatGrid(SimTrack t) {
        int[] times = new int[t.beatCount];
        int[] tempo = new int[t.beatCount];
        int[] inBar = new int[t.beatCount];
        for (int i = 0; i < t.beatCount; i++) {
            times[i] = (int) t.millisAt(i);
            tempo[i] = Math.round(t.bpm * 100f);
            inBar[i] = (i % 4) + 1;
        }
        return new BeatGridData(t.trackId, times, tempo, inBar);
    }

    /** 150 segments per second, like the players' detail waveforms. */
    public static WaveformData detail(SimTrack t) {
        final float sps = WaveformData.DETAIL_SEGMENTS_PER_SECOND;
        final int n = (int) Math.ceil(t.durationMs() * sps / 1000.0);
        float[] low = new float[n];
        float[] mid = new float[n];
        float[] high = new float[n];
        for (int i = 0; i < n; i++) {
            final double ms = i * 1000.0 / sps;
            final double beatPos = (ms - t.firstBeatMs) * t.bpm / 60000.0;   // 0 at the first beat
            if (beatPos < -0.25 || beatPos >= t.beatCount) continue;          // silence before and after
            final double energy = energy(t, beatPos);
            final double phase = beatPos - Math.floor(beatPos);
            final boolean fill = inFill(t, beatPos);
            final double kick = fill ? 0.15 : Math.exp(-phase * 7.0);
            final double offPhase = (phase + 0.5) % 1.0;
            final double hat = Math.exp(-offPhase * 18.0);
            final double noise = noise(t.trackId, i);
            final double snareRoll = fill ? 0.5 + 0.5 * Math.abs(Math.sin(beatPos * Math.PI * 4)) : 0.0;
            low[i] = (float) (energy * (0.25 + 0.75 * kick) * (0.92 + 0.08 * noise));
            mid[i] = (float) (energy * (0.35 + 0.25 * noise + 0.4 * snareRoll) * 0.8);
            high[i] = (float) (energy * (0.15 + 0.55 * hat * (0.7 + 0.3 * noise)) * 0.7);
        }
        return WaveformData.normalize(t.trackId, WaveformData.Kind.DETAIL, WaveformData.Style.THREE_BAND, sps,
                t.durationMs(), null, low, mid, high);
    }

    /** The detail squeezed into {@link #PREVIEW_SEGMENTS} columns (peak of each band per column). */
    public static WaveformData preview(SimTrack t, WaveformData detail) {
        final int n = PREVIEW_SEGMENTS;
        final int total = detail.segmentCount();
        float[] low = new float[n];
        float[] mid = new float[n];
        float[] high = new float[n];
        for (int c = 0; c < n; c++) {
            final int from = (int) ((long) c * total / n);
            final int to = Math.max(from + 1, (int) ((long) (c + 1) * total / n));
            for (int s = from; s < to && s < total; s++) {
                low[c] = Math.max(low[c], detail.low(s));
                mid[c] = Math.max(mid[c], detail.mid(s));
                high[c] = Math.max(high[c], detail.high(s));
            }
        }
        final float sps = n * 1000f / Math.max(1, t.durationMs());
        return WaveformData.normalize(t.trackId, WaveformData.Kind.PREVIEW, WaveformData.Style.THREE_BAND, sps,
                t.durationMs(), null, low, mid, high);
    }

    /**
     * A generated waveform in another style. The generator makes three-band
     * levels; RGB uses the same levels as its color channels, and blue keeps
     * only the overall level.
     */
    public static WaveformData restyle(WaveformData w, WaveformData.Style style) {
        if (w.style == style) return w;
        final byte[] segments = w.segments.clone();
        final boolean bands = style != WaveformData.Style.BLUE && w.bands;
        if (!bands) {
            for (int i = 0; i < segments.length; i += WaveformData.BYTES_PER_SEGMENT) {
                segments[i + 1] = 0;
                segments[i + 2] = 0;
                segments[i + 3] = 0;
            }
        }
        return new WaveformData(w.trackId, w.kind, style, bands, w.segmentsPerSecond, w.durationMs, segments);
    }

    /** A JPEG: a record on a two-color gradient, colors picked from the track id. */
    public static byte[] art(SimTrack t) throws IOException {
        final int size = ART_SIZE;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final float hue = (Math.floorMod(t.trackId * 2654435761L, 1000L)) / 1000f;
            final Color a = Color.getHSBColor(hue, 0.65f, 0.55f);
            final Color b = Color.getHSBColor((hue + 0.18f) % 1f, 0.75f, 0.20f);
            g.setPaint(new GradientPaint(0, 0, a, size, size, b));
            g.fillRect(0, 0, size, size);
            // The record: a dark disc with grooves and a label in the first color.
            final int disc = (int) (size * 0.78);
            final int x = (size - disc) / 2;
            g.setColor(new Color(16, 16, 18));
            g.fillOval(x, x, disc, disc);
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(42, 42, 46));
            for (int r = disc / 2 - 6; r > disc / 5; r -= 6) {
                g.drawOval(size / 2 - r, size / 2 - r, 2 * r, 2 * r);
            }
            final int label = disc / 3;
            g.setColor(Color.getHSBColor(hue, 0.55f, 0.85f));
            g.fillOval(size / 2 - label / 2, size / 2 - label / 2, label, label);
            g.setColor(new Color(16, 16, 18));
            g.fillOval(size / 2 - 4, size / 2 - 4, 8, 8);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) throw new IOException("No JPEG writer");
        return out.toByteArray();
    }

    // =========================================================================

    /** Loudness of the music at a beat position, from the phrase it is in. */
    static double energy(SimTrack t, double beatPos) {
        if (t.phrases == null) return 0.75;
        PhraseData.Entry current = null;
        PhraseData.Entry next = null;
        for (PhraseData.Entry e : t.phrases.entries) {
            if (e.startBeat - 1 <= beatPos) {
                current = e;
            } else {
                next = e;
                break;
            }
        }
        if (current == null) return 0.4;
        final double start = current.startBeat - 1;
        final double end = next != null ? next.startBeat - 1 : t.phrases.endBeat - 1;
        final double progress = end > start ? Math.min(1.0, (beatPos - start) / (end - start)) : 0.0;
        switch (current.rawKind.toLowerCase(Locale.ROOT)) {
            case "intro": return 0.40 + 0.20 * progress;
            case "up": return 0.55 + 0.35 * progress;
            case "down": return 0.32;
            case "bridge": return 0.45;
            case "chorus": return 0.95;
            case "outro": return 0.60 - 0.30 * progress;
            default: return 0.65;   // verses
        }
    }

    static boolean inFill(SimTrack t, double beatPos) {
        if (t.phrases == null) return false;
        for (PhraseData.Entry e : t.phrases.entries) {
            if (!e.fill || e.fillBeat <= 0) continue;
            final double fillStart = e.fillBeat - 1;
            if (beatPos >= fillStart && beatPos < fillStart + 8) return true;
        }
        return false;
    }

    /** Deterministic 0..1 noise per track and segment. */
    private static double noise(int trackId, int i) {
        long x = (trackId * 0x9E3779B97F4A7C15L) ^ (i * 0xBF58476D1CE4E5B9L);
        x ^= x >>> 31;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }
}
