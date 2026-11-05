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

            // A cover file of your own: sent as is, with its real size.
            Path cover = dir.resolve("cover.png");
            ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", cover.toFile());
            sent.clear();
            assertEquals(null, sim.command(SimCommands.parse("art 1 \"" + cover + "\"")));
            OscMessage art = sent.stream().filter(m -> m.address().equals("/art/1")).findFirst().orElseThrow();
            assertEquals(64, art.intArg(1));
            assertEquals(48, art.intArg(2));
            assertTrue(((String) art.arg(3)).endsWith(".png"));
            assertArrayEquals(Files.readAllBytes(cover), Files.readAllBytes(Paths.get((String) art.arg(3))));
        }
    }

    @Test
    void simulatorResendsWaveformsInTheRequestedStyle() throws Exception {
        List<OscMessage> sent = new ArrayList<>();
        BridgeState state = new BridgeState((to, m) -> sent.add(m));
        ResponseRoute app = new ResponseRoute(InetAddress.getByName("127.0.0.1"), 50123);
        state.handle(OscMessage.of("/waveforms", "stream"), app);
        Path assetDir = dir.resolve("styles");
        try (AssetStore store = new AssetStore(assetDir)) {
            Simulator sim = new Simulator(state, (to, m) -> sent.add(m), 2, 1.0, s -> { }, store);
            state.setWaveformStyleListener(sim::waveformStyleChanged);
            sim.start(0, List.of());
            assertEquals(List.of("three_band"), styles(sent), "three-band until a client asks");

            for (String[] c : new String[][]{{"rgb", "rgb"}, {"blue", "blue"}, {"3band", "three_band"}}) {
                sent.clear();
                state.handle(OscMessage.of("/waveforms", "style", c[0]), app);
                List<OscMessage> waveforms = sent.stream().filter(m -> m.address().startsWith("/waveform/"))
                        .collect(Collectors.toList());
                // Preview and detail for both loaded decks, without reloading anything.
                assertEquals(4, waveforms.size(), c[0] + ": " + waveforms);
                assertTrue(sent.stream().noneMatch(m -> m.address().endsWith("/none")), sent.toString());
                assertTrue(sent.stream().noneMatch(m -> m.address().startsWith("/beat-grid/")));
                assertEquals(List.of(c[1]), styles(sent));
                for (OscMessage m : waveforms) {
                    Path file = Paths.get((String) m.arg(7));
                    assertTrue(file.getFileName().toString().contains("-" + c[1] + "-"), file.toString());
                    WaveformData w = AssetCodec.decodeWaveform(Files.readAllBytes(file));
                    assertEquals(c[1], w.style.oscName());
                    assertEquals(c[1].equals("blue") ? 0 : 1, m.intArg(3), "bands flag");
                }
            }
            // The usual cleanup: at most two files per player and kind, whatever their style.
            try (var files = Files.list(assetDir)) {
                assertEquals(2, files.filter(f -> f.getFileName().toString().startsWith("p1-detail-")).count());
            }
        }
    }

    private static List<String> styles(List<OscMessage> sent) {
        return sent.stream().filter(m -> m.address().startsWith("/waveform/") && !m.address().endsWith("/none"))
                .map(m -> (String) m.arg(2)).distinct().collect(Collectors.toList());
    }

    @Test
    void realTrackFallsBackLikeThePlayers() {
        WaveformData rgb = WaveformData.normalize(5, WaveformData.Kind.DETAIL, WaveformData.Style.RGB, 150f, 1000,
                null, new float[]{1f}, new float[]{1f}, new float[]{1f});
        WaveformData blue = WaveformData.normalize(5, WaveformData.Kind.DETAIL, WaveformData.Style.BLUE, 150f, 1000,
                new float[]{1f}, null, null, null);
        java.util.Map<WaveformData.Style, WaveformData> both = new java.util.EnumMap<>(WaveformData.Style.class);
        both.put(WaveformData.Style.RGB, rgb);
        both.put(WaveformData.Style.BLUE, blue);
        SimTrack t = new SimTrack(5, "t", "a", 120f, 0, 8, null, both, both, null);
        assertEquals(WaveformData.Style.RGB, t.realDetail(WaveformData.Style.RGB).style);
        // No three-band data: blue, as a player sends.
        assertEquals(WaveformData.Style.BLUE, t.realDetail(WaveformData.Style.THREE_BAND).style);
        assertEquals(WaveformData.Style.RGB, t.realDetail.style, "best style present");
        // Only RGB: whatever there is.
        SimTrack rgbOnly = new SimTrack(5, "t", "a", 120f, 0, 8, null, null, rgb);
        assertEquals(WaveformData.Style.RGB, rgbOnly.realDetail(WaveformData.Style.THREE_BAND).style);
        // Generated tracks have none.
        assertEquals(null, SimTrack.builtIn("a").realDetail(WaveformData.Style.RGB));
    }
}
