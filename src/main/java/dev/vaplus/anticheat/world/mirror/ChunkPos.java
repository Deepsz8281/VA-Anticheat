package dev.vaplus.anticheat.world.mirror;

import java.util.Objects;

/** Immutable chunk coordinates; these are chunk indices, not block coordinates. */
public record ChunkPos(int x, int z) {

    public static ChunkPos of(BlockPos position) {
        Objects.requireNonNull(position, "position");
        return new ChunkPos(position.chunkX(), position.chunkZ());
    }
}
