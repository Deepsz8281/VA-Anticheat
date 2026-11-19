package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Updates player state from client packets. Runs after packet checks and before movement and
 * interaction processing, also for bypassing players. Owner thread.
 */
@FunctionalInterface
public interface InboundTracker {

    void onReceive(PlayerData player, PacketReceiveEvent event);
}
