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
 * Speed: horizontal movement that no simulated input candidate explains.
 *
 * <p>Judges only {@link SimulationResult.Status#COMPLETE COMPLETE} results and uses nothing but the
 * {@link SimulationResult} (plus the reported delta of the {@link MovementUpdate}); it never computes
 * physics itself. A packet counts as suspicious when the best candidate's horizontal offset exceeds
 * {@code options.threshold} plus the engine's {@link SimulationResult#uncertainty() uncertainty}.
 * With {@code options.ignore-slowdown} (default on) a packet that is clearly slower than the
 * prediction is not counted: being slower than any legal input gives no speed advantage and is
 * typically an unmodelled obstacle.
 *
 * <p>All numeric defaults here are TUNING values (engineering estimates), not vanilla constants.
 */
public final class SpeedCheck extends Check implements PositionCheck {

    /** TUNING: horizontal offset (blocks per packet) tolerated on top of the engine uncertainty. */
    public static final double DEFAULT_THRESHOLD = 0.03;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("speed", CheckCategory.MOVEMENT)
            .displayName("Speed")
            .description("Horizontal movement the movement simulation cannot explain")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(10.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE)
            .build();

    private double threshold = DEFAULT_THRESHOLD;
    private boolean ignoreSlowdown = true;

    public SpeedCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        threshold = MovementOptions.tolerance(options, "threshold", DEFAULT_THRESHOLD);
        ignoreSlowdown = options.getBoolean("ignore-slowdown", true);
    }

    @Override
    public void onPosition(MovementUpdate update) {
        SimulationResult result = update.simulation();
        if (!result.complete() || isExempt()) {
            return;
        }
        double offset = result.horizontalOffset();
        double limit = threshold + result.uncertainty();
        if (offset <= limit) {
            lowerBuffer();
            return;
        }
        double reported = update.horizontalDistance();
        double predicted = Math.sqrt(result.predictedX() * result.predictedX() + result.predictedZ() * result.predictedZ());
        if (ignoreSlowdown && reported + threshold < predicted) {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug("offset=" + MovementOptions.number(offset) + " limit=" + MovementOptions.number(limit)
                    + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("offset=" + MovementOptions.number(offset)
                    + " limit=" + MovementOptions.number(limit)
                    + " moved=" + MovementOptions.number(reported)
                    + " predicted=" + MovementOptions.number(predicted)
                    + " ticks=" + result.ticksSimulated()
                    + " uncertainty=" + MovementOptions.number(result.uncertainty()));
        }
    }

    /** Effective threshold after the last settings application (tests, diagnostics). */
    public double threshold() {
        return threshold;
    }
}
