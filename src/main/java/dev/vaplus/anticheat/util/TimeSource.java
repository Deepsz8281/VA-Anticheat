package dev.vaplus.anticheat.util;

/**
 * Clock abstraction so violation decay, latency measurement and cooldowns can be driven by a fake
 * clock in tests and recorded-scenario replays.
 */
public interface TimeSource {

    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    };

    /** Monotonic time for measuring intervals. */
    long nanoTime();

    /** Wall-clock time for timestamps shown to humans or persisted. */
    long currentTimeMillis();
}
