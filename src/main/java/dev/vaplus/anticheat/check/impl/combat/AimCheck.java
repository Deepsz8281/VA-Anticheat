package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.CombatCheck;
import dev.vaplus.anticheat.check.RotationCheck;
import dev.vaplus.anticheat.check.impl.world.FreecamCheck;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;

/**
 * Aim: rotations no mouse produces, sent while fighting. Aimed at killauras, "silent" auras, aim
 * assists and crystal auras that <em>do</em> rotate towards their target (Meteor's KillAura rotates
 * by default, so the plain angle test of {@link KillauraCheck} never sees it).
 *
 * <p>Signals (one buffer):
 * <ul>
 *   <li>{@code gcd}: vanilla rotation deltas are whole multiples of the mouse grid
 *       ({@link SensitivityEstimator}). The grid is learnt from rotations <em>outside</em> fights
 *       (no attack within {@code combat-window} ticks before or after); inside a fight every
 *       yaw/pitch delta is tested against it. Rotations computed towards a target fall between grid
 *       steps. A window of {@code sample-window} combat deltas with at least {@code min-off-grid}
 *       (and {@code off-grid-ratio}) off-grid deltas raises the buffer.</li>
 *   <li>{@code lock}: the sent rotation points at the exact centre of the target's hitbox. Auras
 *       aim with {@code atan2} at the box centre of the target as the client renders it; the server
 *       does not know the client's interpolated target position, so the error is the minimum over
 *       the target's recent path ({@link #LOCK_HISTORY} ticks of tracked positions, sampled along
 *       each segment) seen from the current and the previous eye position. At least
 *       {@code lock-min-attacks} of the last {@code lock-window} judged attacks within
 *       {@code lock-degrees} on both axes raise the buffer. A human's crosshair lands somewhere on
 *       the body, practically never on its centre point several times in a row.</li>
 *   <li>{@code snap}: silent rotations. The rotation jumps by at least {@code snap-min-degrees},
 *       an attack happens, and within {@code snap-hold-ticks} the rotation jumps back to within
 *       {@code snap-return-ratio} of the jump (at least {@code snap-return-degrees}) of where it
 *       started. The player's camera never moved; only the packets did. A human flick does not
 *       return to its exact start point.</li>
 * </ul>
 *
 * <p>Never judged: teleport rotations, vehicles (boats turn the rider's yaw), pitch at the ±90°
 * clamp, yaw beyond ±{@link #MAX_JUDGED_YAW} (float precision), deltas above
 * {@code max-multiple} grid steps, players whose grid is unknown (cinematic camera, controllers,
 * touch/Bedrock input) and grids finer than {@code min-grid} (very low sensitivity).
 * Experimental: zoom mods that change the sensitivity mid-fight produce off-grid deltas too.
 */
public final class AimCheck extends Check implements RotationCheck, CombatCheck, ClientTickCheck {

    public static final int DEFAULT_COMBAT_WINDOW = 20;
    public static final int DEFAULT_SAMPLE_WINDOW = 20;
    public static final int DEFAULT_MIN_OFF_GRID = 8;
    public static final double DEFAULT_OFF_GRID_RATIO = 0.5;
    public static final double DEFAULT_RESIDUAL = 0.15;
    public static final double DEFAULT_MIN_GRID = 0.02;
    public static final double DEFAULT_GRID_SUPPORT = 0.75;
    public static final double DEFAULT_MAX_MULTIPLE = 150.0;
    public static final double DEFAULT_SNAP_MIN_DEGREES = 35.0;
    public static final int DEFAULT_SNAP_HOLD_TICKS = 10;
    public static final double DEFAULT_SNAP_RETURN_RATIO = 0.15;
    public static final double DEFAULT_SNAP_RETURN_DEGREES = 4.0;
    public static final double DEFAULT_LOCK_DEGREES = 0.25;
    public static final int DEFAULT_LOCK_WINDOW = 8;
    public static final int DEFAULT_LOCK_MIN_ATTACKS = 5;
    public static final double DEFAULT_LOCK_MIN_DISTANCE = 1.5;
    static final double MAX_JUDGED_YAW = 10_000.0;
    static final double PITCH_CLAMP = 89.99;
    static final double MIN_DELTA = 1.0e-3;
    static final int PENDING = 64;
    static final int HISTORY = 16;
    static final int LOCK_HISTORY = 20;
    static final int LOCK_SEGMENT_STEPS = 4;
    static final double LOCK_MAX_DISTANCE = 6.5;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("aim", CheckCategory.COMBAT)
            .displayName("Aim")
            .description("Rotations off the mouse grid, locked on the hitbox centre or snapping back (silent aura / aimbot)")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.02)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE)
            .build();

    private final ComponentKey<NearbyPlayers> trackedKey;
    private final SensitivityEstimator estimator =
            new SensitivityEstimator(DEFAULT_MIN_GRID, DEFAULT_GRID_SUPPORT);

    private boolean checkGcd = true;
    private boolean checkSnap = true;
    private boolean checkLock = true;
    private int combatWindow = DEFAULT_COMBAT_WINDOW;
    private int sampleWindow = DEFAULT_SAMPLE_WINDOW;
    private int minOffGrid = DEFAULT_MIN_OFF_GRID;
    private double offGridRatio = DEFAULT_OFF_GRID_RATIO;
    private double residualLimit = DEFAULT_RESIDUAL;
    private double maxMultiple = DEFAULT_MAX_MULTIPLE;
    private double snapMin = DEFAULT_SNAP_MIN_DEGREES;
    private int snapHold = DEFAULT_SNAP_HOLD_TICKS;
    private double snapReturnRatio = DEFAULT_SNAP_RETURN_RATIO;
    private double snapReturnDegrees = DEFAULT_SNAP_RETURN_DEGREES;
    private double lockDegrees = DEFAULT_LOCK_DEGREES;
    private int lockWindow = DEFAULT_LOCK_WINDOW;
    private int lockMinAttacks = DEFAULT_LOCK_MIN_ATTACKS;
    private double lockMinDistance = DEFAULT_LOCK_MIN_DISTANCE;

    private long lastAttackTick = Long.MIN_VALUE / 2;

    // samples whose fight membership is not decided yet (FIFO ring)
    private final double[] pendingDelta = new double[PENDING];
    private final long[] pendingTick = new long[PENDING];
    private int pendingHead;
    private int pendingSize;

    // gcd outcomes (ring)
    private final boolean[] outcomes = new boolean[64];
    private int outcomeHead;
    private int outcomeSize;
    private int offGridCount;
    private double worstResidual;

    // snap: recent sent rotations (ring, unwrapped yaw)
    private final double[] historyYaw = new double[HISTORY];
    private final double[] historyPitch = new double[HISTORY];
    private final long[] historyTick = new long[HISTORY];
    private int historyHead;
    private int historySize;
    /** Index (absolute count) of the newest departure that already produced a snap verdict. */
    private long snapConsumed = -1L;
    private long rotationCount;

    // lock: path of the current target
    private int lockTarget = -1;
    private final double[] pathX = new double[LOCK_HISTORY];
    private final double[] pathY = new double[LOCK_HISTORY];
    private final double[] pathZ = new double[LOCK_HISTORY];
    private int pathHead;
    private int pathSize;
    private final boolean[] lockOutcomes = new boolean[32];
    private int lockHead;
    private int lockSize;
    private int lockedCount;
    private double bestLockError;

    public AimCheck(PlayerData player, ComponentKey<NearbyPlayers> trackedKey) {
        super(player, DESCRIPTOR);
        this.trackedKey = trackedKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkGcd = options.getBoolean("check-gcd", true);
        checkSnap = options.getBoolean("check-snap", true);
        checkLock = options.getBoolean("check-lock", true);
        combatWindow = Math.min(200, CombatOptions.atLeast(options, "combat-window", DEFAULT_COMBAT_WINDOW, 2));
        sampleWindow = Math.min(outcomes.length,
                CombatOptions.atLeast(options, "sample-window", DEFAULT_SAMPLE_WINDOW, 5));
        minOffGrid = Math.min(sampleWindow, CombatOptions.atLeast(options, "min-off-grid", DEFAULT_MIN_OFF_GRID, 2));
        offGridRatio = Math.min(1.0, CombatOptions.positive(options, "off-grid-ratio", DEFAULT_OFF_GRID_RATIO));
        residualLimit = Math.min(0.45, CombatOptions.positive(options, "residual", DEFAULT_RESIDUAL));
        maxMultiple = CombatOptions.positive(options, "max-multiple", DEFAULT_MAX_MULTIPLE);
        snapMin = Math.min(180.0, CombatOptions.positive(options, "snap-min-degrees", DEFAULT_SNAP_MIN_DEGREES));
        snapHold = Math.min(HISTORY - 2, CombatOptions.atLeast(options, "snap-hold-ticks", DEFAULT_SNAP_HOLD_TICKS, 1));
        snapReturnRatio = Math.min(0.5, CombatOptions.positive(options, "snap-return-ratio", DEFAULT_SNAP_RETURN_RATIO));
        snapReturnDegrees = Math.min(30.0,
                CombatOptions.positive(options, "snap-return-degrees", DEFAULT_SNAP_RETURN_DEGREES));
        lockDegrees = Math.min(5.0, CombatOptions.positive(options, "lock-degrees", DEFAULT_LOCK_DEGREES));
        lockWindow = Math.min(lockOutcomes.length,
                CombatOptions.atLeast(options, "lock-window", DEFAULT_LOCK_WINDOW, 2));
        lockMinAttacks = Math.min(lockWindow,
                CombatOptions.atLeast(options, "lock-min-attacks", DEFAULT_LOCK_MIN_ATTACKS, 2));
        lockMinDistance = CombatOptions.positive(options, "lock-min-distance", DEFAULT_LOCK_MIN_DISTANCE);
        estimator.configure(CombatOptions.positive(options, "min-grid", DEFAULT_MIN_GRID),
                Math.min(1.0, CombatOptions.positive(options, "grid-support", DEFAULT_GRID_SUPPORT)));
        // The outcome rings are indexed modulo their window size.
        clearOutcomes();
        clearLockOutcomes();
    }

    @Override
    protected void onReset() {
        pendingSize = 0;
        clearOutcomes();
        clearLockOutcomes();
        historySize = 0;
        lockTarget = -1;
        pathSize = 0;
        lastAttackTick = Long.MIN_VALUE / 2;
    }

    /** Estimated mouse grid in degrees ({@code NaN} while unknown). */
    public double grid() {
        return estimator.grid();
    }

    // ---- attacks ------------------------------------------------------------------------------

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked()) {
            return;
        }
        long tick = player.clientTicks();
        lastAttackTick = tick;
        // Rotations sent shortly before the first hit already belong to the fight.
        long oldest = tick - combatWindow;
        while (pendingSize > 0) {
            int index = pendingHead;
            pendingHead = (pendingHead + 1) % PENDING;
            pendingSize--;
            if (pendingTick[index] >= oldest) {
                judge(pendingDelta[index]);
            } else {
                estimator.add(pendingDelta[index]);
            }
        }
        if (checkLock && !isExempt() && !player.vehicle().inVehicle()) {
            lock(attack.entityId());
        }
    }

    @Override
    public void onClientTick() {
        if (lockTarget < 0) {
            return;
        }
        NearbyPlayers tracked = player.component(trackedKey);
        int index = tracked.indexOf(lockTarget);
        if (index < 0) {
            lockTarget = -1;
            pathSize = 0;
            return;
        }
        int slot = (pathHead + pathSize) % LOCK_HISTORY;
        if (pathSize == LOCK_HISTORY) {
            pathHead = (pathHead + 1) % LOCK_HISTORY;
        } else {
            pathSize++;
        }
        pathX[slot] = tracked.x(index);
        pathY[slot] = tracked.centerY(index);
        pathZ[slot] = tracked.z(index);
    }

    // ---- lock ---------------------------------------------------------------------------------

    private void lock(int entityId) {
        NearbyPlayers tracked = player.component(trackedKey);
        int index = tracked.indexOf(entityId);
        MovementState movement = player.movement();
        if (index < 0 || !movement.positionKnown() || !movement.rotationKnown()) {
            return;
        }
        if (entityId != lockTarget) {
            // New target: its path starts now, the first hit is not judged.
            lockTarget = entityId;
            pathHead = 0;
            pathSize = 0;
            return;
        }
        if (pathSize < 2) {
            return;
        }
        double eyeHeight = player.pose().sneaking()
                ? FreecamCheck.SNEAKING_EYE_HEIGHT : FreecamCheck.STANDING_EYE_HEIGHT;
        double yaw = movement.yaw();
        double pitch = movement.pitch();
        double targetX = tracked.x(index);
        double targetY = tracked.centerY(index);
        double targetZ = tracked.z(index);
        double distance = Math.sqrt(square(targetX - movement.x()) + square(targetZ - movement.z()));
        if (distance < lockMinDistance || distance > LOCK_MAX_DISTANCE) {
            return;
        }
        double error = Math.min(
                pathError(movement.x(), movement.y() + eyeHeight, movement.z(), yaw, pitch,
                        targetX, targetY, targetZ),
                pathError(movement.lastX(), movement.lastY() + eyeHeight, movement.lastZ(), yaw, pitch,
                        targetX, targetY, targetZ));
        boolean locked = error <= lockDegrees;
        pushLock(locked, error);
        if (debugging()) {
            debug("lock entity=" + entityId + " error=" + CombatOptions.number(error)
                    + " locked=" + lockedCount + "/" + lockSize);
        }
        if (lockSize >= lockWindow && lockedCount >= lockMinAttacks) {
            String details = "lock entity=" + entityId + " locked=" + lockedCount + "/" + lockSize
                    + " best=" + CombatOptions.number(bestLockError)
                    + " limit=" + CombatOptions.number(lockDegrees)
                    + " buffer=" + CombatOptions.number(buffer());
            clearLockOutcomes();
            if (raiseBuffer(1.0)) {
                violation(details);
                resetBuffer();
            }
        }
    }

    /**
     * Smallest angular error (max of the yaw and pitch error, degrees) between the sent rotation and
     * the direction from the eye to any point of the target's recent path (plus its current spot).
     */
    private double pathError(double eyeX, double eyeY, double eyeZ, double yaw, double pitch,
                             double nowX, double nowY, double nowZ) {
        double best = rotationError(eyeX, eyeY, eyeZ, yaw, pitch, nowX, nowY, nowZ);
        double prevX = nowX;
        double prevY = nowY;
        double prevZ = nowZ;
        for (int i = pathSize - 1; i >= 0; i--) {
            int slot = (pathHead + i) % LOCK_HISTORY;
            double x = pathX[slot];
            double y = pathY[slot];
            double z = pathZ[slot];
            for (int step = 0; step < LOCK_SEGMENT_STEPS; step++) {
                double t = (double) step / LOCK_SEGMENT_STEPS;
                double error = rotationError(eyeX, eyeY, eyeZ, yaw, pitch,
                        x + (prevX - x) * t, y + (prevY - y) * t, z + (prevZ - z) * t);
                if (error < best) {
                    best = error;
                }
            }
            prevX = x;
            prevY = y;
            prevZ = z;
        }
        return best;
    }

    /** Max of yaw and pitch error between a rotation and the exact direction to a point. */
    static double rotationError(double eyeX, double eyeY, double eyeZ, double yaw, double pitch,
                                double x, double y, double z) {
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-6) {
            return 180.0;
        }
        double wantYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double wantPitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        double yawError = Math.abs(wrap(yaw - wantYaw));
        double pitchError = Math.abs(pitch - wantPitch);
        return Math.max(yawError, pitchError);
    }

    private void pushLock(boolean locked, double error) {
        if (lockSize == lockWindow) {
            if (lockOutcomes[lockHead]) {
                lockedCount--;
            }
            lockHead = (lockHead + 1) % lockWindow;
            lockSize--;
        }
        lockOutcomes[(lockHead + lockSize) % lockWindow] = locked;
        lockSize++;
        if (locked) {
            lockedCount++;
        }
        bestLockError = lockSize == 1 ? error : Math.min(bestLockError, error);
    }

    private void clearLockOutcomes() {
        lockHead = 0;
        lockSize = 0;
        lockedCount = 0;
        bestLockError = 0.0;
    }

    // ---- rotations ----------------------------------------------------------------------------

    @Override
    public void onRotation(LookChange change) {
        if (change.teleport() || isExempt() || player.vehicle().inVehicle()) {
            historySize = 0;
            return;
        }
        long tick = player.clientTicks();
        double yawDelta = wrap((double) change.toYaw() - (double) change.fromYaw());
        double pitchDelta = (double) change.toPitch() - (double) change.fromPitch();

        if (checkSnap) {
            recordRotation(change, yawDelta, tick);
            snap(tick);
        }
        if (!checkGcd) {
            return;
        }
        if (Math.abs(change.fromYaw()) < MAX_JUDGED_YAW && Math.abs(change.toYaw()) < MAX_JUDGED_YAW) {
            sample(Math.abs(yawDelta), tick);
        }
        if (Math.abs(change.fromPitch()) < PITCH_CLAMP && Math.abs(change.toPitch()) < PITCH_CLAMP) {
            sample(Math.abs(pitchDelta), tick);
        }
    }

    // ---- gcd ----------------------------------------------------------------------------------

    private void sample(double delta, long tick) {
        if (delta < MIN_DELTA) {
            return;
        }
        if (tick - lastAttackTick <= combatWindow) {
            judge(delta);
            return;
        }
        // Undecided: becomes a learning sample once no attack follows within the window.
        long oldest = tick - combatWindow;
        while (pendingSize > 0 && (pendingTick[pendingHead] < oldest || pendingSize == PENDING)) {
            estimator.add(pendingDelta[pendingHead]);
            pendingHead = (pendingHead + 1) % PENDING;
            pendingSize--;
        }
        int slot = (pendingHead + pendingSize) % PENDING;
        pendingDelta[slot] = delta;
        pendingTick[slot] = tick;
        pendingSize++;
    }

    private void judge(double delta) {
        double grid = estimator.grid();
        if (Double.isNaN(grid) || delta / grid > maxMultiple) {
            return;
        }
        double residual = SensitivityEstimator.residual(delta, grid);
        boolean offGrid = residual > residualLimit;
        pushOutcome(offGrid, residual);
        if (!offGrid) {
            lowerBuffer();
        }
        if (outcomeSize < sampleWindow) {
            return;
        }
        if (offGridCount >= minOffGrid && offGridCount >= offGridRatio * outcomeSize) {
            String details = "gcd off-grid=" + offGridCount + "/" + outcomeSize
                    + " grid=" + CombatOptions.number(grid)
                    + " worst=" + CombatOptions.number(worstResidual)
                    + " buffer=" + CombatOptions.number(buffer());
            if (debugging()) {
                debug(details);
            }
            clearOutcomes();
            if (raiseBuffer(1.0)) {
                violation(details);
                resetBuffer();
            }
        }
    }

    private void pushOutcome(boolean offGrid, double residual) {
        if (outcomeSize == sampleWindow) {
            if (outcomes[outcomeHead]) {
                offGridCount--;
            }
            outcomeHead = (outcomeHead + 1) % sampleWindow;
            outcomeSize--;
        }
        outcomes[(outcomeHead + outcomeSize) % sampleWindow] = offGrid;
        outcomeSize++;
        if (offGrid) {
            offGridCount++;
            worstResidual = Math.max(worstResidual, residual);
        }
    }

    private void clearOutcomes() {
        outcomeHead = 0;
        outcomeSize = 0;
        offGridCount = 0;
        worstResidual = 0.0;
    }

    // ---- snap ---------------------------------------------------------------------------------

    private void recordRotation(LookChange change, double yawDelta, long tick) {
        double yaw;
        double pitch = change.toPitch();
        if (historySize == 0) {
            // Seed with the start of this change so the first jump has a departure point.
            pushHistory(change.fromYaw(), change.fromPitch(), tick);
            yaw = change.fromYaw() + yawDelta;
        } else {
            yaw = historyYaw[newest()] + yawDelta;
        }
        pushHistory(yaw, pitch, tick);
    }

    private void pushHistory(double yaw, double pitch, long tick) {
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

    private int newest() {
        return (historyHead + historySize - 1) % HISTORY;
    }

    private int at(int ageFromNewest) {
        return (historyHead + historySize - 1 - ageFromNewest) % HISTORY;
    }

    /**
     * Looks for departure → (hold) → return: entry {@code d} jumped away from {@code d-1} by at least
     * {@code snap-min-degrees}, an attack happened while away, and the newest rotation is a jump of
     * comparable size that lands next to the departure point.
     */
    private void snap(long tick) {
        if (historySize < 3) {
            return;
        }
        int now = newest();
        int before = at(1);
        double back = angle(historyYaw[now] - historyYaw[before], historyPitch[now] - historyPitch[before]);
        if (back < snapMin * 0.5) {
            return;
        }
        // d = age of the departure entry (the rotation that jumped away); d >= 1.
        for (int d = 1; d < historySize - 1; d++) {
            int away = at(d);
            int origin = at(d + 1);
            if (tick - historyTick[away] > snapHold) {
                break;
            }
            long departureId = rotationCount - 1 - d;
            if (departureId <= snapConsumed) {
                break;
            }
            double jump = angle(historyYaw[away] - historyYaw[origin], historyPitch[away] - historyPitch[origin]);
            if (jump < snapMin) {
                continue;
            }
            double returned = angle(historyYaw[now] - historyYaw[origin], historyPitch[now] - historyPitch[origin]);
            double tolerance = Math.max(snapReturnDegrees, snapReturnRatio * jump);
            boolean attacked = lastAttackTick >= historyTick[origin] && lastAttackTick <= tick;
            if (returned > tolerance || !attacked) {
                continue;
            }
            snapConsumed = rotationCount - 1;
            String details = "snap away=" + CombatOptions.number(jump)
                    + " back=" + CombatOptions.number(back)
                    + " miss=" + CombatOptions.number(returned)
                    + " held=" + (tick - historyTick[away])
                    + " buffer=" + CombatOptions.number(buffer());
            if (debugging()) {
                debug(details);
            }
            if (raiseBuffer(1.0)) {
                violation(details);
                resetBuffer();
            }
            return;
        }
    }

    private static double angle(double yaw, double pitch) {
        double wrapped = wrap(yaw);
        return Math.sqrt(wrapped * wrapped + pitch * pitch);
    }

    private static double square(double value) {
        return value * value;
    }

    static double wrap(double degrees) {
        return degrees - 360.0 * Math.rint(degrees / 360.0);
    }
}
