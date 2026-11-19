package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.VehicleState;
import dev.vaplus.anticheat.player.state.VelocityState;

import java.util.List;

/**
 * Feeds {@link VehicleState} (mount / dismount of the player's own entity) from outbound
 * {@code SET_PASSENGERS} and {@code DESTROY_ENTITIES}; the player's entity id comes from the join
 * packet via {@link VelocityState#selfEntityId()} (the {@link VelocityTracker} runs before this
 * tracker). Asymmetric on purpose, so checks never judge a player who might be riding:
 * <ul>
 *   <li>mount: applied when the packet is sent (the client may already ride after the first marker)
 *       plus a {@link ExemptionCause#VEHICLE} grant of {@link #TRANSITION_TICKS};</li>
 *   <li>dismount: applied after the second bracket marker (the client has surely processed it),
 *       again with a {@link ExemptionCause#VEHICLE} grant, and only if the player still rides the
 *       vehicle the packet was about.</li>
 * </ul>
 * Packet fields verified with {@code javap} against PacketEvents 2.14.0:
 * {@code WrapperPlayServerSetPassengers#getEntityId()/getPassengers()},
 * {@code WrapperPlayServerDestroyEntities#getEntityIds()}. Owner thread.
 */
public final class PassengerTracker implements OutboundTracker {

    /** Ticks of {@link ExemptionCause#VEHICLE} around a mount or dismount. TUNING. */
    public static final int TRANSITION_TICKS = 20;

    private final ComponentKey<VelocityState> selfKey;

    public PassengerTracker(ComponentKey<VelocityState> selfKey) {
        this.selfKey = selfKey;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
            onPassengers(player, event.getTasksAfterSend(), packet.getEntityId(), packet.getPassengers());
        } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            VehicleState vehicle = player.vehicle();
            if (vehicle.inVehicle()) {
                int[] ids = new WrapperPlayServerDestroyEntities(event).getEntityIds();
                if (ids != null) {
                    for (int id : ids) {
                        if (id == vehicle.vehicleEntityId()) {
                            scheduleDismount(player, event.getTasksAfterSend(), id);
                        }
                    }
                }
            }
        }
    }

    /** Primitive entry point (tests). */
    void onPassengers(PlayerData player, List<Runnable> tasksAfterSend, int vehicleId, int[] passengers) {
        VelocityState self = player.component(selfKey);
        if (self.selfEntityId() == VelocityState.UNKNOWN_ENTITY_ID) {
            return;
        }
        boolean riding = false;
        if (passengers != null) {
            for (int passenger : passengers) {
                if (self.isSelf(passenger)) {
                    riding = true;
                    break;
                }
            }
        }
        VehicleState vehicle = player.vehicle();
        if (riding) {
            vehicle.mount(vehicleId);
            player.exemptions().grant(ExemptionCause.VEHICLE, TRANSITION_TICKS);
        } else if (vehicle.inVehicle() && vehicle.vehicleEntityId() == vehicleId) {
            scheduleDismount(player, tasksAfterSend, vehicleId);
        }
    }

    private static void scheduleDismount(PlayerData player, List<Runnable> tasksAfterSend, int vehicleId) {
        player.acks().bracket(tasksAfterSend, () -> {
        }, () -> {
            VehicleState vehicle = player.vehicle();
            if (vehicle.inVehicle() && vehicle.vehicleEntityId() == vehicleId) {
                vehicle.dismount();
                player.exemptions().grant(ExemptionCause.VEHICLE, TRANSITION_TICKS);
            }
        });
    }
}
