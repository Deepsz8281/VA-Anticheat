package dev.vaplus.anticheat.testutil.replay;

/**
 * Expected vanilla motion written down independently of the engine, straight from the published
 * formulas (MCPK Horizontal/Vertical Movement Formulas, Minecraft Wiki Entity motion table), so the
 * replay tests do not just compare the engine with itself.
 */
public final class VanillaMotion {

    /** MCPK: ground acceleration 0.1 × M × E × (0.6/S)³ with M = 0.98 (× 1.3 sprinting). */
    public static double groundAccel(double movementSpeed, double multiplier, double effects, double slipperiness) {
        double ratio = 0.6 / slipperiness;
        return movementSpeed * multiplier * effects * ratio * ratio * ratio;
    }

    /** MCPK: momentum carried to the next tick, V × S × 0.91. */
    public static double carry(double velocity, double slipperiness) {
        return velocity * slipperiness * 0.91;
    }

    /** MCPK-V / Wiki Entity: (Vy − g) × 0.98. */
    public static double fall(double vy, double gravity) {
        return (vy - gravity) * 0.98;
    }

    private VanillaMotion() {
    }
}
