package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.testutil.replay.scenarios.CheckReplay;
import dev.vaplus.anticheat.testutil.replay.scenarios.Frame;
import dev.vaplus.anticheat.testutil.replay.scenarios.MovementScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.carry;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.fall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: legitimate vanilla movement (written from MCPK/Wiki formulas, replayed through the
 * full pipeline) must never raise any movement-check buffer.
 */
@DisplayName("Movement checks: legitimate replays never flag")
class LegitMovementReplayTest {

    private static void assertClean(CheckReplay replay) {
        assertEquals(0, MovementReplays.violations(replay));
        assertEquals(0.0, replay.check(SpeedCheck.class).buffer(), "speed buffer");
        assertEquals(0.0, replay.check(FlyCheck.class).buffer(), "fly buffer");
        assertEquals(0.0, replay.check(NoFallCheck.class).buffer(), "nofall buffer");
        assertTrue(replay.harness.alerts.alerts().isEmpty());
        assertTrue(replay.harness.setbacks.requests().isEmpty());
        assertTrue(replay.harness.setbacks.targetRequests().isEmpty());
    }

    @Test
    @DisplayName("walking forward on stone (40 ticks)")
    void walking() {
        List<Frame> frames = MovementScenarios.walk(40);
        CheckReplay replay = MovementReplays.onStone().play(frames);
        MovementReplays.assertAllFramesSimulated(replay, frames.size());
        assertClean(replay);
    }

    @Test
    @DisplayName("sprinting forward on stone (40 ticks)")
    void sprinting() {
        List<Frame> frames = MovementScenarios.sprint(40);
        CheckReplay replay = MovementReplays.onStone().play(frames);
        MovementReplays.assertAllFramesSimulated(replay, frames.size());
        assertClean(replay);
    }

    @Test
    @DisplayName("sprint-jumping with jump held: several jumps, landings and re-jumps (40 ticks)")
    void sprintJumping() {
        List<Frame> frames = MovementScenarios.sprintJumping(40);
        long landings = frames.stream().filter(Frame::onGround).count();
        assertTrue(landings >= 2, "the scenario must contain at least two landings, was " + landings);
        CheckReplay replay = MovementReplays.onStone().play(frames);
        MovementReplays.assertAllFramesSimulated(replay, frames.size());
        assertClean(replay);
    }

    @Test
    @DisplayName("acknowledged knockback (0.3, 0.36, 0) is explained by the engine and never flags")
    void knockback() {
        CheckReplay replay = MovementReplays.onStone();
        VelocityState velocity = replay.player.component(replay.simulation.velocityKey());
        long id = velocity.register(VelocityState.Kind.SET, 0.3, 0.36, 0.0, replay.player.movement().worldEpoch());
        velocity.markFirstAck(id);
        velocity.markSecondAck(id, replay.player.clientTicks());

        // No input: the knockback tick starts on stone (carry 0.6), then airborne (carry 1.0) until landing.
        List<Frame> frames = new ArrayList<>();
        double vx = 0.3;
        double vy = 0.36;
        double y = MovementScenarios.FLOOR_TOP;
        boolean startGround = true;
        for (int i = 0; i < 40; i++) {
            if (Math.abs(vx) < 0.003) {
                vx = 0.0;
            }
            if (Math.abs(vy) < 0.003) {
                vy = 0.0;
            }
            double dy = vy;
            boolean landed = vy < 0.0 && y + dy < MovementScenarios.FLOOR_TOP;
            if (landed) {
                dy = MovementScenarios.FLOOR_TOP - y;
            }
            frames.add(new Frame(vx, dy, 0.0, landed));
            y += dy;
            vx = carry(vx, startGround ? 0.6 : 1.0);
            vy = fall(landed ? 0.0 : vy, 0.08);
            startGround = landed;
        }
        assertTrue(frames.stream().anyMatch(Frame::onGround), "the knockback arc lands within 40 ticks");
        replay.play(frames);
        MovementReplays.assertAllFramesSimulated(replay, frames.size());
        assertEquals(0, velocity.size(), "the velocity was consumed by the engine");
        assertClean(replay);
    }

    @Test
    @DisplayName("falling 8 blocks, landing and standing still")
    void fallingAndLanding() {
        List<Frame> frames = MovementScenarios.fallAndLand(72.0, 10);
        CheckReplay replay = MovementReplays.inAir(72.0).play(frames);
        MovementReplays.assertAllFramesSimulated(replay, frames.size());
        assertEquals(MovementScenarios.FLOOR_TOP, replay.y(), 1.0E-9, "landed on the floor");
        assertClean(replay);
    }
}
