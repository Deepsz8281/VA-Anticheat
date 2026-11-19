package dev.vaplus.anticheat.check;

import java.util.Objects;

/**
 * Code-side baseline settings of a check. Values from {@code config.yml} override these; anything
 * missing from the config falls back to them, so a check works even without a config section.
 *
 * @param bufferMax upper bound of the buffer; {@code 0} means unbounded
 * @param setbackVl violation level from which movement setbacks happen; negative disables setbacks
 * @param profile   name of the punishment profile used when the config does not specify one
 */
public record CheckDefaults(
        boolean enabled,
        double bufferLimit,
        double bufferDecay,
        double bufferMax,
        double vlDecayPerMinute,
        double alertVl,
        double setbackVl,
        String profile) {

    public CheckDefaults {
        Objects.requireNonNull(profile, "profile");
        if (bufferLimit < 0 || bufferDecay < 0 || bufferMax < 0 || vlDecayPerMinute < 0 || alertVl < 0) {
            throw new IllegalArgumentException("buffer, decay and alert values must not be negative");
        }
        if (bufferMax > 0 && bufferMax <= bufferLimit) {
            // raiseBuffer caps at max and flags only above limit, so such a check could never flag.
            throw new IllegalArgumentException("bufferMax must be 0 (unbounded) or greater than bufferLimit");
        }
    }

    /** Sensible defaults for a category; individual checks adjust them with the {@code with*} methods. */
    public static CheckDefaults of(CheckCategory category) {
        return switch (category) {
            case MOVEMENT -> new CheckDefaults(true, 2.0, 0.05, 8.0, 6.0, 1.0, 1.0, "movement");
            case COMBAT -> new CheckDefaults(true, 3.0, 0.1, 10.0, 3.0, 1.0, -1.0, "combat");
            case PACKET -> new CheckDefaults(true, 0.0, 0.0, 0.0, 2.0, 1.0, -1.0, "packet");
            case WORLD -> new CheckDefaults(true, 2.0, 0.1, 8.0, 4.0, 1.0, -1.0, "world");
            case INVENTORY -> new CheckDefaults(true, 2.0, 0.1, 8.0, 4.0, 1.0, -1.0, "inventory");
        };
    }

    public CheckDefaults withEnabled(boolean value) {
        return new CheckDefaults(value, bufferLimit, bufferDecay, bufferMax, vlDecayPerMinute, alertVl, setbackVl, profile);
    }

    public CheckDefaults withBufferLimit(double value) {
        return new CheckDefaults(enabled, value, bufferDecay, bufferMax, vlDecayPerMinute, alertVl, setbackVl, profile);
    }

    public CheckDefaults withBufferDecay(double value) {
        return new CheckDefaults(enabled, bufferLimit, value, bufferMax, vlDecayPerMinute, alertVl, setbackVl, profile);
    }

    public CheckDefaults withBufferMax(double value) {
        return new CheckDefaults(enabled, bufferLimit, bufferDecay, value, vlDecayPerMinute, alertVl, setbackVl, profile);
    }

    public CheckDefaults withVlDecayPerMinute(double value) {
        return new CheckDefaults(enabled, bufferLimit, bufferDecay, bufferMax, value, alertVl, setbackVl, profile);
    }

    public CheckDefaults withAlertVl(double value) {
        return new CheckDefaults(enabled, bufferLimit, bufferDecay, bufferMax, vlDecayPerMinute, value, setbackVl, profile);
    }

    public CheckDefaults withSetbackVl(double value) {
        return new CheckDefaults(enabled, bufferLimit, bufferDecay, bufferMax, vlDecayPerMinute, alertVl, value, profile);
    }

    public CheckDefaults withProfile(String value) {
        return new CheckDefaults(enabled, bufferLimit, bufferDecay, bufferMax, vlDecayPerMinute, alertVl, setbackVl, value);
    }
}
