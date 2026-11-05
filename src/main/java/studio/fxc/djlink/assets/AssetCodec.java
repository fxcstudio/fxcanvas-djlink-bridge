package studio.fxc.djlink.assets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The binary files the bridge writes for {@code /waveform} and
 * {@code /beat-grid}. Little-endian. See README.md, "Track assets".
 *
 * <pre>
 * Waveform (.fxwf)                      Beat grid (.fxbg)
 *  0  4  "FXWF"                          0  4  "FXBG"
 *  4  1  version (1)                     4  1  version (1)
 *  5  1  kind: 0 preview, 1 detail       5  3  reserved (0)
 *  6  1  style: 0 blue, 1 rgb, 2 3-band  8  4  u32 trackId
 *  7  1  flags: bit 0 = bands present  12  4  u32 beat count
 *  8  4  u32 trackId                    16  8n per beat: u32 timeMs,
 * 12  4  u32 segment count                     u16 tempo x100, u8 beatInBar,
 * 16  4  f32 segments per second (0 = unknown)  u8 reserved
 * 20  4  u32 duration ms (0 = unknown)
 * 24 4n  per segment: u8 height, low, mid, high
 * </pre>
 */
public final class AssetCodec {
    public static final int VERSION = 1;
    public static final int WAVEFORM_HEADER = 24;
    public static final int BEAT_GRID_HEADER = 16;
    private static final byte[] WAVEFORM_MAGIC = "FXWF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BEAT_GRID_MAGIC = "FXBG".getBytes(StandardCharsets.US_ASCII);

    private AssetCodec() {
    }

    public static byte[] encode(WaveformData w) {
        ByteBuffer b = ByteBuffer.allocate(WAVEFORM_HEADER + w.segments.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(WAVEFORM_MAGIC);
        b.put((byte) VERSION);
        b.put((byte) w.kind.ordinal());
        b.put((byte) w.style.ordinal());
        b.put((byte) (w.bands ? 1 : 0));
        b.putInt(w.trackId);
        b.putInt(w.segmentCount());
        b.putFloat(w.segmentsPerSecond);
        b.putInt((int) Math.min(Integer.MAX_VALUE, w.durationMs));
        b.put(w.segments);
        return b.array();
    }

    public static WaveformData decodeWaveform(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        expectMagic(b, WAVEFORM_MAGIC, "waveform");
        int version = b.get() & 0xff;
        if (version != VERSION) throw new IllegalArgumentException("Unsupported waveform version " + version);
        WaveformData.Kind kind = WaveformData.Kind.values()[b.get() & 0xff];
        WaveformData.Style style = WaveformData.Style.values()[b.get() & 0xff];
        boolean bands = (b.get() & 1) != 0;
        int trackId = b.getInt();
        int count = b.getInt();
        float sps = b.getFloat();
        long duration = b.getInt() & 0xffffffffL;
        if (count < 0 || b.remaining() < count * WaveformData.BYTES_PER_SEGMENT) {
            throw new IllegalArgumentException("Waveform truncated");
        }
        byte[] segments = new byte[count * WaveformData.BYTES_PER_SEGMENT];
        b.get(segments);
        return new WaveformData(trackId, kind, style, bands, sps, duration, segments);
    }

    public static byte[] encode(BeatGridData g) {
        ByteBuffer b = ByteBuffer.allocate(BEAT_GRID_HEADER + 8 * g.beatCount()).order(ByteOrder.LITTLE_ENDIAN);
        b.put(BEAT_GRID_MAGIC);
        b.put((byte) VERSION);
        b.put(new byte[3]);
        b.putInt(g.trackId);
        b.putInt(g.beatCount());
        for (int i = 0; i < g.beatCount(); i++) {
            b.putInt(Math.max(0, g.timesMs[i]));
            b.putShort((short) Math.max(0, Math.min(0xffff, g.tempo100[i])));
            b.put((byte) g.beatInBar[i]);
            b.put((byte) 0);
        }
        return b.array();
    }

    public static BeatGridData decodeBeatGrid(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        expectMagic(b, BEAT_GRID_MAGIC, "beat grid");
        int version = b.get() & 0xff;
        if (version != VERSION) throw new IllegalArgumentException("Unsupported beat grid version " + version);
        b.position(b.position() + 3);
        int trackId = b.getInt();
        int count = b.getInt();
        if (count < 0 || b.remaining() < count * 8) throw new IllegalArgumentException("Beat grid truncated");
        int[] times = new int[count];
        int[] tempo = new int[count];
        int[] inBar = new int[count];
        for (int i = 0; i < count; i++) {
            times[i] = b.getInt();
            tempo[i] = b.getShort() & 0xffff;
            inBar[i] = b.get() & 0xff;
            b.get();
        }
        return new BeatGridData(trackId, times, tempo, inBar);
    }

    private static void expectMagic(ByteBuffer b, byte[] magic, String what) {
        if (b.remaining() < magic.length) throw new IllegalArgumentException("Not a " + what + " file");
        for (byte m : magic) {
            if (b.get() != m) throw new IllegalArgumentException("Not a " + what + " file");
        }
    }
}
