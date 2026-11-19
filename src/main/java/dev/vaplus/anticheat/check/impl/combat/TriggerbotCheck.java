package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.CombatCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.check.impl.world.FreecamCheck;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.BlockSight;

/**
 * Triggerbot: the attack fires because the crosshair touched the target, not because a human
 * decided to click.
 *
 * <p>A triggerbot is invisible to the other combat checks: range, angle and line of sight are all
 * legitimate, and with a randomised delay its click rate looks human too. What it cannot hide is
 * the <em>cause</em> of the click. The check reconstructs that cause from three observations:
 *
 * <ol>
 *   <li><b>Cold click.</b> Only the first click after a pause ({@code min-idle-ticks} without any
 *       swing) is judged. A player who is already clicking hits the instant the crosshair arrives
 *       by coincidence; a click that starts from rest was started by something.</li>
 *   <li><b>Acquisition time.</b> For that click the check walks back through the recorded look rays
 *       and finds the last client tick on which the crosshair was <em>provably</em> off the target.
 *       The attack came at most that many ticks after the target became hittable. A click from rest
 *       within {@code max-reaction-ticks} (default 2 = 100 ms) is below the human visual reaction
 *       time; it is either a planned flick or a machine.</li>
 *   <li><b>Misses.</b> Planned flicks and anticipation fail regularly: the human clicks a tick too
 *       early or beside the hitbox and swings at air. A triggerbot never does. Every swing that
 *       goes past the target while aiming near it pays the buffer back.</li>
 * </ol>
 *
 * <p>A fast cold click during an unbroken sweep (the view keeps turning after the hit instead of
 * settling on the target) weighs more: a human flick ends on the target, a triggerbot fires while
 * the crosshair merely passes through.
 *
 * <p>"Provably off" is latency-safe: the client renders the target where the server had it some
 * ticks ago, so the ray must miss every position the target held within the possible lag window
 * (round trip plus entity interpolation). Unknown history never counts as off, therefore the first
 * hit on a new target and hits while already tracking the target produce no sample at all.
 *
 * <p>Needs the exact tick boundaries of 1.21.2+ clients (tick-end packet); older clients are not
 * judged. Experimental: thresholds are tuning values without live calibration.
 */
public final class TriggerbotCheck extends Check implements CombatCheck, ClientTickCheck, PacketCheck, BlockCheck {

    public static final int DEFAULT_MIN_IDLE_TICKS = 6;
    public static final int DEFAULT_MAX_REACTION_TICKS = 2;
    public static final int DEFAULT_LOOKBACK_TICKS = 10;
    public static final double DEFAULT_OFF_MARGIN = 0.25;
    public static final double DEFAULT_MISS_RELIEF = 1.0;
    public static final double DEFAULT_SWEEP_WEIGHT = 1.5;

    /** Interpolation delay of remote entities on the client, in ticks. */
    static final int INTERPOLATION_TICKS = 3;
    static final int MAX_LAG_TICKS = 12;
    /** A swing counts as an attempt on the target when the ray passes within this distance of its box. */
    static final double NEAR_MISS_MARGIN = 1.5;
    static final double COMBAT_RANGE = 6.0;
    /** Turn (degrees per tick) that counts as a sweep; below it the view is considered at rest. */
    static final double SWEEP_MIN_DEGREES = 2.0;

    private static final int RING = 64;
    private static final int MASK = RING - 1;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("triggerbot", CheckCategory.COMBAT)
            .displayName("Triggerbot")
            .description("Attacks the instant the crosshair reaches a target, without ever missing")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(4.0)
                    .withBufferDecay(0.5)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private final ComponentKey<NearbyPlayers> trackedKey;

    // Per-tick history, indexed by tick & MASK. Eye NaN = pose not modelled / position unknown.
    private final double[] eyeX = new double[RING];
    private final double[] eyeY = new double[RING];
    private final double[] eyeZ = new double[RING];
    private final double[] dirX = new double[RING];
    private final double[] dirY = new double[RING];
    private final double[] dirZ = new double[RING];
    private final double[] targetX = new double[RING];
    private final double[] targetY = new double[RING];
    private final double[] targetZ = new double[RING];
    private final double[] targetHalf = new double[RING];
    private final double[] targetHeight = new double[RING];
    private final double[] look = new double[3];

    private long tick;
    private int targetId = -1;
    /** First tick with a history entry for {@link #targetId}. */
    private long targetSince;

    private boolean attackThisTick;
    private int attackEntity;
    private boolean swingThisTick;
    private boolean blockActionThisTick;
    private boolean digging;
    /** Last tick with any swing or attack; -1 = none yet. */
    private long lastClickTick = -1L;

    private long pendingAttackTick = -1L;
    private boolean pendingCold;

    private int fastSamples;
    private int slowSamples;
    private int missSwings;

    private int minIdleTicks = DEFAULT_MIN_IDLE_TICKS;
    private int maxReactionTicks = DEFAULT_MAX_REACTION_TICKS;
    private int lookbackTicks = DEFAULT_LOOKBACK_TICKS;
    private double offMargin = DEFAULT_OFF_MARGIN;
    private double missRelief = DEFAULT_MISS_RELIEF;
    private double sweepWeight = DEFAULT_SWEEP_WEIGHT;

    public TriggerbotCheck(PlayerData player, ComponentKey<NearbyPlayers> trackedKey) {
        super(player, DESCRIPTOR);
        this.trackedKey = trackedKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        minIdleTicks = CombatOptions.atLeast(options, "min-idle-ticks", DEFAULT_MIN_IDLE_TICKS, 2);
        maxReactionTicks = Math.min(6,
                CombatOptions.atLeast(options, "max-reaction-ticks", DEFAULT_MAX_REACTION_TICKS, 1));
        lookbackTicks = Math.min(20, Math.max(maxReactionTicks + 1,
                CombatOptions.atLeast(options, "lookback-ticks", DEFAULT_LOOKBACK_TICKS, 2)));
        offMargin = CombatOptions.tolerance(options, "off-margin", DEFAULT_OFF_MARGIN);
        missRelief = CombatOptions.tolerance(options, "miss-relief", DEFAULT_MISS_RELIEF);
        sweepWeight = Math.max(1.0, CombatOptions.positive(options, "sweep-weight", DEFAULT_SWEEP_WEIGHT));
    }

    @Override
    protected void onReset() {
        targetId = -1;
        pendingAttackTick = -1L;
        lastClickTick = -1L;
        attackThisTick = false;
        swingThisTick = false;
        blockActionThisTick = false;
        digging = false;
        fastSamples = 0;
        slowSamples = 0;
        missSwings = 0;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            onSwing();
        }
    }

    /** An arm swing arrived in the current client tick (attack, miss, dig or block use). */
    void onSwing() {
        swingThisTick = true;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        blockActionThisTick = true;
        switch (interaction.kind()) {
            // Creative breaks instantly and never sends a finish, which would leave the flag stuck.
            case DIG_START -> digging = !player.isExempt(CheckCategory.WORLD);
            case DIG_CANCEL, DIG_FINISH -> digging = false;
            default -> {
            }
        }
    }

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked() || attackThisTick) {
            return;
        }
        attackThisTick = true;
        attackEntity = attack.entityId();
        digging = false;
    }

    @Override
    public void onClientTick() {
        long current = ++tick;
        record(current);

        if (pendingAttackTick == current - 1) {
            // Judged one tick late on purpose: the sweep test needs the look direction after the hit.
            judge(pendingAttackTick);
        }
        pendingAttackTick = -1L;

        boolean usable = player.tickEndMode() && !isExempt();
        if (attackThisTick) {
            if (attackEntity != targetId) {
                // New opponent: its history starts now, so this hit cannot be judged.
                targetId = attackEntity;
                targetSince = current;
                recordTarget(current);
            } else if (usable) {
                pendingAttackTick = current;
                pendingCold = lastClickTick < 0 || current - lastClickTick > minIdleTicks;
            }
            lastClickTick = current;
        } else if (swingThisTick) {
            if (usable && !blockActionThisTick && !digging && nearMiss(current)) {
                missSwings++;
                lowerBuffer(missRelief);
                if (debugging()) {
                    debug("miss swing near target, buffer=" + CombatOptions.number(buffer()));
                }
            }
            lastClickTick = current;
        }
        attackThisTick = false;
        swingThisTick = false;
        blockActionThisTick = false;
    }

    private void record(long current) {
        int slot = (int) (current & MASK);
        MovementState movement = player.movement();
        PoseState pose = player.pose();
        if (!movement.positionKnown() || !movement.rotationKnown() || pose.gliding() || pose.swimming()) {
            eyeX[slot] = Double.NaN;
        } else {
            eyeX[slot] = movement.x();
            eyeY[slot] = movement.y() + (pose.sneaking()
                    ? FreecamCheck.SNEAKING_EYE_HEIGHT : FreecamCheck.STANDING_EYE_HEIGHT);
            eyeZ[slot] = movement.z();
            FreecamCheck.direction(movement.yaw(), movement.pitch(), look);
            dirX[slot] = look[0];
            dirY[slot] = look[1];
            dirZ[slot] = look[2];
        }
        recordTarget(current);
    }

    private void recordTarget(long current) {
        int slot = (int) (current & MASK);
        int index = targetId < 0 ? -1 : player.component(trackedKey).indexOf(targetId);
        if (index < 0) {
            targetX[slot] = Double.NaN;
            return;
        }
        NearbyPlayers tracked = player.component(trackedKey);
        targetX[slot] = tracked.x(index);
        targetY[slot] = tracked.y(index);
        targetZ[slot] = tracked.z(index);
        targetHalf[slot] = tracked.halfWidth(index);
        targetHeight[slot] = tracked.height(index);
    }

    private void judge(long attackTick) {
        if (!pendingCold) {
            return;
        }
        int lag = lagTicks();
        int reaction = -1;
        for (int k = 1; k <= lookbackTicks; k++) {
            int state = aim(attackTick - k, lag, offMargin);
            if (state == AIM_UNKNOWN) {
                return;
            }
            if (state == AIM_OFF) {
                reaction = k;
                break;
            }
        }
        if (reaction < 0) {
            // The crosshair may have been on the target the whole time: tracking, not acquiring.
            return;
        }
        if (reaction > maxReactionTicks) {
            slowSamples++;
            lowerBuffer();
            if (debugging()) {
                debug("slow acquisition reaction<=" + reaction + "t buffer=" + CombatOptions.number(buffer()));
            }
            return;
        }
        fastSamples++;
        double before = turnDegrees(attackTick - 1, attackTick);
        double after = turnDegrees(attackTick, attackTick + 1);
        boolean sweep = before >= SWEEP_MIN_DEGREES && after >= 0.5 * before;
        if (debugging()) {
            debug("fast acquisition reaction<=" + reaction + "t sweep=" + sweep
                    + " turn=" + CombatOptions.number(before) + "/" + CombatOptions.number(after)
                    + " buffer=" + CombatOptions.number(buffer()));
        }
        if (raiseBuffer(sweep ? sweepWeight : 1.0)) {
            violation("entity=" + targetId
                    + " reaction<=" + reaction * 50 + "ms"
                    + " sweep=" + sweep
                    + " fast=" + fastSamples + " slow=" + slowSamples + " misses=" + missSwings
                    + " lag=" + lag + "t"
                    + " buffer=" + CombatOptions.number(buffer()));
            resetBuffer();
            fastSamples = 0;
            slowSamples = 0;
            missSwings = 0;
        }
    }

    private static final int AIM_UNKNOWN = 0;
    private static final int AIM_OFF = 1;
    private static final int AIM_MAYBE_ON = 2;

    /**
     * Where the crosshair was at the start of client tick {@code at}: the client picks its target
     * from the eye position it ended the previous tick with and the look direction it sends in
     * this one. {@code AIM_OFF} only when the ray misses every position the target held within the
     * lag window, each grown by {@code margin}.
     */
    private int aim(long at, int lag, double margin) {
        long oldest = at - lag;
        if (at < 2 || oldest < targetSince || tick - (oldest - 1) >= RING) {
            return AIM_UNKNOWN;
        }
        int eye = (int) ((at - 1) & MASK);
        int dir = (int) (at & MASK);
        if (Double.isNaN(eyeX[eye]) || Double.isNaN(eyeX[dir])) {
            return AIM_UNKNOWN;
        }
        for (long i = oldest; i <= at; i++) {
            int slot = (int) (i & MASK);
            if (Double.isNaN(targetX[slot])) {
                return AIM_UNKNOWN;
            }
            double half = targetHalf[slot] + margin;
            double height = targetHeight[slot];
            double entry = BlockSight.rayBoxEntry(eyeX[eye], eyeY[eye], eyeZ[eye],
                    dirX[dir], dirY[dir], dirZ[dir],
                    targetX[slot] - half, targetY[slot] - margin, targetZ[slot] - half,
                    targetX[slot] + half, targetY[slot] + height + margin, targetZ[slot] + half);
            if (!Double.isNaN(entry) && entry <= COMBAT_RANGE + margin) {
                return AIM_MAYBE_ON;
            }
        }
        return AIM_OFF;
    }

    /** A swing at air counts as human evidence only when it was aimed close to the target. */
    private boolean nearMiss(long current) {
        return targetId >= 0 && aim(current, 0, NEAR_MISS_MARGIN) == AIM_MAYBE_ON;
    }

    private int lagTicks() {
        long rtt = player.acks().smoothedRttMillis();
        int network = rtt <= 0 ? 0 : (int) ((rtt + 49) / 50);
        return Math.min(MAX_LAG_TICKS, network + INTERPOLATION_TICKS);
    }

    private double turnDegrees(long from, long to) {
        int a = (int) (from & MASK);
        int b = (int) (to & MASK);
        if (from < 1 || Double.isNaN(eyeX[a]) || Double.isNaN(eyeX[b])) {
            return 0.0;
        }
        double dot = dirX[a] * dirX[b] + dirY[a] * dirY[b] + dirZ[a] * dirZ[b];
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    int fastSamples() {
        return fastSamples;
    }

    int slowSamples() {
        return slowSamples;
    }

    int missSwings() {
        return missSwings;
    }
}
