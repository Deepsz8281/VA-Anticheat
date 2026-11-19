package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.CombatCheck;
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
 * Fake mace smash ("mace kill"): the client claims a fall it never made.
 *
 * <p>Vanilla (Minecraft Wiki, Mace, fetched 2026-11-15): a smash attack needs a fall of at least
 * 1.5 blocks and its bonus damage grows with every further block fallen (4 per block for blocks
 * 1-3, 2 for 4-8, 1 beyond). The server derives the fall distance from the positions the client
 * reports, so a cheat sends position packets far above the target (often several in a row, each
 * under the vanilla 10 block per packet limit), attacks, and drops back down in the same or the
 * next tick.
 *
 * <p>The check records the recent positions and marks an <em>anomaly</em> when
 * <ul>
 *   <li><b>rise</b>: the position is more than {@code max-rise} above the lowest position of the
 *       last {@code rise-window-ticks} (a jump peaks at 1.25 blocks; the default leaves room for a
 *       jump taken right after a stair step), or</li>
 *   <li><b>drop</b>: one packet descends faster than terminal velocity allows for the ticks it
 *       covers (3.92 blocks per tick plus {@code drop-tolerance}).</li>
 * </ul>
 * An attack within {@code suspect-ticks} after an anomaly (or an anomaly within one tick after the
 * attack) is flagged, regardless of the held item. Nothing is judged during teleports, velocity
 * windows (knockback, explosions, wind charges), jump boost or levitation (also while unacknowledged),
 * elytra, flight, vehicles, creative and spectator mode. A recent USE_ITEM (possible wind charge)
 * widens the rise limit to {@code wind-charge-rise}. When a world mirror is available, rises that
 * start in or next to liquids, climbables, bubble columns or unknown blocks are not judged.
 *
 * <p>UNCONFIRMED: spear/lunge behaviour of 26.x (not verified on the wiki; only the LUNGE exemption
 * cause is honoured, nothing grants it yet); piston/slime launches that send no velocity packet.
 * Known false-positive risk: a Riptide launch in rain on land whose trident use started more than
 * {@code wind-charge-ticks} before the release (release packets are not tracked yet).
 */
public final class MaceCheck extends Check implements CombatCheck, PositionCheck, BlockCheck {

    public static final double DEFAULT_MAX_RISE = 2.0;
    public static final int DEFAULT_RISE_WINDOW_TICKS = 5;
    public static final double DEFAULT_DROP_TOLERANCE = 0.5;
    public static final int DEFAULT_SUSPECT_TICKS = 3;
    public static final int DEFAULT_VELOCITY_GRACE_TICKS = 25;
    public static final double DEFAULT_WIND_CHARGE_RISE = 12.0;
    public static final int DEFAULT_WIND_CHARGE_TICKS = 40;
    public static final double DEFAULT_HUGE_RISE = 8.0;
    public static final int DEFAULT_ENVIRONMENT_GRACE_TICKS = 30;

    /** Vanilla terminal fall speed in blocks per tick (gravity 0.08, drag 0.98). */
    static final double TERMINAL_VELOCITY = 3.92;
    private static final int RING = 16;
    private static final int MASK = RING - 1;
    private static final int DISMOUNT_TICKS = 20;

    private static final int KIND_NONE = 0;
    private static final int KIND_RISE = 1;
    private static final int KIND_DROP = 2;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("mace", CheckCategory.COMBAT)
            .displayName("Mace")
            .description("Attacks backed by a fake fall (teleport up and back down before the hit)")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
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

    private final ComponentKey<VelocityState> velocityKey;
    private final ComponentKey<WorldMirror> worldKey;

    private final double[] sampleX = new double[RING];
    private final double[] sampleY = new double[RING];
    private final double[] sampleZ = new double[RING];
    private final long[] sampleTick = new long[RING];
    private int head;
    private int count;

    private long suspectTick = Long.MIN_VALUE;
    private int suspectKind = KIND_NONE;
    private double suspectMagnitude;
    private long lastAttackTick = Long.MIN_VALUE;
    private long lastUseTick = Long.MIN_VALUE;
    private long lastVelocityTick = Long.MIN_VALUE;
    private long seenVelocities = -1L;
    private long environmentTick = Long.MIN_VALUE;

    private double maxRise = DEFAULT_MAX_RISE;
    private int riseWindowTicks = DEFAULT_RISE_WINDOW_TICKS;
    private double dropTolerance = DEFAULT_DROP_TOLERANCE;
    private int suspectTicks = DEFAULT_SUSPECT_TICKS;
    private int velocityGraceTicks = DEFAULT_VELOCITY_GRACE_TICKS;
    private double windChargeRise = DEFAULT_WIND_CHARGE_RISE;
    private int windChargeTicks = DEFAULT_WIND_CHARGE_TICKS;
    private double hugeRise = DEFAULT_HUGE_RISE;
    private int environmentGraceTicks = DEFAULT_ENVIRONMENT_GRACE_TICKS;

    /** Both keys may be {@code null}: velocity windows / world gating are then not applied. */
    public MaceCheck(PlayerData player, ComponentKey<VelocityState> velocityKey,
                     ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.velocityKey = velocityKey;
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxRise = CombatOptions.positive(options, "max-rise", DEFAULT_MAX_RISE);
        riseWindowTicks = Math.min(RING - 1,
                CombatOptions.atLeast(options, "rise-window-ticks", DEFAULT_RISE_WINDOW_TICKS, 1));
        dropTolerance = CombatOptions.tolerance(options, "drop-tolerance", DEFAULT_DROP_TOLERANCE);
        suspectTicks = CombatOptions.atLeast(options, "suspect-ticks", DEFAULT_SUSPECT_TICKS, 1);
        velocityGraceTicks = CombatOptions.atLeast(options, "velocity-grace-ticks", DEFAULT_VELOCITY_GRACE_TICKS, 0);
        windChargeRise = Math.max(maxRise, CombatOptions.positive(options, "wind-charge-rise", DEFAULT_WIND_CHARGE_RISE));
        windChargeTicks = CombatOptions.atLeast(options, "wind-charge-ticks", DEFAULT_WIND_CHARGE_TICKS, 0);
        hugeRise = Math.max(maxRise, CombatOptions.positive(options, "huge-rise", DEFAULT_HUGE_RISE));
        environmentGraceTicks = CombatOptions.atLeast(options, "environment-grace-ticks",
                DEFAULT_ENVIRONMENT_GRACE_TICKS, 0);
    }

    @Override
    protected void onReset() {
        count = 0;
        head = 0;
        suspectKind = KIND_NONE;
        suspectTick = Long.MIN_VALUE;
        lastAttackTick = Long.MIN_VALUE;
        lastUseTick = Long.MIN_VALUE;
        lastVelocityTick = Long.MIN_VALUE;
        seenVelocities = -1L;
        environmentTick = Long.MIN_VALUE;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (interaction.kind() == BlockInteraction.Kind.USE_ITEM) {
            lastUseTick = player.clientTicks();
        }
    }

    @Override
    public void onPosition(MovementUpdate update) {
        long now = player.clientTicks();
        pollVelocity(now);
        if (update.firstPosition() || update.teleport() || !update.positionChanged()) {
            if (update.firstPosition() || update.teleport()) {
                restart(update, now);
            }
            return;
        }
        if (!judgeable(now)) {
            restart(update, now);
            return;
        }

        double dy = update.deltaY();
        int elapsed = Math.max(1, update.elapsedTicks());
        boolean drop = dy < -(TERMINAL_VELOCITY * elapsed + dropTolerance);

        push(update.toX(), update.toY(), update.toZ(), now);
        double rise = 0.0;
        int lowest = -1;
        double low = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int index = (head - 1 - i) & MASK;
            if (now - sampleTick[index] > riseWindowTicks) {
                break;
            }
            if (sampleY[index] < low) {
                low = sampleY[index];
                lowest = index;
            }
        }
        if (lowest >= 0) {
            rise = update.toY() - low;
        }
        boolean windy = within(now, lastUseTick, windChargeTicks);
        double limit = windy ? windChargeRise : maxRise;
        int kind = KIND_NONE;
        double magnitude = 0.0;
        if (rise > limit && !environmentSuspicious(lowest, now)) {
            kind = KIND_RISE;
            magnitude = rise;
        } else if (drop) {
            kind = KIND_DROP;
            magnitude = -dy;
        }
        if (kind == KIND_NONE) {
            return;
        }
        suspectTick = now;
        suspectKind = kind;
        suspectMagnitude = magnitude;
        if (debugging()) {
            debug((kind == KIND_RISE ? "rise " : "drop ") + CombatOptions.number(magnitude)
                    + " blocks, windy=" + windy);
        }
        // The attack already arrived: the packet order is attack, then this tick's movement.
        if (within(now, lastAttackTick, 1)) {
            flag(now, "after-attack");
        }
    }

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked()) {
            return;
        }
        long now = player.clientTicks();
        pollVelocity(now);
        if (!judgeable(now)) {
            return;
        }
        lastAttackTick = now;
        if (suspectKind != KIND_NONE && within(now, suspectTick, suspectTicks)) {
            flag(now, "before-attack");
        } else if (count > 0) {
            lowerBuffer();
        }
    }

    private void flag(long now, String when) {
        int kind = suspectKind;
        double magnitude = suspectMagnitude;
        long age = now - suspectTick;
        suspectKind = KIND_NONE;
        lastAttackTick = Long.MIN_VALUE;
        double weight = magnitude >= hugeRise ? 2.0 : 1.0;
        if (raiseBuffer(weight)) {
            violation((kind == KIND_RISE ? "fake-rise" : "fake-drop")
                    + " " + when
                    + " size=" + CombatOptions.number(magnitude)
                    + " ageTicks=" + age
                    + " buffer=" + CombatOptions.number(buffer()));
            resetBuffer();
        }
    }

    private boolean judgeable(long now) {
        if (isExempt()) {
            return false;
        }
        GameMode mode = player.abilities().gameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return false;
        }
        if (player.abilities().flying() || player.abilities().flightAllowed()) {
            return false;
        }
        if (player.vehicle().inVehicle() || player.vehicle().ticksSinceDismount() < DISMOUNT_TICKS) {
            return false;
        }
        if (player.pose().gliding() || player.pose().glideRequested() || player.pose().swimming()) {
            return false;
        }
        EffectState effects = player.effects();
        if (effects.anyPending() || effects.has(TrackedEffect.JUMP_BOOST) || effects.has(TrackedEffect.LEVITATION)) {
            return false;
        }
        if (lastVelocityTick != Long.MIN_VALUE) {
            long rtt = player.acks().smoothedRttMillis();
            long grace = velocityGraceTicks + (rtt <= 0 ? 0 : Math.min(40, (rtt + 49) / 50));
            if (now - lastVelocityTick <= grace) {
                return false;
            }
        }
        return true;
    }

    private void pollVelocity(long now) {
        if (velocityKey == null) {
            return;
        }
        long registered = player.component(velocityKey).registered();
        if (seenVelocities >= 0 && registered != seenVelocities) {
            lastVelocityTick = now;
        }
        seenVelocities = registered;
    }

    /**
     * Whether the rise started somewhere the engine cannot judge (liquid, ladder, bubble column,
     * unknown block) or recently did. Only evaluated when a rise is about to be flagged.
     */
    private boolean environmentSuspicious(int lowestSample, long now) {
        if (worldKey == null || lowestSample < 0) {
            return false;
        }
        if (within(now, environmentTick, environmentGraceTicks)) {
            return true;
        }
        WorldMirror world = player.component(worldKey);
        int x = (int) Math.floor(sampleX[lowestSample]);
        int y = (int) Math.floor(sampleY[lowestSample]);
        int z = (int) Math.floor(sampleZ[lowestSample]);
        for (int dy = -1; dy <= 1; dy++) {
            BlockSnapshot block = world.getBlock(x, y + dy, z);
            BlockProperties properties = block.properties();
            if (!block.known()
                    || properties.liquid() != BlockProperties.Liquid.NONE
                    || properties.climbable()
                    || !properties.flags().isEmpty()) {
                environmentTick = now;
                return true;
            }
        }
        return false;
    }

    /** Whether {@code tick} is set and at most {@code window} ticks before {@code now} (overflow safe). */
    static boolean within(long now, long tick, long window) {
        return tick != Long.MIN_VALUE && now - tick <= window;
    }

    private void restart(MovementUpdate update, long now) {
        count = 0;
        head = 0;
        suspectKind = KIND_NONE;
        push(update.toX(), update.toY(), update.toZ(), now);
    }

    private void push(double x, double y, double z, long now) {
        sampleX[head] = x;
        sampleY[head] = y;
        sampleZ[head] = z;
        sampleTick[head] = now;
        head = (head + 1) & MASK;
        if (count < RING) {
            count++;
        }
    }
}
