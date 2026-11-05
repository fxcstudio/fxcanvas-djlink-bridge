package studio.fxc.djlink.osc;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Where responses to one client go. A client is identified by the host and
 * port its messages come from. Responses go to that port plus one until the
 * client sends {@code /respond port}, which is the open-beat-control rule.
 *
 * Stream subscriptions on the default response port hold this object, so a
 * later {@code /respond} moves them too.
 */
public final class ResponseRoute {
    private final InetAddress host;
    private final int sourcePort;
    private volatile int responsePort;

    public ResponseRoute(InetAddress host, int sourcePort) {
        this.host = host;
        this.sourcePort = sourcePort;
        this.responsePort = Math.min(sourcePort + 1, 65535);
    }

    public InetAddress host() {
        return host;
    }

    public int sourcePort() {
        return sourcePort;
    }

    public int responsePort() {
        return responsePort;
    }

    public void setResponsePort(int port) {
        this.responsePort = port;
    }

    /** Back to the default (source port plus one). */
    public void resetResponsePort() {
        this.responsePort = Math.min(sourcePort + 1, 65535);
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(host, responsePort);
    }

    @Override
    public String toString() {
        return host.getHostAddress() + ":" + sourcePort + " -> " + responsePort;
    }
}
