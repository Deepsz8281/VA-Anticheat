package dev.vaplus.anticheat.world.mirror;

import dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid;
import dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChunkMirror (sparse block mirror for one chunk)")
class ChunkMirrorTest {

    private static ChunkMirror chunk() {
        return new ChunkMirror(3, -7);
    }

    @Test
    @DisplayName("coordinates are kept; a fresh chunk is empty and queries return UNKNOWN")
    void freshChunk() {
        ChunkMirror chunk = chunk();
        assertEquals(3, chunk.chunkX());
        assertEquals(-7, chunk.chunkZ());
        assertEquals(0, chunk.blockCount());
        assertFalse(chunk.getBlock(new BlockPos(50, 64, -100)).known());
        assertEquals(BlockSnapshot.UNKNOWN, chunk.getBlock(new BlockPos(50, 64, -100)));
    }

    @Test
    @DisplayName("setBlock stores the key and default properties; explicit air is retained as known air")
    void setBlock() {
        ChunkMirror chunk = chunk();
        chunk.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        chunk.setBlock(new BlockPos(50, 65, -110), VanillaBlocks.AIR);

        BlockSnapshot stone = chunk.getBlock(new BlockPos(50, 64, -110));
        assertTrue(stone.known());
        assertEquals(VanillaBlocks.STONE, stone.key());
        assertEquals(VanillaBlocks.DEFAULT_PROPERTIES, stone.properties());

        BlockSnapshot air = chunk.getBlock(new BlockPos(50, 65, -110));
        assertTrue(air.known(), "explicit air must be distinguishable from never-fed positions");
        assertEquals(VanillaBlocks.AIR, air.key());
        assertEquals(2, chunk.blockCount());
    }

    @Test
    @DisplayName("setBlock accepts state-aware metadata overrides, e.g. a downward bubble column")
    void stateAwareOverride() {
        ChunkMirror chunk = chunk();
        chunk.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.BUBBLE_COLUMN,
                VanillaBlocks.BUBBLE_COLUMN_DOWN_PROPERTIES);

        BlockSnapshot snapshot = chunk.getBlock(new BlockPos(50, 64, -110));
        assertEquals(VanillaBlocks.BUBBLE_COLUMN, snapshot.key());
        assertTrue(snapshot.properties().has(SpecialFlag.BUBBLE_COLUMN_DOWN));
        assertEquals(Liquid.WATER, snapshot.properties().liquid());
    }

    @Test
    @DisplayName("positions from a different chunk are rejected")
    void foreignPositionsRejected() {
        ChunkMirror chunk = chunk();
        assertThrows(IllegalArgumentException.class,
                () -> chunk.setBlock(new BlockPos(16, 64, -100), VanillaBlocks.STONE),
                "x 16 is in chunk 4");
        assertThrows(IllegalArgumentException.class,
                () -> chunk.setBlock(new BlockPos(50, 64, -113), VanillaBlocks.STONE),
                "z -113 is in chunk -8");
        assertThrows(IllegalArgumentException.class,
                () -> chunk.getBlock(new BlockPos(47, 64, -96)),
                "reading outside the chunk is also rejected");
        assertThrows(NullPointerException.class, () -> chunk.setBlock(null, VanillaBlocks.STONE));
    }

    @Test
    @DisplayName("overwrites replace the previous entry; clear empties the chunk")
    void overwriteAndClear() {
        ChunkMirror chunk = chunk();
        BlockPos position = new BlockPos(50, 64, -110);
        chunk.setBlock(position, VanillaBlocks.STONE);
        chunk.setBlock(position, VanillaBlocks.SLIME_BLOCK);
        assertEquals(1, chunk.blockCount());
        assertEquals(VanillaBlocks.SLIME_BLOCK, chunk.getBlock(position).key());

        chunk.clear();
        assertEquals(0, chunk.blockCount());
        assertFalse(chunk.getBlock(position).known());
    }

    @Test
    @DisplayName("the snapshot is detached: later writes do not change it, and the map is unmodifiable")
    void detachedSnapshot() {
        ChunkMirror chunk = chunk();
        chunk.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        ChunkMirror.Snapshot snapshot = chunk.snapshot();

        chunk.setBlock(new BlockPos(50, 65, -110), VanillaBlocks.SLIME_BLOCK);
        assertEquals(1, snapshot.blocks().size(), "the snapshot must not see later writes");
        assertEquals(VanillaBlocks.STONE, snapshot.blocks().get(new BlockPos(50, 64, -110)).key());
        assertEquals(3, snapshot.chunkX());
        assertEquals(-7, snapshot.chunkZ());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.blocks().put(new BlockPos(50, 65, -110),
                        new BlockSnapshot(VanillaBlocks.SLIME_BLOCK, VanillaBlocks.DEFAULT_PROPERTIES, true)));
    }

    @Test
    @DisplayName("the snapshot stores map keys as absolute block positions that belong to the chunk")
    void snapshotKeysAreAbsolute() {
        ChunkMirror chunk = chunk();
        chunk.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        ChunkMirror.Snapshot snapshot = chunk.snapshot();
        assertTrue(snapshot.blocks().containsKey(new BlockPos(50, 64, -110)),
                "absolute position, not the local (2, 64, 10) coordinate");
    }

    @Test
    @DisplayName("snapshots validate their input: foreign positions and unknown blocks are rejected")
    void snapshotValidation() {
        Map<BlockPos, BlockSnapshot> foreign = new HashMap<>();
        foreign.put(new BlockPos(16, 64, -100), new BlockSnapshot(VanillaBlocks.STONE,
                VanillaBlocks.DEFAULT_PROPERTIES, true));
        assertThrows(IllegalArgumentException.class, () -> new ChunkMirror.Snapshot(3, -7, foreign),
                "position outside chunk 3,-7");

        Map<BlockPos, BlockSnapshot> unknownBlock = new HashMap<>();
        unknownBlock.put(new BlockPos(50, 64, -110), BlockSnapshot.UNKNOWN);
        assertThrows(IllegalArgumentException.class, () -> new ChunkMirror.Snapshot(3, -7, unknownBlock),
                "only explicitly known blocks may be stored");

        assertThrows(NullPointerException.class, () -> new ChunkMirror.Snapshot(3, -7, null));

        Map<BlockPos, BlockSnapshot> nullBlock = new HashMap<>();
        nullBlock.put(new BlockPos(50, 64, -110), null);
        assertThrows(NullPointerException.class, () -> new ChunkMirror.Snapshot(3, -7, nullBlock));
    }

    @Test
    @DisplayName("a chunk rebuilt from a snapshot keeps its entries; replaceChunk in WorldMirror uses this")
    void rebuiltFromSnapshot() {
        ChunkMirror original = chunk();
        original.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.BLUE_ICE);
        original.setBlock(new BlockPos(50, 65, -110), VanillaBlocks.AIR);

        WorldMirror mirror = new WorldMirror();
        mirror.replaceChunk(original.snapshot());

        assertEquals(2, mirror.chunkSnapshot(3, -7).orElseThrow().blocks().size());
        assertEquals(VanillaBlocks.BLUE_ICE, mirror.getBlock(new BlockPos(50, 64, -110)).key());
    }
}
