package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FastLadderCheck (climbing faster than vanilla)")
class FastLadderCheckTest {

    /** Vanilla climb rise per tick with the default gravity: (0.2 - 0.08) * 0.98. */
    private static final double CLIMB = 0.1176;

    private static ReplayScenario ladder() {
        ReplayScenario s = ReplayScenario.withModule(MovementChecksModule::new)
                .platform(VanillaBlocks.STONE, 63, 4);
        s.fill(VanillaBlocks.LADDER, 0, 0, 64, 79, 0, 0);
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.move(0, 0, 0, true);
        s.move(0, 0, 0, true);
        return s;
    }

    private static FastLadderCheck check(ReplayScenario s) {
        return s.player.checks().get(FastLadderCheck.class);
    }

    @Test
    @DisplayName("descriptor: id fastladder, MOVEMENT")
    void descriptor() {
        assertEquals("fastladder", FastLadderCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, FastLadderCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("vanilla climbing and jumping onto the ladder never flag")
    void legitClimb() {
        ReplayScenario s = ladder();
        s.move(0, CLIMB, 0, true);          // pushed up from the ground (take-off not judged)
        for (int i = 0; i < 30; i++) {
            s.move(0, CLIMB, 0, false);
        }
        assertEquals(0, check(s).fastTicks());

        ReplayScenario jump = ladder();
        jump.move(0, 0.42, 0, false);       // jump take-off
        jump.move(0, 0.3332, 0, false);     // still the jump arc
        for (int i = 0; i < 20; i++) {
            jump.move(0, CLIMB, 0, false);
        }
        assertEquals(0, check(jump).fastTicks());
        assertEquals(0, check(jump).violationCount());
    }

    @Test
    @DisplayName("climbing at 0.25 blocks per tick flags")
    void fastClimb() {
        ReplayScenario s = ladder();
        s.move(0, CLIMB, 0, true);
        for (int i = 0; i < 20; i++) {
            s.move(0, 0.25, 0, false);
        }
        assertTrue(check(s).fastTicks() >= 19, "fast ticks " + check(s).fastTicks());
        assertTrue(check(s).violationCount() > 0);
    }

    @Test
    @DisplayName("away from climbable blocks nothing is judged")
    void noLadder() {
        ReplayScenario s = ReplayScenario.withModule(MovementChecksModule::new).platform(VanillaBlocks.STONE, 63, 4);
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.move(0, 0, 0, true);
        s.move(0, CLIMB, 0, true);
        for (int i = 0; i < 10; i++) {
            s.move(0, 0.25, 0, false);
        }
        assertEquals(0, check(s).fastTicks());
    }

    @Test
    @DisplayName("levitation and server velocity are not judged")
    void excused() {
        ReplayScenario s = ladder();
        s.player.effects().apply(TrackedEffect.LEVITATION, 4);
        s.move(0, CLIMB, 0, true);
        for (int i = 0; i < 10; i++) {
            s.move(0, 0.25, 0, false);
        }
        assertEquals(0, check(s).fastTicks());

        ReplayScenario v = ladder();
        v.move(0, CLIMB, 0, true);
        VelocityState velocity = v.velocity();
        long id = velocity.register(VelocityState.Kind.SET, 0, 0.5, 0, v.player.movement().worldEpoch());
        velocity.markFirstAck(id);
        velocity.markSecondAck(id, v.player.clientTicks());
        v.move(0, 0.5, 0, false);
        assertEquals(0, check(v).fastTicks());
    }
}
