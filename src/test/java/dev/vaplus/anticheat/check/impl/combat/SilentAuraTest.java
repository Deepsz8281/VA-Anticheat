package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Silent aura: aim lock on the hitbox centre and snap-away-and-back")
class SilentAuraTest {

    private static final int TARGET_ID = 41;
    private static final double FEET_Y = 64.0;
    private static final double EYE_Y = FEET_Y + 1.62;

    private final Harness harness;
    private final PlayerData player;
    private final NearbyPlayers tracked;
    private float yaw;
    private float pitch;

    SilentAuraTest() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
        harness = TestContexts.builder()
                .module(simulation)
                .module(visibility)
                .module(new CombatChecksModule(simulation, visibility))
                .build();
        player = harness.newPlayer();
        player.movement().applyPosition(0.5, FEET_Y, 0.5);
        player.movement().applyPosition(0.5, FEET_Y, 0.5);
        player.movement().applyRotation(0f, 0f);
        player.onClientTick();
        tracked = player.component(visibility.nearbyKey());
    }

    private AimCheck aim() {
        return player.checks().get(AimCheck.class);
    }

    private void attack() {
        EntityAttack attack = new EntityAttack();
        attack.assign(TARGET_ID, EntityAttack.Kind.ATTACK, false, 0, 0, 0, System.nanoTime());
        player.checks().dispatchAttack(attack);
    }

    private void rotateTo(float toYaw, float toPitch) {
        LookChange change = new LookChange();
        change.assign(yaw, pitch, toYaw, toPitch, false, 0L);
        yaw = toYaw;
        pitch = toPitch;
        player.movement().applyRotation(toYaw, toPitch);
        player.checks().dispatchRotation(change);
    }

    /** Aura rotation: exact atan2 direction from the eye to the target's box centre. */
    private void aimAtCentre(double x, double centreY, double z, double offsetX, double offsetY) {
        double dx = x + offsetX - 0.5;
        double dz = z - 0.5;
        double dy = centreY + offsetY - EYE_Y;
        float toYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float toPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        rotateTo(toYaw, toPitch);
    }

    @Test
    @DisplayName("rotations locked on the centre of a strafing target flag")
    void lockOnCentreFlags() {
        tracked.spawn(TARGET_ID, -1.0, FEET_Y, 3.5);
        double x = -1.0;
        for (int i = 0; i < 80; i++) {
            x += 0.05;
            tracked.moveTo(TARGET_ID, x, FEET_Y, 3.5);
            player.onClientTick();
            aimAtCentre(x, FEET_Y + 0.9, 3.5, 0.0, 0.0);
            if (i % 2 == 0) {
                attack();
            }
        }
        assertTrue(aim().violationCount() >= 1, () -> "alerts: " + harness.alerts.alerts());
    }

    @Test
    @DisplayName("a human hitting anywhere on the body is not locked")
    void humanAimIsSilent() {
        Random random = new Random(7);
        tracked.spawn(TARGET_ID, -1.0, FEET_Y, 3.5);
        double x = -1.0;
        for (int i = 0; i < 200; i++) {
            x += 0.02;
            tracked.moveTo(TARGET_ID, x, FEET_Y, 3.5);
            player.onClientTick();
            aimAtCentre(x, FEET_Y + 0.9, 3.5, random.nextDouble() * 0.5 - 0.25, random.nextDouble() * 1.4 - 0.7);
            if (i % 2 == 0) {
                attack();
            }
        }
        assertEquals(0, aim().violationCount(), () -> "alerts: " + harness.alerts.alerts());
    }

    @Test
    @DisplayName("away to the target, hold for a few ticks, back to the start point: snap")
    void holdAndReturn() {
        tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
        for (int i = 0; i < 3; i++) {
            float start = yaw;
            rotateTo(start + 90f, 10f);
            player.onClientTick();
            attack();
            rotateTo(start + 91f, 11f);
            player.onClientTick();
            attack();
            rotateTo(start + 92f, 10f);
            player.onClientTick();
            // Back next to where the camera really is (it moved by one degree meanwhile).
            rotateTo(start + 1f, 0f);
            player.onClientTick();
            for (int k = 0; k < 30; k++) {
                rotateTo(yaw + 1f, 0f);
                player.onClientTick();
            }
        }
        assertEquals(1, aim().violationCount(), () -> "alerts: " + harness.alerts.alerts());
    }

    @Test
    @DisplayName("turning to a target and staying there is not a snap")
    void turnAndStay() {
        tracked.spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
        for (int i = 0; i < 6; i++) {
            rotateTo(yaw + 90f, 0f);
            attack();
            player.onClientTick();
            for (int k = 0; k < 15; k++) {
                rotateTo(yaw + 2f, 0f);
                player.onClientTick();
            }
        }
        assertEquals(0, aim().violationCount(), () -> "alerts: " + harness.alerts.alerts());
    }

    @Test
    @DisplayName("rotation error maths: exact direction is 0, a one degree offset is 1")
    void rotationErrorMaths() {
        assertEquals(0.0, AimCheck.rotationError(0, 0, 0, 0.0, 0.0, 0, 0, 5), 1.0e-9);
        assertEquals(90.0, AimCheck.rotationError(0, 0, 0, 0.0, 0.0, -5, 0, 0), 1.0e-9);
        assertEquals(1.0, AimCheck.rotationError(0, 0, 0, 1.0, 0.0, 0, 0, 5), 1.0e-9);
    }
}
