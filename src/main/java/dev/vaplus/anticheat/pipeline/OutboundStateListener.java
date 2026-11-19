package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.PlayerDataRegistry;

/**
 * Runs the {@link OutboundTracker}s at MONITOR priority: only packets that really leave the server
 * (not cancelled, final content) may change tracked state. PacketEvents still runs after-send tasks
 * of cancelled packets, so tracking a packet that is later cancelled would commit state the client
 * never received.
 */
public final class OutboundStateListener extends PacketListenerAbstract {

    private final AnticheatContext context;
    private final PlayerDataRegistry players;
    private final OutboundTracker[] outbound;

    public OutboundStateListener(AnticheatContext context, PlayerDataRegistry players, ModuleSetup setup) {
        super(PacketListenerPriority.MONITOR);
        this.context = context;
        this.players = players;
        this.outbound = setup.outboundTrackers().toArray(new OutboundTracker[0]);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || event.getPacketType() == PacketType.Play.Server.PING) {
            return;
        }
        PlayerData player = players.get(event.getUser());
        if (player == null) {
            return;
        }
        try {
            for (OutboundTracker tracker : outbound) {
                tracker.onSend(player, event);
            }
        } catch (RuntimeException | LinkageError error) {
            context.errors().report("outbound tracking", player.name(), error);
        }
    }
}
