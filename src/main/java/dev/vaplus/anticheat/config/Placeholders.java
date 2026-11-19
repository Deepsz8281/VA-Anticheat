package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;

import java.util.Locale;

/** Standard placeholder sets for alerts, notices, kick messages and punishment commands. */
public final class Placeholders {

    private Placeholders() {
    }

    public static String[] forViolation(PlayerData player, Check check, double violationLevel, String details) {
        long ping = player.acks().smoothedRttMillis();
        return new String[]{
                "player", player.name(),
                "uuid", player.uuid().toString(),
                "check", check.descriptor().displayName(),
                "check_id", check.descriptor().id(),
                "category", check.descriptor().category().key(),
                "vl", formatLevel(violationLevel),
                "count", Integer.toString(check.violationCount()),
                "ping", ping < 0 ? "?" : Long.toString(ping),
                "version", player.clientVersion().getReleaseName(),
                "details", details != null ? details : ""
        };
    }

    public static String[] forPlayer(PlayerData player) {
        long ping = player.acks().smoothedRttMillis();
        return new String[]{
                "player", player.name(),
                "uuid", player.uuid().toString(),
                "ping", ping < 0 ? "?" : Long.toString(ping),
                "version", player.clientVersion().getReleaseName()
        };
    }

    public static String formatLevel(double level) {
        return String.format(Locale.ROOT, "%.1f", level);
    }
}
