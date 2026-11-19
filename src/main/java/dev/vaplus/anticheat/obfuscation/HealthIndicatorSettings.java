package dev.vaplus.anticheat.obfuscation;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Settings of the anti-health-indicator prevention (top-level {@code anti-health-indicator:} section).
 * Immutable; replaced as a whole on reload.
 *
 * @param enabled          master switch of every rewrite below
 * @param playersOnly      only rewrite the entity data of other players (mobs keep their real data)
 * @param health           hide the health of other living entities
 * @param spoofHealth      health value sent instead of the real one (upper bound when randomized)
 * @param randomize        send a random value in {@code [1, spoofHealth]} instead of a fixed one
 * @param absorption       hide the absorption hearts of other players
 * @param score            hide the score (experience) of other players
 * @param airTicks         hide the remaining air of other entities
 * @param items            master switch of the equipment rewrites
 * @param stackAmount      send equipment stacks with an amount of 1
 * @param durability       send equipment undamaged
 * @param enchantments     send equipment without enchantments (the glint stays visible)
 * @param gamemode         show other players as survival in the tab list (spectators stay visible)
 * @param heartsScoreboard hide the values of scoreboard objectives rendered as hearts
 * @param foodSaturation   hide the viewer's own food saturation
 */
public record HealthIndicatorSettings(
        boolean enabled,
        boolean playersOnly,
        boolean health,
        float spoofHealth,
        boolean randomize,
        boolean absorption,
        boolean score,
        boolean airTicks,
        boolean items,
        boolean stackAmount,
        boolean durability,
        boolean enchantments,
        boolean gamemode,
        boolean heartsScoreboard,
        boolean foodSaturation) {

    public static final float DEFAULT_SPOOF_HEALTH = 20.0f;
    /** Largest accepted spoof value; far above every vanilla max health and still a sane float. */
    public static final float MAX_SPOOF_HEALTH = 1024.0f;

    public HealthIndicatorSettings {
        if (!(spoofHealth >= 1.0f) || spoofHealth > MAX_SPOOF_HEALTH) {
            throw new IllegalArgumentException("spoof health must be between 1 and " + MAX_SPOOF_HEALTH);
        }
    }

    public static HealthIndicatorSettings defaults() {
        return new HealthIndicatorSettings(true, false, true, DEFAULT_SPOOF_HEALTH, true, true, true, true,
                true, true, true, true, true, true, false);
    }

    public static HealthIndicatorSettings disabled() {
        HealthIndicatorSettings d = defaults();
        return new HealthIndicatorSettings(false, d.playersOnly, d.health, d.spoofHealth, d.randomize, d.absorption,
                d.score, d.airTicks, d.items, d.stackAmount, d.durability, d.enchantments, d.gamemode,
                d.heartsScoreboard, d.foodSaturation);
    }

    /** The health value to send for one living entity; a fresh random value per call when randomized. */
    public float nextSpoofHealth() {
        if (!randomize || spoofHealth <= 1.0f) {
            return spoofHealth;
        }
        return 1.0f + ThreadLocalRandom.current().nextFloat() * (spoofHealth - 1.0f);
    }

    /**
     * Parses the {@code anti-health-indicator} section. Pure, never throws for bad user input:
     * invalid values are reported as warnings and replaced by defaults.
     *
     * @param section the section, or {@code null} when absent (defaults)
     */
    public static HealthIndicatorSettings parse(ConfigurationSection section, List<String> warnings) {
        HealthIndicatorSettings d = defaults();
        if (section == null) {
            return d;
        }
        String path = "anti-health-indicator.";
        float spoof = d.spoofHealth;
        if (section.contains("health.spoof-value")) {
            Object raw = section.get("health.spoof-value");
            if (raw instanceof Number number && number.doubleValue() >= 1.0 && number.doubleValue() <= MAX_SPOOF_HEALTH) {
                spoof = number.floatValue();
            } else {
                warnings.add(path + "health.spoof-value must be a number between 1 and " + (int) MAX_SPOOF_HEALTH
                        + "; using " + (int) d.spoofHealth);
            }
        }
        return new HealthIndicatorSettings(
                section.getBoolean("enabled", d.enabled),
                section.getBoolean("players-only", d.playersOnly),
                section.getBoolean("health.enabled", d.health),
                spoof,
                section.getBoolean("health.randomize", d.randomize),
                section.getBoolean("absorption", d.absorption),
                section.getBoolean("experience", d.score),
                section.getBoolean("air-ticks", d.airTicks),
                section.getBoolean("items.enabled", d.items),
                section.getBoolean("items.stack-amount", d.stackAmount),
                section.getBoolean("items.durability", d.durability),
                section.getBoolean("items.enchantments", d.enchantments),
                section.getBoolean("gamemode", d.gamemode),
                section.getBoolean("hearts-scoreboard", d.heartsScoreboard),
                section.getBoolean("food-saturation", d.foodSaturation));
    }
}
