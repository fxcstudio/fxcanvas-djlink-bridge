package studio.fxc.djlink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.model.PositionData;
import studio.fxc.djlink.model.TrackInfo;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.ResponseRoute;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OSC contract. The compat cases pin the messages FXCanvas's
 * PioneerOSCHandlers reads (address, argument order and types), so a change
 * here is a change the app must follow.
 */
class BridgeStateTest {
    private RecordingOutbox out;
    private BridgeState state;
    private ResponseRoute app;

    @BeforeEach
    void setUp() throws Exception {
        out = new RecordingOutbox();
        state = new BridgeState(out);
        // The app sends from an ephemeral port and asks for responses on 17003.
        app = new ResponseRoute(InetAddress.getByName("127.0.0.1"), 50123);
        state.handle(OscMessage.of("/respond", 17003), app);
        for (String topic : new String[]{"/master", "/devices", "/tempos", "/beats", "/on-air", "/playing",
                "/metadata", "/phrases", "/positions"}) {
            state.handle(OscMessage.of(topic, "stream"), app);
        }
    }

    @Test
    void respondPortRoutesEverything() {
        // The only state at startup is "no master": the app relies on it to see the bridge is up.
        assertEquals(List.of("17003 /master/none"), out.lines());
    }

    @Test
    void streamingTwiceDoesNotDuplicate() {
        state.handle(OscMessage.of("/beats", "stream"), app);
        out.clear();
        state.beat(2, 1, 128f, 128f, 1f);
        long beats = out.messages().stream().filter(m -> m.address().equals("/beat")).count();
        assertEquals(1, beats);
    }

    @Test
    void stopEndsAStream() {
        state.handle(OscMessage.of("/beats", "stop"), app);
        out.clear();
        state.beat(2, 1, 128f, 128f, 1f);
        assertTrue(out.messages().stream().noneMatch(m -> m.address().equals("/beat")));
    }

    @Test
    void compatDeviceMessages() {
        out.clear();
        state.deviceFound(2, "CDJ-3000", "192.168.1.12");
        state.deviceLost(2, "CDJ-3000", "192.168.1.12");
        assertEquals(List.of(
                OscMessage.of("/device/found", 2, "CDJ-3000", "192.168.1.12"),
                OscMessage.of("/device/lost", 2, "CDJ-3000", "192.168.1.12")), out.messages());
    }

    @Test
    void compatBeatTempoPlayingAndMaster() {
        out.clear();
        state.masterChanged(2);
        state.masterTempo(127.5f);
        state.beat(2, 3, 127.5f, 126f, 1.0119f);
        state.masterBeat(2, 3, 127.5f, 126f, 1.0119f);
        state.status(2, 127.5f, true, true, true);
        assertEquals(List.of(
                OscMessage.of("/master/player", 2),
                OscMessage.of("/master/tempo", 127.5f),
                OscMessage.of("/beat", 2, 3, 127.5f, 126f, 1.0119f),
                OscMessage.of("/tempo/2", 127.5f),
                OscMessage.of("/playing/2", 1),
                OscMessage.of("/master/playing", 1),
                OscMessage.of("/master/beat", 2, 3, 127.5f, 126f, 1.0119f),
                OscMessage.of("/on-air/2", 1)), out.messages());
    }

    @Test
    void statusOnlyReportsChanges() {
        state.status(1, 120f, true, false, false);
        out.clear();
        state.status(1, 120f, true, false, false);
        assertTrue(out.messages().isEmpty());
        state.status(1, Float.NaN, true, true, false);
        assertEquals(List.of(OscMessage.of("/playing/1", 1)), out.messages());
    }

    @Test
    void masterNoneAndMixerMaster() {
        state.masterChanged(1);
        state.beat(1, 1, 120f, 120f, 1f);
        out.clear();
        state.masterChanged(33);
        state.masterChanged(null);
        assertEquals(List.of(
                OscMessage.of("/master/player", 33),
                OscMessage.of("/master/playing", 0),
                OscMessage.of("/master/none")), out.messages());
    }

    @Test
    void compatMetadataMessages() {
        out.clear();
        state.metadata(2, TrackInfo.basic(819, "Running To The Sea", "Royksopp"));
        List<String> addresses = out.messages().stream().map(OscMessage::address)
                .filter(a -> a.startsWith("/metadata")).collect(java.util.stream.Collectors.toList());
        assertEquals(List.of("/metadata/2/title", "/metadata/2/artist", "/metadata/2/color", "/metadata/2/comment",
                "/metadata/2/duration", "/metadata/2/rating", "/metadata/2/tempo"), addresses);
        assertEquals(OscMessage.of("/metadata/2/title", "Running To The Sea"), out.messages().get(0));
        out.clear();
        state.metadata(2, null);
        assertEquals(OscMessage.of("/metadata/2/none"), out.messages().get(0));
    }

    @Test
    void newTrackMakesOldPhrasesStaleUntilItsOwnArrive() {
        state.metadata(2, TrackInfo.basic(100, "A", "B"));
        state.phrases(2, phrases(100));
        out.clear();

        state.metadata(2, TrackInfo.basic(200, "C", "D"));
        assertTrue(out.messages().contains(OscMessage.of("/phrases/2/none", 200)));

        out.clear();
        state.phrases(2, phrases(200));
        assertEquals("/phrases/2", out.messages().get(0).address());
        assertEquals(200, out.messages().get(0).arg(0));
    }

    @Test
    void phraseNoneIsSentOncePerTrack() {
        out.clear();
        state.phrasesNone(3, 55);
        state.phrasesNone(3, 55);
        assertEquals(List.of(OscMessage.of("/phrases/3/none", 55)), out.messages());
    }

    @Test
    void phraseStreamStartReplaysCurrentState() {
        state.phrases(1, phrases(7));
        state.phrasesNone(2, 9);
        out.clear();
        state.handle(OscMessage.of("/phrases", "stream"), app);
        assertEquals(2, out.messages().size());
        assertEquals("/phrases/1", out.messages().get(0).address());
        assertEquals(OscMessage.of("/phrases/2/none", 9), out.messages().get(1));
    }

    @Test
    void positions() {
        out.clear();
        state.position(4, new PositionData(819, 129, true, true, PositionData.REASON_BEAT));
        assertEquals(List.of(OscMessage.of("/position/4", 819, 129, 1, 1, 0)), out.messages());
    }

    @Test
    void versionAnswersOnTheResponsePort() {
        out.clear();
        state.handle(OscMessage.of("/version"), app);
        assertEquals(List.of("17003 /version \"fxc-djlink-bridge\" \"" + BridgeVersion.VERSION + "\" "
                + BridgeVersion.PROTOCOL), out.lines());
    }

    @Test
    void streamToAnExplicitPort() {
        state.handle(OscMessage.of("/tempos", "stream", 4200), app);
        out.clear();
        state.status(2, 128f, true, false, false);
        assertTrue(out.lines().contains("4200 /tempo/2 128.0"));
        assertTrue(out.lines().contains("17003 /tempo/2 128.0"));
    }

    @Test
    void errors() {
        out.clear();
        state.handle(OscMessage.of("/tempos"), app);
        state.handle(OscMessage.of("/tempos", "bogus"), app);
        state.handle(OscMessage.of("/tempos", "stream", 70000), app);
        assertEquals(3, out.messages().stream().filter(m -> m.address().equals("/error")).count());
    }

    private static PhraseData phrases(int trackId) {
        return new PhraseData(trackId, "mid", "", 129, List.of(
                new PhraseData.Entry(1, "intro", "Intro", false, 0),
                new PhraseData.Entry(65, "chorus", "Chorus", true, 121)));
    }
}
