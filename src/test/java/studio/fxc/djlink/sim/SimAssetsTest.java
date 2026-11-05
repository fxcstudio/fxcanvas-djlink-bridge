package studio.fxc.djlink.sim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.fxc.djlink.BridgeState;
import studio.fxc.djlink.assets.AssetCodec;
import studio.fxc.djlink.assets.AssetStore;
import studio.fxc.djlink.assets.BeatGridData;
import studio.fxc.djlink.assets.WaveformData;
import studio.fxc.djlink.model.PhraseData;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.ResponseRoute;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimAssetsTest {
    @TempDir
    Path dir;

    /** Mean of the low band over one phrase. */
    private static double meanLow(WaveformData w, SimTrack t, PhraseData.Entry e) {
        final long from = t.millisAt(e.startBeat - 1);
        final long to = t.millisAt(e.startBeat - 1 + 16);
        double sum = 0;
        int n = 0;
        for (int s = (int) (from * 150 / 1000); s < to * 150 / 1000; s++, n++) sum += w.low(s);
        return sum / n;
    }

    @Test
    void detailFollowsThePhrases() {
        SimTrack a = SimTrack.builtIn("a");
        WaveformData d = SimAssets.detail(a);
        assertEquals(WaveformData.Kind.DETAIL, d.kind);
        assertEquals(WaveformData.Style.THREE_BAND, d.style);
        assertEquals((int) Math.ceil(a.durationMs() * 150.0 / 1000), d.segmentCount());
        assertEquals(a.durationMs(), d.durationMs);
        // Track a: Intro 1, Up 1, Down, Chorus 1, ...
        List<PhraseData.Entry> p = a.phrases.entries;
        assertTrue(meanLow(d, a, p.get(3)) > meanLow(d, a, p.get(0)), "chorus louder than the intro");
        assertTrue(meanLow(d, a, p.get(3)) > meanLow(d, a, p.get(2)), "chorus louder than the breakdown");
        assertEquals(0, d.height(0), "silent before the first beat");
        assertArrayEquals(d.segments, SimAssets.detail(a).segments, "deterministic");
    }

    @Test
    void kicksLandOnTheBeat() {
        SimTrack t = SimTrack.builtIn("plain");
        WaveformData d = SimAssets.detail(t);
        // A segment on beat 9 against one half a beat later.
        int onBeat = (int) (t.millisAt(8) * 150 / 1000) + 1;
        int offBeat = (int) (t.millisAt(8.5) * 150 / 1000) + 1;
        assertTrue(d.low(onBeat) > d.low(offBeat) * 2);
        assertTrue(d.high(offBeat) > d.high(onBeat));
    }

    @Test
    void previewAndGrid() {
        SimTrack t = SimTrack.builtIn("short");
        WaveformData preview = SimAssets.preview(t, SimAssets.detail(t));
        assertEquals(SimAssets.PREVIEW_SEGMENTS, preview.segmentCount());
        assertEquals(WaveformData.Kind.PREVIEW, preview.kind);
        assertEquals(SimAssets.PREVIEW_SEGMENTS * 1000f / t.durationMs(), preview.segmentsPerSecond, 1e-3);
        BeatGridData g = SimAssets.beatGrid(t);
        assertEquals(t.beatCount, g.beatCount());
        assertEquals(t.millisAt(4), g.timesMs[4]);
        assertEquals(13000, g.tempo100[0]);
        assertEquals(List.of(1, 2, 3, 4, 1), Arrays.stream(g.beatInBar).limit(5).boxed().collect(Collectors.toList()));
    }

    @Test
    void artIsAJpeg() throws Exception {
        byte[] jpg = SimAssets.art(SimTrack.builtIn("b"));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpg));
        assertNotNull(image);
        assertEquals(SimAssets.ART_SIZE, image.getWidth());
        assertEquals(SimAssets.ART_SIZE, image.getHeight());
    }

    @Test
    void simulatorPublishesFilesForEachDeck() throws Exception {
        List<OscMessage> sent = new ArrayList<>();
        BridgeState state = new BridgeState((to, m) -> sent.add(m));
        ResponseRoute app = new ResponseRoute(InetAddress.getByName("127.0.0.1"), 50123);
        state.handle(OscMessage.of("/waveforms", "stream"), app);
        state.handle(OscMessage.of("/art", "stream"), app);
        try (AssetStore store = new AssetStore(dir.resolve("assets"))) {
            Simulator sim = new Simulator(state, (to, m) -> sent.add(m), 2, 1.0, s -> { }, store);
            sim.start(0, List.of());
            List<String> addresses = sent.stream().map(OscMessage::address).collect(Collectors.toList());
            for (String a : new String[]{"/waveform/1", "/beat-grid/1", "/art/1", "/waveform/2", "/art/2"}) {
                assertTrue(addresses.contains(a), a + " missing from " + addresses);
            }
            for (OscMessage m : sent) {
                if (!m.address().equals("/waveform/1")) continue;
                Path file = Paths.get((String) m.arg(7));
                WaveformData w = AssetCodec.decodeWaveform(Files.readAllBytes(file));
                assertEquals(9001, w.trackId);
                assertEquals(w.segmentCount(), m.intArg(4));
                assertEquals(w.kind.oscName(), m.arg(1));
            }
            // Loading another track replaces them.
            sent.clear();
            assertEquals(null, sim.command(SimCommands.parse("load 1 c")));
            assertTrue(sent.stream().anyMatch(m -> m.address().equals("/art/1") && m.intArg(0) == 9003));
        }
    }
}
