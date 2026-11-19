package dev.vaplus.anticheat.profiling;

import java.util.Locale;

/**
 * Runtime performance gate (plan item 10): when the server is under sustained load, the anticheat
 * stops making things worse instead of adding to the problem.
 *
 * <p>While {@link #degraded()} is true, violations are still detected, logged, alerted and counted,
 * but configured punishments and setbacks are not executed ({@code shadowOnly}) and the X-Ray
 * pre-obfuscation is paused ({@code preventionPaused}; the client then receives vanilla chunks).
 * Both are reversible: the watchdog calls {@link #recover()} as soon as the load is back to normal.
 *
 * <p>The flags are volatile and written only by the watchdog task, so owner threads read them
 * without synchronisation.
 */
public final class PerformanceGovernor {

    /** Per-player-tick cost above which the governor degrades, in microseconds. */
    public static final double DEGRADE_MICROS_PER_PLAYER_TICK = 1_500.0;
    /** TPS below which the governor degrades (0 or unknown disables the TPS rule). */
    public static final double DEGRADE_TPS = 15.0;

    private volatile boolean degraded;
    private volatile String reason = "";

    public boolean degraded() {
        return degraded;
    }

    /** Human-readable cause of the current degradation, empty when healthy. */
    public String reason() {
        return reason;
    }

    /** Violations keep their alerts/logs but no setbacks or punishments are executed. */
    public boolean shadowOnly() {
        return degraded;
    }

    /** X-Ray chunk rewriting is skipped while degraded (vanilla chunks are cheaper to send). */
    public boolean preventionPaused() {
        return degraded;
    }

    /** Watchdog: enters the degraded state with the measured cause. */
    public void degrade(String cause) {
        reason = cause != null ? cause : "";
        degraded = true;
    }

    /** Watchdog: leaves the degraded state. */
    public void recover() {
        degraded = false;
        reason = "";
    }

    @Override
    public String toString() {
        return degraded ? "PerformanceGovernor[degraded: " + reason + "]" : "PerformanceGovernor[healthy]";
    }

    /** Compact description for commands and logs. */
    public String describe() {
        if (!degraded) {
            return String.format(Locale.ROOT, "healthy (budget %.0f us/tick)", DEGRADE_MICROS_PER_PLAYER_TICK);
        }
        return String.format(Locale.ROOT, "degraded: %s (alerts only, prevention paused)", reason);
    }
}
