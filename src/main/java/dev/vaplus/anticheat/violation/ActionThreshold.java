package dev.vaplus.anticheat.violation;

import java.util.List;

/**
 * Actions that fire once when the violation level rises to {@code vl}, and again every
 * {@code repeatEvery} levels above it when {@code repeatEvery > 0}.
 */
public record ActionThreshold(double vl, double repeatEvery, List<ViolationAction> actions) {

    /**
     * Tolerance for accumulated floating-point error: ten violations of weight 0.1 sum to
     * 0.9999999999999999 and must still reach a threshold of 1.0.
     */
    public static final double EPSILON = 1.0E-9;

    public ActionThreshold {
        if (!(vl > 0)) {
            throw new IllegalArgumentException("threshold vl must be positive: " + vl);
        }
        if (repeatEvery < 0) {
            throw new IllegalArgumentException("repeat must not be negative: " + repeatEvery);
        }
        actions = List.copyOf(actions);
    }

    /** Whether raising the level from {@code before} to {@code after} crosses this threshold (or a repeat step). */
    public boolean triggers(double before, double after) {
        if (!reached(after, vl) || after <= before) {
            return false;
        }
        if (!reached(before, vl)) {
            return true;
        }
        if (repeatEvery <= 0) {
            return false;
        }
        long stepBefore = (long) Math.floor((before - vl + EPSILON) / repeatEvery);
        long stepAfter = (long) Math.floor((after - vl + EPSILON) / repeatEvery);
        return stepAfter > stepBefore;
    }

    /** {@code level >= threshold} with {@link #EPSILON} tolerance. */
    public static boolean reached(double level, double threshold) {
        return level + EPSILON >= threshold;
    }
}
