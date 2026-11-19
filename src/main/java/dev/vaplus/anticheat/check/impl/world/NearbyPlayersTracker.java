package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.pipeline.OutboundTracker;
import dev.vaplus.anticheat.player.PlayerData;

/**
 * Outbound feeder of {@link NearbyPlayers} (MONITOR priority, cancelled packets are skipped, owner
 * thread). Players and every entity with a known vanilla hitbox ({@link EntityDimensions}) are
 * tracked; other spawns (items, projectiles, ...) are ignored so the array stays small.
 *
 * <h2>Packets (fields verified with {@code javap} on PacketEvents 2.14.0)</h2>
 * <ul>
 *   <li>{@code SPAWN_ENTITY}: {@code getEntityId()/getEntityType()/getPosition()} — modern
 *       protocols spawn players as {@code ADD_ENTITY} with type {@code PLAYER}.</li>
 *   <li>{@code SPAWN_PLAYER}: legacy dedicated player spawn; same fields.</li>
 *   <li>{@code ENTITY_RELATIVE_MOVE} and {@code ENTITY_RELATIVE_MOVE_AND_ROTATION}:
 *       {@code getDeltaX/Y/Z()} in blocks (PacketEvents decodes the wire units).</li>
 *   <li>{@code ENTITY_TELEPORT}, {@code ENTITY_POSITION_SYNC}: absolute
 *       {@code getPosition()} / {@code getValues().getPosition()}.</li>
 *   <li>{@code DESTROY_ENTITIES}: {@code getEntityIds()}.</li>
 *   <li>{@code JOIN_GAME}, {@code RESPAWN}: entity ids are per world instance, so the list is
 *       cleared when the server switches the viewer's level (same choice as {@code WorldTracker}).</li>
 * </ul>
 *
 * <p>Rotations are not tracked; the ESP check only needs positions.
 */
public final class NearbyPlayersTracker implements OutboundTracker {

    private final ComponentKey<NearbyPlayers> key;

    public NearbyPlayersTracker(ComponentKey<NearbyPlayers> key) {
        this.key = key;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        NearbyPlayers tracked = player.component(key);

        if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
            EntityType entityType = packet.getEntityType();
            if (entityType == EntityTypes.PLAYER) {
                Vector3d position = packet.getPosition();
                tracked.spawn(packet.getEntityId(), position.getX(), position.getY(), position.getZ());
            } else {
                // Mobs, end crystals, armor stands: killaura / crystal aura attack them too.
                // Entities without a known vanilla box (items, projectiles, ...) are not tracked.
                double[] size = EntityDimensions.of(entityType);
                if (size != null) {
                    Vector3d position = packet.getPosition();
                    tracked.spawn(packet.getEntityId(), position.getX(), position.getY(), position.getZ(),
                            false, size[0], size[1]);
                }
            }
        } else if (type == PacketType.Play.Server.SPAWN_PLAYER) {
            WrapperPlayServerSpawnPlayer packet = new WrapperPlayServerSpawnPlayer(event);
            Vector3d position = packet.getPosition();
            tracked.spawn(packet.getEntityId(), position.getX(), position.getY(), position.getZ());
        } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            WrapperPlayServerEntityRelativeMove packet = new WrapperPlayServerEntityRelativeMove(event);
            tracked.moveBy(packet.getEntityId(), packet.getDeltaX(), packet.getDeltaY(), packet.getDeltaZ());
        } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            WrapperPlayServerEntityRelativeMoveAndRotation packet =
                    new WrapperPlayServerEntityRelativeMoveAndRotation(event);
            tracked.moveBy(packet.getEntityId(), packet.getDeltaX(), packet.getDeltaY(), packet.getDeltaZ());
        } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrapperPlayServerEntityTeleport packet = new WrapperPlayServerEntityTeleport(event);
            Vector3d position = packet.getPosition();
            tracked.moveTo(packet.getEntityId(), position.getX(), position.getY(), position.getZ());
        } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            WrapperPlayServerEntityPositionSync packet = new WrapperPlayServerEntityPositionSync(event);
            Vector3d position = packet.getValues().getPosition();
            tracked.moveTo(packet.getId(), position.getX(), position.getY(), position.getZ());
        } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
            // Index 0 of a living entity's metadata is the shared entity-flags byte:
            // 0x02 = crouching, 0x20 = invisible (vanilla Entity flag bits).
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
            for (EntityData<?> data : packet.getEntityMetadata()) {
                if (data.getIndex() != 0 || !(data.getValue() instanceof Number value)) {
                    continue;
                }
                int flags = value.intValue();
                tracked.setInvisible(packet.getEntityId(), (flags & 0x20) != 0);
                tracked.setSneaking(packet.getEntityId(), (flags & 0x02) != 0);
            }
        } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            WrapperPlayServerDestroyEntities packet = new WrapperPlayServerDestroyEntities(event);
            for (int entityId : packet.getEntityIds()) {
                tracked.remove(entityId);
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME || type == PacketType.Play.Server.RESPAWN) {
            tracked.clear();
        }
    }
}
