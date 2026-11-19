package dev.vaplus.anticheat.config;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads messages.yml (creating it on first start) and exposes the current {@link MessageCatalog}.
 *
 * <p>Loading is strict and two-phased so a reload can be atomic with the configuration: {@link #prepare()}
 * parses the user file against the bundled defaults and throws a {@link LoadException} on syntax or IO
 * errors instead of silently dropping every override (which {@code YamlConfiguration.loadConfiguration}
 * would do). Only {@link #apply(MessageCatalog)} replaces the live catalogue — the caller commits it
 * once everything validated, so a broken file always keeps the previous messages.
 */
public final class MessageService {

    private static final String FILE_NAME = "messages.yml";

    /** messages.yml could not be read or parsed. */
    public static final class LoadException extends Exception {
        private static final long serialVersionUID = 1L;

        public LoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final JavaPlugin plugin;
    private volatile MessageCatalog catalog = MessageCatalog.of(null, null);

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Parses messages.yml strictly; does not change the live catalogue. */
    public MessageCatalog prepare() throws LoadException {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new LoadException(FILE_NAME + " could not be loaded: " + exception.getMessage(), exception);
        }
        return MessageCatalog.of(user, loadBundled());
    }

    /** Replaces the live catalogue. Call only after {@link #prepare()} succeeded (atomic reload). */
    public void apply(MessageCatalog catalog) {
        this.catalog = catalog;
    }

    public MessageCatalog catalog() {
        return catalog;
    }

    public String raw(String key) {
        return catalog.raw(key);
    }

    public Component render(String key, String... pairs) {
        return catalog.render(key, pairs);
    }

    public List<Component> renderLines(String key, String... pairs) {
        return catalog.renderLines(key, pairs);
    }

    public Component renderTemplate(String template, String... pairs) {
        return catalog.renderTemplate(template, pairs);
    }

    /** The bundled defaults; read errors are impossible in practice (jar resource) but reported strictly. */
    private YamlConfiguration loadBundled() throws LoadException {
        try (InputStream stream = plugin.getResource(FILE_NAME)) {
            if (stream == null) {
                return null;
            }
            Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            return parseStrict(reader, "bundled " + FILE_NAME);
        } catch (IOException exception) {
            throw new LoadException("Could not read bundled " + FILE_NAME + ": " + exception.getMessage(), exception);
        }
    }

    /** Strictly parses one YAML source; testable core used by both the user file and the bundled defaults. */
    static YamlConfiguration parseStrict(Reader reader, String source) throws LoadException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new LoadException(source + " could not be loaded: " + exception.getMessage(), exception);
        }
        return yaml;
    }

    /** Testable merge core: strict user parse over a strict bundled parse, no plugin or server needed. */
    static MessageCatalog merge(Reader userReader, Reader bundledReader) throws LoadException {
        YamlConfiguration user = userReader != null ? parseStrict(userReader, FILE_NAME) : null;
        YamlConfiguration bundled = bundledReader != null
                ? parseStrict(bundledReader, "bundled " + FILE_NAME)
                : null;
        return MessageCatalog.of(user, bundled);
    }
}
