package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.PlayerDataRegistry;
import dev.vaplus.anticheat.profiling.CheckProfiler;

/**
 * Main PacketEvents listener (LOW priority). Fixed processing order for incoming packets:
 * <ol>
 *   <li>latency markers (our pongs are consumed and cancelled)</li>
 *   <li>packets already cancelled by earlier listeners are ignored (the server will not see them)</li>
 *   <li>{@link dev.vaplus.anticheat.check.PacketCheck}s on the raw packet (pre-update state)</li>
 *   <li>inbound trackers update player state</li>
 *   <li>movement / tick / interaction processing and the derived checks</li>
 * </ol>
 * Outgoing packets: creates player data on JOIN_GAME and runs
 * {@link dev.vaplus.anticheat.check.OutboundPacketCheck}s. State tracking of outgoing packets happens
 * in {@link OutboundStateListener} at MONITOR priority, after every plugin had the chance to modify
 * or cancel the packet. Everything runs on the connection's netty thread.
 */
public final class PacketPipeline extends PacketListenerAbstract {

    private final AnticheatContext context;
    private final PlayerDataRegistry players;
    private final MovementProcessor movement;
    private final InteractionProcessor interactions = new InteractionProcessor();
    private final InboundTracker[] inbound;

    public PacketPipeline(AnticheatContext context, PlayerDataRegistry players, ModuleSetup setup) {
        super(PacketListenerPriority.LOW);
        this.context = context;
        this.players = players;
        this.inbound = setup.inboundTrackers().toArray(new InboundTracker[0]);
        this.movement = new MovementProcessor(setup);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PlayerData player = players.get(event.getUser());
        if (player == null) {
            return;
        }
        CheckProfiler profiler = context.profiler();
        boolean profiling = profiler.enabled();
        long start = profiling ? System.nanoTime() : 0L;
        try {
            receive(player, event);
        } catch (RuntimeException | LinkageError error) {
            context.errors().report("packet pipeline", player.name(), error);
        }
        if (profiling) {
            profiler.recordPacket(System.nanoTime() - start);
        }
    }

    private void receive(PlayerData player, PacketReceiveEvent event) {
        player.claimOwnerThread();
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PONG
                && player.acks().onPong(new WrapperPlayClientPong(event).getId())) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) {
            return;
        }

        player.checks().dispatchReceive(event);
        if (event.isCancelled()) {
            return;
        }

        for (InboundTracker tracker : inbound) {
            tracker.onReceive(player, event);
        }

        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            movement.handle(player, event);
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            player.enableTickEndMode();
            player.onClientTick();
        } else {
            interactions.handle(player, event, type);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.PING) {
            return;
        }
        PlayerData player = type == PacketType.Play.Server.JOIN_GAME
                ? players.getOrCreate(event.getUser())
                : players.get(event.getUser());
        if (player == null) {
            return;
        }
        try {
            player.checks().dispatchSend(event);
        } catch (RuntimeException | LinkageError error) {
            context.errors().report("packet pipeline", player.name(), error);
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        players.remove(event.getUser());
    }

    /** Exposed for the replay harness. */
    public MovementProcessor movementProcessor() {
        return movement;
    }
}
