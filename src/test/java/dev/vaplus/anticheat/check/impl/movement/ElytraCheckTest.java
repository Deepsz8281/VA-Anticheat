package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ElytraCheck (hover / endless climb while gliding)")
class ElytraCheckTest {

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final SimulationModule simulation;
        final ElytraCheck check;
        double x = 0.5;
        double y = 100.0;
        double z = 0.5;

        /** Known air in x -10..200, y 60..140, z -3..3. */
        Fixture() {
            simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new MovementChecksModule(simulation)).build();
            player = harness.newPlayer();
            WorldMirror world = player.component(simulation.worldMirrorKey());
            for (int bx = -10; bx <= 200; bx++) {
                for (int bz = -3; bz <= 3; bz++) {
                    for (int by = 60; by <= 140; by++) {
                        world.setBlock(new BlockPos(bx, by, bz), VanillaBlocks.AIR);
                    }
                }
            }
            player.pose().gliding(true);
            check = player.checks().get(ElytraCheck.class);
        }

        void move(double dx, double dy) {
            player.onClientTick();
            MovementUpdate update = player.movementUpdate();
            update.assign(x, y, z, x + dx, y + dy, z, false, false, false, true, true, false, 1,
                    harness.time.nanoTime());
            x += dx;
            y += dy;
            player.checks().dispatchPosition(update);
        }

        void rocket() {
            player.interaction().assignUse(InteractionHand.MAIN_HAND, 0, 0L);
            player.checks().dispatchInteraction(player.interaction());
        }
    }

    @Test
    @DisplayName("legit: a long descending glide and a pull-up of 60 ticks never flag")
    void legitGlide() {
        Fixture f = new Fixture();
        for (int i = 0; i < 150; i++) {
            f.move(0.5, -0.05);
        }
        for (int i = 0; i < 60; i++) {
            f.move(0.4, 0.2 - i * 0.003);
        }
        for (int i = 0; i < 40; i++) {
            f.move(0.3, -0.1);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("legit: rocket-boosted level flight is not judged")
    void rocketFlight() {
        Fixture f = new Fixture();
        for (int burst = 0; burst < 4; burst++) {
            f.rocket();
            for (int i = 0; i < 50; i++) {
                f.move(1.0, 0.05);
            }
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("cheat: hovering in place while gliding flags")
    void hover() {
        Fixture f = new Fixture();
        for (int i = 0; i < 50; i++) {
            f.move(0.0, 0.0);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: endless level flight without rockets flags")
    void endlessLevelFlight() {
        Fixture f = new Fixture();
        for (int i = 0; i < 230; i++) {
            f.move(0.8, 0.0);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("not judged: not gliding (server flag), server velocity, near blocks")
    void notJudged() {
        Fixture walking = new Fixture();
        walking.player.pose().gliding(false);
        for (int i = 0; i < 50; i++) {
            walking.move(0.0, 0.0);
        }
        assertEquals(0, walking.check.violationCount());

        Fixture knocked = new Fixture();
        knocked.player.component(knocked.simulation.velocityKey())
                .register(VelocityState.Kind.SET, 0, 0.5, 0, 0);
        for (int i = 0; i < 50; i++) {
            knocked.move(0.0, 0.0);
        }
        assertEquals(0, knocked.check.violationCount());

        Fixture ceiling = new Fixture();
        WorldMirror world = ceiling.player.component(ceiling.simulation.worldMirrorKey());
        world.setBlock(new BlockPos(0, 102, 0), VanillaBlocks.STONE);
        for (int i = 0; i < 50; i++) {
            ceiling.move(0.0, 0.0);
        }
        assertEquals(0, ceiling.check.violationCount());
    }
}
