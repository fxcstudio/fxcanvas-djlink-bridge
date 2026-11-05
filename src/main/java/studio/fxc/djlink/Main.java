package studio.fxc.djlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.fxc.djlink.osc.OscServer;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;

/** Entry point: parse options, open the OSC server, connect to the DJ Link network. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
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

        setUpLogging(options.logFile);
        Logger logger = LoggerFactory.getLogger(Main.class);
        logger.info("{} {} starting (protocol {})", BridgeVersion.NAME, BridgeVersion.VERSION, BridgeVersion.PROTOCOL);
        for (String ignored : options.ignored) {
            logger.warn("Ignoring option {}: Ableton Link is bridged by FXCanvas itself", ignored);
        }

        ExecutorService events = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bridge-events");
            t.setDaemon(true);
            return t;
        });

        // Loopback only: FXCanvas runs on the same machine, and nothing else should
        // be able to drive the bridge from the network.
        OscServer server = new OscServer(InetAddress.getByName("127.0.0.1"), options.oscPort, events);
        BridgeState state = new BridgeState(server);
        server.setFallback(state::handle);

        DjLinkAdapter adapter = new DjLinkAdapter(state, events, options);
        adapter.start();
        server.start();
        logger.info("OSC server listening on 127.0.0.1:{}", server.localPort());

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down");
            try {
                adapter.stop();
            } catch (RuntimeException e) {
                logger.warn("Problem stopping DJ Link", e);
            }
            server.close();
            events.shutdownNow();
            done.countDown();
        }, "shutdown"));
        done.await();
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
