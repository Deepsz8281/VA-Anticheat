package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PhaseCheck (moving into / through solid full cubes)")
class PhaseCheckTest {

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final WorldMirror world;
        final PhaseCheck check;

        /**
         * Stone floor at y=63 over x,z in [-3, 3], air above up to y=70, a stone wall at x=2
         * (y 64..66) and a stone ceiling at y=67.
         */
        Fixture() {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new MovementChecksModule(simulation)).build();
            player = harness.newPlayer();
            world = player.component(simulation.worldMirrorKey());
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int y = 60; y <= 70; y++) {
                        BlockKey key = y <= 63 || y == 67 || (x == 2 && y <= 66) ? VanillaBlocks.STONE : VanillaBlocks.AIR;
                        set(x, y, z, key);
                    }
                }
            }
            check = player.checks().get(PhaseCheck.class);
        }

        void set(int x, int y, int z, BlockKey key) {
            world.setBlock(new BlockPos(x, y, z), key);
        }

        void move(double fx, double fy, double fz, double tx, double ty, double tz) {
            move(fx, fy, fz, tx, ty, tz, 1);
        }

        void move(double fx, double fy, double fz, double tx, double ty, double tz, int ticks) {
            harness.time.advanceMillis(50);
            MovementUpdate update = player.movementUpdate();
            update.assign(fx, fy, fz, tx, ty, tz, true, true, false, true, false, false, ticks,
                    harness.time.nanoTime());
            player.checks().dispatchPosition(update);
        }
    }

    @Test
    @DisplayName("descriptor: id phase, MOVEMENT, experimental")
    void descriptor() {
        Fixture f = new Fixture();
        assertEquals("phase", PhaseCheck.DESCRIPTOR.id());
        assertTrue(PhaseCheck.DESCRIPTOR.experimental());
        assertTrue(f.check != null);
    }

    @Test
    @DisplayName("legit: walking on the floor, up to the wall, jumping under the ceiling")
    void legit() {
        Fixture f = new Fixture();
        double x = -1.0;
        for (int i = 0; i < 10; i++) {
            double next = Math.min(x + 0.28, 1.7);
            f.move(x, 64.0, 0.5, next, 64.0, 0.5);
            x = next;
        }
        // standing flush against the wall (box edge at x = 2.0)
        f.move(1.7, 64.0, 0.5, 1.7, 64.0, 0.5);
        // jump with the head bumping the ceiling at y=67 (box top 64 + 1.8 < 67 anyway)
        f.move(0.0, 64.0, 0.5, 0.0, 64.42, 0.5);
        f.move(0.0, 64.42, 0.5, 0.0, 64.75, 0.5);
        f.move(0.0, 64.75, 0.5, 0.0, 64.0, 0.5);
        // diagonal move past the wall's corner region along z
        f.move(1.5, 64.0, -2.5, 1.6, 64.0, 2.5);
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("cheat: horizontal clip through the wall in one tick flags immediately (sweep)")
    void horizontalClip() {
        Fixture f = new Fixture();
        f.move(1.6, 64.0, 0.5, 3.5, 64.0, 0.5);
        assertEquals(1, f.check.violationCount());
    }

    @Test
    @DisplayName("cheat: vclip down through the floor flags")
    void vclipDown() {
        Fixture f = new Fixture();
        f.set(0, 61, 0, VanillaBlocks.AIR);
        f.set(0, 62, 0, VanillaBlocks.AIR);
        // feet from 64 into the air pocket below (62.. box shrunk); floor block 63 in between
        f.move(0.5, 64.0, 0.5, 0.5, 61.0, 0.5);
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: walking into the wall and staying inside keeps flagging")
    void insideWall() {
        Fixture f = new Fixture();
        f.move(1.6, 64.0, 0.5, 2.2, 64.0, 0.5);
        f.move(2.2, 64.0, 0.5, 2.5, 64.0, 0.5);
        f.move(2.5, 64.0, 0.5, 2.5, 64.0, 0.5);
        assertTrue(f.check.violationCount() >= 1);
        assertTrue(f.check.clipped());
    }

    @Test
    @DisplayName("not judged: a block that appeared around the player (start inside)")
    void pushOut() {
        Fixture f = new Fixture();
        f.set(0, 64, 0, VanillaBlocks.STONE);
        f.move(0.5, 64.0, 0.5, 0.5, 64.0, 0.9);
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("not judged: pending world window, unknown blocks, recent own dig, teleport")
    void notJudged() {
        Fixture f = new Fixture();
        f.world.pending().markBlock(2, 64, 0);
        f.move(1.6, 64.0, 0.5, 3.5, 64.0, 0.5);
        assertEquals(0, f.check.violationCount());

        Fixture unknown = new Fixture();
        unknown.world.clearChunk(0, 0);
        unknown.move(1.6, 64.0, 0.5, 3.5, 64.0, 0.5);
        assertEquals(0, unknown.check.violationCount());

        Fixture dug = new Fixture();
        dug.player.interaction().assignDig(dev.vaplus.anticheat.world.BlockInteraction.Kind.DIG_START,
                2, 64, 0, BlockFace.WEST, 0, dug.harness.time.nanoTime());
        dug.player.checks().dispatchInteraction(dug.player.interaction());
        dug.move(1.6, 64.0, 0.5, 2.5, 64.0, 0.5);
        assertEquals(0, dug.check.violationCount());

        Fixture placed = new Fixture();
        placed.player.interaction().assignPlace(2, 64, 0, BlockFace.WEST, 0f, 0.5f, 0.5f,
                InteractionHand.MAIN_HAND, 0, false, placed.harness.time.nanoTime());
        placed.player.checks().dispatchInteraction(placed.player.interaction());
        placed.move(1.6, 64.0, 0.5, 2.5, 64.0, 0.5);
        assertEquals(0, placed.check.violationCount());

        Fixture teleport = new Fixture();
        MovementUpdate update = teleport.player.movementUpdate();
        update.assign(0.5, 64.0, 0.5, 2.5, 64.0, 0.5, true, true, false, true, false, false, 1,
                teleport.harness.time.nanoTime());
        update.markTeleport();
        teleport.player.checks().dispatchPosition(update);
        assertEquals(0, teleport.check.violationCount());
    }

    @Test
    @DisplayName("multi-tick packets are only overlap-tested")
    void multiTick() {
        Fixture f = new Fixture();
        f.move(1.6, 64.0, 0.5, 3.5, 64.0, 0.5, 3);
        assertEquals(0, f.check.violationCount(), "a curved multi-tick path may go around the wall");
        f.move(1.6, 64.0, 0.5, 2.4, 64.0, 0.5, 3);
        assertEquals(1.0, f.check.buffer(), "ending inside the wall still counts (one overlap fills the buffer)");
        f.move(2.4, 64.0, 0.5, 2.6, 64.0, 0.5, 3);
        assertTrue(f.check.violationCount() >= 1, "staying inside flags");
    }
}
