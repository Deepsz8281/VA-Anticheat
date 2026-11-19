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
 * Fly: vertical movement that no simulated input candidate explains, judged on the signed
 * {@link SimulationResult#verticalOffset() vertical offset} of the best candidate.
 *
 * <ul>
 *   <li>Upward: the player ended higher than any legal input allows (hover, glide, ascend, high
 *       jump) — offset above {@code options.upward-threshold} + uncertainty.</li>
 *   <li>Downward (optional, {@code options.check-downward}, default on): the player ended lower than
 *       predicted by more than {@code options.downward-threshold} + uncertainty (fast-fall).</li>
 * </ul>
 * Judges only {@link SimulationResult.Status#COMPLETE COMPLETE} results; the engine already reports
 * SKIPPED for flight, creative/spectator, elytra, swimming, levitation, liquids, vehicles and every
 * environment it does not model, so those never reach the comparison. The check never computes
 * physics itself. The number of consecutive packets predicted airborne is added to the details.
 *
 * <p>All numeric defaults here are TUNING values (engineering estimates), not vanilla constants.
 */
public final class FlyCheck extends Check implements PositionCheck {

    /** TUNING: upward vertical offset (blocks per packet) tolerated on top of the engine uncertainty. */
    public static final double DEFAULT_UPWARD_THRESHOLD = 0.03;
    /** TUNING: downward vertical offset tolerated on top of the engine uncertainty. */
    public static final double DEFAULT_DOWNWARD_THRESHOLD = 0.1;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("fly", CheckCategory.MOVEMENT)
            .displayName("Fly")
            .description("Vertical movement the movement simulation cannot explain")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(10.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE)
            .build();

    private double upwardThreshold = DEFAULT_UPWARD_THRESHOLD;
    private double downwardThreshold = DEFAULT_DOWNWARD_THRESHOLD;
    private boolean checkDownward = true;
    private int airPackets;

    public FlyCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        upwardThreshold = MovementOptions.tolerance(options, "upward-threshold", DEFAULT_UPWARD_THRESHOLD);
        downwardThreshold = MovementOptions.tolerance(options, "downward-threshold", DEFAULT_DOWNWARD_THRESHOLD);
        checkDownward = options.getBoolean("check-downward", true);
    }

    @Override
    protected void onReset() {
        airPackets = 0;
    }

    @Override
    public void onPosition(MovementUpdate update) {
        SimulationResult result = update.simulation();
        if (!result.complete()) {
            airPackets = 0;
            return;
        }
        airPackets = result.predictedOnGround() ? 0 : airPackets + 1;
        if (isExempt()) {
            return;
        }
        double offset = result.verticalOffset();
        double uncertainty = result.uncertainty();
        String direction;
        double limit;
        if (offset > upwardThreshold + uncertainty) {
            direction = "up";
            limit = upwardThreshold + uncertainty;
        } else if (checkDownward && offset < -(downwardThreshold + uncertainty)) {
            direction = "down";
            limit = downwardThreshold + uncertainty;
        } else {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug(direction + " offset=" + MovementOptions.number(offset) + " limit=" + MovementOptions.number(limit)
                    + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(direction
                    + " offset=" + MovementOptions.number(offset)
                    + " limit=" + MovementOptions.number(limit)
                    + " dy=" + MovementOptions.number(update.deltaY())
                    + " predicted=" + MovementOptions.number(result.predictedY())
                    + " air=" + airPackets
                    + " ticks=" + result.ticksSimulated()
                    + " uncertainty=" + MovementOptions.number(uncertainty));
        }
    }

    /** Consecutive COMPLETE packets whose best candidate ended airborne. */
    public int airPackets() {
        return airPackets;
    }
}
