package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.obfuscation.HealthIndicatorSettings;
import dev.vaplus.anticheat.violation.ActionThreshold;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsed configuration. Per-check settings are resolved on demand in this order:
 * {@code checks.<id>} section, then the descriptor's {@link CheckDefaults}. The category switch
 * under {@code categories.<category>.enabled} can disable a whole category.
 *
 * <p>Not thread-safe; used by the reloading thread only.
 */
public final class ConfigSnapshot {

    private final GlobalSettings global;
    private final Map<String, List<ActionThreshold>> profiles;
    private final Map<CheckCategory, Boolean> categories;
    private final ConfigurationSection checks;
    private final HealthIndicatorSettings healthIndicator;
    private final List<String> warnings;

    ConfigSnapshot(GlobalSettings global, Map<String, List<ActionThreshold>> profiles,
                   Map<CheckCategory, Boolean> categories, ConfigurationSection checks,
                   HealthIndicatorSettings healthIndicator, List<String> warnings) {
        this.global = global;
        this.profiles = Map.copyOf(profiles);
        this.categories = new EnumMap<>(categories);
        this.checks = checks;
        this.healthIndicator = healthIndicator;
        this.warnings = new ArrayList<>(warnings);
    }

    public GlobalSettings global() {
        return global;
    }

    /** Settings of the {@code anti-health-indicator} section. */
    public HealthIndicatorSettings healthIndicator() {
        return healthIndicator;
    }

    public boolean categoryEnabled(CheckCategory category) {
        return categories.getOrDefault(category, Boolean.TRUE);
    }

    public List<ActionThreshold> profile(String name) {
        return profiles.get(name.toLowerCase(Locale.ROOT));
    }

    /** Warnings collected while parsing and resolving; shown on reload. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Effective settings for one check. Problems are added to {@link #warnings()}. */
    public CheckSettings resolve(CheckDescriptor descriptor) {
        CheckDefaults defaults = descriptor.defaults();
        ConfigurationSection section = checks != null ? checks.getConfigurationSection(descriptor.id()) : null;
        String path = "checks." + descriptor.id();

        boolean enabled = categoryEnabled(descriptor.category())
                && (section != null ? section.getBoolean("enabled", defaults.enabled()) : defaults.enabled());
        double bufferLimit = number(section, "buffer.limit", defaults.bufferLimit(), path, true);
        double bufferDecay = number(section, "buffer.decay", defaults.bufferDecay(), path, true);
        double bufferMax = number(section, "buffer.max", defaults.bufferMax(), path, true);
        if (bufferMax > 0 && bufferMax <= bufferLimit) {
            // Keep the default head-room above the limit instead of dropping the cap: an unbounded
            // buffer would keep flagging long after the behaviour stopped.
            double headroom = Math.max(1.0, defaults.bufferMax() - defaults.bufferLimit());
            double derived = bufferLimit + headroom;
            if (section != null && section.contains("buffer.max")) {
                warnings.add(path + ".buffer.max is below buffer.limit, so the check could never flag; using " + derived);
            }
            bufferMax = derived;
        }
        double vlDecay = number(section, "vl-decay-per-minute", defaults.vlDecayPerMinute(), path, true);
        double alertVl = number(section, "alert-vl", defaults.alertVl(), path, true);
        double setbackVl = number(section, "setback-vl", defaults.setbackVl(), path, false);

        List<ActionThreshold> actions = resolveActions(section, defaults, path);
        CheckOptions options = section != null && section.isConfigurationSection("options")
                ? new CheckOptions(section.getConfigurationSection("options").getValues(false))
                : CheckOptions.EMPTY;

        return new CheckSettings(enabled, bufferLimit, bufferDecay, bufferMax, vlDecay, alertVl, setbackVl, actions, options);
    }

    /** Ids under {@code checks:} that match no registered check (likely typos). */
    public List<String> unknownCheckIds(Collection<CheckDescriptor> descriptors) {
        if (checks == null) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        for (CheckDescriptor descriptor : descriptors) {
            known.add(descriptor.id());
        }
        List<String> unknown = new ArrayList<>();
        for (String key : checks.getKeys(false)) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }

    private List<ActionThreshold> resolveActions(ConfigurationSection section, CheckDefaults defaults, String path) {
        if (section != null && section.isList("actions")) {
            return SettingsParser.parseThresholds(section.getList("actions"), path + ".actions", warnings);
        }
        String profileName = section != null ? section.getString("profile", defaults.profile()) : defaults.profile();
        List<ActionThreshold> profile = profileName != null ? profile(profileName) : null;
        if (profile == null) {
            warnings.add(path + " references unknown punishment profile '" + profileName + "'; alerts only");
            return List.of();
        }
        return profile;
    }

    private double number(ConfigurationSection section, String key, double fallback, String path, boolean nonNegative) {
        if (section == null || !section.contains(key)) {
            return fallback;
        }
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            warnings.add(path + "." + key + " must be a number; using " + fallback);
            return fallback;
        }
        double result = number.doubleValue();
        if (Double.isNaN(result) || Double.isInfinite(result) || (nonNegative && result < 0)) {
            warnings.add(path + "." + key + " is out of range; using " + fallback);
            return fallback;
        }
        return result;
    }
}
