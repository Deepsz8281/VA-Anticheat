package dev.vaplus.anticheat.check.impl.world;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockSight;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * ESP: sustained aiming at players that are fully hidden behind opaque blocks.
 *
 * <p>ESP/cheat clients draw every player through walls. This check uses the mirror image: while a
 * tracked player is walled off ({@link BlockSight#lineOfSight} answers
 * {@link BlockSight.Occlusion#OCCLUDED}), the viewer's crosshair stays on that player
 * (perpendicular miss distance within {@code max-miss-distance} + {@code miss-per-block} per block)
 * for at least {@code min-ticks} and the hidden player moves at least
 * {@code min-target-movement} blocks during that time. A player watching a wall by coincidence
 * cannot keep the crosshair on a moving, invisible target for a whole second; an ESP user who is
 * following somebody through terrain does exactly that.
 *
 * <p>Every prerequisite fails closed: a target that is visible, too far away, not aimed at, whose
 * path contains non-mirrored blocks ({@link BlockSight.Occlusion#UNKNOWN}) or that does not move
 * resets the window and decays the buffer. Spectators are exempt through
 * {@link CheckCategory#COMBAT}; creative players are not, ESP is an advantage in every mode.
 *
 * <p>This is a heuristic (marked experimental): the numbers are TUNING values, and players who
 * deliberately track a wall (or a teammate behind one) can produce a false positive after roughly
 * {@code min-ticks}. Keep the buffer at 2 windows before the first alert on a busy server.
 */
public final class EspCheck extends Check implements ClientTickCheck {

    /** Chest height above the target's feet used as the aim point. */
    public static final double TARGET_HEIGHT = 1.0;
    /** Closer targets are treated as visible (a wall cannot be between the two). */
    public static final double MIN_DISTANCE = 1.5;

    public static final double DEFAULT_MAX_DISTANCE = 48.0;
    public static final double DEFAULT_MAX_MISS_DISTANCE = 1.0;
    public static final double DEFAULT_MISS_PER_BLOCK = 0.02;
    public static final int DEFAULT_MIN_TICKS = 20;
    public static final double DEFAULT_MIN_TARGET_MOVEMENT = 2.0;
    public static final int DEFAULT_INTERVAL = 2;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("esp", CheckCategory.COMBAT)
            .displayName("ESP")
            .description("Keeps aiming at players hidden behind opaque blocks")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<NearbyPlayers> trackedKey;

    private double maxDistance = DEFAULT_MAX_DISTANCE;
    private double maxMissDistance = DEFAULT_MAX_MISS_DISTANCE;
    private double missPerBlock = DEFAULT_MISS_PER_BLOCK;
    private int minTicks = DEFAULT_MIN_TICKS;
    private double minTargetMovement = DEFAULT_MIN_TARGET_MOVEMENT;
    private int interval = DEFAULT_INTERVAL;

    private int ticks;
    private int trackedId = -1;
    private int streak;
    private double trackedMovement;
    private double lastX;
    private double lastY;
    private double lastZ;

    /** Reused look vector; owner thread only. */
    private final double[] look = new double[3];

    public EspCheck(PlayerData player, ComponentKey<WorldMirror> worldKey,
                    ComponentKey<NearbyPlayers> trackedKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.trackedKey = trackedKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxDistance = VisibilityOptions.positive(options, "max-distance", DEFAULT_MAX_DISTANCE);
        maxMissDistance = VisibilityOptions.tolerance(options, "max-miss-distance", DEFAULT_MAX_MISS_DISTANCE);
        missPerBlock = VisibilityOptions.tolerance(options, "miss-per-block", DEFAULT_MISS_PER_BLOCK);
        minTicks = VisibilityOptions.atLeast(options, "min-ticks", DEFAULT_MIN_TICKS, 1);
        minTargetMovement = VisibilityOptions.tolerance(options, "min-target-movement",
                DEFAULT_MIN_TARGET_MOVEMENT);
        interval = VisibilityOptions.atLeast(options, "interval", DEFAULT_INTERVAL, 1);
    }

    @Override
    protected void onReset() {
        resetTracking();
    }

    @Override
    public void onClientTick() {
        ticks = ticks == Integer.MAX_VALUE ? 1 : ticks + 1;
        if (ticks % interval != 0) {
            return;
        }
        if (isExempt()) {
            resetTracking();
            return;
        }
        MovementState movement = player.movement();
        if (!movement.positionKnown() || !movement.rotationKnown()) {
            resetTracking();
            return;
        }
        NearbyPlayers nearby = player.component(trackedKey);
        if (nearby.isEmpty()) {
            resetTracking();
            return;
        }
        PoseState pose = player.pose();
        double eyeHeight = pose.sneaking() ? FreecamCheck.SNEAKING_EYE_HEIGHT : FreecamCheck.STANDING_EYE_HEIGHT;
        double eyeX = movement.x();
        double eyeY = movement.y() + eyeHeight;
        double eyeZ = movement.z();
        FreecamCheck.direction(movement.yaw(), movement.pitch(), look);
        WorldMirror world = player.component(worldKey);

        int bestId = -1;
        double bestMiss = 0.0;
        double bestDistance = 0.0;
        double bestX = 0.0;
        double bestY = 0.0;
        double bestZ = 0.0;
        for (int i = 0; i < nearby.size(); i++) {
            if (!nearby.isPlayer(i)) {
                // ESP is about finding hidden players; mobs behind walls are tracked for combat only.
                continue;
            }
            double targetX = nearby.x(i);
            double targetY = nearby.y(i) + TARGET_HEIGHT;
            double targetZ = nearby.z(i);
            double vx = targetX - eyeX;
            double vy = targetY - eyeY;
            double vz = targetZ - eyeZ;
            double distance = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (distance > maxDistance || distance < MIN_DISTANCE) {
                continue;
            }
            double along = vx * look[0] + vy * look[1] + vz * look[2];
            if (along <= 0.0) {
                // Behind the viewer: the perpendicular distance to the look <em>line</em> would be
                // small for a target exactly opposite, so the direction test comes first.
                continue;
            }
            double miss = perpendicularMiss(vx, vy, vz, distance);
            if (miss > maxMissDistance + missPerBlock * distance) {
                continue;
            }
            if (BlockSight.lineOfSight(world, eyeX, eyeY, eyeZ, targetX, targetY, targetZ)
                    != BlockSight.Occlusion.OCCLUDED) {
                continue;
            }
            if (bestId == -1 || miss < bestMiss) {
                bestId = nearby.id(i);
                bestMiss = miss;
                bestDistance = distance;
                bestX = targetX;
                bestY = targetY;
                bestZ = targetZ;
            }
        }

        if (bestId == -1) {
            resetTracking();
            lowerBuffer();
            return;
        }
        if (bestId == trackedId) {
            double dx = bestX - lastX;
            double dy = bestY - lastY;
            double dz = bestZ - lastZ;
            trackedMovement += Math.sqrt(dx * dx + dy * dy + dz * dz);
            streak++;
        } else {
            trackedId = bestId;
            streak = 1;
            trackedMovement = 0.0;
        }
        lastX = bestX;
        lastY = bestY;
        lastZ = bestZ;

        int requiredSamples = Math.max(1, (minTicks + interval - 1) / interval);
        if (streak < requiredSamples || trackedMovement < minTargetMovement) {
            if (debugging()) {
                debug("locking hidden target=" + bestId + " streak=" + streak + "/" + requiredSamples
                        + " movement=" + VisibilityOptions.number(trackedMovement)
                        + " miss=" + VisibilityOptions.number(bestMiss));
            }
            return;
        }
        if (debugging()) {
            debug("confirmed hidden target=" + bestId + " streak=" + streak
                    + " movement=" + VisibilityOptions.number(trackedMovement)
                    + " distance=" + VisibilityOptions.number(bestDistance)
                    + " miss=" + VisibilityOptions.number(bestMiss)
                    + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("entity=" + bestId
                    + " samples=" + streak
                    + " movement=" + VisibilityOptions.number(trackedMovement)
                    + " distance=" + VisibilityOptions.number(bestDistance)
                    + " miss=" + VisibilityOptions.number(bestMiss));
            resetBuffer();
        }
        resetTracking();
    }

    /** Distance between the target and the look ray, in blocks. */
    private double perpendicularMiss(double vx, double vy, double vz, double distance) {
        double crossX = vy * look[2] - vz * look[1];
        double crossY = vz * look[0] - vx * look[2];
        double crossZ = vx * look[1] - vy * look[0];
        return Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ) / distance;
    }

    private void resetTracking() {
        trackedId = -1;
        streak = 0;
        trackedMovement = 0.0;
    }

    /** Consecutive samples the current hidden target has been aimed at (tests, diagnostics). */
    public int streak() {
        return streak;
    }

    /** Distance the current hidden target moved during the streak (tests, diagnostics). */
    public double trackedMovement() {
        return trackedMovement;
    }
}
