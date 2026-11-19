package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JesusCheck (standing on water/lava)")
class JesusCheckTest {

    /** Liquid layer at y=63, feet resting at the block boundary y=64. */
    private static final int LIQUID_Y = 63;
    private static final double SURFACE = 64.0;

    private static final BlockKey LILY_PAD = new BlockKey("minecraft:lily_pad");

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final WorldMirror world;
        final JesusCheck check;
        double x = 0.5;
        double y = SURFACE;
        double z = 0.5;
        private boolean ground;

        Fixture() {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new JesusChecksModule(simulation)).build();
            player = harness.newPlayer();
            world = player.component(simulation.worldMirrorKey());
            check = player.checks().get(JesusCheck.class);
            // 9x9 pool of water with two air layers above and stone below.
            for (int bx = -4; bx <= 4; bx++) {
                for (int bz = -4; bz <= 4; bz++) {
                    set(bx, LIQUID_Y - 3, bz, VanillaBlocks.STONE);
                    set(bx, LIQUID_Y - 2, bz, VanillaBlocks.WATER_BLOCK);
                    set(bx, LIQUID_Y - 1, bz, VanillaBlocks.WATER_BLOCK);
                    set(bx, LIQUID_Y, bz, VanillaBlocks.WATER_BLOCK);
                    set(bx, LIQUID_Y + 1, bz, VanillaBlocks.AIR);
                    set(bx, LIQUID_Y + 2, bz, VanillaBlocks.AIR);
                }
            }
        }

        void set(int bx, int by, int bz, BlockKey key) {
            world.setBlock(new BlockPos(bx, by, bz), key);
        }

        void move(double dx, double dy, double dz, boolean claimedGround) {
            MovementUpdate update = player.movementUpdate();
            update.assign(x, y, z, x + dx, y + dy, z + dz, ground, claimedGround, false,
                    true, false, false, 1, harness.time.nanoTime());
            x += dx;
            y += dy;
            z += dz;
            ground = claimedGround;
            player.checks().dispatchPosition(update);
        }

        void rest(int ticks, boolean claimedGround) {
            for (int i = 0; i < ticks; i++) {
                move(0, 0, 0, claimedGround);
            }
        }

        void options(Map<String, ?> options) {
            harness.applySettings(player, d -> {
                CheckSettings base = CheckSettings.of(d.defaults(), List.of());
                return new CheckSettings(base.enabled(), base.bufferLimit(), base.bufferDecay(), base.bufferMax(),
                        base.vlDecayPerMinute(), base.alertVl(), base.setbackVl(), base.actions(),
                        new CheckOptions(options));
            });
        }

        int flags() {
            return check.violationCount();
        }
    }

    @Test
    @DisplayName("descriptor: jesus, MOVEMENT, experimental, registered by the module")
    void descriptor() {
        Fixture f = new Fixture();
        assertNotNull(f.check);
        assertEquals("jesus", JesusCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, JesusCheck.DESCRIPTOR.category());
        assertTrue(JesusCheck.DESCRIPTOR.experimental());
    }

    @Test
    @DisplayName("cheat: standing still on water with ground=true flags")
    void standOnWater() {
        Fixture f = new Fixture();
        f.rest(30, true);
        assertTrue(f.flags() >= 1);
        String details = f.harness.alerts.alerts().get(0).details();
        assertTrue(details.startsWith("on-liquid ticks="), details);
    }

    @Test
    @DisplayName("cheat: walking on lava with ground=false and flat dy flags")
    void walkOnLava() {
        Fixture f = new Fixture();
        for (int bx = -4; bx <= 4; bx++) {
            for (int bz = -4; bz <= 4; bz++) {
                f.set(bx, LIQUID_Y, bz, VanillaBlocks.LAVA_BLOCK);
            }
        }
        f.x = 0.0;
        for (int i = 0; i < 30; i++) {
            f.move(0.05, 0, 0, false);
        }
        assertTrue(f.flags() >= 1);
    }

    @Test
    @DisplayName("cheat: feet at the 8/9 source surface (0.111 below the boundary) flag too")
    void sourceSurfaceHeight() {
        Fixture f = new Fixture();
        f.y = SURFACE - 1.0 / 9.0;
        f.rest(30, true);
        assertTrue(f.flags() >= 1);
    }

    @Test
    @DisplayName("cheat: spoofed ground with a small jittering step still counts")
    void spoofedGroundTinyDy() {
        Fixture f = new Fixture();
        for (int i = 0; i < 30; i++) {
            f.move(0, i % 2 == 0 ? 0.03 : -0.03, 0, true);
        }
        assertTrue(f.flags() >= 1);
    }

    @Test
    @DisplayName("legit: fewer than min-ticks packets never raise the buffer")
    void shortRest() {
        Fixture f = new Fixture();
        f.rest(JesusCheck.DEFAULT_MIN_TICKS, true);
        assertEquals(0.0, f.check.buffer());
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: a real fall into water and sink (large dy) never flags")
    void fallingIntoWater() {
        Fixture f = new Fixture();
        f.y = 70.0;
        double vy = 0;
        for (int i = 0; i < 60; i++) {
            vy = (vy - 0.08) * 0.98;
            if (f.y + vy < SURFACE) {
                vy = Math.max(vy * 0.2, -0.05);
            }
            f.move(0, vy, 0, false);
            if (f.y < 62.5) {
                break;
            }
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: surface bobbing with alternating dy never flags")
    void bobbing() {
        Fixture f = new Fixture();
        f.y = SURFACE - 0.1;
        for (int i = 0; i < 80; i++) {
            f.move(0, i % 4 < 2 ? 0.04 : -0.04, 0, false);
            if (f.y > SURFACE + 0.04) {
                f.y = SURFACE - 0.1;
            }
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: standing on solid blocks at the liquid layer never flags")
    void solidGround() {
        Fixture f = new Fixture();
        for (BlockKey key : new BlockKey[]{VanillaBlocks.ICE, VanillaBlocks.STONE, VanillaBlocks.PACKED_ICE}) {
            for (int bx = -4; bx <= 4; bx++) {
                for (int bz = -4; bz <= 4; bz++) {
                    f.set(bx, LIQUID_Y, bz, key);
                }
            }
            f.rest(40, true);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: a solid column under the hitbox edge (shore) skips the tick")
    void shoreEdge() {
        Fixture f = new Fixture();
        f.set(1, LIQUID_Y, 0, VanillaBlocks.STONE);
        // Hitbox spans x 0.65..1.25 (+ margin): reaches over the stone block at x=1.
        f.x = 0.95;
        f.z = 0.5;
        f.rest(40, true);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: lily pad, bubble column and unknown shapes are uncertain: skipped")
    void uncertainBlocks() {
        Fixture f = new Fixture();
        f.set(0, LIQUID_Y + 1, 0, LILY_PAD);
        f.rest(40, true);
        assertEquals(0, f.flags(), "lily pad in the feet cell");

        f.set(0, LIQUID_Y + 1, 0, VanillaBlocks.AIR);
        f.set(0, LIQUID_Y, 0, VanillaBlocks.BUBBLE_COLUMN);
        f.rest(40, true);
        assertEquals(0, f.flags(), "bubble column under the feet");

        f.set(0, LIQUID_Y, 0, new BlockKey("minecraft:oak_slab"));
        f.rest(40, true);
        assertEquals(0, f.flags(), "unknown/complex shape");
    }

    @Test
    @DisplayName("legit: unknown chunk data skips")
    void unknownWorld() {
        Fixture f = new Fixture();
        f.x = 500.5;
        f.rest(40, true);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: deep water (liquid also in the feet cell) is not surface standing")
    void deepWater() {
        Fixture f = new Fixture();
        f.set(0, LIQUID_Y + 1, 0, VanillaBlocks.WATER_BLOCK);
        f.rest(40, true);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: pending block change (frost walker ice not yet acknowledged) skips")
    void pendingChange() {
        Fixture f = new Fixture();
        f.world.pending().markBlock(0, LIQUID_Y, 0);
        f.rest(40, true);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("legit: creative, spectator, flying, gliding, swimming, levitation, slow falling")
    void stateExemptions() {
        Fixture creative = new Fixture();
        creative.player.abilities().applyGameMode(GameMode.CREATIVE);
        creative.rest(40, true);
        assertEquals(0, creative.flags());

        Fixture spectator = new Fixture();
        spectator.player.abilities().applyGameMode(GameMode.SPECTATOR);
        spectator.rest(40, true);
        assertEquals(0, spectator.flags());

        Fixture flying = new Fixture();
        flying.player.abilities().applyAbilities(true, true, false, false, 0.05f, 0.1f);
        flying.rest(40, true);
        assertEquals(0, flying.flags());

        Fixture gliding = new Fixture();
        gliding.player.pose().gliding(true);
        gliding.rest(40, true);
        assertEquals(0, gliding.flags());

        Fixture swimming = new Fixture();
        swimming.player.pose().swimming(true);
        swimming.rest(40, true);
        assertEquals(0, swimming.flags());

        Fixture levitation = new Fixture();
        levitation.player.effects().apply(TrackedEffect.LEVITATION, 0);
        levitation.rest(40, true);
        assertEquals(0, levitation.flags());

        Fixture slowFall = new Fixture();
        slowFall.player.effects().apply(TrackedEffect.SLOW_FALLING, 0);
        slowFall.rest(40, true);
        assertEquals(0, slowFall.flags());
    }

    @Test
    @DisplayName("legit: vehicle, riptide, join exemptions and custom gravity")
    void contextExemptions() {
        Fixture vehicle = new Fixture();
        vehicle.player.vehicle().mount(42);
        vehicle.rest(40, true);
        assertEquals(0, vehicle.flags());

        Fixture riptide = new Fixture();
        riptide.player.exemptions().grant(ExemptionCause.RIPTIDE, 100);
        riptide.rest(40, true);
        assertEquals(0, riptide.flags());

        Fixture joined = new Fixture();
        joined.player.exemptions().grant(ExemptionCause.JOIN, 100);
        joined.rest(40, true);
        assertEquals(0, joined.flags());

        Fixture gravity = new Fixture();
        gravity.player.attributes().gravity(0.0);
        gravity.rest(40, true);
        assertEquals(0, gravity.flags());
    }

    @Test
    @DisplayName("an exemption in the middle resets the streak")
    void exemptionResetsStreak() {
        Fixture f = new Fixture();
        f.rest(JesusCheck.DEFAULT_MIN_TICKS, true);
        f.player.exemptions().grant(ExemptionCause.TELEPORT, 1);
        f.rest(1, true);
        assertEquals(0, f.check.streak());
    }

    @Test
    @DisplayName("feet clearly above the surface (boat/entity top) are outside the window")
    void aboveSurface() {
        Fixture f = new Fixture();
        f.y = SURFACE + 0.3;
        f.rest(40, true);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("options: min-ticks is validated and honoured")
    void options() {
        Fixture f = new Fixture();
        f.options(Map.of("min-ticks", 2));
        f.rest(5, true);
        assertTrue(f.check.buffer() > 0.0);

        Fixture bad = new Fixture();
        bad.options(Map.of("min-ticks", -5, "flat-delta", -1.0));
        bad.rest(JesusCheck.DEFAULT_MIN_TICKS, true);
        assertEquals(0.0, bad.check.buffer());
    }

    @Test
    @DisplayName("a legit tick lowers the buffer again and resets the streak")
    void bufferDecays() {
        Fixture f = new Fixture();
        f.rest(JesusCheck.DEFAULT_MIN_TICKS + 2, true);
        double raised = f.check.buffer();
        assertTrue(raised > 0.0);
        f.move(0, -0.5, 0, false);
        assertTrue(f.check.buffer() < raised);
        assertEquals(0, f.check.streak());
    }
}
