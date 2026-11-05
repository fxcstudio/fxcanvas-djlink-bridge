package studio.fxc.djlink.assets;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.data.BeatGrid;
import org.deepsymmetry.beatlink.data.DataReference;
import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformFinder;
import org.deepsymmetry.beatlink.data.WaveformPreview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normalized waveforms, the file formats, the beat-link conversions and the asset folder. */
class AssetsTest {
    private static final DataReference REF = new DataReference(2, CdjStatus.TrackSourceSlot.USB_SLOT, 77);

    @TempDir
    Path dir;

    @Test
    void normalizeScalesEverythingByTheLoudestValue() {
        WaveformData w = WaveformData.normalize(5, WaveformData.Kind.DETAIL, WaveformData.Style.THREE_BAND, 150f,
                1000, null, new float[]{10, 0}, new float[]{20, 5}, new float[]{5, 0});
        assertTrue(w.bands);
        assertEquals(2, w.segmentCount());
        assertEquals(255, w.height(0));        // the largest band
        assertEquals(128, w.low(0));           // 10 / 20 of 255
        assertEquals(255, w.mid(0));
        assertEquals(64, w.high(0));
        assertEquals(64, w.height(1));         // 5 / 20 of 255

        WaveformData blue = WaveformData.normalize(5, WaveformData.Kind.PREVIEW, WaveformData.Style.BLUE, 0f, 0,
                new float[]{0, 31, 15.5f}, null, null, null);
        assertFalse(blue.bands);
        assertEquals(255, blue.height(1));
        assertEquals(128, blue.height(2));
        assertEquals(0, blue.low(1));
    }

    @Test
    void waveformFileRoundTrip() {
        WaveformData w = WaveformData.normalize(9001, WaveformData.Kind.PREVIEW, WaveformData.Style.RGB, 3.5f,
                345678, new float[]{1, 2, 3}, new float[]{1, 0, 0}, new float[]{0, 2, 0}, new float[]{0, 0, 3});
        byte[] bytes = AssetCodec.encode(w);
        assertEquals(AssetCodec.WAVEFORM_HEADER + 12, bytes.length);
        // Little-endian header fields where the README says they are.
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals('F', b.get(0));
        assertEquals(1, b.get(4));
        assertEquals(0, b.get(5));    // preview
        assertEquals(1, b.get(6));    // rgb
        assertEquals(1, b.get(7));    // bands
        assertEquals(9001, b.getInt(8));
        assertEquals(3, b.getInt(12));
        assertEquals(3.5f, b.getFloat(16));
        assertEquals(345678, b.getInt(20));

        WaveformData back = AssetCodec.decodeWaveform(bytes);
        assertEquals(w.trackId, back.trackId);
        assertEquals(w.kind, back.kind);
        assertEquals(w.style, back.style);
        assertEquals(w.durationMs, back.durationMs);
        assertArrayEquals(w.segments, back.segments);
        assertThrows(IllegalArgumentException.class, () -> AssetCodec.decodeWaveform(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void beatGridFileRoundTrip() {
        BeatGridData g = new BeatGridData(12, new int[]{120, 589, 1058}, new int[]{12800, 12800, 12750},
                new int[]{1, 2, 3});
        byte[] bytes = AssetCodec.encode(g);
        assertEquals(AssetCodec.BEAT_GRID_HEADER + 24, bytes.length);
        BeatGridData back = AssetCodec.decodeBeatGrid(bytes);
        assertEquals(12, back.trackId);
        assertArrayEquals(g.timesMs, back.timesMs);
        assertArrayEquals(g.tempo100, back.tempo100);
        assertArrayEquals(g.beatInBar, back.beatInBar);
    }

    @Test
    void threeBandDetailUsesThePlayersBandWeights() {
        // Three bytes per half-frame: mid, high, low.
        ByteBuffer raw = ByteBuffer.wrap(new byte[]{100, 0, 0, 0, 0, (byte) 200, 0, (byte) 250, 0});
        WaveformDetail d = new WaveformDetail(REF, raw, WaveformFinder.WaveformStyle.THREE_BAND);
        WaveformData w = WaveformConverter.fromDetail(d, 77);
        assertEquals(WaveformData.Style.THREE_BAND, w.style);
        assertEquals(WaveformData.Kind.DETAIL, w.kind);
        assertEquals(3, w.segmentCount());
        assertEquals(150f, w.segmentsPerSecond);
        // mid 100 x 0.3 = 30, low 200 x 0.4 = 80 (the loudest), high 250 x 0.06 = 15.
        assertEquals(96, w.mid(0));
        assertEquals(255, w.low(1));
        assertEquals(48, w.high(2));
        assertEquals(0, w.low(0));
    }

    @Test
    void blueDetailHasHeightsOnly() {
        ByteBuffer raw = ByteBuffer.wrap(new byte[]{31, 15, 0});
        WaveformData w = WaveformConverter.fromDetail(new WaveformDetail(REF, raw, WaveformFinder.WaveformStyle.BLUE), 1);
        assertFalse(w.bands);
        assertEquals(255, w.height(0));
        assertEquals(123, w.height(1));
        assertEquals(0, w.height(2));
    }

    @Test
    void previewsKeepTheirColumnsAndTrackLength() {
        // Three-band preview: mid, high, low per column.
        ByteBuffer three = ByteBuffer.wrap(new byte[]{10, 10, 100, 50, 0, 0});
        WaveformData w = WaveformConverter.fromPreview(
                new WaveformPreview(REF, three, WaveformFinder.WaveformStyle.THREE_BAND), 77, 4000);
        assertEquals(2, w.segmentCount());
        assertEquals(0.5f, w.segmentsPerSecond);
        assertEquals(4000, w.durationMs);
        assertEquals(255, w.low(0));          // 100 x 0.49 is the loudest
        assertTrue(w.mid(1) > 0);

        // RGB preview: six bytes per column, the last three are the red, green and blue levels.
        ByteBuffer rgb = ByteBuffer.wrap(new byte[]{0, 0, 0, 20, 10, 5});
        WaveformData c = WaveformConverter.fromPreview(
                new WaveformPreview(REF, rgb, WaveformFinder.WaveformStyle.RGB), 77, 0);
        assertEquals(0f, c.segmentsPerSecond, "unknown length");
        assertEquals(255, c.low(0));
        assertEquals(128, c.mid(0));
        assertEquals(64, c.high(0));
    }

    @Test
    void beatGridConversion() {
        BeatGrid grid = new BeatGrid(REF, new int[]{1, 2, 3, 4, 1}, new int[]{12800, 12800, 12800, 12800, 12900},
                new long[]{100, 569, 1038, 1506, 1975});
        BeatGridData g = WaveformConverter.fromBeatGrid(grid, 77);
        assertEquals(5, g.beatCount());
        assertEquals(1975, g.timesMs[4]);
        assertEquals(12900, g.tempo100[4]);
        assertEquals(1, g.beatInBar[4]);
    }

    @Test
    void storeKeepsTheNewestTwoPerKeyAndCleansUp() throws Exception {
        Path stale = dir.resolve("left-over.fxwf");
        Files.write(stale, new byte[]{1});
        AssetStore store = new AssetStore(dir);
        assertFalse(Files.exists(stale), "emptied at start");

        Path a = store.write("p1-detail", "fxwf", new byte[]{1});
        Path b = store.write("p1-detail", "fxwf", new byte[]{2});
        Path other = store.write("p2-detail", "fxwf", new byte[]{9});
        Path c = store.write("p1-detail", "fxwf", new byte[]{3});
        assertFalse(Files.exists(a));
        assertTrue(Files.exists(b) && Files.exists(c) && Files.exists(other));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(c));
        assertTrue(c.isAbsolute());
        assertTrue(c.getFileName().toString().endsWith(".fxwf"));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
        }

        store.forget("p1-detail");
        assertFalse(Files.exists(b) || Files.exists(c));
        store.close();
        assertFalse(Files.exists(dir));
    }
}
