package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GhostHandCheck (interactions through walls)")
class GhostHandCheckTest {

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final WorldMirror world;
        final GhostHandCheck check;

        /**
         * Air everywhere in x -3..6, y 60..70, z -3..3 except the floor (y 63). Player at
         * (0.5, 64, 0.5); target block (a chest stand-in: stone) at (4, 64, 0).
         */
        Fixture() {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new VisibilityChecksModule(simulation)).build();
            player = harness.newPlayer();
            world = player.component(simulation.worldMirrorKey());
            for (int x = -3; x <= 6; x++) {
                for (int y = 60; y <= 70; y++) {
                    for (int z = -3; z <= 3; z++) {
                        set(x, y, z, y <= 63 ? VanillaBlocks.STONE : VanillaBlocks.AIR);
                    }
                }
            }
            set(4, 64, 0, VanillaBlocks.STONE);
            set(4, 65, 0, VanillaBlocks.STONE);
            player.movement().applyPosition(0.5, 64.0, 0.5);
            player.movement().applyPosition(0.5, 64.0, 0.5);
            check = player.checks().get(GhostHandCheck.class);
        }

        void set(int x, int y, int z, BlockKey key) {
            world.setBlock(new BlockPos(x, y, z), key);
        }

        /** Wall of stone at x = 2 between player and target (y 64..66, z -3..3). */
        void wall() {
            for (int y = 64; y <= 66; y++) {
                for (int z = -3; z <= 3; z++) {
                    set(2, y, z, VanillaBlocks.STONE);
                }
            }
        }

        void use(int x, int y, int z, BlockFace face, float cx, float cy, float cz) {
            player.interaction().assignPlace(x, y, z, face, cx, cy, cz, InteractionHand.MAIN_HAND, 0, false,
                    harness.time.nanoTime());
            player.checks().dispatchInteraction(player.interaction());
        }

        void dig(BlockInteraction.Kind kind, int x, int y, int z, BlockFace face) {
            player.interaction().assignDig(kind, x, y, z, face, 0, harness.time.nanoTime());
            player.checks().dispatchInteraction(player.interaction());
        }
    }

    @Test
    @DisplayName("descriptor: id ghosthand, experimental")
    void descriptor() {
        Fixture f = new Fixture();
        assertEquals("ghosthand", GhostHandCheck.DESCRIPTOR.id());
        assertTrue(GhostHandCheck.DESCRIPTOR.experimental());
        assertTrue(f.check != null);
    }

    @Test
    @DisplayName("legit: open and break a block in plain sight")
    void inSight() {
        Fixture f = new Fixture();
        for (int i = 0; i < 5; i++) {
            f.use(4, 64, 0, BlockFace.WEST, 0.0f, 0.6f, 0.5f);
            f.dig(BlockInteraction.Kind.DIG_START, 4, 65, 0, BlockFace.WEST);
        }
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("cheat: using the target through a full wall flags")
    void throughWallUse() {
        Fixture f = new Fixture();
        f.wall();
        for (int i = 0; i < 4; i++) {
            f.use(4, 64, 0, BlockFace.WEST, 0.0f, 0.6f, 0.5f);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: starting to break the target through a full wall flags")
    void throughWallDig() {
        Fixture f = new Fixture();
        f.wall();
        for (int i = 0; i < 4; i++) {
            f.dig(BlockInteraction.Kind.DIG_START, 4, 65, 0, BlockFace.WEST);
            f.harness.time.advanceSeconds(5);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("see-through and non-cube walls (glass, unknown) are never walls")
    void notWalls() {
        Fixture f = new Fixture();
        for (int y = 64; y <= 66; y++) {
            for (int z = -3; z <= 3; z++) {
                f.set(2, y, z, VanillaBlocks.GLASS);
            }
        }
        for (int i = 0; i < 4; i++) {
            f.use(4, 64, 0, BlockFace.WEST, 0.0f, 0.6f, 0.5f);
        }
        assertEquals(0, f.check.violationCount());

        Fixture unknown = new Fixture();
        unknown.world.clearChunk(0, 0);
        for (int i = 0; i < 4; i++) {
            unknown.use(4, 64, 0, BlockFace.WEST, 0.0f, 0.6f, 0.5f);
        }
        assertEquals(0, unknown.check.violationCount());
    }

    @Test
    @DisplayName("not judged: own recent dig in the wall, pending block change")
    void recentDigAndPending() {
        Fixture f = new Fixture();
        f.wall();
        f.dig(BlockInteraction.Kind.DIG_START, 2, 65, 0, BlockFace.WEST);
        f.dig(BlockInteraction.Kind.DIG_FINISH, 2, 65, 0, BlockFace.WEST);
        for (int i = 0; i < 4; i++) {
            f.use(4, 64, 0, BlockFace.WEST, 0.0f, 0.6f, 0.5f);
        }
        assertEquals(0.0, f.check.buffer());

        Fixture pending = new Fixture();
        pending.wall();
        pending.world.pending().markBlock(2, 64, 0);
        for (int i = 0; i < 4; i++) {
            pending.use(4, 64, 0, BlockFace.WEST, 0.0f, 0.6f, 0.5f);
        }
        assertEquals(0.0, pending.check.buffer());
    }

    @Test
    @DisplayName("segment: the target block itself and the eye's cell are not walls")
    void segment() {
        Fixture f = new Fixture();
        // the ray ends on the target's west face: entering the target cell is not a wall
        assertEquals(GhostHandCheck.VISIBLE,
                GhostHandCheck.segment(f.world, 0.5, 65.62, 0.5, 4.0, 64.6, 0.5, 4, 64, 0));
        f.wall();
        assertEquals(GhostHandCheck.OCCLUDED,
                GhostHandCheck.segment(f.world, 0.5, 65.62, 0.5, 4.0, 64.6, 0.5, 4, 64, 0));
    }
}
