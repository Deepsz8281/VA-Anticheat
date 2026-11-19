package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Runs for every position update before the simulation and position checks, e.g. to recognise
 * teleport confirmations ({@link MovementUpdate#markTeleport()}). Owner thread.
 */
@FunctionalInterface
public interface MovementHook {

    void beforeChecks(PlayerData player, MovementUpdate update);
}
