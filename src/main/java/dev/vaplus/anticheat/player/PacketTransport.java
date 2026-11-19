package dev.vaplus.anticheat.player;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;

/**
 * Outbound side of a player's connection. Decouples the core from PacketEvents' {@code User} so
 * the latency tracker and checks can run against a fake transport in tests.
 */
public interface PacketTransport {

    /**
     * Sends a latency marker (ping) with the given id.
     *
     * @return {@code false} if the connection cannot carry play packets right now (e.g. the client
     *         was sent back to the configuration phase); nothing was sent in that case
     */
    boolean sendMarker(int markerId);

    void send(PacketWrapper<?> packet);

    /** Runs {@code task} on the connection's event loop (the player's owner thread). */
    void runOnOwnerThread(Runnable task);

    /** Closes the connection without a disconnect message (used before a Bukkit player exists). */
    void close();
}
