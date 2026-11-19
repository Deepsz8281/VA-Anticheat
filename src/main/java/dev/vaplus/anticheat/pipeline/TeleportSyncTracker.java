package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Records every outbound {@code player_position_and_look} in the player's {@link TeleportQueue}
 * (handoff H5). Runs at MONITOR priority through {@link OutboundStateListener}, so cancelled
 * packets are not tracked. Both the server's own syncs and syncs sent by the packet-level setback
 * pass through here; a self-registered setback is recognised by its id and not duplicated.
 *
 * <p>PacketEvents 2.14.0 exposes the relative mask as {@code RelativeFlag}; the full mask includes
 * the 1.21.2+ delta/velocity bits ({@code DELTA_X/Y/Z}, {@code ROTATE_DELTA}), which earlier server
 * versions cannot carry (the wrapper reads a byte mask there). Owner thread.
 */
public final class TeleportSyncTracker implements OutboundTracker {

    private final ComponentKey<TeleportQueue> queueKey;

    public TeleportSyncTracker(ComponentKey<TeleportQueue> queueKey) {
        this.queueKey = queueKey;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            return;
        }
        WrapperPlayServerPlayerPositionAndLook packet = new WrapperPlayServerPlayerPositionAndLook(event);
        RelativeFlag flags = packet.getRelativeFlags();
        onPositionSync(player, packet.getTeleportId(),
                packet.getX(), packet.getY(), packet.getZ(), packet.getYaw(), packet.getPitch(),
                flags == null ? 0 : flags.getFullMask());
    }

    /** Primitive entry point so the tracking logic is testable without a PacketEvents event. */
    void onPositionSync(PlayerData player, int teleportId,
                        double x, double y, double z, float yaw, float pitch, int relativeFlags) {
        TeleportQueue queue = player.component(queueKey);
        queue.register(teleportId, new SetbackTarget(x, y, z, yaw, pitch), relativeFlags,
                player.movement().worldEpoch(), player.clientTicks(), TeleportQueue.SERVER_SYNC);
    }
}
