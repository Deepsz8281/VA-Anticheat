package dev.vaplus.anticheat.world.mirror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorldMirror dense columns (feeder path)")
class WorldMirrorColumnTest {

    static final int AIR = 0;
    static final int STONE = 1;
    static final int ICE = 2;
    static final BlockSnapshot AIR_BLOCK = new BlockSnapshot(VanillaBlocks.AIR, VanillaBlocks.properties(VanillaBlocks.AIR), true);
    static final BlockSnapshot STONE_BLOCK = new BlockSnapshot(VanillaBlocks.STONE, VanillaBlocks.DEFAULT_PROPERTIES, true);
    static final BlockSnapshot ICE_BLOCK = new BlockSnapshot(VanillaBlocks.ICE, VanillaBlocks.properties(VanillaBlocks.ICE), true);

    /** 0 = air, 1 = stone, 2 = ice, anything else unknown. */
    static final BlockStateResolver RESOLVER = id -> switch (id) {
        case AIR -> AIR_BLOCK;
        case STONE -> STONE_BLOCK;
        case ICE -> ICE_BLOCK;
        default -> BlockSnapshot.UNKNOWN;
    };

    private static ArrayColumnStorage column() {
        return new ArrayColumnStorage(-64, 384).fillLayers(-64, 63, STONE).fillLayers(64, 100, AIR);
    }

    @Test
    @DisplayName("loaded columns resolve through the resolver; int and BlockPos lookups agree")
    void loadAndRead() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        long before = mirror.version();
        mirror.loadColumn(-1, 2, column());
        assertEquals(before + 1, mirror.version());
        assertTrue(mirror.hasChunk(-1, 2));
        assertSame(STONE_BLOCK, mirror.getBlock(-5, 63, 40));
        assertSame(AIR_BLOCK, mirror.getBlock(-5, 64, 40));
        assertEquals(mirror.getBlock(new BlockPos(-16, 63, 32)), mirror.getBlock(-16, 63, 32));
        assertEquals(STONE, mirror.stateId(-1, 0, 47));
        assertFalse(mirror.getBlock(-5, 101, 40).known(), "unfilled layer stays unknown");
        assertFalse(mirror.getBlock(-5, 400, 40).known(), "above the column");
        assertFalse(mirror.getBlock(0, 63, 40).known(), "neighbouring chunk not loaded");
        assertEquals(0, mirror.chunkSnapshot(-1, 2).orElseThrow().blocks().size(),
                "snapshots contain sparse entries only");
    }

    @Test
    @DisplayName("ids the resolver does not know read as UNKNOWN, never as air")
    void unknownIds() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        ArrayColumnStorage storage = column();
        storage.setStateId(3, 64, 3, 12345);
        mirror.loadColumn(0, 0, storage);
        BlockSnapshot block = mirror.getBlock(3, 64, 3);
        assertFalse(block.known());
        assertEquals(BlockKey.UNKNOWN, block.key());
        WorldMirror noResolver = new WorldMirror();
        noResolver.loadColumn(0, 0, column());
        assertFalse(noResolver.getBlock(3, 63, 3).known(), "the default resolver knows nothing");
    }

    @Test
    @DisplayName("setState applies inside a column and reports why it did not otherwise")
    void setState() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        mirror.loadColumn(0, 0, column());
        long version = mirror.version();
        assertEquals(WorldMirror.UpdateResult.APPLIED, mirror.setState(4, 63, 4, ICE));
        assertSame(ICE_BLOCK, mirror.getBlock(4, 63, 4));
        assertEquals(version + 1, mirror.version());
        assertEquals(WorldMirror.UpdateResult.NO_CHUNK, mirror.setState(40, 63, 4, ICE));
        assertEquals(WorldMirror.UpdateResult.OUT_OF_RANGE, mirror.setState(4, 500, 4, ICE));
        assertEquals(version + 1, mirror.version(), "rejected updates do not count");

        mirror.setBlock(new BlockPos(5, 63, 5), VanillaBlocks.SLIME_BLOCK);
        assertEquals(VanillaBlocks.SLIME_BLOCK, mirror.getBlock(5, 63, 5).key(), "sparse entries take precedence");
        assertEquals(WorldMirror.UpdateResult.APPLIED, mirror.setState(5, 63, 5, ICE));
        assertSame(ICE_BLOCK, mirror.getBlock(5, 63, 5), "a dense update replaces the sparse override");
    }

    @Test
    @DisplayName("a column that cannot store an id drops the chunk instead of keeping wrong data")
    void unrepresentableIdDropsChunk() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        mirror.loadColumn(0, 0, new RefusingColumn());
        mirror.pending().markChunk(0, 0);
        assertEquals(WorldMirror.UpdateResult.DROPPED, mirror.setState(1, 64, 1, ICE));
        assertFalse(mirror.hasChunk(0, 0));
        assertFalse(mirror.getBlock(1, 64, 1).known());
        assertEquals(0, mirror.pending().chunkCount(), "windows of a removed chunk are dropped");
    }

    @Test
    @DisplayName("sparse-only chunks have no column: dense updates are out of range")
    void sparseChunkHasNoColumn() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        mirror.setBlock(new BlockPos(1, 64, 1), VanillaBlocks.STONE);
        assertEquals(WorldMirror.UpdateResult.OUT_OF_RANGE, mirror.setState(1, 64, 1, ICE));
        assertEquals(ColumnStorage.UNKNOWN_STATE, mirror.stateId(1, 64, 1));
        assertEquals(VanillaBlocks.STONE, mirror.getBlock(1, 64, 1).key());
    }

    @Test
    @DisplayName("clear keeps a whole-world window but drops block and chunk windows")
    void clearKeepsWorldWindow() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        mirror.loadColumn(0, 0, column());
        mirror.pending().markChunk(0, 0);
        mirror.pending().markAll();
        mirror.clear();
        assertEquals(0, mirror.chunkCount());
        assertEquals(0, mirror.pending().chunkCount());
        assertTrue(mirror.pending().allPending());
        assertFalse(mirror.getBlock(1, 63, 1).known());
    }

    @Test
    @DisplayName("the primitive chunk index survives many inserts and removals, including negative coordinates")
    void chunkIndex() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        ArrayColumnStorage shared = new ArrayColumnStorage(0, 16).fillLayers(0, 15, STONE);
        Random random = new Random(42);
        Set<Long> present = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            int x = random.nextInt(81) - 40;
            int z = random.nextInt(81) - 40;
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            if (random.nextInt(3) == 0) {
                assertEquals(present.remove(key), mirror.clearChunk(x, z));
            } else {
                mirror.loadColumn(x, z, shared);
                present.add(key);
            }
        }
        assertEquals(present.size(), mirror.chunkCount());
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
                assertEquals(present.contains(key), mirror.hasChunk(x, z), x + "," + z);
                assertEquals(present.contains(key), mirror.getBlock(x * 16 + 7, 3, z * 16 + 9).known());
            }
        }
    }

    @Test
    @DisplayName("memory is capped: beyond MAX_CHUNKS the oldest chunk is evicted (reads UNKNOWN)")
    void chunkCap() {
        WorldMirror mirror = new WorldMirror(RESOLVER);
        ArrayColumnStorage shared = new ArrayColumnStorage(0, 16).fillLayers(0, 15, STONE);
        for (int i = 0; i < WorldMirror.MAX_CHUNKS; i++) {
            mirror.loadColumn(i, 0, shared);
        }
        assertEquals(0, mirror.evictions());
        mirror.loadColumn(-1, -1, shared);
        assertEquals(WorldMirror.MAX_CHUNKS, mirror.chunkCount());
        assertEquals(1, mirror.evictions());
        assertFalse(mirror.hasChunk(0, 0), "the oldest chunk went first");
        assertTrue(mirror.hasChunk(-1, -1));
        mirror.loadColumn(5, 0, shared);
        assertEquals(1, mirror.evictions(), "replacing an existing chunk evicts nothing");
        assertNotNull(mirror.getBlock(-16, 0, -16));
    }

    /** Column whose writes always fail (e.g. an id the section palette cannot hold). */
    private static final class RefusingColumn implements ColumnStorage {
        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int height() {
            return 256;
        }

        @Override
        public int stateId(int localX, int y, int localZ) {
            return STONE;
        }

        @Override
        public boolean setStateId(int localX, int y, int localZ, int stateId) {
            return false;
        }
    }
}
