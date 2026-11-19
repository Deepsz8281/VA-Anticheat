package dev.vaplus.anticheat.simulation;

import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Movement prediction engine contract. Given the player's tracked state and the reported movement,
 * fills {@code out} with the best-matching prediction. Invoked on the player's packet thread before
 * any {@link dev.vaplus.anticheat.check.PositionCheck}. Implementations must not allocate per call.
 */
@FunctionalInterface
public interface MovementSimulator {

    void simulate(PlayerData player, MovementUpdate update, SimulationResult out);
}
