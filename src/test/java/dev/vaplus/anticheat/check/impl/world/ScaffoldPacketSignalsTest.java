package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Scaffold: Grim InvalidPlaceFace, MultiPlace and DuplicateRotPlace")
class ScaffoldPacketSignalsTest {

    private final SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
    private final TestContexts.Harness harness = TestContexts.builder()
            .module(simulation)
            .module(new VisibilityChecksModule(simulation))
            .build();
    private final PlayerData player = harness.newPlayer();
    private final ScaffoldCheck check = player.checks().get(ScaffoldCheck.class);
    private float yaw;

    ScaffoldPacketSignalsTest() {
        player.enableTickEndMode();
        player.movement().applyPosition(0.5, 64.0, 0.5);
    }

    private boolean flagged(String signal) {
        return harness.alerts.alerts().stream()
                .anyMatch(a -> a.check() == check && a.details().startsWith("signal=" + signal));
    }

    private void place(int x, float cursorY) {
        // unknown blocks: only the packet-level signals judge
        player.interaction().assignPlace(x, 63, 5, BlockFace.UP, 0.5f, cursorY, 0.5f, InteractionHand.MAIN_HAND,
                0, false, 0L);
        player.checks().dispatchInteraction(player.interaction());
    }

    private void turn(float delta) {
        LookChange change = new LookChange();
        change.assign(yaw, 80f, yaw + delta, 80f, false, 0L);
        yaw += delta;
        player.checks().dispatchRotation(change);
    }

    @Test
    @DisplayName("invalid-face: face ids outside 0..5 flag at once")
    void invalidFace() {
        check.onPlaceFaceId(5);
        assertEquals(0, check.violationCount());
        check.onPlaceFaceId(6);
        assertTrue(flagged("invalid-face"));
    }

    @Test
    @DisplayName("multi: the same hit twice in a tick is vanilla, different blocks in one tick flag")
    void multi() {
        for (int i = 0; i < 3; i++) {
            place(0, 1f);
            place(0, 1f);
            player.onClientTick();
        }
        assertEquals(0, check.violationCount());
        for (int i = 0; i < 2; i++) {
            place(0, 1f);
            place(1, 1f);
            player.onClientTick();
        }
        assertTrue(flagged("multi"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("duplicate-rotation: varying turns pass, the exact same turn after every placement flags")
    void duplicateRotation() {
        float[] human = {12.3f, 9.8f, 14.1f, 11.7f, 10.2f, 13.6f};
        for (float delta : human) {
            place(0, 1f);
            turn(delta);
            player.onClientTick();
        }
        assertEquals(0, check.violationCount());
        for (int i = 0; i < 12; i++) {
            place(0, 1f);
            turn(i % 2 == 0 ? 45f : -45f);
            player.onClientTick();
        }
        assertTrue(flagged("duplicate-rotation"), harness.alerts.alerts()::toString);
    }
}
