package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.CombatCheck;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.check.impl.combat.EntityAttack;
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
 * Velocity (anti-knockback): a server velocity the client has definitely applied does not show up in
 * the reported movement. Two independent signals share one buffer.
 *
 * <h2>{@code simulation}</h2>
 * The engine offers every server velocity whose bracket markers are acknowledged
 * ({@code VelocityState} phase ACKED) and also tries candidates without it, so its best
 * {@link SimulationResult#offset()} stays small for a player who ignores knockback. It therefore also
 * reports {@link SimulationResult#ackedVelocityOffset()}: the offset of the best candidate that
 * <em>does</em> apply every acknowledged velocity. Ignored or reduced knockback makes that offset
 * large; legit movement keeps it near zero because the candidate set contains the real input. Only
 * COMPLETE single-tick results are judged and only acknowledged, untainted velocities count.
 *
 * <h2>{@code distance}</h2>
 * Works without the simulation, so anti-KB is also caught where the engine skips (edges, unknown
 * blocks, multi-tick packets). When a horizontal {@code set_entity_motion} of at least
 * {@code kb-min-velocity} is acknowledged, the distance travelled <em>along the knockback
 * direction</em> is summed over the next {@code kb-ticks} client ticks (ticks without a position
 * packet count as zero movement for 1.21.2+ clients). The vanilla lower bound replays the knockback
 * under the worst case the player could legally oppose it with:
 * <ul>
 *   <li>each tick: motion plus the input acceleration (ground: movement speed x 1.3 x
 *       0.216 / friction^3, air: 0.026), then multiplied by friction (block x 0.91 on the ground,
 *       0.91 in the air);</li>
 *   <li>with the 1.21.2+ input packet the acceleration points where the keys and the yaw say;
 *       otherwise it is assumed to point straight against the knockback;</li>
 *   <li>a sprint jump (ground tick with upward motion) adds 0.2 along the facing direction (only if
 *       the jump key is down, when the input is known).</li>
 * </ul>
 * Fewer than {@code kb-ratio} of that bound is a violation. Knockback whose bound is below
 * {@code kb-min-expected} (weak hits, jump resets) is not judged. The window is dropped on
 * horizontal collisions, teleports, liquids, climbable, slowing or unknown blocks, sneaking, own
 * attacks (sprint hits slow the attacker by 0.6), a newer server velocity, vehicles, flight and
 * gliding. All numeric defaults are TUNING values, not vanilla constants.
 *
 * <p>Known bypasses: anti-KB only against walls; reducing knockback by less than the margins.
 */
public final class VelocityCheck extends Check implements PositionCheck, ClientTickCheck, CombatCheck {

    /** TUNING: offset (blocks) of the velocity-honouring candidate tolerated on top of the uncertainty. */
    public static final double DEFAULT_THRESHOLD = 0.04;
    /** TUNING: velocities with neither horizontal speed nor |vy| above this are not judged. */
    public static final double DEFAULT_MIN_VELOCITY = 0.1;
    /** TUNING: horizontal knockback (blocks/tick) below this does not open a distance window. */
    public static final double DEFAULT_KB_MIN_VELOCITY = 0.2;
    /** TUNING: client ticks summed per distance window. */
    public static final int DEFAULT_KB_TICKS = 3;
    /** TUNING: share of the vanilla lower bound that must be travelled. */
    public static final double DEFAULT_KB_RATIO = 0.5;
    /** TUNING: windows whose vanilla lower bound is below this are not judged. */
    public static final double DEFAULT_KB_MIN_EXPECTED = 0.2;

    /** Vanilla air drag on horizontal motion. */
    static final double AIR_FRICTION = 0.91;
    /** Vanilla air acceleration while sprinting (0.02 walking). */
    static final double AIR_ACCELERATION = 0.026;
    /** Vanilla horizontal boost of a sprint jump. */
    static final double SPRINT_JUMP_BOOST = 0.2;
    /** Vanilla sprint multiplier on the movement speed. */
    static final double SPRINT_MULTIPLIER = 1.3;
    /** Ground acceleration: speed x 0.216 / blockFriction^3 (vanilla getFrictionInfluencedSpeed). */
    static final double FRICTION_SPEED = 0.21600002;
    static final String SPRINT_MODIFIER = "minecraft:sprinting";

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.VEHICLE,
            ExemptionCause.ELYTRA_BOOST, ExemptionCause.RIPTIDE, ExemptionCause.WIND_CHARGE,
            ExemptionCause.LUNGE, ExemptionCause.PORTAL, ExemptionCause.GAMEMODE_CHANGE,
            ExemptionCause.ABILITY_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.TELEPORT);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("velocity", CheckCategory.MOVEMENT)
            .displayName("Velocity")
            .description("Acknowledged knockback that does not show up in the movement")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(1.5)
                    .withBufferDecay(0.25)
                    .withBufferMax(4.0)
                    .withSetbackVl(-1.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE)
            .build();

    /** Both {@code null} without the simulation module: only the simulation signal runs. */
    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private double threshold = DEFAULT_THRESHOLD;
    private double minVelocity = DEFAULT_MIN_VELOCITY;
    private boolean checkDistance = true;
    private double kbMinVelocity = DEFAULT_KB_MIN_VELOCITY;
    private int kbTicks = DEFAULT_KB_TICKS;
    private double kbRatio = DEFAULT_KB_RATIO;
    private double kbMinExpected = DEFAULT_KB_MIN_EXPECTED;

    // distance window
    private long lastSeenId;
    private long maxSplitId;
    private boolean windowActive;
    private long windowRegistered;
    private double dirX;
    private double dirZ;
    private double knockback;
    private double motion;
    private double expected;
    private double travelled;
    private int steps;
    private boolean positionThisTick;
    private int distanceFailures;
    private long attackTick = Long.MIN_VALUE;
    private int distanceVerdicts;

    public VelocityCheck(PlayerData player) {
        this(player, null, null);
    }

    public VelocityCheck(PlayerData player, ComponentKey<WorldMirror> worldKey, ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        threshold = MovementOptions.tolerance(options, "threshold", DEFAULT_THRESHOLD);
        minVelocity = MovementOptions.tolerance(options, "min-velocity", DEFAULT_MIN_VELOCITY);
        checkDistance = options.getBoolean("check-distance", true);
        kbMinVelocity = MovementOptions.tolerance(options, "kb-min-velocity", DEFAULT_KB_MIN_VELOCITY);
        int ticks = options.getInt("kb-ticks", DEFAULT_KB_TICKS);
        kbTicks = ticks >= 1 && ticks <= 20 ? ticks : DEFAULT_KB_TICKS;
        double ratio = options.getDouble("kb-ratio", DEFAULT_KB_RATIO);
        kbRatio = ratio > 0.0 && ratio <= 1.0 ? ratio : DEFAULT_KB_RATIO;
        kbMinExpected = MovementOptions.tolerance(options, "kb-min-expected", DEFAULT_KB_MIN_EXPECTED);
    }

    @Override
    protected void onReset() {
        windowActive = false;
    }

    // ------------------------------------------------------------------ simulation signal

    @Override
    public void onPosition(MovementUpdate update) {
        trackDistance(update);
        SimulationResult result = update.simulation();
        if (!result.complete() || result.velocityAcked() == 0 || isExempt()) {
            return;
        }
        judge(result.ticksSimulated(), result.ackedVelocityOffset(), result.uncertainty(),
                result.ackedVelocityHorizontal(), result.ackedVelocityVertical(), result.offset());
    }

    /** Decision logic on primitives (tests). */
    void judge(int ticks, double forcedOffset, double uncertainty, double velocityHorizontal,
               double velocityVertical, double bestOffset) {
        if (ticks != 1 || Double.isNaN(forcedOffset)) {
            return;
        }
        if (velocityHorizontal < minVelocity && Math.abs(velocityVertical) < minVelocity) {
            return;
        }
        double limit = threshold + uncertainty;
        if (forcedOffset <= limit) {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug("forced=" + MovementOptions.number(forcedOffset) + " limit=" + MovementOptions.number(limit)
                    + " best=" + MovementOptions.number(bestOffset));
        }
        if (raiseBuffer(1.0)) {
            violation("offset=" + MovementOptions.number(forcedOffset)
                    + " limit=" + MovementOptions.number(limit)
                    + " velocity-h=" + MovementOptions.number(velocityHorizontal)
                    + " velocity-y=" + MovementOptions.number(velocityVertical)
                    + " ignored-offset=" + MovementOptions.number(bestOffset));
        }
    }

    // ------------------------------------------------------------------ distance signal

    private boolean distanceEnabled() {
        return checkDistance && worldKey != null && velocityKey != null;
    }

    /**
     * Movement hook, before the engine consumes acknowledged velocities: opens a window for the
     * newest acknowledged knockback.
     */
    void beforeSimulation() {
        if (distanceEnabled()) {
            scanVelocities(true);
        }
    }

    /**
     * Opens a window for a newly acknowledged knockback. {@code atMovement}: called for a movement
     * packet; an entry still in WINDOW phase there may be applied inside that movement, so it is
     * marked split and never judged.
     */
    private void scanVelocities(boolean atMovement) {
        VelocityState velocity = player.component(velocityKey);
        if (windowActive && velocity.registered() != windowRegistered) {
            windowActive = false; // a newer velocity: the window would mix two impulses
        }
        for (int i = 0; i < velocity.size(); i++) {
            long id = velocity.id(i);
            VelocityState.Phase phase = velocity.phase(i);
            if (phase == VelocityState.Phase.WINDOW && atMovement) {
                maxSplitId = Math.max(maxSplitId, id);
            }
            if (phase != VelocityState.Phase.ACKED || id <= lastSeenId) {
                continue;
            }
            lastSeenId = id;
            windowActive = false;
            if (id <= maxSplitId || velocity.tainted(i) || velocity.kind(i) != VelocityState.Kind.SET
                    || attackTick == player.clientTicks()) {
                continue;
            }
            double horizontal = Math.hypot(velocity.x(i), velocity.z(i));
            if (horizontal < kbMinVelocity) {
                continue;
            }
            windowActive = true;
            windowRegistered = velocity.registered();
            dirX = velocity.x(i) / horizontal;
            dirZ = velocity.z(i) / horizontal;
            knockback = horizontal;
            motion = horizontal;
            expected = 0.0;
            travelled = 0.0;
            steps = 0;
            positionThisTick = false;
        }
    }

    private void trackDistance(MovementUpdate update) {
        if (!windowActive) {
            return;
        }
        if (!update.positionChanged() && !update.teleport()) {
            return;
        }
        if (update.teleport() || update.firstPosition() || update.elapsedTicks() != 1
                || update.horizontalCollision() || !windowJudgeable() || !environmentPlain(update)) {
            windowActive = false;
            return;
        }
        positionThisTick = true;
        step(update.deltaX() * dirX + update.deltaZ() * dirZ, update.fromGround(),
                update.fromGround() && update.deltaY() > 0.0, frictionBelow(update.fromX(), update.fromY(), update.fromZ()));
    }

    @Override
    public void onClientTick() {
        if (!distanceEnabled()) {
            return;
        }
        boolean hadPosition = positionThisTick;
        positionThisTick = false;
        if (player.tickEndMode()) {
            scanVelocities(false);
            if (windowActive && !hadPosition) {
                // a tick without a position packet: the client did not move at all
                if (!windowJudgeable()) {
                    windowActive = false;
                    return;
                }
                double friction = frictionBelow(player.movement().x(), player.movement().y(), player.movement().z());
                if (Double.isNaN(friction)) {
                    windowActive = false;
                    return;
                }
                step(0.0, player.movement().onGround(), false, friction);
            }
        }
    }

    @Override
    public void onAttack(EntityAttack attack) {
        // sprint hits multiply the attacker's motion by 0.6, also in the tick the knockback lands
        attackTick = player.clientTicks();
        windowActive = false;
    }

    /** One tick of the window: adds the travelled distance and the vanilla lower bound. */
    private void step(double along, boolean ground, boolean jumped, double blockFriction) {
        if (!windowActive) {
            return;
        }
        if (Double.isNaN(blockFriction)) {
            windowActive = false;
            return;
        }
        double acceleration = ground ? groundAcceleration(blockFriction) : AIR_ACCELERATION;
        double against = inputProjection(acceleration);
        if (jumped) {
            against += jumpProjection();
        }
        double moved = motion + against;
        expected += moved;
        travelled += along;
        motion = moved * (ground ? blockFriction * AIR_FRICTION : AIR_FRICTION);
        if (++steps >= kbTicks) {
            windowActive = false;
            verdict();
        }
    }

    private void verdict() {
        if (expected < kbMinExpected) {
            return;
        }
        distanceVerdicts++;
        double ratio = travelled / expected;
        if (ratio >= kbRatio) {
            lowerBuffer();
            return;
        }
        distanceFailures++;
        String details = "distance moved=" + MovementOptions.number(travelled)
                + " expected=" + MovementOptions.number(expected)
                + " ratio=" + MovementOptions.number(ratio)
                + " kb=" + MovementOptions.number(knockback)
                + " ticks=" + steps;
        if (debugging()) {
            debug(details + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(details);
        }
    }

    /** Vanilla ground acceleration with the sprint multiplier assumed (larger = more lenient). */
    private double groundAcceleration(double blockFriction) {
        AttributeState attributes = player.attributes();
        double speed = attributes.valueWithout(AttributeState.MOVEMENT_SPEED, SPRINT_MODIFIER) * SPRINT_MULTIPLIER;
        double friction = Math.max(0.1, blockFriction);
        return speed * (FRICTION_SPEED / (friction * friction * friction));
    }

    /**
     * Least favourable component of the input acceleration along the knockback (never positive).
     * Without the input packet the whole acceleration may point against the knockback.
     */
    private double inputProjection(double acceleration) {
        PoseState pose = player.pose();
        if (!player.tickEndMode() || !pose.inputKnown()) {
            return -acceleration;
        }
        double strafe = pose.strafeImpulse();
        double forward = pose.forwardImpulse();
        double length = Math.hypot(strafe, forward);
        if (length < 1.0e-6) {
            return 0.0;
        }
        double yaw = Math.toRadians(player.movement().yaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double x = (strafe * cos - forward * sin) / length;
        double z = (forward * cos + strafe * sin) / length;
        return Math.min(0.0, (x * dirX + z * dirZ) * acceleration);
    }

    /** Least favourable component of the sprint-jump boost along the knockback (never positive). */
    private double jumpProjection() {
        PoseState pose = player.pose();
        if (player.tickEndMode() && pose.inputKnown() && !pose.jump()) {
            return 0.0;
        }
        double yaw = Math.toRadians(player.movement().yaw());
        double along = -Math.sin(yaw) * dirX + Math.cos(yaw) * dirZ;
        return Math.min(0.0, along * SPRINT_JUMP_BOOST);
    }

    private boolean windowJudgeable() {
        if (isExempt() || player.exemptions().anyActive(EXEMPT_CAUSES)) {
            return false;
        }
        PoseState pose = player.pose();
        if (player.vehicle().inVehicle() || player.abilities().flying() || pose.gliding() || pose.glideRequested()
                || pose.swimming() || pose.sneaking() || pose.shift()) {
            return false;
        }
        return !player.attributes().pending() && !player.effects().anyPending();
    }

    /** Known, dry, non-slowing blocks at the feet, head and below the target position. */
    private boolean environmentPlain(MovementUpdate update) {
        WorldMirror world = player.component(worldKey);
        if (world.pending().any()) {
            return false;
        }
        int x = (int) Math.floor(update.toX());
        int z = (int) Math.floor(update.toZ());
        int feetY = (int) Math.floor(update.toY() + 1.0e-6);
        for (int y = feetY; y <= feetY + 1; y++) {
            if (!plain(world.getBlock(x, y, z))) {
                return false;
            }
        }
        return !Double.isNaN(frictionBelow(update.toX(), update.toY(), update.toZ()));
    }

    private static boolean plain(BlockSnapshot block) {
        if (!block.known()) {
            return false;
        }
        BlockProperties properties = block.properties();
        if (properties.liquid() != BlockProperties.Liquid.NONE || properties.climbable() || properties.uncertain()
                || properties.speedFactor() != 1.0 || !properties.flags().isEmpty()) {
            return false;
        }
        String key = block.key().value();
        return !key.contains("cobweb") && !key.contains("powder_snow") && !key.contains("berry")
                && !key.contains("scaffolding") && !key.contains("piston");
    }

    /** Friction of vanilla's "block below that affects movement", or NaN when it is unknown or slows. */
    private double frictionBelow(double x, double y, double z) {
        WorldMirror world = player.component(worldKey);
        BlockSnapshot below = world.getBlock((int) Math.floor(x), (int) Math.floor(y - 0.5000001), (int) Math.floor(z));
        if (!below.known()) {
            return Double.NaN;
        }
        BlockProperties properties = below.properties();
        if (properties.speedFactor() != 1.0 || properties.liquid() != BlockProperties.Liquid.NONE) {
            return Double.NaN;
        }
        return properties.friction();
    }

    public double threshold() {
        return threshold;
    }

    /** Judged distance windows (tests, diagnostics). */
    int distanceVerdicts() {
        return distanceVerdicts;
    }

    /** Distance windows below the ratio (tests, diagnostics). */
    int distanceFailures() {
        return distanceFailures;
    }
}
