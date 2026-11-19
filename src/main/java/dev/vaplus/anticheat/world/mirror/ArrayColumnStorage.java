package dev.vaplus.anticheat.world.mirror;

import java.util.Arrays;

/**
 * Simple uncompressed {@link ColumnStorage} ({@code int} per block), initially
 * {@link ColumnStorage#UNKNOWN_STATE} everywhere. Meant for tests, replays and small synthetic
 * columns; live chunk packets use the feeder's compact section storage instead.
 */
public final class ArrayColumnStorage implements ColumnStorage {

    private final int minY;
    private final int height;
    private final int[] states;

    public ArrayColumnStorage(int minY, int height) {
        if (height <= 0 || height > 4096) {
            throw new IllegalArgumentException("height must be 1..4096");
        }
        this.minY = minY;
        this.height = height;
        this.states = new int[16 * 16 * height];
        Arrays.fill(states, UNKNOWN_STATE);
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int stateId(int localX, int y, int localZ) {
        if (!containsY(y)) {
            return UNKNOWN_STATE;
        }
        return states[index(localX, y, localZ)];
    }

    @Override
    public boolean setStateId(int localX, int y, int localZ, int stateId) {
        if (!containsY(y) || stateId < UNKNOWN_STATE) {
            return false;
        }
        states[index(localX, y, localZ)] = stateId;
        return true;
    }

    /** Fills the inclusive layer range {@code [fromY, toY]} of the whole column. */
    public ArrayColumnStorage fillLayers(int fromY, int toY, int stateId) {
        for (int y = fromY; y <= toY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    setStateId(x, y, z, stateId);
                }
            }
        }
        return this;
    }

    private int index(int localX, int y, int localZ) {
        return ((y - minY) << 8) | ((localZ & 15) << 4) | (localX & 15);
    }
}
