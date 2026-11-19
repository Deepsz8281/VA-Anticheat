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
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Set;

/**
 * FastLadder: climbing ladders, vines or scaffolding faster than vanilla.
 *
 * <p>Vanilla ({@code LivingEntity#handleRelativeFrictionAndCalculateMovement}): while the player is
 * on a climbable block and collides horizontally or holds jump, the motion after the move is set to
 * 0.2 upwards; gravity and drag follow, so the next tick rises by {@code (0.2 - gravity) x 0.98}
 * (0.1176 with the default gravity, the familiar 2.35 m/s). Without that boost the motion decays like
 * any airborne motion, {@code (previous - gravity) x 0.98}. An airborne tick that starts on a
 * climbable block can therefore rise at most
 * <pre>max((0.2 - gravity) x 0.98, (previous rise - gravity) x 0.98) + tolerance</pre>
 * Faster ticks raise the buffer. After a flagged tick the legal rise is carried forward instead of
 * the cheated one, so a single fast tick cannot excuse the following ones. The take-off tick from
 * the ground (jump velocity, Jump Boost) and step-ups are not judged.
 *
 * <p>Never judged: unknown blocks, liquids and bubble columns, powder snow (it multiplies vertical
 * motion), bouncy or launching blocks, levitation, pending effects or attributes, pending or just
 * applied server velocity, vehicles, flight, gliding, swimming, multi-tick packets, teleports and the
 * usual exemption causes. All numeric defaults are TUNING values.
 */
public final class FastLadderCheck extends Check implements PositionCheck {

    /** TUNING: blocks per tick tolerated above the vanilla climb rise. */
    public static final double DEFAULT_TOLERANCE = 0.02;
    /** Vanilla upward motion set while climbing. */
    static final double CLIMB_MOTION = 0.2;
    static final double DRAG = 0.98;
    /** TUNING: client ticks after a pending server velocity during which nothing is judged. */
    static final int VELOCITY_GRACE_TICKS = 2;
    /** TUNING: ticks after leaving a vehicle during which nothing is judged. */
    static final int DISMOUNT_GRACE_TICKS = 10;

    /** Climbable blocks {@code BlockProperties.climbable()} does not report. */
    static final Set<String> EXTRA_CLIMBABLE = Set.of(
            "minecraft:scaffolding", "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:cave_vines",
            "minecraft:cave_vines_plant");

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE,
            ExemptionCause.ABILITY_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE,
            ExemptionCause.KNOCKBACK, ExemptionCause.EXPLOSION, ExemptionCause.PORTAL,
            ExemptionCause.ELYTRA_BOOST, ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE,
            ExemptionCause.BOUNCE, ExemptionCause.LUNGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("fastladder", CheckCategory.MOVEMENT)
            .displayName("FastLadder")
            .description("Climbing ladders, vines or scaffolding faster than vanilla")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private double tolerance = DEFAULT_TOLERANCE;

    private boolean previousValid;
    private double previousRise;
    private long seenVelocities = -1;
    private long velocityTick = Long.MIN_VALUE / 2;
    private int fastTicks;

    public FastLadderCheck(PlayerData player, ComponentKey<WorldMirror> worldKey, ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        tolerance = MovementOptions.tolerance(options, "tolerance", DEFAULT_TOLERANCE);
    }

    @Override
    protected void onReset() {
        previousValid = false;
        seenVelocities = -1;
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (!update.positionChanged()) {
            return;
        }
        if (update.firstPosition() || update.teleport() || update.elapsedTicks() != 1) {
            previousValid = false;
            return;
        }
        long tick = player.clientTicks();
        VelocityState velocity = player.component(velocityKey);
        if (velocity.size() > 0 || velocity.registered() != seenVelocities) {
            seenVelocities = velocity.registered();
            velocityTick = tick;
        }
        double rise = update.deltaY();
        double carried = rise;
        if (previousValid && !update.fromGround() && rise > 0.0 && tick - velocityTick > VELOCITY_GRACE_TICKS) {
            double gravity = player.attributes().gravity();
            double climb = (CLIMB_MOTION - gravity) * DRAG;
            double decay = (previousRise - gravity) * DRAG;
            double legal = Math.max(climb, decay);
            if (rise > legal + tolerance) {
                WorldMirror world = player.component(worldKey);
                if (onClimbable(world, update) && judgeable() && environmentClear(world, update)) {
                    carried = legal;
                    fastTicks++;
                    String details = "rise=" + MovementOptions.number(rise)
                            + " max=" + MovementOptions.number(legal + tolerance)
                            + " previous=" + MovementOptions.number(previousRise)
                            + " block=" + feetBlock(world, update).key().value();
                    if (debugging()) {
                        debug(details + " buffer=" + MovementOptions.number(buffer()));
                    }
                    if (raiseBuffer(1.0)) {
                        violation(details);
                    }
                }
            } else if (onClimbable(player.component(worldKey), update)) {
                lowerBuffer();
            }
        }
        previousRise = carried;
        previousValid = true;
    }

    private static BlockSnapshot feetBlock(WorldMirror world, MovementUpdate update) {
        return world.getBlock((int) Math.floor(update.fromX()), (int) Math.floor(update.fromY() + 1.0e-6),
                (int) Math.floor(update.fromZ()));
    }

    /** Vanilla's onClimbable: the block at the feet position at the start of the tick. */
    static boolean onClimbable(WorldMirror world, MovementUpdate update) {
        BlockSnapshot block = feetBlock(world, update);
        return block.known() && climbable(block);
    }

    static boolean climbable(BlockSnapshot block) {
        return block.properties().climbable() || EXTRA_CLIMBABLE.contains(block.key().value());
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
        if (player.effects().has(TrackedEffect.LEVITATION) || player.effects().anyPending()) {
            return false;
        }
        return !player.attributes().pending();
    }

    /** Known blocks without liquids, powder snow or launch flags around the start and end of the move. */
    private boolean environmentClear(WorldMirror world, MovementUpdate update) {
        if (world.pending().any()) {
            return false;
        }
        return columnClear(world, update.fromX(), update.fromY(), update.fromZ())
                && columnClear(world, update.toX(), update.toY(), update.toZ());
    }

    private static boolean columnClear(WorldMirror world, double x, double y, double z) {
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
                    if (properties.liquid() != BlockProperties.Liquid.NONE) {
                        return false;
                    }
                    for (BlockProperties.SpecialFlag flag : BlockProperties.SpecialFlag.values()) {
                        if (flag != BlockProperties.SpecialFlag.SOUL_SAND_SLOW && properties.has(flag)) {
                            return false;
                        }
                    }
                    String key = block.key().value();
                    if (key.equals("minecraft:powder_snow") || key.contains("piston") || key.equals("minecraft:cobweb")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Ticks above the climb limit (tests, diagnostics). */
    int fastTicks() {
        return fastTicks;
    }
}
