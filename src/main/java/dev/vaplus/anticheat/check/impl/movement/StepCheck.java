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
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * Step: rising further in one client tick, starting from the ground, than vanilla allows without
 * a jump or an external impulse.
 *
 * <p>Vanilla facts: a grounded player rises at most {@code max(step_height, jump velocity)} in one
 * tick. {@code step_height} defaults to 0.6 (Minecraft Wiki "Attribute", fetched 2026-11-15), read
 * from {@link AttributeState#stepHeight()}. A jump tick moves by exactly the jump velocity
 * ({@code jump_strength} 0.42 plus 0.1 per Jump Boost level); a step-up on a jump tick ends at the
 * step height, never above the larger of the two (derived from the collision algorithm,
 * UNCONFIRMED against a capture). Slabs and stairs rise 0.5, so they stay under the limit.
 *
 * <p>The rise is summed over all position packets of one client tick (1.21.2+ tick-end clients), which
 * catches "packet step" (+0.42, +0.33, +0.25 sent within one tick: each packet alone is a legal jump
 * delta, but a client sends one position per tick). For older clients every packet is its own tick,
 * so only a single oversized packet can be judged (packet step with them is not detectable here).
 *
 * <p>Only groups that start with the client claiming ground are judged. Airborne rises (bounce
 * follow-up ticks, flight) belong to Fly and the simulation. A judged group is skipped (buffer
 * neither raised nor lowered) when an impulse may explain it: velocity registered and not yet landed
 * from, unknown or unacknowledged world data around the move, and special blocks (slime, honey, beds,
 * bubble columns, pistons, ladders and vines, liquids). Elytra, levitation, flying, vehicles and
 * pending attribute or effect changes are skipped as well.
 *
 * <p>All numeric defaults are TUNING values, not vanilla constants (except the attribute defaults).
 */
public final class StepCheck extends Check implements PositionCheck {

    /** TUNING: blocks tolerated above max(step height, jump velocity). */
    public static final double DEFAULT_TOLERANCE = 0.03;
    /** TUNING: client ticks after which a registered velocity no longer excuses the airborne phase. */
    public static final int DEFAULT_VELOCITY_WINDOW_TICKS = 100;
    /** TUNING: ticks after leaving a vehicle during which nothing is judged. */
    public static final int DISMOUNT_GRACE_TICKS = 10;
    /** Vertical layers scanned above the lowest feet position. */
    private static final int MAX_SCAN_LAYERS = 14;
    /** Half the player hitbox width plus margin (UNCONFIRMED 0.6 wide player). */
    private static final double SCAN_REACH = 0.4;

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE,
            ExemptionCause.ABILITY_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE,
            ExemptionCause.KNOCKBACK, ExemptionCause.EXPLOSION, ExemptionCause.PORTAL,
            ExemptionCause.ELYTRA_BOOST, ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE,
            ExemptionCause.BOUNCE, ExemptionCause.LUNGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("step", CheckCategory.MOVEMENT)
            .displayName("Step")
            .description("Rising more than the step height or jump velocity in one tick without an impulse")
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
    private boolean packetStep = true;

    private boolean groupActive;
    private long groupTick;
    private boolean groupGround;
    private double groupStartX;
    private double groupStartY;
    private double groupStartZ;
    private double groupRise;
    private boolean groupFlagged;
    private int groupPackets;

    private long seenVelocities = -1;
    private boolean velocityAir;
    private long velocityTick;

    public StepCheck(PlayerData player, ComponentKey<WorldMirror> worldKey, ComponentKey<VelocityState> velocityKey) {
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
        packetStep = options.getBoolean("check-packet-step", true);
    }

    @Override
    protected void onReset() {
        groupActive = false;
        velocityAir = false;
        seenVelocities = -1;
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (!update.positionChanged() || update.firstPosition() || update.teleport()) {
            groupActive = false;
            return;
        }
        long tick = player.clientTicks();
        trackVelocity(tick, update);

        double dy = update.deltaY();
        if (packetStep && groupActive && player.tickEndMode() && tick == groupTick) {
            groupRise += dy;
            groupPackets++;
        } else {
            groupActive = true;
            groupTick = tick;
            groupGround = update.fromGround();
            groupStartX = update.fromX();
            groupStartY = update.fromY();
            groupStartZ = update.fromZ();
            groupRise = dy;
            groupFlagged = false;
            groupPackets = 1;
        }
        if (!groupGround || groupFlagged) {
            return;
        }
        AttributeState attributes = player.attributes();
        double scale = Math.max(1.0, attributes.scale());
        double limit = Math.max(attributes.stepHeight() * scale,
                (attributes.jumpStrength() + 0.1 * player.effects().level(TrackedEffect.JUMP_BOOST)) * scale)
                + tolerance;
        if (groupRise <= limit) {
            lowerBuffer();
            return;
        }
        if (!judgeable()) {
            return;
        }
        WorldMirror world = player.component(worldKey);
        if (world.pending().any() || !terrainClear(world, update)) {
            return;
        }
        groupFlagged = true;
        if (debugging()) {
            debug("rise=" + MovementOptions.number(groupRise) + " limit=" + MovementOptions.number(limit)
                    + " packets=" + groupPackets + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("rise=" + MovementOptions.number(groupRise)
                    + " limit=" + MovementOptions.number(limit)
                    + " packets=" + groupPackets
                    + " ground=" + update.toGround()
                    + " step=" + MovementOptions.number(attributes.stepHeight())
                    + " jump=" + MovementOptions.number(attributes.jumpStrength()));
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

    /**
     * Whether the blocks around the start and end of the move are known and free of anything that
     * can push or launch a player. Only runs for an already suspicious move.
     */
    private boolean terrainClear(WorldMirror world, MovementUpdate update) {
        double minY = Math.min(groupStartY, update.toY());
        double maxY = Math.max(groupStartY, update.toY());
        int y0 = (int) Math.floor(minY) - 1;
        int y1 = Math.min((int) Math.floor(maxY) + 2, y0 + MAX_SCAN_LAYERS);
        return columnClear(world, groupStartX, groupStartZ, y0, y1)
                && columnClear(world, update.toX(), update.toZ(), y0, y1);
    }

    /** Whether one column of blocks is known and free of anything that can push or launch a player (shared with {@link HighJumpCheck}). */
    static boolean columnClear(WorldMirror world, double x, double z, int y0, int y1) {
        int x0 = (int) Math.floor(x - SCAN_REACH);
        int x1 = (int) Math.floor(x + SCAN_REACH);
        int z0 = (int) Math.floor(z - SCAN_REACH);
        int z1 = (int) Math.floor(z + SCAN_REACH);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int by = y0; by <= y1; by++) {
                    BlockSnapshot block = world.getBlock(bx, by, bz);
                    if (!block.known()) {
                        return false;
                    }
                    BlockProperties properties = block.properties();
                    if (properties.climbable() || properties.liquid() != BlockProperties.Liquid.NONE) {
                        return false;
                    }
                    for (BlockProperties.SpecialFlag flag : BlockProperties.SpecialFlag.values()) {
                        if (flag != BlockProperties.SpecialFlag.SOUL_SAND_SLOW && properties.has(flag)) {
                            return false;
                        }
                    }
                    String key = block.key().value();
                    if (key.contains("piston") || key.contains("scaffolding") || key.contains("shulker")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
