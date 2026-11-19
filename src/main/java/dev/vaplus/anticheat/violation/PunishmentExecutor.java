package dev.vaplus.anticheat.violation;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Executes configured actions. Called on packet threads; implementations must hop to the correct
 * Folia scheduler (entity scheduler for the player, global region scheduler for console commands).
 */
public interface PunishmentExecutor {

    void execute(ViolationAction action, PlayerData player, Check check, double violationLevel);

    /**
     * Disconnects the player with the message stored under {@code messageKey} in messages.yml.
     * Callers rate-limit through {@link PlayerData#claimKick(long)}; implementations do not.
     */
    void kick(PlayerData player, String messageKey);
}
