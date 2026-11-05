package studio.fxc.djlink.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.fxc.djlink.BridgeState;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.Outbox;
import studio.fxc.djlink.osc.ResponseRoute;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulator drives the real {@link BridgeState}, so these check the OSC a
 * client sees. Time is explicit nanoseconds, advanced in 5 ms ticks like Main.
 */
class SimulatorTest {
    private static final long TICK = 5_000_000L;
    private static final String[] TOPICS = {"/master", "/devices", "/tempos", "/beats", "/on-air", "/playing",
            "/metadata", "/phrases", "/positions", "/deck-state"};

    private final List<OscMessage> sent = new ArrayList<>();
    private final List<String> console = new ArrayList<>();
    private BridgeState state;
    private Simulator sim;
    private ResponseRoute app;
    private long now;

    @BeforeEach
    void setUp() throws Exception {
        Outbox outbox = (to, message) -> sent.add(message);
        state = new BridgeState(outbox);
        sim = new Simulator(state, outbox, 2, 1.0, console::add);
        app = new ResponseRoute(InetAddress.getByName("127.0.0.1"), 50123);
        state.handle(OscMessage.of("/respond", 17003), app);
        for (String topic : TOPICS) state.handle(OscMessage.of(topic, "stream"), app);
        sent.clear();
    }

    private void run(double seconds) {
        long end = now + Math.round(seconds * 1e9);
        while (now < end) {
            now += TICK;
            sim.tick(now);
        }
    }

    private String cmd(String line) {
        return sim.command(SimCommands.parse(line));
    }

    private List<OscMessage> at(String address) {
        return sent.stream().filter(m -> m.address().equals(address)).collect(Collectors.toList());
    }

    private List<OscMessage> beats(int deck) {
        return at("/beat").stream().filter(m -> m.intArg(0) == deck).collect(Collectors.toList());
    }

    private static OscMessage last(List<OscMessage> messages) {
        assertFalse(messages.isEmpty(), "no messages");
        return messages.get(messages.size() - 1);
    }

    @Test
    void startAnnouncesPlayersMixerMasterAndPhrases() {
        sim.start(now, List.of());
        assertTrue(sent.contains(OscMessage.of("/device/found", 1, "CDJ-3000", "127.0.0.11")));
        assertTrue(sent.contains(OscMessage.of("/device/found", 2, "CDJ-3000", "127.0.0.12")));
        assertTrue(sent.contains(OscMessage.of("/device/found", 33, "DJM-A9", "127.0.0.43")));
        assertTrue(sent.contains(OscMessage.of("/master/player", 1)));
        assertTrue(sent.contains(OscMessage.of("/master/tempo", 128f)));
        // Deck 1 track a (club_1), deck 2 track b (hot).
        assertEquals("club_1", last(at("/phrases/1")).arg(2));
        assertEquals("hot", last(at("/phrases/2")).arg(2));
        assertEquals("playing", last(at("/deck/1")).arg(1));
        assertEquals("cued", last(at("/deck/2")).arg(1));
        assertTrue(sent.contains(OscMessage.of("/playing/1", 1)));
    }

    @Test
    void lateClientGetsTheDevicesToo() throws Exception {
        sim.start(now, List.of());
        ResponseRoute late = new ResponseRoute(InetAddress.getByName("127.0.0.1"), 50200);
        sent.clear();
        state.handle(OscMessage.of("/devices", "stream"), late);
        assertEquals(3, at("/device/found").size());
    }

    @Test
    void playingDeckSendsBeatsAndPositionsAtItsTempo() {
        sim.start(now, List.of());
        sent.clear();
        run(8 * 60.0 / 128 + 0.01);   // eight beats at 128 BPM
        List<OscMessage> beats = beats(1);
        assertEquals(8, beats.size());
        assertEquals(List.of(2, 3, 4, 1, 2, 3, 4, 1),
                beats.stream().map(m -> m.intArg(1)).collect(Collectors.toList()));
        assertEquals(128f, beats.get(0).arg(2));
        assertEquals(8, at("/master/beat").size());
        assertTrue(beats(2).isEmpty(), "the cued deck stays quiet");
        OscMessage position = last(at("/position/1"));
        assertEquals(9001, position.intArg(0));
        assertEquals(9, position.intArg(1));      // beat 9 just started
        assertEquals(0, position.intArg(4));      // reason: beat
        assertEquals(120 + 8 * 60000 / 128, (int) position.intArg(5));
    }

    @Test
    void speedMultiplierRunsTimeFaster() {
        sim = new Simulator(state, (to, m) -> sent.add(m), 1, 4.0, console::add);
        sim.start(now, List.of());
        sent.clear();
        run(8 * 60.0 / 128 / 4 + 0.005);
        assertEquals(8, beats(1).size());
    }

    @Test
    void loopRepeatsBeatsWithAMovementReport() {
        sim.start(now, List.of());
        run(0.1);
        assertNull(cmd("loop 1 4"));                 // beats 1-4
        assertEquals("looping", last(at("/deck/1")).arg(1));
        assertEquals(4, last(at("/deck/1")).intArg(5));
        sent.clear();
        run(3 * 4 * 60.0 / 128);                     // three times round
        for (OscMessage p : at("/position/1")) {
            assertTrue(p.intArg(1) <= 4, "beat " + p.intArg(1) + " is outside the loop");
        }
        long wraps = at("/position/1").stream().filter(p -> p.intArg(4) == 1).count();
        assertTrue(wraps >= 2, "each wrap is a movement, got " + wraps);
        assertTrue(beats(1).size() >= 11);

        assertNull(cmd("loop 1 0"));
        assertEquals("playing", last(at("/deck/1")).arg(1));
        sent.clear();
        run(8 * 60.0 / 128);
        assertTrue(last(at("/position/1")).intArg(1) > 4, "plays on after the loop ends");
    }

    @Test
    void endOfTrackStopsTheDeck() {
        sim.start(now, List.of());
        SimTrack a = SimTrack.builtIn("a");
        assertNull(cmd("end 1 1"));
        assertEquals(a.beatCount - 3, last(at("/position/1")).intArg(1));
        sent.clear();
        run(6 * 60.0 / 128);
        assertEquals("ended", last(at("/deck/1")).arg(1));
        assertEquals(1, last(at("/deck/1")).intArg(6));
        assertEquals(OscMessage.of("/playing/1", 0), last(at("/playing/1")));
        assertEquals(a.beatCount, last(at("/position/1")).intArg(1), "stops on the last beat");
        sent.clear();
        run(1);
        assertTrue(beats(1).isEmpty());
        assertNull(cmd("play 1"));
        run(1);
        assertTrue(beats(1).isEmpty() && at("/playing/1").isEmpty(), "an ended deck needs a cue or seek first");
        assertNull(cmd("cue 1"));
        assertEquals("cued", last(at("/deck/1")).arg(1));
    }

    @Test
    void phraseCommandLandsJustBeforeThePhrase() {
        sim.start(now, List.of());
        PhraseData.Entry third = SimTrack.builtIn("a").phrases.entries.get(2);
        assertNull(cmd("phrase 1 3"));
        assertEquals(third.startBeat - 1, last(at("/position/1")).intArg(1));
        sent.clear();
        run(60.0 / 128 + 0.01);
        assertEquals(third.startBeat, last(at("/position/1")).intArg(1));
        assertEquals(0, last(at("/position/1")).intArg(4));
    }

    @Test
    void tempoChangesPitchAndMasterTempo() {
        sim.start(now, List.of());
        assertNull(cmd("tempo 1 120.0"));
        assertEquals(OscMessage.of("/master/tempo", 120f), last(at("/master/tempo")));
        sent.clear();
        run(2 * 60.0 / 120 + 0.01);
        OscMessage beat = last(beats(1));
        assertEquals(120f, beat.arg(2));
        assertEquals(128f, beat.arg(3));
        assertEquals(120f / 128f, (float) beat.arg(4), 1e-5);
        assertEquals(2, beats(1).size());
    }

    @Test
    void loadBankMasterAndOnAir() {
        sim.start(now, List.of());
        sent.clear();
        assertNull(cmd("load 2 short warm"));
        assertEquals("Simulated Short Track", last(at("/metadata/2/title")).arg(0));
        assertEquals("warm", last(at("/phrases/2")).arg(2));
        assertNull(cmd("bank 2 CLUB_2"));
        assertEquals("club_2", last(at("/phrases/2")).arg(2));
        assertNull(cmd("load 2 plain"));
        assertEquals(OscMessage.of("/phrases/2/none", 9005), last(at("/phrases/2/none")));
        assertNotNull(cmd("bank 2 hot"), "an unanalyzed track has no bank");

        assertNull(cmd("master 2"));
        assertEquals(OscMessage.of("/master/player", 2), last(at("/master/player")));
        assertNull(cmd("onair 2 1"));
        assertEquals(OscMessage.of("/on-air/2", 1), last(at("/on-air/2")));

        assertNull(cmd("info 2 \"On My Knees\" \"RUFUS DU SOL\""));
        assertEquals(OscMessage.of("/metadata/2/title", "On My Knees"), last(at("/metadata/2/title")));
        assertEquals(OscMessage.of("/metadata/2/artist", "RUFUS DU SOL"), last(at("/metadata/2/artist")));
        assertNotNull(cmd("info 2"), "a title is required");
        assertNotNull(cmd("art 2 missing.jpg"), "a missing file is refused");
    }

    @Test
    void scriptRunsCommandsAtTheirTime() {
        sim.addScriptLine("# hand over to deck 2 after a second");
        sim.addScriptLine("1.0 master 2");
        sim.addScriptLine("0.5 play 2");
        sim.start(now, List.of());
        run(0.4);
        assertEquals("cued", last(at("/deck/2")).arg(1));
        run(0.2);
        assertEquals("playing", last(at("/deck/2")).arg(1));
        assertFalse(sent.contains(OscMessage.of("/master/player", 2)));
        run(0.5);
        assertTrue(sent.contains(OscMessage.of("/master/player", 2)));
    }

    @Test
    void oscRepliesOkErrorAndState() {
        sim.start(now, List.of());
        sent.clear();
        sim.handle(OscMessage.of("/sim/seek", 2, 33), app);
        assertEquals(OscMessage.of("/sim/ok", "/sim/seek"), last(sent));
        sim.handle(OscMessage.of("/sim/seek", 9, 1), app);
        assertEquals("/sim/error", last(sent).address());
        sim.handle(OscMessage.of("/sim/nope", 1), app);
        assertEquals("/sim/error", last(sent).address());
        sim.handle(OscMessage.of("/sim/bank", 1, "purple"), app);
        assertTrue(last(sent).stringArg(0).contains("club_1"), "lists the banks: " + last(sent));
        sent.clear();
        sim.handle(OscMessage.of("/sim/state"), app);
        List<OscMessage> decks = at("/sim/deck");
        assertEquals(2, decks.size());
        assertEquals(OscMessage.of("/sim/deck", 2, "Simulated Track B", 33, 0, 0, 124f, 0), decks.get(1));
        assertEquals(2, console.stream().filter(s -> s.startsWith("deck ")).count());
    }

    @Test
    void builtInTracks() {
        for (String name : SimTrack.BUILT_IN) assertNotNull(SimTrack.builtIn(name), name);
        assertNull(SimTrack.builtIn("nope"));
        assertNull(SimTrack.builtIn("plain").phrases);
        SimTrack s = SimTrack.builtIn("short");
        List<Integer> starts = s.phrases.entries.stream().map(e -> e.startBeat).collect(Collectors.toList());
        assertEquals(List.of(1, 17, 33, 49, 65, 81), starts);
        assertEquals(97, s.phrases.endBeat);
        assertEquals(96 + 8, s.beatCount);
        assertTrue(s.phrases.entries.get(0).fill);
        assertEquals(9, s.phrases.entries.get(0).fillBeat);
        assertFalse(s.phrases.entries.get(5).fill);
    }
}
