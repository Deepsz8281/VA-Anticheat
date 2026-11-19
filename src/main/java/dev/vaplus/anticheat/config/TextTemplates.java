package dev.vaplus.anticheat.config;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * {@code %key%} placeholder substitution. Used for MiniMessage templates (values are escaped so a
 * player-controlled string can never inject tags) and for console commands (values inserted raw).
 */
public final class TextTemplates {

    private TextTemplates() {
    }

    /**
     * @param pairs alternating keys and values, e.g. {@code "player", "Steve", "vl", "3.0"}
     */
    public static String substitute(String template, String[] pairs, boolean escapeForMiniMessage) {
        if (template == null || template.indexOf('%') < 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        int length = template.length();
        int i = 0;
        while (i < length) {
            char current = template.charAt(i);
            if (current == '%') {
                int end = template.indexOf('%', i + 1);
                if (end > i + 1) {
                    String value = lookup(pairs, template, i + 1, end);
                    if (value != null) {
                        out.append(escapeForMiniMessage ? escape(value) : value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(current);
            i++;
        }
        return out.toString();
    }

    /**
     * Escapes MiniMessage tags and replaces the characters that are special inside quoted tag
     * arguments: quotes would terminate the argument, and a backslash pair collapses to a single
     * backslash there, which would un-escape a following tag in nested (hover) parsing.
     */
    public static String escape(String value) {
        String neutralised = value.replace('\\', '∖').replace('\'', '’').replace('"', '”');
        return MiniMessage.miniMessage().escapeTags(neutralised);
    }

    private static String lookup(String[] pairs, String template, int keyStart, int keyEnd) {
        int keyLength = keyEnd - keyStart;
        for (int p = 0; p + 1 < pairs.length; p += 2) {
            String key = pairs[p];
            if (key.length() == keyLength && template.regionMatches(keyStart, key, 0, keyLength)) {
                return pairs[p + 1] != null ? pairs[p + 1] : "";
            }
        }
        return null;
    }
}
