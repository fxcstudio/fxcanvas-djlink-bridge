package studio.fxc.djlink;

import org.junit.jupiter.api.Test;
import studio.fxc.djlink.osc.OscCodec;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.OscServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** End to end over loopback UDP, the way FXCanvas talks to the bridge. */
class OscServerTest {

    @Test
    void answersVersionOnTheRespondPort() throws Exception {
        ExecutorService events = Executors.newSingleThreadExecutor();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (OscServer server = new OscServer(loopback, 0, events);
             DatagramSocket app = new DatagramSocket(0, loopback);
             DatagramSocket appReceive = new DatagramSocket(0, loopback)) {
            BridgeState state = new BridgeState(server);
            server.setFallback(state::handle);
            server.start();
            appReceive.setSoTimeout(5000);

            InetSocketAddress bridge = new InetSocketAddress(loopback, server.localPort());
            send(app, bridge, OscMessage.of("/respond", appReceive.getLocalPort()));
            send(app, bridge, OscMessage.of("/version"));

            byte[] buf = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            appReceive.receive(packet);
            List<OscMessage> got = OscCodec.decode(packet.getData(), 0, packet.getLength());
            assertEquals(List.of(OscMessage.of("/version", BridgeVersion.NAME, BridgeVersion.VERSION,
                    BridgeVersion.PROTOCOL)), got);
        } finally {
            events.shutdownNow();
        }
    }

    private static void send(DatagramSocket socket, InetSocketAddress to, OscMessage m) throws Exception {
        byte[] bytes = OscCodec.encode(m);
        socket.send(new DatagramPacket(bytes, bytes.length, to));
    }
}
