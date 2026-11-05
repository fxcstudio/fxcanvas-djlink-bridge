package studio.fxc.djlink.sim;

import studio.fxc.djlink.osc.OscMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a text command ("loop 1 8", "load 2 b hot") into the OSC message the
 * simulator handles ({@code /sim/loop 1 8}). The same words work on the
 * console, in a script file, and with {@code --send}.
 */
public final class SimCommands {
    private SimCommands() {
    }

    public static final String HELP = String.join(System.lineSeparator(),
            "Simulator commands (console, --sim-script, --send, or OSC /sim/<command> to the bridge port):",
            "  play <deck> [0|1]          start (default) or stop a deck",
            "  cue <deck>                 stop and go back to the first beat",
            "  seek <deck> <beat>         jump to a beat-grid beat (1-based)",
            "  phrase <deck> <n>          jump to the start of phrase n (1-based)",
            "  end <deck> [bars]          jump to [bars] (default 8) before the end of the track",
            "  loop <deck> <beats>        loop <beats> beats from the current beat; 0 ends the loop",
            "  tempo <deck> <bpm>         play at this tempo (sets the pitch)",
            "  load <deck> <name|file> [bank]   load a built-in track (a, b, c, short, plain)",
            "                             or a rekordbox ANLZ0000.EXT/.DAT file (\"quote\" paths with",
            "                             spaces); optional Lighting bank",
            "  bank <deck> <bank>         change the loaded track's Lighting bank (default cool natural",
            "                             hot subtle warm vivid club_1 club_2) and resend its phrases",
            "  master <deck>              make a deck the tempo master",
            "  onair <deck> <0|1>         channel fader up or down",
            "  state                      print (and send /sim/deck for) every deck",
            "  help                       this text");

    /** @return the message, or null for an empty line or a comment ({@code #}). */
    public static OscMessage parse(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;
        List<String> words = new ArrayList<>();
        List<Boolean> quoted = new ArrayList<>();
        split(trimmed, words, quoted);
        String first = words.get(0);
        String address = first.startsWith("/") ? first : "/sim/" + first.toLowerCase(Locale.ROOT);
        List<Object> args = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            args.add(quoted.get(i) ? words.get(i) : number(words.get(i)));
        }
        return new OscMessage(address, args);
    }

    /** Split on whitespace; "double quotes" keep spaces (for file paths) and keep a word a string. */
    private static void split(String line, List<String> words, List<Boolean> quoted) {
        StringBuilder word = new StringBuilder();
        boolean inQuotes = false;
        boolean wasQuoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                wasQuoted = true;
                any = true;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (any) {
                    words.add(word.toString());
                    quoted.add(wasQuoted);
                }
                word.setLength(0);
                wasQuoted = false;
                any = false;
            } else {
                word.append(c);
                any = true;
            }
        }
        if (any) {
            words.add(word.toString());
            quoted.add(wasQuoted);
        }
    }

    private static Object number(String word) {
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        try {
            if (word.matches("-?\\d*\\.\\d+|-?\\d+\\.\\d*")) return Float.parseFloat(word);
        } catch (NumberFormatException ignored) {
            // not a number
        }
        return word;
    }
}
