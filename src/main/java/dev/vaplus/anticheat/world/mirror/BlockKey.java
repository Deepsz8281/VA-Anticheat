package dev.vaplus.anticheat.world.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, fully namespaced block identifier, independent of protocol registry numbers.
 * State properties (e.g. bubble-column direction) belong in {@link BlockProperties}, not this key.
 */
public record BlockKey(String value) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    /** Mirror-only sentinel, never a claimed Minecraft registry entry. */
    public static final BlockKey UNKNOWN = new BlockKey("vaplus:unknown");

    public BlockKey {
        Objects.requireNonNull(value, "value");
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("not a namespaced block identifier: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
