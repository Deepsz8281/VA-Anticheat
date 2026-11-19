package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.PoseState;

/**
 * Tracks client-declared pose and input: sprint/sneak commands, the 1.21.2+ key-state packet and
 * client-side flight toggles.
 *
 * <p>Packets are parsed in the <em>server's</em> protocol format (translation layers such as
 * ViaVersion sit in between), so the sneak source is chosen by server version: from 1.21.6 the
 * sneak entity actions no longer exist (PacketEvents maps them to id -1) and sneaking is the shift
 * key of the player-input packet. Sneak entity actions are still honoured if they arrive.
 */
public final class InputTracker implements InboundTracker {

    private final boolean sneakFromInputPacket;

    public InputTracker(boolean sneakFromInputPacket) {
        this.sneakFromInputPacket = sneakFromInputPacket;
    }

    @Override
    public void onReceive(PlayerData player, PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.ENTITY_ACTION) {
            onEntityAction(player.pose(), new WrapperPlayClientEntityAction(event));
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            WrapperPlayClientPlayerInput packet = new WrapperPlayClientPlayerInput(event);
            PoseState pose = player.pose();
            pose.applyInput(packet.isForward(), packet.isBackward(), packet.isLeft(), packet.isRight(),
                    packet.isJump(), packet.isShift(), packet.isSprint(), player.clientTicks());
            if (sneakFromInputPacket) {
                pose.sneaking(packet.isShift());
            }
        } else if (type == PacketType.Play.Client.PLAYER_ABILITIES) {
            WrapperPlayClientPlayerAbilities packet = new WrapperPlayClientPlayerAbilities(event);
            // Mirrors vanilla: the flying flag is only accepted while flight is allowed.
            player.abilities().applyClientFlying(packet.isFlying() && player.abilities().flightAllowed());
        }
    }

    private static void onEntityAction(PoseState pose, WrapperPlayClientEntityAction packet) {
        switch (packet.getAction()) {
            case START_SNEAKING -> pose.sneaking(true);
            case STOP_SNEAKING -> pose.sneaking(false);
            case START_SPRINTING -> pose.sprinting(true);
            case STOP_SPRINTING -> pose.sprinting(false);
            case START_FLYING_WITH_ELYTRA -> pose.requestGlide();
            default -> {
            }
        }
    }
}
