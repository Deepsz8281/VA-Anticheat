package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Feeds {@link EffectState} from {@code ENTITY_EFFECT} / {@code REMOVE_ENTITY_EFFECT} of the
 * player's own entity (id from {@link VelocityState#selfEntityId()}), bracketed by two markers like
 * {@link VelocityTracker}: committed at the second ack. MONITOR priority via
 * {@link OutboundStateListener}, owner thread.
 *
 * <h2>Uncertainty window</h2>
 * If the packet changes the effect compared with what was last <em>sent</em> (amplifier added,
 * changed or removed), the first ack opens {@link EffectState#beginPending} and the second closes
 * it; the engine skips ({@code effects-pending}) while a movement effect is pending. Re-sends with
 * the same amplifier (beacon refreshes every few seconds, duration updates) open no window.
 * Durations are not tracked: the client keeps an effect until the server removes it (removal on the
 * client side at duration end is UNCONFIRMED from fetched sources; the remove packet is authoritative).
 *
 * <p>Packet API (verified with {@code javap} on PacketEvents 2.14.0):
 * {@code WrapperPlayServerEntityEffect#getEntityId()/getPotionType()/getEffectAmplifier()},
 * {@code WrapperPlayServerRemoveEntityEffect#getEntityId()/getPotionType()}; {@code PotionType} is a
 * {@code MappedEntity} whose {@code getName()} is a namespaced {@code ResourceLocation}. Effects
 * are matched by name (e.g. {@code minecraft:jump_boost}), effects not in {@link TrackedEffect}
 * are ignored.
 */
public final class EffectTracker implements OutboundTracker {

    private final ComponentKey<VelocityState> selfKey;
    private final ComponentKey<SentEffects> sentKey;

    public EffectTracker(ComponentKey<VelocityState> selfKey, ComponentKey<SentEffects> sentKey) {
        this.selfKey = selfKey;
        this.sentKey = sentKey;
    }

    /** Per-player amplifiers last <em>sent</em> ({@code -1} = removed/never sent). Owner thread. */
    public static final class SentEffects {
        private final int[] amplifiers = new int[TrackedEffect.values().length];

        public SentEffects() {
            clear();
        }

        void clear() {
            Arrays.fill(amplifiers, -1);
        }

        /** @return whether the sent state changed */
        boolean set(TrackedEffect effect, int amplifier) {
            int previous = amplifiers[effect.ordinal()];
            amplifiers[effect.ordinal()] = amplifier;
            return previous != amplifier;
        }

        /** Sent amplifier, {@code -1} when removed or never sent. */
        public int amplifier(TrackedEffect effect) {
            return amplifiers[effect.ordinal()];
        }
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_EFFECT) {
            WrapperPlayServerEntityEffect packet = new WrapperPlayServerEntityEffect(event);
            if (!player.component(selfKey).isSelf(packet.getEntityId())) {
                return;
            }
            TrackedEffect effect = effectOf(packet.getPotionType());
            if (effect != null) {
                onEffect(player, event.getTasksAfterSend(), effect, packet.getEffectAmplifier());
            }
        } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
            WrapperPlayServerRemoveEntityEffect packet = new WrapperPlayServerRemoveEntityEffect(event);
            if (!player.component(selfKey).isSelf(packet.getEntityId())) {
                return;
            }
            TrackedEffect effect = effectOf(packet.getPotionType());
            if (effect != null) {
                onRemove(player, event.getTasksAfterSend(), effect);
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            onJoin(player);
        } else if (type == PacketType.Play.Server.RESPAWN) {
            // The client recreates its entity without effects (GameStateTracker clears the committed
            // state at the second ack); the server resends active effects afterwards.
            player.component(sentKey).clear();
        }
    }

    /** JOIN: a new entity without effects. */
    void onJoin(PlayerData player) {
        player.effects().clear();
        player.component(sentKey).clear();
    }

    /**
     * Primitive entry point (tests).
     *
     * @return whether an uncertainty window will be opened
     */
    boolean onEffect(PlayerData player, List<Runnable> tasksAfterSend, TrackedEffect effect, int amplifier) {
        int normalised = Math.max(0, amplifier);
        boolean changed = player.component(sentKey).set(effect, normalised);
        EffectState effects = player.effects();
        player.acks().bracket(tasksAfterSend,
                changed ? () -> effects.beginPending(effect) : null,
                () -> {
                    effects.apply(effect, normalised);
                    if (changed) {
                        effects.endPending(effect);
                    }
                });
        return changed;
    }

    /** Primitive entry point (tests). @return whether an uncertainty window will be opened */
    boolean onRemove(PlayerData player, List<Runnable> tasksAfterSend, TrackedEffect effect) {
        boolean changed = player.component(sentKey).set(effect, -1);
        EffectState effects = player.effects();
        player.acks().bracket(tasksAfterSend,
                changed ? () -> effects.beginPending(effect) : null,
                () -> {
                    effects.remove(effect);
                    if (changed) {
                        effects.endPending(effect);
                    }
                });
        return changed;
    }

    /** Maps a PacketEvents potion type to a tracked effect by its registry name; {@code null} if untracked. */
    static TrackedEffect effectOf(PotionType type) {
        if (type == null) {
            return null;
        }
        ResourceLocation name = type.getName();
        return name == null ? null : effectOf(name.toString());
    }

    /** Name form of {@link #effectOf(PotionType)} (namespaced or bare). */
    static TrackedEffect effectOf(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        int colon = key.indexOf(':');
        if (colon >= 0) {
            if (!key.startsWith("minecraft:")) {
                return null;
            }
            key = key.substring(colon + 1);
        }
        return switch (key) {
            case "speed" -> TrackedEffect.SPEED;
            case "slowness" -> TrackedEffect.SLOWNESS;
            case "haste" -> TrackedEffect.HASTE;
            case "mining_fatigue" -> TrackedEffect.MINING_FATIGUE;
            case "jump_boost" -> TrackedEffect.JUMP_BOOST;
            case "levitation" -> TrackedEffect.LEVITATION;
            case "slow_falling" -> TrackedEffect.SLOW_FALLING;
            case "dolphins_grace" -> TrackedEffect.DOLPHINS_GRACE;
            case "conduit_power" -> TrackedEffect.CONDUIT_POWER;
            case "blindness" -> TrackedEffect.BLINDNESS;
            case "darkness" -> TrackedEffect.DARKNESS;
            default -> null;
        };
    }
}
