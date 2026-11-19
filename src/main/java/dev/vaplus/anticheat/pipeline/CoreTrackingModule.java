package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.latency.ServerTickMarkers;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleContext;

/**
 * Built-in trackers every other module relies on: game state (outbound, plus the inbound
 * {@code PLAYER_LOADED} signal), outbound position syncs / teleport confirmations (handoff H5),
 * client input (inbound) and the per-server-tick marker component (handoff M15).
 */
public final class CoreTrackingModule implements AnticheatModule {

    private final boolean sneakFromInputPacket;
    private final boolean awaitPlayerLoaded;
    private ComponentKey<TeleportQueue> teleportKey;
    private ComponentKey<ServerTickMarkers> serverTickKey;

    /**
     * Detects the {@code PLAYER_LOADED} support from the running server version
     * ({@link GameStateTracker#serverHasPlayerLoaded()}; {@code false} without PacketEvents).
     *
     * @param sneakFromInputPacket whether the server protocol (1.21.6+) conveys sneaking only through
     *                             the shift key of the player-input packet
     */
    public CoreTrackingModule(boolean sneakFromInputPacket) {
        this(sneakFromInputPacket, GameStateTracker.serverHasPlayerLoaded());
    }

    /**
     * @param sneakFromInputPacket whether the server protocol (1.21.6+) conveys sneaking only through
     *                             the shift key of the player-input packet
     * @param awaitPlayerLoaded    whether JOIN/RESPAWN exemptions are held until {@code PLAYER_LOADED}
     *                             (server protocol 1.21.4+)
     */
    public CoreTrackingModule(boolean sneakFromInputPacket, boolean awaitPlayerLoaded) {
        this.sneakFromInputPacket = sneakFromInputPacket;
        this.awaitPlayerLoaded = awaitPlayerLoaded;
    }

    @Override
    public String name() {
        return "core-tracking";
    }

    @Override
    public void register(ModuleContext context) {
        // One queue per player, created before checks like every component.
        teleportKey = context.registerComponent("teleport-queue", TeleportQueue.class,
                player -> new TeleportQueue());
        serverTickKey = context.registerComponent("server-tick-markers", ServerTickMarkers.class,
                ServerTickMarkers::create);
        GameStateTracker gameState = new GameStateTracker(teleportKey, awaitPlayerLoaded);
        context.addOutboundTracker(gameState);
        context.addOutboundTracker(new TeleportSyncTracker(teleportKey));
        context.addInboundTracker(new TeleportConfirmTracker(teleportKey));
        context.addInboundTracker(new InputTracker(sneakFromInputPacket));
        // Same instance: only reacts to PLAYER_LOADED (appended so existing tracker order is kept).
        context.addInboundTracker(gameState);
    }

    /** Key of the per-player {@link TeleportQueue} component; usable after registration. */
    public ComponentKey<TeleportQueue> teleportQueueKey() {
        if (teleportKey == null) {
            throw new IllegalStateException("CoreTrackingModule is not registered yet");
        }
        return teleportKey;
    }

    /** Key of the per-player {@link ServerTickMarkers} component; usable after registration. */
    public ComponentKey<ServerTickMarkers> serverTickMarkersKey() {
        if (serverTickKey == null) {
            throw new IllegalStateException("CoreTrackingModule is not registered yet");
        }
        return serverTickKey;
    }
}
