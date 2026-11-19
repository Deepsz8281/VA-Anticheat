package dev.vaplus.anticheat.check.impl.world;

import dev.vaplus.anticheat.check.CheckOptions;

import java.util.Locale;

/**
 * Option reading shared by the visibility checks: every numeric value is validated so a typo
 * cannot silently disable a check (a negative tolerance would let everything through), and numbers
 * are formatted locale independent because alerts and logs must not depend on the server locale.
 * Only used on the settings and violation paths, never per packet.
 */
final class VisibilityOptions {

    private VisibilityOptions() {
    }

    /** Non-negative, finite value; anything else falls back. */
    static double tolerance(CheckOptions options, String key, double fallback) {
        double value = options.getDouble(key, fallback);
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    /** Strictly positive, finite value; anything else falls back. */
    static double positive(CheckOptions options, String key, double fallback) {
        double value = options.getDouble(key, fallback);
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    /** Integer of at least {@code minimum}; anything else falls back. */
    static int atLeast(CheckOptions options, String key, int fallback, int minimum) {
        int value = options.getInt(key, fallback);
        return value >= minimum ? value : fallback;
    }

    static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
