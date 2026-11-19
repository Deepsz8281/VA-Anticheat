package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * GhostHand: block interactions (opening containers, pressing buttons, placing, starting to break)
 * through a wall of full opaque cubes.
 *
 * <p>Signal: a vanilla client only interacts with the block its pick ray hits first, so the segment
 * from the eye to the interaction point cannot pass through a full opaque cube. For a placement or
 * block use the packet carries the exact hit point; for a dig start (no hit point) five points of
 * the clicked face (centre and four inset corners) are tried and the dig counts only when all of
 * them are hidden. The segment is traversed voxel by voxel (Amanatides &amp; Woo) and only blocks on
 * the {@link OpaqueCubes} allowlist (full, opaque, every block state) count as walls.
 *
 * <p>Why legit play does not trigger it: the eye is tried at the current and the previous reported
 * position (the client picks with an interpolated eye) and at the pose's eye height; any visible
 * combination clears the interaction. Unknown blocks on the path, pending block changes in the
 * region, the player's own recent digs near the path (client-predicted breaking removes a wall before
 * the server confirms it), {@code insideBlock} placements, vehicles, gliding/swimming poses and
 * creative/spectator are not judged.
 *
 * <p>Known bypasses: reaching through non-full or see-through blocks (slabs, stairs, glass, leaves,
 * doors), corners where one sample point peeks out, and blocks that are not mirrored. Experimental;
 * numbers are TUNING values.
 */
public final class GhostHandCheck extends Check implements BlockCheck {

    /** TUNING: own digs near the path suppress judging for this long, plus the smoothed RTT. */
    public static final long DEFAULT_DIG_GRACE_MILLIS = 1500L;
    /** Inset of the dig sample points from the face corners. */
    static final double CORNER_INSET = 0.1;
    static final int VISIBLE = 0;
    static final int OCCLUDED = 1;
    static final int UNKNOWN = 2;
    private static final int DIG_SLOTS = 8;
    private static final double EPSILON = 1.0E-9;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("ghosthand", CheckCategory.WORLD)
            .displayName("GhostHand")
            .description("Block interactions through a wall of opaque full blocks")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.VEHICLE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private long digGraceNanos = DEFAULT_DIG_GRACE_MILLIS * 1_000_000L;
    private boolean checkPlace = true;
    private boolean checkDig = true;

    private final int[] digX = new int[DIG_SLOTS];
    private final int[] digY = new int[DIG_SLOTS];
    private final int[] digZ = new int[DIG_SLOTS];
    private final long[] digAt = new long[DIG_SLOTS];
    private int digCount;
    private int digNext;

    public GhostHandCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        long grace = options.getLong("dig-grace-millis", DEFAULT_DIG_GRACE_MILLIS);
        digGraceNanos = (grace >= 0 && grace <= 60_000L ? grace : DEFAULT_DIG_GRACE_MILLIS) * 1_000_000L;
        checkPlace = options.getBoolean("check-place", true);
        checkDig = options.getBoolean("check-dig", true);
    }

    @Override
    protected void onReset() {
        digCount = 0;
        digNext = 0;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind == BlockInteraction.Kind.USE_ITEM) {
            return;
        }
        if (kind == BlockInteraction.Kind.PLACE) {
            if (checkPlace && !interaction.insideBlock()) {
                judge(interaction, true);
            }
            return;
        }
        if (kind == BlockInteraction.Kind.DIG_START && checkDig) {
            judge(interaction, false);
        }
        // Every dig is remembered after judging: it may open a hole the server confirms later.
        remember(interaction);
    }

    private void judge(BlockInteraction interaction, boolean place) {
        BlockInteraction.Kind kind = interaction.kind();
        if (isExempt()) {
            return;
        }
        MovementState movement = player.movement();
        PoseState pose = player.pose();
        if (!movement.positionKnown() || pose.gliding() || pose.swimming()) {
            return;
        }
        double eye = pose.sneaking() ? FreecamCheck.SNEAKING_EYE_HEIGHT : FreecamCheck.STANDING_EYE_HEIGHT;
        int bx = interaction.blockX();
        int by = interaction.blockY();
        int bz = interaction.blockZ();
        WorldMirror world = player.component(worldKey);
        double ex = movement.x();
        double ey = movement.y() + eye;
        double ez = movement.z();
        int minX = (int) Math.floor(Math.min(Math.min(ex, movement.lastX()), bx)) - 1;
        int maxX = (int) Math.floor(Math.max(Math.max(ex, movement.lastX()), bx)) + 1;
        int minY = (int) Math.floor(Math.min(Math.min(ey, movement.lastY() + eye), by)) - 1;
        int maxY = (int) Math.floor(Math.max(Math.max(ey, movement.lastY() + eye), by)) + 1;
        int minZ = (int) Math.floor(Math.min(Math.min(ez, movement.lastZ()), bz)) - 1;
        int maxZ = (int) Math.floor(Math.max(Math.max(ez, movement.lastZ()), bz)) + 1;
        if (maxX - minX > 16 || maxY - minY > 16 || maxZ - minZ > 16) {
            return; // beyond any interaction range: reach checks own it
        }
        if (world.pending().intersects(minX, minY, minZ, maxX, maxY, maxZ)
                || recentDigNear(interaction.timestampNanos(), bx, by, bz, minX, minY, minZ, maxX, maxY, maxZ)) {
            return;
        }
        int verdict = place
                ? placeVerdict(world, interaction, ex, ey, ez, movement.lastX(), movement.lastY() + eye, movement.lastZ())
                : digVerdict(world, interaction.face(), bx, by, bz, ex, ey, ez,
                movement.lastX(), movement.lastY() + eye, movement.lastZ());
        if (verdict == UNKNOWN) {
            return;
        }
        if (verdict == VISIBLE) {
            lowerBuffer();
            return;
        }
        String details = "kind=" + kind + " target=" + bx + "," + by + "," + bz + " face=" + interaction.face();
        if (debugging()) {
            debug(details + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(details);
        }
    }

    private int placeVerdict(WorldMirror world, BlockInteraction interaction, double ex, double ey, double ez,
                             double lx, double ly, double lz) {
        int bx = interaction.blockX();
        int by = interaction.blockY();
        int bz = interaction.blockZ();
        double tx = bx + interaction.cursorX();
        double ty = by + interaction.cursorY();
        double tz = bz + interaction.cursorZ();
        int current = segment(world, ex, ey, ez, tx, ty, tz, bx, by, bz);
        if (current == VISIBLE) {
            return VISIBLE;
        }
        int previous = segment(world, lx, ly, lz, tx, ty, tz, bx, by, bz);
        return combine(current, previous);
    }

    private int digVerdict(WorldMirror world, BlockFace face, int bx, int by, int bz,
                           double ex, double ey, double ez, double lx, double ly, double lz) {
        int result = OCCLUDED;
        for (int sample = 0; sample < 5; sample++) {
            double u = sample == 0 ? 0.5 : (sample == 1 || sample == 2) ? CORNER_INSET : 1.0 - CORNER_INSET;
            double v = sample == 0 ? 0.5 : (sample == 1 || sample == 3) ? CORNER_INSET : 1.0 - CORNER_INSET;
            double tx;
            double ty;
            double tz;
            switch (face) {
                case UP -> { tx = bx + u; ty = by + 1.0; tz = bz + v; }
                case DOWN -> { tx = bx + u; ty = by; tz = bz + v; }
                case EAST -> { tx = bx + 1.0; ty = by + u; tz = bz + v; }
                case WEST -> { tx = bx; ty = by + u; tz = bz + v; }
                case SOUTH -> { tx = bx + u; ty = by + v; tz = bz + 1.0; }
                case NORTH -> { tx = bx + u; ty = by + v; tz = bz; }
                default -> {
                    return UNKNOWN;
                }
            }
            result = combine(result, segment(world, ex, ey, ez, tx, ty, tz, bx, by, bz));
            if (result == VISIBLE) {
                return VISIBLE;
            }
            result = combine(result, segment(world, lx, ly, lz, tx, ty, tz, bx, by, bz));
            if (result == VISIBLE) {
                return VISIBLE;
            }
        }
        return result;
    }

    /** VISIBLE wins over UNKNOWN, which wins over OCCLUDED. */
    private static int combine(int a, int b) {
        if (a == VISIBLE || b == VISIBLE) {
            return VISIBLE;
        }
        return a == UNKNOWN || b == UNKNOWN ? UNKNOWN : OCCLUDED;
    }

    /**
     * Voxel traversal from the eye to the target point. The eye's cell and the target block's cell are
     * not tested; the walk stops when it reaches the target cell or the target point.
     */
    static int segment(WorldMirror world, double fromX, double fromY, double fromZ,
                       double toX, double toY, double toZ, int targetX, int targetY, int targetZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(distance > EPSILON)) {
            return VISIBLE;
        }
        double dirX = dx / distance;
        double dirY = dy / distance;
        double dirZ = dz / distance;
        int x = (int) Math.floor(fromX);
        int y = (int) Math.floor(fromY);
        int z = (int) Math.floor(fromZ);
        int stepX = dirX > 0.0 ? 1 : dirX < 0.0 ? -1 : 0;
        int stepY = dirY > 0.0 ? 1 : dirY < 0.0 ? -1 : 0;
        int stepZ = dirZ > 0.0 ? 1 : dirZ < 0.0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirZ);
        double tMaxX = stepX > 0 ? (x + 1.0 - fromX) / dirX : stepX < 0 ? (fromX - x) / -dirX : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1.0 - fromY) / dirY : stepY < 0 ? (fromY - y) / -dirY : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1.0 - fromZ) / dirZ : stepZ < 0 ? (fromZ - z) / -dirZ : Double.POSITIVE_INFINITY;
        // Cells entered at (or within float noise of) the end point are the target face, not a wall.
        double limit = distance - 1.0E-4;
        for (int guard = 0; guard < 64; guard++) {
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
            if (!(t < limit) || (x == targetX && y == targetY && z == targetZ)) {
                return VISIBLE;
            }
            BlockSnapshot block = world.getBlock(x, y, z);
            if (!block.known()) {
                return UNKNOWN;
            }
            if (OpaqueCubes.occludes(block)) {
                return OCCLUDED;
            }
        }
        return UNKNOWN;
    }

    private void remember(BlockInteraction interaction) {
        int slot = digNext;
        digX[slot] = interaction.blockX();
        digY[slot] = interaction.blockY();
        digZ[slot] = interaction.blockZ();
        digAt[slot] = interaction.timestampNanos();
        digNext = (slot + 1) % DIG_SLOTS;
        if (digCount < DIG_SLOTS) {
            digCount++;
        }
    }

    /** Own digs (other than of the target itself) inside the region within the grace time. */
    private boolean recentDigNear(long nowNanos, int targetX, int targetY, int targetZ,
                                  int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (digCount == 0) {
            return false;
        }
        long rtt = player.acks().smoothedRttMillis();
        long grace = digGraceNanos + (rtt > 0 ? rtt * 1_000_000L : 0L);
        for (int i = 0; i < digCount; i++) {
            if (nowNanos - digAt[i] > grace
                    || (digX[i] == targetX && digY[i] == targetY && digZ[i] == targetZ)) {
                continue;
            }
            if (digX[i] >= minX && digX[i] <= maxX && digY[i] >= minY && digY[i] <= maxY
                    && digZ[i] >= minZ && digZ[i] <= maxZ) {
                return true;
            }
        }
        return false;
    }
}
