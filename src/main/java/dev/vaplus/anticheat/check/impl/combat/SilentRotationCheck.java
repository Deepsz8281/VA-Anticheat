package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.check.RotationCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Locale;

/**
 * Silent rotation: the rotation the client sends is not the one its camera uses. Killauras,
 * scaffolds, auto-pearls and nukers rotate "silently" so the player keeps looking elsewhere.
 *
 * <p>Signals (one buffer):
 * <ul>
 *   <li>{@code move}: vanilla turns forward-only input ({@code W}, no strafe) into acceleration along
 *       the yaw. A client that moves by its camera but sends another yaw (silent rotation without
 *       movement correction) walks sideways or backwards relative to the sent yaw. Judged on the
 *       ground, with the 1.21.2+ input packet, a sent yaw that stayed within
 *       {@code move-stable-degrees} for {@link #YAW_HISTORY} packets (so the old momentum has
 *       decayed) and enough speed. {@code move-min-ticks} consecutive packets whose motion direction
 *       is more than {@code move-max-degrees} off the sent yaw raise the buffer.</li>
 *   <li>{@code snap}: the rotation jumps at least {@code snap-min-degrees}, an item use or a dig
 *       start happens, and within {@code snap-hold-ticks} the rotation jumps back to within
 *       max({@code snap-return-degrees}, {@code snap-return-ratio} x jump) of where it started.
 *       Attacks are judged by {@link AimCheck} and placements by the scaffold check, so only the
 *       remaining interactions are covered here (auto-pearl, silent potions, nuker).</li>
 * </ul>
 *
 * <p>Never judged: teleport rotations, vehicles, flight, gliding, swimming, sneaking (edge clamp),
 * horizontal collisions, liquids, slippery or unknown ground, pending velocity and the usual
 * exemption causes. Experimental; all numeric defaults are TUNING values.
 */
public final class SilentRotationCheck extends Check implements PositionCheck, RotationCheck, BlockCheck {

    public static final double DEFAULT_MOVE_MAX_DEGREES = 50.0;
    public static final int DEFAULT_MOVE_MIN_TICKS = 4;
    public static final double DEFAULT_MOVE_MIN_SPEED = 0.15;
    public static final double DEFAULT_MOVE_STABLE_DEGREES = 10.0;
    public static final double DEFAULT_SNAP_MIN_DEGREES = 35.0;
    public static final int DEFAULT_SNAP_HOLD_TICKS = 2;
    public static final double DEFAULT_SNAP_RETURN_RATIO = 0.15;
    public static final double DEFAULT_SNAP_RETURN_DEGREES = 4.0;
    /** TUNING: client ticks a registered velocity excuses the movement direction. */
    static final int VELOCITY_GRACE_TICKS = 40;
    /** Positions whose sent yaw must agree before the motion direction is judged. */
    static final int YAW_HISTORY = 4;
    /** Vanilla default block friction; anything slipperier keeps momentum too long. */
    static final double DEFAULT_FRICTION = 0.6;
    static final double MOVE_WEIGHT = 1.0;
    static final double SNAP_WEIGHT = 1.0;

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE,
            ExemptionCause.ABILITY_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE,
            ExemptionCause.KNOCKBACK, ExemptionCause.EXPLOSION, ExemptionCause.PORTAL,
            ExemptionCause.ELYTRA_BOOST, ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE,
            ExemptionCause.BOUNCE, ExemptionCause.LUNGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("silentrotation", CheckCategory.COMBAT)
            .displayName("SilentRotation")
            .description("Sent rotations that differ from the camera (moving off the sent yaw, snap-use-return)")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.01)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private boolean checkMove = true;
    private double moveMaxDegrees = DEFAULT_MOVE_MAX_DEGREES;
    private int moveMinTicks = DEFAULT_MOVE_MIN_TICKS;
    private double moveMinSpeed = DEFAULT_MOVE_MIN_SPEED;
    private double moveStableDegrees = DEFAULT_MOVE_STABLE_DEGREES;
    private boolean checkSnap = true;
    private double snapMin = DEFAULT_SNAP_MIN_DEGREES;
    private int snapHold = DEFAULT_SNAP_HOLD_TICKS;
    private double snapReturnRatio = DEFAULT_SNAP_RETURN_RATIO;
    private double snapReturnDegrees = DEFAULT_SNAP_RETURN_DEGREES;

    // move signal
    private final float[] yawHistory = new float[YAW_HISTORY];
    private int yawHead;
    private int yawSize;
    private int moveStreak;
    private double moveWorst;
    private long seenVelocities = -1;
    private long velocityTick = Long.MIN_VALUE / 2;

    // snap signal
    private boolean snapActive;
    private long snapTick;
    private double originYaw;
    private double originPitch;
    private double snapJump;
    private BlockInteraction.Kind snapAction;

    public SilentRotationCheck(PlayerData player, ComponentKey<WorldMirror> worldKey,
                               ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkMove = options.getBoolean("check-move", true);
        moveMaxDegrees = degrees(options, "move-max-degrees", DEFAULT_MOVE_MAX_DEGREES, 10.0, 180.0);
        moveMinTicks = ticks(options, "move-min-ticks", DEFAULT_MOVE_MIN_TICKS, 1, 100);
        moveMinSpeed = degrees(options, "move-min-speed", DEFAULT_MOVE_MIN_SPEED, 0.01, 5.0);
        moveStableDegrees = degrees(options, "move-stable-degrees", DEFAULT_MOVE_STABLE_DEGREES, 0.0, 90.0);
        checkSnap = options.getBoolean("check-snap", true);
        snapMin = degrees(options, "snap-min-degrees", DEFAULT_SNAP_MIN_DEGREES, 5.0, 180.0);
        snapHold = ticks(options, "snap-hold-ticks", DEFAULT_SNAP_HOLD_TICKS, 1, 40);
        snapReturnRatio = degrees(options, "snap-return-ratio", DEFAULT_SNAP_RETURN_RATIO, 0.0, 1.0);
        snapReturnDegrees = degrees(options, "snap-return-degrees", DEFAULT_SNAP_RETURN_DEGREES, 0.0, 90.0);
    }

    private static double degrees(CheckOptions options, String key, double fallback, double min, double max) {
        double value = options.getDouble(key, fallback);
        return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
    }

    private static int ticks(CheckOptions options, String key, int fallback, int min, int max) {
        int value = options.getInt(key, fallback);
        return value >= min && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        yawSize = 0;
        moveStreak = 0;
        seenVelocities = -1;
        snapActive = false;
    }

    // ------------------------------------------------------------------ move

    @Override
    public void onPosition(MovementUpdate update) {
        if (!checkMove) {
            return;
        }
        if (!update.positionChanged() || update.firstPosition() || update.teleport()) {
            breakMove();
            return;
        }
        long tick = player.clientTicks();
        VelocityState velocity = player.component(velocityKey);
        long registered = velocity.registered();
        if (seenVelocities < 0) {
            seenVelocities = registered;
        } else if (registered != seenVelocities) {
            seenVelocities = registered;
            velocityTick = tick;
        }
        float yaw = player.movement().yaw();
        pushYaw(yaw);
        if (!moveJudgeable(update, tick, velocity)) {
            moveStreak = 0;
            return;
        }
        double speed = Math.hypot(update.deltaX(), update.deltaZ());
        double motionYaw = Math.toDegrees(Math.atan2(-update.deltaX(), update.deltaZ()));
        double off = Math.abs(wrap(motionYaw - yaw));
        if (off <= moveMaxDegrees) {
            moveStreak = 0;
            lowerBuffer();
            return;
        }
        moveStreak++;
        moveWorst = moveStreak == 1 ? off : Math.max(moveWorst, off);
        if (moveStreak < moveMinTicks) {
            return;
        }
        String details = "move off=" + format(moveWorst) + " yaw=" + format(wrap(yaw))
                + " motion=" + format(motionYaw) + " speed=" + format(speed) + " ticks=" + moveStreak;
        moveStreak = 0;
        flag(MOVE_WEIGHT, details);
    }

    private void breakMove() {
        moveStreak = 0;
        yawSize = 0;
    }

    private void pushYaw(float yaw) {
        yawHistory[yawHead] = yaw;
        yawHead = (yawHead + 1) % YAW_HISTORY;
        if (yawSize < YAW_HISTORY) {
            yawSize++;
        }
    }

    private boolean yawStable(float yaw) {
        if (yawSize < YAW_HISTORY) {
            return false;
        }
        for (int i = 0; i < YAW_HISTORY; i++) {
            if (Math.abs(wrap(yawHistory[i] - yaw)) > moveStableDegrees) {
                return false;
            }
        }
        return true;
    }

    private boolean moveJudgeable(MovementUpdate update, long tick, VelocityState velocity) {
        PoseState pose = player.pose();
        if (!player.tickEndMode() || !pose.inputKnown()) {
            return false;
        }
        // forward only: any strafe or backward key legitimately turns the motion off the yaw
        if (!pose.forward() || pose.backward() || pose.left() || pose.right() || pose.shift() || pose.sneaking()) {
            return false;
        }
        if (!update.fromGround() || !update.toGround() || update.horizontalCollision()) {
            return false;
        }
        if (Math.hypot(update.deltaX(), update.deltaZ()) < moveMinSpeed || !yawStable(player.movement().yaw())) {
            return false;
        }
        if (isExempt() || player.exemptions().anyActive(EXEMPT_CAUSES) || velocity.size() > 0
                || tick - velocityTick <= VELOCITY_GRACE_TICKS) {
            return false;
        }
        if (player.vehicle().inVehicle() || player.abilities().flying() || pose.gliding() || pose.swimming()) {
            return false;
        }
        return groundPlain(update);
    }

    /** Block under the feet (vanilla's "block below that affects movement") is known, dry and not slippery. */
    private boolean groundPlain(MovementUpdate update) {
        WorldMirror world = player.component(worldKey);
        if (world.pending().any()) {
            return false;
        }
        int x = (int) Math.floor(update.toX());
        int z = (int) Math.floor(update.toZ());
        BlockSnapshot below = world.getBlock(x, (int) Math.floor(update.toY() - 0.5000001), z);
        BlockSnapshot feet = world.getBlock(x, (int) Math.floor(update.toY()), z);
        if (!below.known() || !feet.known()) {
            return false;
        }
        BlockProperties ground = below.properties();
        return ground.friction() <= DEFAULT_FRICTION + 1.0e-6
                && ground.liquid() == BlockProperties.Liquid.NONE
                && feet.properties().liquid() == BlockProperties.Liquid.NONE
                && !feet.properties().climbable();
    }

    // ------------------------------------------------------------------ snap

    @Override
    public void onRotation(LookChange change) {
        if (!checkSnap) {
            return;
        }
        if (change.teleport() || player.vehicle().inVehicle()) {
            snapActive = false;
            return;
        }
        long tick = player.clientTicks();
        if (snapActive && tick - snapTick > snapHold) {
            snapActive = false;
        }
        if (snapActive) {
            double back = Math.hypot(wrap(change.toYaw() - originYaw), change.toPitch() - originPitch);
            if (back <= Math.max(snapReturnDegrees, snapReturnRatio * snapJump)) {
                if (snapAction != null && !isExempt()) {
                    flag(SNAP_WEIGHT, "snap jump=" + format(snapJump) + " back=" + format(back)
                            + " ticks=" + (tick - snapTick) + " action=" + snapAction.name().toLowerCase(Locale.ROOT));
                }
                snapActive = false;
                return;
            }
        }
        double jump = Math.hypot(change.wrappedDeltaYaw(), change.deltaPitch());
        if (!snapActive && jump >= snapMin) {
            snapActive = true;
            snapTick = tick;
            originYaw = change.fromYaw();
            originPitch = change.fromPitch();
            snapJump = jump;
            snapAction = null;
        }
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (snapActive && (interaction.kind() == BlockInteraction.Kind.USE_ITEM
                || interaction.kind() == BlockInteraction.Kind.DIG_START)) {
            snapAction = interaction.kind();
        }
    }

    // ------------------------------------------------------------------ shared

    private void flag(double weight, String details) {
        if (debugging()) {
            debug(details + " buffer=" + format(buffer()));
        }
        if (raiseBuffer(weight)) {
            violation(details);
        }
    }

    static double wrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
