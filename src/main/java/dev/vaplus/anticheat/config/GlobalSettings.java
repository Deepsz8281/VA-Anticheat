package dev.vaplus.anticheat.config;

/**
 * Plugin-wide settings from {@code config.yml} (everything outside the per-check sections).
 * Immutable; replaced as a whole on reload.
 *
 * @param serverTickMarkers {@code latency.server-tick-markers}: send one acknowledgement marker per
 *                          server tick so client acks map onto server ticks (handoff M15)
 */
public record GlobalSettings(
        boolean alertsEnableOnJoin,
        boolean alertsToConsole,
        long alertMinIntervalMillis,
        long heartbeatIntervalMillis,
        long ackTimeoutMillis,
        boolean setbacksEnabled,
        long setbackCooldownMillis,
        boolean profilingEnabled,
        int logEntriesPerPlayer,
        int logMaxPlayers,
        boolean serverTickMarkers,
        int setbackConfirmations,
        long setbackWindowMillis) {

    public static final long MIN_HEARTBEAT_MILLIS = 10L;
    /**
     * Bounds of the ack timeout. The upper bound limits how long a client can stall the latency
     * compensation (and how much scheduled work can pile up); 15 s matches vanilla's keep-alive limit.
     */
    public static final long MIN_ACK_TIMEOUT_MILLIS = 5_000L;
    public static final long MAX_ACK_TIMEOUT_MILLIS = 120_000L;

    public GlobalSettings {
        if (alertMinIntervalMillis < 0 || setbackCooldownMillis < 0) {
            throw new IllegalArgumentException("durations must not be negative");
        }
        if (ackTimeoutMillis < MIN_ACK_TIMEOUT_MILLIS || ackTimeoutMillis > MAX_ACK_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("ack timeout must be between " + MIN_ACK_TIMEOUT_MILLIS
                    + " and " + MAX_ACK_TIMEOUT_MILLIS + "ms");
        }
        if (heartbeatIntervalMillis < MIN_HEARTBEAT_MILLIS) {
            throw new IllegalArgumentException("heartbeat interval must be at least " + MIN_HEARTBEAT_MILLIS + "ms");
        }
        if (logEntriesPerPlayer <= 0 || logMaxPlayers <= 0) {
            throw new IllegalArgumentException("log limits must be positive");
        }
        if (setbackConfirmations < 1 || setbackConfirmations > 8) {
            throw new IllegalArgumentException("setback confirmations must be between 1 and 8");
        }
    }

    /** Settings without the setback-confirmation window; one violation confirms a setback. */
    public GlobalSettings(boolean alertsEnableOnJoin, boolean alertsToConsole, long alertMinIntervalMillis,
                          long heartbeatIntervalMillis, long ackTimeoutMillis, boolean setbacksEnabled,
                          long setbackCooldownMillis, boolean profilingEnabled, int logEntriesPerPlayer,
                          int logMaxPlayers, boolean serverTickMarkers) {
        this(alertsEnableOnJoin, alertsToConsole, alertMinIntervalMillis, heartbeatIntervalMillis, ackTimeoutMillis,
                setbacksEnabled, setbackCooldownMillis, profilingEnabled, logEntriesPerPlayer, logMaxPlayers,
                serverTickMarkers, 1, DEFAULT_SETBACK_WINDOW_MILLIS);
    }

    /** Settings without the server-tick toggle (pre-M15 signature); per-tick markers stay enabled. */
    public GlobalSettings(boolean alertsEnableOnJoin, boolean alertsToConsole, long alertMinIntervalMillis,
                          long heartbeatIntervalMillis, long ackTimeoutMillis, boolean setbacksEnabled,
                          long setbackCooldownMillis, boolean profilingEnabled, int logEntriesPerPlayer,
                          int logMaxPlayers) {
        this(alertsEnableOnJoin, alertsToConsole, alertMinIntervalMillis, heartbeatIntervalMillis, ackTimeoutMillis,
                setbacksEnabled, setbackCooldownMillis, profilingEnabled, logEntriesPerPlayer, logMaxPlayers, true);
    }

    /** Default confirmation window for setbacks (plan item 9). */
    public static final long DEFAULT_SETBACK_WINDOW_MILLIS = 4_000L;
    /** Default number of violations inside the window before a setback is issued. */
    public static final int DEFAULT_SETBACK_CONFIRMATIONS = 2;

    public static GlobalSettings defaults() {
        return new GlobalSettings(true, true, 0L, 50L, 15_000L, true, 250L, true, 200, 500, true,
                DEFAULT_SETBACK_CONFIRMATIONS, DEFAULT_SETBACK_WINDOW_MILLIS);
    }
}
