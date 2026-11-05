package studio.fxc.djlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest {

    @Test
    void acceptsWhatFxCanvasPassedToOpenBeatControl() throws Exception {
        // OBCConfig::buildArgs(): -o 17002 -c 17000 -l 20 [-r] [-B]
        Options o = Options.parse(new String[]{"-o", "17002", "-c", "17000", "-l", "20", "-r", "-B"});
        assertEquals(17002, o.oscPort);
        assertEquals(20, o.latencyMs);
        assertTrue(o.realPlayer);
        assertEquals(2, o.ignored.size());
    }

    @Test
    void defaults() throws Exception {
        Options o = Options.parse(new String[0]);
        assertEquals(17002, o.oscPort);
        assertEquals(0, o.deviceNumber);
        assertFalse(o.realPlayer);
        assertTrue(o.ignored.isEmpty());
    }

    @Test
    void longFormsAndDeviceNumber() throws Exception {
        Options o = Options.parse(new String[]{"--osc-port", "4000", "--device-number", "7", "--log-file", "x.log"});
        assertEquals(4000, o.oscPort);
        assertEquals(7, o.deviceNumber);
        assertEquals("x.log", o.logFile);
    }

    @Test
    void simulatorOptions() throws Exception {
        Options plain = Options.parse(new String[0]);
        assertFalse(plain.simulate);

        Options o = Options.parse(new String[]{"--simulate", "--sim-decks", "3", "--sim-speed", "2.5",
                "--sim-track", "b", "--anlz", "x/ANLZ0000.EXT", "--sim-script", "show.txt"});
        assertTrue(o.simulate);
        assertEquals(3, o.simDecks);
        assertEquals(2.5, o.simSpeed);
        assertEquals(java.util.List.of("b", "x/ANLZ0000.EXT"), o.simTracks);
        assertEquals("show.txt", o.simScript);

        assertTrue(Options.parse(new String[]{"--sim-track", "a"}).simulate, "any --sim-* implies --simulate");
        assertEquals(4, Options.parse(new String[]{"--sim-track", "a", "--sim-track", "b", "--sim-track", "c",
                "--sim-track", "short"}).simDecks, "one deck per track");

        Options send = Options.parse(new String[]{"-o", "17102", "--send", "loop 1 8", "--send", "state"});
        assertFalse(send.simulate);
        assertEquals(java.util.List.of("loop 1 8", "state"), send.send);

        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"--sim-decks", "7"}));
        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"--sim-speed", "0"}));
        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"--send"}));
    }

    @Test
    void rejectsBadValues() {
        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"-o", "0"}));
        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"-o", "abc"}));
        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"-d", "200"}));
        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"-l"}));
        assertThrows(Options.OptionsException.class, () -> Options.parse(new String[]{"--nope"}));
    }
}
