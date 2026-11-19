package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.impl.world.EntityDimensions;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection of Meteor-style combat modules: KillAura / CrystalAura on mobs and crystals, rotating
 * auras and aim assists (aim), Multitask / GuiMove / NoSwing attacks (killaura signals).
 */
@DisplayName("Meteor-style combat cheats (mob targets, aim, killaura signals)")
class MeteorCombatTest {

    private static final int TARGET_ID = 31;
    private static final double FEET_Y = 64.0;
    /** Mouse grid at 50% sensitivity: (0.5 * 0.6 + 0.2)^3 * 8 * 0.15. */
    private static final float GRID = 0.15f;

    private static final class Fixture {
        final Harness harness;
        final PlayerData player;
        final NearbyPlayers tracked;
        float yaw;
        float pitch;

        Fixture() {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
            harness = TestContexts.builder()
                    .module(simulation)
                    .module(visibility)
                    .module(new CombatChecksModule(simulation, visibility))
                    .build();
            player = harness.newPlayer();
            player.movement().applyPosition(0.5, FEET_Y, 0.5);
            player.movement().applyRotation(0f, 0f);
            player.onClientTick();
            tracked = player.component(visibility.nearbyKey());
        }

        <T extends dev.vaplus.anticheat.check.Check> T check(Class<T> type) {
            T check = player.checks().get(type);
            assertNotNull(check, type.getSimpleName());
            return check;
        }

        void attack(int entityId) {
            EntityAttack attack = new EntityAttack();
            attack.assign(entityId, EntityAttack.Kind.ATTACK, false, 0, 0, 0, System.nanoTime());
            player.checks().dispatchAttack(attack);
        }

        void rotateTo(float toYaw, float toPitch) {
            LookChange change = new LookChange();
            change.assign(yaw, pitch, toYaw, toPitch, false, 0L);
            yaw = toYaw;
            pitch = toPitch;
            player.movement().applyRotation(toYaw, toPitch);
            player.checks().dispatchRotation(change);
        }

        /** Vanilla mouse input: whole mouse counts times the sensitivity grid, accumulated in float. */
        void mouse(int countsX, int countsY) {
            float toYaw = yaw + (float) countsX * GRID;
            float toPitch = Math.max(-90f, Math.min(90f, pitch + (float) countsY * GRID));
            rotateTo(toYaw, toPitch);
        }

        void tick() {
            player.onClientTick();
        }

        /** Every marker sent so far is acknowledged by the client. */
        void acknowledgeAll() {
            RecordingTransport transport = harness.lastTransport();
            for (int id : transport.markerIds()) {
                player.acks().onPong(id);
            }
        }
    }

    private static int nonZero(Random random, int bound) {
        int value = 0;
        while (value == 0) {
            value = random.nextInt(2 * bound + 1) - bound;
        }
        return value;
    }

    /** Legit mouse movement without fighting, so the check learns the grid. */
    private static void learn(Fixture fixture, Random random, int ticks) {
        for (int i = 0; i < ticks; i++) {
            fixture.mouse(nonZero(random, 25), random.nextInt(11) - 5);
            fixture.tick();
        }
    }

    @Nested
    @DisplayName("mob and crystal targets")
    class MobTargets {

        @Test
        @DisplayName("hitbox table: mobs and crystals are known, items and projectiles are not")
        void dimensions() {
            assertEquals(0.6, EntityDimensions.of("zombie")[0]);
            assertEquals(1.95, EntityDimensions.of("zombie")[1]);
            assertEquals(2.0, EntityDimensions.of("end_crystal")[0]);
            assertEquals(1.4, EntityDimensions.of("spider")[0]);
            assertNull(EntityDimensions.of("item"));
            assertNull(EntityDimensions.of("arrow"));
            assertNull(EntityDimensions.of("slime"), "size-variable entities are not judged");
        }

        @Test
        @DisplayName("killaura flags attacks on a zombie behind the attacker")
        void killauraOnMob() {
            Fixture fixture = new Fixture();
            double[] zombie = EntityDimensions.of("zombie");
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 3.5, false, zombie[0], zombie[1]);
            fixture.attack(TARGET_ID);
            assertEquals(0, fixture.check(KillauraCheck.class).violationCount(), "zombie straight ahead");

            fixture.player.movement().applyRotation(180f, 0f);
            for (int i = 0; i < 5; i++) {
                fixture.attack(TARGET_ID);
            }
            assertEquals(1, fixture.check(KillauraCheck.class).violationCount(),
                    () -> "alerts: " + fixture.harness.alerts.alerts());
        }

        @Test
        @DisplayName("reach uses the mob's own box: a spider's wide box is in reach where a player's is not")
        void reachUsesMobBox() {
            Fixture fixture = new Fixture();
            double[] spider = EntityDimensions.of("spider");
            // Spider half width 0.7: front face 3.0 blocks from the eye (in reach).
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 4.2, false, spider[0], spider[1]);
            for (int i = 0; i < 5; i++) {
                fixture.attack(TARGET_ID);
            }
            assertEquals(0, fixture.check(ReachCheck.class).violationCount());

            // A player at the same spot: front face 3.4 blocks away.
            fixture.tracked.spawn(TARGET_ID + 1, 0.5, FEET_Y, 4.2);
            for (int i = 0; i < 5; i++) {
                fixture.attack(TARGET_ID + 1);
            }
            assertTrue(fixture.check(ReachCheck.class).violationCount() >= 1);
        }

        @Test
        @DisplayName("a full tracker evicts a mob for a new player, never a player for a mob")
        void playersWinWhenFull() {
            NearbyPlayers tracked = new NearbyPlayers();
            for (int i = 0; i < NearbyPlayers.MAX_TRACKED; i++) {
                tracked.spawn(1000 + i, 0, 0, 0, false, 0.6, 1.95);
            }
            tracked.spawn(5, 1, 2, 3);
            assertTrue(tracked.indexOf(5) >= 0, "player evicts a mob");
            assertEquals(NearbyPlayers.MAX_TRACKED, tracked.size());
            assertTrue(tracked.isPlayer(tracked.indexOf(5)));
            tracked.spawn(9999, 0, 0, 0, false, 0.6, 1.95);
            assertEquals(-1, tracked.indexOf(9999), "a mob does not evict anything");
            assertEquals(1.8, tracked.height(tracked.indexOf(5)));
            tracked.setSneaking(5, true);
            assertEquals(1.5, tracked.height(tracked.indexOf(5)));
            assertEquals(1.95, tracked.height(tracked.indexOf(1001)));
        }
    }

    @Nested
    @DisplayName("aim (rotating killaura / aim assist)")
    class Aim {

        @Test
        @DisplayName("the mouse grid is learnt from ordinary rotations")
        void learnsGrid() {
            Fixture fixture = new Fixture();
            learn(fixture, new Random(1), 80);
            AimCheck aim = fixture.check(AimCheck.class);
            assertTrue(Math.abs(aim.grid() - GRID) < 1.0e-4, () -> "grid=" + aim.grid());
        }

        @Test
        @DisplayName("a legit fighter (grid rotations) is never flagged")
        void legitFightIsSilent() {
            Fixture fixture = new Fixture();
            Random random = new Random(2);
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            learn(fixture, random, 80);
            for (int i = 0; i < 400; i++) {
                fixture.mouse(nonZero(random, 40), random.nextInt(9) - 4);
                if (i % 10 == 0) {
                    fixture.attack(TARGET_ID);
                }
                fixture.tick();
            }
            assertEquals(0, fixture.check(AimCheck.class).violationCount(),
                    () -> "alerts: " + fixture.harness.alerts.alerts());
        }

        @Test
        @DisplayName("computed (atan2-style) rotations while attacking flag")
        void computedRotationsFlag() {
            Fixture fixture = new Fixture();
            Random random = new Random(3);
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            learn(fixture, random, 80);
            for (int i = 0; i < 200; i++) {
                // Aura rotation: lands anywhere, not on a whole number of mouse counts.
                float toYaw = fixture.yaw + (float) (random.nextDouble() * 8.0 - 4.0);
                float toPitch = (float) (random.nextDouble() * 20.0 - 10.0);
                fixture.rotateTo(toYaw, toPitch);
                if (i % 10 == 0) {
                    fixture.attack(TARGET_ID);
                }
                fixture.tick();
            }
            assertTrue(fixture.check(AimCheck.class).violationCount() >= 1,
                    () -> "alerts: " + fixture.harness.alerts.alerts());
        }

        @Test
        @DisplayName("without a learnt grid (cinematic camera, controller) nothing is judged")
        void unknownGridIsSilent() {
            Fixture fixture = new Fixture();
            Random random = new Random(4);
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            for (int i = 0; i < 300; i++) {
                float toYaw = fixture.yaw + (float) (random.nextDouble() * 6.0 - 3.0);
                float toPitch = (float) (random.nextDouble() * 10.0 - 5.0);
                fixture.rotateTo(toYaw, toPitch);
                if (i > 150 && i % 10 == 0) {
                    fixture.attack(TARGET_ID);
                }
                fixture.tick();
            }
            AimCheck aim = fixture.check(AimCheck.class);
            assertTrue(Double.isNaN(aim.grid()), () -> "grid=" + aim.grid());
            assertEquals(0, aim.violationCount());
        }

        @Test
        @DisplayName("snap to the target at the attack and straight back flags on the third time")
        void snapBack() {
            Fixture fixture = new Fixture();
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            for (int i = 0; i < 3; i++) {
                fixture.rotateTo(fixture.yaw + 2f, 0f);
                fixture.tick();
                fixture.rotateTo(fixture.yaw + 70f, 0f);
                fixture.attack(TARGET_ID);
                fixture.tick();
                fixture.rotateTo(fixture.yaw - 68f, 0f);
                fixture.tick();
                for (int k = 0; k < 30; k++) {
                    fixture.tick();
                }
            }
            assertEquals(1, fixture.check(AimCheck.class).violationCount(),
                    () -> "alerts: " + fixture.harness.alerts.alerts());
        }

        @Test
        @DisplayName("a flick that is not turned back is not a snap")
        void flickWithoutReturn() {
            Fixture fixture = new Fixture();
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            for (int i = 0; i < 10; i++) {
                fixture.rotateTo(fixture.yaw + 70f, 0f);
                fixture.attack(TARGET_ID);
                fixture.tick();
                fixture.rotateTo(fixture.yaw + 5f, 0f);
                fixture.tick();
            }
            assertEquals(0, fixture.check(AimCheck.class).violationCount());
        }

        @Test
        @DisplayName("estimator maths: float gcd and residuals")
        void estimatorMaths() {
            assertEquals(0.15, SensitivityEstimator.gcd(0.15 * 7, 0.15 * 4, 0.005), 1.0e-6);
            assertEquals(0.3, SensitivityEstimator.gcd(0.15 * 6, 0.15 * 4, 0.005), 1.0e-6);
            assertEquals(0.0, SensitivityEstimator.residual(0.15 * 9, 0.15), 1.0e-9);
            assertEquals(0.5, SensitivityEstimator.residual(0.05, 0.15), "below one mouse count");
            assertTrue(SensitivityEstimator.residual(0.15 * 9.4, 0.15) > 0.3);
            assertEquals(10.0, AimCheck.wrap(370.0), 1.0e-9);
            assertEquals(-10.0, AimCheck.wrap(-370.0), 1.0e-9);
        }
    }

    @Nested
    @DisplayName("killaura signals (Multitask, GuiMove, NoSwing)")
    class KillauraSignals {

        @Test
        @DisplayName("attacks without arm swing flag on 1.21.2+ clients; with swing they do not")
        void noSwing() {
            Fixture fixture = new Fixture();
            fixture.player.enableTickEndMode();
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            KillauraCheck killaura = fixture.check(KillauraCheck.class);
            for (int i = 0; i < 20; i++) {
                fixture.attack(TARGET_ID);
                killaura.onSwing();
                fixture.tick();
            }
            assertEquals(0, killaura.violationCount());
            for (int i = 0; i < 4; i++) {
                fixture.attack(TARGET_ID);
                fixture.tick();
            }
            assertEquals(1, killaura.violationCount(), () -> "alerts: " + fixture.harness.alerts.alerts());
        }

        @Test
        @DisplayName("attacks while an acknowledged container is open flag; before the ack or after close they do not")
        void containerOpen() {
            Fixture fixture = new Fixture();
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            KillauraCheck killaura = fixture.check(KillauraCheck.class);
            List<Runnable> afterSend = new ArrayList<>();
            killaura.onContainerOpened(afterSend);
            afterSend.forEach(Runnable::run);
            for (int i = 0; i < 5; i++) {
                fixture.attack(TARGET_ID);
            }
            assertEquals(0, killaura.violationCount(), "not acknowledged yet");

            fixture.acknowledgeAll();
            for (int i = 0; i < 3; i++) {
                fixture.attack(TARGET_ID);
            }
            assertEquals(1, killaura.violationCount(), () -> "alerts: " + fixture.harness.alerts.alerts());
        }

        @Test
        @DisplayName("attacks during a confirmed item use flag; the use ending right after does not")
        void itemUse() {
            Fixture fixture = new Fixture();
            fixture.tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            KillauraCheck killaura = fixture.check(KillauraCheck.class);
            List<Runnable> afterSend = new ArrayList<>();
            killaura.onUsingFlag(true, afterSend);
            afterSend.forEach(Runnable::run);
            fixture.acknowledgeAll();

            // Eating ends on the server one tick after the attack: the race is never judged.
            fixture.attack(TARGET_ID);
            fixture.tick();
            killaura.onUsingFlag(false, new ArrayList<>());
            for (int i = 0; i < 5; i++) {
                fixture.tick();
            }
            assertEquals(0, killaura.violationCount());

            afterSend.clear();
            killaura.onUsingFlag(true, afterSend);
            afterSend.forEach(Runnable::run);
            fixture.acknowledgeAll();
            for (int i = 0; i < 3; i++) {
                fixture.attack(TARGET_ID);
                for (int k = 0; k < 4; k++) {
                    fixture.tick();
                }
            }
            assertEquals(1, killaura.violationCount(), () -> "alerts: " + fixture.harness.alerts.alerts());
            assertFalse(killaura.descriptor().description().isEmpty());
        }
    }
}
