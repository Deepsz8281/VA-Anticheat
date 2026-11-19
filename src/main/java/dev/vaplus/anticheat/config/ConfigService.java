package dev.vaplus.anticheat.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Loads config.yml from the data folder (writing the bundled default on first start).
 *
 * <p>Deliberately avoids {@code JavaPlugin#getConfig()}: Bukkit swallows YAML syntax errors there
 * (yielding an empty configuration, i.e. no punishments and every check re-enabled) and its
 * defaults mechanism behaves inconsistently between getters. Missing keys fall back to code
 * defaults instead, and a broken file is reported to the caller.
 */
public final class ConfigService {

    private static final String FILE_NAME = "config.yml";

    /** The configuration file exists but cannot be read or parsed. */
    public static final class LoadException extends Exception {
        private static final long serialVersionUID = 1L;

        public LoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final JavaPlugin plugin;

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public ConfigSnapshot load() throws LoadException {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new LoadException(FILE_NAME + " could not be loaded: " + exception.getMessage(), exception);
        }
        return SettingsParser.parse(yaml);
    }

    /** The configuration shipped inside the jar; used when the user's file is broken on startup. */
    public ConfigSnapshot loadBundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = plugin.getResource(FILE_NAME)) {
            if (stream != null) {
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    yaml.load(reader);
                }
            }
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Bundled " + FILE_NAME + " is invalid", exception);
        }
        return SettingsParser.parse(yaml);
    }
}
