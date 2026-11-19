package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.protocol.player.GameMode;
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
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VehicleState;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.PendingChanges;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Set;

/**
 * Spider / WallClimb: climbing a wall without a climbable block.
 *
 * <p>Signal: an airborne tick on which the client reports a horizontal collision, rises
 * ({@code dy > min-rise}) and rises <b>more than vanilla vertical physics allows</b> for the previous
 * tick's vertical speed: {@code dy > (prevDy - 0.08) * 0.98 + excess-tolerance}. Such a tick is a
 * "climb tick". A jump arc (0.42, 0.3332, 0.2477, ...) follows exactly that decay and never produces
 * one; the take-off tick itself (previous packet on ground) is not judged. A constant-speed spider
 * (e.g. 0.2 per tick) produces a climb tick on every tick, a "re-jump" spider on every re-jump.
 * Climb ticks are counted per airborne phase (they need not be consecutive); from {@code min-ticks}
 * climb ticks on, and only once the player is more than {@code min-height} above the lowest point
 * of the phase (a plain jump peaks at about 1.25 blocks), every further climb tick raises the buffer.
 *
 * <p>Why legit play does not trigger it: nothing in vanilla accelerates an airborne player upwards
 * except the sources that are excluded here. Nothing is judged (phase reset plus a
 * {@code grace-ticks} quiet period) while the player may legitimately be lifted or slowed:
 * climbable, liquid (incl. waterlogged blocks and water plants), bubble-column, slime/honey,
 * bed/shelf bounce, piston, shulker-box, magma (26.2 geyser), web and trapdoor blocks (a trapdoor
 * above a ladder is climbable) anywhere in the player's box expanded by one block up and down;
 * unknown world data; a block or chunk change in that box that the client has not acknowledged yet
 * ({@code WorldMirror.pending()}: a ladder the server just removed is still there for the client);
 * jump boost, levitation, slow falling, pending effects/attributes, non-default gravity, tracked
 * velocity, vehicles, elytra/swimming, creative/spectator/flight, and the declared exemption causes
 * (teleport, knockback, explosion, wind charge, riptide, bounce, ...). Packets that are teleports,
 * carry no position or span more than one client tick break the chain. A claimed ground flag only
 * counts as airborne when the mirror shows no collidable block under the feet (otherwise it is a
 * landing or step-up). Lag cannot create climb ticks: the comparison uses only consecutive
 * one-tick deltas of the client's own movement.
 *
 * <p>Climbable blocks come from {@code BlockProperties.climbable()} (ladder, vine in
 * {@link dev.vaplus.anticheat.world.mirror.VanillaBlocks}) plus the private {@code EXTRA_CLIMBABLE}
 * key set (VanillaBlocks lacks the others). Powder snow is treated as climbable (leather boots are
 * unknowable). UNCONFIRMED (from memory, not verified against sources in this project): vanilla sets
 * the vertical speed to 0.2 when colliding horizontally on a climbable, which after gravity and
 * drag shows up as (0.2 - 0.08) * 0.98 = 0.1176 blocks per tick. The check does not depend on it.
 *
 * <p>Known bypasses / limits: the horizontal-collision flag is client-claimed and exists only on
 * 1.21.2+ clients (older clients and a spider that clears the flag are not judged here; Fly and
 * Prediction cover them where the engine does not skip). A spider that always claims
 * {@code onGround} is not judged (the take-off rule; ground spoofing is NoFall/Fly territory, and
 * players standing on entities such as boats or happy ghasts legitimately rise with that flag).
 * Any special block from the lists above next to the wall silences the check. Entities that lift a
 * player whose ground flag is false are not modelled. All numbers are TUNING values.
 */
public final class SpiderCheck extends Check implements PositionCheck {

    /** TUNING: climb ticks within one airborne phase before the buffer is raised. */
    public static final int DEFAULT_MIN_TICKS = 3;
    /** TUNING: blocks per tick the rise may exceed the gravity/drag continuation of the previous tick. */
    public static final double DEFAULT_EXCESS_TOLERANCE = 0.04;
    /** TUNING: smallest upward delta that counts as rising. */
    public static final double DEFAULT_MIN_RISE = 0.01;
    /** TUNING: height above the lowest point of the airborne phase before anything is flagged. */
    public static final double DEFAULT_MIN_HEIGHT = 1.5;
    /** TUNING: quiet ticks after a legitimate-lift condition (climbable, liquid, effect, ...) was seen. */
    public static final int DEFAULT_GRACE_TICKS = 10;

    /** Vanilla gravity (blocks/tick^2) and vertical drag, for the continuation of the last vertical speed. */
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;

    private static final double GRAVITY_EPSILON = 1.0E-6;
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    /** Block keys treated as climbable although {@code BlockProperties.climbable()} is false for them. */
    private static final Set<String> EXTRA_CLIMBABLE = Set.of(
            "minecraft:scaffolding", "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:cave_vines",
            "minecraft:cave_vines_plant", "minecraft:powder_snow");
    /** Other blocks that lift, drag or slow an entity beyond the modelled jump physics. */
    private static final Set<String> EXTRA_SPECIAL = Set.of(
            "minecraft:cobweb", "minecraft:piston", "minecraft:sticky_piston", "minecraft:piston_head",
            "minecraft:moving_piston", "minecraft:bubble_column", "minecraft:honey_block",
            "minecraft:slime_block", "minecraft:big_dripleaf", "minecraft:sweet_berry_bush",
            // Inherently water-filled plants: no "waterlogged" property, so the mirror shows no liquid.
            "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass",
            // 26.2 sulfur geyser (upward impulse above magma, magnitude unknown).
            "minecraft:magma_block");

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("spider", CheckCategory.MOVEMENT)
            .displayName("Spider")
            .description("Climbing walls without a climbable block")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(6.0)
                    .withSetbackVl(-1.0))
            .experimental(true)
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL, ExemptionCause.ELYTRA_BOOST,
                    ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE,
                    ExemptionCause.LUNGE)
            .build();

    private static final int SCAN_CLEAR = 0;
    private static final int SCAN_SUPPORTED = 1;
    private static final int SCAN_BLOCKED = 2;

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private int minTicks = DEFAULT_MIN_TICKS;
    private double excessTolerance = DEFAULT_EXCESS_TOLERANCE;
    private double minRise = DEFAULT_MIN_RISE;
    private double minHeight = DEFAULT_MIN_HEIGHT;
    private int graceTicks = DEFAULT_GRACE_TICKS;

    private boolean prevValid;
    private double prevDy;
    /** Climb ticks of the current airborne phase. */
    private int climbTicks;
    /** Lowest y of the current airborne phase; only meaningful while {@link #baseValid}. */
    private double baseY;
    private boolean baseValid;
    private int grace;

    public SpiderCheck(PlayerData player, ComponentKey<WorldMirror> worldKey,
                       ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        int ticks = options.getInt("min-ticks", DEFAULT_MIN_TICKS);
        minTicks = ticks >= 1 && ticks <= 100 ? ticks : DEFAULT_MIN_TICKS;
        excessTolerance = MovementOptions.tolerance(options, "excess-tolerance", DEFAULT_EXCESS_TOLERANCE);
        minRise = MovementOptions.tolerance(options, "min-rise", DEFAULT_MIN_RISE);
        minHeight = MovementOptions.tolerance(options, "min-height", DEFAULT_MIN_HEIGHT);
        int quiet = options.getInt("grace-ticks", DEFAULT_GRACE_TICKS);
        graceTicks = quiet >= 0 && quiet <= 200 ? quiet : DEFAULT_GRACE_TICKS;
    }

    @Override
    protected void onReset() {
        prevValid = false;
        prevDy = 0.0;
        climbTicks = 0;
        baseValid = false;
        grace = 0;
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (update.teleport() || update.firstPosition() || !update.positionChanged() || update.elapsedTicks() != 1) {
            // No contiguous one-tick delta: the physics continuation is unknown.
            prevValid = false;
            endPhase();
            return;
        }
        double dy = update.deltaY();
        boolean hadPrev = prevValid;
        double previous = prevDy;
        prevDy = dy;
        prevValid = Double.isFinite(dy);
        if (!hadPrev || !prevValid) {
            endPhase();
            return;
        }
        if (grace > 0) {
            grace--;
        }
        if (isExempt() || stateBlocked()) {
            quiet();
            return;
        }
        if (update.fromGround()) {
            // Take-off, step-up or walking: a new airborne phase can only start here.
            endPhase();
            lowerBuffer();
            return;
        }
        double fromY = update.fromY();
        if (!baseValid || fromY < baseY) {
            baseY = fromY;
            baseValid = true;
        }
        double expected = (previous - GRAVITY) * VERTICAL_DRAG;
        if (!isClimbTick(update.horizontalCollision(), dy, expected, minRise, excessTolerance)) {
            return;
        }
        if (grace > 0) {
            return;
        }
        int verdict = scanWorld(player, player.component(worldKey), update);
        if (verdict == SCAN_BLOCKED) {
            quiet();
            return;
        }
        if (update.toGround() && verdict == SCAN_SUPPORTED) {
            // A real ground contact (landing, step-up): not a wall climb tick.
            return;
        }
        climbTicks++;
        double height = update.toY() - baseY;
        if (climbTicks < minTicks || !(height > minHeight)) {
            return;
        }
        if (debugging()) {
            debug("dy=" + MovementOptions.number(dy) + " expected=" + MovementOptions.number(expected)
                    + " climbTicks=" + climbTicks + " height=" + MovementOptions.number(height)
                    + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("dy=" + MovementOptions.number(dy)
                    + " expected=" + MovementOptions.number(expected)
                    + " excess=" + MovementOptions.number(dy - expected)
                    + " climbTicks=" + climbTicks
                    + " height=" + MovementOptions.number(height));
        }
    }

    /**
     * Pure decision: wall contact, rising, and rising faster than gravity/drag allow after
     * {@code expected = (previousDy - 0.08) * 0.98}.
     */
    static boolean isClimbTick(boolean horizontalCollision, double dy, double expected,
                               double minRise, double excessTolerance) {
        return horizontalCollision && dy > minRise && dy > expected + excessTolerance;
    }

    private void endPhase() {
        climbTicks = 0;
        baseValid = false;
    }

    private void quiet() {
        endPhase();
        grace = graceTicks;
    }

    /** Cheap player-state conditions under which upward movement is not judged. */
    private boolean stateBlocked() {
        GameMode mode = player.abilities().gameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return true;
        }
        if (player.abilities().flying() || player.abilities().flightAllowed()) {
            return true;
        }
        PoseState pose = player.pose();
        if (pose.gliding() || pose.swimming() || pose.glideRequested()) {
            return true;
        }
        VehicleState vehicle = player.vehicle();
        if (vehicle.inVehicle() || vehicle.ticksSinceDismount() < 10) {
            return true;
        }
        EffectState effects = player.effects();
        if (effects.has(TrackedEffect.JUMP_BOOST) || effects.has(TrackedEffect.LEVITATION)
                || effects.has(TrackedEffect.SLOW_FALLING) || effects.anyPending()) {
            return true;
        }
        AttributeState attributes = player.attributes();
        if (attributes.pending()
                || Math.abs(attributes.gravity() - AttributeState.DEFAULT_GRAVITY) > GRAVITY_EPSILON) {
            return true;
        }
        return player.component(velocityKey).size() > 0;
    }

    /**
     * Looks at the columns under the player's box (feet to head, one block of margin below and above).
     *
     * @return BLOCKED for any climbable/special/liquid/unknown cell or an unacknowledged world change
     *         in the box, SUPPORTED when a collidable block is right below the feet, otherwise CLEAR
     */
    private static int scanWorld(PlayerData player, WorldMirror world, MovementUpdate update) {
        double x = update.toX();
        double y = update.toY();
        double z = update.toZ();
        int minX = floor(x - HALF_WIDTH);
        int maxX = floor(x + HALF_WIDTH);
        int minZ = floor(z - HALF_WIDTH);
        int maxZ = floor(z + HALF_WIDTH);
        int minY = floor(Math.min(update.fromY(), y)) - 1;
        int maxY = floor(Math.max(update.fromY(), y) + HEIGHT) + 1;
        int supportY = floor(y - 0.001);
        // Feet footprint, slightly shrunk so a wall touching the box edge is not mistaken for a floor.
        int footMinX = floor(x - HALF_WIDTH + 0.01);
        int footMaxX = floor(x + HALF_WIDTH - 0.01);
        int footMinZ = floor(z - HALF_WIDTH + 0.01);
        int footMaxZ = floor(z + HALF_WIDTH - 0.01);
        PendingChanges pending = world.pending();
        if (pending.any()) {
            // Same settle the engine does; idempotent. The client may still see the old blocks.
            pending.settle(player.acks().lastAckedMarker());
            if (pending.intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                return SCAN_BLOCKED;
            }
        }
        boolean supported = false;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    BlockSnapshot block = world.getBlock(bx, by, bz);
                    if (!block.known() || isSpecial(block)) {
                        return SCAN_BLOCKED;
                    }
                    if (by == supportY && bx >= footMinX && bx <= footMaxX && bz >= footMinZ && bz <= footMaxZ
                            && block.properties().collision() != BlockProperties.Collision.EMPTY) {
                        supported = true;
                    }
                }
            }
        }
        return supported ? SCAN_SUPPORTED : SCAN_CLEAR;
    }

    private static boolean isSpecial(BlockSnapshot block) {
        BlockProperties properties = block.properties();
        if (properties.climbable() || properties.liquid() != BlockProperties.Liquid.NONE
                || !properties.flags().isEmpty()) {
            return true;
        }
        if (properties.speedFactor() != 1.0 || properties.jumpFactor() != 1.0) {
            return true;
        }
        String key = block.key().value();
        return EXTRA_CLIMBABLE.contains(key) || EXTRA_SPECIAL.contains(key) || key.endsWith("_trapdoor")
                || key.endsWith("shulker_box");
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** Climb ticks counted in the current airborne phase. */
    public int climbTicks() {
        return climbTicks;
    }
}
