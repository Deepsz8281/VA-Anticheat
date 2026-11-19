package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.VelocityState;

import java.util.List;

/**
 * Tracks server velocities of the player's own entity into {@link VelocityState}, bracketed by two
 * markers like the other outbound trackers ({@link GameStateTracker}): first ack = the velocity may
 * be applied, second ack = it has been applied. Runs at MONITOR priority through
 * {@link OutboundStateListener}, so cancelled packets are not tracked. Owner thread.
 *
 * <p>Packet fields used, verified with {@code javap} against the installed PacketEvents 2.14.0 jar:
 * <ul>
 *   <li>{@code WrapperPlayServerJoinGame#getEntityId()} — the player's own entity id.</li>
 *   <li>{@code WrapperPlayServerEntityVelocity#getEntityId()/getVelocity()} — the wrapper converts
 *       to blocks per tick itself (pre-1.21.9: three shorts divided by 8000.0; 1.21.9+:
 *       {@code LpVector3d}), see its {@code read()} bytecode.</li>
 *   <li>{@code WrapperPlayServerExplosion#getKnockback()} — pre-1.21.2 three floats, 1.21.2+ an
 *       optional vector, so {@code null} means "no knockback for this player".</li>
 * </ul>
 * Explosion knockback is additive ({@link VelocityState.Kind#ADD}); entity velocity replaces the
 * motion ({@link VelocityState.Kind#SET}). No exemption is granted: the engine models both cases.
 * Mace / wind-charge / lunge impulses are NOT derived here (their numbers are UNCONFIRMED,
 * version-notes.md section 6); they only arrive as ordinary velocity/explosion packets if at all.
 */
public final class VelocityTracker implements OutboundTracker {

    private final ComponentKey<VelocityState> stateKey;

    public VelocityTracker(ComponentKey<VelocityState> stateKey) {
        this.stateKey = stateKey;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity packet = new WrapperPlayServerEntityVelocity(event);
            Vector3d velocity = packet.getVelocity();
            if (velocity != null) {
                onEntityVelocity(player, event.getTasksAfterSend(), packet.getEntityId(),
                        velocity.x, velocity.y, velocity.z);
            }
        } else if (type == PacketType.Play.Server.EXPLOSION) {
            Vector3d knockback = new WrapperPlayServerExplosion(event).getKnockback();
            if (knockback != null) {
                onVelocity(player, event.getTasksAfterSend(), VelocityState.Kind.ADD,
                        knockback.x, knockback.y, knockback.z);
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            onJoin(player, new WrapperPlayServerJoinGame(event).getEntityId());
        }
    }

    /** Primitive entry point (tests): the join packet carries the player's own entity id. */
    void onJoin(PlayerData player, int entityId) {
        VelocityState state = player.component(stateKey);
        state.clear();
        state.selfEntityId(entityId);
    }

    /** Primitive entry point (tests): velocities of other entities are ignored. */
    boolean onEntityVelocity(PlayerData player, List<Runnable> tasksAfterSend, int entityId,
                             double x, double y, double z) {
        if (!player.component(stateKey).isSelf(entityId)) {
            return false;
        }
        return onVelocity(player, tasksAfterSend, VelocityState.Kind.SET, x, y, z);
    }

    /** Primitive entry point (tests). Non-finite vectors are ignored. */
    boolean onVelocity(PlayerData player, List<Runnable> tasksAfterSend, VelocityState.Kind kind,
                       double x, double y, double z) {
        if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
            return false;
        }
        VelocityState state = player.component(stateKey);
        long id = state.register(kind, x, y, z, player.movement().worldEpoch());
        player.acks().bracket(tasksAfterSend,
                () -> state.markFirstAck(id),
                () -> state.markSecondAck(id, player.clientTicks()));
        return true;
    }
}
