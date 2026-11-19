package dev.vaplus.anticheat.storage;

import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Parses the top-level {@code storage:} section. Pure (no server access), never throws for bad user
 * input: invalid values are reported as warnings and replaced by defaults, like {@code SettingsParser}.
 */
public final class StorageSettingsParser {

    private StorageSettingsParser() {
    }

    /** @param section the {@code storage} section, or {@code null} when absent (defaults) */
    public static StorageSettings parse(ConfigurationSection section, List<String> warnings) {
        StorageSettings defaults = StorageSettings.defaults();
        if (section == null) {
            return defaults;
        }
        StorageSettings.Type type = defaults.type();
        Object rawType = section.get("type");
        if (rawType != null) {
            StorageSettings.Type parsed = StorageSettings.Type.fromKey(String.valueOf(rawType));
            if (parsed == null) {
                warnings.add("storage.type must be memory, sqlite or mysql; using " + defaults.type().key());
            } else {
                type = parsed;
            }
        }

        String file = string(section, "file", defaults.file(), warnings);
        if (!validFile(file)) {
            warnings.add("storage.file must be a relative file name inside the plugin folder; using "
                    + defaults.file());
            file = defaults.file();
        }
        String url = string(section, "url", "", warnings).trim();
        String user = string(section, "user", "", warnings);
        String password = string(section, "password", "", warnings);
        if (type == StorageSettings.Type.MYSQL) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("jdbc:mysql:") && !lower.startsWith("jdbc:mariadb:")) {
                warnings.add("storage.url must be a jdbc:mysql: or jdbc:mariadb: URL when storage.type is mysql;"
                        + " using sqlite");
                type = StorageSettings.Type.SQLITE;
            }
        }

        int retention = intValue(section, "retention-days", defaults.retentionDays(), 0,
                StorageSettings.MAX_RETENTION_DAYS, warnings);
        int queueSize = intValue(section, "queue-size", defaults.queueSize(), StorageSettings.MIN_QUEUE_SIZE,
                StorageSettings.MAX_QUEUE_SIZE, warnings);
        int batchSize = intValue(section, "batch-size", defaults.batchSize(), 1, StorageSettings.MAX_BATCH_SIZE,
                warnings);
        if (batchSize > queueSize) {
            warnings.add("storage.batch-size must not exceed storage.queue-size; using " + queueSize);
            batchSize = queueSize;
        }
        return new StorageSettings(type, file, url, user, password, retention, queueSize, batchSize);
    }

    static boolean validFile(String file) {
        if (file == null || file.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(file);
            if (path.isAbsolute() || path.getRoot() != null) {
                return false;
            }
            for (Path part : path) {
                if (part.toString().equals("..")) {
                    return false;
                }
            }
            return true;
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private static String string(ConfigurationSection section, String key, String fallback, List<String> warnings) {
        Object value = section.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        warnings.add("storage." + key + " must be a text value; using the default");
        return fallback;
    }

    private static int intValue(ConfigurationSection section, String key, int fallback, int min, int max,
                                List<String> warnings) {
        Object value = section.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            warnings.add("storage." + key + " must be a whole number; using " + fallback);
            return fallback;
        }
        long parsed = number.longValue();
        if (parsed < min || parsed > max) {
            warnings.add("storage." + key + " must be between " + min + " and " + max + "; using " + fallback);
            return fallback;
        }
        return (int) parsed;
    }
}
