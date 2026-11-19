package dev.vaplus.anticheat.violation;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;

/** Lets third-party plugins observe and cancel violations before they are applied. */
@FunctionalInterface
public interface FlagEventPublisher {

    FlagEventPublisher NONE = (player, check, violationLevel, details) -> true;

    /** @return {@code false} if the violation was cancelled */
    boolean publish(PlayerData player, Check check, double violationLevel, String details);
}
