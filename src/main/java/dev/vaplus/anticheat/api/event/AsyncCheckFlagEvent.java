package dev.vaplus.anticheat.api.event;

import dev.vaplus.anticheat.check.CheckCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Fired before a violation is applied. Normally asynchronous (called from the player's netty
 * thread): listeners must not use thread-bound Bukkit API and must return quickly. If a violation
 * is ever raised from a server tick thread the event is fired synchronously there instead
 * ({@link #isAsynchronous()} tells which). Cancelling suppresses the violation entirely (no level
 * increase, alert, log entry or punishment).
 */
public final class AsyncCheckFlagEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final Player player;
    private final String checkId;
    private final String checkName;
    private final CheckCategory category;
    private final double violationLevel;
    private final String details;
    private boolean cancelled;

    public AsyncCheckFlagEvent(boolean async, UUID playerId, String playerName, @Nullable Player player,
                               String checkId, String checkName, CheckCategory category,
                               double violationLevel, String details) {
        super(async);
        this.playerId = playerId;
        this.playerName = playerName;
        this.player = player;
        this.checkId = checkId;
        this.checkName = checkName;
        this.category = category;
        this.violationLevel = violationLevel;
        this.details = details;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    /** The Bukkit player, or {@code null} if the violation happened before the join event. */
    public @Nullable Player getPlayer() {
        return player;
    }

    public String getCheckId() {
        return checkId;
    }

    public String getCheckName() {
        return checkName;
    }

    public CheckCategory getCategory() {
        return category;
    }

    /** The violation level the player will have if the event is not cancelled. */
    public double getViolationLevel() {
        return violationLevel;
    }

    public String getDetails() {
        return details;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
