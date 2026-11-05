package studio.fxc.djlink;

/** Name, version and OSC protocol version reported by {@code /version}. */
public final class BridgeVersion {
    public static final String NAME = "fxc-djlink-bridge";
    /** Keep in step with the version in pom.xml. */
    public static final String VERSION = "1.1.0";
    /**
     * OSC protocol version. Bump when a message FXCanvas reads changes shape.
     * Adding a new message does not need a bump.
     */
    public static final int PROTOCOL = 1;

    private BridgeVersion() {
    }
}
