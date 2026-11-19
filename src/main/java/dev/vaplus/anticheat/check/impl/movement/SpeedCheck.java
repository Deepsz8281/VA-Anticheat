package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.AttributeState;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * Speed: horizontal movement faster than vanilla allows. Two signals share one buffer.
 *
 * <h2>{@code simulation}</h2>
 * Judges only {@link SimulationResult.Status#COMPLETE COMPLETE} results: a packet counts as
 * suspicious when the best candidate's horizontal offset exceeds {@code threshold} plus the engine's
 * {@link SimulationResult#uncertainty() uncertainty}. With {@code ignore-slowdown} (default on) a
 * packet that is clearly slower than the prediction is not counted.
 *
 * <h2>{@code limit}</h2>
 * A physical upper bound that does not need the simulation, so speed is also caught where the
 * engine skips. Vanilla moves the player each tick by its motion plus the input acceleration and
 * multiplies the motion by the friction afterwards, so the horizontal distance of one tick is at most
 * <pre>previous distance x previous friction + acceleration + sprint-jump boost</pre>
 * with friction = block friction x 0.91 on the ground (0.91 in the air), acceleration = movement
 * speed x 1.3 (sprint) x 0.216 / block friction^3 on the ground (0.026 in the air) and the 0.2 boost
 * on a ground tick with upward motion. Speed effects and Soul Speed arrive as movement-speed
 * attribute modifiers. A tick above {@code (bound) x 1.02 + limit-tolerance} raises the buffer.
 * Only 1.21.2+ clients (exact tick boundaries) and single-tick packets are judged; ticks with a
 * pending or just applied server velocity, liquids, climbable, unknown or bouncy blocks, pistons,
 * vehicles, flight, gliding, swimming and the usual exemption causes are skipped.
 *
 * <p>All numeric defaults here are TUNING values (engineering estimates), not vanilla constants.
 */
public final class SpeedCheck extends Check implements PositionCheck {

    /** TUNING: horizontal offset (blocks per packet) tolerated on top of the engine uncertainty. */
    public static final double DEFAULT_THRESHOLD = 0.03;
    /** TUNING: blocks per tick tolerated above the physical bound (entity pushing, float error). */
    public static final double DEFAULT_LIMIT_TOLERANCE = 0.04;
    /** TUNING: relative margin on the physical bound. */
    static final double LIMIT_MARGIN = 1.02;
    /** TUNING: client ticks after a pending server velocity during which nothing is judged. */
    static final int VELOCITY_GRACE_TICKS = 2;
    /** TUNING: ticks after leaving a vehicle during which nothing is judged. */
    static final int DISMOUNT_GRACE_TICKS = 10;
    static final double AIR_FRICTION = 0.91;
    static final double AIR_ACCELERATION = 0.026;
    static final double SPRINT_JUMP_BOOST = 0.2;
    static final double SPRINT_MULTIPLIER = 1.3;
    static final double FRICTION_SPEED = 0.21600002;
    static final String SPRINT_MODIFIER = "minecraft:sprinting";

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE,
            ExemptionCause.ABILITY_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE,
            ExemptionCause.KNOCKBACK, ExemptionCause.EXPLOSION, ExemptionCause.PORTAL,
            ExemptionCause.ELYTRA_BOOST, ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE,
            ExemptionCause.BOUNCE, ExemptionCause.LUNGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("speed", CheckCategory.MOVEMENT)
            .displayName("Speed")
            .description("Horizontal movement faster than the simulation or the physical limit allows")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(10.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE)
            .build();

    /** Both {@code null} without the simulation module: only the simulation signal runs. */
    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private double threshold = DEFAULT_THRESHOLD;
    private boolean ignoreSlowdown = true;
    private boolean checkLimit = true;
    private double limitTolerance = DEFAULT_LIMIT_TOLERANCE;

    private boolean previousValid;
    private double previousDistance;
    private double previousFriction;
    private long seenVelocities = -1;
    private long velocityTick = Long.MIN_VALUE / 2;
    private int limitFailures;

    public SpeedCheck(PlayerData player) {
        this(player, null, null);
    }

    public SpeedCheck(PlayerData player, ComponentKey<WorldMirror> worldKey, ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        threshold = MovementOptions.tolerance(options, "threshold", DEFAULT_THRESHOLD);
        ignoreSlowdown = options.getBoolean("ignore-slowdown", true);
        checkLimit = options.getBoolean("check-limit", true);
        limitTolerance = MovementOptions.tolerance(options, "limit-tolerance", DEFAULT_LIMIT_TOLERANCE);
    }

    @Override
    protected void onReset() {
        previousValid = false;
        seenVelocities = -1;
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (checkLimit && worldKey != null && velocityKey != null) {
            limit(update);
        }
        SimulationResult result = update.simulation();
        if (!result.complete() || isExempt()) {
            return;
        }
        double offset = result.horizontalOffset();
        double limit = threshold + result.uncertainty();
        if (offset <= limit) {
            lowerBuffer();
            return;
        }
        double reported = update.horizontalDistance();
        double predicted = Math.sqrt(result.predictedX() * result.predictedX() + result.predictedZ() * result.predictedZ());
        if (ignoreSlowdown && reported + threshold < predicted) {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug("offset=" + MovementOptions.number(offset) + " limit=" + MovementOptions.number(limit)
                    + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("offset=" + MovementOptions.number(offset)
                    + " limit=" + MovementOptions.number(limit)
                    + " moved=" + MovementOptions.number(reported)
                    + " predicted=" + MovementOptions.number(predicted)
                    + " ticks=" + result.ticksSimulated()
                    + " uncertainty=" + MovementOptions.number(result.uncertainty()));
        }
    }

    // ------------------------------------------------------------------ limit signal

    private void limit(MovementUpdate update) {
        if (!update.positionChanged()) {
            return;
        }
        if (update.firstPosition() || update.teleport() || update.elapsedTicks() != 1 || !player.tickEndMode()) {
            previousValid = false;
            return;
        }
        long tick = player.clientTicks();
        VelocityState velocity = player.component(velocityKey);
        if (velocity.size() > 0 || velocity.registered() != seenVelocities) {
            seenVelocities = velocity.registered();
            velocityTick = tick;
        }
        double distance = update.horizontalDistance();
        double carried = distance;
        double blockFriction = update.fromGround()
                ? frictionBelow(update.fromX(), update.fromY(), update.fromZ()) : AIR_FRICTION;
        if (previousValid && !Double.isNaN(blockFriction) && tick - velocityTick > VELOCITY_GRACE_TICKS) {
            double acceleration = update.fromGround() ? groundAcceleration(blockFriction) : AIR_ACCELERATION;
            double jump = update.fromGround() && update.deltaY() > 0.0 ? SPRINT_JUMP_BOOST : 0.0;
            double physical = previousDistance * previousFriction + acceleration + jump;
            double bound = physical * LIMIT_MARGIN + limitTolerance;
            if (distance > bound && judgeable() && environmentClear(update)) {
                // carry the legal maximum, not the cheated speed: air friction would keep it alive
                carried = physical;
                limitFailures++;
                String details = "limit moved=" + MovementOptions.number(distance)
                        + " max=" + MovementOptions.number(bound)
                        + " previous=" + MovementOptions.number(previousDistance)
                        + " ground=" + update.fromGround()
                        + " speed=" + MovementOptions.number(player.attributes().movementSpeed());
                if (debugging()) {
                    debug(details + " buffer=" + MovementOptions.number(buffer()));
                }
                if (raiseBuffer(1.0)) {
                    violation(details);
                }
            }
        }
        previousDistance = carried;
        previousValid = !Double.isNaN(blockFriction);
        previousFriction = update.fromGround() ? blockFriction * AIR_FRICTION : AIR_FRICTION;
    }

    private double groundAcceleration(double blockFriction) {
        double speed = player.attributes().valueWithout(AttributeState.MOVEMENT_SPEED, SPRINT_MODIFIER) * SPRINT_MULTIPLIER;
        double friction = Math.max(0.1, blockFriction);
        return speed * (FRICTION_SPEED / (friction * friction * friction));
    }

    private boolean judgeable() {
        if (isExempt() || player.exemptions().anyActive(EXEMPT_CAUSES)) {
            return false;
        }
        if (player.vehicle().inVehicle() || player.vehicle().ticksSinceDismount() < DISMOUNT_GRACE_TICKS) {
            return false;
        }
        PoseState pose = player.pose();
        if (player.abilities().flying() || player.abilities().flightAllowed() || pose.gliding()
                || pose.glideRequested() || pose.swimming()) {
            return false;
        }
        return !player.attributes().pending() && !player.effects().anyPending();
    }

    /** Friction of vanilla's "block below that affects movement", or NaN when unknown. */
    private double frictionBelow(double x, double y, double z) {
        WorldMirror world = player.component(worldKey);
        BlockSnapshot below = world.getBlock((int) Math.floor(x), (int) Math.floor(y - 0.5000001), (int) Math.floor(z));
        return below.known() ? below.properties().friction() : Double.NaN;
    }

    /**
     * Known blocks without liquids, climbables, pistons or launch flags in the 3x3 columns around the
     * start and end of the move (feet level, below and head). Only runs for a suspicious tick.
     */
    private boolean environmentClear(MovementUpdate update) {
        WorldMirror world = player.component(worldKey);
        if (world.pending().any()) {
            return false;
        }
        return areaClear(world, update.fromX(), update.fromY(), update.fromZ())
                && areaClear(world, update.toX(), update.toY(), update.toZ());
    }

    private static boolean areaClear(WorldMirror world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y + 1.0e-6);
        int bz = (int) Math.floor(z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockSnapshot block = world.getBlock(bx + dx, by + dy, bz + dz);
                    if (!block.known()) {
                        return false;
                    }
                    BlockProperties properties = block.properties();
                    if (properties.liquid() != BlockProperties.Liquid.NONE || properties.climbable()) {
                        return false;
                    }
                    for (BlockProperties.SpecialFlag flag : BlockProperties.SpecialFlag.values()) {
                        if (flag != BlockProperties.SpecialFlag.SOUL_SAND_SLOW && properties.has(flag)) {
                            return false;
                        }
                    }
                    if (block.key().value().contains("piston")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Effective threshold after the last settings application (tests, diagnostics). */
    public double threshold() {
        return threshold;
    }

    /** Ticks above the physical bound (tests, diagnostics). */
    int limitFailures() {
        return limitFailures;
    }
}
