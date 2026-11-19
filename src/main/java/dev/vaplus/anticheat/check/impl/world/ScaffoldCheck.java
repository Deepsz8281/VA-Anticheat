package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.RotationCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * Scaffold: block placements a vanilla client's pick ray cannot produce.
 *
 * <p>A vanilla placement is the result of one ray: from the camera (eye between the previous and
 * the current position), along the camera rotation, to the first block outline it hits. The packet
 * carries the clicked block, its face and the exact hit point. Every signal below tests one
 * consequence of that; geometric signals only judge targets the world mirror knows as a full opaque
 * cube ({@link OpaqueCubes}) and that are not inside a pending world window.
 * <ul>
 *   <li><b>face</b> — the eye must be on the outer side of the clicked face's plane (a ray only enters
 *       a cube through a face it is in front of). Scaffolds that click the far or bottom face of the
 *       block below fail it.</li>
 *   <li><b>cursor</b> — the hit point lies exactly on the face plane (0 or 1 on the face axis).
 *       Hand-built packets often send the block centre.</li>
 *   <li><b>ray</b> — the look ray must reach the clicked face <em>at the hit point</em>. Candidates:
 *       eye positions along the previous→current position, standing and crouching eye height,
 *       rotations between the rotation sent before the placement and the one sent right after it
 *       (the camera may turn within a tick), sampled finer than {@link #RAY_STEP_DEGREES}. If no
 *       candidate ray crosses the face plane within {@code ray-tolerance} blocks of the hit point,
 *       the client placed somewhere it was not looking (scaffold without rotations, wrong silent
 *       rotations, expand, "keep rotation" modes).</li>
 *   <li><b>reach</b> — the closest point of the clicked block is farther from every eye candidate
 *       than the {@code block_interaction_range} attribute (4.5) plus {@code reach-tolerance}.</li>
 *   <li><b>air</b> — the clicked block is air or a pure fluid block (water, lava, bubble column) in
 *       the mirror. Pick rays ignore both, so nothing can be clicked there (AirPlace). Blocks the
 *       player placed itself within {@link #OWN_PLACE_MEMORY_TICKS} ticks are not judged (the mirror
 *       may lag behind the client's own prediction).</li>
 *   <li><b>snap</b> — silent rotations: the rotation jumps at least {@code snap-min-degrees}, a block
 *       is placed, and within {@code snap-hold-ticks} it jumps back to within
 *       max({@code snap-return-degrees}, {@code snap-return-ratio} × jump) of where it started. The
 *       camera never turned; only the packets did. Humans who turn to bridge do not land back on
 *       their exact start rotation within a few ticks.</li>
 * </ul>
 *
 * <p>Never judged: {@code insideBlock} placements, vehicles, gliding, creative/spectator (WORLD
 * category exemption), pending attributes (reach), unknown rotation (ray). Experimental; numbers
 * are TUNING values.
 */
public final class ScaffoldCheck extends Check implements BlockCheck, RotationCheck, ClientTickCheck {

    /** Vanilla standing eye height (also {@link FreecamCheck#STANDING_EYE_HEIGHT}). */
    public static final double MAX_EYE_HEIGHT = 1.62;
    /** Crouching eye height (1.21). */
    public static final double CROUCH_EYE_HEIGHT = 1.27;
    /** Lowest eye height of a player pose (swimming / crawling, 0.6 high box). UNCONFIRMED: 0.4. */
    public static final double MIN_EYE_HEIGHT = 0.4;
    public static final double DEFAULT_TOLERANCE = 0.05;
    public static final double DEFAULT_CURSOR_TOLERANCE = 0.02;
    public static final double DEFAULT_RAY_TOLERANCE = 0.25;
    public static final double DEFAULT_REACH_TOLERANCE = 0.3;
    public static final double DEFAULT_SNAP_MIN_DEGREES = 60.0;
    public static final int DEFAULT_SNAP_HOLD_TICKS = 3;
    public static final double DEFAULT_SNAP_RETURN_RATIO = 0.1;
    public static final double DEFAULT_SNAP_RETURN_DEGREES = 5.0;
    static final double RAY_STEP_DEGREES = 0.5;
    static final int MAX_RAY_STEPS = 64;
    static final int EYE_STEPS = 3;
    static final int MAX_PENDING = 4;
    static final int OWN_PLACE_MEMORY = 32;
    static final int OWN_PLACE_MEMORY_TICKS = 60;
    static final int HISTORY = 8;

    static final double AIR_WEIGHT = 1.5;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("scaffold", CheckCategory.WORLD)
            .displayName("Scaffold")
            .description("Placements the pick ray cannot produce: hidden face, off-face hit point, "
                    + "not looked at, out of reach, against air, silent rotation")
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
    private double tolerance = DEFAULT_TOLERANCE;
    private double cursorTolerance = DEFAULT_CURSOR_TOLERANCE;
    private double rayTolerance = DEFAULT_RAY_TOLERANCE;
    private double reachTolerance = DEFAULT_REACH_TOLERANCE;
    private double snapMin = DEFAULT_SNAP_MIN_DEGREES;
    private int snapHold = DEFAULT_SNAP_HOLD_TICKS;
    private double snapReturnRatio = DEFAULT_SNAP_RETURN_RATIO;
    private double snapReturnDegrees = DEFAULT_SNAP_RETURN_DEGREES;
    private boolean checkFace = true;
    private boolean checkCursor = true;
    private boolean checkRay = true;
    private boolean checkReach = true;
    private boolean checkAir = true;
    private boolean checkSnap = true;

    // placements waiting for the rotation sent after them (ray signal)
    private final int[] pendingX = new int[MAX_PENDING];
    private final int[] pendingY = new int[MAX_PENDING];
    private final int[] pendingZ = new int[MAX_PENDING];
    private final BlockFace[] pendingFace = new BlockFace[MAX_PENDING];
    private final double[] pendingCursorX = new double[MAX_PENDING];
    private final double[] pendingCursorY = new double[MAX_PENDING];
    private final double[] pendingCursorZ = new double[MAX_PENDING];
    private final double[] pendingFromX = new double[MAX_PENDING];
    private final double[] pendingFromY = new double[MAX_PENDING];
    private final double[] pendingFromZ = new double[MAX_PENDING];
    private final double[] pendingToX = new double[MAX_PENDING];
    private final double[] pendingToY = new double[MAX_PENDING];
    private final double[] pendingToZ = new double[MAX_PENDING];
    private final float[] pendingYaw = new float[MAX_PENDING];
    private final float[] pendingPitch = new float[MAX_PENDING];
    private final boolean[] pendingCrawl = new boolean[MAX_PENDING];
    private int pendingCount;

    // positions this player placed recently (air signal)
    private final long[] ownPlaced = new long[OWN_PLACE_MEMORY];
    private final long[] ownPlacedTick = new long[OWN_PLACE_MEMORY];
    private int ownPlacedNext;

    // rotation history (snap signal), unwrapped yaw
    private final double[] historyYaw = new double[HISTORY];
    private final double[] historyPitch = new double[HISTORY];
    private final long[] historyTick = new long[HISTORY];
    private int historyHead;
    private int historySize;
    private long rotationCount;
    private long snapConsumed = -1L;
    private long lastPlaceTick = Long.MIN_VALUE / 2;

    public ScaffoldCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        java.util.Arrays.fill(ownPlacedTick, Long.MIN_VALUE / 2);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        tolerance = Math.min(1.0, VisibilityOptions.tolerance(options, "tolerance", DEFAULT_TOLERANCE));
        cursorTolerance = Math.min(0.5,
                VisibilityOptions.tolerance(options, "cursor-tolerance", DEFAULT_CURSOR_TOLERANCE));
        rayTolerance = Math.min(1.0, VisibilityOptions.tolerance(options, "ray-tolerance", DEFAULT_RAY_TOLERANCE));
        reachTolerance = Math.min(3.0,
                VisibilityOptions.tolerance(options, "reach-tolerance", DEFAULT_REACH_TOLERANCE));
        snapMin = range(options.getDouble("snap-min-degrees", DEFAULT_SNAP_MIN_DEGREES), 10.0, 180.0,
                DEFAULT_SNAP_MIN_DEGREES);
        int hold = options.getInt("snap-hold-ticks", DEFAULT_SNAP_HOLD_TICKS);
        snapHold = hold >= 1 && hold <= HISTORY - 2 ? hold : DEFAULT_SNAP_HOLD_TICKS;
        snapReturnRatio = range(options.getDouble("snap-return-ratio", DEFAULT_SNAP_RETURN_RATIO), 0.0, 0.5,
                DEFAULT_SNAP_RETURN_RATIO);
        snapReturnDegrees = range(options.getDouble("snap-return-degrees", DEFAULT_SNAP_RETURN_DEGREES), 0.0,
                30.0, DEFAULT_SNAP_RETURN_DEGREES);
        checkFace = options.getBoolean("check-face", true);
        checkCursor = options.getBoolean("check-cursor", true);
        checkRay = options.getBoolean("check-ray", true);
        checkReach = options.getBoolean("check-reach", true);
        checkAir = options.getBoolean("check-air", true);
        checkSnap = options.getBoolean("check-snap", true);
    }

    private static double range(double value, double min, double max, double fallback) {
        return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        pendingCount = 0;
        historySize = 0;
        java.util.Arrays.fill(ownPlacedTick, Long.MIN_VALUE / 2);
    }

    // ---- placements ---------------------------------------------------------------------------

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (interaction.kind() != BlockInteraction.Kind.PLACE || interaction.insideBlock()) {
            return;
        }
        long tick = player.clientTicks();
        int bx = interaction.blockX();
        int by = interaction.blockY();
        int bz = interaction.blockZ();
        BlockFace face = interaction.face();
        boolean own = recentlyPlacedByPlayer(bx, by, bz, tick);
        rememberOwnPlacement(bx, by, bz, face, tick);
        lastPlaceTick = tick;
        if (isExempt()) {
            return;
        }
        MovementState movement = player.movement();
        if (!movement.positionKnown() || player.pose().gliding()) {
            return;
        }
        WorldMirror world = player.component(worldKey);
        if (world.pending().intersects(bx, by, bz, bx, by, bz)) {
            return;
        }
        BlockSnapshot block = world.getBlock(bx, by, bz);

        if (checkAir && !own && block.known() && unclickable(block.key())) {
            flag(AIR_WEIGHT, "signal=air target=" + bx + "," + by + "," + bz + " block=" + block.key().value());
            return;
        }
        if (checkReach && !player.attributes().pending()) {
            double range = player.attributes().blockInteractionRange();
            double distance = closestDistance(bx, by, bz, movement);
            if (distance > range + reachTolerance) {
                flag(1.0, "signal=reach target=" + bx + "," + by + "," + bz + " distance="
                        + VisibilityOptions.number(distance) + " range=" + VisibilityOptions.number(range));
                return;
            }
        }
        if (!OpaqueCubes.occludes(block)) {
            return;
        }
        if (checkFace && !faceVisible(face, bx, by, bz, movement.x(), movement.y(), movement.z(), tolerance)
                && !faceVisible(face, bx, by, bz, movement.lastX(), movement.lastY(), movement.lastZ(), tolerance)) {
            flag(1.0, "signal=face face=" + face + " target=" + bx + "," + by + "," + bz
                    + " feet=" + VisibilityOptions.number(movement.x()) + "," + VisibilityOptions.number(movement.y())
                    + "," + VisibilityOptions.number(movement.z()));
            return;
        }
        if (checkCursor && !cursorOnFace(face, interaction.cursorX(), interaction.cursorY(),
                interaction.cursorZ(), cursorTolerance)) {
            flag(1.0, "signal=cursor face=" + face + " target=" + bx + "," + by + "," + bz
                    + " cursor=" + interaction.cursorX() + "," + interaction.cursorY() + "," + interaction.cursorZ());
            return;
        }
        // The scale attribute moves the eye; the ray model assumes vanilla eye heights.
        if (checkRay && movement.rotationKnown() && axis(face) >= 0
                && !player.attributes().pending() && player.attributes().scale() == 1.0) {
            queueRay(interaction, movement);
            return;
        }
        lowerBuffer();
    }

    /** Air, cave air, void air and pure fluid blocks: a pick ray passes through them. */
    static boolean unclickable(BlockKey key) {
        return key.equals(VanillaBlocks.AIR) || key.equals(VanillaBlocks.CAVE_AIR)
                || key.equals(VanillaBlocks.VOID_AIR) || key.equals(VanillaBlocks.WATER_BLOCK)
                || key.equals(VanillaBlocks.LAVA_BLOCK) || key.equals(VanillaBlocks.BUBBLE_COLUMN);
    }

    private double closestDistance(int bx, int by, int bz, MovementState movement) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < EYE_STEPS; i++) {
            double t = (double) i / (EYE_STEPS - 1);
            double x = movement.lastX() + (movement.x() - movement.lastX()) * t;
            double y = movement.lastY() + (movement.y() - movement.lastY()) * t;
            double z = movement.lastZ() + (movement.z() - movement.lastZ()) * t;
            // The eye can sit anywhere between the lowest pose and standing height.
            double dx = axisDistance(x, bx, bx + 1.0);
            double dz = axisDistance(z, bz, bz + 1.0);
            double dy = Math.min(axisDistance(y + MAX_EYE_HEIGHT, by, by + 1.0),
                    axisDistance(y + MIN_EYE_HEIGHT, by, by + 1.0));
            if (y + MIN_EYE_HEIGHT <= by + 1.0 && y + MAX_EYE_HEIGHT >= by) {
                dy = 0.0;
            }
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0;
    }

    private boolean recentlyPlacedByPlayer(int x, int y, int z, long tick) {
        long key = pack(x, y, z);
        for (int i = 0; i < OWN_PLACE_MEMORY; i++) {
            if (ownPlaced[i] == key && tick - ownPlacedTick[i] <= OWN_PLACE_MEMORY_TICKS) {
                return true;
            }
        }
        return false;
    }

    private void rememberOwnPlacement(int bx, int by, int bz, BlockFace face, long tick) {
        int x = bx;
        int y = by;
        int z = bz;
        switch (face) {
            case UP -> y++;
            case DOWN -> y--;
            case EAST -> x++;
            case WEST -> x--;
            case SOUTH -> z++;
            case NORTH -> z--;
            default -> {
                return;
            }
        }
        ownPlaced[ownPlacedNext] = pack(x, y, z);
        ownPlacedTick[ownPlacedNext] = tick;
        ownPlacedNext = (ownPlacedNext + 1) % OWN_PLACE_MEMORY;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ---- ray ----------------------------------------------------------------------------------

    private void queueRay(BlockInteraction interaction, MovementState movement) {
        if (pendingCount == MAX_PENDING) {
            judgePending(movement.yaw(), movement.pitch());
        }
        int i = pendingCount++;
        pendingX[i] = interaction.blockX();
        pendingY[i] = interaction.blockY();
        pendingZ[i] = interaction.blockZ();
        pendingFace[i] = interaction.face();
        pendingCursorX[i] = interaction.blockX() + interaction.cursorX();
        pendingCursorY[i] = interaction.blockY() + interaction.cursorY();
        pendingCursorZ[i] = interaction.blockZ() + interaction.cursorZ();
        pendingFromX[i] = movement.lastX();
        pendingFromY[i] = movement.lastY();
        pendingFromZ[i] = movement.lastZ();
        pendingToX[i] = movement.x();
        pendingToY[i] = movement.y();
        pendingToZ[i] = movement.z();
        pendingYaw[i] = movement.yaw();
        pendingPitch[i] = movement.pitch();
        pendingCrawl[i] = player.pose().swimming();
    }

    @Override
    public void onClientTick() {
        if (pendingCount > 0) {
            MovementState movement = player.movement();
            judgePending(movement.yaw(), movement.pitch());
        }
    }

    private void judgePending(float nextYaw, float nextPitch) {
        int count = pendingCount;
        pendingCount = 0;
        for (int i = 0; i < count; i++) {
            double miss = rayMiss(i, nextYaw, nextPitch);
            if (miss > rayTolerance) {
                flag(1.0, "signal=ray face=" + pendingFace[i] + " target=" + pendingX[i] + "," + pendingY[i]
                        + "," + pendingZ[i] + " miss=" + VisibilityOptions.number(miss)
                        + " yaw=" + VisibilityOptions.number(pendingYaw[i])
                        + " pitch=" + VisibilityOptions.number(pendingPitch[i]));
            } else {
                lowerBuffer();
            }
        }
    }

    /**
     * Smallest distance between the hit point and the point where a candidate look ray crosses the
     * clicked face's plane. Candidates: eye positions between the previous and current position,
     * standing and crouching eye height (plus crawling while swimming), rotations from the one sent
     * before the placement to the one sent after it.
     */
    private double rayMiss(int i, float nextYaw, float nextPitch) {
        double fromYaw = pendingYaw[i];
        double fromPitch = pendingPitch[i];
        double yawDelta = wrap((double) nextYaw - fromYaw);
        double pitchDelta = (double) nextPitch - fromPitch;
        double turn = Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
        int steps = (int) Math.min(MAX_RAY_STEPS, Math.max(1, Math.ceil(turn / RAY_STEP_DEGREES)));
        int axis = axis(pendingFace[i]);
        double plane = planeCoordinate(pendingFace[i], pendingX[i], pendingY[i], pendingZ[i]);
        double best = Double.MAX_VALUE;
        int heights = pendingCrawl[i] ? 3 : 2;
        for (int r = 0; r <= steps; r++) {
            double t = (double) r / steps;
            double yaw = Math.toRadians(fromYaw + yawDelta * t);
            double pitch = Math.toRadians(fromPitch + pitchDelta * t);
            double cosPitch = Math.cos(pitch);
            double dirX = -Math.sin(yaw) * cosPitch;
            double dirY = -Math.sin(pitch);
            double dirZ = Math.cos(yaw) * cosPitch;
            double dirAxis = axis == 0 ? dirX : axis == 1 ? dirY : dirZ;
            if (Math.abs(dirAxis) < 1.0e-9) {
                continue;
            }
            for (int e = 0; e < EYE_STEPS; e++) {
                double s = (double) e / (EYE_STEPS - 1);
                double feetX = pendingFromX[i] + (pendingToX[i] - pendingFromX[i]) * s;
                double feetY = pendingFromY[i] + (pendingToY[i] - pendingFromY[i]) * s;
                double feetZ = pendingFromZ[i] + (pendingToZ[i] - pendingFromZ[i]) * s;
                for (int h = 0; h < heights; h++) {
                    double eyeY = feetY + (h == 0 ? MAX_EYE_HEIGHT : h == 1 ? CROUCH_EYE_HEIGHT : MIN_EYE_HEIGHT);
                    double originAxis = axis == 0 ? feetX : axis == 1 ? eyeY : feetZ;
                    double along = (plane - originAxis) / dirAxis;
                    if (along <= 0.0) {
                        continue;
                    }
                    double hx = feetX + dirX * along - pendingCursorX[i];
                    double hy = eyeY + dirY * along - pendingCursorY[i];
                    double hz = feetZ + dirZ * along - pendingCursorZ[i];
                    double miss = Math.sqrt(hx * hx + hy * hy + hz * hz);
                    if (miss < best) {
                        best = miss;
                        if (best <= rayTolerance * 0.5) {
                            return best;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 0 = x, 1 = y, 2 = z, -1 = no axis. */
    static int axis(BlockFace face) {
        return switch (face) {
            case EAST, WEST -> 0;
            case UP, DOWN -> 1;
            case SOUTH, NORTH -> 2;
            default -> -1;
        };
    }

    private static double planeCoordinate(BlockFace face, int bx, int by, int bz) {
        return switch (face) {
            case EAST -> bx + 1.0;
            case WEST -> bx;
            case UP -> by + 1.0;
            case DOWN -> by;
            case SOUTH -> bz + 1.0;
            default -> bz;
        };
    }

    // ---- rotations / snap ---------------------------------------------------------------------

    @Override
    public void onRotation(LookChange change) {
        if (pendingCount > 0) {
            // The rotation sent right after the placement bounds how far the camera turned.
            judgePending(change.toYaw(), change.toPitch());
        }
        if (change.teleport() || player.vehicle().inVehicle()) {
            historySize = 0;
            return;
        }
        if (!checkSnap) {
            return;
        }
        long tick = player.clientTicks();
        if (historySize == 0) {
            push(change.fromYaw(), change.fromPitch(), tick);
        }
        push(historyYaw[at(0)] + wrap((double) change.toYaw() - (double) change.fromYaw()), change.toPitch(), tick);
        snap(tick);
    }

    private void push(double yaw, double pitch, long tick) {
        int slot = (historyHead + historySize) % HISTORY;
        if (historySize == HISTORY) {
            historyHead = (historyHead + 1) % HISTORY;
        } else {
            historySize++;
        }
        historyYaw[slot] = yaw;
        historyPitch[slot] = pitch;
        historyTick[slot] = tick;
        rotationCount++;
    }

    private int at(int age) {
        return (historyHead + historySize - 1 - age) % HISTORY;
    }

    private void snap(long tick) {
        if (historySize < 3 || isExempt()) {
            return;
        }
        int now = at(0);
        int before = at(1);
        double back = angle(historyYaw[now] - historyYaw[before], historyPitch[now] - historyPitch[before]);
        if (back < snapMin * 0.5) {
            return;
        }
        for (int d = 1; d < historySize - 1; d++) {
            int away = at(d);
            int origin = at(d + 1);
            if (tick - historyTick[away] > snapHold) {
                break;
            }
            if (rotationCount - 1 - d <= snapConsumed) {
                break;
            }
            double jump = angle(historyYaw[away] - historyYaw[origin], historyPitch[away] - historyPitch[origin]);
            if (jump < snapMin) {
                continue;
            }
            double returned = angle(historyYaw[now] - historyYaw[origin], historyPitch[now] - historyPitch[origin]);
            boolean placed = lastPlaceTick >= historyTick[origin] && lastPlaceTick <= tick;
            if (!placed || returned > Math.max(snapReturnDegrees, snapReturnRatio * jump)) {
                continue;
            }
            snapConsumed = rotationCount - 1;
            flag(1.0, "signal=snap away=" + VisibilityOptions.number(jump) + " miss="
                    + VisibilityOptions.number(returned) + " held=" + (tick - historyTick[away]));
            return;
        }
    }

    private static double angle(double yaw, double pitch) {
        double wrapped = wrap(yaw);
        return Math.sqrt(wrapped * wrapped + pitch * pitch);
    }

    static double wrap(double degrees) {
        return degrees - 360.0 * Math.rint(degrees / 360.0);
    }

    // ---- shared -------------------------------------------------------------------------------

    private void flag(double weight, String details) {
        if (debugging()) {
            debug(details + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(weight)) {
            violation(details);
        }
    }

    /**
     * Whether a ray from an eye of a player whose feet are at {@code (x, y, z)} can hit {@code face}
     * of the unit cube at the block position. Eye height spans {@link #MIN_EYE_HEIGHT}..{@link #MAX_EYE_HEIGHT}.
     * Faces without an axis ({@code OTHER}) are always "visible" (not judged).
     */
    static boolean faceVisible(BlockFace face, int bx, int by, int bz, double x, double y, double z,
                               double tolerance) {
        return switch (face) {
            case UP -> y + MAX_EYE_HEIGHT + tolerance >= by + 1.0;
            case DOWN -> y + MIN_EYE_HEIGHT - tolerance <= by;
            case EAST -> x + tolerance >= bx + 1.0;
            case WEST -> x - tolerance <= bx;
            case SOUTH -> z + tolerance >= bz + 1.0;
            case NORTH -> z - tolerance <= bz;
            default -> true;
        };
    }

    /** Whether the hit point lies on the plane of {@code face} of the unit cube. */
    static boolean cursorOnFace(BlockFace face, float cx, float cy, float cz, double tolerance) {
        return switch (face) {
            case UP -> Math.abs(cy - 1.0) <= tolerance;
            case DOWN -> Math.abs(cy) <= tolerance;
            case EAST -> Math.abs(cx - 1.0) <= tolerance;
            case WEST -> Math.abs(cx) <= tolerance;
            case SOUTH -> Math.abs(cz - 1.0) <= tolerance;
            case NORTH -> Math.abs(cz) <= tolerance;
            default -> true;
        };
    }
}
