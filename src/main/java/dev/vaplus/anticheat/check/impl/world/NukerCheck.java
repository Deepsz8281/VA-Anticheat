package dev.vaplus.anticheat.check.impl.world;

import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockInteraction;

/**
 * Nuker: breaking blocks the way no vanilla client can. Two signals share one buffer.
 *
 * <ul>
 *   <li>{@code multi}: dig starts or finishes on more than {@code max-blocks-per-tick} different
 *       blocks within one client tick (1.21.2+ clients, exact tick boundary). Vanilla starts at most
 *       one block per tick ({@code MultiPlayerGameMode#startDestroyBlock} from the click, then
 *       {@code continueDestroyBlock} on the same crosshair target), and a finish is followed by a
 *       five tick destroy delay.</li>
 *   <li>{@code view}: a dig start or finish on a block the look ray does not touch. Vanilla only
 *       digs the block under the crosshair. The ray runs from the eye along the sent rotation up to
 *       the interaction range plus {@code view-margin}; the block counts as hit when the ray touches
 *       its full cube grown by {@code view-expand} (a superset of every block shape). Input is
 *       handled before the movement packet of the same tick, so the dig is tried with the previous
 *       and the current position and rotation, first when it arrives and again at the end of the
 *       tick (after that tick's rotation). Eye heights for standing, sneaking and crawling are all
 *       tried. Nukers without rotations dig everything around the player.</li>
 * </ul>
 *
 * <p>Never judged: unknown position, vehicles, gliding, swimming, teleports and the usual
 * exemption causes. Silent-rotation nukers that rotate per block are left to
 * {@code SilentRotationCheck}. All numeric defaults are TUNING values.
 */
public final class NukerCheck extends Check implements BlockCheck, ClientTickCheck {

    public static final int DEFAULT_MAX_BLOCKS_PER_TICK = 1;
    public static final double DEFAULT_VIEW_EXPAND = 0.1;
    public static final double DEFAULT_VIEW_MARGIN = 1.0;
    static final double MULTI_WEIGHT = 1.0;
    static final double VIEW_WEIGHT = 1.0;
    static final double CRAWLING_EYE_HEIGHT = 0.4;
    /** Pending view verdicts kept per tick; more digs than this in one tick are judged by multi. */
    static final int PENDING = 16;

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.VEHICLE,
            ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("nuker", CheckCategory.WORLD)
            .displayName("Nuker")
            .description("Breaking several blocks per tick or blocks outside the crosshair")
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE)
            .build();

    private boolean checkMulti = true;
    private int maxBlocksPerTick = DEFAULT_MAX_BLOCKS_PER_TICK;
    private boolean checkView = true;
    private double viewExpand = DEFAULT_VIEW_EXPAND;
    private double viewMargin = DEFAULT_VIEW_MARGIN;

    // distinct dig targets of the current tick
    private final long[] tickTargets = new long[PENDING];
    private int tickTargetCount;
    private boolean multiFlagged;

    // view verdicts deferred to the end of the tick
    private final int[] pendingX = new int[PENDING];
    private final int[] pendingY = new int[PENDING];
    private final int[] pendingZ = new int[PENDING];
    private final BlockInteraction.Kind[] pendingKind = new BlockInteraction.Kind[PENDING];
    private int pendingCount;

    public NukerCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkMulti = options.getBoolean("check-multi", true);
        int max = options.getInt("max-blocks-per-tick", DEFAULT_MAX_BLOCKS_PER_TICK);
        maxBlocksPerTick = max >= 1 && max < PENDING ? max : DEFAULT_MAX_BLOCKS_PER_TICK;
        checkView = options.getBoolean("check-view", true);
        viewExpand = bounded(options.getDouble("view-expand", DEFAULT_VIEW_EXPAND), 0.0, 1.0, DEFAULT_VIEW_EXPAND);
        viewMargin = bounded(options.getDouble("view-margin", DEFAULT_VIEW_MARGIN), 0.0, 4.0, DEFAULT_VIEW_MARGIN);
    }

    private static double bounded(double value, double min, double max, double fallback) {
        return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        tickTargetCount = 0;
        pendingCount = 0;
        multiFlagged = false;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind != BlockInteraction.Kind.DIG_START && kind != BlockInteraction.Kind.DIG_FINISH) {
            return;
        }
        if (!judgeable()) {
            return;
        }
        int x = interaction.blockX();
        int y = interaction.blockY();
        int z = interaction.blockZ();
        if (checkMulti && player.tickEndMode()) {
            countTarget(x, y, z);
        }
        if (checkView) {
            if (visible(x, y, z)) {
                lowerBuffer();
            } else if (pendingCount < PENDING) {
                pendingX[pendingCount] = x;
                pendingY[pendingCount] = y;
                pendingZ[pendingCount] = z;
                pendingKind[pendingCount] = kind;
                pendingCount++;
            }
        }
    }

    private void countTarget(int x, int y, int z) {
        long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
        for (int i = 0; i < tickTargetCount; i++) {
            if (tickTargets[i] == key) {
                return;
            }
        }
        if (tickTargetCount < PENDING) {
            tickTargets[tickTargetCount++] = key;
        }
        if (tickTargetCount > maxBlocksPerTick && !multiFlagged) {
            multiFlagged = true;
            flag(MULTI_WEIGHT, "multi blocks=" + tickTargetCount + " max=" + maxBlocksPerTick);
        }
    }

    @Override
    public void onClientTick() {
        // end of the tick: the movement packet with this tick's rotation has arrived
        for (int i = 0; i < pendingCount; i++) {
            if (!judgeable()) {
                break;
            }
            if (visible(pendingX[i], pendingY[i], pendingZ[i])) {
                lowerBuffer();
            } else {
                MovementState movement = player.movement();
                flag(VIEW_WEIGHT, "view block=" + pendingX[i] + "," + pendingY[i] + "," + pendingZ[i]
                        + " action=" + (pendingKind[i] == BlockInteraction.Kind.DIG_START ? "start" : "finish")
                        + " distance=" + format(distance(movement, pendingX[i], pendingY[i], pendingZ[i]))
                        + " yaw=" + format(movement.yaw()) + " pitch=" + format(movement.pitch()));
            }
        }
        pendingCount = 0;
        if (player.tickEndMode()) {
            tickTargetCount = 0;
            multiFlagged = false;
        }
    }

    private boolean judgeable() {
        if (isExempt() || player.exemptions().anyActive(EXEMPT_CAUSES)) {
            return false;
        }
        PoseState pose = player.pose();
        return player.movement().positionKnown() && player.movement().rotationKnown()
                && !player.vehicle().inVehicle() && !pose.gliding() && !pose.swimming();
    }

    /** Whether any combination of previous/current position and rotation looks at the block. */
    boolean visible(int bx, int by, int bz) {
        MovementState movement = player.movement();
        double scale = Math.max(0.1, player.attributes().scale());
        double reach = Math.max(player.attributes().blockInteractionRange(), 4.5) + viewMargin;
        float[] yaws = {movement.yaw(), movement.lastYaw()};
        float[] pitches = {movement.pitch(), movement.lastPitch()};
        double[] xs = {movement.x(), movement.lastX()};
        double[] ys = {movement.y(), movement.lastY()};
        double[] zs = {movement.z(), movement.lastZ()};
        double[] eyes = {FreecamCheck.STANDING_EYE_HEIGHT, FreecamCheck.SNEAKING_EYE_HEIGHT, CRAWLING_EYE_HEIGHT};
        for (int r = 0; r < 2; r++) {
            double yaw = Math.toRadians(yaws[r]);
            double pitch = Math.toRadians(pitches[r]);
            double dx = -Math.sin(yaw) * Math.cos(pitch);
            double dy = -Math.sin(pitch);
            double dz = Math.cos(yaw) * Math.cos(pitch);
            for (int p = 0; p < 2; p++) {
                for (double eye : eyes) {
                    if (rayHitsBox(xs[p], ys[p] + eye * scale, zs[p], dx, dy, dz, reach,
                            bx - viewExpand, by - viewExpand, bz - viewExpand,
                            bx + 1 + viewExpand, by + 1 + viewExpand, bz + 1 + viewExpand)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Slab test: does the segment origin + t * direction, t in [0, length], touch the box? */
    static boolean rayHitsBox(double ox, double oy, double oz, double dx, double dy, double dz, double length,
                              double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double tMin = 0.0;
        double tMax = length;
        double[] origin = {ox, oy, oz};
        double[] direction = {dx, dy, dz};
        double[] min = {minX, minY, minZ};
        double[] max = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0e-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return false;
                }
                continue;
            }
            double t1 = (min[axis] - origin[axis]) / direction[axis];
            double t2 = (max[axis] - origin[axis]) / direction[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return false;
            }
        }
        return true;
    }

    private static double distance(MovementState movement, int bx, int by, int bz) {
        double dx = bx + 0.5 - movement.x();
        double dy = by + 0.5 - (movement.y() + FreecamCheck.STANDING_EYE_HEIGHT);
        double dz = bz + 0.5 - movement.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void flag(double weight, String details) {
        if (debugging()) {
            debug(details + " buffer=" + format(buffer()));
        }
        if (raiseBuffer(weight)) {
            violation(details);
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
