package dev.vaplus.anticheat.world;

import dev.vaplus.anticheat.world.BlockSight.Occlusion;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BlockSight (line of sight and ray/box geometry)")
class BlockSightTest {

    private static final double EPS = 1e-9;
    private static final BlockKey GLASS = new BlockKey("minecraft:glass");
    private static final BlockKey DIAMOND_ORE = new BlockKey("minecraft:diamond_ore");

    private static void set(WorldMirror mirror, int x, int y, int z, BlockKey key) {
        mirror.setBlock(new BlockPos(x, y, z), key);
    }

    /** Fills a box with air so the ray does not leave mirrored data. */
    private static void air(WorldMirror mirror, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    set(mirror, x, y, z, VanillaBlocks.AIR);
                }
            }
        }
    }

    @Test
    @DisplayName("an opaque block between the two points reports OCCLUDED, without it VISIBLE")
    void wallAndOpenPath() {
        WorldMirror mirror = new WorldMirror();
        air(mirror, 0, 65, 0, 1, 65, 12);
        assertEquals(Occlusion.VISIBLE, BlockSight.lineOfSight(mirror, 0.5, 65.6, 0.5, 0.5, 65.0, 10.5));

        set(mirror, 0, 65, 5, VanillaBlocks.STONE);
        assertEquals(Occlusion.OCCLUDED, BlockSight.lineOfSight(mirror, 0.5, 65.6, 0.5, 0.5, 65.0, 10.5));
    }

    @Test
    @DisplayName("a wall beside the ray does not occlude")
    void wallBesideTheRay() {
        WorldMirror mirror = new WorldMirror();
        air(mirror, 0, 65, 0, 4, 65, 12);
        set(mirror, 3, 65, 5, VanillaBlocks.STONE);
        assertEquals(Occlusion.VISIBLE, BlockSight.lineOfSight(mirror, 0.5, 65.5, 0.5, 0.5, 65.5, 10.5));
    }

    @Test
    @DisplayName("non-mirrored blocks on the path make the answer UNKNOWN, never OCCLUDED or VISIBLE")
    void unknownPath() {
        WorldMirror mirror = new WorldMirror();
        air(mirror, 0, 65, 0, 0, 65, 3);
        // everything from z=4 onwards is not mirrored
        assertEquals(Occlusion.UNKNOWN, BlockSight.lineOfSight(mirror, 0.5, 65.5, 0.5, 0.5, 65.5, 10.5));
    }

    @Test
    @DisplayName("see-through blocks do not occlude")
    void seeThrough() {
        WorldMirror mirror = new WorldMirror();
        air(mirror, 0, 65, 0, 0, 65, 12);
        set(mirror, 0, 65, 5, GLASS);
        set(mirror, 0, 65, 7, VanillaBlocks.WATER_BLOCK);
        assertEquals(Occlusion.VISIBLE, BlockSight.lineOfSight(mirror, 0.5, 65.5, 0.5, 0.5, 65.5, 10.5));
    }

    @Test
    @DisplayName("a target inside the margin is treated as visible")
    void veryClose() {
        WorldMirror mirror = new WorldMirror();
        assertEquals(Occlusion.VISIBLE, BlockSight.lineOfSight(mirror, 0.5, 65.5, 0.5, 0.5, 65.5, 0.7),
                "too close for a wall to be in between");
    }

    @Test
    @DisplayName("obscures: unknown, empty-collision, fluids and see-through blocks do not hide anything")
    void obscures() {
        assertFalse(BlockSight.obscures(BlockSnapshot.UNKNOWN));
        assertFalse(BlockSight.obscures(new BlockSnapshot(VanillaBlocks.AIR,
                VanillaBlocks.properties(VanillaBlocks.AIR), true)));
        assertFalse(BlockSight.obscures(new BlockSnapshot(GLASS, VanillaBlocks.DEFAULT_PROPERTIES, true)));
        assertTrue(BlockSight.obscures(new BlockSnapshot(DIAMOND_ORE, VanillaBlocks.DEFAULT_PROPERTIES, true)),
                "ores hide what is behind them");
        assertTrue(BlockSight.obscures(new BlockSnapshot(VanillaBlocks.STONE, VanillaBlocks.DEFAULT_PROPERTIES, true)));
        assertFalse(BlockSight.obscures(new BlockSnapshot(VanillaBlocks.WATER_BLOCK,
                VanillaBlocks.properties(VanillaBlocks.WATER_BLOCK), true)));
    }

    @Test
    @DisplayName("rayBoxEntry: straight hit, miss, inside and behind the camera")
    void rayBoxEntry() {
        assertEquals(3.5, BlockSight.rayBoxEntry(0.5, 64.5, 0.5, 0, 0, 1,
                0, 64, 4, 1, 65, 5), EPS);
        assertTrue(Double.isNaN(BlockSight.rayBoxEntry(0.5, 64.5, 0.5, 0, 0, -1,
                0, 64, 4, 1, 65, 5)), "looking away from the box");
        assertEquals(0.0, BlockSight.rayBoxEntry(0.5, 64.5, 4.5, 0, 0, 1,
                0, 64, 4, 1, 65, 5), EPS, "origin inside the box");
        assertTrue(Double.isNaN(BlockSight.rayBoxEntry(0.5, 64.5, 0.5, 0, 0, 1,
                0, 64, -5, 1, 65, -4)), "box behind the origin");
    }

    @Test
    @DisplayName("rayBoxEntry handles diagonal rays and grazing corners")
    void rayBoxDiagonals() {
        double entry = BlockSight.rayBoxEntry(0.5, 64.5, 0.5, 0.6, 0, 0.8,
                4, 64, 5, 5, 65, 6);
        assertEquals(3.5 / 0.6, entry, 1e-6, "entry through the x face of the box");

        double grazing = BlockSight.rayBoxEntry(0.5, 64.5, 0.5, 0, 0, 1,
                1.5, 64, 4, 2.5, 65, 5);
        assertTrue(Double.isNaN(grazing), "a ray passing beside the box misses it");
    }
}
