package dev.vaplus.anticheat.violation;

import java.util.Objects;

/**
 * A configured reaction to a violation level threshold. Parsed once at config load by
 * {@link ActionParser}; templates use {@code %placeholder%} tokens.
 */
public sealed interface ViolationAction {

    /** Disconnects the player; {@code template == null} uses {@code kick-message} from messages.yml. */
    record Kick(String template) implements ViolationAction {
    }

    /** Runs a console command on the global region thread (e.g. a ban plugin's command). */
    record ConsoleCommand(String template) implements ViolationAction {
        public ConsoleCommand {
            Objects.requireNonNull(template, "template");
            if (template.isBlank()) {
                throw new IllegalArgumentException("command must not be blank");
            }
        }
    }

    /** Sends a MiniMessage line to staff with alerts enabled; {@code null} uses {@code staff-notice}. */
    record StaffNotice(String template) implements ViolationAction {
    }
}
