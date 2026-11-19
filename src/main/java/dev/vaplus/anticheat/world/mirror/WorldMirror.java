package dev.vaplus.anticheat.world.mirror;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-player compensated block storage. Owner-thread confined: all reads, writes and snapshot
 * creation run on that player's owner thread; no locks or volatile live fields are needed.
 * Detached snapshots are immutable, but publishing them to other threads is the caller's job.
 *
 * <p>Chunks are allocated only by writes, never by queries. The caller applies updates at the
 * appropriate acknowledgement boundary and discards stale world-epoch work before feeding this
 * object. This class neither sends markers nor parses packets. {@link #version()} is a local
 * mutation stamp, not {@code MovementState.worldEpoch} or a Minecraft protocol version.
 *
 * <p><strong>Feeder path (phase 2 round 3).</strong> A chunk feeder attaches dense
 * {@link ColumnStorage}s ({@link #loadColumn}) whose state ids are resolved through the
 * {@link BlockStateResolver} given at construction, applies block updates with
 * {@link #setState} and records uncertainty windows in {@link #pending()}. Queries by int
 * coordinates ({@link #getBlock(int, int, int)}) are allocation-free on this path (primitive chunk
 * index, no {@link BlockPos}/{@link ChunkPos} keys). Memory is bounded by the chunks the client
 * holds (the feeder removes unloaded chunks) and hard-capped at {@link #MAX_CHUNKS}; beyond the cap
 * the oldest chunk is evicted (it then reads UNKNOWN, which only makes the engine skip).
 */
public final class WorldMirror {

    /**
     * Hard cap on mirrored chunks: a 32-chunk view distance needs (2 * 32 + 1)^2 = 4225 columns;
     * a few more are allowed for unload/load reordering.
     */
    public static final int MAX_CHUNKS = 4400;

    private final Map<ChunkPos, ChunkMirror> chunks = new LinkedHashMap<>();
    private final ChunkIndex index = new ChunkIndex();
    private final BlockStateResolver resolver;
    private final PendingChanges pending = new PendingChanges();
    private long version;
    private long evictions;

    /** Mirror without a state resolver: dense columns read as UNKNOWN (sparse API unaffected). */
    public WorldMirror() {
        this(BlockStateResolver.NONE);
    }

    public WorldMirror(BlockStateResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Starts at zero; every accepted write/reset advances it, including same-value setBlock calls. */
    public long version() {
        return version;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Chunks evicted because {@link #MAX_CHUNKS} was exceeded. */
    public long evictions() {
        return evictions;
    }

    /** Uncertainty windows maintained by the feeder and queried by the engine. */
    public PendingChanges pending() {
        return pending;
    }

    public BlockStateResolver resolver() {
        return resolver;
    }

    /** Missing data is UNKNOWN, not air, and does not allocate a chunk. */
    public BlockSnapshot getBlock(BlockPos position) {
        Objects.requireNonNull(position, "position");
        return getBlock(position.x(), position.y(), position.z());
    }

    /**
     * Same as {@link #getBlock(BlockPos)} without allocating a position key. Missing chunks and
     * positions outside a column are UNKNOWN.
     */
    public BlockSnapshot getBlock(int x, int y, int z) {
        ChunkMirror chunk = index.get(x >> 4, z >> 4);
        return chunk == null ? BlockSnapshot.UNKNOWN : chunk.getBlock(x, y, z);
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return index.get(chunkX, chunkZ) != null;
    }

    public void setBlock(BlockPos position, BlockKey key) {
        setBlock(position, key, VanillaBlocks.properties(key));
    }

    /** State-aware feeder entry point; metadata is immutable and can safely be shared. */
    public void setBlock(BlockPos position, BlockKey key, BlockProperties properties) {
        ChunkPos chunkPosition = ChunkPos.of(position);
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(properties, "properties");
        ChunkMirror chunk = chunks.get(chunkPosition);
        if (chunk == null) {
            chunk = new ChunkMirror(chunkPosition.x(), chunkPosition.z());
            put(chunkPosition, chunk);
        }
        chunk.setBlock(position, key, properties);
        version++;
    }

    /**
     * Atomically replace one chunk with a detached feeder snapshot; bumps the version once.
     * The old chunk's entries are not merged. A future chunk parser can build a standalone
     * ChunkMirror and supply its snapshot here; missing entries still mean UNKNOWN, not air.
     */
    public void replaceChunk(ChunkMirror.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ChunkPos position = new ChunkPos(snapshot.chunkX(), snapshot.chunkZ());
        put(position, new ChunkMirror(snapshot));
        version++;
    }

    /**
     * Replaces one chunk with a dense column (chunk packet). The previous chunk's data, sparse or
     * dense, is dropped; bumps the version once. Does not open a pending window (the feeder does).
     */
    public void loadColumn(int chunkX, int chunkZ, ColumnStorage column) {
        Objects.requireNonNull(column, "column");
        put(new ChunkPos(chunkX, chunkZ), new ChunkMirror(chunkX, chunkZ, column, resolver));
        version++;
    }

    /**
     * Dense state id at the position, or {@link ColumnStorage#UNKNOWN_STATE} when the chunk is
     * missing, has no column or the height is outside it.
     */
    public int stateId(int x, int y, int z) {
        ChunkMirror chunk = index.get(x >> 4, z >> 4);
        return chunk == null ? ColumnStorage.UNKNOWN_STATE : chunk.stateId(x, y, z);
    }

    /** Result of {@link #setState}. */
    public enum UpdateResult {
        /** Stored; the version advanced. */
        APPLIED,
        /** The chunk is not mirrored (the client ignores updates for chunks it does not hold). */
        NO_CHUNK,
        /** The chunk has no dense column or the height lies outside it; nothing changed. */
        OUT_OF_RANGE,
        /** The column could not store the id; the chunk was dropped so it reads UNKNOWN. */
        DROPPED
    }

    /**
     * Applies a block update to a dense column. Never turns an unrepresentable id into another
     * block: such a chunk is removed instead.
     */
    public UpdateResult setState(int x, int y, int z, int stateId) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        ChunkMirror chunk = index.get(chunkX, chunkZ);
        if (chunk == null) {
            return UpdateResult.NO_CHUNK;
        }
        if (!chunk.columnContains(y)) {
            return UpdateResult.OUT_OF_RANGE;
        }
        if (!chunk.setStateId(x, y, z, stateId)) {
            clearChunk(chunkX, chunkZ);
            return UpdateResult.DROPPED;
        }
        version++;
        return UpdateResult.APPLIED;
    }

    /** Chunk unload; only an actually removed chunk advances the version. */
    public boolean clearChunk(int chunkX, int chunkZ) {
        if (chunks.remove(new ChunkPos(chunkX, chunkZ)) == null) {
            return false;
        }
        index.remove(chunkX, chunkZ);
        pending.forgetChunk(chunkX, chunkZ);
        version++;
        return true;
    }

    /**
     * World/respawn reset. Always advances the stamp; never resets it to zero. Block and chunk
     * pending windows are dropped with the data; a whole-world window stays (the feeder opens one).
     */
    public void clear() {
        chunks.clear();
        index.clear();
        pending.clearEntries();
        version++;
    }

    public Optional<ChunkMirror.Snapshot> chunkSnapshot(int chunkX, int chunkZ) {
        ChunkMirror chunk = chunks.get(new ChunkPos(chunkX, chunkZ));
        return chunk == null ? Optional.empty() : Optional.of(chunk.snapshot());
    }

    /** Detached snapshot of the sparse entries (dense columns are not materialised). */
    public Snapshot snapshot() {
        Map<ChunkPos, ChunkMirror.Snapshot> copy = new LinkedHashMap<>();
        for (Map.Entry<ChunkPos, ChunkMirror> entry : chunks.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new Snapshot(version, copy);
    }

    private void put(ChunkPos position, ChunkMirror chunk) {
        if (chunks.put(position, chunk) == null && chunks.size() > MAX_CHUNKS) {
            Iterator<Map.Entry<ChunkPos, ChunkMirror>> eldest = chunks.entrySet().iterator();
            ChunkPos evicted = eldest.next().getKey();
            eldest.remove();
            index.remove(evicted.x(), evicted.z());
            pending.forgetChunk(evicted.x(), evicted.z());
            evictions++;
        }
        index.put(position.x(), position.z(), chunk);
    }

    /** Immutable deep snapshot in chunk insertion order. */
    public record Snapshot(long version, Map<ChunkPos, ChunkMirror.Snapshot> chunks) {

        public Snapshot {
            Objects.requireNonNull(chunks, "chunks");
            Map<ChunkPos, ChunkMirror.Snapshot> copy = new LinkedHashMap<>();
            for (Map.Entry<ChunkPos, ChunkMirror.Snapshot> entry : chunks.entrySet()) {
                ChunkPos position = Objects.requireNonNull(entry.getKey(), "chunkPosition");
                ChunkMirror.Snapshot chunk = Objects.requireNonNull(entry.getValue(), "chunk");
                if (position.x() != chunk.chunkX() || position.z() != chunk.chunkZ()) {
                    throw new IllegalArgumentException("snapshot chunk coordinates do not match its map key");
                }
                copy.put(position, chunk);
            }
            chunks = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * Open-addressing map from packed chunk coordinates to chunks (linear probing, backward-shift
     * deletion, load factor at most 1/2), so lookups need no boxed or record keys.
     */
    private static final class ChunkIndex {

        private long[] keys = new long[64];
        private ChunkMirror[] values = new ChunkMirror[64];
        private int size;

        private static long pack(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        private static int mix(long key, int mask) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & mask;
        }

        ChunkMirror get(int x, int z) {
            long key = pack(x, z);
            int mask = keys.length - 1;
            for (int slot = mix(key, mask); ; slot = (slot + 1) & mask) {
                ChunkMirror value = values[slot];
                if (value == null) {
                    return null;
                }
                if (keys[slot] == key) {
                    return value;
                }
            }
        }

        void put(int x, int z, ChunkMirror chunk) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            long key = pack(x, z);
            int mask = keys.length - 1;
            for (int slot = mix(key, mask); ; slot = (slot + 1) & mask) {
                if (values[slot] == null) {
                    keys[slot] = key;
                    values[slot] = chunk;
                    size++;
                    return;
                }
                if (keys[slot] == key) {
                    values[slot] = chunk;
                    return;
                }
            }
        }

        void remove(int x, int z) {
            long key = pack(x, z);
            int mask = keys.length - 1;
            int slot = mix(key, mask);
            while (true) {
                if (values[slot] == null) {
                    return;
                }
                if (keys[slot] == key) {
                    break;
                }
                slot = (slot + 1) & mask;
            }
            values[slot] = null;
            size--;
            // Backward-shift deletion keeps every probe chain contiguous.
            int hole = slot;
            for (int next = (hole + 1) & mask; values[next] != null; next = (next + 1) & mask) {
                int home = mix(keys[next], mask);
                boolean movable = hole <= next ? (home <= hole || home > next) : (home <= hole && home > next);
                if (movable) {
                    keys[hole] = keys[next];
                    values[hole] = values[next];
                    values[next] = null;
                    hole = next;
                }
            }
        }

        void clear() {
            java.util.Arrays.fill(values, null);
            size = 0;
        }

        private void grow() {
            long[] oldKeys = keys;
            ChunkMirror[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new ChunkMirror[oldValues.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldValues[i] != null) {
                    put((int) (oldKeys[i] >> 32), (int) oldKeys[i], oldValues[i]);
                }
            }
        }
    }
}
