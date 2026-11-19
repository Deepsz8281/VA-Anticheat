package dev.vaplus.anticheat.platform;

/** Permission nodes declared in plugin.yml. */
public final class Permissions {

    /** Receive and toggle alerts; view player info and logs. */
    public static final String ALERTS = "ac.alerts";
    /** Exempt from all checks. */
    public static final String BYPASS = "ac.bypass";
    /** Reload, profile and debug. */
    public static final String ADMIN = "ac.admin";
    /** Receive real health, equipment and game mode data of other entities (anti-health-indicator off). */
    public static final String HEALTH_INDICATOR_BYPASS = "ac.healthindicator.bypass";

    private Permissions() {
    }
}
