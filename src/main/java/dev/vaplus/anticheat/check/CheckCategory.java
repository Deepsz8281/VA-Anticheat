package dev.vaplus.anticheat.check;

import java.util.Locale;

/** Groups checks for configuration, exemptions and default punishment profiles. */
public enum CheckCategory {
    MOVEMENT(true),
    COMBAT(false),
    PACKET(false),
    WORLD(false),
    INVENTORY(false);

    private final boolean setbackCapable;
    private final String key;

    CheckCategory(boolean setbackCapable) {
        this.setbackCapable = setbackCapable;
        this.key = name().toLowerCase(Locale.ROOT);
    }

    /** Lower-case name used in configuration files and commands. */
    public String key() {
        return key;
    }

    /** Whether violations in this category may pull the player back to the last safe position. */
    public boolean setbackCapable() {
        return setbackCapable;
    }

    /** Resolves a configuration key, or returns {@code null} when the key is unknown. */
    public static CheckCategory fromKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (CheckCategory category : values()) {
            if (category.key.equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
