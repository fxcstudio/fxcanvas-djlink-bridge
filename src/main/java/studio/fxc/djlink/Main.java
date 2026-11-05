package studio.fxc.djlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.fxc.djlink.assets.AssetStore;
import studio.fxc.djlink.osc.OscCodec;
import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.OscServer;
import studio.fxc.djlink.sim.SimCommands;
import studio.fxc.djlink.sim.SimTrack;
import studio.fxc.djlink.sim.Simulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;

/**
 * Entry point: parse options, open the OSC server, then either connect to the
 * DJ Link network or run the simulator. With {@code --send}, act as a client
 * of a running simulator instead.
 */
public final class Main {
    /** How often simulated decks advance. */
    private static final long SIM_TICK_MS = 5;
    /** How long {@code --send} waits for an answer. */
    private static final int SEND_TIMEOUT_MS = 1500;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // Album art is decoded (and simulated art drawn) with java.awt. Headless,
        // so that never opens a window or a macOS dock icon.
        System.setProperty("java.awt.headless", "true");
        final Options options;
        try {
            options = Options.parse(args);
        } catch (Options.OptionsException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.print(Options.usage());
            System.exit(1);
            return;
        }
        if (options.help) {
            System.out.print(Options.usage());
            return;
        }
        if (options.version) {
            System.out.println(BridgeVersion.NAME + " " + BridgeVersion.VERSION + " (protocol " + BridgeVersion.PROTOCOL + ")");
            return;
        }
        if (!options.send.isEmpty()) {
            System.exit(sendCommands(options));
            return;
        }

        setUpLogging(options.logFile);
        Logger logger = LoggerFactory.getLogger(Main.class);
        logger.info("{} {} starting (protocol {})", BridgeVersion.NAME, BridgeVersion.VERSION, BridgeVersion.PROTOCOL);
        for (String ignored : options.ignored) {
            logger.warn("Ignoring option {}: Ableton Link is bridged by FXCanvas itself", ignored);
        }

        ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bridge-events");
            t.setDaemon(true);
            return t;
        });

        // Loopback only: FXCanvas runs on the same machine, and nothing else should
        // be able to drive the bridge from the network.
        OscServer server = new OscServer(InetAddress.getByName("127.0.0.1"), options.oscPort, events);
        BridgeState state = new BridgeState(server);
        final AssetStore assets = new AssetStore(options.assetDir != null
                ? Paths.get(options.assetDir) : AssetStore.defaultDir(options.oscPort));
        logger.info("Track assets go to {}", assets.dir());

        DjLinkAdapter adapter = null;
        if (options.simulate) {
            final Simulator sim;
            try {
                sim = createSimulator(options, state, server, assets);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("Cannot start the simulator: " + e.getMessage());
                server.close();
                System.exit(1);
                return;
            }
            state.setWaveformStyleListener(sim::waveformStyleChanged);
            server.setFallback((msg, route) -> {
                if (msg.address().startsWith("/sim/")) {
                    sim.handle(msg, route);
                } else {
                    state.handle(msg, route);
                }
            });
            events.scheduleAtFixedRate(() -> {
                try {
                    sim.tick(System.nanoTime());
                } catch (RuntimeException e) {
                    logger.error("Simulator tick failed", e);
                }
            }, SIM_TICK_MS, SIM_TICK_MS, TimeUnit.MILLISECONDS);
            startConsole(sim, events);
        } else {
            server.setFallback(state::handle);
            adapter = new DjLinkAdapter(state, events, options, assets);
            state.setDemandListener(adapter::demandChanged);
            state.setWaveformStyleListener(adapter::waveformStyleChanged);
            adapter.start();
        }
        server.start();
        logger.info("OSC server listening on 127.0.0.1:{}", server.localPort());

        final DjLinkAdapter running = adapter;
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down");
            if (running != null) {
                try {
                    running.stop();
                } catch (RuntimeException e) {
                    logger.warn("Problem stopping DJ Link", e);
                }
            }
            server.close();
            events.shutdownNow();
            assets.close();
            done.countDown();
        }, "shutdown"));
        done.await();
    }

    // =========================================================================
    // Simulator
    // =========================================================================

    private static Simulator createSimulator(Options options, BridgeState state, OscServer server,
                                             AssetStore assets) throws IOException {
        List<SimTrack> tracks = Simulator.resolveTracks(options.simTracks);
        Simulator sim = new Simulator(state, server, options.simDecks, options.simSpeed, System.out::println,
                assets);
        if (options.simScript != null) {
            for (String line : Files.readAllLines(Paths.get(options.simScript), StandardCharsets.UTF_8)) {
                sim.addScriptLine(line);
            }
        }
        // Before the server starts, so the first client already sees the devices.
        sim.start(System.nanoTime(), tracks);
        return sim;
    }

    /** Commands typed on stdin run on the event thread. Ends quietly when stdin closes. */
    private static void startConsole(Simulator sim, ScheduledExecutorService events) {
        Thread console = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.equals("quit") || trimmed.equals("exit")) {
                        System.exit(0);
                    }
                    OscMessage msg = SimCommands.parse(trimmed);
                    if (msg != null) {
                        events.execute(() -> sim.command(msg));
                    }
                }
            } catch (IOException e) {
                // stdin is gone: nothing more to read
            }
        }, "sim-console");
        console.setDaemon(true);
        console.start();
    }

    // =========================================================================
    // --send client
    // =========================================================================

    /** @return the process exit code: 0 ok, 1 a command failed, 2 no simulator answered. */
    private static int sendCommands(Options options) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetSocketAddress bridge = new InetSocketAddress(loopback, options.oscPort);
        int failures = 0;
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            socket.setSoTimeout(SEND_TIMEOUT_MS);
            sendPacket(socket, bridge, OscMessage.of("/respond", socket.getLocalPort()));
            for (String text : options.send) {
                OscMessage msg = SimCommands.parse(text);
                if (msg == null) continue;
                sendPacket(socket, bridge, msg);
                boolean simCommand = msg.address().startsWith("/sim/");
                Boolean ok = awaitReply(socket, simCommand);
                if (ok == null) {
                    if (!simCommand) continue;   // raw OSC: print whatever came back, no answer is fine
                    System.err.println("No simulator answered on 127.0.0.1:" + options.oscPort
                            + " (is the bridge running with --simulate?)");
                    return 2;
                }
                if (!ok) failures++;
            }
        }
        return failures == 0 ? 0 : 1;
    }

    /**
     * Print replies until {@code /sim/ok} or {@code /sim/error}.
     *
     * @return true for ok, false for an error, null on timeout
     */
    private static Boolean awaitReply(DatagramSocket socket, boolean simCommand) throws IOException {
        byte[] buffer = new byte[65507];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                return null;
            }
            final List<OscMessage> replies;
            try {
                replies = OscCodec.decode(packet.getData(), packet.getOffset(), packet.getLength());
            } catch (OscCodec.OscFormatException e) {
                continue;
            }
            for (OscMessage reply : replies) {
                switch (reply.address()) {
                    case "/sim/ok":
                        if (simCommand) return true;
                        break;
                    case "/sim/error":
                        System.err.println(reply.stringArg(0));
                        if (simCommand) return false;
                        break;
                    case "/sim/deck":
                        System.out.println(describeDeck(reply));
                        break;
                    default:
                        if (!simCommand) System.out.println(reply);
                        break;
                }
            }
        }
    }

    private static String describeDeck(OscMessage deck) {
        // /sim/deck number title beat playing looping tempo master
        return String.format("deck %s: \"%s\" beat %s, %s%s, %.1f BPM%s", deck.arg(0), deck.arg(1), deck.arg(2),
                Integer.valueOf(1).equals(deck.intArg(3)) ? "playing" : "stopped",
                Integer.valueOf(1).equals(deck.intArg(4)) ? ", looping" : "",
                deck.arg(5) instanceof Number ? ((Number) deck.arg(5)).doubleValue() : 0.0,
                Integer.valueOf(1).equals(deck.intArg(6)) ? ", master" : "");
    }

    private static void sendPacket(DatagramSocket socket, InetSocketAddress to, OscMessage message)
            throws IOException {
        byte[] bytes = OscCodec.encode(message);
        socket.send(new DatagramPacket(bytes, bytes.length, to));
    }

    private static void setUpLogging(String logFile) throws IOException {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s [%3$s] %5$s%6$s%n");
        Handler handler;
        if (logFile != null) {
            handler = new FileHandler(logFile, 5 * 1024 * 1024, 3, true);
        } else {
            handler = new ConsoleHandler();
        }
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.INFO);
        root.addHandler(handler);
        root.setLevel(Level.INFO);
    }
}
