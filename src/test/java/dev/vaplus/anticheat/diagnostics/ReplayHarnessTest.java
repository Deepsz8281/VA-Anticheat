package dev.vaplus.anticheat.diagnostics;

import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.replay.ReplayHarness;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.carry;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.groundAccel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Replay harness (recorded frames through the real engine)")
class ReplayHarnessTest {

    private static final double WALK = groundAccel(0.1, 0.98, 1.0, 0.6);

    private static List<ReplayFrame> walk(double excessPerTick, int ticks) {
        List<ReplayFrame> frames = new ArrayList<>();
        double z = 0.5;
        double delta = 0.0;
        frames.add(new ReplayFrame(0L, 0.5, 64.0, z, 0f, 0f, true));
        for (int i = 0; i < ticks; i++) {
            delta = i == 0 ? WALK : carry(delta, 0.6) + WALK;
            z += delta + excessPerTick;
            frames.add(new ReplayFrame((i + 1) * 50L, 0.5, 64.0, z, 0f, 0f, true));
        }
        return frames;
    }

    @Test
    @DisplayName("a recorded legit walk is explained with a negligible cumulative error")
    void legitWalk() {
        ReplayScenario scenario = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 6);
        List<SimulationResult> results = ReplayHarness.play(scenario, walk(0.0, 10));
        assertEquals(11, results.size());
        for (SimulationResult result : results.subList(1, results.size())) {
            assertEquals(SimulationResult.Status.COMPLETE, result.status(),
                    () -> "skip reason: " + result.skipReason());
            assertTrue(result.cumulativeOffset() < 1.0E-3,
                    () -> "cumulative offset " + result.cumulativeOffset());
        }
    }

    @Test
    @DisplayName("a recorded walk with a constant excess ends in a growing cumulative offset")
    void hackedWalk() {
        ReplayScenario scenario = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 6);
        List<SimulationResult> results = ReplayHarness.play(scenario, walk(0.005, 10));
        double last = results.get(results.size() - 1).cumulativeOffset();
        assertTrue(last > 0.03, () -> "cumulative offset must accumulate, was " + last);
    }
}
