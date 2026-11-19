package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.BlockCheck;
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
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * Elytra (ElytraFly): gliding that vanilla glide physics cannot produce without a boost.
 *
 * <p>Vanilla glide tick (client {@code LivingEntity} fall-flying travel, read from client code;
 * UNCONFIRMED against a capture): gravity is applied as {@code g × (-1 + 0.75 cos²(pitch))}, so the
 * vertical motion always loses at least {@code 0.25 g} per tick; lift (the only upward term apart from
 * pulling up) only exists while the player is already moving and costs horizontal speed; all motion is
 * damped by 0.99/0.98 per tick. Two consequences are judged, only while the server has acknowledged
 * the gliding pose and only in known free air:
 * <ul>
 *   <li><b>hover</b> — {@code hover-ticks} consecutive ticks with almost no movement at all
 *       ({@code |dy| <= hover-vertical}, horizontal {@code <= hover-horizontal} per tick). Gravity
 *       accelerates a stationary glider downward every tick.</li>
 *   <li><b>climb</b> — {@code climb-ticks} consecutive ticks without descending ({@code dy >= 0}).
 *       Climbing (pulling up) converts horizontal speed into height and loses at least 1% speed per
 *       tick to drag, so a pull-up from any dive ends in a descent long before that.</li>
 * </ul>
 *
 * <p>Why legit play does not trigger it: any item use (firework rockets, wind charges, tridents) and
 * any server velocity suspend judging for {@code boost-grace-ticks} (a rocket lasts at most about
 * 51 ticks); ceilings, walls, liquids, climbables, special blocks and unknown blocks around the
 * player reset the streaks; levitation, slow falling, pending effects, flight, vehicles and
 * creative/spectator (category exemption) are skipped.
 *
 * <p>Known bypasses: speed hacks that keep descending, rocket-boost multipliers (speed is not
 * bounded here), and hacks that only climb for less than {@code climb-ticks}. All numbers are TUNING
 * values. Experimental.
 */
public final class ElytraCheck extends Check implements PositionCheck, BlockCheck {

    public static final int DEFAULT_HOVER_TICKS = 20;
    public static final double DEFAULT_HOVER_VERTICAL = 0.005;
    public static final double DEFAULT_HOVER_HORIZONTAL = 0.05;
    public static final int DEFAULT_CLIMB_TICKS = 100;
    public static final int DEFAULT_BOOST_GRACE_TICKS = 80;
    /** Half the player width plus a margin for the free-air scan. */
    private static final double SCAN_REACH = 0.5;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("elytra", CheckCategory.MOVEMENT)
            .displayName("Elytra")
            .description("Gliding that hovers or keeps climbing without a boost")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0)
                    .withSetbackVl(-1.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL, ExemptionCause.ELYTRA_BOOST,
                    ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE,
                    ExemptionCause.LUNGE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private int hoverTicksLimit = DEFAULT_HOVER_TICKS;
    private double hoverVertical = DEFAULT_HOVER_VERTICAL;
    private double hoverHorizontal = DEFAULT_HOVER_HORIZONTAL;
    private int climbTicksLimit = DEFAULT_CLIMB_TICKS;
    private int boostGraceTicks = DEFAULT_BOOST_GRACE_TICKS;

    private int hoverTicks;
    private int climbTicks;
    private long lastBoostTick = Long.MIN_VALUE;
    private long seenVelocities = -1L;

    public ElytraCheck(PlayerData player, ComponentKey<WorldMirror> worldKey, ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        hoverTicksLimit = bounded(options.getInt("hover-ticks", DEFAULT_HOVER_TICKS), 5, 1200, DEFAULT_HOVER_TICKS);
        hoverVertical = MovementOptions.tolerance(options, "hover-vertical", DEFAULT_HOVER_VERTICAL);
        hoverHorizontal = MovementOptions.tolerance(options, "hover-horizontal", DEFAULT_HOVER_HORIZONTAL);
        climbTicksLimit = bounded(options.getInt("climb-ticks", DEFAULT_CLIMB_TICKS), 20, 6000, DEFAULT_CLIMB_TICKS);
        boostGraceTicks = bounded(options.getInt("boost-grace-ticks", DEFAULT_BOOST_GRACE_TICKS), 0, 1200,
                DEFAULT_BOOST_GRACE_TICKS);
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        streakReset();
        lastBoostTick = Long.MIN_VALUE;
        seenVelocities = -1L;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (interaction.kind() == BlockInteraction.Kind.USE_ITEM || interaction.kind() == BlockInteraction.Kind.PLACE) {
            // Rockets are used from the hand (USE_ITEM) or against a block; both may boost.
            lastBoostTick = player.clientTicks();
            streakReset();
        }
    }

    @Override
    public void onPosition(MovementUpdate update) {
        long now = player.clientTicks();
        long registered = player.component(velocityKey).registered();
        if (seenVelocities >= 0 && registered != seenVelocities) {
            lastBoostTick = now;
        }
        seenVelocities = registered;
        if (!update.positionChanged() || update.firstPosition() || update.teleport() || !judgeable(now)) {
            streakReset();
            return;
        }
        if (!freeAir(update)) {
            streakReset();
            return;
        }
        int ticks = Math.max(1, update.elapsedTicks());
        judge(ticks, update.deltaY(), update.horizontalDistance());
    }

    /** Decision logic on primitives (tests): one judgeable gliding packet in free air. */
    void judge(int ticks, double dy, double horizontal) {
        boolean hovering = Math.abs(dy) <= hoverVertical * ticks && horizontal <= hoverHorizontal * ticks;
        hoverTicks = hovering ? hoverTicks + ticks : 0;
        climbTicks = dy >= 0.0 ? climbTicks + ticks : 0;
        String signal = null;
        if (hoverTicks > hoverTicksLimit) {
            signal = "hover ticks=" + hoverTicks;
            hoverTicks = 0;
        } else if (climbTicks > climbTicksLimit) {
            signal = "climb ticks=" + climbTicks;
            climbTicks = 0;
        }
        if (signal == null) {
            if (dy < 0.0) {
                lowerBuffer();
            }
            return;
        }
        if (debugging()) {
            debug(signal + " dy=" + MovementOptions.number(dy) + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(signal + " dy=" + MovementOptions.number(dy)
                    + " horizontal=" + MovementOptions.number(horizontal));
        }
    }

    private boolean judgeable(long now) {
        if (isExempt() || !player.pose().gliding()) {
            return false;
        }
        if (lastBoostTick != Long.MIN_VALUE && now - lastBoostTick <= boostGraceTicks) {
            return false;
        }
        if (player.abilities().flying() || player.vehicle().inVehicle()) {
            return false;
        }
        EffectState effects = player.effects();
        return !effects.has(TrackedEffect.LEVITATION) && !effects.has(TrackedEffect.SLOW_FALLING)
                && !effects.anyPending() && !player.attributes().pending()
                && player.component(velocityKey).size() == 0;
    }

    /** Known, collision-free, non-liquid, non-special blocks from one below the feet to above the head. */
    private boolean freeAir(MovementUpdate update) {
        WorldMirror world = player.component(worldKey);
        double x = update.toX();
        double y = update.toY();
        double z = update.toZ();
        int x0 = (int) Math.floor(x - SCAN_REACH);
        int x1 = (int) Math.floor(x + SCAN_REACH);
        int z0 = (int) Math.floor(z - SCAN_REACH);
        int z1 = (int) Math.floor(z + SCAN_REACH);
        int y0 = (int) Math.floor(y) - 1;
        int y1 = (int) Math.floor(y + 2.0);
        if (world.pending().intersects(x0, y0, z0, x1, y1, z1)) {
            return false;
        }
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int by = y0; by <= y1; by++) {
                    BlockSnapshot block = world.getBlock(bx, by, bz);
                    if (!block.known()) {
                        return false;
                    }
                    BlockProperties properties = block.properties();
                    if (properties.collision() != BlockProperties.Collision.EMPTY
                            || properties.liquid() != BlockProperties.Liquid.NONE
                            || properties.climbable() || !properties.flags().isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void streakReset() {
        hoverTicks = 0;
        climbTicks = 0;
    }
}
