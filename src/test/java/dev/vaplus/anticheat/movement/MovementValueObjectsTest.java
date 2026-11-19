package dev.vaplus.anticheat.movement;

import dev.vaplus.anticheat.simulation.SimulationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Movement value objects (MovementUpdate, LookChange, SimulationResult)")
class MovementValueObjectsTest {

    @Test
    @DisplayName("MovementUpdate derives deltas and horizontal distance")
    void movementUpdateDeltas() {
        MovementUpdate update = new MovementUpdate(new SimulationResult());
        update.assign(1, 2, 3, 4, 6, 7, true, false, true, true, false, false, 3, 99L);
        assertEquals(3.0, update.deltaX());
        assertEquals(4.0, update.deltaY());
        assertEquals(4.0, update.deltaZ());
        assertEquals(5.0, update.horizontalDistance(), 1e-12);
        assertTrue(update.fromGround());
        assertFalse(update.toGround());
        assertTrue(update.horizontalCollision());
        assertEquals(3, update.elapsedTicks());
        assertEquals(99L, update.timestampNanos());
    }

    @Test
    @DisplayName("assign clears the teleport marker of the previous packet")
    void teleportMarkerReset() {
        MovementUpdate update = new MovementUpdate(new SimulationResult());
        update.assign(0, 0, 0, 0, 0, 0, true, true, false, true, false, false, 1, 0L);
        assertFalse(update.teleport());
        update.markTeleport();
        assertTrue(update.teleport());
        update.assign(0, 0, 0, 0, 0, 0, true, true, false, true, false, false, 1, 0L);
        assertFalse(update.teleport());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "90,90", "179,179", "180,-180", "-180,-180", "181,-179", "-181,179", "360,0", "-360,0",
            "540,-180", "725,5", "-725,-5", "3600.5,0.5"})
    @DisplayName("LookChange.wrapDegrees maps into [-180, 180)")
    void wrapDegrees(float input, float expected) {
        assertEquals(expected, LookChange.wrapDegrees(input), 1e-3);
    }

    @Test
    @DisplayName("LookChange exposes raw and wrapped yaw deltas")
    void rotationDeltas() {
        LookChange update = new LookChange();
        update.assign(350f, 10f, 370f, -5f, false, 7L);
        assertEquals(20f, update.deltaYaw());
        assertEquals(20f, update.wrappedDeltaYaw());
        assertEquals(-15f, update.deltaPitch());
        update.assign(10f, 0f, 350f, 0f, true, 8L);
        assertEquals(340f, update.deltaYaw());
        assertEquals(-20f, update.wrappedDeltaYaw());
        assertTrue(update.teleport());
        assertEquals(8L, update.timestampNanos());
    }

    @Test
    @DisplayName("SimulationResult.complete computes total and horizontal offsets")
    void simulationComplete() {
        SimulationResult result = new SimulationResult();
        assertEquals(SimulationResult.Status.NOT_RUN, result.status());
        assertFalse(result.complete());
        result.complete(0.1, 0.2, 0.3, 3, 4, 12, true, 2, 5, 0.01);
        assertEquals(SimulationResult.Status.COMPLETE, result.status());
        assertTrue(result.complete());
        assertEquals(13.0, result.offset(), 1e-12);
        assertEquals(Math.sqrt(9 + 144), result.horizontalOffset(), 1e-12);
        assertEquals(4.0, result.verticalOffset());
        assertEquals(2, result.ticksSimulated());
        assertEquals(5, result.candidateIndex());
        assertEquals(0.01, result.uncertainty());
        assertTrue(result.predictedOnGround());
    }

    @Test
    @DisplayName("skip stores the reason and clears previous results; reset returns to NOT_RUN with candidate -1")
    void simulationSkipAndReset() {
        SimulationResult result = new SimulationResult();
        result.complete(1, 1, 1, 1, 1, 1, true, 1, 3, 0);
        result.skip("teleport");
        assertEquals(SimulationResult.Status.SKIPPED, result.status());
        assertEquals("teleport", result.skipReason());
        assertEquals(0.0, result.offset());
        assertEquals(-1, result.candidateIndex());
        result.skip(null);
        assertEquals("", result.skipReason());
        result.reset();
        assertEquals(SimulationResult.Status.NOT_RUN, result.status());
        assertEquals(-1, result.candidateIndex());
        assertEquals("", result.skipReason());
    }

    @Test
    @DisplayName("a fresh SimulationResult reports candidate index -1")
    void freshCandidateIndex() {
        assertEquals(-1, new SimulationResult().candidateIndex());
    }
}
