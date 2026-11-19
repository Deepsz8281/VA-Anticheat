package dev.vaplus.anticheat.testutil;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** YAML loading helpers that work without a Bukkit server. */
public final class Configs {

    private Configs() {
    }

    public static YamlConfiguration fromString(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml);
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("invalid test YAML", exception);
        }
        return configuration;
    }

    /** The config.yml shipped in the jar, loaded from the classpath. */
    public static YamlConfiguration bundledConfig() {
        return fromResource("/config.yml");
    }

    /** The messages.yml shipped in the jar, loaded from the classpath. */
    public static YamlConfiguration bundledMessages() {
        return fromResource("/messages.yml");
    }

    private static YamlConfiguration fromResource(String path) {
        try (InputStream stream = Configs.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                YamlConfiguration configuration = new YamlConfiguration();
                configuration.load(reader);
                return configuration;
            }
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("cannot load " + path, exception);
        }
    }
}
