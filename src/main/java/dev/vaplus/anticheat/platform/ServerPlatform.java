package dev.vaplus.anticheat.platform;

import org.bukkit.Bukkit;

import java.util.Locale;

/**
 * The server implementation VAPlus is running on.
 *
 * <p>VAPlus is a Bukkit/Paper plugin: it compiles against {@code paper-api} and uses the Bukkit
 * scheduler, events and entities. That covers Paper and its forks (Purpur) and Folia; hybrid servers
 * that expose a Bukkit API on top of Fabric/NeoForge (Cardboard/Sinytra, Mohist, Arclight, Youer)
 * load the same jar and are reported as {@link #HYBRID}. A <em>native</em> Fabric mod would be a
 * separate Gradle/Loom project and is not what this artifact is.
 *
 * <p>Detection is string-based on purpose: it must not add a dependency on any fork's API. Folia is
 * additionally detected by class ({@code ServerTickMarkerService.detectFolia()}).
 */
public enum ServerPlatform {

    PAPER("Paper"),
    PURPUR("Purpur"),
    FOLIA("Folia"),
    HYBRID("Bukkit bridge (hybrid server)"),
    UNKNOWN("unknown server");

    private final String displayName;

    ServerPlatform(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Detects the platform from Bukkit's name/version strings and the Folia class check. */
    public static ServerPlatform detect(String bukkitName, String bukkitVersion, boolean foliaClasses) {
        String haystack = (lower(bukkitName) + " " + lower(bukkitVersion)).trim();
        if (foliaClasses || haystack.contains("folia")) {
            return FOLIA;
        }
        if (haystack.contains("purpur")) {
            return PURPUR;
        }
        if (containsAny(haystack, "fabric", "cardboard", "mohist", "arclight", "youer", "magma", "banner")) {
            return HYBRID;
        }
        if (containsAny(haystack, "paper", "spigot", "craftbukkit", "bukkit")) {
            return PAPER;
        }
        return UNKNOWN;
    }

    /** Detects the platform of the running server; call on the server thread during enable. */
    public static ServerPlatform detectCurrent() {
        return detect(Bukkit.getName(), Bukkit.getVersion(), ServerTickMarkerService.detectFolia());
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
