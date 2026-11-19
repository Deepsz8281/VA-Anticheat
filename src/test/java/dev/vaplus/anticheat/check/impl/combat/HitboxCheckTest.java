package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Hitbox (look ray against the target box)")
class HitboxCheckTest {

    private static final int TARGET = 21;
    private static final double FEET_Y = 64.0;
    /** Target stands 3 blocks in front of the attacker at x = 0.5. */
    private static final double TARGET_X = 0.5;
    private static final double DISTANCE = 3.0;

    private record Fixture(Harness harness, PlayerData player, NearbyPlayers tracked, HitboxCheck check) {

        void tick(float yaw, float pitch, boolean attack) {
            if (attack) {
                EntityAttack packet = new EntityAttack();
                packet.assign(TARGET, EntityAttack.Kind.ATTACK, false, 0, 0, 0, 0L);
                player.checks().dispatchAttack(packet);
            }
            player.movement().applyRotation(yaw, pitch);
            player.onClientTick();
        }

        void idle(float yaw, int ticks) {
            for (int i = 0; i < ticks; i++) {
                tick(yaw, 0f, false);
            }
        }

        /** The first attack on a target only starts its history; then the lag window fills. */
        void prime() {
            idle(0f, 2);
            tick(0f, 0f, true);
            idle(0f, 8);
        }

        /** Repeated attacks at a fixed look, 10 ticks apart (attack cooldown). */
        void attacks(float yaw, float pitch, int count) {
            for (int i = 0; i < count; i++) {
                tick(yaw, pitch, true);
                for (int j = 0; j < 9; j++) {
                    tick(yaw, pitch, false);
                }
            }
        }

        int flags() {
            return check.violationCount();
        }

        String details() {
            return "judged=" + check.judgedSamples() + " hits=" + check.hitSamples()
                    + " misses=" + check.missSamples() + " lastMiss=" + check.lastMiss()
                    + " alerts=" + harness.alerts.alerts();
        }
    }

    /** Yaw that aims the ray {@code lateral} blocks beside the target centre at its distance. */
    private static float yawFor(double lateral) {
        return (float) Math.toDegrees(Math.atan2(lateral, DISTANCE));
    }

    private static Fixture fixture(boolean tickEnd) {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
        Harness harness = TestContexts.builder()
                .module(simulation)
                .module(visibility)
                .check(HitboxCheck.DESCRIPTOR, p -> new HitboxCheck(p, visibility.nearbyKey()))
                .build();
        PlayerData player = harness.newPlayer();
        if (tickEnd) {
            player.enableTickEndMode();
        }
        player.movement().applyPosition(0.5, FEET_Y, 0.5);
        player.movement().applyRotation(0f, 0f);
        NearbyPlayers tracked = player.component(visibility.nearbyKey());
        tracked.spawn(TARGET, TARGET_X, FEET_Y, 0.5 + DISTANCE);
        return new Fixture(harness, player, tracked, player.checks().get(HitboxCheck.class));
    }

    @Test
    @DisplayName("descriptor and registration")
    void registered() {
        assertEquals("hitbox", HitboxCheck.DESCRIPTOR.id());
        assertTrue(fixture(true).check() != null);
    }

    @Test
    @DisplayName("crosshair on the body never flags")
    void onBody() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.attacks(0f, 0f, 30);
        assertEquals(0, fixture.flags(), fixture::details);
        assertTrue(fixture.check().hitSamples() >= 25, fixture::details);
    }

    @Test
    @DisplayName("crosshair on the edge and just outside the edge (within tolerance) never flags")
    void onEdge() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.attacks(yawFor(0.29), 0f, 10);
        fixture.attacks(yawFor(-0.37), 0f, 10);
        assertEquals(0, fixture.flags(), fixture::details);
        assertTrue(fixture.check().hitSamples() >= 15, fixture::details);
    }

    @Test
    @DisplayName("a miss between the tolerance and the miss limit is neither evidence nor a hit")
    void borderline() {
        Fixture fixture = fixture(true);
        fixture.prime();
        // 0.3 half width + 0.13 beyond the box
        fixture.attacks(yawFor(0.43), 0f, 20);
        assertEquals(0, fixture.flags(), fixture::details);
        assertEquals(0, fixture.check().missSamples(), fixture::details);
    }

    @Test
    @DisplayName("hits 0.4 blocks beside the box flag")
    void farBeside() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.attacks(yawFor(0.8), 0f, 8);
        assertTrue(fixture.flags() >= 1, fixture::details);
        assertTrue(fixture.check().lastMiss() > 0.25 && fixture.check().lastMiss() < 0.6, fixture::details);
    }

    @Test
    @DisplayName("hits 0.25 and 0.5 blocks beside the box flag, on either side")
    void besideBothSides() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.attacks(yawFor(-0.55), 0f, 8);
        assertTrue(fixture.flags() >= 1, fixture::details);
        Fixture other = fixture(true);
        other.prime();
        other.attacks(yawFor(0.8), 0f, 8);
        assertTrue(other.flags() >= 1, other.details());
    }

    @Test
    @DisplayName("a sneaking target hit on the body never flags")
    void sneakingTarget() {
        Fixture fixture = fixture(true);
        fixture.tracked().setSneaking(TARGET, true);
        fixture.prime();
        fixture.attacks(0f, 5f, 12);
        assertEquals(0, fixture.flags(), fixture::details);
        assertTrue(fixture.check().hitSamples() >= 10, fixture::details);
    }

    @Test
    @DisplayName("an eye lower than the tracked pose (forced crouch under a ceiling) never flags")
    void lowerEye() {
        // From the standing eye (65.62) this ray passes 0.3 above the box top; from 1.27 it hits.
        float pitch = (float) -Math.toDegrees(Math.atan2(0.48, DISTANCE));
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.attacks(0f, pitch, 12);
        assertEquals(0, fixture.flags(), fixture::details);
        assertEquals(0, fixture.check().missSamples(), fixture::details);
    }

    @Test
    @DisplayName("hits with the ray far below the target's feet flag")
    void belowFeet() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.attacks(0f, 40f, 8);
        assertTrue(fixture.flags() >= 1, fixture::details);
    }

    private void strafing(double direction, double lateral) {
        Fixture fixture = fixture(true);
        fixture.prime();
        // The frame eye lies between the positions of the two ticks before the attack tick: the
        // look is on the body from there, although it is beside the box from the newer positions.
        float yaw = yawFor(lateral);
        fixture.idle(yaw, 10);
        for (int cycle = 0; cycle < 14; cycle++) {
            fixture.player().movement().applyPosition(0.5, FEET_Y, 0.5);
            fixture.idle(yaw, 10);
            fixture.player().movement().applyPosition(0.5 + 0.25 * direction, FEET_Y, 0.5);
            fixture.tick(yaw, 0f, false);
            fixture.player().movement().applyPosition(0.5 + 0.5 * direction, FEET_Y, 0.5);
            fixture.tick(yaw, 0f, true);
        }
        assertEquals(0, fixture.flags(), fixture::details);
        assertEquals(0, fixture.check().missSamples(), fixture::details);
        assertTrue(fixture.check().hitSamples() >= 10, fixture::details);
    }

    @Test
    @DisplayName("a strafing attacker whose frame eye lags behind the sent position never flags")
    void strafingAttacker() {
        strafing(1.0, 0.25);
        strafing(1.0, -0.25);
        strafing(-1.0, 0.25);
        strafing(-1.0, -0.25);
    }

    @Test
    @DisplayName("a flick across the target with the click in the middle of the turn never flags")
    void flickAcross() {
        Fixture fixture = fixture(true);
        double far = 6.0;
        fixture.tracked().moveTo(TARGET, TARGET_X, FEET_Y, 0.5 + far);
        float left = (float) Math.toDegrees(Math.atan2(-0.55, far));
        float right = (float) Math.toDegrees(Math.atan2(0.55, far));
        fixture.prime();
        for (int cycle = 0; cycle < 14; cycle++) {
            fixture.idle(left, 10);
            // Both sent rotations are 0.25 blocks beside the box; the frame's look was in between.
            fixture.tick(right, 0f, true);
        }
        assertEquals(0, fixture.flags(), fixture::details);
        assertEquals(0, fixture.check().missSamples(), fixture::details);
        assertTrue(fixture.check().hitSamples() >= 10, fixture::details);
    }

    @Test
    @DisplayName("a riding target (stale tracked position) is not judged; judging resumes after the dismount")
    void ridingTarget() {
        Fixture fixture = fixture(true);
        fixture.check().onPassengers(77, new int[]{TARGET});
        fixture.prime();
        fixture.attacks(yawFor(0.8), 0f, 10);
        assertEquals(0, fixture.check().judgedSamples(), fixture::details);
        fixture.check().onPassengers(77, new int[0]);
        fixture.attacks(yawFor(0.8), 0f, 1);
        assertEquals(0, fixture.check().judgedSamples(), fixture::details);
        fixture.attacks(yawFor(0.8), 0f, 8);
        assertTrue(fixture.flags() >= 1, fixture::details);
    }

    @Test
    @DisplayName("mounting inside the window, and a destroyed vehicle, restart the target history")
    void mountInsideWindow() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.check().onPassengers(77, new int[]{TARGET});
        fixture.check().onEntityDestroyed(77);
        fixture.tick(yawFor(0.8), 0f, true);
        fixture.tick(yawFor(0.8), 0f, false);
        fixture.tick(yawFor(0.8), 0f, true);
        assertEquals(0, fixture.check().judgedSamples(), fixture::details);
        fixture.idle(yawFor(0.8), 10);
        fixture.attacks(yawFor(0.8), 0f, 8);
        assertTrue(fixture.flags() >= 1, fixture::details);
    }

    @Test
    @DisplayName("scaled targets and scaled attackers are not judged")
    void scale() {
        Fixture target = fixture(true);
        target.check().onScale(TARGET, true);
        target.prime();
        target.attacks(yawFor(0.8), 0f, 10);
        assertEquals(0, target.check().judgedSamples(), target.details());
        target.check().onScale(TARGET, false);
        target.idle(yawFor(0.8), 10);
        target.attacks(yawFor(0.8), 0f, 8);
        assertTrue(target.flags() >= 1, target.details());

        Fixture attacker = fixture(true);
        attacker.player().attributes().scale(2.0);
        attacker.prime();
        attacker.attacks(yawFor(0.8), 0f, 10);
        assertEquals(0, attacker.check().judgedSamples(), attacker.details());
    }

    @Test
    @DisplayName("a full passenger table makes every target unknown until the next world")
    void passengerOverflow() {
        Fixture fixture = fixture(true);
        for (int vehicle = 0; vehicle < 300; vehicle++) {
            fixture.check().onPassengers(1000 + vehicle, new int[]{2000 + vehicle});
        }
        fixture.prime();
        fixture.attacks(yawFor(0.8), 0f, 10);
        assertEquals(0, fixture.check().judgedSamples(), fixture::details);
        fixture.check().onEntitiesCleared();
        fixture.prime();
        fixture.attacks(yawFor(0.8), 0f, 8);
        assertTrue(fixture.flags() >= 1, fixture::details);
    }

    @Test
    @DisplayName("latency beyond the recorded window is not judged (never truncated)")
    void lagBeyondWindow() {
        Fixture fixture = fixture(true);
        fixture.check().rttOverrideMillis = 900L;
        fixture.prime();
        fixture.idle(0f, 30);
        fixture.attacks(yawFor(0.8), 0f, 10);
        assertEquals(0, fixture.check().judgedSamples(), fixture::details);
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("a target that walked away inside the lag window is judged at its earlier positions")
    void movingTargetInsideWindow() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int cycle = 0; cycle < 12; cycle++) {
            fixture.tracked().moveTo(TARGET, TARGET_X, FEET_Y, 0.5 + DISTANCE);
            fixture.idle(0f, 12);
            // 0.3 blocks per tick sideways: after 3 ticks the target is 0.9 off the crosshair.
            for (int step = 1; step <= 3; step++) {
                fixture.tracked().moveTo(TARGET, TARGET_X + 0.3 * step, FEET_Y, 0.5 + DISTANCE);
                fixture.tick(0f, 0f, step == 3);
            }
        }
        assertEquals(0, fixture.flags(), fixture::details);
        assertTrue(fixture.check().judgedSamples() >= 10, fixture::details);
        assertTrue(fixture.check().hitSamples() >= 10, fixture::details);
    }

    private void walkAwayScenario(Fixture fixture, int cycles) {
        fixture.prime();
        for (int cycle = 0; cycle < cycles; cycle++) {
            fixture.tracked().moveTo(TARGET, TARGET_X, FEET_Y, 0.5 + DISTANCE);
            fixture.idle(0f, 14);
            for (int step = 1; step <= 8; step++) {
                fixture.tracked().moveTo(TARGET, TARGET_X + 0.4 * step, FEET_Y, 0.5 + DISTANCE);
                fixture.tick(0f, 0f, step == 8);
            }
        }
    }

    @Test
    @DisplayName("high ping widens the lag window; without it the same hits are impossible")
    void highPing() {
        Fixture laggy = fixture(true);
        laggy.check().rttOverrideMillis = 300L;
        walkAwayScenario(laggy, 12);
        assertEquals(0, laggy.flags(), laggy.details());
        assertTrue(laggy.check().hitSamples() >= 10, laggy.details());

        Fixture lan = fixture(true);
        lan.check().rttOverrideMillis = 0L;
        walkAwayScenario(lan, 12);
        assertTrue(lan.flags() >= 1, lan.details());
    }

    @Test
    @DisplayName("a target that teleported inside the window is not judged")
    void teleportedTarget() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int cycle = 0; cycle < 10; cycle++) {
            fixture.tracked().moveTo(TARGET, TARGET_X, FEET_Y, 0.5 + DISTANCE);
            fixture.idle(0f, 14);
            fixture.tracked().moveTo(TARGET, TARGET_X + 4.5, FEET_Y, 0.5 + DISTANCE);
            fixture.tick(0f, 0f, false);
            fixture.tick(0f, 0f, true);
        }
        assertEquals(0, fixture.check().judgedSamples(), fixture::details);
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("the first hit on a target and hits before its history fills the window are not judged")
    void unknownHistory() {
        Fixture fixture = fixture(true);
        fixture.tick(yawFor(0.8), 0f, true);
        fixture.tick(yawFor(0.8), 0f, true);
        fixture.tick(yawFor(0.8), 0f, true);
        assertEquals(0, fixture.check().judgedSamples());
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("a target that left and re-spawned restarts its history")
    void respawnedTarget() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.tracked().remove(TARGET);
        fixture.idle(0f, 2);
        fixture.tracked().spawn(TARGET, TARGET_X, FEET_Y, 0.5 + DISTANCE);
        fixture.idle(0f, 2);
        fixture.attacks(yawFor(0.8), 0f, 1);
        assertEquals(0, fixture.check().judgedSamples(), fixture::details);
    }

    @Test
    @DisplayName("fast view turns are not judged")
    void fastTurn() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int i = 0; i < 12; i++) {
            fixture.idle(0f, 8);
            fixture.tick(yawFor(0.8) + 40f, 0f, true);
            fixture.tick(0f, 0f, false);
        }
        assertEquals(0, fixture.flags(), fixture::details);
    }

    @Test
    @DisplayName("clients without tick-end packets (pre-1.21.2) are not judged")
    void legacyClient() {
        Fixture fixture = fixture(false);
        fixture.prime();
        fixture.attacks(yawFor(0.8), 0f, 12);
        assertEquals(0, fixture.flags());
        assertEquals(0, fixture.check().judgedSamples());
    }

    @Test
    @DisplayName("spectators, gliding, swimming and riding attackers are not judged")
    void excludedStates() {
        Fixture spectator = fixture(true);
        spectator.player().abilities().applyGameMode(GameMode.SPECTATOR);
        spectator.prime();
        spectator.attacks(yawFor(0.8), 0f, 12);
        assertEquals(0, spectator.check().judgedSamples());

        Fixture gliding = fixture(true);
        gliding.player().pose().gliding(true);
        gliding.prime();
        gliding.attacks(yawFor(0.8), 0f, 12);
        assertEquals(0, gliding.check().judgedSamples());

        Fixture swimming = fixture(true);
        swimming.player().pose().swimming(true);
        swimming.prime();
        swimming.attacks(yawFor(0.8), 0f, 12);
        assertEquals(0, swimming.check().judgedSamples());

        Fixture riding = fixture(true);
        riding.player().vehicle().mount(99);
        riding.prime();
        riding.attacks(yawFor(0.8), 0f, 12);
        assertEquals(0, riding.check().judgedSamples());
    }

    @Test
    @DisplayName("targets that are too close or too far are not judged")
    void distanceBounds() {
        Fixture fixture = fixture(true);
        fixture.tracked().moveTo(TARGET, TARGET_X, FEET_Y, 0.5 + 8.0);
        fixture.prime();
        fixture.attacks(yawFor(0.8), 0f, 10);
        assertEquals(0, fixture.check().judgedSamples());
        fixture.tracked().moveTo(TARGET, TARGET_X, FEET_Y, 0.5 + 0.5);
        fixture.idle(0f, 20);
        fixture.attacks(60f, 0f, 10);
        assertEquals(0, fixture.check().judgedSamples());
    }

    @Test
    @DisplayName("Meteor-style expansion: mostly body hits with a few impossible edge hits flags via the window")
    void sporadicExpansion() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int round = 0; round < 6; round++) {
            fixture.idle(0f, 1);
            fixture.attacks(0f, 0f, 4);
            // 0.45 beside the box: impossible without a ~0.5 expansion (turned there a tick earlier)
            fixture.idle(yawFor(0.75), 1);
            fixture.attacks(yawFor(0.75), 0f, 1);
        }
        assertTrue(fixture.check().windowFlags() >= 1, fixture::details);
        String details = fixture.harness().alerts.alerts().get(0).details();
        assertTrue(details.startsWith("pattern"), details);
        double expansion = Double.parseDouble(details.replaceAll(".*expansion>=([0-9.]+).*", "$1"));
        assertTrue(expansion > 0.2 && expansion <= 0.45, details);
    }

    @Test
    @DisplayName("two impossible hits spread over many body hits do not reach the window limit")
    void windowNeedsSeveralMisses() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int round = 0; round < 2; round++) {
            fixture.idle(yawFor(0.75), 1);
            fixture.attacks(yawFor(0.75), 0f, 1);
            fixture.idle(0f, 1);
            fixture.attacks(0f, 0f, 25);
        }
        assertEquals(0, fixture.check().windowFlags(), fixture::details);
        assertEquals(0, fixture.flags(), fixture::details);
    }
}
