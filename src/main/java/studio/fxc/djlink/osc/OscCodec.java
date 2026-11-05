package studio.fxc.djlink.osc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OSC 1.0 encoder and decoder.
 *
 * Encodes messages with {@code i}, {@code f} and {@code s} arguments. Decodes
 * messages and bundles (recursively) with {@code i f s h d T F N b} arguments;
 * bundle time tags are ignored because every message is handled on arrival.
 * FXCanvas sends its messages wrapped in bundles, so bundle decoding is required.
 */
public final class OscCodec {
    private static final byte[] BUNDLE_TAG = "#bundle\0".getBytes(StandardCharsets.US_ASCII);

    private OscCodec() {
    }

    // =========================================================================
    // Encoding
    // =========================================================================

    public static byte[] encode(OscMessage message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeString(out, message.address());

        StringBuilder tags = new StringBuilder(",");
        for (Object arg : message.args()) {
            tags.append(typeTagFor(arg));
        }
        writeString(out, tags.toString());

        for (Object arg : message.args()) {
            if (arg instanceof Integer) {
                writeInt(out, (Integer) arg);
            } else if (arg instanceof Float) {
                writeInt(out, Float.floatToIntBits((Float) arg));
            } else {
                writeString(out, (String) arg);
            }
        }
        return out.toByteArray();
    }

    private static char typeTagFor(Object arg) {
        if (arg instanceof Integer) return 'i';
        if (arg instanceof Float) return 'f';
        if (arg instanceof String) return 's';
        throw new IllegalArgumentException("Unsupported OSC argument type for sending: "
                + (arg == null ? "null" : arg.getClass().getName()));
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        int padding = 4 - (bytes.length % 4); // always at least one NUL terminator
        for (int i = 0; i < padding; i++) {
            out.write(0);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    // =========================================================================
    // Decoding
    // =========================================================================

    /**
     * Decode a packet into the messages it contains.
     *
     * @throws OscFormatException when the packet is not valid OSC
     */
    public static List<OscMessage> decode(byte[] data, int offset, int length) throws OscFormatException {
        List<OscMessage> result = new ArrayList<>();
        decodeInto(ByteBuffer.wrap(data, offset, length).slice().order(ByteOrder.BIG_ENDIAN), result, 0);
        return result;
    }

    public static List<OscMessage> decode(byte[] data) throws OscFormatException {
        return decode(data, 0, data.length);
    }

    private static void decodeInto(ByteBuffer buf, List<OscMessage> result, int depth) throws OscFormatException {
        if (depth > 8) {
            throw new OscFormatException("Bundles nested too deeply");
        }
        if (isBundle(buf)) {
            buf.position(buf.position() + BUNDLE_TAG.length);
            need(buf, 8);
            buf.getLong(); // time tag, ignored
            while (buf.hasRemaining()) {
                need(buf, 4);
                int size = buf.getInt();
                if (size < 0 || size > buf.remaining() || size % 4 != 0) {
                    throw new OscFormatException("Bad bundle element size " + size);
                }
                ByteBuffer element = buf.slice();
                element.limit(size);
                element.order(ByteOrder.BIG_ENDIAN);
                decodeInto(element, result, depth + 1);
                buf.position(buf.position() + size);
            }
            return;
        }
        result.add(decodeMessage(buf));
    }

    private static boolean isBundle(ByteBuffer buf) {
        if (buf.remaining() < BUNDLE_TAG.length) return false;
        for (int i = 0; i < BUNDLE_TAG.length; i++) {
            if (buf.get(buf.position() + i) != BUNDLE_TAG[i]) return false;
        }
        return true;
    }

    private static OscMessage decodeMessage(ByteBuffer buf) throws OscFormatException {
        String address = readString(buf);
        if (!address.startsWith("/")) {
            throw new OscFormatException("OSC address must start with '/': " + address);
        }
        List<Object> args = new ArrayList<>();
        if (!buf.hasRemaining()) {
            return new OscMessage(address, args); // old-style message without type tags
        }
        String tags = readString(buf);
        if (!tags.startsWith(",")) {
            throw new OscFormatException("Missing type tag string");
        }
        for (int i = 1; i < tags.length(); i++) {
            char tag = tags.charAt(i);
            switch (tag) {
                case 'i':
                    need(buf, 4);
                    args.add(buf.getInt());
                    break;
                case 'f':
                    need(buf, 4);
                    args.add(buf.getFloat());
                    break;
                case 's':
                case 'S':
                    args.add(readString(buf));
                    break;
                case 'h':
                    need(buf, 8);
                    args.add(buf.getLong());
                    break;
                case 'd':
                    need(buf, 8);
                    args.add(buf.getDouble());
                    break;
                case 'T':
                    args.add(Boolean.TRUE);
                    break;
                case 'F':
                    args.add(Boolean.FALSE);
                    break;
                case 'N':
                    args.add(null);
                    break;
                case 'b': {
                    need(buf, 4);
                    int size = buf.getInt();
                    if (size < 0 || size > buf.remaining()) {
                        throw new OscFormatException("Bad blob size " + size);
                    }
                    byte[] blob = new byte[size];
                    buf.get(blob);
                    skipPadding(buf, size);
                    args.add(blob);
                    break;
                }
                default:
                    throw new OscFormatException("Unsupported OSC type tag '" + tag + "'");
            }
        }
        return new OscMessage(address, args);
    }

    private static String readString(ByteBuffer buf) throws OscFormatException {
        int start = buf.position();
        int end = start;
        while (end < buf.limit() && buf.get(end) != 0) {
            end++;
        }
        if (end >= buf.limit()) {
            throw new OscFormatException("Unterminated OSC string");
        }
        byte[] bytes = new byte[end - start];
        buf.get(bytes);
        int consumed = bytes.length + 1;
        int padded = (consumed + 3) & ~3;
        if (start + padded > buf.limit()) {
            throw new OscFormatException("OSC string padding runs past the end of the packet");
        }
        buf.position(start + padded);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skipPadding(ByteBuffer buf, int size) throws OscFormatException {
        int padding = (4 - (size % 4)) % 4;
        need(buf, padding);
        buf.position(buf.position() + padding);
    }

    private static void need(ByteBuffer buf, int bytes) throws OscFormatException {
        if (buf.remaining() < bytes) {
            throw new OscFormatException("OSC packet truncated");
        }
    }

    /** Thrown when a packet cannot be decoded. */
    public static final class OscFormatException extends Exception {
        public OscFormatException(String message) {
            super(message);
        }
    }
}
