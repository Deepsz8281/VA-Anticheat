package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VehicleCheck: steering, mount jump and paddle packets")
class VehicleInputTest {

    private static final int SELF = 7;
    private static final int BOAT = 50;
    private static final int HORSE = 51;

    private final TestContexts.Harness harness;
    private final PlayerData player;
    private final VehicleCheck check;

    VehicleInputTest() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        harness = TestContexts.builder().module(simulation).module(new MovementChecksModule(simulation)).build();
        player = harness.newPlayer();
        player.enableTickEndMode();
        check = player.checks().get(VehicleCheck.class);
        check.onJoin(SELF);
    }

    private void ride(int vehicle, int kind) {
        check.remember(vehicle, kind);
        check.onPassengers(vehicle, new int[]{SELF});
        player.vehicle().mount(vehicle);
    }

    private void keys(boolean forward, boolean left, boolean right) {
        player.pose().applyInput(forward, false, left, right, false, false, false, player.clientTicks());
    }

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    @Test
    @DisplayName("A/B: steering beyond 0.98 or without a vehicle flags")
    void steering() {
        ride(HORSE, VehicleCheck.KIND_HORSE);
        check.onSteerVehicle(0.98f, -0.98f);
        assertEquals(0, check.violationCount());
        check.onSteerVehicle(1.5f, 0f);
        assertTrue(flagged("steer-input"));
        player.vehicle().dismount();
        check.onSteerVehicle(0f, 0f);
        assertTrue(flagged("steer-without-vehicle"));
    }

    @Test
    @DisplayName("D: a mount jump on a horse passes; in a boat or on foot it flags")
    void mountJump() {
        ride(HORSE, VehicleCheck.KIND_HORSE);
        check.onMountJump();
        assertEquals(0, check.violationCount());
        ride(BOAT, VehicleCheck.KIND_BOAT);
        check.onMountJump();
        assertTrue(flagged("mount-jump vehicle=boat"));
    }

    @Test
    @DisplayName("E/F: paddles must match the keys and need a boat")
    void paddles() {
        ride(BOAT, VehicleCheck.KIND_BOAT);
        keys(true, false, false);
        check.onSteerBoat(true, true);
        keys(false, true, false);
        check.onSteerBoat(false, true);
        keys(false, false, true);
        check.onSteerBoat(true, false);
        keys(false, false, false);
        check.onSteerBoat(false, false);
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
        keys(false, false, false);
        check.onSteerBoat(true, true);
        assertTrue(flagged("paddle-mismatch"));
        ride(HORSE, VehicleCheck.KIND_HORSE);
        check.onSteerBoat(false, false);
        assertTrue(flagged("paddle-without-boat"));
    }
}
