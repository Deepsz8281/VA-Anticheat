package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.violation.ActionThreshold;

import java.util.List;

/**
 * Effective, fully resolved settings of one check type (config merged over {@link CheckDefaults}).
 * Immutable; a reload swaps in a new instance.
 *
 * @param bufferLimit buffer value that has to be exceeded before a violation is registered
 * @param bufferDecay amount removed by {@link Check#lowerBuffer()}
 * @param bufferMax   cap of the buffer, {@code 0} for none
 * @param setbackVl   violation level from which setbacks happen, negative to disable
 */
public record CheckSettings(
        boolean enabled,
        double bufferLimit,
        double bufferDecay,
        double bufferMax,
        double vlDecayPerMinute,
        double alertVl,
        double setbackVl,
        List<ActionThreshold> actions,
        CheckOptions options) {

    public CheckSettings {
        actions = List.copyOf(actions);
        options = options != null ? options : CheckOptions.EMPTY;
    }

    public boolean setbackEnabled() {
        return setbackVl >= 0;
    }

    public static CheckSettings of(CheckDefaults defaults, List<ActionThreshold> actions) {
        return new CheckSettings(
                defaults.enabled(),
                defaults.bufferLimit(),
                defaults.bufferDecay(),
                defaults.bufferMax(),
                defaults.vlDecayPerMinute(),
                defaults.alertVl(),
                defaults.setbackVl(),
                actions,
                CheckOptions.EMPTY);
    }
}
