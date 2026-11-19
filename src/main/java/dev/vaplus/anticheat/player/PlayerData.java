package dev.vaplus.anticheat.player;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.check.CheckBundle;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.latency.MarkerTracker;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.pipeline.ClientTickListener;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.state.AbilityState;
import dev.vaplus.anticheat.player.state.AttributeState;
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.ExemptionState;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.VehicleState;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.world.BlockInteraction;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything the anticheat knows about one connected player.
 *
 * <p>Threading contract: state objects, check instances and the ack tracker are confined to the
 * player's netty event-loop thread (the "owner thread"); packet events for one connection always
 * arrive on that thread. Fields that commands or schedulers read from other threads are volatile.
 * Bukkit API objects are never touched from the owner thread except the immutable identity data.
 */
public final class PlayerData {

    private final AnticheatContext context;
    private final UUID uuid;
    private final String name;
    private final ClientVersion clientVersion;
    private final PacketTransport transport;
    private final long createdAtMillis;

    private final MovementState movement = new MovementState();
    private final AbilityState abilities = new AbilityState();
    private final EffectState effects = new EffectState();
    private final AttributeState attributes = new AttributeState();
    private final PoseState pose = new PoseState();
    private final VehicleState vehicle = new VehicleState();
    private final ExemptionState exemptions = new ExemptionState();
    private final MarkerTracker acks;

    private final SimulationResult simulation = new SimulationResult();
    private final MovementUpdate movementUpdate = new MovementUpdate(simulation);
    private final LookChange lookChange = new LookChange();
    private final BlockInteraction interaction = new BlockInteraction();
    private final dev.vaplus.anticheat.check.impl.combat.EntityAttack entityAttack =
            new dev.vaplus.anticheat.check.impl.combat.EntityAttack();
    private final Runnable heartbeatTask = this::heartbeat;

    private static final long KICK_RETRY_NANOS = 5_000_000_000L;

    private Object[] components = new Object[0];
    private CheckBundle checks;
    private volatile Player bukkitPlayer;
    private volatile boolean bypass;
    private volatile boolean healthIndicatorBypass;
    private volatile long clientTicks;
    private volatile Thread ownerThread;
    private volatile boolean disconnecting;
    private volatile boolean kickRequested;
    private volatile long kickRequestedNanos;
    private volatile byte[] clientBrandPayload;
    private volatile boolean chatHidden;
    private boolean tickEndMode;
    private long lastSetbackNanos;
    private boolean setbackIssued;

    private PlayerData(AnticheatContext context, UUID uuid, String name, ClientVersion clientVersion, PacketTransport transport) {
        this.context = Objects.requireNonNull(context, "context");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.name = Objects.requireNonNull(name, "name");
        this.clientVersion = clientVersion != null ? clientVersion : ClientVersion.UNKNOWN;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.createdAtMillis = context.time().currentTimeMillis();
        this.acks = new MarkerTracker(transport, context.time(), context.logger());
    }

    /** Creates the data container and instantiates every registered check for it. */
    public static PlayerData create(AnticheatContext context, UUID uuid, String name, ClientVersion clientVersion,
                                    PacketTransport transport) {
        PlayerData data = new PlayerData(context, uuid, name, clientVersion, transport);
        // Components first: check constructors may look them up.
        List<ComponentKey<?>> keys = context.setup().components();
        Object[] created = new Object[keys.size()];
        data.components = created;
        for (ComponentKey<?> key : keys) {
            created[key.index()] = key.create(data);
        }
        data.checks = CheckBundle.create(data, context.checks(), context.profiler(), context.errors());
        return data;
    }

    public AnticheatContext context() {
        return context;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public ClientVersion clientVersion() {
        return clientVersion;
    }

    public PacketTransport transport() {
        return transport;
    }

    /**
     * Whether client ticks are counted from the explicit end-of-tick packet (1.21.2+ protocol).
     * Starts {@code false} and switches on with the first tick-end packet actually received, so
     * protocol translation layers (ViaVersion/ViaBackwards) that add or drop the packet are handled
     * without trusting the reported client version. Owner thread only.
     */
    public boolean tickEndMode() {
        return tickEndMode;
    }

    /** Pipeline: a tick-end packet arrived. */
    public void enableTickEndMode() {
        this.tickEndMode = true;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public MovementState movement() {
        return movement;
    }

    public AbilityState abilities() {
        return abilities;
    }

    public EffectState effects() {
        return effects;
    }

    public AttributeState attributes() {
        return attributes;
    }

    public PoseState pose() {
        return pose;
    }

    public VehicleState vehicle() {
        return vehicle;
    }

    public ExemptionState exemptions() {
        return exemptions;
    }

    public MarkerTracker acks() {
        return acks;
    }

    public CheckBundle checks() {
        return checks;
    }

    /** Per-player component registered by a module. */
    @SuppressWarnings("unchecked")
    public <T> T component(ComponentKey<T> key) {
        return (T) components[key.index()];
    }

    public SimulationResult simulation() {
        return simulation;
    }

    public MovementUpdate movementUpdate() {
        return movementUpdate;
    }

    public LookChange lookChange() {
        return lookChange;
    }

    public BlockInteraction interaction() {
        return interaction;
    }

    /** Reused entity-attack event for the combat checks. */
    public dev.vaplus.anticheat.check.impl.combat.EntityAttack entityAttack() {
        return entityAttack;
    }

    /** Bukkit player, or {@code null} before the join event. Only use it through schedulers. */
    public Player bukkitPlayer() {
        return bukkitPlayer;
    }

    public void attach(Player player) {
        this.bukkitPlayer = player;
    }

    /** Raw {@code minecraft:brand} payload the client sent, or {@code null}. */
    public byte[] clientBrandPayload() {
        return clientBrandPayload;
    }

    public void clientBrandPayload(byte[] payload) {
        this.clientBrandPayload = payload;
    }

    /** Decoded client brand for display, {@code "?"} while unknown. */
    public String clientBrand() {
        byte[] payload = clientBrandPayload;
        String brand = dev.vaplus.anticheat.check.impl.packet.ClientBrandCheck.decode(payload);
        return brand != null ? brand.replaceAll("\\p{Cntrl}", "") : (payload == null ? "?" : "invalid");
    }

    /** Whether the client's chat visibility is "hidden". */
    public boolean chatHidden() {
        return chatHidden;
    }

    public void chatHidden(boolean hidden) {
        this.chatHidden = hidden;
    }

    /** Whether the player holds the bypass permission (refreshed periodically on the entity thread). */
    public boolean bypassing() {
        return bypass;
    }

    public void updateBypass(boolean bypass) {
        this.bypass = bypass;
    }

    /** Whether the player receives real entity data (anti-health-indicator bypass permission). */
    public boolean healthIndicatorBypass() {
        return healthIndicatorBypass;
    }

    public void updateHealthIndicatorBypass(boolean bypass) {
        this.healthIndicatorBypass = bypass;
    }

    public long clientTicks() {
        return clientTicks;
    }

    /**
     * Category-wide exemptions based on the acknowledged game mode. Spectators are exempt from
     * everything except protocol checks; creative players additionally from movement, world and
     * inventory checks (they legitimately fly, insta-break and spawn items).
     */
    public boolean isExempt(CheckCategory category) {
        GameMode mode = abilities.gameMode();
        return switch (category) {
            case MOVEMENT, WORLD, INVENTORY -> mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
            case COMBAT -> mode == GameMode.SPECTATOR;
            case PACKET -> false;
        };
    }

    /** Pipeline: advances per-tick state and runs tick checks. Owner thread only. */
    public void onClientTick() {
        clientTicks = clientTicks + 1;
        movement.tick();
        vehicle.tick();
        exemptions.tick();
        ClientTickListener[] listeners = context.tickListeners();
        for (ClientTickListener listener : listeners) {
            listener.onClientTick(this);
        }
        checks.dispatchClientTick();
        context.profiler().countClientTick();
    }

    /** Records the netty thread on the first packet; used for owner-thread assertions. */
    public void claimOwnerThread() {
        if (ownerThread == null) {
            ownerThread = Thread.currentThread();
        }
    }

    public boolean isOwnerThread() {
        return ownerThread == Thread.currentThread();
    }

    /** Schedules a heartbeat marker on the owner thread; called by the async heartbeat task. */
    public void scheduleHeartbeat() {
        if (!disconnecting) {
            transport.runOnOwnerThread(heartbeatTask);
        }
    }

    /**
     * Setback rate limiter. Owner thread only.
     *
     * @return {@code true} if a setback may be issued now
     */
    public boolean claimSetback(long nowNanos, long cooldownNanos) {
        if (setbackIssued && nowNanos - lastSetbackNanos < cooldownNanos) {
            return false;
        }
        setbackIssued = true;
        lastSetbackNanos = nowNanos;
        return true;
    }

    /** True once the connection is closing (quit, disconnect or transport closed). */
    public boolean disconnecting() {
        return disconnecting;
    }

    /** Marks the connection as closing so no further heartbeats or punishments are issued. */
    public void markDisconnecting() {
        this.disconnecting = true;
    }

    /**
     * Kick rate limiter. A requested kick can be cancelled by other plugins (PlayerKickEvent) or
     * dropped by a retired scheduler, so tracking continues and another kick may be requested after
     * a few seconds instead of the player becoming permanently unchecked.
     *
     * @return {@code true} if a kick may be issued now
     */
    public boolean claimKick(long nowNanos) {
        if (disconnecting || (kickRequested && nowNanos - kickRequestedNanos < KICK_RETRY_NANOS)) {
            return false;
        }
        kickRequested = true;
        kickRequestedNanos = nowNanos;
        return true;
    }

    private void heartbeat() {
        if (disconnecting) {
            return;
        }
        acks.sendMarker();
        long timeoutMillis = context.settings().ackTimeoutMillis();
        if (timeoutMillis > 0 && acks.oldestPendingAgeNanos() > timeoutMillis * 1_000_000L
                && claimKick(context.time().nanoTime())) {
            context.punishments().kick(this, "timeout-kick");
        }
    }

    @Override
    public String toString() {
        return "PlayerData[" + name + ", " + clientVersion.getReleaseName() + "]";
    }
}
