package dev.vaplus.anticheat.world.mirror;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Block mirror for one chunk. Owner-thread confined per player; no synchronisation.
 *
 * <p>Two layers: an optional dense {@link ColumnStorage} attached by a chunk feeder (state ids
 * resolved through a {@link BlockStateResolver}), and a sparse map of explicitly set entries
 * ({@link #setBlock}) that takes precedence over the column. Unfed positions are UNKNOWN, even in
 * an existing chunk; explicit air is retained as known air. Queries and snapshots never expose
 * mutable storage. There is no protocol or chunk-packet parser here; {@link #snapshot()} contains
 * the sparse entries only (dense columns are not materialised).
 */
public final class ChunkMirror {

    private final int chunkX;
    private final int chunkZ;
    private final Map<BlockPos, BlockSnapshot> blocks = new LinkedHashMap<>();
    private ColumnStorage column;
    private BlockStateResolver resolver = BlockStateResolver.NONE;

    public ChunkMirror(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    /** Chunk backed by a dense column (feeder path); no sparse entries. */
    ChunkMirror(int chunkX, int chunkZ, ColumnStorage column, BlockStateResolver resolver) {
        this(chunkX, chunkZ);
        this.column = Objects.requireNonNull(column, "column");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    ChunkMirror(Snapshot snapshot) {
        this(snapshot.chunkX(), snapshot.chunkZ());
        blocks.putAll(snapshot.blocks());
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    /** Number of explicit (sparse) entries; a dense column is not counted. */
    public int blockCount() {
        return blocks.size();
    }

    /** Whether a dense column is attached. */
    public boolean hasColumn() {
        return column != null;
    }

    public BlockSnapshot getBlock(BlockPos position) {
        requirePosition(chunkX, chunkZ, position);
        return getBlock(position.x(), position.y(), position.z());
    }

    /**
     * Allocation-free lookup when no sparse entries exist (the live feeder path). The caller
     * guarantees that {@code x}/{@code z} lie in this chunk.
     */
    BlockSnapshot getBlock(int x, int y, int z) {
        if (!blocks.isEmpty()) {
            BlockSnapshot explicit = blocks.get(new BlockPos(x, y, z));
            if (explicit != null) {
                return explicit;
            }
        }
        ColumnStorage storage = column;
        if (storage == null) {
            return BlockSnapshot.UNKNOWN;
        }
        int state = storage.stateId(x & 15, y, z & 15);
        if (state == ColumnStorage.UNKNOWN_STATE) {
            return BlockSnapshot.UNKNOWN;
        }
        BlockSnapshot snapshot = resolver.resolve(state);
        return snapshot == null ? BlockSnapshot.UNKNOWN : snapshot;
    }

    /** Dense state id, or {@link ColumnStorage#UNKNOWN_STATE} without a column / outside it. */
    int stateId(int x, int y, int z) {
        ColumnStorage storage = column;
        return storage == null ? ColumnStorage.UNKNOWN_STATE : storage.stateId(x & 15, y, z & 15);
    }

    /** Whether the dense column covers this height. */
    boolean columnContains(int y) {
        return column != null && column.containsY(y);
    }

    /**
     * Writes a dense state id; drops a sparse override at the same position so the new state is
     * visible. Returns {@code false} if the column could not store it (caller decides).
     */
    boolean setStateId(int x, int y, int z, int state) {
        if (column == null || !column.containsY(y)) {
            return false;
        }
        if (!blocks.isEmpty()) {
            blocks.remove(new BlockPos(x, y, z));
        }
        return column.setStateId(x & 15, y, z & 15, state);
    }

    public void setBlock(BlockPos position, BlockKey key) {
        setBlock(position, key, VanillaBlocks.properties(key));
    }

    /** Explicit state-aware metadata override, e.g. bubble direction, waterlogging or future shapes. */
    public void setBlock(BlockPos position, BlockKey key, BlockProperties properties) {
        requirePosition(chunkX, chunkZ, position);
        BlockSnapshot block = new BlockSnapshot(key, properties, true);
        blocks.put(position, block);
    }

    /** Drops the sparse entries and any dense column; every position becomes UNKNOWN. */
    public void clear() {
        blocks.clear();
        column = null;
    }

    public Snapshot snapshot() {
        return new Snapshot(chunkX, chunkZ, blocks);
    }

    private static void requirePosition(int chunkX, int chunkZ, BlockPos position) {
        Objects.requireNonNull(position, "position");
        if (position.chunkX() != chunkX || position.chunkZ() != chunkZ) {
            throw new IllegalArgumentException("position is outside chunk " + chunkX + "," + chunkZ);
        }
    }

    /** Detached immutable snapshot. Map keys are absolute block positions, not local coordinates. */
    public record Snapshot(int chunkX, int chunkZ, Map<BlockPos, BlockSnapshot> blocks) {

        public Snapshot {
            Objects.requireNonNull(blocks, "blocks");
            Map<BlockPos, BlockSnapshot> copy = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, BlockSnapshot> entry : blocks.entrySet()) {
                requirePosition(chunkX, chunkZ, entry.getKey());
                BlockSnapshot block = Objects.requireNonNull(entry.getValue(), "block");
                if (!block.known()) {
                    throw new IllegalArgumentException("only explicitly known blocks may be stored");
                }
                copy.put(entry.getKey(), block);
            }
            blocks = Collections.unmodifiableMap(copy);
        }
    }
}
