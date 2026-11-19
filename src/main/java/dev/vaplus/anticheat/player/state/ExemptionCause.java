package dev.vaplus.anticheat.player.state;

/**
 * Situations in which some checks must not judge the player. Trackers grant them; checks decide
 * which ones are relevant via {@link ExemptionState#anyActive(long)} with a precomputed mask.
 */
public enum ExemptionCause {
    JOIN,
    RESPAWN,
    WORLD_CHANGE,
    TELEPORT,
    GAMEMODE_CHANGE,
    ABILITY_CHANGE,
    ATTRIBUTE_CHANGE,
    VEHICLE,
    KNOCKBACK,
    EXPLOSION,
    PORTAL,
    ELYTRA_BOOST,
    WIND_CHARGE,
    RIPTIDE,
    BOUNCE,
    LUNGE;

    private final long bit = 1L << ordinal();

    public long bit() {
        return bit;
    }

    /** Builds a mask; call once into a {@code static final} field, never on the hot path. */
    public static long mask(ExemptionCause... causes) {
        long mask = 0L;
        for (ExemptionCause cause : causes) {
            mask |= cause.bit;
        }
        return mask;
    }
}
