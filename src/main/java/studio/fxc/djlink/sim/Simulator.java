package studio.fxc.djlink.sim;

import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.fxc.djlink.BridgeState;
import studio.fxc.djlink.assets.AssetCodec;
import studio.fxc.djlink.assets.AssetStore;
import studio.fxc.djlink.assets.BeatGridData;
import studio.fxc.djlink.assets.WaveformData;
import studio.fxc.djlink.model.DeckState;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.model.PositionData;
import studio.fxc.djlink.model.TrackInfo;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.Outbox;
import studio.fxc.djlink.osc.ResponseRoute;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
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
 * Decks are 1..N plus a mixer (33), unless {@link #setMixer} turned the mixer
 * off: then no deck ever goes on air, as with players and no DJM on the link.
 * Loads, pauses and master hand-offs follow what real CDJ-3000s send (see
 * {@link #beginLoad}, {@link #brake}, {@link #handOffMaster}).
 * Tempo is constant per track; pitch,
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
    private static final String MIXER_IP = "127.0.0.43";

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
        /** A load runs: the old track is gone, the new one is not there yet. */
        boolean loading;
        /** Counts loads, so a stage of an older load does nothing. */
        int loadGeneration;
        /** Play was pressed while the track still loads. */
        boolean playWhenLoaded;

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
    private final AssetStore assets;
    private final List<Deck> decks = new ArrayList<>();
    /** The waveform style clients asked for (see {@link #waveformStyleChanged}). */
    private WaveformData.Style waveformStyle = BridgeState.DEFAULT_WAVEFORM_STYLE;
    private final double speed;
    private final Consumer<String> console;
    private int master = -1;
    private boolean mixer = true;
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

    /** Something the simulated players do a moment later (a stage of a load, the platter slowing down). */
    private static final class Pending {
        final double at;
        final Runnable action;

        Pending(double at, Runnable action) {
            this.at = at;
            this.action = action;
        }
    }

    private final List<Pending> pending = new ArrayList<>();

    /** Run @p action when the simulated time is @p seconds later. Same order for the same time. */
    private void after(double seconds, Runnable action) {
        final Pending p = new Pending(elapsedSeconds + seconds, action);
        int i = pending.size();
        while (i > 0 && pending.get(i - 1).at > p.at) i--;
        pending.add(i, p);
    }

    // What a real CDJ-3000 does, measured on the wire (2026-11-10): seconds
    // after the load starts.
    static final double LOAD_NO_TRACK = 0.06;
    static final double LOAD_TRACK_READY = 0.19;
    static final double LOAD_METADATA = 0.35;
    static final double LOAD_GRID_AND_ART = 0.38;
    static final double LOAD_WAVEFORMS = 0.50;
    /** A paused platter reports its position every 30 ms while it slows down, for about half a second. */
    static final double BRAKE_STEP = 0.03;
    static final int BRAKE_STEPS = 16;

    /**
     * @param deckCount 1-6 players
     * @param speed     time multiplier (2 = twice as fast), for quick tests
     * @param console   where text replies go (state, help, errors); may be null
     */
    public Simulator(BridgeState state, Outbox outbox, int deckCount, double speed, Consumer<String> console) {
        this(state, outbox, deckCount, speed, console, null);
    }

    /** @param assets where track waveforms, beat grids and art are written; null for none */
    public Simulator(BridgeState state, Outbox outbox, int deckCount, double speed, Consumer<String> console,
                     AssetStore assets) {
        this.assets = assets;
        this.state = state;
        this.outbox = outbox;
        this.speed = speed > 0 ? speed : 1.0;
        this.console = console == null ? s -> { } : console;
        int n = Math.max(1, Math.min(BridgeState.MAX_PLAYER, deckCount));
        for (int i = 1; i <= n; i++) decks.add(new Deck(i));
    }

    /**
     * With no mixer (false), {@link #start} announces no device 33 and every
     * deck stays off air: real players report "off air" when no DJM is on the
     * network. Call before {@link #start}.
     */
    public void setMixer(boolean mixer) {
        this.mixer = mixer;
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
        if (mixer) state.deviceFound(MIXER, MIXER_NAME, MIXER_IP);
        String[] defaults = {"a", "b", "c", "short", "a", "b"};
        for (Deck deck : decks) {
            int i = deck.number - 1;
            SimTrack track = i < tracks.size() ? tracks.get(i) : SimTrack.builtIn(defaults[i]);
            load(deck, track);
        }
        Deck first = decks.get(0);
        first.onAir = mixer;
        setMaster(first.number);
        setPlaying(first, true);
        logger.info("Simulating {} decks{} (speed x{})", decks.size(), mixer ? " and a mixer" : ", no mixer", speed);
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
        while (!pending.isEmpty() && pending.get(0).at <= elapsedSeconds) {
            pending.remove(0).action.run();
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
            if (deck.number == master) handOffMaster(deck);
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
        if (deck.loading) playState = "loading";
        else if (deck.track == null) playState = "no_track";
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
        sendMetadata(deck);
        sendPhrases(deck);
        sendAssets(deck.number, track);
        emitPosition(deck, PositionData.REASON_MOVEMENT);
        emitStatus(deck);
    }

    /**
     * Load a track the way a player does, in stages (the times are the LOAD_
     * constants). The old track goes first: the deck reports "loading" and
     * clears its metadata, phrases and assets. Then "no_track", then the new
     * track (cued, or playing when play was pressed meanwhile), and only then
     * its metadata and phrases, its beat grid and art, and last its waveforms.
     * So a client sees a deck that plays before it has a title, as it does
     * from a real player.
     */
    private void beginLoad(Deck deck, SimTrack track) {
        final int gen = ++deck.loadGeneration;
        final boolean wasMaster = deck.number == master;
        deck.track = null;
        deck.loading = true;
        deck.playWhenLoaded = false;
        deck.playing = false;
        deck.cued = false;
        deck.atEnd = false;
        deck.looping = false;
        deck.position = 0;
        deck.lastBeatIndex = 0;
        emitStatus(deck);
        if (wasMaster) handOffMaster(deck);
        state.metadata(deck.number, null);
        state.phrasesNone(deck.number, 0);
        if (assets != null) {
            state.beatGridNone(deck.number, 0);
            state.artNone(deck.number, 0);
            state.waveformNone(deck.number, 0, WaveformData.Kind.PREVIEW);
            state.waveformNone(deck.number, 0, WaveformData.Kind.DETAIL);
        }
        after(LOAD_NO_TRACK, () -> {
            if (deck.loadGeneration != gen) return;
            deck.loading = false;
            emitStatus(deck);
        });
        after(LOAD_TRACK_READY, () -> {
            if (deck.loadGeneration != gen) return;
            deck.track = track;
            deck.cued = true;
            emitPosition(deck, PositionData.REASON_MOVEMENT);
            emitStatus(deck);
            if (deck.playWhenLoaded) setPlaying(deck, true);
            deck.playWhenLoaded = false;
        });
        after(LOAD_METADATA, () -> {
            if (deck.loadGeneration != gen || deck.track == null) return;
            sendMetadata(deck);
            sendPhrases(deck);
        });
        after(LOAD_GRID_AND_ART, () -> {
            if (deck.loadGeneration != gen || deck.track == null || assets == null) return;
            try {
                BeatGridData grid = SimAssets.beatGrid(deck.track);
                state.beatGrid(deck.number, deck.track.trackId, grid.beatCount(),
                        assets.write("p" + deck.number + "-grid", "fxbg", AssetCodec.encode(grid)).toString());
                sendArt(deck.number, deck.track);
            } catch (IOException | RuntimeException e) {
                logger.warn("Unable to write simulated track assets for deck {}", deck.number, e);
            }
        });
        after(LOAD_WAVEFORMS, () -> {
            if (deck.loadGeneration != gen || deck.track == null || assets == null) return;
            try {
                sendWaveforms(deck.number, deck.track);
            } catch (IOException | RuntimeException e) {
                logger.warn("Unable to write simulated waveforms for deck {}", deck.number, e);
            }
        });
    }

    /**
     * The tempo master stopped: the lowest playing deck takes over, as the
     * players do among themselves. With no other deck playing it stays master.
     */
    private void handOffMaster(Deck stopped) {
        for (Deck other : decks) {
            if (other != stopped && other.playing) {
                setMaster(other.number);
                return;
            }
        }
    }

    /**
     * A platter does not stop at once: for about half a second a paused deck
     * reports a position every 30 ms that still creeps forward, slower each time.
     */
    private void brake(Deck deck) {
        final int gen = deck.loadGeneration;
        final SimTrack track = deck.track;
        final double beatsPerStep = deck.effectiveTempo() / 60.0 * BRAKE_STEP;
        for (int i = 1; i <= BRAKE_STEPS; i++) {
            final double slowdown = 1.0 - (double) i / BRAKE_STEPS;
            after(i * BRAKE_STEP, () -> {
                if (deck.playing || deck.loadGeneration != gen || deck.track != track || deck.cued) return;
                deck.position = Math.min(track.beatCount - 1, deck.position + beatsPerStep * slowdown * 0.5);
                deck.lastBeatIndex = (int) Math.floor(deck.position);
                emitPosition(deck, PositionData.REASON_MOVEMENT);
            });
        }
    }

    /** Waveforms, beat grid and art for a newly loaded track (the real ones from an analysis file if read). */
    private void sendAssets(int player, SimTrack track) {
        if (assets == null) return;
        final String p = "p" + player;
        try {
            BeatGridData grid = SimAssets.beatGrid(track);
            state.beatGrid(player, track.trackId, grid.beatCount(),
                    assets.write(p + "-grid", "fxbg", AssetCodec.encode(grid)).toString());
            sendWaveforms(player, track);
            sendArt(player, track);
        } catch (IOException | RuntimeException e) {
            logger.warn("Unable to write simulated track assets for deck {}", player, e);
        }
    }

    private void sendMetadata(Deck deck) {
        final SimTrack track = deck.track;
        // The fields a rekordbox track always has; label, original artist and
        // remixer are sent only when the track has them, as from real players.
        state.metadata(deck.number, new TrackInfo(track.trackId, track.title, track.artist, "", "No Color", "",
                (int) (track.durationMs() / 1000), "Electro", "8A", null, null, 0, null, track.bpm));
    }

    /** The track's cover file if it has one, else generated art. */
    private void sendArt(int player, SimTrack track) throws IOException {
        if (track.artFile != null) {
            final byte[] bytes = Files.readAllBytes(track.artFile);
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new IOException("Not an image: " + track.artFile);
            final String name = track.artFile.getFileName().toString().toLowerCase(Locale.ROOT);
            final String ext = name.endsWith(".png") ? "png" : "jpg";
            state.art(player, track.trackId, image.getWidth(), image.getHeight(),
                    assets.write("p" + player + "-art", ext, bytes).toString());
            return;
        }
        state.art(player, track.trackId, SimAssets.ART_SIZE, SimAssets.ART_SIZE,
                assets.write("p" + player + "-art", "jpg", SimAssets.art(track)).toString());
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
        if (deck.track == null) {
            // Play pressed while the track still loads: it starts when the track is there.
            if (deck.loading || deck.loadGeneration > 0) deck.playWhenLoaded = playing;
            return;
        }
        if (playing && deck.atEnd) return;
        final boolean wasPlaying = deck.playing;
        deck.playing = playing;
        if (playing) deck.cued = false;
        emitPosition(deck, PositionData.REASON_MOVEMENT);
        emitStatus(deck);
        if (wasPlaying && !playing) {
            if (deck.number == master) handOffMaster(deck);
            brake(deck);
        }
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

    /**
     * The mixer joins or leaves the running network. When it leaves, every
     * deck goes off air; when it joins, all faders are down until an
     * {@code onair} command.
     */
    private void connectMixer(boolean connected) {
        if (connected == mixer) return;
        mixer = connected;
        if (connected) {
            state.deviceFound(MIXER, MIXER_NAME, MIXER_IP);
            return;
        }
        for (Deck deck : decks) {
            deck.onAir = false;
            emitStatus(deck);
        }
        state.deviceLost(MIXER, MIXER_NAME, MIXER_IP);
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

    /**
     * Preview and detail waveforms in the requested style. Like the players,
     * a track without that style falls back to blue (then to what it has);
     * the message names the style actually sent.
     */
    private void sendWaveforms(int player, SimTrack track) throws IOException {
        final String p = "p" + player;
        WaveformData detail = track.realDetail(waveformStyle);
        if (detail == null) detail = SimAssets.restyle(SimAssets.detail(track), waveformStyle);
        state.waveform(player, detail, assets.write(p + "-detail", detail.style.oscName(), "fxwf",
                AssetCodec.encode(detail)).toString());
        WaveformData preview = track.realPreview(waveformStyle);
        if (preview == null) {
            preview = SimAssets.restyle(SimAssets.preview(track, SimAssets.detail(track)), waveformStyle);
        }
        state.waveform(player, preview, assets.write(p + "-preview", preview.style.oscName(), "fxwf",
                AssetCodec.encode(preview)).toString());
    }

    /** {@link BridgeState.WaveformStyleListener}: resend every loaded deck's waveforms in the new style. */
    public void waveformStyleChanged(WaveformData.Style style) {
        waveformStyle = style;
        if (assets == null) return;
        for (Deck deck : decks) {
            if (deck.track == null) continue;
            try {
                sendWaveforms(deck.number, deck.track);
            } catch (IOException | RuntimeException e) {
                logger.warn("Unable to write simulated waveforms for deck {}", deck.number, e);
            }
        }
    }

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
        if (name.equals("mixer")) {
            Integer on = msg.intArg(0);
            if (on == null) return fail("mixer: give 1 (a mixer on the network) or 0 (none)");
            connectMixer(on != 0);
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
                if (deck.playing && deck.number == master) {
                    deck.playing = false;
                    handOffMaster(deck);
                }
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
                beginLoad(deck, track);
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
            case "info": {
                if (deck.track == null) return fail("info: no track loaded");
                if (msg.arg(1) == null) return fail("info: give a title (and an artist)");
                final String title = String.valueOf(msg.arg(1));
                final String artist = msg.arg(2) == null ? deck.track.artist : String.valueOf(msg.arg(2));
                deck.track = deck.track.withInfo(title, artist);
                sendMetadata(deck);
                return null;
            }
            case "art": {
                if (deck.track == null) return fail("art: no track loaded");
                if (msg.arg(1) == null) return fail("art: give a JPEG or PNG file");
                if (assets == null) return fail("art: no asset folder");
                final Path file = Paths.get(String.valueOf(msg.arg(1))).toAbsolutePath();
                if (!Files.isRegularFile(file)) return fail("art: no such file: " + file);
                final SimTrack before = deck.track;
                deck.track = deck.track.withArt(file);
                try {
                    sendArt(deck.number, deck.track);
                } catch (IOException | RuntimeException e) {
                    deck.track = before;
                    return fail("art: " + e.getMessage());
                }
                return null;
            }
            case "onair": {
                if (!mixer) return fail("onair: no mixer on this network (mixer 1 adds one)");
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
