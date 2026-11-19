package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VelocityCheck (anti-knockback)")
class VelocityCheckTest {

    private static ReplayScenario onStone() {
        ReplayScenario s = ReplayScenario.withModule(simulation -> new MovementChecksModule(simulation))
                .platform(VanillaBlocks.STONE, 63, 4);
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        return s;
    }

    private static VelocityCheck check(ReplayScenario s) {
        return s.player.checks().get(VelocityCheck.class);
    }

    private static long knock(ReplayScenario s, double x, double y, double z, boolean firstAck, boolean secondAck) {
        VelocityState velocity = s.velocity();
        long id = velocity.register(VelocityState.Kind.SET, x, y, z, s.player.movement().worldEpoch());
        if (firstAck) {
            velocity.markFirstAck(id);
        }
        if (secondAck) {
            velocity.markSecondAck(id, s.player.clientTicks());
        }
        return id;
    }

    @Test
    @DisplayName("descriptor: id velocity, MOVEMENT, no setback by default")
    void descriptor() {
        assertEquals("velocity", VelocityCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, VelocityCheck.DESCRIPTOR.category());
        assertTrue(VelocityCheck.DESCRIPTOR.defaults().setbackVl() < 0);
    }

    @Test
    @DisplayName("taken knockback is explained and relieves the buffer")
    void legitKnockback() {
        ReplayScenario s = onStone();
        knock(s, 0.3, 0.36, 0.0, true, true);
        SimulationResult result = s.move(0.3, 0.36, 0.0);
        assertTrue(result.complete(), result.skipReason());
        assertEquals(1, result.velocityAcked());
        assertTrue(result.ackedVelocityOffset() < 1.0E-3, "offset " + result.ackedVelocityOffset());
        assertEquals(0, check(s).violationCount());
        assertEquals(0.0, check(s).buffer());
    }

    @Test
    @DisplayName("ignoring acknowledged knockback repeatedly flags")
    void ignoredKnockback() {
        ReplayScenario s = onStone();
        for (int i = 0; i < 3; i++) {
            knock(s, 0.3, 0.36, 0.0, true, true);
            SimulationResult result = s.move(0, 0, 0);
            assertTrue(result.complete(), result.skipReason());
            assertTrue(result.offset() < 1.0E-3, "standing still is explainable without the velocity");
            assertTrue(result.ackedVelocityOffset() > 0.4, "but not with it: " + result.ackedVelocityOffset());
        }
        assertTrue(check(s).violationCount() >= 1);
    }

    @Test
    @DisplayName("halved horizontal knockback raises the buffer")
    void reducedKnockback() {
        ReplayScenario s = onStone();
        knock(s, 0.4, 0.36, 0.0, true, true);
        SimulationResult result = s.move(0.2, 0.36, 0.0);
        assertTrue(result.complete(), result.skipReason());
        assertTrue(result.ackedVelocityOffset() > 0.1, "offset " + result.ackedVelocityOffset());
        assertTrue(check(s).buffer() > 0.0);
    }

    @Test
    @DisplayName("a velocity in the ack window (second marker missing) is not judged")
    void windowNotJudged() {
        ReplayScenario s = onStone();
        for (int i = 0; i < 3; i++) {
            knock(s, 0.3, 0.36, 0.0, true, false);
            SimulationResult result = s.move(0, 0, 0);
            assertEquals(0, result.velocityAcked());
            s.velocity().clear();
        }
        assertEquals(0, check(s).violationCount());
        assertEquals(0.0, check(s).buffer());
    }

    @Test
    @DisplayName("tiny velocities are below min-velocity and never judged")
    void tinyVelocity() {
        ReplayScenario s = onStone();
        for (int i = 0; i < 4; i++) {
            knock(s, 0.05, 0.0, 0.0, true, true);
            s.move(0, 0, 0);
        }
        assertEquals(0, check(s).violationCount());
        assertEquals(0.0, check(s).buffer());
    }

    @Test
    @DisplayName("a velocity offerable during a skipped movement is tainted and not judged")
    void taintedAfterSkip() {
        ReplayScenario s = onStone();
        long id = knock(s, 0.3, 0.36, 0.0, true, false);
        s.world().clearChunk(0, 0);
        assertEquals(SimulationResult.Status.SKIPPED, s.move(0.3, 0.36, 0.0).status());
        assertTrue(s.velocity().tainted(0));
        s.velocity().markSecondAck(id, s.player.clientTicks());
        // world back: the next movement is simulated again; the possibly-applied velocity must not count
        s.platform(VanillaBlocks.STONE, 63, 4);
        SimulationResult result = s.move(0, 0, 0);
        assertTrue(Double.isNaN(result.ackedVelocityOffset()) || !result.complete());
        assertEquals(0.0, check(s).buffer());
    }

    @Test
    @DisplayName("primitive decision: multi-tick, unknown and in-limit offsets never raise the buffer")
    void primitiveGuards() {
        ReplayScenario s = onStone();
        VelocityCheck check = check(s);
        check.judge(2, 1.0, 0.0, 0.5, 0.4, 0.0);
        check.judge(1, Double.NaN, 0.0, 0.5, 0.4, 0.0);
        check.judge(1, VelocityCheck.DEFAULT_THRESHOLD, 0.0, 0.5, 0.4, 0.0);
        check.judge(1, 0.06, 0.03, 0.5, 0.4, 0.0);
        assertEquals(0.0, check.buffer());
        check.judge(1, 0.3, 0.0, 0.5, 0.4, 0.0);
        assertEquals(1.0, check.buffer());
        check.judge(1, 0.3, 0.0, 0.5, 0.4, 0.0);
        assertEquals(1, check.violationCount());
        assertFalse(check.buffer() > 4.0);
    }
}
