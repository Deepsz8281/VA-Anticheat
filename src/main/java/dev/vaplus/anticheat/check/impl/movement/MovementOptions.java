package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckOptions;

import java.util.Locale;

/**
 * Shared helpers of the Phase-2 movement checks: option reading with validation and the
 * {@code %details%} number format. Only used on the settings and violation paths, never per packet.
 */
final class MovementOptions {

    private MovementOptions() {
    }

    /**
     * Reads a non-negative, finite tolerance from the {@code options:} block. Invalid values (negative,
     * NaN, infinite, non-numeric) fall back to {@code fallback} so a typo can never disable a check
     * silently by producing a negative limit.
     */
    static double tolerance(CheckOptions options, String key, double fallback) {
        double value = options.getDouble(key, fallback);
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    /** Four decimals, locale independent (alerts and logs must not depend on the server locale). */
    static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
