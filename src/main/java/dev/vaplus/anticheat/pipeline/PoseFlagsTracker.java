package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.VelocityState;

import java.util.List;

/**
 * Feeds the server-owned pose flags of the player's own entity into {@link PoseState}: elytra gliding
 * (shared entity flags byte, index 0, bit 0x80) and swimming (bit 0x10), from outbound
 * {@code ENTITY_METADATA}. Asymmetric like the other trackers so checks never judge a pose that may
 * still be active on the client: a flag turning on is applied when the packet is sent, a flag turning
 * off after the second bracket marker. A glide request ({@code START_FLYING_WITH_ELYTRA}) made after
 * the "off" packet was sent stays pending; an unanswered request expires after
 * {@link #GLIDE_REQUEST_TICKS} client ticks (the server rejects silently when no elytra is worn).
 *
 * <p>Index 0 and the bit values are the vanilla {@code Entity} shared flags (on fire 0x01, crouching
 * 0x02, sprinting 0x08, swimming 0x10, invisible 0x20, glowing 0x40, fall flying 0x80); the same
 * index is read for other players by {@code NearbyPlayersTracker}. The own entity id comes from
 * {@link VelocityState#selfEntityId()}. Owner thread.
 */
public final class PoseFlagsTracker implements OutboundTracker, ClientTickListener {

    public static final int SHARED_FLAGS_INDEX = 0;
    public static final int SWIMMING_BIT = 0x10;
    public static final int GLIDING_BIT = 0x80;
    /** Client ticks an unconfirmed glide request is honoured. TUNING (well above any RTT). */
    public static final int GLIDE_REQUEST_TICKS = 60;

    private final ComponentKey<VelocityState> selfKey;

    public PoseFlagsTracker(ComponentKey<VelocityState> selfKey) {
        this.selfKey = selfKey;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) {
            return;
        }
        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        if (!player.component(selfKey).isSelf(packet.getEntityId())) {
            return;
        }
        for (EntityData<?> data : packet.getEntityMetadata()) {
            if (data.getIndex() == SHARED_FLAGS_INDEX && data.getValue() instanceof Number value) {
                onFlags(player, event.getTasksAfterSend(), value.intValue());
            }
        }
    }

    @Override
    public void onClientTick(PlayerData player) {
        player.pose().ageGlideRequest(GLIDE_REQUEST_TICKS);
    }

    /** Primitive entry point (tests): the shared flags byte of the own entity. */
    void onFlags(PlayerData player, List<Runnable> tasksAfterSend, int flags) {
        PoseState pose = player.pose();
        boolean gliding = (flags & GLIDING_BIT) != 0;
        boolean swimming = (flags & SWIMMING_BIT) != 0;
        if (gliding) {
            pose.gliding(true);
        }
        if (swimming) {
            pose.swimming(true);
        }
        if (gliding && swimming) {
            return;
        }
        long requests = pose.glideRequests();
        player.acks().bracket(tasksAfterSend, null, () -> {
            if (!gliding) {
                if (pose.glideRequests() == requests) {
                    pose.gliding(false);
                } else {
                    pose.glidingEndedKeepRequest();
                }
            }
            if (!swimming) {
                pose.swimming(false);
            }
        });
    }
}
