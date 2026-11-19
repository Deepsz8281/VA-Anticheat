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
 * NoFall: the client's on-ground claim contradicts the simulation's predicted ground state.
 *
 * <p>The movement engine derives ground contact from the compensated world, never from the client
 * flag ({@link SimulationResult#predictedOnGround()}). This check compares the two:
 * <ul>
 *   <li>Claimed on ground while the best candidate ends airborne (spoofed ground) — always judged.</li>
 *   <li>Claimed airborne while the best candidate ends on the ground — only with
 *       {@code options.flag-missing-ground} (default off; TUNING decision, the practical benefit of
 *       such a spoof is not verified).</li>
 * </ul>
 * The predicted ground state is only trusted when the prediction itself matches: results whose
 * total offset exceeds {@code options.max-offset} + uncertainty are left alone (neither raise nor
 * lower the buffer) — Speed/Fly judge those movements. Judges only
 * {@link SimulationResult.Status#COMPLETE COMPLETE} results and never computes physics itself.
 *
 * <p>All numeric defaults here are TUNING values (engineering estimates), not vanilla constants.
 */
public final class NoFallCheck extends Check implements PositionCheck {

    /** TUNING: largest total offset at which the predicted ground state is still trusted. */
    public static final double DEFAULT_MAX_OFFSET = 0.1;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("nofall", CheckCategory.MOVEMENT)
            .displayName("NoFall")
            .description("On-ground claims that contradict the simulated ground state")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE)
            .build();

    private double maxOffset = DEFAULT_MAX_OFFSET;
    private boolean flagMissingGround;

    public NoFallCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxOffset = MovementOptions.tolerance(options, "max-offset", DEFAULT_MAX_OFFSET);
        flagMissingGround = options.getBoolean("flag-missing-ground", false);
    }

    @Override
    public void onPosition(MovementUpdate update) {
        SimulationResult result = update.simulation();
        if (!result.complete() || isExempt()) {
            return;
        }
        if (result.offset() > maxOffset + result.uncertainty()) {
            return;
        }
        boolean claimed = update.toGround();
        boolean predicted = result.predictedOnGround();
        String kind;
        if (claimed && !predicted) {
            kind = "spoofed-ground";
        } else if (!claimed && predicted && flagMissingGround) {
            kind = "missing-ground";
        } else {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug(kind + " offset=" + MovementOptions.number(result.offset()) + " buffer="
                    + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(kind
                    + " claimed=" + claimed
                    + " predicted=" + predicted
                    + " dy=" + MovementOptions.number(update.deltaY())
                    + " offset=" + MovementOptions.number(result.offset())
                    + " ticks=" + result.ticksSimulated()
                    + " uncertainty=" + MovementOptions.number(result.uncertainty()));
        }
    }
}
