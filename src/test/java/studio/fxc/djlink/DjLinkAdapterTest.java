package studio.fxc.djlink;

import org.deepsymmetry.beatlink.CdjStatus;
import org.junit.jupiter.api.Test;
import studio.fxc.djlink.model.DeckState;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DjLinkAdapterTest {
    /** Size of a nexus-era CDJ status packet. */
    private static final int STATUS_SIZE = 0xd4;

    /** A minimal CDJ status packet from player 2 with the given first play state. */
    private static CdjStatus status(CdjStatus.PlayState1 play) throws Exception {
        final byte[] bytes = new byte[STATUS_SIZE];
        final byte[] magic = {0x51, 0x73, 0x70, 0x74, 0x31, 0x57, 0x6d, 0x4a, 0x4f, 0x4c};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        bytes[0x0a] = 0x0a;   // CDJ status packet type
        final byte[] name = "CDJ-3000X".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, bytes, 0x0b, name.length);
        bytes[0x21] = 2;      // device number
        final int payload = STATUS_SIZE - 0x24;
        bytes[0x22] = (byte) (payload >> 8);
        bytes[0x23] = (byte) payload;
        bytes[0x24] = 2;      // device number, repeated
        bytes[0x7b] = (byte) play.protocolValue;
        return new CdjStatus(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), 50002));
    }

    @Test
    void emergencyLoopCountsAsLooping() throws Exception {
        final DeckState deck = DjLinkAdapter.toDeckState(status(CdjStatus.PlayState1.EMERGENCY_LOOP));
        assertEquals("emergency_loop", deck.playState);
        assertTrue(deck.looping);
    }

    @Test
    void normalLoopIsLooping() throws Exception {
        final DeckState deck = DjLinkAdapter.toDeckState(status(CdjStatus.PlayState1.LOOPING));
        assertEquals("looping", deck.playState);
        assertTrue(deck.looping);
    }

    @Test
    void playingIsNotLooping() throws Exception {
        final DeckState deck = DjLinkAdapter.toDeckState(status(CdjStatus.PlayState1.PLAYING));
        assertEquals("playing", deck.playState);
        assertFalse(deck.looping);
    }

    @Test
    void passiveOnlyAboveFourWithAnOlderPlayer() {
        assertFalse(DjLinkAdapter.usePassiveMetadata(2, true));
        assertFalse(DjLinkAdapter.usePassiveMetadata(4, true));
        assertFalse(DjLinkAdapter.usePassiveMetadata(7, false), "CDJ-3000X only: query the players directly");
        assertTrue(DjLinkAdapter.usePassiveMetadata(7, true));
    }
}
