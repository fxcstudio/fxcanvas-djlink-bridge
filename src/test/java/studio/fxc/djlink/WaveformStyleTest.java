package studio.fxc.djlink;

import org.deepsymmetry.beatlink.data.WaveformFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.fxc.djlink.assets.WaveformData;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.ResponseRoute;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /waveforms style <name>}: choosing which rekordbox waveform style the bridge sends. */
class WaveformStyleTest {
    private RecordingOutbox out;
    private BridgeState state;
    private ResponseRoute app;
    private final List<WaveformData.Style> changes = new ArrayList<>();
    private final List<String> demand = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        out = new RecordingOutbox();
        state = new BridgeState(out);
        state.setWaveformStyleListener(changes::add);
        state.setDemandListener((topic, wanted) -> demand.add(topic + "=" + wanted));
        app = new ResponseRoute(InetAddress.getByName("127.0.0.1"), 50123);
        state.handle(OscMessage.of("/respond", 17003), app);
        out.clear();
    }

    @Test
    void parsesTheStyleNames() {
        assertEquals(WaveformData.Style.RGB, BridgeState.parseWaveformStyle("rgb"));
        assertEquals(WaveformData.Style.RGB, BridgeState.parseWaveformStyle("RGB"));
        assertEquals(WaveformData.Style.THREE_BAND, BridgeState.parseWaveformStyle("3band"));
        assertEquals(WaveformData.Style.THREE_BAND, BridgeState.parseWaveformStyle("three_band"));
        assertEquals(WaveformData.Style.BLUE, BridgeState.parseWaveformStyle(" blue "));
        assertNull(BridgeState.parseWaveformStyle("purple"));
        assertNull(BridgeState.parseWaveformStyle(""));
        assertNull(BridgeState.parseWaveformStyle(null));
    }

    @Test
    void threeBandUntilAClientAsks() {
        assertEquals(WaveformData.Style.THREE_BAND, state.waveformStyle());
    }

    @Test
    void styleCommandChangesTheStyleOnce() {
        state.handle(OscMessage.of("/waveforms", "style", "rgb"), app);
        assertEquals(List.of(WaveformData.Style.RGB), changes);
        assertEquals(WaveformData.Style.RGB, state.waveformStyle());
        // The same style again is not a change.
        state.handle(OscMessage.of("/waveforms", "style", "rgb"), app);
        assertEquals(1, changes.size());
        state.handle(OscMessage.of("/waveforms", "style", "3band"), app);
        assertEquals(List.of(WaveformData.Style.RGB, WaveformData.Style.THREE_BAND), changes);
        // Choosing a style is not a subscription and sends nothing back.
        assertTrue(demand.isEmpty());
        assertTrue(out.messages().isEmpty(), out.lines().toString());
    }

    @Test
    void unknownStyleIsAnErrorAndChangesNothing() {
        state.handle(OscMessage.of("/waveforms", "style", "purple"), app);
        state.handle(OscMessage.of("/waveforms", "style"), app);
        state.handle(OscMessage.of("/waveforms", "style", 3), app);
        assertTrue(changes.isEmpty());
        assertEquals(WaveformData.Style.THREE_BAND, state.waveformStyle());
        assertEquals(3, out.messages().stream().filter(m -> m.address().equals("/error")).count());
    }

    @Test
    void streamListAndStopStillWork() {
        state.handle(OscMessage.of("/waveforms", "style", "blue"), app);
        state.handle(OscMessage.of("/waveforms", "stream"), app);
        state.handle(OscMessage.of("/waveforms", "list"), app);
        state.handle(OscMessage.of("/waveforms", "stop"), app);
        assertEquals(List.of("/waveforms=true", "/waveforms=false"), demand);
        assertTrue(out.messages().stream().noneMatch(m -> m.address().equals("/error")), out.lines().toString());
    }

    @Test
    void adapterMapsToBeatLinkStyles() {
        assertEquals(WaveformFinder.WaveformStyle.RGB, DjLinkAdapter.finderStyle(WaveformData.Style.RGB));
        assertEquals(WaveformFinder.WaveformStyle.THREE_BAND, DjLinkAdapter.finderStyle(WaveformData.Style.THREE_BAND));
        assertEquals(WaveformFinder.WaveformStyle.BLUE, DjLinkAdapter.finderStyle(WaveformData.Style.BLUE));
    }
}
