package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Vanilla hitbox sizes (width x height, adult, scale 1) of the entities combat checks judge.
 *
 * <p>Values are the {@code EntityType.Builder#dimensions} of the vanilla 1.21 registry (read from
 * the client source, UNCONFIRMED against 26.x). Only entities that a hacked client realistically
 * attacks are listed: mobs, animals, golems, end crystals and armor stands. Everything else
 * (items, projectiles, minecarts, ...) is not tracked, so it never produces a combat verdict.
 *
 * <p>Size-variable entities (slimes, magma cubes, phantoms) and the multipart ender dragon are left
 * out on purpose: an unknown box must not be judged. Baby variants and the {@code scale} attribute
 * are not tracked either, so a baby or scaled-down mob is judged against the adult box. That box is
 * larger than the real one, which only makes reach/hitbox verdicts more lenient; the angle-based
 * checks aim at the box centre and keep enough tolerance for the difference.
 */
public final class EntityDimensions {

    /** Player hitbox (standing). */
    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double PLAYER_SNEAKING_HEIGHT = 1.5;

    private static final Map<String, double[]> SIZES = new HashMap<>();

    static {
        // humanoid mobs
        put(0.6, 1.95, "zombie", "husk", "drowned", "zombie_villager", "villager", "witch",
                "pillager", "vindicator", "evoker", "illusioner", "wandering_trader", "piglin",
                "piglin_brute", "zombified_piglin");
        put(0.6, 1.99, "skeleton", "stray", "bogged");
        put(0.7, 2.4, "wither_skeleton");
        put(0.6, 1.7, "creeper");
        put(0.6, 1.8, "blaze");
        put(0.6, 1.77, "breeze");
        put(0.6, 2.9, "enderman");
        put(0.9, 2.9, "warden");
        put(0.9, 2.7, "creaking");
        put(1.4, 0.9, "spider");
        put(0.7, 0.5, "cave_spider");
        put(0.4, 0.3, "silverfish", "endermite");
        put(0.4, 0.8, "vex");
        put(0.35, 0.6, "allay");
        put(1.95, 2.2, "ravager");
        put(4.0, 4.0, "ghast");
        put(0.85, 0.85, "guardian");
        put(1.9975, 1.9975, "elder_guardian");
        put(1.0, 1.0, "shulker");
        put(0.9, 3.5, "wither");
        put(1.3965, 1.4, "hoglin", "zoglin");
        // golems
        put(1.4, 2.7, "iron_golem");
        put(0.7, 1.9, "snow_golem");
        // animals
        put(0.9, 1.4, "cow", "mooshroom");
        put(0.9, 0.9, "pig");
        put(0.9, 1.3, "sheep", "goat");
        put(0.4, 0.7, "chicken");
        put(1.3965, 1.6, "horse", "mule", "skeleton_horse", "zombie_horse");
        put(1.3965, 1.5, "donkey");
        put(0.9, 1.87, "llama", "trader_llama");
        put(0.6, 0.85, "wolf");
        put(0.6, 0.7, "cat", "ocelot", "fox");
        put(0.4, 0.5, "rabbit");
        put(1.4, 1.4, "polar_bear");
        put(1.3, 1.25, "panda");
        put(0.9, 1.7, "strider");
        put(1.2, 0.4, "turtle");
        put(0.9, 0.6, "dolphin");
        put(0.8, 0.8, "squid", "glow_squid");
        put(0.5, 0.9, "bat", "parrot");
        put(0.7, 0.6, "bee");
        put(0.75, 0.42, "axolotl");
        put(0.5, 0.5, "frog");
        put(0.7, 0.65, "armadillo");
        put(1.7, 2.375, "camel");
        put(1.9, 1.75, "sniffer");
        put(0.7, 0.4, "cod", "salmon", "pufferfish");
        put(0.5, 0.4, "tropical_fish");
        put(0.6, 0.6, "tadpole");
        // attackable non-living entities that combat cheats target
        put(2.0, 2.0, "end_crystal");
        put(0.5, 1.975, "armor_stand");
    }

    private EntityDimensions() {
    }

    private static void put(double width, double height, String... keys) {
        for (String key : keys) {
            SIZES.put(key, new double[]{width, height});
        }
    }

    /**
     * Width/height of a vanilla entity id path ({@code "zombie"}), or {@code null} if the entity is
     * not judged. The returned array must not be modified.
     */
    public static double[] of(String key) {
        if (key == null) {
            return null;
        }
        return SIZES.get(key.toLowerCase(Locale.ROOT));
    }

    /** Width/height of a PacketEvents entity type, or {@code null} if it is not judged. */
    public static double[] of(EntityType type) {
        if (type == null || type.getName() == null) {
            return null;
        }
        return of(type.getName().getKey());
    }
}
