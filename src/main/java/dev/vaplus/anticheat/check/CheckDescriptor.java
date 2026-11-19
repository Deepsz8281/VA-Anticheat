package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.player.state.ExemptionCause;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Static metadata of a check type. One descriptor is shared by all per-player instances of that
 * check; descriptors are compared by identity.
 */
public final class CheckDescriptor {

    /** Ids double as YAML keys, so dots (the Bukkit path separator) are not allowed. */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,47}");

    private final String id;
    private final String displayName;
    private final String description;
    private final CheckCategory category;
    private final boolean experimental;
    private final CheckDefaults defaults;
    private final long exemptionMask;

    private CheckDescriptor(Builder builder) {
        this.exemptionMask = builder.exemptionMask;
        this.id = builder.id;
        this.category = builder.category;
        this.displayName = builder.displayName != null ? builder.displayName : builder.id;
        this.description = builder.description != null ? builder.description : "";
        this.experimental = builder.experimental;
        this.defaults = builder.defaults != null ? builder.defaults : CheckDefaults.of(builder.category);
    }

    public static Builder builder(String id, CheckCategory category) {
        return new Builder(id, category);
    }

    /** Unique, lower-case identifier; also the key of the check's section under {@code checks:}. */
    public String id() {
        return id;
    }

    /** Human-readable name used in alerts. */
    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public CheckCategory category() {
        return category;
    }

    /** Experimental checks are expected to have a higher false-positive rate. */
    public boolean experimental() {
        return experimental;
    }

    public CheckDefaults defaults() {
        return defaults;
    }

    /** {@link ExemptionCause} bits during which {@link Check#isExempt()} is true for this check. */
    public long exemptionMask() {
        return exemptionMask;
    }

    @Override
    public String toString() {
        return id;
    }

    public static final class Builder {
        private final String id;
        private final CheckCategory category;
        private String displayName;
        private String description;
        private boolean experimental;
        private CheckDefaults defaults;
        private long exemptionMask;

        private Builder(String id, CheckCategory category) {
            Objects.requireNonNull(id, "id");
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid check id '" + id + "' (expected " + ID_PATTERN.pattern() + ")");
            }
            this.id = id;
            this.category = Objects.requireNonNull(category, "category");
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder experimental(boolean experimental) {
            this.experimental = experimental;
            return this;
        }

        public Builder defaults(CheckDefaults defaults) {
            this.defaults = defaults;
            return this;
        }

        /** Situations in which the check must not judge; applied centrally by {@link Check#isExempt()}. */
        public Builder exemptWhile(ExemptionCause... causes) {
            this.exemptionMask |= ExemptionCause.mask(causes);
            return this;
        }

        public CheckDescriptor build() {
            return new CheckDescriptor(this);
        }
    }
}
