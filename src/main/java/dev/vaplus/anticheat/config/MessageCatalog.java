package dev.vaplus.anticheat.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable set of MiniMessage templates from messages.yml, with bundled defaults filling gaps.
 * {@code %prefix%} inserts the raw prefix template; every other placeholder value is escaped.
 */
public final class MessageCatalog {

    private static final String PREFIX_KEY = "prefix";

    private final Map<String, List<String>> templates;
    private final String prefix;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private MessageCatalog(Map<String, List<String>> templates) {
        this.templates = Collections.unmodifiableMap(templates);
        List<String> prefixLines = templates.get(PREFIX_KEY);
        this.prefix = prefixLines != null && !prefixLines.isEmpty() ? prefixLines.get(0) : "";
    }

    /** Builds a catalogue from the user's file, using {@code defaults} for missing keys. */
    public static MessageCatalog of(ConfigurationSection user, ConfigurationSection defaults) {
        Map<String, List<String>> result = new HashMap<>();
        if (defaults != null) {
            collect(defaults, result);
        }
        if (user != null) {
            collect(user, result);
        }
        return new MessageCatalog(result);
    }

    public boolean has(String key) {
        return templates.containsKey(key);
    }

    /** Raw template (first line for list messages); the key itself if missing, so typos are visible. */
    public String raw(String key) {
        List<String> lines = templates.get(key);
        return lines == null || lines.isEmpty() ? key : lines.get(0);
    }

    public Component render(String key, String... pairs) {
        return renderTemplate(raw(key), pairs);
    }

    public List<Component> renderLines(String key, String... pairs) {
        List<String> lines = templates.get(key);
        if (lines == null) {
            return List.of(renderTemplate(key, pairs));
        }
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(renderTemplate(line, pairs));
        }
        return result;
    }

    public Component renderTemplate(String template, String... pairs) {
        String withPrefix = template.replace("%prefix%", prefix);
        return miniMessage.deserialize(TextTemplates.substitute(withPrefix, pairs, true));
    }

    private static void collect(ConfigurationSection section, Map<String, List<String>> into) {
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            if (section.isList(key)) {
                List<String> lines = section.getStringList(key);
                into.put(key, List.copyOf(lines));
            } else {
                Object value = section.get(key);
                if (value != null) {
                    into.put(key, List.of(value.toString()));
                }
            }
        }
    }
}
