package dev.vaplus.anticheat.movement;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;

/** Pulls a player back to the last position that passed all movement checks. */
@FunctionalInterface
public interface SetbackHandler {

    SetbackHandler NONE = (player, cause) -> {
    };

    /** Called on the player's packet thread from the violation processor. */
    void request(PlayerData player, Check cause);

    /**
     * Packet-level setback (Phase 2): moves the client back to {@code target} by sending a
     * server-bound position sync and waits for its acknowledgement before movement is judged again
     * (see {@code pipeline.TeleportQueue}).
     *
     * <p>The default is a no-op so existing/platform implementations keep compiling; a handler that
     * supports packet-level setbacks overrides it. Called on the player's packet thread, like
     * {@link #request(PlayerData, Check)}; implementations must not touch thread-bound Bukkit API
     * from there (Folia-safe: transport/owner-thread scheduling only).
     *
     * @param player the player to move
     * @param target absolute destination; yaw/pitch are sent as-is
     * @param reason short diagnostic reason (e.g. the check id or {@code "setback"})
     */
    default void request(PlayerData player, SetbackTarget target, String reason) {
    }

    /**
     * Absolute destination of a packet-level setback. Coordinates must be finite; implementations
     * validate before sending.
     */
    record SetbackTarget(double x, double y, double z, float yaw, float pitch) {

        /** Whether every value can be encoded in a position-sync packet. */
        public boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Float.isFinite(yaw) && Float.isFinite(pitch);
        }
    }
}
