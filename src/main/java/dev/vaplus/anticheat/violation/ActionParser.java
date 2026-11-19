package dev.vaplus.anticheat.violation;

import java.util.Locale;

/**
 * Parses action strings from the config:
 * <ul>
 *   <li>{@code kick} or {@code kick: <message>}</li>
 *   <li>{@code cmd: <console command>} (alias {@code command:})</li>
 *   <li>{@code notify} or {@code notify: <staff message>}</li>
 * </ul>
 */
public final class ActionParser {

    private ActionParser() {
    }

    public static ViolationAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty action");
        }
        String text = raw.trim();
        int colon = text.indexOf(':');
        String type = (colon >= 0 ? text.substring(0, colon) : text).trim().toLowerCase(Locale.ROOT);
        String argument = colon >= 0 ? text.substring(colon + 1).trim() : "";

        return switch (type) {
            case "kick" -> new ViolationAction.Kick(argument.isEmpty() ? null : argument);
            case "cmd", "command" -> {
                String command = argument.startsWith("/") ? argument.substring(1).trim() : argument;
                if (command.isEmpty()) {
                    throw new IllegalArgumentException("'" + raw + "' has no command");
                }
                yield new ViolationAction.ConsoleCommand(command);
            }
            case "notify" -> new ViolationAction.StaffNotice(argument.isEmpty() ? null : argument);
            default -> throw new IllegalArgumentException("Unknown action type '" + type + "' in '" + raw
                    + "' (expected kick, cmd or notify)");
        };
    }
}
