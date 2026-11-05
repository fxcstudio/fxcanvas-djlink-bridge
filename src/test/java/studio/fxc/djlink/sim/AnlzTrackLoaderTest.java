package studio.fxc.djlink.sim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analysis files built byte by byte from crate-digger's rekordbox_anlz.ksy
 * layout: a PMAI header, then tagged sections (PQTZ beat grid in the .DAT,
 * PSSI song structure in the .EXT).
 */
class AnlzTrackLoaderTest {
    @TempDir
    Path dir;

    private static byte[] section(String fourcc, int lenHeader, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(12 + body.length);
        b.put(fourcc.getBytes(StandardCharsets.US_ASCII));
        b.putInt(lenHeader);
        b.putInt(12 + body.length);
        b.put(body);
        return b.array();
    }

    private static byte[] file(byte[]... sections) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] s : sections) body.write(s);
        ByteBuffer b = ByteBuffer.allocate(28 + body.size());
        b.put("PMAI".getBytes(StandardCharsets.US_ASCII));
        b.putInt(28);
        b.putInt(28 + body.size());
        b.put(new byte[16]);
        b.put(body.toByteArray());
        return b.array();
    }

    /** {@code beats} beats at {@code bpm}, the first at {@code firstMs}. */
    private static byte[] beatGrid(int beats, double bpm, int firstMs) {
        ByteBuffer b = ByteBuffer.allocate(12 + 8 * beats);
        b.putInt(0);
        b.putInt(0x80000);
        b.putInt(beats);
        for (int i = 0; i < beats; i++) {
            b.putShort((short) ((i % 4) + 1));
            b.putShort((short) Math.round(bpm * 100));
            b.putInt((int) Math.round(firstMs + i * 60000.0 / bpm));
        }
        return section("PQTZ", 24, b.array());
    }

    /** Mid mood, bank hot: intro at 1, chorus at 33 with a fill-in from 57. */
    private static byte[] songStructure() {
        int[][] phrases = {{1, 1, 0}, {33, 9, 57}};
        ByteBuffer body = ByteBuffer.allocate(6 + 14 + 24 * phrases.length);
        body.putInt(24);
        body.putShort((short) phrases.length);
        body.putShort((short) 2);                 // mood: mid
        body.put(new byte[6]);
        body.putShort((short) 65);                // end beat
        body.put(new byte[2]);
        body.put((byte) 3);                       // bank: hot
        body.put((byte) 0);
        for (int i = 0; i < phrases.length; i++) {
            body.putShort((short) (i + 1));
            body.putShort((short) phrases[i][0]);
            body.putShort((short) phrases[i][1]);
            body.put(new byte[6]);                // k1, k2, b and their padding
            body.putShort((short) 0);             // beat2
            body.putShort((short) 0);             // beat3
            body.putShort((short) 0);             // beat4
            body.put(new byte[3]);                // k3 and its padding
            body.put((byte) (phrases[i][2] != 0 ? 1 : 0));
            body.putShort((short) phrases[i][2]);
        }
        return section("PSSI", 32, body.array());
    }

    @Test
    void readsBeatGridAndPhrasesFromEitherFile() throws Exception {
        Files.write(dir.resolve("ANLZ0000.DAT"), file(beatGrid(72, 125.0, 40)));
        Files.write(dir.resolve("ANLZ0000.EXT"), file(beatGrid(72, 125.0, 40), songStructure()));

        for (String name : new String[]{"ANLZ0000.DAT", "ANLZ0000.EXT"}) {
            SimTrack t = AnlzTrackLoader.load(dir.resolve(name));
            assertEquals(125f, t.bpm, 1e-3, name);
            assertEquals(40, t.firstBeatMs);
            assertEquals(72, t.beatCount);
            assertEquals("mid", t.phrases.mood);
            assertEquals("hot", t.phrases.bank);
            assertEquals(65, t.phrases.endBeat);
            assertEquals(2, t.phrases.entries.size());
            assertEquals("chorus", t.phrases.entries.get(1).rawKind);
            assertTrue(t.phrases.entries.get(1).fill);
            assertEquals(57, t.phrases.entries.get(1).fillBeat);
            assertTrue(t.trackId >= 100000);
        }
    }

    @Test
    void beatGridOnlyIsAnUnanalyzedTrack() throws Exception {
        Path dat = dir.resolve("ANLZ0000.DAT");
        Files.write(dat, file(beatGrid(16, 140.0, 0)));
        SimTrack t = AnlzTrackLoader.load(dat);
        assertNull(t.phrases);
        assertEquals(16, t.beatCount);
    }

    @Test
    void missingFileIsAnError() {
        assertThrows(IOException.class, () -> AnlzTrackLoader.load(dir.resolve("nope.EXT")));
    }
}
