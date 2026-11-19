package dev.vaplus.anticheat.diagnostics;

import dev.vaplus.anticheat.player.PlayerData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional movement recorder for the replay harness (plan item 7): with
 * {@code -Dvaplus.record=<file>} every accepted movement packet is appended as one text line
 * ({@code M,<millis>,<x>,<y>,<z>,<yaw>,<pitch>,<ground>}).
 *
 * <p>Owner thread only. Recording is a no-op (one boolean check) unless the system property is set;
 * the writer is opened lazily on the first frame so a broken path cannot break the plugin start.
 * The format is line-based and locale-independent so it can be diffed, grepped and replayed in
 * tests through {@link ReplayFile} and the replay harness.
 */
public final class ReplayRecorder implements AutoCloseable {

    public static final String PROPERTY = "vaplus.record";

    private static volatile ReplayRecorder active;
    private static volatile boolean initialized;

    private final Logger logger;
    private final Path path;
    private BufferedWriter writer;
    private long startNanos;
    private boolean failed;

    private ReplayRecorder(Logger logger, Path path) {
        this.logger = logger;
        this.path = path;
    }

    /** A recorder that writes nothing. */
    public static ReplayRecorder disabled() {
        return new ReplayRecorder(Logger.getGlobal(), null);
    }

    /** The process-wide recorder configured through {@code -Dvaplus.record}; disabled by default. */
    public static ReplayRecorder active() {
        if (!initialized) {
            synchronized (ReplayRecorder.class) {
                if (!initialized) {
                    active = fromSystemProperty(Logger.getGlobal());
                    initialized = true;
                }
            }
        }
        return active;
    }

    /** Opens a recorder for an explicit path (tests, tooling). */
    public static ReplayRecorder open(Path path, Logger logger) {
        return new ReplayRecorder(logger, java.util.Objects.requireNonNull(path, "path"));
    }

    /** Test/plugin entry point: no property means a disabled recorder. */
    public static ReplayRecorder fromSystemProperty(Logger logger) {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            return new ReplayRecorder(logger, null);
        }
        return new ReplayRecorder(logger, Path.of(value.trim()));
    }

    /** Closes the process-wide recorder (plugin shutdown). */
    public static void closeActive() {
        ReplayRecorder recorder = active;
        if (recorder != null) {
            recorder.close();
        }
    }

    public boolean enabled() {
        return path != null;
    }

    public Path path() {
        return path;
    }

    /**
     * Records the player's current movement state. Never throws: a full disk must not affect the
     * anticheat, so the first failure disables the recorder and logs once.
     */
    public void record(PlayerData player, long nowNanos) {
        if (!enabled() || failed) {
            return;
        }
        if (startNanos == 0L) {
            startNanos = nowNanos;
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                logger.info("VA+ replay recording to " + path.toAbsolutePath());
            } catch (IOException exception) {
                fail(exception);
                return;
            }
        }
        var movement = player.movement();
        String line = String.format(Locale.ROOT, "M,%d,%.6f,%.6f,%.6f,%.3f,%.3f,%d%n",
                (nowNanos - startNanos) / 1_000_000L,
                movement.x(), movement.y(), movement.z(),
                movement.yaw(), movement.pitch(), movement.onGround() ? 1 : 0);
        try {
            writer.write(line);
        } catch (IOException exception) {
            fail(exception);
        }
    }

    private void fail(IOException exception) {
        failed = true;
        logger.log(Level.WARNING, "VA+ replay recording disabled: " + exception.getMessage(), exception);
    }

    @Override
    public void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException exception) {
                logger.log(Level.WARNING, "VA+ replay recording could not be closed", exception);
            }
            writer = null;
        }
    }
}
