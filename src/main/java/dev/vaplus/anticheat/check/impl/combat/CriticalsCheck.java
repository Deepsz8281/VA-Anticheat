package dev.vaplus.anticheat.check.impl.combat;

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
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.PhysicsConstants;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Locale;

/**
 * Criticals: attacks made while the client claims to be airborne after a take-off that no jump,
 * fall or server impulse explains ("packet" crits and mini-jumps).
 *
 * <h2>Vanilla background</h2>
 * The server grants a critical hit from its own copy of the client-reported movement state: the
 * attacker must be falling (fall distance &gt; 0, i.e. it moved down while not on ground) and not on
 * ground, and must not be climbing, in water, blind, under Slow Falling, riding or flying; the attack
 * cooldown must be charged (Minecraft Wiki "Damage", critical hit: "84.8% or higher"; the
 * sprint-knockback exclusion is listed there "until JE Combat Tests"). The exact source-level
 * condition set of 1.21.x/26.x was not checked against decompiled code: UNCONFIRMED. Because fall
 * distance and the ground flag come from movement packets, a cheat can fake both without jumping:
 * it sends one or more position packets a few hundredths of a block above the floor (+0.0625,
 * +0.11, +1e-5, ...) with {@code onGround=false}, goes back down (fall distance &gt; 0) and attacks,
 * all inside one client tick, before the tick's regular movement packet.
 *
 * <h2>Signal</h2>
 * Per position packet the check tracks a short <em>take-off episode</em>:
 * <ol>
 *   <li>A packet that claims airborne after a ground claim is a take-off. It is judged only when the
 *       movement engine explained the packet ({@link SimulationResult.Status#COMPLETE COMPLETE},
 *       horizontal offset within {@code max-offset}) in simple terrain (the engine skips liquids,
 *       climbables, special blocks, edges, walls, unknown or unacknowledged world data), no server
 *       velocity is pending or was just applied, and the head room for a full first jump tick is
 *       known air in the world mirror (a ceiling bonk legitimately shortens a jump).</li>
 *   <li>The take-off is <em>fake</em> when the reported lift above the floor is at most
 *       {@code max-lift} and below the jump height ({@code jump_strength + 0.1 × Jump Boost level}
 *       minus {@code jump-margin}) and the engine's best candidate is not a jump: either it ends on
 *       the ground (the client cannot have left a floor that way) or its vertical offset exceeds
 *       {@code vertical-tolerance}. A real jump starts with exactly the jump velocity (0.42 by
 *       default; gravity 0.08, drag 0.98 afterwards), which the engine's jump candidate matches.</li>
 *   <li>The episode stays fake while further packets keep claiming airborne within {@code max-lift}
 *       of the floor, for at most {@code max-episode-packets} packets, and ends with the next ground
 *       claim.</li>
 * </ol>
 * An attack that arrives while a fake episode is open and the latest ground claim is airborne
 * raises the buffer (once per episode): (a) the correlated attack. When the previous correlated
 * attack lies at most {@code pattern-window} client ticks back, {@code pattern-bonus} is added: (b)
 * recurring micro-hops timed with attacks. Attacks on ground or during real jumps lower the buffer.
 *
 * <h2>Why legit play does not trigger it</h2>
 * Real jumps (any Jump Boost level, any jump_strength the attribute tracker knows) match the
 * engine's jump candidate; walking off an edge is skipped by the engine; knockback, explosions,
 * wind charges and other impulses are excluded via the velocity state and exemptions; ceiling bonks
 * are excluded by the head-room test; unknown world data, pending block changes, teleports,
 * join/respawn/world change, vehicles, flight, gliding, swimming, levitation and slow falling never
 * judge. Merely standing on ground and attacking never raises the buffer.
 *
 * <h2>Known bypasses / limits</h2>
 * Crits faked at a block edge, on slabs/stairs or other COMPLEX terrain are not judged (the engine
 * skips there). A cheat that performs a real, full jump (vanilla "jump crits") is legitimate
 * movement. Attacks on non-player entities are judged like any other attack because the check does
 * not need the target. Status-only packets (ground flag without a position) do not start an
 * episode; they also do not give the server a fall distance on their own.
 *
 * <p>Experimental: thresholds are tuning values, not live-calibrated.
 */
public final class CriticalsCheck extends Check implements PositionCheck, CombatCheck {

    /** TUNING: largest lift above the floor (blocks) a faked take-off may reach. Below the 0.42 jump. */
    public static final double DEFAULT_MAX_LIFT = 0.4;
    /** TUNING: a fake take-off must stay this far below the jump height of the first tick. */
    public static final double DEFAULT_JUMP_MARGIN = 0.02;
    /** TUNING: vertical offset beyond which the engine's best candidate counts as "not explained". */
    public static final double DEFAULT_VERTICAL_TOLERANCE = 0.01;
    /** TUNING: largest horizontal offset at which the engine's verdict is trusted. */
    public static final double DEFAULT_MAX_OFFSET = 0.1;
    /** TUNING: airborne packets after the take-off before the episode stops being a crit pattern. */
    public static final int DEFAULT_MAX_EPISODE_PACKETS = 4;
    /** TUNING: client ticks within which a second correlated attack counts as a recurring pattern. */
    public static final int DEFAULT_PATTERN_WINDOW = 40;
    /** TUNING: extra buffer for a recurring pattern. */
    public static final double DEFAULT_PATTERN_BONUS = 0.5;
    /** TUNING: extra height above the first jump tick that must be known air (head room). */
    public static final double HEADROOM_MARGIN = 0.05;
    /** Player bounding box half width; the engine's constant (UNCONFIRMED there as well). */
    private static final double HALF_WIDTH = PhysicsConstants.UNCONFIRMED_HALF_WIDTH;
    /** Player bounding box height; the engine's constant (UNCONFIRMED there as well). */
    private static final double HEIGHT = PhysicsConstants.UNCONFIRMED_HEIGHT;
    /** Feet within this distance of the floor count as standing on it (engine GROUND_EPSILON). */
    private static final double FLOOR_EPSILON = 1.0E-4;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("criticals", CheckCategory.COMBAT)
            .displayName("Criticals")
            .description("Attacks after a faked take-off (packet crits, mini-jumps)")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL, ExemptionCause.ELYTRA_BOOST,
                    ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE,
                    ExemptionCause.LUNGE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    // Ground verified by the engine on the latest position packet.
    private boolean groundKnown;
    private double groundY;

    // Open fake take-off episode.
    private boolean fakeEpisode;
    private boolean episodeJudged;
    private boolean episodeDescended;
    private int episodePackets;
    private double episodeFloor;
    private double episodePeakLift;
    private long episodeStartTick;

    private long lastCorrelatedTick = Long.MIN_VALUE;
    private long lastAppliedVelocities;
    private int fakeTakeOffs;

    private double maxLift = DEFAULT_MAX_LIFT;
    private double jumpMargin = DEFAULT_JUMP_MARGIN;
    private double verticalTolerance = DEFAULT_VERTICAL_TOLERANCE;
    private double maxOffset = DEFAULT_MAX_OFFSET;
    private int maxEpisodePackets = DEFAULT_MAX_EPISODE_PACKETS;
    private int patternWindow = DEFAULT_PATTERN_WINDOW;
    private double patternBonus = DEFAULT_PATTERN_BONUS;

    public CriticalsCheck(PlayerData player, ComponentKey<WorldMirror> worldKey,
                          ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxLift = CombatOptions.positive(options, "max-lift", DEFAULT_MAX_LIFT);
        jumpMargin = CombatOptions.tolerance(options, "jump-margin", DEFAULT_JUMP_MARGIN);
        verticalTolerance = CombatOptions.tolerance(options, "vertical-tolerance", DEFAULT_VERTICAL_TOLERANCE);
        maxOffset = CombatOptions.tolerance(options, "max-offset", DEFAULT_MAX_OFFSET);
        maxEpisodePackets = CombatOptions.atLeast(options, "max-episode-packets", DEFAULT_MAX_EPISODE_PACKETS, 1);
        patternWindow = CombatOptions.atLeast(options, "pattern-window", DEFAULT_PATTERN_WINDOW, 0);
        patternBonus = CombatOptions.tolerance(options, "pattern-bonus", DEFAULT_PATTERN_BONUS);
    }

    @Override
    protected void onReset() {
        clear();
        lastCorrelatedTick = Long.MIN_VALUE;
    }

    /** Number of fake take-offs seen (diagnostics, tests). */
    public int fakeTakeOffs() {
        return fakeTakeOffs;
    }

    /** Whether a fake take-off episode is open (diagnostics, tests). */
    public boolean fakeEpisodeOpen() {
        return fakeEpisode;
    }

    @Override
    public void onPosition(MovementUpdate update) {
        VelocityState velocity = player.component(velocityKey);
        long applied = velocity.appliedByEngine();
        boolean velocityApplied = applied != lastAppliedVelocities;
        lastAppliedVelocities = applied;
        if (update.teleport() || update.firstPosition() || isExempt() || unsupported()) {
            clear();
            return;
        }
        SimulationResult result = update.simulation();
        if (update.toGround()) {
            endEpisode();
            groundKnown = result.complete() && result.predictedOnGround()
                    && Math.abs(result.offsetY()) <= verticalTolerance + result.uncertainty();
            groundY = groundKnown ? update.fromY() + result.predictedY() : Double.NaN;
            return;
        }
        if (fakeEpisode) {
            episodePackets++;
            double lift = update.toY() - episodeFloor;
            if (episodePackets > maxEpisodePackets || lift > maxLift || lift < -FLOOR_EPSILON) {
                // Not a crit hop any more (real fall, impulse, ...): unknown, other checks own it.
                endEpisode();
            } else {
                episodePeakLift = Math.max(episodePeakLift, lift);
                if (update.deltaY() < 0.0) {
                    episodeDescended = true;
                }
            }
            groundKnown = false;
            return;
        }
        boolean takeOff = update.fromGround();
        boolean wasGrounded = groundKnown;
        double floorBefore = groundY;
        groundKnown = false;
        if (!takeOff || velocityApplied || velocity.size() > 0) {
            return;
        }
        double floor = fakeTakeOffFloor(update, result, wasGrounded, floorBefore);
        if (Double.isNaN(floor)) {
            return;
        }
        fakeTakeOffs++;
        fakeEpisode = true;
        episodeJudged = false;
        episodePackets = 1;
        episodeFloor = floor;
        episodePeakLift = update.toY() - floor;
        episodeDescended = false;
        episodeStartTick = player.clientTicks();
        if (debugging()) {
            debug("fake take-off lift=" + fmt(episodePeakLift) + " offsetY=" + fmt(result.offsetY())
                    + " predictedGround=" + result.predictedOnGround());
        }
    }

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked() || isExempt() || unsupported()) {
            return;
        }
        if (!fakeEpisode || player.movement().onGround()) {
            lowerBuffer();
            return;
        }
        if (episodeJudged) {
            return;
        }
        episodeJudged = true;
        long tick = player.clientTicks();
        boolean recurring = lastCorrelatedTick != Long.MIN_VALUE && tick - lastCorrelatedTick <= patternWindow;
        lastCorrelatedTick = tick;
        double weight = recurring ? 1.0 + patternBonus : 1.0;
        if (debugging()) {
            debug("attack in fake episode lift=" + fmt(episodePeakLift) + " recurring=" + recurring
                    + " buffer=" + fmt(buffer()));
        }
        if (raiseBuffer(weight)) {
            violation("fake-takeoff lift=" + fmt(episodePeakLift)
                    + " packets=" + episodePackets
                    + " descended=" + episodeDescended
                    + " recurring=" + recurring
                    + " ticks=" + (tick - episodeStartTick));
        }
    }

    /**
     * @return the floor height when this take-off is fake, otherwise NaN (legit or not judgeable)
     */
    private double fakeTakeOffFloor(MovementUpdate update, SimulationResult result, boolean wasGrounded,
                                    double floorBefore) {
        if (!result.complete() || result.horizontalOffset() > maxOffset + result.uncertainty()) {
            return Double.NaN;
        }
        double tolerance = verticalTolerance + result.uncertainty();
        boolean predictedGround = result.predictedOnGround();
        double floor;
        if (predictedGround) {
            floor = update.fromY() + result.predictedY();
        } else if (wasGrounded && Math.abs(update.fromY() - floorBefore) <= FLOOR_EPSILON
                && Math.abs(result.offsetY()) > tolerance) {
            floor = floorBefore;
        } else {
            return Double.NaN;
        }
        double lift = update.toY() - floor;
        double jumpPower = player.attributes().jumpStrength()
                + PhysicsConstants.JUMP_BOOST_PER_LEVEL * player.effects().level(TrackedEffect.JUMP_BOOST);
        if (lift < -FLOOR_EPSILON || lift > maxLift || lift >= jumpPower - jumpMargin) {
            return Double.NaN;
        }
        if (!headroomClear(update, floor, jumpPower)) {
            return Double.NaN;
        }
        return floor;
    }

    /** The space a first jump tick needs above the head is known, acknowledged, collision-free air. */
    private boolean headroomClear(MovementUpdate update, double floor, double jumpPower) {
        WorldMirror world = player.component(worldKey);
        int minX = (int) Math.floor(Math.min(update.fromX(), update.toX()) - HALF_WIDTH);
        int maxX = (int) Math.floor(Math.max(update.fromX(), update.toX()) + HALF_WIDTH);
        int minZ = (int) Math.floor(Math.min(update.fromZ(), update.toZ()) - HALF_WIDTH);
        int maxZ = (int) Math.floor(Math.max(update.fromZ(), update.toZ()) + HALF_WIDTH);
        int minY = (int) Math.floor(floor + HEIGHT);
        int maxY = (int) Math.floor(floor + HEIGHT + Math.max(jumpPower, 0.0) + HEADROOM_MARGIN);
        if (maxX - minX > 4 || maxZ - minZ > 4) {
            return false;
        }
        if (world.pending().any() && world.pending().intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockSnapshot block = world.getBlock(x, y, z);
                    if (!block.known() || block.properties().collision() != BlockProperties.Collision.EMPTY) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** States in which a take-off cannot be judged (the engine skips them as well). */
    private boolean unsupported() {
        if (player.vehicle().inVehicle() || player.abilities().flying()) {
            return true;
        }
        PoseState pose = player.pose();
        if (pose.gliding() || pose.glideRequested() || pose.swimming()) {
            return true;
        }
        EffectState effects = player.effects();
        return effects.has(TrackedEffect.LEVITATION) || effects.has(TrackedEffect.SLOW_FALLING);
    }

    private void endEpisode() {
        fakeEpisode = false;
        episodeJudged = false;
        episodePackets = 0;
    }

    private void clear() {
        endEpisode();
        groundKnown = false;
        groundY = Double.NaN;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
