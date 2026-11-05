package studio.fxc.djlink.osc;

import java.net.InetSocketAddress;

/** Where outgoing OSC messages go. The server implements it; tests record into it. */
public interface Outbox {
    void send(InetSocketAddress to, OscMessage message);
}
