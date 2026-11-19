package dev.vaplus.anticheat.check.impl.combat;

/**
 * Estimates the player's mouse rotation grid from rotation deltas.
 *
 * <p>Vanilla turns the camera by {@code cursorDelta * (s * 0.6 + 0.2)^3 * 8 * 0.15} degrees per
 * mouse count ({@code s} = mouse sensitivity, cursor deltas are whole mouse counts on Windows and
 * on X11). Every yaw/pitch change a vanilla client sends is therefore an integer multiple of one
 * constant {@code grid} (0.0096° at 0% up to 1.2° at 200% sensitivity). Rotations computed by an
 * aimbot or killaura ({@code atan2} to the target) land anywhere and do not fit that grid.
 *
 * <p>Estimation: the greatest common divisor of two consecutive deltas is {@code grid * gcd(k1, k2)},
 * most often the grid itself. The largest such candidate that divides at least
 * {@code support} of the stored deltas wins and is refined by averaging {@code v / round(v / grid)}
 * over the deltas it explains. Without a candidate (cinematic camera, controllers, touch input,
 * sensitivity just changed) the grid is unknown and nothing is judged.
 */
final class SensitivityEstimator {

    static final int CAPACITY = 40;
    static final int MIN_SAMPLES = 20;
    static final int RECOMPUTE_EVERY = 5;
    /** A delta fits a candidate when its multiple is this close to an integer. */
    static final double FIT_TOLERANCE = 0.05;

    private final double[] samples = new double[CAPACITY];
    private int count;
    private int position;
    private int sinceRecompute;
    private double grid = Double.NaN;

    private double minGrid;
    private double support;

    SensitivityEstimator(double minGrid, double support) {
        configure(minGrid, support);
    }

    void configure(double minGrid, double support) {
        this.minGrid = minGrid;
        this.support = support;
    }

    /** Estimated grid in degrees, or {@code NaN} while unknown. */
    double grid() {
        return grid;
    }

    boolean known() {
        return !Double.isNaN(grid);
    }

    int samples() {
        return count;
    }

    void reset() {
        count = 0;
        position = 0;
        sinceRecompute = 0;
        grid = Double.NaN;
    }

    /** Adds an absolute, non-zero rotation delta of a rotation that is known to be legitimate input. */
    void add(double delta) {
        samples[position] = delta;
        position = (position + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        if (++sinceRecompute >= RECOMPUTE_EVERY && count >= MIN_SAMPLES) {
            sinceRecompute = 0;
            grid = estimate();
        }
    }

    private double estimate() {
        double tolerance = minGrid * 0.5;
        double best = Double.NaN;
        int needed = (int) Math.ceil(support * count);
        for (int i = 1; i < count; i++) {
            double candidate = gcd(samples[i - 1], samples[i], tolerance);
            if (!(candidate >= minGrid) || (!Double.isNaN(best) && candidate <= best * (1.0 + FIT_TOLERANCE))) {
                continue;
            }
            if (fits(candidate) >= needed) {
                best = candidate;
            }
        }
        return Double.isNaN(best) ? Double.NaN : refine(best);
    }

    private int fits(double candidate) {
        int fitting = 0;
        for (int i = 0; i < count; i++) {
            double multiple = samples[i] / candidate;
            if (multiple >= 0.5 && Math.abs(multiple - Math.rint(multiple)) < FIT_TOLERANCE) {
                fitting++;
            }
        }
        return fitting;
    }

    private double refine(double candidate) {
        double sum = 0.0;
        double weight = 0.0;
        for (int i = 0; i < count; i++) {
            double multiple = samples[i] / candidate;
            double steps = Math.rint(multiple);
            if (steps >= 1.0 && Math.abs(multiple - steps) < FIT_TOLERANCE) {
                // Weighted by the step count: large deltas carry the most precise grid information.
                sum += samples[i];
                weight += steps;
            }
        }
        return weight > 0.0 ? sum / weight : candidate;
    }

    /** Floating-point Euclid; stops once the remainder is below {@code tolerance}. */
    static double gcd(double a, double b, double tolerance) {
        double x = Math.max(Math.abs(a), Math.abs(b));
        double y = Math.min(Math.abs(a), Math.abs(b));
        int guard = 0;
        while (y > tolerance && guard++ < 64) {
            double remainder = x % y;
            x = y;
            y = remainder;
            // A remainder just below y is float noise of an exact multiple.
            if (y > x - tolerance) {
                y = 0.0;
            }
        }
        return x;
    }

    /** Distance of {@code delta / grid} from the nearest whole multiple (0 = on the grid). */
    static double residual(double delta, double grid) {
        double multiple = Math.abs(delta) / grid;
        if (multiple < 0.5) {
            // Smaller than one mouse count: impossible for raw mouse input.
            return 0.5;
        }
        return Math.abs(multiple - Math.rint(multiple));
    }
}
