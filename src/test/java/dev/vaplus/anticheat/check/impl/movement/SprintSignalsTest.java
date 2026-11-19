package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Sprint: hunger, blindness, walls, gliding and shallow water")
class SprintSignalsTest {

    private final SimulationModule simulation = new SimulationModule();
    private final Harness harness = TestContexts.builder()
            .module(simulation)
            .check(SprintCheck.DESCRIPTOR, p -> new SprintCheck(p, simulation.worldMirrorKey()))
            .build();
    private final PlayerData player = harness.newPlayer();
    private final SprintCheck check = player.checks().get(SprintCheck.class);

    SprintSignalsTest() {
        player.enableTickEndMode();
        player.movement().applyPosition(0.5, 64.0, 0.5);
        player.movement().applyPosition(0.5, 64.0, 0.5);
        WorldMirror world = player.component(simulation.worldMirrorKey());
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.setBlock(new BlockPos(x, 63, z), VanillaBlocks.STONE);
                for (int y = 64; y <= 66; y++) {
                    world.setBlock(new BlockPos(x, y, z), VanillaBlocks.AIR);
                }
            }
        }
        forward();
    }

    private void forward() {
        player.pose().applyInput(true, false, false, false, false, false, true, player.clientTicks());
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            player.onClientTick();
        }
    }

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    /** Sends a food value and lets the client acknowledge both markers. */
    private void food(int value) {
        List<Runnable> tasks = new ArrayList<>();
        check.onFood(value, tasks);
        tasks.forEach(Runnable::run);
        for (int id : harness.lastTransport().markerIds()) {
            player.acks().onPong(id);
        }
    }

    @Test
    @DisplayName("hunger: sprinting with 6 food flags once acknowledged; 7 food or flight never")
    void hunger() {
        player.pose().sprinting(true);
        food(7);
        ticks(10);
        player.abilities().applyAbilities(false, true, false, false, 0.05f, 0.1f);
        food(6);
        ticks(10);
        assertEquals(0, check.violationCount());
        player.abilities().applyAbilities(false, false, false, false, 0.05f, 0.1f);
        ticks(12);
        assertTrue(flagged("hunger"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("blindness: starting to sprint while blind flags; already sprinting does not")
    void blindness() {
        player.pose().sprinting(true);
        player.onClientTick();
        player.effects().apply(TrackedEffect.BLINDNESS, 0);
        ticks(5);
        assertEquals(0, check.violationCount());
        for (int i = 0; i < 3; i++) {
            player.pose().sprinting(false);
            player.onClientTick();
            player.pose().sprinting(true);
            player.onClientTick();
        }
        assertTrue(flagged("blindness"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("wall: still sprinting while standing head-on at a wall flags; sliding along it does not")
    void wall() {
        player.pose().sprinting(true);
        player.movement().applyFlags(true, true);
        for (int i = 0; i < 6; i++) {
            player.movement().applyPosition(0.5 + 0.2 * (i + 1), 64.0, 0.5);
            player.onClientTick();
        }
        assertEquals(0, check.violationCount());
        for (int i = 0; i < 12; i++) {
            player.movement().applyPosition(1.7, 64.0, 0.5);
            player.movement().applyFlags(true, true);
            player.onClientTick();
        }
        assertTrue(flagged("wall"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("gliding: sprinting while gliding flags for 1.21.4 clients")
    void gliding() {
        player.pose().sprinting(true);
        player.pose().gliding(true);
        ticks(12);
        assertTrue(flagged("gliding"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("water: sprinting with the feet in water and the eyes above flags; swimming does not")
    void water() {
        player.component(simulation.worldMirrorKey()).setBlock(new BlockPos(0, 64, 0), VanillaBlocks.WATER_BLOCK);
        player.pose().sprinting(true);
        player.pose().swimming(true);
        ticks(10);
        assertEquals(0, check.violationCount());
        player.pose().swimming(false);
        ticks(12);
        assertTrue(flagged("water"), harness.alerts.alerts()::toString);
    }
}
