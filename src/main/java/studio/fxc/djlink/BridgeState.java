package studio.fxc.djlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.fxc.djlink.assets.WaveformData;
import studio.fxc.djlink.model.DeckState;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.model.PositionData;
import studio.fxc.djlink.model.TrackInfo;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.Outbox;
import studio.fxc.djlink.osc.ResponseRoute;
import studio.fxc.djlink.osc.Subscriptions;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Everything the bridge knows and every OSC rule, with no beat-link types.
 *
 * The beat-link adapter ({@link DjLinkAdapter}) turns network events into calls
 * on this class; {@link #handle} answers OSC commands. All calls come from one
 * event thread, so nothing here is synchronized.
 *
 * Messages, argument order and argument types for the topics FXCanvas used with
 * open-beat-control match open-beat-control 0.1.x exactly (see the compatibility
 * contract in README.md). {@code /version}, {@code /phrases} and
 * {@code /positions} are new.
 */
public final class BridgeState {
    private static final Logger logger = LoggerFactory.getLogger(BridgeState.class);

    public static final String DEVICES = "/devices";
    public static final String BEATS = "/beats";
    public static final String MASTER = "/master";
    public static final String TEMPOS = "/tempos";
    public static final String PLAYING = "/playing";
    public static final String ON_AIR = "/on-air";
    public static final String METADATA = "/metadata";
    public static final String PHRASES = "/phrases";
    public static final String POSITIONS = "/positions";
    /** Since 1.1.0: play state, loop and end of track per player. */
    public static final String DECK_STATE = "/deck-state";
    /** Since 1.3.0: waveform preview, waveform detail and beat grid files per player. */
    public static final String WAVEFORMS = "/waveforms";
    /** Since 1.3.0: album art files per player. */
    public static final String ART = "/art";

    /**
     * Told when a demand-driven topic ({@link #WAVEFORMS}, {@link #ART}) gets
     * its first subscriber or loses its last, so the adapter only asks the
     * players for that data while someone wants it.
     */
    public interface DemandListener {
        void demandChanged(String topic, boolean wanted);
    }

    /** Told when a client picks another waveform style with {@code /waveforms style <name>}. */
    public interface WaveformStyleListener {
        void waveformStyleChanged(WaveformData.Style style);
    }

    /** The style the bridge asks for until a client picks one. */
    public static final WaveformData.Style DEFAULT_WAVEFORM_STYLE = WaveformData.Style.THREE_BAND;

    /** Highest player number that is a deck (CDJ-3000 and DJM-V10 setups use 1-6). */
    public static final int MAX_PLAYER = 6;

    private static final class Device {
        final String name;
        final String ip;

        Device(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }
    }

    private final Outbox outbox;
    private final Subscriptions subs = new Subscriptions();

    private final TreeMap<Integer, Device> devices = new TreeMap<>();
    private final TreeMap<Integer, Float> tempos = new TreeMap<>();
    private final TreeMap<Integer, Integer> playing = new TreeMap<>();
    private final TreeMap<Integer, Integer> onAir = new TreeMap<>();
    private final TreeMap<Integer, TrackInfo> metadata = new TreeMap<>();
    private final TreeMap<Integer, PhraseData> phrases = new TreeMap<>();
    /** Players whose current track has no phrase data, with that track's id. */
    private final TreeMap<Integer, Integer> phraseNone = new TreeMap<>();
    private final TreeMap<Integer, PositionData> positions = new TreeMap<>();
    private final TreeMap<Integer, DeckState> deckStates = new TreeMap<>();
    /** Latest /waveform message per player and kind ("preview", "detail"). */
    private final TreeMap<Integer, TreeMap<String, OscMessage>> waveforms = new TreeMap<>();
    private final TreeMap<Integer, OscMessage> beatGrids = new TreeMap<>();
    private final TreeMap<Integer, OscMessage> art = new TreeMap<>();
    private DemandListener demandListener = (topic, wanted) -> { };
    private WaveformStyleListener styleListener = style -> { };
    private WaveformData.Style waveformStyle = DEFAULT_WAVEFORM_STYLE;

    private Integer masterPlayer;
    private Float masterTempo;
    private int masterPlaying;

    public BridgeState(Outbox outbox) {
        this.outbox = Objects.requireNonNull(outbox);
    }

    public void setDemandListener(DemandListener listener) {
        this.demandListener = listener == null ? (topic, wanted) -> { } : listener;
    }

    public void setWaveformStyleListener(WaveformStyleListener listener) {
        this.styleListener = listener == null ? style -> { } : listener;
    }

    /** The waveform style clients asked for last ({@link #DEFAULT_WAVEFORM_STYLE} until one does). */
    public WaveformData.Style waveformStyle() {
        return waveformStyle;
    }

    /**
     * The style named in {@code /waveforms style <name>}: {@code rgb},
     * {@code 3band} (or {@code three_band}, as announced) or {@code blue},
     * in any case. Null for anything else.
     */
    static WaveformData.Style parseWaveformStyle(String name) {
        if (name == null) return null;
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "rgb": return WaveformData.Style.RGB;
            case "3band":
            case "three_band": return WaveformData.Style.THREE_BAND;
            case "blue": return WaveformData.Style.BLUE;
            default: return null;
        }
    }

    /** True while a client streams {@code topic}. */
    public boolean isWanted(String topic) {
        return subs.hasSubscribers(topic);
    }

    // =========================================================================
    // OSC commands
    // =========================================================================

    /** Handle one command from a client. */
    public void handle(OscMessage msg, ResponseRoute route) {
        switch (msg.address()) {
            case "/respond":
                handleRespond(msg, route);
                break;
            case "/version":
                outbox.send(route.address(), OscMessage.of("/version",
                        BridgeVersion.NAME, BridgeVersion.VERSION, BridgeVersion.PROTOCOL));
                break;
            case DEVICES:
            case TEMPOS:
            case PLAYING:
            case ON_AIR:
            case METADATA:
            case PHRASES:
            case POSITIONS:
            case DECK_STATE:
            case MASTER:
                streamable(msg, route, true);
                break;
            case WAVEFORMS:
                if ("style".equals(msg.stringArg(0))) {
                    handleWaveformStyle(msg, route);
                    break;
                }
                // fall through
            case ART: {
                final boolean before = subs.hasSubscribers(msg.address());
                streamable(msg, route, true);
                final boolean after = subs.hasSubscribers(msg.address());
                if (before != after) demandListener.demandChanged(msg.address(), after);
                break;
            }
            case BEATS:
                streamable(msg, route, false);
                break;
            default:
                logger.debug("Ignoring unsupported OSC command {}", msg);
        }
    }

    private void handleWaveformStyle(OscMessage msg, ResponseRoute route) {
        final Object arg = msg.arg(1);
        final WaveformData.Style style = parseWaveformStyle(msg.stringArg(1));
        if (style == null) {
            error(route, "Unknown waveform style for /waveforms style (use rgb, 3band or blue): " + arg);
            return;
        }
        if (style == waveformStyle) return;
        logger.info("Waveform style {} requested (was {})", style.oscName(), waveformStyle.oscName());
        waveformStyle = style;
        styleListener.waveformStyleChanged(style);
    }

    private void handleRespond(OscMessage msg, ResponseRoute route) {
        Object arg = msg.arg(0);
        if (arg == null) {
            route.resetResponsePort();
        } else {
            Integer port = msg.intArg(0);
            if (port == null || port < 1 || port > 65535) {
                logger.error("Illegal port value for /respond, must be a number in range 1-65535, ignoring: {}", arg);
                return;
            }
            route.setResponsePort(port);
        }
        logger.info("Will respond to messages from {}", route);
    }

    private void streamable(OscMessage msg, ResponseRoute route, boolean hasState) {
        String topic = msg.address();
        String command = msg.stringArg(0);
        Object portArg = msg.arg(1);
        Integer port = null;
        if (portArg != null) {
            port = msg.intArg(1);
            if (port == null || port < 1 || port > 65535) {
                error(route, "Illegal port value for " + topic + ", must be a number in range 1-65535: " + portArg);
                return;
            }
        }
        InetSocketAddress to = port == null ? route.address() : new InetSocketAddress(route.host(), port);

        if (command == null) {
            error(route, topic + " requires a command.");
            return;
        }
        switch (command) {
            case "list":
                if (hasState) sendCurrent(topic, to);
                else error(route, "Unknown " + topic + " command: " + command);
                break;
            case "stream":
                subs.subscribe(topic, route, port);
                if (hasState) sendCurrent(topic, to);
                break;
            case "stop":
                subs.unsubscribe(topic, route, port);
                break;
            default:
                error(route, "Unknown " + topic + " command: " + command);
        }
    }

    private void error(ResponseRoute route, String text) {
        logger.error(text);
        outbox.send(route.address(), OscMessage.of("/error", text));
    }

    /** Send the current state of {@code topic} to one destination (stream start and list). */
    private void sendCurrent(String topic, InetSocketAddress to) {
        for (OscMessage m : currentState(topic)) {
            outbox.send(to, m);
        }
    }

    List<OscMessage> currentState(String topic) {
        List<OscMessage> out = new ArrayList<>();
        switch (topic) {
            case DEVICES:
                devices.forEach((n, d) -> out.add(OscMessage.of("/device/found", n, d.name, d.ip)));
                break;
            case TEMPOS:
                tempos.forEach((n, t) -> out.add(OscMessage.of("/tempo/" + n, t)));
                break;
            case PLAYING:
                playing.forEach((n, p) -> out.add(OscMessage.of("/playing/" + n, p)));
                break;
            case ON_AIR:
                onAir.forEach((n, a) -> out.add(OscMessage.of("/on-air/" + n, a)));
                break;
            case METADATA:
                metadata.forEach((n, md) -> out.addAll(metadataMessages(n, md)));
                break;
            case PHRASES:
                phrases.forEach((n, p) -> out.addAll(PhraseEncoder.encode(n, p)));
                phraseNone.forEach((n, id) -> out.add(PhraseEncoder.none(n, id)));
                break;
            case POSITIONS:
                positions.forEach((n, p) -> out.add(positionMessage(n, p)));
                break;
            case DECK_STATE:
                deckStates.forEach((n, d) -> out.add(deckMessage(n, d)));
                break;
            case WAVEFORMS:
                waveforms.forEach((n, byKind) -> out.addAll(byKind.values()));
                out.addAll(beatGrids.values());
                break;
            case ART:
                out.addAll(art.values());
                break;
            case MASTER:
                if (masterPlayer != null) {
                    out.add(OscMessage.of("/master/player", masterPlayer));
                    if (masterTempo != null) out.add(OscMessage.of("/master/tempo", masterTempo));
                    out.add(OscMessage.of("/master/playing", masterPlaying));
                } else {
                    out.add(OscMessage.of("/master/none"));
                }
                break;
            default:
                break;
        }
        return out;
    }

    private void publish(String topic, OscMessage message) {
        subs.publish(outbox, topic, message);
    }

    // =========================================================================
    // Network events
    // =========================================================================

    public void deviceFound(int number, String name, String ip) {
        devices.put(number, new Device(name, ip));
        publish(DEVICES, OscMessage.of("/device/found", number, name, ip));
    }

    public void deviceLost(int number, String name, String ip) {
        devices.remove(number);
        publish(DEVICES, OscMessage.of("/device/lost", number, name, ip));
        tempos.remove(number);
        playing.remove(number);
        onAir.remove(number);
        metadata.remove(number);
        phrases.remove(number);
        phraseNone.remove(number);
        positions.remove(number);
        deckStates.remove(number);
        waveforms.remove(number);
        beatGrids.remove(number);
        art.remove(number);
    }

    /**
     * A status packet.
     *
     * @param tempo effective tempo, or NaN when the packet has none
     * @param isCdj true for player status (playing and on-air are meaningful)
     */
    public void status(int device, float tempo, boolean isCdj, boolean isPlaying, boolean isOnAir) {
        if (!Float.isNaN(tempo)) {
            updateTempo(device, tempo);
        }
        if (isCdj) {
            int p = isPlaying ? 1 : 0;
            if (!Objects.equals(playing.put(device, p), p)) {
                publish(PLAYING, OscMessage.of("/playing/" + device, p));
            }
            if (masterPlayer != null && masterPlayer == device) {
                updateMasterPlaying(p);
            }
            int a = isOnAir ? 1 : 0;
            if (!Objects.equals(onAir.put(device, a), a)) {
                publish(ON_AIR, OscMessage.of("/on-air/" + device, a));
            }
        }
    }

    public void beat(int device, int beatInBar, float effectiveTempo, float trackTempo, float pitch) {
        publish(BEATS, OscMessage.of("/beat", device, beatInBar, effectiveTempo, trackTempo, pitch));
        updateTempo(device, effectiveTempo);
        if (device >= 1 && device <= MAX_PLAYER) {
            // A beat can be the first sign that a player is playing.
            if (!Objects.equals(playing.put(device, 1), 1)) {
                publish(PLAYING, OscMessage.of("/playing/" + device, 1));
            }
            if (masterPlayer != null && masterPlayer == device) {
                updateMasterPlaying(1);
            }
        }
    }

    public void masterBeat(int device, int beatInBar, float effectiveTempo, float trackTempo, float pitch) {
        publish(MASTER, OscMessage.of("/master/beat", device, beatInBar, effectiveTempo, trackTempo, pitch));
    }

    /** The tempo master changed; {@code device} is null when there is none. */
    public void masterChanged(Integer device) {
        if (device != null) {
            publish(MASTER, OscMessage.of("/master/player", device));
            masterPlayer = device;
            if (device > MAX_PLAYER) {
                updateMasterPlaying(0); // A mixer master is not "playing".
            }
        } else {
            publish(MASTER, OscMessage.of("/master/none"));
            updateMasterPlaying(0);
            masterPlayer = null;
            masterTempo = null;
        }
    }

    public void masterTempo(float tempo) {
        masterTempo = tempo;
        publish(MASTER, OscMessage.of("/master/tempo", tempo));
    }

    /** New metadata for a player's deck, or {@code null} when it unloaded. */
    public void metadata(int player, TrackInfo info) {
        if (info == null) {
            metadata.remove(player);
            publish(METADATA, OscMessage.of("/metadata/" + player + "/none"));
            phrasesNone(player, 0);
            return;
        }
        metadata.put(player, info);
        for (OscMessage m : metadataMessages(player, info)) {
            publish(METADATA, m);
        }
        // Phrases of the previous track are stale from now on. The new track's
        // phrases (if any) arrive separately once the analysis file is read.
        PhraseData current = phrases.get(player);
        if (current == null || current.trackId != info.trackId) {
            phrasesNone(player, info.trackId);
        }
    }

    /** Phrase structure for the track on a player. */
    public void phrases(int player, PhraseData data) {
        if (data == null) {
            throw new IllegalArgumentException("use phrasesNone for missing data");
        }
        phrases.put(player, data);
        phraseNone.remove(player);
        for (OscMessage m : PhraseEncoder.encode(player, data)) {
            publish(PHRASES, m);
        }
    }

    /** The track ({@code trackId}, 0 if unknown) on a player has no phrase data. */
    public void phrasesNone(int player, int trackId) {
        boolean hadData = phrases.remove(player) != null;
        Integer previous = phraseNone.put(player, trackId);
        if (hadData || previous == null || previous != trackId) {
            publish(PHRASES, PhraseEncoder.none(player, trackId));
        }
    }

    public void position(int player, PositionData position) {
        positions.put(player, position);
        publish(POSITIONS, positionMessage(player, position));
    }

    /** A player's transport state (status packets). Published only when it changes. */
    public void deckState(int player, DeckState state) {
        if (state.equals(deckStates.put(player, state))) return;
        publish(DECK_STATE, deckMessage(player, state));
    }

    // =========================================================================
    // Track assets (since 1.3.0): files written by an AssetStore
    // =========================================================================

    /** A waveform for the track on a player, written to {@code path}. */
    public void waveform(int player, WaveformData w, String path) {
        OscMessage m = OscMessage.of("/waveform/" + player, w.trackId, w.kind.oscName(), w.style.oscName(),
                w.bands ? 1 : 0, w.segmentCount(), w.segmentsPerSecond, clampInt(w.durationMs), path);
        waveforms.computeIfAbsent(player, p -> new TreeMap<>()).put(w.kind.oscName(), m);
        publish(WAVEFORMS, m);
    }

    /** The track on a player has no waveform of this kind (or the deck unloaded, trackId 0). */
    public void waveformNone(int player, int trackId, WaveformData.Kind kind) {
        TreeMap<String, OscMessage> byKind = waveforms.get(player);
        if (byKind != null) byKind.remove(kind.oscName());
        publish(WAVEFORMS, OscMessage.of("/waveform/" + player + "/none", trackId, kind.oscName()));
    }

    public void beatGrid(int player, int trackId, int beatCount, String path) {
        OscMessage m = OscMessage.of("/beat-grid/" + player, trackId, beatCount, path);
        beatGrids.put(player, m);
        publish(WAVEFORMS, m);
    }

    public void beatGridNone(int player, int trackId) {
        beatGrids.remove(player);
        publish(WAVEFORMS, OscMessage.of("/beat-grid/" + player + "/none", trackId));
    }

    /** Album art (JPEG) for the track on a player. */
    public void art(int player, int trackId, int width, int height, String path) {
        OscMessage m = OscMessage.of("/art/" + player, trackId, width, height, path);
        art.put(player, m);
        publish(ART, m);
    }

    public void artNone(int player, int trackId) {
        art.remove(player);
        publish(ART, OscMessage.of("/art/" + player + "/none", trackId));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void updateTempo(int device, float tempo) {
        if (!Objects.equals(tempos.put(device, tempo), tempo)) {
            publish(TEMPOS, OscMessage.of("/tempo/" + device, tempo));
        }
    }

    private void updateMasterPlaying(int value) {
        if (masterPlaying != value) {
            masterPlaying = value;
            publish(MASTER, OscMessage.of("/master/playing", value));
        }
    }

    /**
     * {@code /position/{player}}. The first five arguments are protocol 1 as
     * shipped in 1.0.0; 1.1.0 appends position, duration, beat count and
     * pitch, so a reader of the first five is unaffected.
     */
    static OscMessage positionMessage(int player, PositionData p) {
        return OscMessage.of("/position/" + player, p.trackId, p.beatNumber,
                p.playing ? 1 : 0, p.definitive ? 1 : 0, p.reason,
                clampInt(p.positionMs), clampInt(p.durationMs), p.beatCount, p.pitch);
    }

    static OscMessage deckMessage(int player, DeckState d) {
        return OscMessage.of("/deck/" + player, d.trackId, d.playState, d.looping ? 1 : 0,
                clampInt(d.loopStartMs), clampInt(d.loopEndMs), d.loopBeats, d.atEnd ? 1 : 0, d.cued ? 1 : 0);
    }

    private static int clampInt(long value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    static List<OscMessage> metadataMessages(int player, TrackInfo md) {
        String prefix = "/metadata/" + player;
        List<OscMessage> out = new ArrayList<>();
        out.add(OscMessage.of(prefix + "/title", nz(md.title)));
        optional(out, prefix + "/artist", md.artist);
        optional(out, prefix + "/album", md.album);
        out.add(OscMessage.of(prefix + "/color", nz(md.color)));
        out.add(OscMessage.of(prefix + "/comment", nz(md.comment)));
        out.add(OscMessage.of(prefix + "/duration", md.duration));
        optional(out, prefix + "/genre", md.genre);
        optional(out, prefix + "/key", md.key);
        optional(out, prefix + "/label", md.label);
        optional(out, prefix + "/original-artist", md.originalArtist);
        out.add(OscMessage.of(prefix + "/rating", md.rating));
        optional(out, prefix + "/remixer", md.remixer);
        out.add(OscMessage.of(prefix + "/tempo", md.tempo));
        return out;
    }

    private static void optional(List<OscMessage> out, String address, String value) {
        if (value != null) out.add(OscMessage.of(address, value));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** For tests and diagnostics. */
    Map<Integer, PhraseData> phrasesForTests() {
        return phrases;
    }

    Subscriptions subscriptions() {
        return subs;
    }
}
