package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.violation.ActionParser;
import dev.vaplus.anticheat.violation.ActionThreshold;
import dev.vaplus.anticheat.violation.ViolationAction;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses config.yml into a {@link ConfigSnapshot}. Pure (no server access), never throws for bad
 * user input: invalid values are reported as warnings and replaced by defaults.
 */
public final class SettingsParser {

    private SettingsParser() {
    }

    public static ConfigSnapshot parse(ConfigurationSection root) {
        List<String> warnings = new ArrayList<>();
        GlobalSettings global = parseGlobal(root, warnings);
        Map<String, List<ActionThreshold>> profiles = parseProfiles(root.getConfigurationSection("punishment-profiles"), warnings);
        Map<CheckCategory, Boolean> categories = parseCategories(root.getConfigurationSection("categories"), warnings);
        dev.vaplus.anticheat.obfuscation.HealthIndicatorSettings healthIndicator =
                dev.vaplus.anticheat.obfuscation.HealthIndicatorSettings.parse(
                        root.getConfigurationSection("anti-health-indicator"), warnings);
        return new ConfigSnapshot(global, profiles, categories, root.getConfigurationSection("checks"),
                healthIndicator, warnings);
    }

    static GlobalSettings parseGlobal(ConfigurationSection root, List<String> warnings) {
        GlobalSettings defaults = GlobalSettings.defaults();
        boolean enableOnJoin = root.getBoolean("alerts.enable-on-join", defaults.alertsEnableOnJoin());
        boolean console = root.getBoolean("alerts.console", defaults.alertsToConsole());
        long alertInterval = nonNegative(root, "alerts.min-interval", defaults.alertMinIntervalMillis(), warnings);
        long heartbeat = longValue(root, "latency.heartbeat-interval", defaults.heartbeatIntervalMillis(), warnings);
        if (heartbeat < GlobalSettings.MIN_HEARTBEAT_MILLIS) {
            warnings.add("latency.heartbeat-interval must be at least " + GlobalSettings.MIN_HEARTBEAT_MILLIS
                    + "ms; using " + defaults.heartbeatIntervalMillis());
            heartbeat = defaults.heartbeatIntervalMillis();
        }
        long ackTimeout = longValue(root, "latency.ack-timeout", defaults.ackTimeoutMillis(), warnings);
        if (ackTimeout < GlobalSettings.MIN_ACK_TIMEOUT_MILLIS || ackTimeout > GlobalSettings.MAX_ACK_TIMEOUT_MILLIS) {
            long clamped = Math.max(GlobalSettings.MIN_ACK_TIMEOUT_MILLIS, Math.min(GlobalSettings.MAX_ACK_TIMEOUT_MILLIS, ackTimeout));
            warnings.add("latency.ack-timeout must be between " + GlobalSettings.MIN_ACK_TIMEOUT_MILLIS + " and "
                    + GlobalSettings.MAX_ACK_TIMEOUT_MILLIS + "; using " + clamped);
            ackTimeout = clamped;
        }
        boolean serverTickMarkers = root.getBoolean("latency.server-tick-markers", defaults.serverTickMarkers());
        boolean setbacks = root.getBoolean("setback.enabled", defaults.setbacksEnabled());
        long setbackCooldown = nonNegative(root, "setback.cooldown", defaults.setbackCooldownMillis(), warnings);
        int confirmations = positive(root, "setback.confirmations", defaults.setbackConfirmations(), warnings);
        if (confirmations < 1 || confirmations > 8) {
            warnings.add("setback.confirmations must be between 1 and 8; using " + defaults.setbackConfirmations());
            confirmations = defaults.setbackConfirmations();
        }
        long setbackWindow = nonNegative(root, "setback.window", defaults.setbackWindowMillis(), warnings);
        boolean profiling = root.getBoolean("profiling.enabled", defaults.profilingEnabled());
        int perPlayer = positive(root, "logging.memory-per-player", defaults.logEntriesPerPlayer(), warnings);
        int maxPlayers = positive(root, "logging.memory-max-players", defaults.logMaxPlayers(), warnings);
        return new GlobalSettings(enableOnJoin, console, alertInterval, heartbeat, ackTimeout, setbacks,
                setbackCooldown, profiling, perPlayer, maxPlayers, serverTickMarkers, confirmations, setbackWindow);
    }

    static Map<String, List<ActionThreshold>> parseProfiles(ConfigurationSection section, List<String> warnings) {
        Map<String, List<ActionThreshold>> profiles = new HashMap<>();
        if (section == null) {
            warnings.add("No punishment-profiles section; checks will only alert");
            return profiles;
        }
        for (String name : section.getKeys(false)) {
            profiles.put(name.toLowerCase(Locale.ROOT),
                    parseThresholds(section.getList(name), "punishment-profiles." + name, warnings));
        }
        return profiles;
    }

    /** Parses a list of {@code {vl, repeat, run}} maps. */
    static List<ActionThreshold> parseThresholds(List<?> entries, String path, List<String> warnings) {
        List<ActionThreshold> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        int index = 0;
        for (Object entry : entries) {
            String where = path + "[" + index++ + "]";
            if (!(entry instanceof Map<?, ?> map)) {
                warnings.add(where + " must be a map with 'vl' and 'run'");
                continue;
            }
            if (!(map.get("vl") instanceof Number vlNumber) || !(vlNumber.doubleValue() > 0)) {
                warnings.add(where + ".vl must be a positive number");
                continue;
            }
            double repeat = 0.0;
            Object repeatValue = map.get("repeat");
            if (repeatValue != null) {
                if (repeatValue instanceof Number number && number.doubleValue() >= 0) {
                    repeat = number.doubleValue();
                } else {
                    warnings.add(where + ".repeat must be a number >= 0; ignoring it");
                }
            }
            List<ViolationAction> actions = new ArrayList<>();
            Object run = map.get("run");
            List<?> rawActions = run instanceof List<?> list ? list : run != null ? List.of(run) : List.of();
            for (Object rawAction : rawActions) {
                try {
                    actions.add(ActionParser.parse(String.valueOf(rawAction)));
                } catch (IllegalArgumentException exception) {
                    warnings.add(where + ": " + exception.getMessage());
                }
            }
            if (actions.isEmpty()) {
                warnings.add(where + " has no valid actions");
                continue;
            }
            result.add(new ActionThreshold(vlNumber.doubleValue(), repeat, actions));
        }
        result.sort((a, b) -> Double.compare(a.vl(), b.vl()));
        return List.copyOf(result);
    }

    static Map<CheckCategory, Boolean> parseCategories(ConfigurationSection section, List<String> warnings) {
        Map<CheckCategory, Boolean> result = new EnumMap<>(CheckCategory.class);
        for (CheckCategory category : CheckCategory.values()) {
            result.put(category, Boolean.TRUE);
        }
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            CheckCategory category = CheckCategory.fromKey(key);
            if (category == null) {
                warnings.add("Unknown category '" + key + "' under categories");
                continue;
            }
            // Accept both "movement: {enabled: false}" and the shorthand "movement: false".
            Object value = section.get(key);
            if (value instanceof Boolean enabled) {
                result.put(category, enabled);
            } else if (section.isConfigurationSection(key)) {
                Object enabled = section.get(key + ".enabled");
                if (enabled == null || enabled instanceof Boolean) {
                    result.put(category, enabled == null || (Boolean) enabled);
                } else {
                    warnings.add("categories." + key + ".enabled must be true or false; keeping it enabled");
                }
            } else {
                warnings.add("categories." + key + " must be true/false or a section with 'enabled'; keeping it enabled");
            }
        }
        return result;
    }

    private static long nonNegative(ConfigurationSection root, String path, long fallback, List<String> warnings) {
        long value = longValue(root, path, fallback, warnings);
        if (value < 0) {
            warnings.add(path + " must not be negative; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static int positive(ConfigurationSection root, String path, int fallback, List<String> warnings) {
        long value = longValue(root, path, fallback, warnings);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            warnings.add(path + " must be a positive integer; using " + fallback);
            return fallback;
        }
        return (int) value;
    }

    /** Reads an integral number, warning (instead of silently defaulting) when the value is not one. */
    private static long longValue(ConfigurationSection root, String path, long fallback, List<String> warnings) {
        Object value = root.get(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.longValue();
        }
        warnings.add(path + " must be a whole number; using " + fallback);
        return fallback;
    }
}
