package studio.fxc.djlink.assets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The folder the bridge writes track assets to (waveforms, beat grids,
 * album art). OSC carries only the file paths: a detail waveform is far
 * larger than a UDP datagram.
 *
 * Files are written under a temporary name and moved into place, so a
 * client never reads a half-written file. The newest {@link #KEEP} files per
 * key (for example {@code p2-detail}) are kept, so a client still reading the
 * previous one is not cut off; older ones are deleted. The folder is emptied
 * at start and removed at {@link #close()}.
 *
 * Thread safe: the network finders write from their own threads.
 */
public final class AssetStore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AssetStore.class);
    static final int KEEP = 2;

    private final Path dir;
    private final Map<String, Deque<Path>> recent = new HashMap<>();
    private long sequence;

    public AssetStore(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath();
        Files.createDirectories(this.dir);
        deleteContents();
    }

    /** The default folder: one per OSC port, so two bridges never share files. */
    public static Path defaultDir(int oscPort) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "fxc-djlink-bridge", "port-" + oscPort);
    }

    public Path dir() {
        return dir;
    }

    /** Write {@code bytes} as the newest file for {@code key}; returns its absolute path. */
    public synchronized Path write(String key, String extension, byte[] bytes) throws IOException {
        final long n = ++sequence;
        Path tmp = dir.resolve(key + "-" + n + ".tmp");
        Path target = dir.resolve(key + "-" + n + "." + extension);
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Deque<Path> files = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
        files.addLast(target);
        while (files.size() > KEEP) {
            deleteQuietly(files.removeFirst());
        }
        return target;
    }

    /** Delete every file for {@code key} (the track is gone). */
    public synchronized void forget(String key) {
        Deque<Path> files = recent.remove(key);
        if (files == null) return;
        for (Path p : files) deleteQuietly(p);
    }

    @Override
    public synchronized void close() {
        recent.clear();
        deleteContents();
        try {
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            logger.debug("Could not remove {}: {}", dir, e.getMessage());
        }
    }

    private void deleteContents() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                if (Files.isRegularFile(p)) deleteQuietly(p);
            }
        } catch (IOException e) {
            logger.debug("Could not list {}: {}", dir, e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // Windows refuses while a client has it open; the next start clears it.
            logger.debug("Could not delete {}: {}", p, e.getMessage());
        }
    }
}
