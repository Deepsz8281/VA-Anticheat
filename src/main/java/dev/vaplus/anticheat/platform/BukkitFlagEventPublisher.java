package dev.vaplus.anticheat.platform;

import dev.vaplus.anticheat.api.event.AsyncCheckFlagEvent;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.violation.FlagEventPublisher;
import org.bukkit.Bukkit;

/** Publishes {@link AsyncCheckFlagEvent}; skips event construction entirely when nobody listens. */
public final class BukkitFlagEventPublisher implements FlagEventPublisher {

    @Override
    public boolean publish(PlayerData player, Check check, double violationLevel, String details) {
        if (AsyncCheckFlagEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return true;
        }
        AsyncCheckFlagEvent event = new AsyncCheckFlagEvent(
                !Bukkit.isPrimaryThread(),
                player.uuid(),
                player.name(),
                player.bukkitPlayer(),
                check.descriptor().id(),
                check.descriptor().displayName(),
                check.descriptor().category(),
                violationLevel,
                details);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }
}
