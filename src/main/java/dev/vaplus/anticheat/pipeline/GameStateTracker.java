package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.ExemptionState;

import java.util.List;

/**
 * Tracks game mode, abilities and respawns from outbound packets with two-marker latency
 * compensation: between the two acknowledgements the change is "in flight" and the matching
 * exemption is held; after the second acknowledgement the new state is committed.
 *
 * <p>Queue interplay (handoff H5, minimal): JOIN clears the player's {@link TeleportQueue}, and a
 * RESPAWN immediately drops pending syncs of the previous world epoch. Both are server-side events,
 * so they happen when the packet leaves, not when the client acknowledges it; the client can no
 * longer meaningfully confirm a sync from the old world.
 *
 * <p>Load holds (handoff L11): when the server protocol has the client's {@code PLAYER_LOADED}
 * packet, the JOIN and RESPAWN exemptions are additionally held until that packet arrives (this
 * class is also registered as an {@link InboundTracker} for it). The timed grants are unchanged, so
 * the exemption lasts until the later of "timer ran out" and "client finished loading"; the
 * {@code ExemptionState.MAX_HOLD_TICKS} hold expiry bounds the wait if the packet never comes.
 * Verified in the installed {@code packetevents-api 2.14.0} jar: {@code PacketType.Play.Client.PLAYER_LOADED}
 * and {@code WrapperPlayClientPlayerLoaded} exist, and the packet id is first mapped by
 * {@code ServerboundPacketType_1_21_4} (registered for {@code ClientVersion.V_1_21_4}); it is absent
 * from {@code ServerboundPacketType_1_21_2} and older. PacketEvents parses packets in the server's
 * protocol, so the feature is gated on the <em>server</em> version (1.21.4+); older servers keep the
 * previous behaviour. Not verified (UNCONFIRMED, no fetched source): whether vanilla clients send
 * {@code PLAYER_LOADED} again after every respawn, and whether ViaVersion synthesises it for older
 * clients on a 1.21.4+ server — in both cases the hold expiry is the fallback.
 */
public final class GameStateTracker implements OutboundTracker, InboundTracker {

    private static final int JOIN_TICKS = 40;
    private static final int RESPAWN_TICKS = 20;
    private static final int GAMEMODE_TICKS = 5;
    private static final int ABILITY_TICKS = 5;

    private final ComponentKey<TeleportQueue> teleportKey;
    private final boolean awaitPlayerLoaded;

    /** Without a teleport queue (kept for compatibility); no queue maintenance, no load holds. */
    public GameStateTracker() {
        this(null);
    }

    /** Without load holds (legacy behaviour). */
    public GameStateTracker(ComponentKey<TeleportQueue> teleportKey) {
        this(teleportKey, false);
    }

    /**
     * @param awaitPlayerLoaded whether JOIN/RESPAWN exemptions are held until {@code PLAYER_LOADED}
     *                          (only for servers whose protocol has the packet, see
     *                          {@link #serverHasPlayerLoaded()})
     */
    public GameStateTracker(ComponentKey<TeleportQueue> teleportKey, boolean awaitPlayerLoaded) {
        this.teleportKey = teleportKey;
        this.awaitPlayerLoaded = awaitPlayerLoaded;
    }

    /**
     * Whether the running server's protocol carries {@code PLAYER_LOADED} (1.21.4+ per the
     * PacketEvents 2.14.0 mappings). {@code false} when PacketEvents is not initialised (unit tests).
     */
    public static boolean serverHasPlayerLoaded() {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || api.getServerManager() == null) {
            return false;
        }
        ServerVersion version = api.getServerManager().getVersion();
        return version != null && version.isNewerThanOrEquals(ServerVersion.V_1_21_4);
    }

    public boolean awaitsPlayerLoaded() {
        return awaitPlayerLoaded;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.JOIN_GAME) {
            onJoin(player, new WrapperPlayServerJoinGame(event).getGameMode());
        } else if (type == PacketType.Play.Server.RESPAWN) {
            WrapperPlayServerRespawn packet = new WrapperPlayServerRespawn(event);
            onRespawn(player, event.getTasksAfterSend(), packet.getGameMode(), packet.getKeptData());
        } else if (type == PacketType.Play.Server.CHANGE_GAME_STATE) {
            onGameState(player, event, new WrapperPlayServerChangeGameState(event));
        } else if (type == PacketType.Play.Server.PLAYER_ABILITIES) {
            onAbilities(player, event, new WrapperPlayServerPlayerAbilities(event));
        }
    }

    @Override
    public void onReceive(PlayerData player, PacketReceiveEvent event) {
        if (awaitPlayerLoaded && event.getPacketType() == PacketType.Play.Client.PLAYER_LOADED) {
            onPlayerLoaded(player);
        }
    }

    /** Primitive entry point: the client reported that it finished loading the world. */
    void onPlayerLoaded(PlayerData player) {
        if (awaitPlayerLoaded) {
            player.exemptions().releaseLoadHolds();
        }
    }

    /** Primitive entry point for JOIN_GAME (testable without a PacketEvents event). */
    void onJoin(PlayerData player, GameMode gameMode) {
        // First play packet: there is no previous state to compensate for.
        player.abilities().applyGameMode(gameMode);
        player.movement().advanceWorldEpoch();
        player.movement().invalidatePosition();
        if (teleportKey != null) {
            // A new connection starts without pending position syncs.
            player.component(teleportKey).clear();
        }
        player.exemptions().grant(ExemptionCause.JOIN, JOIN_TICKS);
        if (awaitPlayerLoaded) {
            // The client is still loading the world; keep JOIN active until it says it is done.
            player.exemptions().holdUntilLoaded(ExemptionCause.JOIN);
        }
    }

    /** Primitive entry point for RESPAWN (testable without a PacketEvents event). */
    void onRespawn(PlayerData player, List<Runnable> tasksAfterSend, GameMode mode, byte kept) {
        ExemptionState exemptions = player.exemptions();
        // Setbacks captured for the old world become invalid as soon as the packet leaves.
        player.movement().advanceWorldEpoch();
        if (teleportKey != null) {
            // Syncs recorded before the world change can never be confirmed meaningfully anymore;
            // syncs sent after the new epoch (the respawn position) are kept.
            player.component(teleportKey).discardOtherEpochs(player.movement().worldEpoch());
        }
        player.acks().bracket(tasksAfterSend,
                () -> {
                    // The client may switch worlds from now on; the old position is meaningless.
                    player.movement().invalidatePosition();
                    exemptions.hold(ExemptionCause.RESPAWN);
                    if (awaitPlayerLoaded) {
                        // From here on the client may be loading the new world. A PLAYER_LOADED
                        // received after this point releases it (UNCONFIRMED that clients resend it
                        // per respawn; the hold expiry is the fallback).
                        exemptions.holdUntilLoaded(ExemptionCause.RESPAWN);
                    }
                },
                () -> {
                    // The client recreated its player entity: effects are gone (the server resends
                    // them), attributes and entity data survive only if the keep flags say so.
                    player.abilities().applyGameMode(mode);
                    player.effects().clear();
                    if ((kept & WrapperPlayServerRespawn.KEEP_ATTRIBUTES) == 0) {
                        player.attributes().reset();
                    }
                    if ((kept & WrapperPlayServerRespawn.KEEP_ENTITY_DATA) == 0) {
                        player.pose().resetFlags();
                    }
                    player.vehicle().dismount();
                    player.checks().resetAll();
                    exemptions.release(ExemptionCause.RESPAWN);
                    exemptions.grant(ExemptionCause.RESPAWN, RESPAWN_TICKS);
                });
    }

    private void onGameState(PlayerData player, PacketSendEvent event, WrapperPlayServerChangeGameState packet) {
        if (packet.getReason() != WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE) {
            return;
        }
        GameMode mode = gameModeById((int) packet.getValue());
        if (mode == null) {
            return;
        }
        ExemptionState exemptions = player.exemptions();
        player.acks().bracket(event.getTasksAfterSend(),
                () -> exemptions.hold(ExemptionCause.GAMEMODE_CHANGE),
                () -> {
                    player.abilities().applyGameMode(mode);
                    exemptions.release(ExemptionCause.GAMEMODE_CHANGE);
                    exemptions.grant(ExemptionCause.GAMEMODE_CHANGE, GAMEMODE_TICKS);
                });
    }

    private void onAbilities(PlayerData player, PacketSendEvent event, WrapperPlayServerPlayerAbilities packet) {
        boolean flying = packet.isFlying();
        boolean flightAllowed = packet.isFlightAllowed();
        boolean creative = packet.isInCreativeMode();
        boolean invulnerable = packet.isInGodMode();
        float flySpeed = packet.getFlySpeed();
        float walkSpeed = packet.getFOVModifier();
        ExemptionState exemptions = player.exemptions();
        player.acks().bracket(event.getTasksAfterSend(),
                () -> exemptions.hold(ExemptionCause.ABILITY_CHANGE),
                () -> {
                    player.abilities().applyAbilities(flying, flightAllowed, creative, invulnerable, flySpeed, walkSpeed);
                    exemptions.release(ExemptionCause.ABILITY_CHANGE);
                    exemptions.grant(ExemptionCause.ABILITY_CHANGE, ABILITY_TICKS);
                });
    }

    private static GameMode gameModeById(int id) {
        for (GameMode mode : GameMode.values()) {
            if (mode.getId() == id) {
                return mode;
            }
        }
        return null;
    }
}
