package studio.fxc.djlink.sim;

import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.fxc.djlink.BridgeState;
import studio.fxc.djlink.model.DeckState;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.model.PositionData;
import studio.fxc.djlink.model.TrackInfo;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.Outbox;
import studio.fxc.djlink.osc.ResponseRoute;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Simulated Pro DJ Link players for testing without hardware.
 *
 * Feeds {@link BridgeState} the same events the beat-link adapter does
 * (devices, status, beats, master, metadata, phrases, positions, deck state),
 * so a client cannot tell a simulated deck from a real one at the OSC level.
 * Decks are 1..N plus a mixer (33). Tempo is constant per track; pitch,
 * seeks, loops, master hand-offs, track loads and Lighting banks are driven by
 * commands ({@link SimCommands}).
 *
 * Not thread safe: call {@link #tick(long)} and {@link #handle} from the
 * bridge's event thread only. Time comes in as nanoseconds, so tests drive it.
 */
public final class Simulator {
    private static final Logger logger = LoggerFactory.getLogger(Simulator.class);
    public static final int MIXER = 33;
    public static final String DECK_NAME = "CDJ-3000";
    public static final String MIXER_NAME = "DJM-A9";

    /** One simulated player. */
    static final class Deck {
        final int number;
        SimTrack track;
        boolean playing;
        boolean cued = true;
        boolean onAir;
        boolean atEnd;
        double pitch = 1.0;
        /** 0-based beat position; beat N (1-based) is [N-1, N). */
        double position;
        int lastBeatIndex = -1;
        boolean looping;
        int loopStart;
        int loopEnd;

        Deck(int number) {
            this.number = number;
        }

        int beatNumber() {
            int beat = Math.max(1, (int) Math.floor(position) + 1);
            return track == null ? beat : Math.min(track.beatCount, beat);
        }

        double effectiveTempo() {
            return track == null ? 0 : track.bpm * pitch;
        }
    }

    private final BridgeState state;
    private final Outbox outbox;
    private final List<Deck> decks = new ArrayList<>();
    private final double speed;
    private final Consumer<String> console;
    private int master = -1;
    private long lastNanos = -1;
    private double elapsedSeconds;

    /** A timed command from a script: run when the simulated time reaches {@code at}. */
    private static final class Scheduled {
        final double at;
        final String command;

        Scheduled(double at, String command) {
            this.at = at;
            this.command = command;
        }
    }

    private final List<Scheduled> script = new ArrayList<>();

    /**
     * @param deckCount 1-6 players
     * @param speed     time multiplier (2 = twice as fast), for quick tests
     * @param console   where text replies go (state, help, errors); may be null
     */
    public Simulator(BridgeState state, Outbox outbox, int deckCount, double speed, Consumer<String> console) {
        this.state = state;
        this.outbox = outbox;
        this.speed = speed > 0 ? speed : 1.0;
        this.console = console == null ? s -> { } : console;
        int n = Math.max(1, Math.min(BridgeState.MAX_PLAYER, deckCount));
        for (int i = 1; i <= n; i++) decks.add(new Deck(i));
    }

    /** Add a script line {@code "<seconds> <command>"}; blank lines and # comments are skipped. */
    public void addScriptLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
        int space = trimmed.indexOf(' ');
        if (space < 0) throw new IllegalArgumentException("Script line needs '<seconds> <command>': " + line);
        double at = Double.parseDouble(trimmed.substring(0, space));
        script.add(new Scheduled(at, trimmed.substring(space + 1)));
        script.sort((a, b) -> Double.compare(a.at, b.at));
    }

    /**
     * Announce the devices and set up the decks: deck 1 plays @p tracks[0] (or
     * track a) as tempo master, the others are cued on the next tracks (b, c, ...).
     */
    public void start(long nanos, List<SimTrack> tracks) {
        lastNanos = nanos;
        for (Deck deck : decks) {
            state.deviceFound(deck.number, DECK_NAME, "127.0.0." + (10 + deck.number));
        }
        state.deviceFound(MIXER, MIXER_NAME, "127.0.0.43");
        String[] defaults = {"a", "b", "c", "short", "a", "b"};
        for (Deck deck : decks) {
            int i = deck.number - 1;
            SimTrack track = i < tracks.size() ? tracks.get(i) : SimTrack.builtIn(defaults[i]);
            load(deck, track);
        }
        Deck first = decks.get(0);
        first.onAir = true;
        setMaster(first.number);
        setPlaying(first, true);
        logger.info("Simulating {} decks (speed x{})", decks.size(), speed);
    }

    // =========================================================================
    // Time
    // =========================================================================

    /** Advance every deck to @p nanos. */
    public void tick(long nanos) {
        if (lastNanos < 0) {
            lastNanos = nanos;
            return;
        }
        double dt = Math.max(0, (nanos - lastNanos) / 1e9) * speed;
        lastNanos = nanos;
        elapsedSeconds += dt;
        while (!script.isEmpty() && script.get(0).at <= elapsedSeconds) {
            Scheduled next = script.remove(0);
            logger.info("Script at {}s: {}", next.at, next.command);
            OscMessage msg = SimCommands.parse(next.command);
            if (msg != null) command(msg);
        }
        for (Deck deck : decks) {
            advance(deck, dt);
        }
    }

    public double elapsedSeconds() {
        return elapsedSeconds;
    }

    private void advance(Deck deck, double dt) {
        if (!deck.playing || deck.track == null || dt <= 0) return;
        double next = deck.position + dt * deck.effectiveTempo() / 60.0;

        if (deck.looping && next >= deck.loopEnd) {
            emitBeatsThrough(deck, deck.loopEnd - 1);
            int length = Math.max(1, deck.loopEnd - deck.loopStart);
            next = deck.loopStart + (next - deck.loopEnd) % length;
            deck.position = next;
            // The beat the loop jumps back to starts again: a movement, then its beat.
            deck.lastBeatIndex = (int) Math.floor(next) - 1;
            emitPosition(deck, PositionData.REASON_MOVEMENT);
        }

        if (next >= deck.track.beatCount) {
            emitBeatsThrough(deck, deck.track.beatCount - 1);
            deck.position = deck.track.beatCount;
            deck.playing = false;
            deck.atEnd = true;
            emitPosition(deck, PositionData.REASON_MOVEMENT);
            emitStatus(deck);
            return;
        }
        deck.position = next;
        emitBeatsThrough(deck, (int) Math.floor(next));
    }

    private void emitBeatsThrough(Deck deck, int index) {
        while (deck.lastBeatIndex < index) {
            deck.lastBeatIndex++;
            emitBeat(deck, deck.lastBeatIndex);
        }
    }

    private void emitBeat(Deck deck, int index) {
        float effective = (float) deck.effectiveTempo();
        float trackTempo = deck.track.bpm;
        float pitch = (float) deck.pitch;
        int beatInBar = (index % 4) + 1;
        state.beat(deck.number, beatInBar, effective, trackTempo, pitch);
        if (deck.number == master) {
            state.masterBeat(deck.number, beatInBar, effective, trackTempo, pitch);
        }
        int beatNumber = index + 1;
        state.position(deck.number, new PositionData(deck.track.trackId, beatNumber, deck.playing, true,
                PositionData.REASON_BEAT, deck.track.millisAt(index), deck.track.durationMs(),
                deck.track.beatCount, pitch));
        emitStatus(deck);
    }

    private void emitPosition(Deck deck, int reason) {
        if (deck.track == null) return;
        state.position(deck.number, new PositionData(deck.track.trackId, deck.beatNumber(), deck.playing, true,
                reason, deck.track.millisAt(deck.position), deck.track.durationMs(), deck.track.beatCount,
                (float) deck.pitch));
    }

    private void emitStatus(Deck deck) {
        float tempo = deck.track == null ? Float.NaN : (float) deck.effectiveTempo();
        state.status(deck.number, tempo, true, deck.playing, deck.onAir);
        String playState;
        if (deck.track == null) playState = "no_track";
        else if (deck.atEnd) playState = "ended";
        else if (deck.playing && deck.looping) playState = "looping";
        else if (deck.playing) playState = "playing";
        else if (deck.cued) playState = "cued";
        else playState = "paused";
        int trackId = deck.track == null ? 0 : deck.track.trackId;
        long loopStartMs = deck.looping && deck.track != null ? deck.track.millisAt(deck.loopStart) : 0;
        long loopEndMs = deck.looping && deck.track != null ? deck.track.millisAt(deck.loopEnd) : 0;
        state.deckState(deck.number, new DeckState(trackId, playState, deck.looping && deck.playing,
                loopStartMs, loopEndMs, deck.looping ? deck.loopEnd - deck.loopStart : 0, deck.atEnd,
                deck.cued && !deck.playing));
    }

    // =========================================================================
    // Deck actions
    // =========================================================================

    private void load(Deck deck, SimTrack track) {
        deck.track = track;
        deck.playing = false;
        deck.cued = true;
        deck.atEnd = false;
        deck.looping = false;
        deck.position = 0;
        deck.lastBeatIndex = 0;     // sitting on beat 1: its beat is not sent again
        if (track == null) {
            state.metadata(deck.number, null);
            emitStatus(deck);
            return;
        }
        state.metadata(deck.number, new TrackInfo(track.trackId, track.title, track.artist, null, "", "",
                (int) (track.durationMs() / 1000), null, null, null, null, 0, null, track.bpm));
        sendPhrases(deck);
        emitPosition(deck, PositionData.REASON_MOVEMENT);
        emitStatus(deck);
    }

    private void sendPhrases(Deck deck) {
        PhraseData phrases = deck.track.phrases;
        if (phrases != null) {
            state.phrases(deck.number, phrases);
        } else {
            state.phrasesNone(deck.number, deck.track.trackId);
        }
    }

    private void setPlaying(Deck deck, boolean playing) {
        if (deck.track == null) return;
        if (playing && deck.atEnd) return;
        deck.playing = playing;
        if (playing) deck.cued = false;
        emitPosition(deck, PositionData.REASON_MOVEMENT);
        emitStatus(deck);
    }

    private void seek(Deck deck, double position) {
        if (deck.track == null) return;
        deck.position = Math.max(0, Math.min(deck.track.beatCount - 1, position));
        deck.lastBeatIndex = (int) Math.floor(deck.position);
        deck.atEnd = false;
        deck.cued = false;
        if (deck.looping && (deck.position < deck.loopStart || deck.position >= deck.loopEnd)) {
            deck.looping = false;
        }
        emitPosition(deck, PositionData.REASON_MOVEMENT);
        emitStatus(deck);
    }

    private void setMaster(int number) {
        master = number;
        state.masterChanged(number);
        Deck deck = deck(number);
        if (deck != null && deck.track != null) state.masterTempo((float) deck.effectiveTempo());
    }

    private Deck deck(int number) {
        for (Deck deck : decks) {
            if (deck.number == number) return deck;
        }
        return null;
    }

    // =========================================================================
    // Commands
    // =========================================================================

    /** OSC handler for {@code /sim/*}; replies go to the sender's response port and the console. */
    public void handle(OscMessage msg, ResponseRoute route) {
        String error = command(msg);
        if (error != null) {
            outbox.send(route.address(), OscMessage.of("/sim/error", error));
            return;
        }
        if (msg.address().equals("/sim/state")) {
            for (Deck deck : decks) outbox.send(route.address(), deckReport(deck));
        }
        outbox.send(route.address(), OscMessage.of("/sim/ok", msg.address()));
    }

    /** Run one command. @return an error text, or null on success. */
    public String command(OscMessage msg) {
        String name = msg.address().startsWith("/sim/") ? msg.address().substring(5) : msg.address();
        if (name.equals("help")) {
            console.accept(SimCommands.HELP);
            return null;
        }
        if (name.equals("state")) {
            for (Deck deck : decks) console.accept(describe(deck));
            return null;
        }
        Integer number = msg.intArg(0);
        Deck deck = number == null ? null : deck(number);
        if (deck == null) {
            return fail(name + ": first argument must be a deck number 1-" + decks.size());
        }
        switch (name) {
            case "play": {
                Integer on = msg.intArg(1);
                setPlaying(deck, on == null || on != 0);
                return null;
            }
            case "cue":
                deck.playing = false;
                deck.looping = false;
                seek(deck, 0);
                deck.cued = true;
                emitStatus(deck);
                return null;
            case "seek": {
                Integer beat = msg.intArg(1);
                if (beat == null) return fail("seek: give a beat number");
                seek(deck, beat - 1);
                return null;
            }
            case "phrase": {
                Integer n = msg.intArg(1);
                if (deck.track == null || deck.track.phrases == null) return fail("phrase: this track has no phrases");
                if (n == null || n < 1 || n > deck.track.phrases.entries.size()) {
                    return fail("phrase: give 1-" + deck.track.phrases.entries.size());
                }
                // Land a beat before it, so playing on crosses into it the normal way.
                seek(deck, deck.track.phrases.entries.get(n - 1).startBeat - 2);
                return null;
            }
            case "end": {
                Integer bars = msg.intArg(1);
                if (deck.track == null) return fail("end: no track loaded");
                seek(deck, deck.track.beatCount - 4.0 * (bars == null ? 8 : bars));
                return null;
            }
            case "loop": {
                Integer beats = msg.intArg(1);
                if (beats == null || beats < 0) return fail("loop: give a beat count, 0 to end the loop");
                if (beats == 0) {
                    deck.looping = false;
                } else {
                    deck.loopStart = (int) Math.floor(deck.position);
                    deck.loopEnd = deck.loopStart + beats;
                    deck.looping = true;
                }
                emitStatus(deck);
                return null;
            }
            case "tempo": {
                Object value = msg.arg(1);
                if (!(value instanceof Number) || deck.track == null) return fail("tempo: give a BPM");
                double bpm = ((Number) value).doubleValue();
                if (bpm <= 0) return fail("tempo: BPM must be positive");
                deck.pitch = bpm / deck.track.bpm;
                emitStatus(deck);
                if (deck.number == master) state.masterTempo((float) deck.effectiveTempo());
                return null;
            }
            case "load": {
                Object what = msg.arg(1);
                if (what == null) return fail("load: give a track name or an analysis file");
                SimTrack track = SimTrack.builtIn(String.valueOf(what));
                if (track == null) {
                    try {
                        track = AnlzTrackLoader.load(Paths.get(String.valueOf(what)));
                    } catch (IOException | RuntimeException e) {
                        return fail("load: " + e.getMessage());
                    }
                }
                if (msg.arg(2) != null) {
                    String bank = bankName(String.valueOf(msg.arg(2)));
                    if (bank == null) return fail("load: unknown bank '" + msg.arg(2) + "' (" + bankNames() + ")");
                    track = track.withBank(bank);
                }
                load(deck, track);
                return null;
            }
            case "bank": {
                if (deck.track == null) return fail("bank: no track loaded");
                if (deck.track.phrases == null) return fail("bank: this track has no phrases");
                String bank = msg.arg(1) == null ? null : bankName(String.valueOf(msg.arg(1)));
                if (bank == null) return fail("bank: give one of " + bankNames());
                deck.track = deck.track.withBank(bank);
                sendPhrases(deck);
                return null;
            }
            case "master":
                setMaster(deck.number);
                return null;
            case "onair": {
                Integer on = msg.intArg(1);
                deck.onAir = on == null || on != 0;
                emitStatus(deck);
                return null;
            }
            default:
                return fail("Unknown simulator command '" + name + "' (try help)");
        }
    }

    /** The rekordbox Lighting bank name as the bridge sends it, or null when unknown. */
    static String bankName(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (RekordboxAnlz.TrackBank bank : RekordboxAnlz.TrackBank.values()) {
            if (bank.name().toLowerCase(Locale.ROOT).equals(lower)) return lower;
        }
        return null;
    }

    static String bankNames() {
        List<String> names = new ArrayList<>();
        for (RekordboxAnlz.TrackBank bank : RekordboxAnlz.TrackBank.values()) {
            names.add(bank.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }

    private String fail(String text) {
        console.accept(text);
        return text;
    }

    String describe(Deck deck) {
        if (deck.track == null) return "deck " + deck.number + ": empty";
        String phrase = "";
        if (deck.track.phrases != null) {
            for (PhraseData.Entry e : deck.track.phrases.entries) {
                if (e.startBeat <= deck.beatNumber()) phrase = e.label;
            }
        }
        return String.format("deck %d%s: \"%s\" beat %d/%d %.1f BPM %s%s%s%s", deck.number,
                deck.number == master ? " (master)" : "", deck.track.title, deck.beatNumber(),
                deck.track.beatCount, deck.effectiveTempo(), deck.playing ? "playing" : deck.atEnd ? "ended" : "stopped",
                deck.looping ? " looping " + (deck.loopEnd - deck.loopStart) + " beats" : "",
                phrase.isEmpty() ? "" : ", " + phrase,
                deck.track.phrases == null ? ", no phrase data" : ", bank " + deck.track.phrases.bank);
    }

    private OscMessage deckReport(Deck deck) {
        return OscMessage.of("/sim/deck", deck.number, deck.track == null ? "" : deck.track.title,
                deck.beatNumber(), deck.playing ? 1 : 0, deck.looping ? 1 : 0, (float) deck.effectiveTempo(),
                deck.number == master ? 1 : 0);
    }

    /** For tests. */
    Deck deckForTests(int number) {
        return deck(number);
    }

    /** Load the tracks given on the command line (built-in names or analysis files). */
    public static List<SimTrack> resolveTracks(List<String> specs) throws IOException {
        List<SimTrack> tracks = new ArrayList<>();
        for (String spec : specs) {
            SimTrack track = SimTrack.builtIn(spec);
            tracks.add(track != null ? track : AnlzTrackLoader.load(Path.of(spec)));
        }
        return tracks;
    }
}
