package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.simulation.SimulationResult;

/**
 * Velocity (anti-knockback): a server velocity the client has definitely applied does not show up in
 * the reported movement.
 *
 * <p>Signal: the engine offers every server velocity whose bracket markers are acknowledged
 * ({@code VelocityState} phase ACKED) and also tries candidates without it, so its best
 * {@link SimulationResult#offset()} stays small for a player who ignores knockback. It therefore also
 * reports {@link SimulationResult#ackedVelocityOffset()}: the offset of the best candidate that
 * <em>does</em> apply every acknowledged velocity. Ignored or reduced knockback (anti-KB, "velocity
 * 0%/50%") makes that offset large; legit movement keeps it near zero because the candidate set
 * contains the real input.
 *
 * <p>Why legit play does not trigger it:
 * <ul>
 *   <li>Only COMPLETE single-tick results are judged; walls, edges, liquids, unknown chunks, pending
 *       teleports etc. make the engine skip (pushed into a wall = SKIPPED, never "absorbed").</li>
 *   <li>Only acknowledged velocities count: the client processed the packet before answering the
 *       second marker, so it cannot be "not applied yet". A velocity that was offerable while the
 *       engine skipped a movement is tainted (it may have been applied unseen) and not judged.</li>
 *   <li>Tiny velocities (below {@code min-velocity}) are ignored: they are within the input noise.</li>
 *   <li>The engine's own uncertainty is added to the threshold.</li>
 * </ul>
 *
 * <p>Known bypasses: anti-KB that only triggers while the engine skips (standing at a block edge,
 * against a wall); delaying the marker answers (lag) until the knockback has decayed — the
 * {@code timer}/latency layers bound that; reducing knockback by less than {@code threshold}.
 * All numeric defaults are TUNING values, not vanilla constants.
 */
public final class VelocityCheck extends Check implements PositionCheck {

    /** TUNING: offset (blocks) of the velocity-honouring candidate tolerated on top of the uncertainty. */
    public static final double DEFAULT_THRESHOLD = 0.04;
    /** TUNING: velocities with neither horizontal speed nor |vy| above this are not judged. */
    public static final double DEFAULT_MIN_VELOCITY = 0.1;

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

    private double threshold = DEFAULT_THRESHOLD;
    private double minVelocity = DEFAULT_MIN_VELOCITY;

    public VelocityCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        threshold = MovementOptions.tolerance(options, "threshold", DEFAULT_THRESHOLD);
        minVelocity = MovementOptions.tolerance(options, "min-velocity", DEFAULT_MIN_VELOCITY);
    }

    @Override
    public void onPosition(MovementUpdate update) {
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

    public double threshold() {
        return threshold;
    }
}
