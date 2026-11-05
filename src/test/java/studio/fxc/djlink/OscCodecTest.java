package studio.fxc.djlink;

import org.junit.jupiter.api.Test;
import studio.fxc.djlink.osc.OscCodec;
import studio.fxc.djlink.osc.OscMessage;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OscCodecTest {

    @Test
    void encodesTheOscOneZeroLayout() {
        byte[] bytes = OscCodec.encode(OscMessage.of("/beat", 2, 4, 128.0f, "ab"));
        // "/beat\0\0\0" ",iifs\0\0\0" 2 4 128.0f "ab\0\0"
        ByteBuffer expected = ByteBuffer.allocate(8 + 8 + 4 + 4 + 4 + 4);
        expected.put("/beat\0\0\0".getBytes(StandardCharsets.US_ASCII));
        expected.put(",iifs\0\0\0".getBytes(StandardCharsets.US_ASCII));
        expected.putInt(2).putInt(4).putFloat(128.0f);
        expected.put("ab\0\0".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(expected.array(), bytes);
    }

    @Test
    void stringsOfFourBytesGetAFullPadWord() {
        byte[] bytes = OscCodec.encode(OscMessage.of("/abc"));
        // "/abc" needs a terminator, so it takes 8 bytes; ",\0\0\0" takes 4
        assertEquals(12, bytes.length);
    }

    @Test
    void roundTripsMessages() throws Exception {
        OscMessage m = OscMessage.of("/phrases/2", 819, "high", "club_1", 512, 1, 0, 1, 1, "intro", "Intro 1", 0, 0);
        List<OscMessage> decoded = OscCodec.decode(OscCodec.encode(m));
        assertEquals(List.of(m), decoded);
    }

    @Test
    void decodesBundlesAsTheAppSendsThem() throws Exception {
        byte[] a = OscCodec.encode(OscMessage.of("/respond", 17003));
        byte[] b = OscCodec.encode(OscMessage.of("/master", "stream"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("#bundle\0".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 1});
        out.write(ByteBuffer.allocate(4).putInt(a.length).array());
        out.write(a);
        out.write(ByteBuffer.allocate(4).putInt(b.length).array());
        out.write(b);
        List<OscMessage> decoded = OscCodec.decode(out.toByteArray());
        assertEquals(List.of(OscMessage.of("/respond", 17003), OscMessage.of("/master", "stream")), decoded);
    }

    @Test
    void rejectsTruncatedPackets() {
        byte[] bytes = OscCodec.encode(OscMessage.of("/respond", 17003));
        byte[] cut = new byte[bytes.length - 2];
        System.arraycopy(bytes, 0, cut, 0, cut.length);
        assertThrows(OscCodec.OscFormatException.class, () -> OscCodec.decode(cut));
    }

    @Test
    void refusesToSendUnsupportedTypes() {
        assertThrows(IllegalArgumentException.class, () -> OscCodec.encode(OscMessage.of("/x", 1.0d)));
    }
}
