package studio.fxc.djlink.osc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One OSC message: an address and a list of arguments.
 *
 * Arguments are {@link Integer}, {@link Float}, {@link String}, and, for
 * decoded messages only, {@link Long}, {@link Double}, {@link Boolean}, {@code null}
 * and {@code byte[]}. Encoding accepts only the first three, so every message we
 * send uses the OSC 1.0 core types that every client understands.
 */
public final class OscMessage {
    private final String address;
    private final List<Object> args;

    public OscMessage(String address, List<?> args) {
        this.address = Objects.requireNonNull(address, "address");
        this.args = Collections.unmodifiableList(new ArrayList<>(args));
    }

    public static OscMessage of(String address, Object... args) {
        List<Object> list = new ArrayList<>(args.length);
        Collections.addAll(list, args);
        return new OscMessage(address, list);
    }

    public String address() {
        return address;
    }

    public List<Object> args() {
        return args;
    }

    /** The argument at {@code index}, or {@code null} when absent. */
    public Object arg(int index) {
        return index < args.size() ? args.get(index) : null;
    }

    /** The argument at {@code index} as a string, or {@code null} when absent or not a string. */
    public String stringArg(int index) {
        Object value = arg(index);
        return value instanceof String ? (String) value : null;
    }

    /**
     * The argument at {@code index} as an integer. Floats are truncated, because some
     * environments (Pure Data) only send floats.
     *
     * @return the value, or {@code null} when absent or not numeric
     */
    public Integer intArg(int index) {
        Object value = arg(index);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OscMessage)) return false;
        OscMessage other = (OscMessage) o;
        return address.equals(other.address) && args.equals(other.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, args);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(address);
        for (Object arg : args) {
            sb.append(' ');
            if (arg instanceof String) {
                sb.append('"').append(arg).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }
}
