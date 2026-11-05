package studio.fxc.djlink.osc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * UDP OSC server. Receives on one socket, decodes on its own thread, and hands
 * every message to {@code eventThread}, so handlers never run concurrently.
 * Sends from the same socket.
 */
public final class OscServer implements Outbox, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OscServer.class);
    private static final int MAX_PACKET = 65507;

    /** Handles one message from one client. */
    public interface Handler {
        void handle(OscMessage message, ResponseRoute client);
    }

    private final DatagramSocket socket;
    private final Executor eventThread;
    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, ResponseRoute> routes = new HashMap<>();
    private Handler fallback;
    private Thread receiver;
    private volatile boolean running;

    public OscServer(InetAddress bindAddress, int port, Executor eventThread) throws SocketException {
        this.socket = new DatagramSocket(new InetSocketAddress(bindAddress, port));
        this.eventThread = eventThread;
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    /** Register before {@link #start()}. */
    public void register(String address, Handler handler) {
        handlers.put(address, handler);
    }

    /** Called for addresses with no handler. */
    public void setFallback(Handler handler) {
        this.fallback = handler;
    }

    public void start() {
        running = true;
        receiver = new Thread(this::receiveLoop, "osc-receive");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    logger.warn("OSC receive failed", e);
                }
                continue;
            }
            final List<OscMessage> messages;
            try {
                messages = OscCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
            } catch (OscCodec.OscFormatException e) {
                logger.warn("Ignoring malformed OSC packet from {}: {}", packet.getSocketAddress(), e.getMessage());
                continue;
            }
            final InetAddress host = packet.getAddress();
            final int sourcePort = packet.getPort();
            eventThread.execute(() -> dispatch(messages, host, sourcePort));
        }
    }

    private void dispatch(List<OscMessage> messages, InetAddress host, int sourcePort) {
        ResponseRoute route = routes.computeIfAbsent(host.getHostAddress() + ":" + sourcePort,
                k -> new ResponseRoute(host, sourcePort));
        for (OscMessage message : messages) {
            Handler handler = handlers.getOrDefault(message.address(), fallback);
            if (handler == null) {
                continue;
            }
            try {
                handler.handle(message, route);
            } catch (RuntimeException e) {
                logger.error("Handler for {} failed", message.address(), e);
            }
        }
    }

    @Override
    public void send(InetSocketAddress to, OscMessage message) {
        byte[] bytes = OscCodec.encode(message);
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, to));
        } catch (IOException e) {
            logger.debug("Unable to send {} to {}: {}", message.address(), to, e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
