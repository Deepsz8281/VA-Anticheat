package dev.vaplus.anticheat.simulation;

import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.carry;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.fall;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.groundAccel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay tests of the movement engine. Expected deltas come from {@code VanillaMotion} (MCPK / Wiki
 * formulas written out independently); the engine must explain them with an offset below
 * {@link #EPS} (float rounding of the vanilla constants only).
 */
@DisplayName("PredictionSimulator (replayed through MovementProcessor.process)")
class PredictionSimulatorTest {

    private static final double EPS = 1.0E-6;
    private static final double JUMP = 0.41999998688697815;
    private static final double WALK = groundAccel(0.1, 0.98, 1.0, 0.6);

    private static ReplayScenario onStone() {
        ReplayScenario scenario = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
        scenario.spawn(0.5, 64.0, 0.5, 0f);
        return scenario;
    }

    private static void assertExplained(SimulationResult result) {
        assertEquals(SimulationResult.Status.COMPLETE, result.status(), () -> "skip reason: " + result.skipReason());
        assertTrue(result.offset() < EPS, () -> "offset " + result.offset());
    }

    @Test
    @DisplayName("the module installs the engine and registers its components and the velocity tracker")
    void wiring() {
        ReplayScenario scenario = ReplayScenario.create();
        assertInstanceOf(PredictionSimulator.class, scenario.harness.context.simulator());
        assertEquals(scenario.simulation.simulator(), scenario.harness.context.simulator());
        assertTrue(scenario.harness.context.setup().outboundTrackers().stream()
                .anyMatch(t -> t instanceof dev.vaplus.anticheat.pipeline.VelocityTracker));
        assertEquals(0, scenario.world().chunkCount());
    }

    @Nested
    @DisplayName("ground movement")
    class Ground {

        @Test
        @DisplayName("first position and the first idle tick are SKIPPED, then standing still is explained")
        void standingStill() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            assertEquals("first-position", s.spawn(0.5, 64.0, 0.5, 0f).skipReason());
            assertEquals(PredictionSimulator.SKIP_NO_SEED, s.settle().skipReason());
            assertTrue(s.prediction().seeded(), "an idle tick in simple terrain seeds the engine");
            assertEquals(fall(0.0, 0.08), s.prediction().seedY(), EPS, "on-ground seed: (0 - 0.08) * 0.98");
            for (int i = 0; i < 3; i++) {
                SimulationResult result = s.move(0, 0, 0);
                assertExplained(result);
                assertTrue(result.predictedOnGround());
                assertEquals(1, result.ticksSimulated());
                assertEquals(0.0, result.predictedY(), EPS, "the floor stops the -0.0784 motion");
            }
        }

        @Test
        @DisplayName("walking forward on stone follows MCPK: 0.098, then 0.098 + 0.098 * 0.6 * 0.91")
        void walkingOnStone() {
            ReplayScenario s = onStone();
            s.settle();
            SimulationResult first = s.move(0, 0, WALK);
            assertExplained(first);
            assertEquals(PredictionSimulator.candidateIndex(0, 0, 0, 1, 0), first.candidateIndex(),
                    "forward, no strafe, no sprint, no jump");
            double second = carry(WALK, 0.6) + WALK;
            assertExplained(s.move(0, 0, second));
            double third = carry(second, 0.6) + WALK;
            assertExplained(s.move(0, 0, third));
        }

        @Test
        @DisplayName("farmland (0.9375) and carpets (0.0625) are modelled as floors")
        void partialFloors() {
            ReplayScenario farmland = ReplayScenario.create().platform(VanillaBlocks.FARMLAND, 63, 4);
            farmland.spawn(0.5, 63.9375, 0.5, 0f);
            farmland.settle();
            assertExplained(farmland.move(0, 0, WALK));

            ReplayScenario carpet = ReplayScenario.create().platform(VanillaBlocks.WHITE_CARPET, 63, 4);
            carpet.spawn(0.5, 63.0625, 0.5, 0f);
            carpet.settle();
            assertExplained(carpet.move(0, 0, WALK));
        }

        @Test
        @DisplayName("a small constant excess accumulates in the position chain; a legit walk stays at zero")
        void positionChainAccumulates() {
            ReplayScenario legit = onStone();
            legit.settle();
            double legitDelta = 0.0;
            for (int i = 0; i < 10; i++) {
                legitDelta = i == 0 ? WALK : carry(legitDelta, 0.6) + WALK;
                SimulationResult result = legit.move(0, 0, legitDelta);
                assertExplained(result);
                assertTrue(result.cumulativeOffset() < 1.0E-3,
                        () -> "legit cumulative offset " + result.cumulativeOffset());
            }

            ReplayScenario hack = onStone();
            hack.settle();
            double hackDelta = 0.0;
            double last = 0.0;
            for (int i = 0; i < 10; i++) {
                hackDelta = i == 0 ? WALK : carry(hackDelta, 0.6) + WALK;
                SimulationResult result = hack.move(0, 0, hackDelta + 0.005);
                assertEquals(SimulationResult.Status.COMPLETE, result.status(),
                        () -> "skip reason: " + result.skipReason());
                last = result.cumulativeOffset();
            }
            double accumulated = last;
            assertTrue(accumulated > 0.03, () -> "cumulative offset must accumulate, was " + accumulated);
        }

        @Test
        @DisplayName("yaw rotates the input: facing west (yaw 90) walks towards -x")
        void yawRotation() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            s.spawn(0.5, 64.0, 0.5, 90f);
            s.settle();
            assertExplained(s.move(-WALK, 0, 0));
        }

        @Test
        @DisplayName("sprint-jump: 0.42 up and 0.2 + 0.1274 forward, then airborne 0.026 acceleration")
        void sprintJump() {
            ReplayScenario s = onStone();
            s.settle();
            double dz1 = 0.2 + groundAccel(0.1, 0.98 * 1.3, 1.0, 0.6);
            SimulationResult first = s.move(0, JUMP, dz1);
            assertExplained(first);
            assertEquals(PredictionSimulator.candidateIndex(0, 1, 1, 1, 0), first.candidateIndex());
            assertFalse(first.predictedOnGround());

            double dz2 = carry(dz1, 0.6) + 0.02 * 1.3 * 0.98;
            double dy2 = fall(JUMP, 0.08);
            SimulationResult second = s.move(0, dy2, dz2);
            assertExplained(second);
        }

        @Test
        @DisplayName("Speed II multiplies the ground acceleration by 1.4; without the effect that is an offset")
        void speedEffect() {
            ReplayScenario s = onStone();
            s.player.effects().apply(TrackedEffect.SPEED, 1);
            s.settle();
            double fast = groundAccel(0.1, 0.98, 1.4, 0.6);
            assertExplained(s.move(0, 0, fast));

            ReplayScenario plain = onStone();
            plain.settle();
            SimulationResult cheat = plain.move(0, 0, fast);
            assertEquals(SimulationResult.Status.COMPLETE, cheat.status());
            double sprint = groundAccel(0.1, 0.98 * 1.3, 1.0, 0.6);
            assertEquals(fast - sprint, cheat.offset(), 1.0E-5, "the sprint candidate is the closest");
        }

        @Test
        @DisplayName("ice (slipperiness 0.98): tiny acceleration, long momentum")
        void iceFriction() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.ICE, 63, 4);
            s.spawn(0.5, 64.0, 0.5, 0f);
            s.settle();
            double first = groundAccel(0.1, 0.98, 1.0, 0.98);
            assertExplained(s.move(0, 0, first));
            double second = carry(first, 0.98) + first;
            assertExplained(s.move(0, 0, second));
            double third = carry(second, 0.98) + first;
            assertExplained(s.move(0, 0, third));
        }

        @Test
        @DisplayName("walking while using an item (input x 0.2) is explained by the item-use candidate")
        void itemUseSlowdown() {
            ReplayScenario s = onStone();
            s.settle();
            SimulationResult result = s.move(0, 0, WALK * 0.2);
            assertExplained(result);
            assertEquals(PredictionSimulator.candidateIndex(0, 0, 0, 1, 1, 0), result.candidateIndex());
        }

        @Test
        @DisplayName("moving 0.5 blocks in one tick from rest is COMPLETE with a large offset (speed hack)")
        void speedHack() {
            ReplayScenario s = onStone();
            s.settle();
            SimulationResult result = s.move(0, 0, 0.5);
            assertEquals(SimulationResult.Status.COMPLETE, result.status());
            assertTrue(result.offset() > 0.15, () -> "offset " + result.offset());
            assertTrue(result.horizontalOffset() > 0.15);
        }

        @Test
        @DisplayName("tick-end mode: one packet covering two ticks is simulated as two ticks")
        void multiTick() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4).tickEndMode();
            s.spawn(0.5, 64.0, 0.5, 0f);
            s.settle();
            double total = WALK + (carry(WALK, 0.6) + WALK);
            SimulationResult result = s.moveTicks(2, 0, 0, total);
            assertExplained(result);
            assertEquals(2, result.ticksSimulated());
        }
    }

    @Nested
    @DisplayName("vertical movement")
    class Vertical {

        @Test
        @DisplayName("free fall follows (vy - 0.08) * 0.98 and is not predicted on ground")
        void falling() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            s.spawn(0.5, 72.0, 0.5, 0f);
            s.settle();
            double vy = fall(0.0, 0.08);
            for (int i = 0; i < 5; i++) {
                SimulationResult result = s.move(0, vy, 0);
                assertExplained(result);
                assertFalse(result.predictedOnGround());
                vy = fall(vy, 0.08);
            }
        }

        @Test
        @DisplayName("falling onto the floor clamps to its top and predicts ground contact")
        void landing() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            s.spawn(0.5, 65.0, 0.5, 0f);
            s.settle();
            double vy = fall(0.0, 0.08);
            boolean landed = false;
            for (int i = 0; i < 10 && !landed; i++) {
                double target = s.y() + vy;
                double dy = vy;
                if (target < 64.0) {
                    dy = 64.0 - s.y();
                    landed = true;
                }
                SimulationResult result = s.move(0, dy, 0);
                assertExplained(result);
                assertEquals(landed, result.predictedOnGround());
                vy = fall(vy, 0.08);
            }
            assertTrue(landed);
            SimulationResult standing = s.move(0, 0, 0);
            assertExplained(standing);
            assertTrue(standing.predictedOnGround());
        }

        @Test
        @DisplayName("Slow Falling caps gravity at 0.01 (Wiki Entity motion table)")
        void slowFalling() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            s.player.effects().apply(TrackedEffect.SLOW_FALLING, 0);
            s.spawn(0.5, 72.0, 0.5, 0f);
            s.settle();
            double vy = fall(0.0, 0.01);
            for (int i = 0; i < 4; i++) {
                assertExplained(s.move(0, vy, 0));
                vy = fall(vy, 0.01);
            }
        }

        @Test
        @DisplayName("falling at normal speed under Slow Falling is a negative vertical offset")
        void slowFallingIgnored() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            s.player.effects().apply(TrackedEffect.SLOW_FALLING, 0);
            s.spawn(0.5, 72.0, 0.5, 0f);
            s.settle();
            SimulationResult result = s.move(0, fall(0.0, 0.08), 0);
            assertEquals(SimulationResult.Status.COMPLETE, result.status());
            assertEquals(fall(0.0, 0.08) - fall(0.0, 0.01), result.verticalOffset(), EPS);
        }

        @Test
        @DisplayName("hovering in the air (dy = 0) is a positive vertical offset")
        void hover() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            s.spawn(0.5, 72.0, 0.5, 0f);
            s.settle();
            SimulationResult result = s.move(0, 0, 0);
            assertEquals(SimulationResult.Status.COMPLETE, result.status());
            assertEquals(-fall(0.0, 0.08), result.verticalOffset(), EPS);
        }
    }

    @Nested
    @DisplayName("server velocity (VelocityState)")
    class Knockback {

        private long knock(ReplayScenario s, VelocityState.Kind kind, double x, double y, double z,
                           boolean firstAck, boolean secondAck) {
            VelocityState velocity = s.velocity();
            long id = velocity.register(kind, x, y, z, s.player.movement().worldEpoch());
            if (firstAck) {
                velocity.markFirstAck(id);
            }
            if (secondAck) {
                velocity.markSecondAck(id, s.player.clientTicks());
            }
            return id;
        }

        @Test
        @DisplayName("an acknowledged entity velocity replaces the motion and is consumed")
        void setVelocity() {
            ReplayScenario s = onStone();
            s.settle();
            knock(s, VelocityState.Kind.SET, 0.3, 0.36, 0.0, true, true);
            SimulationResult result = s.move(0.3, 0.36, 0.0);
            assertExplained(result);
            assertEquals(PredictionSimulator.candidateIndex(1, 0, 0, 0, 0), result.candidateIndex());
            assertEquals(0, s.velocity().size());
            assertEquals(1, s.velocity().appliedByEngine());
        }

        @Test
        @DisplayName("explosion knockback is added to the current motion")
        void explosionAdds() {
            ReplayScenario s = onStone();
            s.settle();
            knock(s, VelocityState.Kind.ADD, 0.5, 0.2, 0.0, true, true);
            // start motion = seed (0, -0.0784, 0) + (0.5, 0.2, 0)
            assertExplained(s.move(0.5, 0.2 + fall(0.0, 0.08), 0.0));
        }

        @Test
        @DisplayName("in the ack window both outcomes are explained; an unapplied window entry stays")
        void windowBothWays() {
            ReplayScenario s = onStone();
            s.settle();
            long id = knock(s, VelocityState.Kind.SET, 0.3, 0.36, 0.0, true, false);
            SimulationResult without = s.move(0, 0, 0);
            assertExplained(without);
            assertEquals(1, s.velocity().size(), "not applied yet: kept for the next movement");

            s.velocity().markSecondAck(id, s.player.clientTicks());
            assertExplained(s.move(0.3, 0.36, 0.0));
            assertEquals(0, s.velocity().size());
        }

        @Test
        @DisplayName("a velocity whose first marker is not acknowledged is not offered")
        void sentNotOffered() {
            ReplayScenario s = onStone();
            s.settle();
            knock(s, VelocityState.Kind.SET, 0.3, 0.36, 0.0, false, false);
            SimulationResult result = s.move(0.3, 0.36, 0.0);
            assertEquals(SimulationResult.Status.COMPLETE, result.status());
            assertTrue(result.offset() > 0.15, "knockback before the marker was acknowledged is not explained");
        }
    }

    @Nested
    @DisplayName("SKIPPED instead of a false offset")
    class Skips {

        @Test
        @DisplayName("no world data (unloaded chunk) is SKIPPED, never COMPLETE")
        void unloadedChunk() {
            ReplayScenario s = ReplayScenario.create();
            s.spawn(0.5, 64.0, 0.5, 0f);
            assertEquals(PredictionSimulator.SKIP_NO_SEED, s.settle().skipReason());
            SimulationResult result = s.move(0, 0, 5.0);
            assertEquals(SimulationResult.Status.SKIPPED, result.status());
            assertEquals(PredictionSimulator.SKIP_WORLD_UNKNOWN, result.skipReason());

            ReplayScenario partial = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
            partial.spawn(0.5, 64.0, 0.5, 0f);
            partial.settle();
            partial.world().clearChunk(0, 0);
            SimulationResult cleared = partial.move(0, 0, 0);
            assertEquals(PredictionSimulator.SKIP_WORLD_UNKNOWN, cleared.skipReason());
        }

        @Test
        @DisplayName("standing at an edge (mixed floor) is SKIPPED")
        void edge() {
            ReplayScenario s = ReplayScenario.create()
                    .fill(VanillaBlocks.STONE, -4, 0, 63, 63, -4, 4)
                    .fill(VanillaBlocks.AIR, 1, 4, 55, 63, -4, 4)
                    .fill(VanillaBlocks.AIR, -4, 4, 64, 79, -4, 4);
            s.spawn(0.9, 64.0, 0.5, 0f);
            s.settle();
            assertEquals(PredictionSimulator.SKIP_EDGE, s.move(0, 0, 0).skipReason());
        }

        @Test
        @DisplayName("a wall next to the player is SKIPPED (no wall collision model)")
        void wall() {
            ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4)
                    .fill(VanillaBlocks.STONE, 1, 1, 64, 65, -4, 4);
            s.spawn(0.5, 64.0, 0.5, 0f);
            s.settle();
            assertEquals(PredictionSimulator.SKIP_COMPLEX, s.move(0, 0, 0).skipReason());
        }

        @Test
        @DisplayName("flight, creative, vehicles, elytra, levitation and unmodelled impulses are SKIPPED")
        void gates() {
            ReplayScenario flying = onStone();
            flying.settle();
            flying.player.abilities().applyClientFlying(true);
            assertEquals(PredictionSimulator.SKIP_FLYING, flying.move(0, 0.5, 0).skipReason());

            ReplayScenario vehicle = onStone();
            vehicle.settle();
            vehicle.player.vehicle().mount(42);
            assertEquals(PredictionSimulator.SKIP_VEHICLE, vehicle.move(0, 0, 1).skipReason());

            ReplayScenario elytra = onStone();
            elytra.settle();
            elytra.player.pose().gliding(true);
            assertEquals(PredictionSimulator.SKIP_ELYTRA, elytra.move(0, 0, 1).skipReason());

            ReplayScenario levitation = onStone();
            levitation.settle();
            levitation.player.effects().apply(TrackedEffect.LEVITATION, 0);
            assertEquals(PredictionSimulator.SKIP_LEVITATION, levitation.move(0, 0.1, 0).skipReason());

            ReplayScenario riptide = onStone();
            riptide.settle();
            riptide.player.exemptions().grant(ExemptionCause.RIPTIDE, 20);
            assertEquals(PredictionSimulator.SKIP_UNMODELLED_IMPULSE, riptide.move(0, 1, 0).skipReason());

            ReplayScenario creative = onStone();
            creative.settle();
            creative.player.abilities().applyGameMode(
                    com.github.retrooper.packetevents.protocol.player.GameMode.CREATIVE);
            assertEquals(PredictionSimulator.SKIP_GAMEMODE, creative.move(0, 0, 1).skipReason());

            ReplayScenario scaled = onStone();
            scaled.settle();
            scaled.player.attributes().scale(2.0);
            assertEquals(PredictionSimulator.SKIP_ATTRIBUTES, scaled.move(0, 0, 0).skipReason());
        }

        @Test
        @DisplayName("a skipped packet invalidates the seed; the next simple single tick reseeds")
        void reseedAfterGate() {
            ReplayScenario s = onStone();
            s.settle();
            s.player.abilities().applyClientFlying(true);
            assertEquals(PredictionSimulator.SKIP_FLYING, s.move(0, 0, 0).skipReason());
            s.player.abilities().applyClientFlying(false);
            SimulationResult next = s.move(0, 0, 0);
            // The previous packet was a seen single tick, so the motion-history fallback seeds it.
            assertEquals(SimulationResult.Status.COMPLETE, next.status(), () -> next.skipReason());
            assertTrue(next.offset() < EPS);
            assertTrue(next.uncertainty() >= PredictionSimulator.HISTORY_SEED_UNCERTAINTY,
                    "history seeds report their extra uncertainty");
        }
    }
}
