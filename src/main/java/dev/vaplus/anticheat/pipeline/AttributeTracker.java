package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.AttributeState;
import dev.vaplus.anticheat.player.state.VelocityState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Feeds {@link AttributeState} from {@code UPDATE_ATTRIBUTES} packets of the player's own entity
 * (id from {@link VelocityState#selfEntityId()}, set by the velocity tracker on JOIN), bracketed by
 * two markers like {@link VelocityTracker}: the packet entries are committed at the second ack with
 * {@link AttributeState#setAttribute} (base + full modifier list; vanilla replaces an attribute's
 * modifiers with the packet's list). MONITOR priority via {@link OutboundStateListener}, owner thread.
 *
 * <h2>Uncertainty window</h2>
 * If the packet changes a movement attribute ({@link #MOVEMENT_ATTRIBUTES}) compared with what was
 * last <em>sent</em> (ignoring modifiers the engine models itself, e.g. the sprint modifier), the
 * first ack opens {@link AttributeState#beginPending()} and the second closes it; the engine skips
 * ({@code attributes-pending}) in between. Sprint toggles, which make the server resend
 * {@code movement_speed} with/without the sprint modifier, therefore open no window.
 *
 * <p>Packet API (verified with {@code javap} on PacketEvents 2.14.0):
 * {@code WrapperPlayServerUpdateAttributes#getEntityId()/getProperties()},
 * {@code Property#getKey()} ({@code Attribute.getName().toString()}, namespaced),
 * {@code Property#getValue()} (base), {@code Property#getModifiers()},
 * {@code PropertyModifier#getName()} (ResourceLocation; for pre-1.21 servers the wrapper builds it
 * from the UUID), {@code getUUID()}, {@code getAmount()}, {@code getOperation()} ∈
 * {@code ADDITION, MULTIPLY_BASE, MULTIPLY_TOTAL} (wire order 0, 1, 2 via {@code Operation.VALUES}).
 * Legacy {@code generic.}/{@code player.} name prefixes are stripped (canonical 1.21.2+ names).
 */
public final class AttributeTracker implements OutboundTracker {

    /** Attributes whose change makes the movement engine's state uncertain. */
    public static final Set<String> MOVEMENT_ATTRIBUTES = Set.of(
            AttributeState.MOVEMENT_SPEED, AttributeState.JUMP_STRENGTH, AttributeState.STEP_HEIGHT,
            AttributeState.GRAVITY, AttributeState.SAFE_FALL_DISTANCE, AttributeState.FALL_DAMAGE_MULTIPLIER,
            AttributeState.SCALE, AttributeState.SNEAKING_SPEED, AttributeState.MOVEMENT_EFFICIENCY,
            AttributeState.WATER_MOVEMENT_EFFICIENCY, AttributeState.AIR_DRAG_MODIFIER,
            AttributeState.FRICTION_MODIFIER, AttributeState.BOUNCINESS);

    private final ComponentKey<VelocityState> selfKey;
    private final ComponentKey<SentAttributes> sentKey;
    private final Set<String> engineModifierIds;

    /**
     * @param engineModifierIds modifier ids the engine applies itself (ignored when deciding whether
     *                          an update opens an uncertainty window; still committed)
     */
    public AttributeTracker(ComponentKey<VelocityState> selfKey, ComponentKey<SentAttributes> sentKey,
                            Set<String> engineModifierIds) {
        this.selfKey = selfKey;
        this.sentKey = sentKey;
        this.engineModifierIds = Set.copyOf(engineModifierIds);
    }

    /** One attribute of a packet in tracker form (canonical name, base, modifiers). */
    public record Entry(String name, double base, List<AttributeState.Modifier> modifiers) {
        public Entry {
            Objects.requireNonNull(name, "name");
            modifiers = List.copyOf(modifiers);
        }
    }

    /**
     * Per-player view of the movement attributes last <em>sent</em> (not yet necessarily applied),
     * used to decide whether a new packet changes anything the engine cares about. Owner thread.
     */
    public static final class SentAttributes {
        private final Map<String, Entry> sent = new HashMap<>();

        void clear() {
            sent.clear();
        }

        /** Number of attributes with a recorded sent state. */
        public int size() {
            return sent.size();
        }
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            WrapperPlayServerUpdateAttributes packet = new WrapperPlayServerUpdateAttributes(event);
            if (!player.component(selfKey).isSelf(packet.getEntityId())) {
                return;
            }
            List<WrapperPlayServerUpdateAttributes.Property> properties = packet.getProperties();
            if (properties == null || properties.isEmpty()) {
                return;
            }
            List<Entry> entries = new ArrayList<>(properties.size());
            for (WrapperPlayServerUpdateAttributes.Property property : properties) {
                Entry entry = convert(property);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            onAttributes(player, event.getTasksAfterSend(), entries);
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            onJoin(player);
        } else if (type == PacketType.Play.Server.RESPAWN) {
            onRespawn(player);
        }
    }

    /** JOIN: a new player entity with vanilla defaults; nothing was sent for it yet. */
    void onJoin(PlayerData player) {
        player.attributes().reset();
        player.component(sentKey).clear();
    }

    /**
     * RESPAWN: the client recreates its entity ({@code GameStateTracker} resets the committed
     * attributes at the second ack unless kept); forget the sent view so the next update counts as
     * a change.
     */
    void onRespawn(PlayerData player) {
        player.component(sentKey).clear();
    }

    /**
     * Primitive entry point (tests): commits {@code entries} at the second ack.
     *
     * @return whether the update opened an uncertainty window
     */
    boolean onAttributes(PlayerData player, List<Runnable> tasksAfterSend, List<Entry> entries) {
        if (entries.isEmpty()) {
            return false;
        }
        List<Entry> commit = List.copyOf(entries);
        boolean relevant = recordSent(player.component(sentKey), commit);
        AttributeState attributes = player.attributes();
        player.acks().bracket(tasksAfterSend,
                relevant ? attributes::beginPending : null,
                () -> {
                    for (Entry entry : commit) {
                        attributes.setAttribute(entry.name(), entry.base(), entry.modifiers());
                    }
                    if (relevant) {
                        attributes.endPending();
                    }
                });
        return relevant;
    }

    /** Updates the sent view; true if a movement attribute changed (ignoring engine modifiers). */
    private boolean recordSent(SentAttributes sent, List<Entry> entries) {
        boolean changed = false;
        for (Entry entry : entries) {
            if (!MOVEMENT_ATTRIBUTES.contains(entry.name())) {
                continue;
            }
            Entry signature = withoutEngineModifiers(entry);
            Entry previous = sent.sent.put(entry.name(), signature);
            if (!signature.equals(previous)) {
                changed = true;
            }
        }
        return changed;
    }

    private Entry withoutEngineModifiers(Entry entry) {
        TreeMap<String, AttributeState.Modifier> byId = new TreeMap<>();
        for (AttributeState.Modifier modifier : entry.modifiers()) {
            if (!engineModifierIds.contains(modifier.id())) {
                byId.put(modifier.id(), modifier);
            }
        }
        return new Entry(entry.name(), entry.base(), new ArrayList<>(byId.values()));
    }

    /** Converts a packet property; {@code null} for names/values the state cannot hold. */
    static Entry convert(WrapperPlayServerUpdateAttributes.Property property) {
        String name = canonicalName(property.getKey());
        double base = property.getValue();
        if (name == null || !Double.isFinite(base)) {
            return null;
        }
        List<WrapperPlayServerUpdateAttributes.PropertyModifier> raw = property.getModifiers();
        List<AttributeState.Modifier> modifiers = new ArrayList<>(raw == null ? 0 : raw.size());
        if (raw != null) {
            for (WrapperPlayServerUpdateAttributes.PropertyModifier modifier : raw) {
                AttributeState.Modifier converted = convert(modifier);
                if (converted != null) {
                    modifiers.add(converted);
                }
            }
        }
        return new Entry(name, base, Collections.unmodifiableList(modifiers));
    }

    private static AttributeState.Modifier convert(WrapperPlayServerUpdateAttributes.PropertyModifier modifier) {
        AttributeState.Operation operation = operation(modifier.getOperation());
        double amount = modifier.getAmount();
        String id = modifierId(modifier.getName(), modifier.getUUID());
        if (operation == null || id == null || !Double.isFinite(amount)) {
            return null;
        }
        return new AttributeState.Modifier(operation, amount, id);
    }

    static AttributeState.Operation operation(WrapperPlayServerUpdateAttributes.PropertyModifier.Operation operation) {
        if (operation == null) {
            return null;
        }
        return switch (operation) {
            case ADDITION -> AttributeState.Operation.ADD;
            case MULTIPLY_BASE -> AttributeState.Operation.MULTIPLY_BASE;
            case MULTIPLY_TOTAL -> AttributeState.Operation.MULTIPLY_TOTAL;
        };
    }

    static String modifierId(ResourceLocation name, UUID uuid) {
        if (name != null) {
            String value = name.toString();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return uuid == null ? null : uuid.toString();
    }

    /**
     * Canonical attribute name: namespaced, lower case, legacy {@code generic.}/{@code player.}
     * prefixes removed ({@code minecraft:generic.movement_speed} → {@code minecraft:movement_speed}).
     * {@code null} for blank or malformed names.
     */
    static String canonicalName(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String name = key.trim().toLowerCase(java.util.Locale.ROOT);
        int colon = name.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : name.substring(0, colon);
        String path = colon < 0 ? name : name.substring(colon + 1);
        if (path.startsWith("generic.")) {
            path = path.substring("generic.".length());
        } else if (path.startsWith("player.")) {
            path = path.substring("player.".length());
        }
        if (namespace.isEmpty() || path.isEmpty()
                || !namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9/._-]+")) {
            return null;
        }
        return namespace + ":" + path;
    }
}
