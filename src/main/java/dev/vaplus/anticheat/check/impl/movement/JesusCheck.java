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
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * Jesus / WaterWalk / LavaWalk: the player rests on top of a liquid as if it were solid.
 *
 * <p>Water and lava never carry an entity: a player whose feet are at the liquid surface sinks, or at
 * best bobs (swim up, break the surface, fall back). A tick is <em>suspicious</em> when all of the
 * following hold, each judged against the compensated {@link WorldMirror}:
 * <ul>
 *   <li>the feet are within {@code options.surface-below} below / {@code options.surface-above} above
 *       a block boundary {@code Y} (the top of a liquid block; a source block's own surface is
 *       1/9 below it);</li>
 *   <li>every block at {@code Y-1} under the hitbox (half width 0.3 plus {@code options.box-margin}) is
 *       known, certain and a plain liquid (waterlogged, bubble-column and unknown blocks are uncertain
 *       in the mirror and therefore skipped; liquid levels are not known and not needed: no liquid
 *       level carries a player);</li>
 *   <li>the feet and head blocks {@code Y} and {@code Y+1} over the hitbox are known plain air (no
 *       lily pad, no ladder, no deeper water, no ceiling);</li>
 *   <li>no mirror change is still pending inside the scanned region (frost walker ice, melting ice);</li>
 *   <li>the vertical step is negligible: {@code |dy| <= flat-delta}, or the client claims
 *       {@code onGround} with {@code |dy| <= ground-delta} (spoofed ground over liquid).</li>
 * </ul>
 * {@code options.min-ticks} consecutive suspicious movement packets are tolerated; every further one
 * raises the buffer. Any non-suspicious packet resets the streak and lowers the buffer, so a jump into
 * water, a bob at the surface or a normal fall never accumulates.
 *
 * <p>Never judged (streak reset, buffer untouched): creative/spectator, the descriptor's exemptions
 * (join, respawn, world change, teleport, game mode/ability change, attribute change, vehicle,
 * knockback, explosion, portal, elytra boost, wind charge, riptide, bounce, lunge), flying, gliding,
 * swimming pose, levitation, slow falling, the first position, pending effect/attribute windows and
 * the first ticks after leaving a vehicle.
 *
 * <p>All numeric defaults are TUNING values (engineering estimates), not vanilla constants. Vanilla
 * facts used: player hitbox width 0.6; neither water nor lava has collision. UNCONFIRMED: the source
 * surface height of 8/9 block; that a stationary unridden boat or other unmirrored entity can carry a
 * player (its top then rests clearly above the 0.05 surface window, but this is not verified).
 */
public final class JesusCheck extends Check implements PositionCheck {

    /** TUNING: consecutive suspicious packets tolerated before the buffer rises (20 TPS: 0.4 s). */
    public static final int DEFAULT_MIN_TICKS = 8;
    /** TUNING: largest |dy| of a "resting" tick. */
    public static final double DEFAULT_FLAT_DELTA = 0.01;
    /** TUNING: largest |dy| of a tick that claims onGround over a liquid. */
    public static final double DEFAULT_GROUND_DELTA = 0.1;
    /** TUNING: feet may be this far below the block boundary (source surface is 1/9 = 0.111 below). */
    public static final double DEFAULT_SURFACE_BELOW = 0.15;
    /** TUNING: feet may be this far above the block boundary. */
    public static final double DEFAULT_SURFACE_ABOVE = 0.05;
    /** TUNING: margin beyond the 0.3 half-width so solid blocks next to the box skip the tick. */
    public static final double DEFAULT_BOX_MARGIN = 0.05;

    /** Vanilla player half width. */
    private static final double HALF_WIDTH = 0.3;
    private static final int MAX_AXIS_BLOCKS = 3;
    private static final int DISMOUNT_GRACE_TICKS = 10;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("jesus", CheckCategory.MOVEMENT)
            .displayName("Jesus")
            .description("Standing or walking on top of water or lava")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(10.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL, ExemptionCause.ELYTRA_BOOST,
                    ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE,
                    ExemptionCause.LUNGE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;

    private int minTicks = DEFAULT_MIN_TICKS;
    private double flatDelta = DEFAULT_FLAT_DELTA;
    private double groundDelta = DEFAULT_GROUND_DELTA;
    private double surfaceBelow = DEFAULT_SURFACE_BELOW;
    private double surfaceAbove = DEFAULT_SURFACE_ABOVE;
    private double boxMargin = DEFAULT_BOX_MARGIN;

    /** Consecutive suspicious packets; owner thread only. */
    private int streak;

    public JesusCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        int ticks = options.getInt("min-ticks", DEFAULT_MIN_TICKS);
        minTicks = ticks >= 2 && ticks <= 1000 ? ticks : DEFAULT_MIN_TICKS;
        flatDelta = MovementOptions.tolerance(options, "flat-delta", DEFAULT_FLAT_DELTA);
        groundDelta = MovementOptions.tolerance(options, "ground-delta", DEFAULT_GROUND_DELTA);
        surfaceBelow = MovementOptions.tolerance(options, "surface-below", DEFAULT_SURFACE_BELOW);
        surfaceAbove = MovementOptions.tolerance(options, "surface-above", DEFAULT_SURFACE_ABOVE);
        boxMargin = MovementOptions.tolerance(options, "box-margin", DEFAULT_BOX_MARGIN);
        // A window of 0.5 or more would make the nearest block boundary ambiguous.
        if (surfaceBelow + surfaceAbove >= 0.5) {
            surfaceBelow = DEFAULT_SURFACE_BELOW;
            surfaceAbove = DEFAULT_SURFACE_ABOVE;
        }
        if (boxMargin > 0.2) {
            boxMargin = DEFAULT_BOX_MARGIN;
        }
    }

    @Override
    protected void onReset() {
        streak = 0;
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (update.firstPosition() || update.teleport() || isExempt() || !judgeable()) {
            streak = 0;
            return;
        }
        if (!suspicious(update)) {
            streak = 0;
            lowerBuffer();
            return;
        }
        streak++;
        if (streak <= minTicks) {
            return;
        }
        if (debugging()) {
            debug("on-liquid streak=" + streak + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("on-liquid ticks=" + streak
                    + " y=" + MovementOptions.number(update.toY())
                    + " dy=" + MovementOptions.number(update.deltaY())
                    + " claimedGround=" + update.toGround());
            resetBuffer();
        }
    }

    /** State that makes the situation unjudgeable; allocation free. */
    private boolean judgeable() {
        GameMode mode = player.abilities().gameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.abilities().flying()) {
            return false;
        }
        PoseState pose = player.pose();
        if (pose.gliding() || pose.glideRequested() || pose.swimming()) {
            return false;
        }
        if (player.vehicle().inVehicle() || player.vehicle().ticksSinceDismount() < DISMOUNT_GRACE_TICKS) {
            return false;
        }
        EffectState effects = player.effects();
        AttributeState attributes = player.attributes();
        // Custom gravity or scale changes the physics this check reasons about: do not judge.
        return !effects.has(TrackedEffect.LEVITATION) && !effects.has(TrackedEffect.SLOW_FALLING)
                && !effects.anyPending() && !attributes.pending()
                && attributes.gravity() == AttributeState.DEFAULT_GRAVITY
                && attributes.scale() == AttributeState.DEFAULT_SCALE;
    }

    private boolean suspicious(MovementUpdate update) {
        double dy = Math.abs(update.deltaY());
        if (dy > flatDelta && !(update.toGround() && dy <= groundDelta)) {
            return false;
        }
        double feetY = update.toY();
        int boundary = (int) Math.round(feetY);
        double offset = feetY - boundary;
        if (offset < -surfaceBelow || offset > surfaceAbove) {
            return false;
        }
        WorldMirror world = player.component(worldKey);
        if (world == null) {
            return false;
        }
        int minX = floor(update.toX() - HALF_WIDTH - boxMargin);
        int maxX = floor(update.toX() + HALF_WIDTH + boxMargin);
        int minZ = floor(update.toZ() - HALF_WIDTH - boxMargin);
        int maxZ = floor(update.toZ() + HALF_WIDTH + boxMargin);
        if (maxX - minX >= MAX_AXIS_BLOCKS || maxZ - minZ >= MAX_AXIS_BLOCKS) {
            return false;
        }
        if (world.pending().any()) {
            world.pending().settle(player.acks().lastAckedMarker());
            if (world.pending().intersects(minX, boundary - 2, minZ, maxX, boundary + 1, maxZ)) {
                return false;
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!plainLiquid(world.getBlock(x, boundary - 1, z))
                        || !plainAir(world.getBlock(x, boundary, z))
                        || !plainAir(world.getBlock(x, boundary + 1, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean plainLiquid(BlockSnapshot block) {
        if (!block.known()) {
            return false;
        }
        BlockProperties properties = block.properties();
        return properties.liquid() != BlockProperties.Liquid.NONE
                && properties.collision() == BlockProperties.Collision.EMPTY
                && !properties.climbable() && properties.flags().isEmpty() && !properties.uncertain();
    }

    private static boolean plainAir(BlockSnapshot block) {
        if (!block.known()) {
            return false;
        }
        BlockProperties properties = block.properties();
        return properties.liquid() == BlockProperties.Liquid.NONE
                && properties.collision() == BlockProperties.Collision.EMPTY
                && !properties.climbable() && properties.flags().isEmpty() && !properties.uncertain();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** Current suspicious streak (tests, diagnostics). */
    public int streak() {
        return streak;
    }
}
