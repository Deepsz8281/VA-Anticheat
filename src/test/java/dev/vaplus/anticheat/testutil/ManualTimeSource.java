package dev.vaplus.anticheat.testutil;

import dev.vaplus.anticheat.util.TimeSource;

import java.util.concurrent.TimeUnit;

/** Settable clock; monotonic and wall-clock time advance together unless set explicitly. */
public final class ManualTimeSource implements TimeSource {

    public static final long DEFAULT_START_NANOS = 5_000_000_000L;
    public static final long DEFAULT_START_MILLIS = 1_700_000_000_000L;

    private long nanos;
    private long millis;

    public ManualTimeSource() {
        this(DEFAULT_START_NANOS, DEFAULT_START_MILLIS);
    }

    public ManualTimeSource(long nanos, long millis) {
        this.nanos = nanos;
        this.millis = millis;
    }

    @Override
    public long nanoTime() {
        return nanos;
    }

    @Override
    public long currentTimeMillis() {
        return millis;
    }

    public ManualTimeSource setNanoTime(long nanos) {
        this.nanos = nanos;
        return this;
    }

    public ManualTimeSource setCurrentTimeMillis(long millis) {
        this.millis = millis;
        return this;
    }

    public ManualTimeSource advance(long amount, TimeUnit unit) {
        nanos += unit.toNanos(amount);
        millis += unit.toMillis(amount);
        return this;
    }

    public ManualTimeSource advanceNanos(long amount) {
        return advance(amount, TimeUnit.NANOSECONDS);
    }

    public ManualTimeSource advanceMillis(long amount) {
        return advance(amount, TimeUnit.MILLISECONDS);
    }

    public ManualTimeSource advanceSeconds(long amount) {
        return advance(amount, TimeUnit.SECONDS);
    }

    public ManualTimeSource advanceMinutes(long amount) {
        return advance(amount, TimeUnit.MINUTES);
    }
}
