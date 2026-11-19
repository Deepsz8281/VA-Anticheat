package dev.vaplus.anticheat.simulation;

import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * Conservative environment check for one movement packet, reused per player (owned by
 * {@link PredictionState}). The engine only simulates in "simple" terrain:
 * <ul>
 *   <li>every block the body can touch between the from- and to-position (plus the hitbox half
 *       width, a margin and, for multi-tick packets, a jump apex) is known and plain air — no
 *       walls, ceilings, liquids, climbables, special blocks or uncertain shapes;</li>
 *   <li>the first non-empty layer below the feet is completely made of plain full blocks with one
 *       uniform height (a uniform floor) or no floor exists down to the lowest reachable height;
 *       low state-independent blocks whose top is at the feet (farmland, dirt paths, carpets) are
 *       accepted as that floor.</li>
 * </ul>
 * Anything else (unknown/unloaded blocks, edges, mixed shapes, state-dependent shapes such as
 * slabs or stairs, ...) yields a non-OK result and the engine reports SKIPPED instead of an
 * unproven offset. Step-up and wall sliding are therefore not modelled in this round.
 *
 * <p>Compensated world: if any block, chunk or whole-world uncertainty window of the mirror
 * ({@link WorldMirror#pending()}, settled by the engine before the scan) touches the scanned box —
 * from the lowest floor layer to the head — the result is {@link #PENDING}: the client may still
 * hold the old state there.
 *
 * <p>Owner-thread confined and allocation-free ({@link WorldMirror#getBlock(int, int, int)}).
 */
final class SupportScan {

    static final int OK = 0;
    static final int UNKNOWN = 1;
    static final int COMPLEX = 2;
    static final int EDGE = 3;
    static final int TOO_LARGE = 4;
    /** A world change touching the scanned box is not acknowledged by the client yet. */
    static final int PENDING = 5;

    static final int MAX_COLUMNS_PER_AXIS = 8;
    static final int MAX_BODY_LAYERS = 8;
    static final int MAX_FLOOR_LAYERS = 32;

    private final double[] friction = new double[MAX_COLUMNS_PER_AXIS * MAX_COLUMNS_PER_AXIS];
    private int minBlockX;
    private int minBlockZ;
    private int sizeX;
    private int sizeZ;
    private boolean floorFound;
    private double floorTop;
    private double lowestVerifiedFeet;

    /**
     * @param lowestFeet lowest feet height any candidate can reach (from the fall bound)
     * @return {@link #OK} or the failure kind
     */
    int scan(WorldMirror world, double minX, double maxX, double minZ, double maxZ,
             double minFeet, double maxHead, double lowestFeet) {
        floorFound = false;
        floorTop = Double.NaN;
        lowestVerifiedFeet = Double.NaN;
        minBlockX = floor(minX);
        minBlockZ = floor(minZ);
        sizeX = floor(maxX) - minBlockX + 1;
        sizeZ = floor(maxZ) - minBlockZ + 1;
        if (sizeX > MAX_COLUMNS_PER_AXIS || sizeZ > MAX_COLUMNS_PER_AXIS) {
            return TOO_LARGE;
        }
        int bodyLow = floor(minFeet);
        int bodyHigh = (int) Math.ceil(maxHead) - 1;
        if (bodyHigh - bodyLow + 1 > MAX_BODY_LAYERS) {
            return TOO_LARGE;
        }
        if (world.pending().any()) {
            int lowestLayer = Math.min(bodyLow - 1, floor(lowestFeet) - 1);
            if (world.pending().intersects(minBlockX, lowestLayer, minBlockZ,
                    minBlockX + sizeX - 1, bodyHigh, minBlockZ + sizeZ - 1)) {
                return PENDING;
            }
        }
            int columns = sizeX * sizeZ;
        int partialFloor = 0;
        double partialTop = 0.0;
        double partialFriction = 0.0;
        for (int y = bodyLow; y <= bodyHigh; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockSnapshot block = world.getBlock(minBlockX + x, y, minBlockZ + z);
                    if (!block.known()) {
                        return UNKNOWN;
                    }
                    BlockProperties properties = block.properties();
                    if (plainAir(properties)) {
                        continue;
                    }
                    // The feet may stand inside the upper part of a low block (farmland, carpet):
                    // it is a floor, not an obstacle, as long as its top is at or below the feet.
                    if (y == bodyLow && plainFull(properties) && properties.collisionHeight() < 1.0
                            && bodyLow + properties.collisionHeight() <= minFeet + 1.0E-5) {
                        partialFloor++;
                        partialTop = bodyLow + properties.collisionHeight();
                        partialFriction = properties.friction();
                        continue;
                    }
                    return COMPLEX;
                }
            }
        }
        if (partialFloor > 0) {
            if (partialFloor != columns) {
                // Mixed floor shapes under the hitbox (e.g. a carpet ending mid-body).
                return COMPLEX;
            }
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    friction[x * sizeZ + z] = partialFriction;
                }
            }
            floorFound = true;
            floorTop = partialTop;
            return OK;
        }
        int top = bodyLow - 1;
        int bottom = Math.min(top, floor(lowestFeet) - 1);
        if (top - bottom + 1 > MAX_FLOOR_LAYERS) {
            return TOO_LARGE;
        }
        for (int y = top; y >= bottom; y--) {
            int solid = 0;
            int empty = 0;
            double height = -1.0;
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockSnapshot block = world.getBlock(minBlockX + x, y, minBlockZ + z);
                    if (!block.known()) {
                        return UNKNOWN;
                    }
                    BlockProperties properties = block.properties();
                    if (plainAir(properties)) {
                        empty++;
                    } else if (plainFull(properties)) {
                        // A uniform floor: every column must have the same top height, otherwise the
                        // per-column friction/height model would be wrong.
                        if (height < 0.0) {
                            height = properties.collisionHeight();
                        } else if (height != properties.collisionHeight()) {
                            return COMPLEX;
                        }
                        solid++;
                        friction[x * sizeZ + z] = properties.friction();
                    } else {
                        return COMPLEX;
                    }
                }
            }
            if (solid == 0) {
                continue;
            }
            if (empty > 0) {
                return EDGE;
            }
            floorFound = true;
            floorTop = y + height;
            return OK;
        }
        // No floor down to the lowest reachable height; the bottom layer itself was verified empty.
        lowestVerifiedFeet = bottom;
        return OK;
    }

    boolean floorFound() {
        return floorFound;
    }

    /** Top surface of the uniform floor (feet height when standing on it); NaN without a floor. */
    double floorTop() {
        return floorTop;
    }

    /** Without a floor: feet must stay at or above this height to remain inside verified blocks. */
    double lowestVerifiedFeet() {
        return lowestVerifiedFeet;
    }

    /** Slipperiness of the floor block under (x, z); the vanilla default outside the scanned grid. */
    double friction(double x, double z) {
        int ix = floor(x) - minBlockX;
        int iz = floor(z) - minBlockZ;
        if (!floorFound || ix < 0 || iz < 0 || ix >= sizeX || iz >= sizeZ) {
            return VanillaBlocks.DEFAULT_FRICTION;
        }
        return friction[ix * sizeZ + iz];
    }

    private static boolean plainAir(BlockProperties properties) {
        return properties.collision() == BlockProperties.Collision.EMPTY && plain(properties);
    }

    private static boolean plainFull(BlockProperties properties) {
        return properties.collision() == BlockProperties.Collision.FULL && plain(properties);
    }

    private static boolean plain(BlockProperties properties) {
        return properties.liquid() == BlockProperties.Liquid.NONE && !properties.climbable()
                && properties.flags().isEmpty() && !properties.uncertain();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
