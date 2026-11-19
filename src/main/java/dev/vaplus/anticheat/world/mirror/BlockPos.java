package dev.vaplus.anticheat.world.mirror;

/** Immutable block coordinates. Y is not packed or restricted to a particular dimension height. */
public record BlockPos(int x, int y, int z) {

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    /** Coordinate within the chunk, including for negative world coordinates (0..15). */
    public int localX() {
        return x & 15;
    }

    /** Coordinate within the chunk, including for negative world coordinates (0..15). */
    public int localZ() {
        return z & 15;
    }
}
