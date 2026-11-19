package dev.vaplus.anticheat.world.mirror;

import dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorldMirror (per-player compensated block storage) and ChunkPos")
class WorldMirrorTest {

    @Test
    @DisplayName("ChunkPos maps positions into chunk indices, including negative coordinates")
    void chunkPos() {
        assertEquals(new ChunkPos(0, 0), ChunkPos.of(new BlockPos(0, 64, 0)));
        assertEquals(new ChunkPos(1, -2), ChunkPos.of(new BlockPos(20, 64, -25)));
        assertEquals(new ChunkPos(-1, -1), ChunkPos.of(new BlockPos(-1, 64, -1)));
        assertEquals(new ChunkPos(-1, -1), ChunkPos.of(new BlockPos(-16, 64, -16)));
        assertEquals(new ChunkPos(-2, 3), ChunkPos.of(new BlockPos(-17, 64, 55)));
        assertEquals(new ChunkPos(3, -7), ChunkPos.of(new BlockPos(55, 64, -110)));
        assertThrows(NullPointerException.class, () -> ChunkPos.of(null));
        assertEquals(new ChunkPos(4, 5), new ChunkPos(4, 5));
        assertEquals(new ChunkPos(4, 5).hashCode(), new ChunkPos(4, 5).hashCode());
    }

    @Test
    @DisplayName("a fresh mirror has no chunks; missing data is UNKNOWN and does not allocate a chunk")
    void freshMirror() {
        WorldMirror mirror = new WorldMirror();
        assertEquals(0, mirror.chunkCount());
        assertEquals(0L, mirror.version());
        BlockSnapshot block = mirror.getBlock(new BlockPos(50, 64, -110));
        assertFalse(block.known());
        assertEquals(BlockKey.UNKNOWN, block.key());
        assertEquals(0, mirror.chunkCount(), "queries never allocate");
    }

    @Test
    @DisplayName("setBlock allocates only the needed chunk and bumps the version, even for same-value writes")
    void setBlockVersions() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        assertEquals(1, mirror.chunkCount());
        assertEquals(1L, mirror.version());

        // a second block in the same chunk does not add a chunk, but is a new mutation
        mirror.setBlock(new BlockPos(51, 64, -110), VanillaBlocks.DIRT);
        assertEquals(1, mirror.chunkCount());
        assertEquals(2L, mirror.version());

        // same-value writes are still mutations of the mirror's content timeline
        mirror.setBlock(new BlockPos(51, 64, -110), VanillaBlocks.DIRT);
        assertEquals(3L, mirror.version());
    }

    @Test
    @DisplayName("getBlock returns what was fed, with default properties resolved from the table")
    void roundTrip() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.SLIME_BLOCK);

        BlockSnapshot block = mirror.getBlock(new BlockPos(50, 64, -110));
        assertTrue(block.known());
        assertEquals(VanillaBlocks.SLIME_BLOCK, block.key());
        assertTrue(block.properties().has(SpecialFlag.SLIME_BOUNCE));
        assertFalse(mirror.getBlock(new BlockPos(51, 64, -110)).known(),
                "unset positions inside an existing chunk stay UNKNOWN");
    }

    @Test
    @DisplayName("state-aware feeding replaces the table properties, e.g. bubble column direction")
    void stateAwareFeed() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.BUBBLE_COLUMN,
                VanillaBlocks.BUBBLE_COLUMN_UP_PROPERTIES);

        BlockSnapshot block = mirror.getBlock(new BlockPos(50, 64, -110));
        assertTrue(block.properties().has(SpecialFlag.BUBBLE_COLUMN_UP));
        assertFalse(block.properties().has(SpecialFlag.BUBBLE_COLUMN_DOWN));
        assertFalse(block.uncertain(), "a fully specified state-aware entry is not uncertain");
    }

    @Test
    @DisplayName("null keys or properties are rejected")
    void nullArguments() {
        WorldMirror mirror = new WorldMirror();
        assertThrows(NullPointerException.class,
                () -> mirror.setBlock(new BlockPos(0, 64, 0), null));
        assertThrows(NullPointerException.class,
                () -> mirror.setBlock(new BlockPos(0, 64, 0), VanillaBlocks.STONE, null));
        assertThrows(NullPointerException.class,
                () -> mirror.setBlock(null, VanillaBlocks.STONE));
        assertThrows(NullPointerException.class, () -> mirror.replaceChunk(null));
    }

    @Test
    @DisplayName("replaceChunk atomically swaps the chunk; old entries are not merged")
    void replaceChunk() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        long versionBefore = mirror.version();

        Map<BlockPos, BlockSnapshot> entries = new LinkedHashMap<>();
        entries.put(new BlockPos(51, 64, -110),
                new BlockSnapshot(VanillaBlocks.BLUE_ICE, VanillaBlocks.DEFAULT_PROPERTIES, true));
        mirror.replaceChunk(new ChunkMirror.Snapshot(3, -7, entries));

        assertEquals(1, mirror.chunkCount());
        assertFalse(mirror.getBlock(new BlockPos(50, 64, -110)).known(),
                "old entries must not survive the atomic replacement");
        assertEquals(VanillaBlocks.BLUE_ICE, mirror.getBlock(new BlockPos(51, 64, -110)).key());
        assertEquals(versionBefore + 1, mirror.version(), "one replacement is one version bump");
    }

    @Test
    @DisplayName("clearChunk reports whether anything was removed and bumps the version only then")
    void clearChunk() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        long versionBefore = mirror.version();

        assertFalse(mirror.clearChunk(4, -7), "removing an absent chunk reports false");
        assertEquals(versionBefore, mirror.version());

        assertTrue(mirror.clearChunk(3, -7));
        assertEquals(0, mirror.chunkCount());
        assertEquals(versionBefore + 1, mirror.version());
        assertFalse(mirror.getBlock(new BlockPos(50, 64, -110)).known());
    }

    @Test
    @DisplayName("clear resets the world and always advances the stamp, never to zero")
    void clear() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        mirror.setBlock(new BlockPos(-5, 64, 20), VanillaBlocks.STONE);
        assertEquals(2, mirror.chunkCount());

        mirror.clear();
        assertEquals(0, mirror.chunkCount());
        assertEquals(3L, mirror.version());
        assertFalse(mirror.getBlock(new BlockPos(50, 64, -110)).known());

        mirror.clear();
        assertEquals(4L, mirror.version(), "the stamp never resets to zero");
    }

    @Test
    @DisplayName("chunkSnapshot is empty for absent chunks and detached for present ones")
    void chunkSnapshot() {
        WorldMirror mirror = new WorldMirror();
        assertEquals(Optional.empty(), mirror.chunkSnapshot(3, -7));

        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        ChunkMirror.Snapshot snapshot = mirror.chunkSnapshot(3, -7).orElseThrow();
        mirror.setBlock(new BlockPos(51, 64, -110), VanillaBlocks.DIRT);
        assertEquals(1, snapshot.blocks().size(), "the detached snapshot ignores later writes");
    }

    @Test
    @DisplayName("the full snapshot is deep-immutable and validates chunk/key consistency")
    void fullSnapshot() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(50, 64, -110), VanillaBlocks.STONE);
        WorldMirror.Snapshot snapshot = mirror.snapshot();

        mirror.setBlock(new BlockPos(-5, 64, 20), VanillaBlocks.DIRT);
        assertEquals(1, snapshot.chunks().size(), "later writes stay out of the snapshot");
        assertEquals(VanillaBlocks.STONE, snapshot.chunks().get(new ChunkPos(3, -7))
                .blocks().get(new BlockPos(50, 64, -110)).key());
        assertEquals(snapshot.version(), mirror.version() - 1,
                "the snapshot carries the version it was taken at");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.chunks().clear());

        Map<ChunkPos, ChunkMirror.Snapshot> inconsistent = new LinkedHashMap<>();
        inconsistent.put(new ChunkPos(0, 0), mirror.chunkSnapshot(3, -7).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new WorldMirror.Snapshot(9, inconsistent),
                "a chunk snapshot must not be stored under a mismatching chunk key");
        assertThrows(NullPointerException.class, () -> new WorldMirror.Snapshot(9, null));
    }

    @Test
    @DisplayName("blocks near a chunk border land in their own chunks (negative coordinates included)")
    void chunkBorders() {
        WorldMirror mirror = new WorldMirror();
        mirror.setBlock(new BlockPos(15, 64, 15), VanillaBlocks.STONE);
        mirror.setBlock(new BlockPos(16, 64, 15), VanillaBlocks.STONE);
        mirror.setBlock(new BlockPos(-1, 64, -1), VanillaBlocks.STONE);
        assertEquals(3, mirror.chunkCount());
        // each chunk holds exactly one block
        assertEquals(1, mirror.chunkSnapshot(0, 0).orElseThrow().blocks().size());
        assertEquals(1, mirror.chunkSnapshot(1, 0).orElseThrow().blocks().size());
        assertEquals(1, mirror.chunkSnapshot(-1, -1).orElseThrow().blocks().size());
    }
}
