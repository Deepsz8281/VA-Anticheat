package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Matches inbound {@code TELEPORT_CONFIRM} packets against the player's {@link TeleportQueue}.
 *
 * <p>PacketEvents 2.14.0 models the confirmation as its own wrapper
 * ({@code WrapperPlayClientTeleportConfirm}); 26.3 additionally carries the confirmed position in
 * the same packet (the wrapper reads it), but no PacketEvents state machine exists, so matching is
 * ours. Only the teleport id is used here; the queue keeps its FIFO invariant against forged ids.
 *
 * <p>After an accepted confirmation the tracked position is invalidated: the next position packet
 * carries the teleport destination and must not be judged as movement from the pre-teleport
 * position. Owner thread.
 */
public final class TeleportConfirmTracker implements InboundTracker {

    private final ComponentKey<TeleportQueue> queueKey;

    public TeleportConfirmTracker(ComponentKey<TeleportQueue> queueKey) {
        this.queueKey = queueKey;
    }

    @Override
    public void onReceive(PlayerData player, PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TELEPORT_CONFIRM) {
            return;
        }
        onConfirm(player, new WrapperPlayClientTeleportConfirm(event).getTeleportId());
    }

    /** Primitive entry point so the matching logic is testable without a PacketEvents event. */
    void onConfirm(PlayerData player, int teleportId) {
        TeleportQueue queue = player.component(queueKey);
        TeleportQueue.Entry confirmed = queue.head();
        if (queue.confirm(teleportId) == TeleportQueue.ConfirmResult.CONFIRMED && confirmed != null) {
            // The next position packet is the teleport destination, not a movement delta.
            player.movement().invalidatePosition();
        }
    }
}
