package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.util.TimeSource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs exceptions thrown by checks without letting one broken check take down the packet pipeline
 * or flood the console: each check id logs at most once per minute.
 */
public final class CheckErrorReporter {

    private static final long LOG_INTERVAL_MILLIS = 60_000L;

    private final Logger logger;
    private final TimeSource time;
    private final ConcurrentHashMap<String, AtomicLong> lastLogged = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();

    public CheckErrorReporter(Logger logger, TimeSource time) {
        this.logger = logger;
        this.time = time;
    }

    public void report(Check check, Throwable error) {
        report(check.descriptor().id(), check.player().name(), error);
    }

    public void report(String source, String playerName, Throwable error) {
        total.increment();
        long now = time.currentTimeMillis();
        AtomicLong last = lastLogged.computeIfAbsent(source, key -> new AtomicLong(Long.MIN_VALUE));
        long previous = last.get();
        if (previous != Long.MIN_VALUE && now - previous < LOG_INTERVAL_MILLIS) {
            return;
        }
        if (last.compareAndSet(previous, now)) {
            logger.log(Level.WARNING, "Error in " + source + " while processing " + playerName
                    + " (repeats of this error are suppressed for 60s)", error);
        }
    }

    /** Total number of errors since start, including suppressed ones. */
    public long totalErrors() {
        return total.sum();
    }
}
