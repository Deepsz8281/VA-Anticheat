package dev.vaplus.anticheat.profiling;

import dev.vaplus.anticheat.util.TimeSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free timing counters for {@code /ac profile}. Per-check cost is attributed by slot; the
 * pipeline counter covers all work done for incoming packets (including checks), so
 * {@code pipelineNanos / clientTicks} is the average cost per player per tick.
 */
public final class CheckProfiler {

    /** Aggregated cost of one check slot. */
    public record CheckSample(int slot, long totalNanos, long calls) {
        public double averageNanos() {
            return calls == 0 ? 0.0 : (double) totalNanos / calls;
        }
    }

    /** Snapshot of all counters since the last reset. */
    public record Report(long windowNanos, long pipelineNanos, long packets, long clientTicks, List<CheckSample> checks) {
        /** Average processing time per player per client tick, in microseconds. */
        public double microsPerPlayerTick() {
            return clientTicks == 0 ? 0.0 : pipelineNanos / 1_000.0 / clientTicks;
        }

        public double averagePacketNanos() {
            return packets == 0 ? 0.0 : (double) pipelineNanos / packets;
        }
    }

    private final TimeSource time;
    private final LongAdder[] checkNanos;
    private final LongAdder[] checkCalls;
    private final LongAdder pipelineNanos = new LongAdder();
    private final LongAdder packets = new LongAdder();
    private final LongAdder clientTicks = new LongAdder();
    private volatile boolean enabled = true;
    private volatile long windowStartNanos;

    public CheckProfiler(int slots, TimeSource time) {
        this.time = time;
        this.checkNanos = new LongAdder[slots];
        this.checkCalls = new LongAdder[slots];
        for (int i = 0; i < slots; i++) {
            checkNanos[i] = new LongAdder();
            checkCalls[i] = new LongAdder();
        }
        this.windowStartNanos = time.nanoTime();
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void recordCheck(int slot, long nanos) {
        checkNanos[slot].add(nanos);
        checkCalls[slot].increment();
    }

    public void recordPacket(long nanos) {
        pipelineNanos.add(nanos);
        packets.increment();
    }

    public void countClientTick() {
        clientTicks.increment();
    }

    public void reset() {
        for (int i = 0; i < checkNanos.length; i++) {
            checkNanos[i].reset();
            checkCalls[i].reset();
        }
        pipelineNanos.reset();
        packets.reset();
        clientTicks.reset();
        windowStartNanos = time.nanoTime();
    }

    /** Snapshot sorted by total time, most expensive first. */
    public Report report() {
        List<CheckSample> samples = new ArrayList<>(checkNanos.length);
        for (int i = 0; i < checkNanos.length; i++) {
            long calls = checkCalls[i].sum();
            if (calls > 0) {
                samples.add(new CheckSample(i, checkNanos[i].sum(), calls));
            }
        }
        samples.sort(Comparator.comparingLong(CheckSample::totalNanos).reversed());
        return new Report(time.nanoTime() - windowStartNanos, pipelineNanos.sum(), packets.sum(), clientTicks.sum(),
                List.copyOf(samples));
    }
}
