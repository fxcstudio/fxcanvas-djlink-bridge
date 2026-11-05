package studio.fxc.djlink;

import io.kaitai.struct.ByteBufferKaitaiStream;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.junit.jupiter.api.Test;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.osc.OscCodec;
import studio.fxc.djlink.osc.OscMessage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Song structure parsing and encoding.
 *
 * The PSSI tags here are built byte by byte from crate-digger's
 * rekordbox_anlz.ksy layout, so they exercise the real parser, including the
 * rekordbox 6 masking. Real exported fixtures would be better still (design D7).
 */
class PhraseEncoderTest {
    private static final int MOOD_HIGH = 1, MOOD_MID = 2, MOOD_LOW = 3;

    /** {beat, kind, k1, k2, k3, fill, fillBeat} */
    private static byte[] pssi(int mood, int endBeat, int bank, int[][] phrases, boolean masked) {
        int n = phrases.length;
        ByteBuffer body = ByteBuffer.allocate(14 + 24 * n);
        body.putShort((short) mood);
        body.put(new byte[6]);
        body.putShort((short) endBeat);
        body.put(new byte[2]);
        body.put((byte) bank);
        body.put((byte) 0);
        for (int i = 0; i < n; i++) {
            int[] p = phrases[i];
            body.putShort((short) (i + 1));       // index
            body.putShort((short) p[0]);          // beat
            body.putShort((short) p[1]);          // kind
            body.put((byte) 0);
            body.put((byte) p[2]);                // k1
            body.put((byte) 0);
            body.put((byte) p[3]);                // k2
            body.put((byte) 0);
            body.put((byte) 0);                   // b
            body.putShort((short) 0);             // beat2
            body.putShort((short) 0);             // beat3
            body.putShort((short) 0);             // beat4
            body.put((byte) 0);
            body.put((byte) p[4]);                // k3
            body.put((byte) 0);
            body.put((byte) p[5]);                // fill
            body.putShort((short) p[6]);          // beat_fill
        }
        byte[] bodyBytes = body.array();
        if (masked) {
            int[] base = {0xCB, 0xE1, 0xEE, 0xFA, 0xE5, 0xEE, 0xAD, 0xEE, 0xE9, 0xD2,
                    0xE9, 0xEB, 0xE1, 0xE9, 0xF3, 0xE8, 0xE9, 0xF4, 0xE1};
            for (int i = 0; i < bodyBytes.length; i++) {
                bodyBytes[i] ^= (byte) (base[i % base.length] + n);
            }
        }
        ByteBuffer tag = ByteBuffer.allocate(6 + bodyBytes.length);
        tag.putInt(24);
        tag.putShort((short) n);
        tag.put(bodyBytes);
        return tag.array();
    }

    private static PhraseData parse(byte[] bytes, int trackId) {
        RekordboxAnlz.SongStructureTag tag =
                new RekordboxAnlz.SongStructureTag(new ByteBufferKaitaiStream(bytes), null, null);
        return PhraseEncoder.fromTag(tag, trackId);
    }

    @Test
    void midMoodTrack() {
        PhraseData d = parse(pssi(MOOD_MID, 257, 3, new int[][]{
                {65, 9, 0, 0, 0, 0, 0},        // chorus (given out of order)
                {1, 1, 0, 0, 0, 0, 0},         // intro
                {33, 3, 0, 0, 0, 1, 61},       // verse_2 with fill-in
        }, false), 819);
        assertEquals(819, d.trackId);
        assertEquals("mid", d.mood);
        assertEquals("hot", d.bank);
        assertEquals(257, d.endBeat);
        assertEquals(3, d.entries.size());
        assertEquals(1, d.entries.get(0).startBeat);
        assertEquals("intro", d.entries.get(0).rawKind);
        assertEquals("verse_2", d.entries.get(1).rawKind);
        assertEquals("Verse 2", d.entries.get(1).label);
        assertTrue(d.entries.get(1).fill);
        assertEquals(61, d.entries.get(1).fillBeat);
        assertEquals("chorus", d.entries.get(2).rawKind);
    }

    @Test
    void lowMoodTrack() {
        PhraseData d = parse(pssi(MOOD_LOW, 200, 0, new int[][]{
                {1, 1, 0, 0, 0, 0, 0},
                {17, 3, 0, 0, 0, 0, 0},        // verse_1b
                {49, 8, 0, 0, 0, 0, 0},        // bridge
                {81, 10, 0, 0, 0, 0, 0},       // outro
        }, false), 5);
        assertEquals("low", d.mood);
        assertEquals("default", d.bank);
        assertEquals("verse_1b", d.entries.get(1).rawKind);
        assertEquals("Verse 1", d.entries.get(1).label);
        assertEquals("bridge", d.entries.get(2).rawKind);
        assertEquals("outro", d.entries.get(3).rawKind);
    }

    @Test
    void highMoodTrackUsesBeatLinkLabels() {
        // Variant flags as beat-link 8.0.0 Util.phraseLabel() reads them.
        PhraseData d = parse(pssi(MOOD_HIGH, 300, 7, new int[][]{
                {1, 1, 1, 0, 0, 0, 0},         // intro, k1=1        -> Intro 1
                {17, 1, 0, 0, 0, 0, 0},        // intro, k1=0        -> Intro 2
                {33, 2, 0, 0, 0, 0, 0},        // up, k2=0 k3=0      -> Up 1
                {49, 2, 0, 0, 1, 0, 0},        // up, k2=0 k3=1      -> Up 2
                {57, 2, 0, 1, 0, 0, 0},        // up, k2=1           -> Up 3
                {65, 3, 0, 0, 0, 0, 0},        // down               -> Down
                {97, 5, 1, 0, 0, 0, 0},        // chorus, k1=1       -> Chorus 1
                {113, 5, 0, 0, 0, 0, 0},       // chorus, k1=0       -> Chorus 2
                {129, 6, 1, 0, 0, 0, 0},       // outro, k1=1        -> Outro 1
        }, false), 9);
        assertEquals("high", d.mood);
        assertEquals("club_1", d.bank);
        String[] raw = {"intro", "intro", "up", "up", "up", "down", "chorus", "chorus", "outro"};
        String[] labels = {"Intro 1", "Intro 2", "Up 1", "Up 2", "Up 3", "Down", "Chorus 1", "Chorus 2", "Outro 1"};
        for (int i = 0; i < raw.length; i++) {
            assertEquals(raw[i], d.entries.get(i).rawKind);
            assertEquals(labels[i], d.entries.get(i).label);
        }
    }

    @Test
    void maskedRekordboxSixTagIsUnmasked() {
        PhraseData d = parse(pssi(MOOD_MID, 129, 1, new int[][]{
                {1, 1, 0, 0, 0, 0, 0},
                {65, 9, 0, 0, 0, 0, 0},
        }, true), 3);
        assertEquals("mid", d.mood);
        assertEquals("cool", d.bank);
        assertEquals(65, d.entries.get(1).startBeat);
        assertEquals("chorus", d.entries.get(1).rawKind);
    }

    @Test
    void emptyStructureIsNoData() {
        assertNull(parse(pssi(MOOD_MID, 0, 0, new int[0][], false), 1));
    }

    @Test
    void encodesOneChunkForATypicalTrack() {
        PhraseData d = new PhraseData(819, "mid", "", 129, List.of(
                new PhraseData.Entry(1, "intro", "Intro", false, 0),
                new PhraseData.Entry(65, "chorus", "Chorus", true, 121)));
        List<OscMessage> chunks = PhraseEncoder.encode(2, d);
        assertEquals(1, chunks.size());
        assertEquals(OscMessage.of("/phrases/2", 819, "mid", "", 129, 2, 0, 2,
                1, "intro", "Intro", 0, 0,
                65, "chorus", "Chorus", 1, 121), chunks.get(0));
    }

    @Test
    void longStructuresAreChunkedUnderTheMacDatagramLimit() {
        List<PhraseData.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            entries.add(new PhraseData.Entry(1 + i * 16, "verse_6", "Verse 6", true, 13 + i * 16));
        }
        List<OscMessage> chunks = PhraseEncoder.encode(6, new PhraseData(1, "mid", "vivid", 2401, entries));
        assertEquals(3, chunks.size());
        assertEquals(List.of(0, 64, 128), List.of(chunks.get(0).arg(5), chunks.get(1).arg(5), chunks.get(2).arg(5)));
        assertEquals(22, chunks.get(2).arg(6));
        for (OscMessage chunk : chunks) {
            assertEquals(150, chunk.arg(4));
            assertTrue(OscCodec.encode(chunk).length < 9216, "chunk too large: " + OscCodec.encode(chunk).length);
        }
    }
}
