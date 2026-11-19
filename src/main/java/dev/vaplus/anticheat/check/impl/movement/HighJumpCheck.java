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
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * HighJump: an airborne phase that started on the ground rises higher than the jump velocity can
 * carry the player.
 *
 * <p>Vanilla facts: a jump starts with {@code jump_strength} (0.42) plus 0.1 per Jump Boost level;
 * every tick the player moves by the velocity, then {@code v = (v - gravity) * 0.98}. The apex is the
 * sum of all positive velocities (1.2522 blocks with the default attributes). {@link StepCheck}
 * only sees the first tick; this check sums the whole rise, so a modified jump velocity, air jumps
 * and slow-fall style hovering above the apex are caught.
 *
 * <p>Tracking starts on the first packet that leaves the ground and ends with the next packet that
 * claims ground. A phase is judged once: the first packet above the apex limit either flags or, when
 * an impulse may explain it, marks the phase as excused. Excused: registered velocity not yet landed
 * from, unknown or pending world data, special blocks along the start and current column (slime,
 * honey, beds, bubble columns, pistons, ladders, vines, scaffolding, liquids), flight, elytra,
 * levitation, vehicles, pending attribute or effect changes and the usual exemption causes.
 *
 * <p>All numeric defaults are TUNING values, not vanilla constants (except the attribute defaults).
 */
public final class HighJumpCheck extends Check implements PositionCheck {

    /** TUNING: blocks tolerated above the computed apex. */
    public static final double DEFAULT_TOLERANCE = 0.05;
    /** TUNING: client ticks after which a registered velocity no longer excuses the airborne phase. */
    public static final int DEFAULT_VELOCITY_WINDOW_TICKS = 100;
    /** TUNING: ticks after leaving a vehicle during which nothing is judged. */
    public static final int DISMOUNT_GRACE_TICKS = 10;
    /** Vanilla vertical air drag. */
    static final double AIR_DRAG = 0.98;
    /** Upper bound for the apex simulation (gravity near zero). */
    private static final int MAX_APEX_TICKS = 400;
    /** Vertical layers scanned above the lowest feet position. */
    private static final int MAX_SCAN_LAYERS = 16;

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE,
            ExemptionCause.ABILITY_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE,
            ExemptionCause.KNOCKBACK, ExemptionCause.EXPLOSION, ExemptionCause.PORTAL,
            ExemptionCause.ELYTRA_BOOST, ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE,
            ExemptionCause.BOUNCE, ExemptionCause.LUNGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("highjump", CheckCategory.MOVEMENT)
            .displayName("HighJump")
            .description("Jumping higher than the jump velocity allows")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL, ExemptionCause.ELYTRA_BOOST,
                    ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE,
                    ExemptionCause.LUNGE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private double tolerance = DEFAULT_TOLERANCE;
    private int velocityWindowTicks = DEFAULT_VELOCITY_WINDOW_TICKS;

    private boolean airborne;
    private boolean judged;
    private double startX;
    private double startY;
    private double startZ;
    private double peakRise;
    private int airPackets;

    private long seenVelocities = -1;
    private boolean velocityAir;
    private long velocityTick;

    public HighJumpCheck(PlayerData player, ComponentKey<WorldMirror> worldKey, ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        tolerance = MovementOptions.tolerance(options, "tolerance", DEFAULT_TOLERANCE);
        int window = options.getInt("velocity-window-ticks", DEFAULT_VELOCITY_WINDOW_TICKS);
        velocityWindowTicks = window >= 0 && window <= 6000 ? window : DEFAULT_VELOCITY_WINDOW_TICKS;
    }

    @Override
    protected void onReset() {
        airborne = false;
        velocityAir = false;
        seenVelocities = -1;
    }

    /**
     * Highest rise of a jump that starts with {@code velocity} under {@code gravity}, or
     * {@link Double#POSITIVE_INFINITY} when gravity is too weak to bound it.
     */
    static double apex(double velocity, double gravity) {
        if (velocity <= 0.0) {
            return 0.0;
        }
        if (gravity <= 1.0e-4) {
            return Double.POSITIVE_INFINITY;
        }
        double height = 0.0;
        double v = velocity;
        for (int i = 0; i < MAX_APEX_TICKS && v > 0.0; i++) {
            height += v;
            v = (v - gravity) * AIR_DRAG;
        }
        return v > 0.0 ? Double.POSITIVE_INFINITY : height;
    }

    /** Apex limit for the player's current attributes and Jump Boost level, without tolerance. */
    double maxJumpHeight() {
        AttributeState attributes = player.attributes();
        double scale = Math.max(1.0, attributes.scale());
        double velocity = (attributes.jumpStrength() + 0.1 * player.effects().level(TrackedEffect.JUMP_BOOST)) * scale;
        return apex(velocity, attributes.gravity());
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (!update.positionChanged() || update.firstPosition() || update.teleport()) {
            airborne = false;
            return;
        }
        long tick = player.clientTicks();
        trackVelocity(tick, update);

        if (update.toGround()) {
            if (airborne && !judged) {
                lowerBuffer();
            }
            airborne = false;
            return;
        }
        if (!airborne) {
            if (!update.fromGround()) {
                return;
            }
            airborne = true;
            judged = false;
            startX = update.fromX();
            startY = update.fromY();
            startZ = update.fromZ();
            peakRise = 0.0;
            airPackets = 0;
        }
        airPackets++;
        double rise = update.toY() - startY;
        if (judged || rise <= peakRise) {
            return;
        }
        peakRise = rise;
        double apex = maxJumpHeight();
        double limit = apex + tolerance;
        if (rise <= limit) {
            return;
        }
        judged = true;
        if (!judgeable()) {
            return;
        }
        WorldMirror world = player.component(worldKey);
        if (world.pending().any() || !terrainClear(world, update)) {
            return;
        }
        if (debugging()) {
            debug("rise=" + MovementOptions.number(rise) + " limit=" + MovementOptions.number(limit)
                    + " air=" + airPackets + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("rise=" + MovementOptions.number(rise)
                    + " limit=" + MovementOptions.number(limit)
                    + " air=" + airPackets
                    + " jump=" + MovementOptions.number(player.attributes().jumpStrength())
                    + " boost=" + player.effects().level(TrackedEffect.JUMP_BOOST));
        }
    }

    /** Impulse bookkeeping: a registered velocity excuses the airborne phase that follows it. */
    private void trackVelocity(long tick, MovementUpdate update) {
        VelocityState velocity = player.component(velocityKey);
        long registered = velocity.registered();
        if (seenVelocities < 0) {
            seenVelocities = registered;
        } else if (registered != seenVelocities) {
            seenVelocities = registered;
            velocityAir = true;
            velocityTick = tick;
        }
        if (velocityAir && velocity.size() == 0
                && ((update.toGround() && tick > velocityTick) || tick - velocityTick > velocityWindowTicks)) {
            velocityAir = false;
        }
    }

    private boolean judgeable() {
        if (isExempt() || velocityAir || player.component(velocityKey).size() > 0) {
            return false;
        }
        if (player.exemptions().anyActive(EXEMPT_CAUSES)) {
            return false;
        }
        if (player.vehicle().inVehicle() || player.vehicle().ticksSinceDismount() < DISMOUNT_GRACE_TICKS) {
            return false;
        }
        if (player.abilities().flying() || player.abilities().flightAllowed()) {
            return false;
        }
        if (player.pose().gliding() || player.pose().glideRequested() || player.pose().swimming()) {
            return false;
        }
        if (player.effects().has(TrackedEffect.LEVITATION) || player.effects().anyPending()) {
            return false;
        }
        return !player.attributes().pending();
    }

    /** Known, impulse-free blocks around the jump start and the current position. */
    private boolean terrainClear(WorldMirror world, MovementUpdate update) {
        double minY = Math.min(startY, update.toY());
        double maxY = Math.max(startY, update.toY());
        // epsilon: landings end a hair below the block top (63.9999999 must scan from 63)
        int y0 = (int) Math.floor(minY + 1.0e-6) - 1;
        int y1 = Math.min((int) Math.floor(maxY) + 2, y0 + MAX_SCAN_LAYERS);
        return StepCheck.columnClear(world, startX, startZ, y0, y1)
                && StepCheck.columnClear(world, update.toX(), update.toZ(), y0, y1);
    }
}
