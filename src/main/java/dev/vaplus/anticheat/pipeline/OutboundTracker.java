package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Updates player state from server packets, normally through
 * {@link dev.vaplus.anticheat.latency.AckTracker#bracket} so the change is applied only once the
 * client acknowledged it. Runs before packet checks see the outbound packet. Owner thread.
 */
@FunctionalInterface
public interface OutboundTracker {

    void onSend(PlayerData player, PacketSendEvent event);
}
