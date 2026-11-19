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
 * Prediction: sustained position-domain deviation from the movement engine.
 *
 * <p>The other movement checks compare one packet's delta with the best candidate, so a hack that
 * adds a small constant amount every tick keeps a small per-packet offset forever. This check uses
 * {@link SimulationResult#cumulativeOffset()}: the engine chains its <em>own</em> predicted
 * positions, so the same hack accumulates linearly (0.005/tick becomes 0.15 blocks after 30 ticks).
 *
 * <p>Only packets the engine explained ({@code COMPLETE}) and chains of at least {@code min-ticks}
 * count; skipped packets, teleports and hard desyncs restart the chain and are never judged. The
 * threshold is a TUNING value, deliberately above float drift and below one block.
 */
public final class PredictionCheck extends Check implements PositionCheck {

    public static final double DEFAULT_CUMULATIVE_THRESHOLD = 0.15;
    public static final int DEFAULT_MIN_TICKS = 5;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("prediction", CheckCategory.MOVEMENT)
            .displayName("Prediction")
            .description("Accumulating position error: sustained movement the engine cannot explain")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private double threshold = DEFAULT_CUMULATIVE_THRESHOLD;
    private int minTicks = DEFAULT_MIN_TICKS;

    public PredictionCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        double configured = options.getDouble("cumulative-threshold", DEFAULT_CUMULATIVE_THRESHOLD);
        threshold = Double.isFinite(configured) && configured > 0.0 ? configured : DEFAULT_CUMULATIVE_THRESHOLD;
        int ticks = options.getInt("min-ticks", DEFAULT_MIN_TICKS);
        minTicks = Math.max(2, Math.min(200, ticks));
    }

    @Override
    public void onPosition(MovementUpdate update) {
        SimulationResult simulation = player.simulation();
        if (!simulation.complete() || simulation.cumulativeTicks() < minTicks) {
            lowerBuffer();
            return;
        }
        double offset = simulation.cumulativeOffset();
        if (debugging()) {
            debug("cumulative=" + VisibilityNumbers.number(offset)
                    + " ticks=" + simulation.cumulativeTicks()
                    + " limit=" + VisibilityNumbers.number(threshold)
                    + " buffer=" + VisibilityNumbers.number(buffer()));
        }
        if (offset > threshold) {
            if (raiseBuffer(1.0)) {
                violation("cumulative=" + VisibilityNumbers.number(offset)
                        + " ticks=" + simulation.cumulativeTicks()
                        + " limit=" + VisibilityNumbers.number(threshold)
                        + " buffer=" + VisibilityNumbers.number(buffer()));
                resetBuffer();
            }
        } else {
            lowerBuffer();
        }
    }

    /** Small local formatter (keeps the hot path free of MessageFormat). */
    private static final class VisibilityNumbers {
        private VisibilityNumbers() {
        }

        static String number(double value) {
            return String.format(java.util.Locale.ROOT, "%.3f", value);
        }
    }
}
