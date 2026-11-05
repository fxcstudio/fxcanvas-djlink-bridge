package studio.fxc.djlink.sim;

import org.junit.jupiter.api.Test;
import studio.fxc.djlink.osc.OscMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SimCommandsTest {

    @Test
    void wordsBecomeSimMessages() {
        assertEquals(OscMessage.of("/sim/loop", 1, 8), SimCommands.parse("loop 1 8"));
        assertEquals(OscMessage.of("/sim/tempo", 2, 126.5f), SimCommands.parse("  TEMPO  2   126.5 "));
        assertEquals(OscMessage.of("/sim/load", 2, "b", "hot"), SimCommands.parse("load 2 b hot"));
        assertEquals(OscMessage.of("/sim/state"), SimCommands.parse("state"));
    }

    @Test
    void quotesKeepSpacesAndStrings() {
        assertEquals(OscMessage.of("/sim/load", 1, "C:\\My Music\\ANLZ0000.EXT"),
                SimCommands.parse("load 1 \"C:\\My Music\\ANLZ0000.EXT\""));
        assertEquals(OscMessage.of("/sim/bank", 1, "7"), SimCommands.parse("bank 1 \"7\""));
    }

    @Test
    void rawAddressesPassThrough() {
        assertEquals(OscMessage.of("/version"), SimCommands.parse("/version"));
    }

    @Test
    void blanksAndCommentsAreNothing() {
        assertNull(SimCommands.parse(""));
        assertNull(SimCommands.parse("   "));
        assertNull(SimCommands.parse("# a comment"));
        assertNull(SimCommands.parse(null));
    }
}
