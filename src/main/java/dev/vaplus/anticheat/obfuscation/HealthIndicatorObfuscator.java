package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.pipeline.OutboundTracker;
import dev.vaplus.anticheat.player.PlayerData;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Anti-health-indicator prevention: rewrites outgoing packets so the viewer's client never learns
 * information vanilla does not display — the exact health, absorption, score and air of other
 * entities, the durability, enchantments and stack size of their equipment, their game mode in the
 * tab list, heart-rendered scoreboard values and (optionally) the viewer's own food saturation.
 * Health-indicator, "target HUD" and armor-durability cheats then show fake values.
 *
 * <h2>Pipeline position</h2>
 * Runs as an {@link OutboundTracker} (MONITOR priority, cancelled packets skipped) and is registered
 * by the last module, so every state tracker and outbound check of the anticheat (all at lower
 * priorities or earlier in the tracker list) still reads the real values. It is a transformer and
 * never writes player state except its own {@link HealthIndicatorState}.
 *
 * <h2>What is never rewritten</h2>
 * <ul>
 *   <li>the viewer's own entity (including its health/absorption/score);</li>
 *   <li>entities whose spawn packet the viewer never received (type unknown);</li>
 *   <li>non-living entities (interaction and display entities use the same indexes for other data);</li>
 *   <li>wolves, withers and ender dragons ({@link HealthIndicatorSpoofer#exempt});</li>
 *   <li>a dead entity's zero health (the client needs it for the death animation);</li>
 *   <li>the vehicle the viewer rides: the client shows the mount's health bar. On mount the real
 *       health is re-sent, on dismount the next spoofed value;</li>
 *   <li>everything for viewers with {@code ac.healthindicator.bypass}.</li>
 * </ul>
 */
public final class HealthIndicatorObfuscator implements OutboundTracker {

    private final ComponentKey<HealthIndicatorState> stateKey;
    private final HealthIndicatorSpoofer.Indexes indexes;
    private volatile HealthIndicatorSettings settings = HealthIndicatorSettings.defaults();

    public HealthIndicatorObfuscator(ComponentKey<HealthIndicatorState> stateKey, HealthIndicatorSpoofer.Indexes indexes) {
        this.stateKey = Objects.requireNonNull(stateKey, "stateKey");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
    }

    /** Applies reloaded settings; takes effect with the next packet. */
    public void update(HealthIndicatorSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public HealthIndicatorSettings settings() {
        return settings;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        HealthIndicatorState state = player.component(stateKey);
        HealthIndicatorSettings current = settings;
        boolean active = current.enabled() && !player.healthIndicatorBypass();

        // Entity bookkeeping always runs, so a reload that enables the feature or a dropped bypass
        // permission works for entities that are already visible.
        if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity packet = wrapper(event, WrapperPlayServerSpawnEntity.class,
                    WrapperPlayServerSpawnEntity::new);
            state.spawn(packet.getEntityId(), packet.getEntityType());
        } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            int[] ids = wrapper(event, WrapperPlayServerDestroyEntities.class, WrapperPlayServerDestroyEntities::new)
                    .getEntityIds();
            if (ids != null) {
                for (int id : ids) {
                    state.remove(id);
                }
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME || type == PacketType.Play.Server.RESPAWN) {
            state.clearEntities();
        } else if (type == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers packet = wrapper(event, WrapperPlayServerSetPassengers.class,
                    WrapperPlayServerSetPassengers::new);
            onPassengers(player, state, event, packet.getEntityId(), packet.getPassengers(), active && current.health());
        } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
            onMetadata(player, state, event, current, active);
        } else if (type == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
            WrapperPlayServerScoreboardObjective packet = wrapper(event, WrapperPlayServerScoreboardObjective.class,
                    WrapperPlayServerScoreboardObjective::new);
            if (packet.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                state.heartObjective(packet.getName(), false);
            } else {
                state.heartObjective(packet.getName(),
                        packet.getRenderType() == WrapperPlayServerScoreboardObjective.RenderType.HEARTS);
            }
        } else if (!active) {
            return;
        } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            if (current.items()) {
                onEquipment(player, event, current);
            }
        } else if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            if (current.gamemode()) {
                onPlayerInfo(player, event);
            }
        } else if (type == PacketType.Play.Server.UPDATE_SCORE) {
            if (current.heartsScoreboard()) {
                onScore(player, state, event);
            }
        } else if (type == PacketType.Play.Server.UPDATE_HEALTH) {
            if (current.foodSaturation()) {
                WrapperPlayServerUpdateHealth packet = wrapper(event, WrapperPlayServerUpdateHealth.class,
                        WrapperPlayServerUpdateHealth::new);
                if (packet.getFoodSaturation() > 0.0f) {
                    packet.setFoodSaturation(0.0f);
                    reEncode(event, packet);
                }
            }
        }
    }

    private void onMetadata(PlayerData player, HealthIndicatorState state, PacketSendEvent event,
                            HealthIndicatorSettings current, boolean active) {
        WrapperPlayServerEntityMetadata packet = wrapper(event, WrapperPlayServerEntityMetadata.class,
                WrapperPlayServerEntityMetadata::new);
        int entityId = packet.getEntityId();
        EntityType entityType = state.type(entityId);
        if (!HealthIndicatorSpoofer.living(entityType)) {
            return;
        }
        List<EntityData<?>> entries = packet.getEntityMetadata();
        if (entries == null) {
            return;
        }
        // Remember the real health first: a rider has to get it back for its mount.
        for (EntityData<?> entry : entries) {
            if (entry.getIndex() == indexes.health() && entry.getValue() instanceof Float health) {
                state.recordHealth(entityId, health);
            }
        }
        if (!active
                || entityId == event.getUser().getEntityId()
                || entityId == state.vehicle()
                || HealthIndicatorSpoofer.exempt(entityType)
                || (current.playersOnly() && entityType != EntityTypes.PLAYER)) {
            return;
        }
        if (HealthIndicatorSpoofer.spoofMetadata(entries, entityType, indexes, current)) {
            reEncode(event, packet);
        }
    }

    /**
     * Mount: the client renders the vehicle's health bar, so the real value is sent right after the
     * passenger packet and the vehicle is no longer rewritten. Dismount: the real value is sent again
     * and passes through this obfuscator, which now spoofs it.
     */
    void onPassengers(PlayerData player, HealthIndicatorState state, PacketSendEvent event, int vehicleId,
                      int[] passengers, boolean resync) {
        int self = event.getUser().getEntityId();
        boolean riding = false;
        if (passengers != null) {
            for (int passenger : passengers) {
                if (passenger == self) {
                    riding = true;
                    break;
                }
            }
        }
        if (riding) {
            if (state.vehicle() == vehicleId || vehicleId == self) {
                return;
            }
            state.vehicle(vehicleId);
        } else if (state.vehicle() == vehicleId) {
            state.vehicle(HealthIndicatorState.NO_VEHICLE);
        } else {
            return;
        }
        Float health = state.realHealth(vehicleId);
        if (!resync || health == null) {
            return;
        }
        WrapperPlayServerEntityMetadata update = new WrapperPlayServerEntityMetadata(vehicleId,
                List.of(new EntityData<>(indexes.health(), EntityDataTypes.FLOAT, health)));
        event.getTasksAfterSend().add(() -> player.transport().send(update));
    }

    private void onEquipment(PlayerData player, PacketSendEvent event, HealthIndicatorSettings current) {
        WrapperPlayServerEntityEquipment packet = wrapper(event, WrapperPlayServerEntityEquipment.class,
                WrapperPlayServerEntityEquipment::new);
        if (packet.getEntityId() == event.getUser().getEntityId()) {
            return;
        }
        List<Equipment> equipment = packet.getEquipment();
        if (equipment == null) {
            return;
        }
        ClientVersion version = version(event);
        boolean changed = false;
        for (Equipment slot : equipment) {
            changed |= HealthIndicatorSpoofer.spoofItem(slot.getItem(), version, current);
        }
        if (changed) {
            reEncode(event, packet);
        }
    }

    private void onPlayerInfo(PlayerData player, PacketSendEvent event) {
        WrapperPlayServerPlayerInfoUpdate packet = wrapper(event, WrapperPlayServerPlayerInfoUpdate.class,
                WrapperPlayServerPlayerInfoUpdate::new);
        if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) {
            return;
        }
        boolean changed = false;
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo info : packet.getEntries()) {
            GameMode mode = info.getGameMode();
            if (mode == null || mode == GameMode.SURVIVAL || mode == GameMode.SPECTATOR
                    || player.uuid().equals(info.getProfileId())) {
                continue;
            }
            info.setGameMode(GameMode.SURVIVAL);
            changed = true;
        }
        if (changed) {
            reEncode(event, packet);
        }
    }

    private void onScore(PlayerData player, HealthIndicatorState state, PacketSendEvent event) {
        WrapperPlayServerUpdateScore packet = wrapper(event, WrapperPlayServerUpdateScore.class,
                WrapperPlayServerUpdateScore::new);
        if (!state.isHeartObjective(packet.getObjectiveName()) || player.name().equals(packet.getEntityName())) {
            return;
        }
        Optional<Integer> value = packet.getValue();
        if (value != null && value.isPresent() && value.get() != -1) {
            packet.setValue(Optional.of(-1));
            reEncode(event, packet);
        }
    }

    /** Reuses the wrapper an earlier tracker already parsed for this event, if it has the right type. */
    private static <W extends PacketWrapper<?>> W wrapper(PacketSendEvent event, Class<W> type,
                                                          Function<PacketSendEvent, W> factory) {
        Object last = event.getLastUsedWrapper();
        return type.isInstance(last) ? type.cast(last) : factory.apply(event);
    }

    private static void reEncode(PacketSendEvent event, PacketWrapper<?> wrapper) {
        event.setLastUsedWrapper(wrapper);
        event.markForReEncode(true);
    }

    private static ClientVersion version(PacketSendEvent event) {
        ServerVersion server = event.getServerVersion();
        return server != null
                ? server.toClientVersion()
                : PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    }
}
