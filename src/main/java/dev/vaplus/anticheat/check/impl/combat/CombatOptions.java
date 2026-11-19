package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.CheckOptions;

/** Validated readers for the combat checks' options; invalid values fall back to the defaults. */
final class CombatOptions {

    private CombatOptions() {
    }

    static double positive(CheckOptions options, String key, double fallback) {
        double value = options.getDouble(key, fallback);
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    static double tolerance(CheckOptions options, String key, double fallback) {
        double value = options.getDouble(key, fallback);
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    static int atLeast(CheckOptions options, String key, int fallback, int minimum) {
        int value = options.getInt(key, fallback);
        return value >= minimum ? value : fallback;
    }

    static String number(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
