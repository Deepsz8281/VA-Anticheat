package dev.vaplus.anticheat.player.state;

/**
 * Status effects that influence movement, mining or combat physics. Mapping from protocol effect
 * types to these constants is done by the effect tracker (phase 2), keeping the core independent of
 * per-version effect registries.
 */
public enum TrackedEffect {
    SPEED,
    SLOWNESS,
    HASTE,
    MINING_FATIGUE,
    JUMP_BOOST,
    LEVITATION,
    SLOW_FALLING,
    DOLPHINS_GRACE,
    CONDUIT_POWER,
    BLINDNESS,
    DARKNESS
}
