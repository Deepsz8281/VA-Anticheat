package dev.vaplus.anticheat.world;

import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Objects;

/**
 * Voxel sight helpers over a per-player {@link WorldMirror}: exact block ray traversal (Amanatides
 * &amp; Woo) for line-of-sight, a slab ray/box intersection for interaction geometry, and the single
 * definition of "this block hides what is behind it" shared by the visibility checks.
 *
 * <p>All methods are allocation-free and owner-thread confined when a player's mirror is passed.
 * Missing mirror data is never treated as air or as a wall: {@link #lineOfSight} answers
 * {@link Occlusion#UNKNOWN} so callers cannot flag on incomplete data.
 */
public final class BlockSight {

    /** Result of a line-of-sight query. */
    public enum Occlusion {
        /** No opaque block between the two points. */
        VISIBLE,
        /** At least one opaque block lies between the two points. */
        OCCLUDED,
        /** A block on the path is not mirrored (or the path leaves the mirrored area). */
        UNKNOWN
    }

    /**
     * The query stops this far short of the target so the block the target stands in (or the body
     * itself) cannot be mistaken for a wall. Also the minimum distance for a meaningful query.
     */
    public static final double TARGET_MARGIN = 0.35;

    private static final double EPSILON = 1.0E-9;

    private BlockSight() {
    }

    /**
     * Whether the segment between two points is blocked by an opaque block.
     *
     * <p>A block that is not mirrored makes the whole answer {@link Occlusion#UNKNOWN}, even if an
     * opaque block was already found before it: the caller must not act on a partial picture.
     */
    public static Occlusion lineOfSight(WorldMirror world, double fromX, double fromY, double fromZ,
                                        double toX, double toY, double toZ) {
        Objects.requireNonNull(world, "world");
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(distance > TARGET_MARGIN)) {
            return Occlusion.VISIBLE;
        }
        double limit = distance - TARGET_MARGIN;
        double dirX = dx / distance;
        double dirY = dy / distance;
        double dirZ = dz / distance;

        int x = floor(fromX);
        int y = floor(fromY);
        int z = floor(fromZ);
        int stepX = dirX > 0.0 ? 1 : dirX < 0.0 ? -1 : 0;
        int stepY = dirY > 0.0 ? 1 : dirY < 0.0 ? -1 : 0;
        int stepZ = dirZ > 0.0 ? 1 : dirZ < 0.0 ? -1 : 0;

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirZ);
        double tMaxX = stepX > 0 ? (x + 1.0 - fromX) / dirX
                : stepX < 0 ? (fromX - x) / -dirX : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1.0 - fromY) / dirY
                : stepY < 0 ? (fromY - y) / -dirY : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1.0 - fromZ) / dirZ
                : stepZ < 0 ? (fromZ - z) / -dirZ : Double.POSITIVE_INFINITY;

        // The start cell is skipped: the eye (or the target) may sit inside a block while clipping.
        for (int guard = 0; guard < 4096; guard++) {
            double t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
            if (!(t <= limit)) {
                return Occlusion.VISIBLE;
            }
            BlockSnapshot block = world.getBlock(x, y, z);
            if (!block.known()) {
                return Occlusion.UNKNOWN;
            }
            if (obscures(block)) {
                return Occlusion.OCCLUDED;
            }
        }
        // Unreachable for finite input; a runaway loop must never produce a detection.
        return Occlusion.UNKNOWN;
    }

    /**
     * Whether this block hides what is behind it. Empty-collision blocks (air, fluids, plants),
     * everything without full-cube collision and the common see-through full cubes do not.
     */
    public static boolean obscures(BlockSnapshot block) {
        if (!block.known()) {
            return false;
        }
        BlockProperties properties = block.properties();
        if (properties.liquid() != BlockProperties.Liquid.NONE
                || properties.collision() == BlockProperties.Collision.EMPTY) {
            return false;
        }
        return !seeThrough(block.key());
    }

    /**
     * Full-cube blocks a client renders see-through. Keeping this list generous only makes the
     * checks quieter (a hidden ore behind glass is not counted, a player behind leaves is not
     * treated as walled off), never louder.
     */
    public static boolean seeThrough(BlockKey key) {
        String value = key.value();
        int colon = value.indexOf(':');
        String path = colon >= 0 ? value.substring(colon + 1) : value;
        return switch (path) {
            case "air", "cave_air", "void_air", "water", "lava", "bubble_column", "vine", "ladder",
                 "glass", "tinted_glass", "ice", "frosted_ice", "packed_ice", "blue_ice",
                 "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch",
                 "redstone_wall_torch", "lantern", "soul_lantern", "chain", "cobweb",
                 "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "snow",
                 "structure_void", "light" -> true;
            default -> path.endsWith("_glass") || path.endsWith("_glass_pane")
                    || path.endsWith("_pane") || path.endsWith("_bars") || path.endsWith("_leaves");
        };
    }

    /**
     * Distance along the ray to the entry point of an axis-aligned box, or {@link Double#NaN} when
     * the ray misses it. A ray starting inside the box returns {@code 0}.
     *
     * @param dirX normalized look direction; the returned value is in blocks
     */
    public static double rayBoxEntry(double originX, double originY, double originZ,
                                     double dirX, double dirY, double dirZ,
                                     double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ) {
        // Allocation-free slab test: this runs for every block interaction, so no per-call arrays.
        double near = 0.0;
        double far = Double.POSITIVE_INFINITY;

        if (Math.abs(dirX) < EPSILON) {
            if (originX < minX || originX > maxX) {
                return Double.NaN;
            }
        } else {
            double first = (minX - originX) / dirX;
            double second = (maxX - originX) / dirX;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) {
                return Double.NaN;
            }
        }

        if (Math.abs(dirY) < EPSILON) {
            if (originY < minY || originY > maxY) {
                return Double.NaN;
            }
        } else {
            double first = (minY - originY) / dirY;
            double second = (maxY - originY) / dirY;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) {
                return Double.NaN;
            }
        }

        if (Math.abs(dirZ) < EPSILON) {
            if (originZ < minZ || originZ > maxZ) {
                return Double.NaN;
            }
        } else {
            double first = (minZ - originZ) / dirZ;
            double second = (maxZ - originZ) / dirZ;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) {
                return Double.NaN;
            }
        }
        return far < 0.0 ? Double.NaN : near;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
