package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import dev.vaplus.anticheat.check.impl.combat.EntityAttack;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.world.BlockInteraction;

/** Converts placement, item-use and digging packets into {@link BlockInteraction}s for block checks. */
public final class InteractionProcessor {

    /** Owner thread. Ignores packet types that are not interactions. */
    public void handle(PlayerData player, PacketReceiveEvent event, PacketTypeCommon type) {
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            // Combat layer: only attacks are detection-relevant (right-click interactions are not).
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                player.entityAttack().assign(packet.getEntityId(), EntityAttack.Kind.ATTACK, false,
                        0.0, 0.0, 0.0, player.context().time().nanoTime());
                player.checks().dispatchAttack(player.entityAttack());
            }
            return;
        }
        BlockInteraction interaction = player.interaction();
        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            long now = player.context().time().nanoTime();
            WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);
            Vector3i position = packet.getBlockPosition();
            Vector3f cursor = packet.getCursorPosition();
            interaction.assignPlace(position.getX(), position.getY(), position.getZ(), packet.getFace(),
                    cursor.getX(), cursor.getY(), cursor.getZ(), packet.getHand(), packet.getSequence(),
                    packet.getInsideBlock().orElse(Boolean.FALSE), now);
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            long now = player.context().time().nanoTime();
            WrapperPlayClientUseItem packet = new WrapperPlayClientUseItem(event);
            interaction.assignUse(packet.getHand(), packet.getSequence(), now);
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
            BlockInteraction.Kind kind = switch (packet.getAction()) {
                case START_DIGGING -> BlockInteraction.Kind.DIG_START;
                case CANCELLED_DIGGING -> BlockInteraction.Kind.DIG_CANCEL;
                case FINISHED_DIGGING -> BlockInteraction.Kind.DIG_FINISH;
                default -> null;
            };
            if (kind == null) {
                return;
            }
            long now = player.context().time().nanoTime();
            Vector3i position = packet.getBlockPosition();
            interaction.assignDig(kind, position.getX(), position.getY(), position.getZ(),
                    packet.getBlockFace(), packet.getSequence(), now);
        } else {
            return;
        }
        player.checks().dispatchInteraction(interaction);
    }
}
