package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpeedCheck limit signal (physical bound without the simulation)")
class SpeedLimitTest {

    /** Vanilla vertical motion of a jump until landing on the start level. */
    private static final double[] JUMP = {0.42, 0.3332, 0.2486, 0.1670, 0.0871, 0.0090, -0.0784,
            -0.1552, -0.2305, -0.3043};

    private static ReplayScenario scenario(BlockKey floor) {
        ReplayScenario s = ReplayScenario.withModule(MovementChecksModule::new)
                .tickEndMode()
                .platform(floor, 63, 4);
        s.fill(floor, -4, 200, 63, 63, -4, 4);
        s.fill(VanillaBlocks.AIR, -4, 200, 64, 79, -4, 4);
        s.spawn(0.5, 64.0, 0.5, -90f);
        tick(s, 0, 0, true);
        tick(s, 0, 0, true);
        return s;
    }

    private static SpeedCheck check(ReplayScenario s) {
        return s.player.checks().get(SpeedCheck.class);
    }

    /** One client tick in production order: the position packet, then the tick-end packet. */
    private static void tick(ReplayScenario s, double dx, double dy, boolean ground) {
        s.move(dx, dy, 0.0, ground);
        s.player.onClientTick();
    }

    /** Vanilla ground sprinting along +x from standing: d = d * 0.546 + 0.13. */
    private static double sprint(ReplayScenario s, int ticks, double start) {
        double d = start;
        for (int i = 0; i < ticks; i++) {
            d = d * 0.546 + 0.13;
            tick(s, d, 0, true);
        }
        return d;
    }

    @Test
    @DisplayName("vanilla sprinting and sprint jumping never exceed the bound")
    void legitSprintJumping() {
        ReplayScenario s = scenario(VanillaBlocks.STONE);
        double d = sprint(s, 20, 0.0);
        for (int jump = 0; jump < 5; jump++) {
            // friction after a tick follows the ground state at its start: 0.546 after the jump tick
            double friction = 0.546;
            for (int t = 0; t < JUMP.length; t++) {
                boolean ground = t == 0;
                d = d * friction + (ground ? 0.13 + 0.2 : 0.026);
                friction = ground ? 0.546 : 0.91;
                tick(s, d, JUMP[t], t == JUMP.length - 1);
            }
            d = sprint(s, 1, d / 0.546 * 0.91);
        }
        assertEquals(0, check(s).limitFailures());
    }

    @Test
    @DisplayName("ice keeps its momentum legally")
    void ice() {
        ReplayScenario s = scenario(VanillaBlocks.ICE);
        double d = 0.0;
        for (int i = 0; i < 40; i++) {
            d = d * 0.98 * 0.91 + 0.13 * 0.216 / (0.98 * 0.98 * 0.98);
            tick(s, d, 0, true);
        }
        assertEquals(0, check(s).limitFailures());
    }

    @Test
    @DisplayName("ground speed above the bound flags")
    void blatantSpeed() {
        ReplayScenario s = scenario(VanillaBlocks.STONE);
        sprint(s, 10, 0.0);
        for (int i = 0; i < 10; i++) {
            tick(s, 0.45, 0, true);
        }
        assertTrue(check(s).limitFailures() >= 9);
        assertTrue(check(s).violationCount() > 0);
    }

    @Test
    @DisplayName("air speed (strafe hacks) above the bound flags")
    void airSpeed() {
        ReplayScenario s = scenario(VanillaBlocks.STONE);
        sprint(s, 10, 0.0);
        tick(s, 0.5, 0.42, false);
        tick(s, 0.5, 0.3332, false);
        tick(s, 0.5, 0.2486, false);
        assertTrue(check(s).limitFailures() >= 2);
    }

    @Test
    @DisplayName("a pending server velocity excuses the knockback tick")
    void knockback() {
        ReplayScenario s = scenario(VanillaBlocks.STONE);
        sprint(s, 10, 0.0);
        VelocityState velocity = s.velocity();
        long id = velocity.register(VelocityState.Kind.SET, 0.9, 0.0, 0.0, s.player.movement().worldEpoch());
        velocity.markFirstAck(id);
        velocity.markSecondAck(id, s.player.clientTicks());
        tick(s, 0.9, 0, true);
        tick(s, 0.9 * 0.546 + 0.13, 0, true);
        assertEquals(0, check(s).limitFailures());
    }
}
