package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.replay.scenarios.CheckReplay;
import dev.vaplus.anticheat.testutil.replay.scenarios.MovementScenarios;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared setup of the movement-check replay tests. */
final class MovementReplays {

    /** Platform radius: large enough that no scenario reaches an edge (edges are SKIPPED). */
    static final int RADIUS = 16;
    static final double START_Z = -12.5;

    private MovementReplays() {
    }

    /** Stone floor (layer 63), player standing at its top after spawn + settle. */
    static CheckReplay onStone() {
        return CheckReplay.create(new MovementChecksModule())
                .platform(VanillaBlocks.STONE, 63, RADIUS)
                .spawn(0.5, MovementScenarios.FLOOR_TOP, START_Z, 0f, true)
                .settle(true);
    }

    /** Stone floor, player spawned in mid-air at {@code y} (e.g. after a teleport) and settled there. */
    static CheckReplay inAir(double y) {
        return CheckReplay.create(new MovementChecksModule())
                .platform(VanillaBlocks.STONE, 63, RADIUS)
                .spawn(0.5, y, START_Z, 0f, false)
                .settle(false);
    }

    /** Spawn and settle are SKIPPED by design; every scenario frame after them must be COMPLETE. */
    static void assertAllFramesSimulated(CheckReplay replay, int frames) {
        List<SimulationResult.Status> statuses = replay.statuses();
        assertEquals(frames + 2, statuses.size());
        assertEquals(SimulationResult.Status.SKIPPED, statuses.get(0), "spawn");
        assertEquals(SimulationResult.Status.SKIPPED, statuses.get(1), "settle");
        assertEquals(frames, replay.completeCount(), () -> "statuses " + statuses);
    }

    static int violations(CheckReplay replay) {
        return replay.check(SpeedCheck.class).violationCount()
                + replay.check(FlyCheck.class).violationCount()
                + replay.check(NoFallCheck.class).violationCount();
    }
}
