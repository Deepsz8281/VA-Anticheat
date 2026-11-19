package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.impl.combat.EntityAttack;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VelocityCheck distance signal (anti-KB without the simulation)")
class VelocityDistanceTest {

    /** Vanilla vertical motion after a 0.36 knockback: 0.36, then (v - 0.08) * 0.98. */
    private static final double[] RISE = {0.36, 0.2744, 0.1905};

    private static ReplayScenario scenario() {
        ReplayScenario s = ReplayScenario.withModule(MovementChecksModule::new)
                .tickEndMode()
                .platform(VanillaBlocks.STONE, 63, 12);
        s.spawn(0.5, 64.0, 0.5, 0f);
        tick(s, 0, 0, 0, true);
        tick(s, 0, 0, 0, true);
        // no keys held (1.21.2+ input packet)
        s.player.pose().applyInput(false, false, false, false, false, false, false, s.player.clientTicks());
        return s;
    }

    private static VelocityCheck check(ReplayScenario s) {
        return s.player.checks().get(VelocityCheck.class);
    }

    /** One client tick in production order: the position packet, then the tick-end packet. */
    private static void tick(ReplayScenario s, double dx, double dy, double dz, boolean ground) {
        s.move(dx, dy, dz, ground);
        s.player.onClientTick();
    }

    /** Server knockback along +z, both markers acknowledged (the client has applied it). */
    private static void knock(ReplayScenario s, double horizontal) {
        VelocityState velocity = s.velocity();
        long id = velocity.register(VelocityState.Kind.SET, 0.0, 0.36, horizontal, s.player.movement().worldEpoch());
        velocity.markFirstAck(id);
        velocity.markSecondAck(id, s.player.clientTicks());
    }

    /** Lands and stands still long enough for the next hit to start from the ground. */
    private static void land(ReplayScenario s, double dropToGround) {
        tick(s, 0, -dropToGround, 0, true);
        for (int i = 0; i < 5; i++) {
            tick(s, 0, 0, 0, true);
        }
    }

    @Test
    @DisplayName("taking vanilla knockback passes")
    void takenKnockback() {
        ReplayScenario s = scenario();
        for (int hit = 0; hit < 4; hit++) {
            knock(s, 0.4);
            double motion = 0.4;
            boolean ground = true;
            double height = 0;
            for (double dy : RISE) {
                tick(s, 0, dy, motion, false);
                height += dy;
                motion *= ground ? 0.546 : 0.91;
                ground = false;
            }
            land(s, height);
        }
        assertEquals(4, check(s).distanceVerdicts());
        assertEquals(0, check(s).distanceFailures());
    }

    /** Explosion knockback along +z (added to the motion), both markers acknowledged. */
    private static void explode(ReplayScenario s, double horizontal) {
        VelocityState velocity = s.velocity();
        long id = velocity.register(VelocityState.Kind.ADD, 0.0, 0.2, horizontal, s.player.movement().worldEpoch());
        velocity.markFirstAck(id);
        velocity.markSecondAck(id, s.player.clientTicks());
    }

    @Test
    @DisplayName("explosions: taking the push passes, ignoring it flags")
    void explosions() {
        ReplayScenario s = scenario();
        for (int hit = 0; hit < 3; hit++) {
            explode(s, 0.6);
            double motion = 0.6;
            for (int t = 0; t < 3; t++) {
                tick(s, 0, 0, motion, true);
                motion *= 0.546;
            }
            land(s, 0);
        }
        assertEquals(0, check(s).distanceFailures());
        for (int hit = 0; hit < 3; hit++) {
            explode(s, 0.6);
            for (int t = 0; t < 3; t++) {
                tick(s, 0, 0, 0, true);
            }
            land(s, 0);
        }
        assertEquals(3, check(s).distanceFailures());
    }

    @Test
    @DisplayName("ignoring knockback flags")
    void ignoredKnockback() {
        ReplayScenario s = scenario();
        for (int hit = 0; hit < 3; hit++) {
            knock(s, 0.4);
            for (int t = 0; t < 3; t++) {
                tick(s, 0, 0, 0, true);
            }
            land(s, 0);
        }
        assertEquals(3, check(s).distanceFailures());
        assertTrue(check(s).violationCount() > 0);
    }

    @Test
    @DisplayName("ignoring knockback without sending any position flags too")
    void noPositionPackets() {
        ReplayScenario s = scenario();
        knock(s, 0.4);
        for (int t = 0; t < 3; t++) {
            s.player.onClientTick();
        }
        assertEquals(1, check(s).distanceFailures());
    }

    @Test
    @DisplayName("half knockback while walking into it flags")
    void reducedKnockback() {
        ReplayScenario s = scenario();
        knock(s, 0.4);
        tick(s, 0, 0.36, 0.02, false);
        tick(s, 0, 0.2744, 0.01, false);
        tick(s, 0, 0.1905, 0.0, false);
        assertEquals(1, check(s).distanceFailures());
    }

    @Test
    @DisplayName("weak knockback, own attacks and walls are not judged")
    void notJudged() {
        ReplayScenario s = scenario();
        knock(s, 0.1);
        for (int t = 0; t < 3; t++) {
            tick(s, 0, 0, 0, true);
        }
        knock(s, 0.4);
        EntityAttack attack = new EntityAttack();
        attack.assign(7, EntityAttack.Kind.ATTACK, false, 0, 0, 0, System.nanoTime());
        s.player.checks().dispatchAttack(attack);
        for (int t = 0; t < 3; t++) {
            tick(s, 0, 0, 0, true);
        }
        s.world().setBlock(new BlockPos(0, 64, 0), new dev.vaplus.anticheat.world.mirror.BlockKey("minecraft:cobweb"));
        knock(s, 0.4);
        for (int t = 0; t < 3; t++) {
            tick(s, 0, 0, 0, true);
        }
        assertEquals(0, check(s).distanceVerdicts());
    }
}
