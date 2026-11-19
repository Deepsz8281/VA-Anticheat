package dev.vaplus.anticheat.profiling;

import dev.vaplus.anticheat.testutil.ManualTimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckProfiler")
class CheckProfilerTest {

    private ManualTimeSource time;
    private CheckProfiler profiler;

    @BeforeEach
    void setUp() {
        time = new ManualTimeSource();
        profiler = new CheckProfiler(4, time);
    }

    @Test
    @DisplayName("a fresh profiler is enabled and reports nothing")
    void fresh() {
        assertTrue(profiler.enabled());
        CheckProfiler.Report report = profiler.report();
        assertTrue(report.checks().isEmpty());
        assertEquals(0, report.packets());
        assertEquals(0, report.clientTicks());
        assertEquals(0, report.pipelineNanos());
        assertEquals(0.0, report.microsPerPlayerTick());
        assertEquals(0.0, report.averagePacketNanos());
    }

    @Test
    @DisplayName("recorded check time and calls are aggregated per slot, most expensive first, unused slots omitted")
    void recordAndReport() {
        profiler.recordCheck(2, 1_000);
        profiler.recordCheck(2, 3_000);
        profiler.recordCheck(0, 10_000);
        profiler.recordCheck(3, 500);

        List<CheckProfiler.CheckSample> samples = profiler.report().checks();
        assertEquals(3, samples.size());
        assertEquals(0, samples.get(0).slot());
        assertEquals(10_000, samples.get(0).totalNanos());
        assertEquals(1, samples.get(0).calls());
        assertEquals(2, samples.get(1).slot());
        assertEquals(4_000, samples.get(1).totalNanos());
        assertEquals(2, samples.get(1).calls());
        assertEquals(2_000.0, samples.get(1).averageNanos());
        assertEquals(3, samples.get(2).slot());
    }

    @Test
    @DisplayName("a call that took 0 ns still counts as a call")
    void zeroNanosCall() {
        profiler.recordCheck(1, 0);
        List<CheckProfiler.CheckSample> samples = profiler.report().checks();
        assertEquals(1, samples.size());
        assertEquals(1, samples.get(0).calls());
        assertEquals(0.0, samples.get(0).averageNanos());
        assertEquals(0.0, new CheckProfiler.CheckSample(0, 0, 0).averageNanos(), "no division by zero");
    }

    @Test
    @DisplayName("microsPerPlayerTick is pipeline time divided by client ticks, in microseconds")
    void microsPerPlayerTick() {
        profiler.recordPacket(10_000);
        profiler.recordPacket(15_000);
        for (int i = 0; i < 5; i++) {
            profiler.countClientTick();
        }
        CheckProfiler.Report report = profiler.report();
        assertEquals(25_000, report.pipelineNanos());
        assertEquals(2, report.packets());
        assertEquals(5, report.clientTicks());
        assertEquals(5.0, report.microsPerPlayerTick(), 1e-12);
        assertEquals(12_500.0, report.averagePacketNanos(), 1e-12);
    }

    @Test
    @DisplayName("packets without any client tick yield 0 per tick instead of infinity")
    void packetsWithoutTicks() {
        profiler.recordPacket(1_000);
        assertEquals(0.0, profiler.report().microsPerPlayerTick());
    }

    @Test
    @DisplayName("the report window follows the clock")
    void window() {
        time.advanceMillis(1500);
        assertEquals(1_500_000_000L, profiler.report().windowNanos());
    }

    @Test
    @DisplayName("reset clears every counter and restarts the window")
    void reset() {
        profiler.recordCheck(1, 100);
        profiler.recordPacket(100);
        profiler.countClientTick();
        time.advanceSeconds(10);
        profiler.reset();
        time.advanceSeconds(2);
        CheckProfiler.Report report = profiler.report();
        assertTrue(report.checks().isEmpty());
        assertEquals(0, report.packets());
        assertEquals(0, report.pipelineNanos());
        assertEquals(0, report.clientTicks());
        assertEquals(2_000_000_000L, report.windowNanos());
    }

    @Test
    @DisplayName("enabled flag can be toggled")
    void toggle() {
        profiler.setEnabled(false);
        assertFalse(profiler.enabled());
        profiler.setEnabled(true);
        assertTrue(profiler.enabled());
    }

    @Test
    @DisplayName("report is an immutable snapshot")
    void snapshot() {
        profiler.recordCheck(0, 1);
        CheckProfiler.Report report = profiler.report();
        profiler.recordCheck(0, 1);
        assertEquals(1, report.checks().get(0).calls());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> report.checks().clear());
    }

    @Test
    @DisplayName("concurrent recording from several threads loses no updates")
    void concurrent() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    profiler.recordCheck(1, 2);
                    profiler.recordPacket(3);
                    profiler.countClientTick();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        CheckProfiler.Report report = profiler.report();
        assertEquals(40_000, report.checks().get(0).calls());
        assertEquals(80_000, report.checks().get(0).totalNanos());
        assertEquals(40_000, report.packets());
        assertEquals(120_000, report.pipelineNanos());
        assertEquals(40_000, report.clientTicks());
    }
}
