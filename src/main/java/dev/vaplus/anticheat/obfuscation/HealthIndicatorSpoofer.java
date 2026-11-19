package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.util.List;

/**
 * Pure rewrite rules of the anti-health-indicator prevention (no packet or player access, unit
 * tested). The {@link HealthIndicatorObfuscator} decides <em>whether</em> an entity is rewritten for a
 * viewer; this class decides <em>what</em> is rewritten.
 *
 * <h2>Entity data indexes</h2>
 * PacketEvents decodes entity data in the server's protocol, so indexes follow the server version.
 * The plugin supports 1.21 and newer only:
 * <ul>
 *   <li>{@code Entity} air supply: index 1 (int)</li>
 *   <li>{@code LivingEntity} health: index 9 (float)</li>
 *   <li>{@code Player} absorption / score: 15 / 16 before 1.21.9, 17 / 18 from 1.21.9 on (the
 *       {@code Avatar} class inserted main hand and skin parts in front of them)</li>
 * </ul>
 * Every rewrite also checks the value type, so a shifted index on an unknown future version leaves
 * the entry untouched instead of corrupting it.
 */
public final class HealthIndicatorSpoofer {

    public static final int AIR_INDEX = 1;
    /** Vanilla maximum air supply; other entities are always shown with full air. */
    public static final int FULL_AIR = 300;
    /** Vanilla iron golem max health; its cracks are derived from health / max health. */
    public static final float IRON_GOLEM_MAX_HEALTH = 100.0f;

    private HealthIndicatorSpoofer() {
    }

    /** Entity data indexes for one server version. */
    public record Indexes(int health, int absorption, int score) {

        public static Indexes forServer(ServerVersion version) {
            boolean avatar = version != null && version.isNewerThanOrEquals(ServerVersion.V_1_21_9);
            return avatar ? new Indexes(9, 17, 18) : new Indexes(9, 15, 16);
        }
    }

    /**
     * Entities whose health drives visuals or behaviour the viewer must see (wolf tails/armor, the
     * wither's armor phase, the dragon's fight phases): never rewritten.
     */
    public static boolean exempt(EntityType type) {
        return type == EntityTypes.WOLF || type == EntityTypes.WITHER || type == EntityTypes.ENDER_DRAGON;
    }

    /** Only living entities carry health at the health index; others (interaction, display, ...) use it for other data. */
    public static boolean living(EntityType type) {
        return type != null && (type == EntityTypes.PLAYER || type.isInstanceOf(EntityTypes.LIVINGENTITY));
    }

    /**
     * Rewrites the entity data entries of one living entity in place.
     *
     * @return whether any entry changed (the packet then needs re-encoding)
     */
    public static boolean spoofMetadata(List<EntityData<?>> entries, EntityType type, Indexes indexes,
                                        HealthIndicatorSettings settings) {
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        boolean player = type == EntityTypes.PLAYER;
        boolean changed = false;
        for (EntityData<?> entry : entries) {
            int index = entry.getIndex();
            Object value = entry.getValue();
            if (index == AIR_INDEX) {
                if (settings.airTicks() && value instanceof Integer air && air != FULL_AIR) {
                    set(entry, FULL_AIR);
                    changed = true;
                }
            } else if (index == indexes.health()) {
                if (settings.health() && value instanceof Float health && health > 0.0f) {
                    float spoofed = type == EntityTypes.IRON_GOLEM ? ironGolemHealth(health) : settings.nextSpoofHealth();
                    if (spoofed != health) {
                        set(entry, spoofed);
                        changed = true;
                    }
                }
            } else if (player && index == indexes.absorption()) {
                if (settings.absorption() && value instanceof Float absorption && absorption != 0.0f) {
                    set(entry, 0.0f);
                    changed = true;
                }
            } else if (player && index == indexes.score()) {
                if (settings.score() && value instanceof Integer score && score != 0) {
                    set(entry, 0);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Iron golems show cracks at 75 %, 50 % and 25 % health. The viewer keeps the crack stage (it is
     * visible in vanilla) but not the exact health: the top of the current stage is sent.
     */
    public static float ironGolemHealth(float health) {
        float ratio = health / IRON_GOLEM_MAX_HEALTH;
        if (ratio >= 0.75f) {
            return 100.0f;
        }
        if (ratio >= 0.5f) {
            return 74.0f;
        }
        if (ratio >= 0.25f) {
            return 49.0f;
        }
        return 24.0f;
    }

    /**
     * Rewrites one equipment stack in place.
     *
     * @return whether the stack changed
     */
    public static boolean spoofItem(ItemStack item, ClientVersion version, HealthIndicatorSettings settings) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        boolean changed = false;
        if (settings.stackAmount() && item.getAmount() > 1) {
            item.setAmount(1);
            changed = true;
        }
        if (settings.durability() && item.isDamageableItem() && item.getDamageValue() > 0 && !brokenGlider(item)) {
            item.setDamageValue(0);
            changed = true;
        }
        if (settings.enchantments() && !item.getEnchantments(version).isEmpty()) {
            item.setEnchantments(List.of(), version);
            // Keep the glint: an enchanted look is visible in vanilla, the enchantments are not.
            // An explicit "no glint" override set by the server is respected.
            Boolean glint = item.getComponentOr(ComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, null);
            if (glint == null) {
                item.setComponent(ComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
            }
            changed = true;
        }
        return changed;
    }

    /**
     * A broken elytra (or other glider) cannot be used; the client needs the real damage to render
     * and predict that, so it is kept.
     */
    private static boolean brokenGlider(ItemStack item) {
        boolean glider = item.getType() == ItemTypes.ELYTRA || item.hasComponent(ComponentTypes.GLIDER);
        return glider && item.getDamageValue() >= item.getMaxDamage() - 1;
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(EntityData<?> entry, T value) {
        ((EntityData<T>) entry).setValue(value);
    }
}
