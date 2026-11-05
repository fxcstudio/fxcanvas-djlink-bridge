package studio.fxc.djlink;

import java.util.ArrayList;
import java.util.List;

/**
 * Command-line options. Accepts the options FXCanvas passed to open-beat-control,
 * so an older app build can start this bridge unchanged. The Ableton Link options
 * ({@code -B -a -b -c}) are accepted and ignored: FXCanvas bridges to Link itself.
 */
public final class Options {
    public int oscPort = 17002;
    public int latencyMs = 20;
    public boolean realPlayer = false;
    public int deviceNumber = 0;          // 0 = auto-assign
    public String logFile = null;
    public boolean help = false;
    public boolean version = false;
    public final List<String> ignored = new ArrayList<>();

    /** Thrown for an option the bridge cannot accept. */
    public static final class OptionsException extends Exception {
        public OptionsException(String message) {
            super(message);
        }
    }

    public static Options parse(String[] args) throws OptionsException {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-o":
                case "--osc-port":
                    o.oscPort = intValue(args, ++i, arg, 1, 65535);
                    break;
                case "-l":
                case "--latency":
                    o.latencyMs = intValue(args, ++i, arg, -1000, 1000);
                    break;
                case "-r":
                case "--real-player":
                    o.realPlayer = true;
                    break;
                case "-d":
                case "--device-number":
                    o.deviceNumber = intValue(args, ++i, arg, 1, 127);
                    break;
                case "-L":
                case "--log-file":
                    o.logFile = stringValue(args, ++i, arg);
                    break;
                case "-h":
                case "--help":
                    o.help = true;
                    break;
                case "-V":
                case "--version":
                    o.version = true;
                    break;
                case "-B":
                case "--bridge":
                case "-a":
                case "--ableton-master":
                case "-b":
                case "--beat-align":
                    o.ignored.add(arg);
                    break;
                case "-c":
                case "--carabiner-port":
                    intValue(args, ++i, arg, 1, 65535);
                    o.ignored.add(arg + " " + args[i]);
                    break;
                default:
                    throw new OptionsException("Unknown argument: " + arg);
            }
        }
        return o;
    }

    private static String stringValue(String[] args, int index, String option) throws OptionsException {
        if (index >= args.length) {
            throw new OptionsException(option + " requires a value");
        }
        return args[index];
    }

    private static int intValue(String[] args, int index, String option, int min, int max) throws OptionsException {
        String text = stringValue(args, index, option);
        final int value;
        try {
            value = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new OptionsException(option + " must be a number, got: " + text);
        }
        if (value < min || value > max) {
            throw new OptionsException(option + " must be between " + min + " and " + max + ", got: " + value);
        }
        return value;
    }

    public static String usage() {
        String nl = System.lineSeparator();
        return "fxc-djlink-bridge " + BridgeVersion.VERSION + ": Pro DJ Link to OSC bridge for FXCanvas" + nl
                + "Usage: java -jar fxc-djlink-bridge.jar [options]" + nl + nl
                + "  -o, --osc-port PORT       17002  Port for the OSC server (loopback only)" + nl
                + "  -r, --real-player                Try to pose as a real CDJ (device #1-4)" + nl
                + "  -d, --device-number NUM          Use a fixed device number (overrides -r)" + nl
                + "  -l, --latency MS          20     Accepted for compatibility" + nl
                + "  -L, --log-file PATH              Log to a file instead of stderr" + nl
                + "  -V, --version                    Print the version and exit" + nl
                + "  -h, --help                       Print this help and exit" + nl + nl
                + "  -B, -a, -b, -c PORT              Accepted and ignored (Ableton Link is bridged by FXCanvas)" + nl;
    }
}
