package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpiderCheck (wall climbing without a climbable block)")
class SpiderCheckTest {

    private static final double[] JUMP_ARC = {0.42, 0.3332, 0.2477, 0.1649, 0.0830, 0.0023};

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final WorldMirror world;
        final SpiderCheck check;
        final SimulationModule simulation;
        double y = 64.0;
        boolean ground = true;

        /** Player at (0.7, 64, 0.5) beside a stone wall at x=1, stone floor at y=63, air elsewhere. */
        Fixture() {
            simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new SpiderChecksModule(simulation)).build();
            player = harness.newPlayer();
            world = player.component(simulation.worldMirrorKey());
            for (int x = 0; x <= 2; x++) {
                for (int z = 0; z <= 1; z++) {
                    for (int yy = 60; yy <= 90; yy++) {
                        BlockKey key = yy <= 63 || x == 1 ? VanillaBlocks.STONE : VanillaBlocks.AIR;
                        world.setBlock(new BlockPos(x, yy, z), key);
                    }
                }
            }
            check = player.checks().get(SpiderCheck.class);
        }

        void set(int x, int y, int z, BlockKey key) {
            world.setBlock(new BlockPos(x, y, z), key);
        }

        /** One movement packet with vertical delta {@code dy}. */
        void step(double dy, boolean toGround, boolean collision) {
            step(dy, toGround, collision, 1);
        }

        void step(double dy, boolean toGround, boolean collision, int elapsedTicks) {
            MovementUpdate update = player.movementUpdate();
            update.assign(0.7, y, 0.5, 0.7, y + dy, 0.5, ground, toGround, collision,
                    true, false, false, elapsedTicks, harness.time.nanoTime());
            y += dy;
            ground = toGround;
            player.checks().dispatchPosition(update);
        }

        /** Take-off from the ground followed by {@code ticks} constant-speed climbing ticks. */
        void climb(int ticks, double speed) {
            step(JUMP_ARC[0], false, true);
            for (int i = 0; i < ticks; i++) {
                step(speed, false, true);
            }
        }
    }

    @Test
    @DisplayName("descriptor: id spider, MOVEMENT")
    void descriptor() {
        Fixture f = new Fixture();
        assertNotNull(f.check);
        assertEquals("spider", SpiderCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, SpiderCheck.DESCRIPTOR.category());
        assertTrue(SpiderCheck.DESCRIPTOR.experimental());
        assertTrue(SpiderCheck.DESCRIPTOR.defaults().setbackVl() < 0);
    }

    @Test
    @DisplayName("climbing a plain stone wall at 0.2 per tick flags")
    void stoneWallFlags() {
        Fixture f = new Fixture();
        f.climb(12, 0.2);
        assertTrue(f.check.violationCount() >= 1, "violations=" + f.check.violationCount());
        String details = f.harness.alerts.alerts().get(0).details();
        assertTrue(details.startsWith("dy=0.2000"), details);
    }

    @Test
    @DisplayName("a short spider burst below min-ticks does not flag")
    void shortBurst() {
        Fixture f = new Fixture();
        f.climb(3, 0.2);
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("a full jump arc against a wall, repeated, never flags")
    void jumpAgainstWall() {
        Fixture f = new Fixture();
        for (int round = 0; round < 5; round++) {
            for (double dy : JUMP_ARC) {
                f.step(dy, false, true);
            }
            double fall = 0.0;
            for (int i = 0; i < 3; i++) {
                fall = (fall - 0.08) * 0.98;
                f.step(fall, false, true);
            }
            f.step(-0.1, true, true);
            f.y = 64.0;
        }
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("ladder, vines, scaffolding and a trapdoor in the body column are climbable")
    void climbables() {
        String[] keys = {"minecraft:ladder", "minecraft:vine", "minecraft:scaffolding",
                "minecraft:twisting_vines", "minecraft:weeping_vines_plant", "minecraft:cave_vines",
                "minecraft:powder_snow", "minecraft:oak_trapdoor"};
        for (String key : keys) {
            Fixture f = new Fixture();
            for (int yy = 64; yy <= 80; yy++) {
                f.set(0, yy, 0, new BlockKey(key));
            }
            f.climb(15, 0.2);
            assertEquals(0, f.check.violationCount(), key);
        }
    }

    @Test
    @DisplayName("water, slime, honey and bubble columns beside the player are not judged")
    void specialBlocks() {
        String[] keys = {"minecraft:water", "minecraft:slime_block", "minecraft:honey_block",
                "minecraft:bubble_column", "minecraft:piston", "minecraft:kelp_plant", "minecraft:seagrass",
                "minecraft:red_shulker_box", "minecraft:shulker_box", "minecraft:cobweb"};
        for (String key : keys) {
            Fixture f = new Fixture();
            for (int yy = 64; yy <= 80; yy++) {
                f.set(0, yy, 0, new BlockKey(key));
            }
            f.climb(15, 0.2);
            assertEquals(0, f.check.violationCount(), key);
        }
    }

    @Test
    @DisplayName("jump boost, levitation, slow falling are not judged")
    void effects() {
        for (TrackedEffect effect : new TrackedEffect[]{TrackedEffect.JUMP_BOOST, TrackedEffect.LEVITATION,
                TrackedEffect.SLOW_FALLING}) {
            Fixture f = new Fixture();
            f.player.effects().apply(effect, 1);
            f.climb(15, 0.2);
            assertEquals(0, f.check.violationCount(), effect.name());
        }
    }

    @Test
    @DisplayName("tracked velocity, creative mode, a declared exemption and a vehicle suppress judging")
    void stateExemptions() {
        Fixture velocity = new Fixture();
        velocity.player.component(velocity.simulation.velocityKey())
                .register(VelocityState.Kind.SET, 0, 0.5, 0, 0);
        velocity.climb(15, 0.2);
        assertEquals(0, velocity.check.violationCount(), "velocity");

        Fixture creative = new Fixture();
        creative.player.abilities().applyGameMode(GameMode.CREATIVE);
        creative.climb(15, 0.2);
        assertEquals(0, creative.check.violationCount(), "creative");

        Fixture exempt = new Fixture();
        exempt.player.exemptions().grant(ExemptionCause.KNOCKBACK, 100);
        exempt.climb(15, 0.2);
        assertEquals(0, exempt.check.violationCount(), "knockback exemption");

        Fixture vehicle = new Fixture();
        vehicle.player.vehicle().mount(5);
        vehicle.climb(15, 0.2);
        assertEquals(0, vehicle.check.violationCount(), "vehicle");
    }

    @Test
    @DisplayName("unknown world data and a missing collision flag never flag")
    void unknownAndNoCollision() {
        Fixture noCollision = new Fixture();
        noCollision.step(0.42, false, false);
        for (int i = 0; i < 15; i++) {
            noCollision.step(0.2, false, false);
        }
        assertEquals(0, noCollision.check.violationCount());

        Fixture unknown = new Fixture();
        unknown.world.clear();
        unknown.climb(15, 0.2);
        assertEquals(0, unknown.check.violationCount());
    }

    @Test
    @DisplayName("grounded step-ups (claimed ground with a floor below) do not count")
    void stepUps() {
        Fixture f = new Fixture();
        for (int i = 0; i < 10; i++) {
            f.set(0, 64 + i, 0, VanillaBlocks.STONE);
        }
        for (int i = 0; i < 10; i++) {
            f.step(0.5, true, true);
            f.step(0.0, true, true);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("claiming ground in mid-air (no block below) while climbing still flags")
    void spoofedGround() {
        Fixture f = new Fixture();
        f.step(0.42, false, true);
        for (int i = 0; i < 12; i++) {
            f.ground = false;
            f.step(0.2, true, true);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("a re-jump spider (fresh 0.42 arcs in mid-air against the wall) flags")
    void rejumpSpiderFlags() {
        Fixture f = new Fixture();
        for (int round = 0; round < 8; round++) {
            for (int i = 0; i < 5; i++) {
                f.step(JUMP_ARC[i], false, true);
            }
        }
        assertTrue(f.check.violationCount() >= 1, "violations=" + f.check.violationCount());
    }

    @Test
    @DisplayName("climb ticks below min-height never flag")
    void belowMinHeight() {
        Fixture f = new Fixture();
        f.step(0.0, true, true);
        f.step(0.1, false, true);
        for (int i = 0; i < 12; i++) {
            f.step(0.1, false, true);
        }
        assertTrue(f.check.climbTicks() >= SpiderCheck.DEFAULT_MIN_TICKS);
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("an unacknowledged block change in the scanned box is not judged; once acknowledged it is")
    void pendingWorldChange() {
        Fixture f = new Fixture();
        // The server just removed a ladder: the mirror shows air, the client may still climb it.
        f.world.pending().markBlock(0, 66, 0);
        f.climb(15, 0.1176);
        assertEquals(0, f.check.violationCount());
        assertEquals(0, f.check.climbTicks());

        Fixture far = new Fixture();
        far.world.pending().markBlock(0, 200, 0);
        far.climb(15, 0.2);
        assertTrue(far.check.violationCount() >= 1, "a pending change elsewhere must not silence the check");
    }

    @Test
    @DisplayName("leaving the top of a ladder with normal physics does not flag")
    void ladderTop() {
        Fixture f = new Fixture();
        for (int yy = 64; yy <= 67; yy++) {
            f.set(0, yy, 0, VanillaBlocks.LADDER);
        }
        f.step(0.1176, false, true);
        while (f.y < 68.0) {
            f.step(0.1176, false, true);
        }
        double dy = 0.1176;
        for (int i = 0; i < 12; i++) {
            dy = (dy - 0.08) * 0.98;
            f.step(dy, false, true);
        }
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("the grace period after water keeps a short rise unjudged")
    void graceAfterWater() {
        Fixture f = new Fixture();
        f.set(0, 64, 0, VanillaBlocks.WATER_BLOCK);
        f.step(0.0, false, true);
        f.step(0.3, false, true);
        f.step(0.3, false, true);
        f.set(0, 64, 0, VanillaBlocks.AIR);
        f.y = 70.0;
        f.step(0.2, false, true);
        for (int i = 0; i < 9; i++) {
            f.step(0.2, false, true);
        }
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("packets spanning several client ticks (lag) and teleports break the chain")
    void lagAndTeleport() {
        Fixture lag = new Fixture();
        lag.step(0.42, false, true);
        for (int i = 0; i < 20; i++) {
            lag.step(0.4, false, true, 2);
        }
        assertEquals(0, lag.check.violationCount());
        assertEquals(0.0, lag.check.buffer());

        Fixture alternating = new Fixture();
        alternating.step(0.42, false, true);
        for (int i = 0; i < 20; i++) {
            alternating.step(0.2, false, true, i % 2 == 0 ? 2 : 1);
        }
        assertEquals(0, alternating.check.violationCount());
    }

    @Test
    @DisplayName("isClimbTick: needs wall contact, a rise and an excess over the gravity continuation")
    void climbTickDecision() {
        double afterJump = (0.42 - 0.08) * 0.98;
        assertTrue(SpiderCheck.isClimbTick(true, 0.2, (0.2 - 0.08) * 0.98, 0.01, 0.04));
        assertTrue(!SpiderCheck.isClimbTick(false, 0.2, (0.2 - 0.08) * 0.98, 0.01, 0.04));
        assertTrue(!SpiderCheck.isClimbTick(true, 0.3332, afterJump, 0.01, 0.04));
        assertTrue(!SpiderCheck.isClimbTick(true, 0.005, -0.5, 0.01, 0.04));
        assertTrue(!SpiderCheck.isClimbTick(true, -0.05, -0.2, 0.01, 0.04));
        assertTrue(!SpiderCheck.isClimbTick(true, Double.NaN, 0.0, 0.01, 0.04));
    }

    private static Fixture withOptions(java.util.Map<String, ?> options) {
        Fixture f = new Fixture();
        f.harness.applySettings(f.player, d -> {
            dev.vaplus.anticheat.check.CheckSettings base =
                    dev.vaplus.anticheat.check.CheckSettings.of(d.defaults(), java.util.List.of());
            if (d != SpiderCheck.DESCRIPTOR) {
                return base;
            }
            return new dev.vaplus.anticheat.check.CheckSettings(base.enabled(), base.bufferLimit(),
                    base.bufferDecay(), base.bufferMax(), base.vlDecayPerMinute(), base.alertVl(),
                    base.setbackVl(), base.actions(), new dev.vaplus.anticheat.check.CheckOptions(options));
        });
        return f;
    }

    @Test
    @DisplayName("min-ticks option raises the requirement; invalid values fall back to the default")
    void options() {
        Fixture strict = withOptions(java.util.Map.of("min-ticks", 8, "min-height", 0.5));
        strict.climb(8, 0.2);
        assertEquals(0, strict.check.violationCount());
        assertEquals(7, strict.check.climbTicks()); // first 0.2 tick after the 0.42 take-off is below the arc

        Fixture invalid = withOptions(java.util.Map.of("min-ticks", -4, "excess-tolerance", -1.0));
        invalid.climb(12, 0.2);
        assertTrue(invalid.check.violationCount() >= 1);
    }
}
