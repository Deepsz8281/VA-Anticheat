package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PassengerTracker (own mount / dismount)")
class PassengerTrackerTest {

    private static final long VEHICLE_MASK = ExemptionCause.mask(ExemptionCause.VEHICLE);

    @Test
    @DisplayName("module installs the tracker")
    void installed() {
        TestContexts.Harness harness = TestContexts.builder().module(new SimulationModule()).build();
        assertTrue(harness.context.setup().outboundTrackers().stream().anyMatch(t -> t instanceof PassengerTracker));
    }

    @Test
    @DisplayName("mount applies at send with an exemption; dismount only after the second marker")
    void mountAndDismount() {
        SimulationModule simulation = new SimulationModule();
        TestContexts.Harness harness = TestContexts.builder().module(simulation).build();
        PlayerData player = harness.newPlayer();
        player.component(simulation.velocityKey()).selfEntityId(7);
        PassengerTracker tracker = new PassengerTracker(simulation.velocityKey());

        tracker.onPassengers(player, new ArrayList<>(), 50, new int[]{3, 7});
        assertTrue(player.vehicle().inVehicle());
        assertEquals(50, player.vehicle().vehicleEntityId());
        assertTrue(player.exemptions().anyActive(VEHICLE_MASK));

        // other vehicles do not dismount us
        List<Runnable> tasks = new ArrayList<>();
        tracker.onPassengers(player, tasks, 51, new int[]{});
        assertTrue(tasks.isEmpty());

        tasks = new ArrayList<>();
        tracker.onPassengers(player, tasks, 50, new int[]{3});
        tasks.forEach(Runnable::run);
        assertTrue(player.vehicle().inVehicle(), "still riding until the client acknowledged the dismount");
        acknowledgeAll(harness, player);
        assertFalse(player.vehicle().inVehicle());
        assertEquals(0, player.vehicle().ticksSinceDismount());
    }

    @Test
    @DisplayName("unknown own entity id: nothing is tracked")
    void unknownSelf() {
        SimulationModule simulation = new SimulationModule();
        TestContexts.Harness harness = TestContexts.builder().module(simulation).build();
        PlayerData player = harness.newPlayer();
        assertEquals(VelocityState.UNKNOWN_ENTITY_ID, player.component(simulation.velocityKey()).selfEntityId());
        new PassengerTracker(simulation.velocityKey()).onPassengers(player, new ArrayList<>(), 50, new int[]{7});
        assertFalse(player.vehicle().inVehicle());
    }

    private static void acknowledgeAll(TestContexts.Harness harness, PlayerData player) {
        for (int id : harness.lastTransport().markerIds()) {
            player.acks().onPong(id);
        }
    }
}
