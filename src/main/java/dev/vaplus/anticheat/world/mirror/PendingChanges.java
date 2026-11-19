package dev.vaplus.anticheat.world.mirror;

/**
 * Uncertainty windows of a {@link WorldMirror}: which blocks, chunks or the whole world changed on
 * the server side but may not have been applied by the client yet.
 *
 * <h2>Model</h2>
 * The mirror always holds the newest state the server <em>sent</em>. A change sent at time S is
 * certainly applied by the client once a marker sent after the packet was acknowledged. In between
 * the client may hold the old or the new state, and the movement engine must not trust either: it
 * asks {@link #intersects} for the region it is about to scan and SKIPs ({@code world-pending}) on a
 * hit. The window deliberately starts at the send (not at the ack of a marker before the packet):
 * cheaper (no marker before every block/chunk packet), and only conservative (a few more skips).
 *
 * <p>Stamps are opaque, monotonically increasing acknowledgement handles chosen by the feeder
 * (marker handles of {@code latency.AckTracker}). A fresh entry carries {@link #UNRESOLVED} until the
 * feeder learns which marker follows the packet ({@link #resolve}); {@link #settle} drops every
 * entry whose stamp has been acknowledged. Unresolved entries never settle.
 *
 * <p>Bounded: at most {@link #MAX_BLOCKS} block and {@link #MAX_CHUNKS} chunk entries. On overflow
 * the whole world is marked pending instead (counted in {@link #overflows()}), which is safe: the
 * engine only skips more. Owner-thread confined and allocation-free after construction.
 */
public final class PendingChanges {

    /** Stamp of an entry whose closing marker is not known yet. */
    public static final long UNRESOLVED = Long.MAX_VALUE;
    public static final int MAX_BLOCKS = 256;
    public static final int MAX_CHUNKS = 256;
    private static final long NONE = 0L;

    private final int[] blockX = new int[MAX_BLOCKS];
    private final int[] blockY = new int[MAX_BLOCKS];
    private final int[] blockZ = new int[MAX_BLOCKS];
    private final long[] blockUntil = new long[MAX_BLOCKS];
    private int blocks;

    private final int[] chunkX = new int[MAX_CHUNKS];
    private final int[] chunkZ = new int[MAX_CHUNKS];
    private final long[] chunkUntil = new long[MAX_CHUNKS];
    private int chunks;

    private long allUntil = NONE;
    private long overflows;

    /** One block changed; its window ends with the next {@link #resolve} stamp. */
    public void markBlock(int x, int y, int z) {
        for (int i = 0; i < blocks; i++) {
            if (blockX[i] == x && blockY[i] == y && blockZ[i] == z) {
                blockUntil[i] = UNRESOLVED;
                return;
            }
        }
        if (blocks == MAX_BLOCKS) {
            overflows++;
            markAll();
            return;
        }
        blockX[blocks] = x;
        blockY[blocks] = y;
        blockZ[blocks] = z;
        blockUntil[blocks] = UNRESOLVED;
        blocks++;
    }

    /** A whole chunk column was (re)loaded. */
    public void markChunk(int x, int z) {
        for (int i = 0; i < chunks; i++) {
            if (chunkX[i] == x && chunkZ[i] == z) {
                chunkUntil[i] = UNRESOLVED;
                return;
            }
        }
        if (chunks == MAX_CHUNKS) {
            overflows++;
            markAll();
            return;
        }
        chunkX[chunks] = x;
        chunkZ[chunks] = z;
        chunkUntil[chunks] = UNRESOLVED;
        chunks++;
    }

    /** Everything is uncertain (join, respawn/world change, overflow). */
    public void markAll() {
        allUntil = UNRESOLVED;
    }

    /**
     * Gives every unresolved entry its closing stamp (the handle of the first marker sent after the
     * packets that created them).
     *
     * @return number of entries (including the whole-world window) that were resolved
     */
    public int resolve(long stamp) {
        if (stamp == UNRESOLVED || stamp <= NONE) {
            throw new IllegalArgumentException("stamp must be positive and resolvable: " + stamp);
        }
        int resolved = 0;
        for (int i = 0; i < blocks; i++) {
            if (blockUntil[i] == UNRESOLVED) {
                blockUntil[i] = stamp;
                resolved++;
            }
        }
        for (int i = 0; i < chunks; i++) {
            if (chunkUntil[i] == UNRESOLVED) {
                chunkUntil[i] = stamp;
                resolved++;
            }
        }
        if (allUntil == UNRESOLVED) {
            allUntil = stamp;
            resolved++;
        }
        return resolved;
    }

    /** Drops every window whose stamp is at or below {@code acknowledged}. */
    public void settle(long acknowledged) {
        int write = 0;
        for (int read = 0; read < blocks; read++) {
            if (blockUntil[read] <= acknowledged) {
                continue;
            }
            blockX[write] = blockX[read];
            blockY[write] = blockY[read];
            blockZ[write] = blockZ[read];
            blockUntil[write] = blockUntil[read];
            write++;
        }
        blocks = write;
        write = 0;
        for (int read = 0; read < chunks; read++) {
            if (chunkUntil[read] <= acknowledged) {
                continue;
            }
            chunkX[write] = chunkX[read];
            chunkZ[write] = chunkZ[read];
            chunkUntil[write] = chunkUntil[read];
            write++;
        }
        chunks = write;
        if (allUntil != NONE && allUntil <= acknowledged) {
            allUntil = NONE;
        }
    }

    /** Whether any window is open. */
    public boolean any() {
        return blocks > 0 || chunks > 0 || allUntil != NONE;
    }

    /** Whether the whole world is in an uncertainty window. */
    public boolean allPending() {
        return allUntil != NONE;
    }

    public int blockCount() {
        return blocks;
    }

    public int chunkCount() {
        return chunks;
    }

    /** Times a full list escalated to a whole-world window. */
    public long overflows() {
        return overflows;
    }

    /**
     * Whether a pending window touches the inclusive block box. Allocation-free; constant time when
     * nothing is pending.
     */
    public boolean intersects(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (allUntil != NONE) {
            return true;
        }
        for (int i = 0; i < blocks; i++) {
            int x = blockX[i];
            int y = blockY[i];
            int z = blockZ[i];
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                return true;
            }
        }
        if (chunks > 0) {
            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;
            for (int i = 0; i < chunks; i++) {
                int x = chunkX[i];
                int z = chunkZ[i];
                if (x >= minChunkX && x <= maxChunkX && z >= minChunkZ && z <= maxChunkZ) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops block and chunk windows (the world they refer to is gone); the whole-world window stays. */
    void clearEntries() {
        blocks = 0;
        chunks = 0;
    }

    /** Drops one chunk's windows (chunk unloaded: reads become UNKNOWN anyway). */
    void forgetChunk(int x, int z) {
        int write = 0;
        for (int read = 0; read < chunks; read++) {
            if (chunkX[read] == x && chunkZ[read] == z) {
                continue;
            }
            chunkX[write] = chunkX[read];
            chunkZ[write] = chunkZ[read];
            chunkUntil[write] = chunkUntil[read];
            write++;
        }
        chunks = write;
        write = 0;
        for (int read = 0; read < blocks; read++) {
            if ((blockX[read] >> 4) == x && (blockZ[read] >> 4) == z) {
                continue;
            }
            blockX[write] = blockX[read];
            blockY[write] = blockY[read];
            blockZ[write] = blockZ[read];
            blockUntil[write] = blockUntil[read];
            write++;
        }
        blocks = write;
    }
}
