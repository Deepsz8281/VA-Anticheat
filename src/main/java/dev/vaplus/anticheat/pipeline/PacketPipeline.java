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
    /** Configuration-phase client data, kept until player data exists (created on JOIN_GAME). */
    private final java.util.Map<com.github.retrooper.packetevents.protocol.player.User, PreJoin> preJoin =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class PreJoin {
        volatile byte[] brand;
        volatile boolean chatHidden;
    }

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
            configuration(event);
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

    /** Remembers the brand and chat visibility a client sends before it joins the game. */
    private void configuration(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        try {
            if (type == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
                com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage packet =
                        new com.github.retrooper.packetevents.wrapper.configuration.client
                                .WrapperConfigClientPluginMessage(event);
                if (dev.vaplus.anticheat.check.impl.packet.ClientBrandCheck.CHANNEL.equals(packet.getChannelName())
                        && event.getUser() != null) {
                    preJoin.computeIfAbsent(event.getUser(), user -> new PreJoin()).brand = packet.getData();
                }
            } else if (type == PacketType.Configuration.Client.CLIENT_SETTINGS && event.getUser() != null) {
                preJoin.computeIfAbsent(event.getUser(), user -> new PreJoin()).chatHidden =
                        new com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientSettings(event)
                                .getChatVisibility() == com.github.retrooper.packetevents.wrapper.common.client
                                .WrapperCommonClientSettings.ChatVisibility.HIDDEN;
            }
        } catch (RuntimeException decodeError) {
            // the server's codec decides about malformed packets
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
            blockDuringSetback(player, event, type);
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            player.enableTickEndMode();
            player.onClientTick();
        } else {
            interactions.handle(player, event, type);
        }
    }

    /**
     * SetbackBlocker: while our own setback teleport waits for its confirmation, the client's
     * position packets are tracked but not handed to the server, so it never applies a move we are
     * pulling back. Rotation-only packets pass. The queue expires stale entries, so this cannot block
     * a player for good.
     */
    private void blockDuringSetback(PlayerData player, PacketReceiveEvent event, PacketTypeCommon type) {
        if (!context.settings().setbackBlockMovement()
                || (type != PacketType.Play.Client.PLAYER_POSITION
                && type != PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)) {
            return;
        }
        TeleportQueue queue = TeleportQueue.resolve(player);
        if (queue != null && queue.awaitingOwnSetback()) {
            event.setCancelled(true);
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
        if (type == PacketType.Play.Server.JOIN_GAME) {
            PreJoin data = preJoin.remove(event.getUser());
            if (data != null) {
                if (data.brand != null) {
                    player.clientBrandPayload(data.brand);
                }
                player.chatHidden(data.chatHidden);
            }
        }
        try {
            player.checks().dispatchSend(event);
        } catch (RuntimeException | LinkageError error) {
            context.errors().report("packet pipeline", player.name(), error);
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        preJoin.remove(event.getUser());
        players.remove(event.getUser());
    }

    /** Exposed for the replay harness. */
    public MovementProcessor movementProcessor() {
        return movement;
    }
}
