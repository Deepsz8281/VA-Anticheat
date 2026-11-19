package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.RotationCheck;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

/**
 * Baritone: pitch-only rotations off every mouse grid (Grim Baritone).
 *
 * <p>Path bots such as Baritone turn the camera by interpolating the pitch in small steps while the
 * yaw stays exactly the same. A mouse moves the pitch in multiples of the sensitivity grid (at least
 * 0.0096 degrees at 0% sensitivity, see {@link SensitivityEstimator}). The pitch changes are fed into
 * a grid estimator with Grim's minimum divisor; when no grid explains them and more than
 * {@code min-streak} consecutive rotations keep the yaw exactly while moving the pitch by less than
 * one degree, the check flags.
 *
 * <p>Known false positives: the cinematic camera and controller mods also leave the grid. Experimental.
 */
public final class BaritoneCheck extends Check implements RotationCheck {

    /** Smallest grid a vanilla mouse can produce, minus a margin (Grim MINIMUM_DIVISOR). */
    static final double MIN_GRID = Math.pow(0.2, 3) * 8.0 * 0.15 - 1.0E-3;
    static final double GRID_SUPPORT = 0.75;
    public static final int DEFAULT_MIN_STREAK = 8;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("baritone", CheckCategory.COMBAT)
            .displayName("Baritone")
            .description("Bot-like pitch-only rotations off the mouse grid")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE)
            .build();

    private final SensitivityEstimator pitchGrid = new SensitivityEstimator(MIN_GRID, GRID_SUPPORT);
    private int minStreak = DEFAULT_MIN_STREAK;
    private int streak;

    public BaritoneCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        int value = options.getInt("min-streak", DEFAULT_MIN_STREAK);
        minStreak = value >= 3 && value <= 200 ? value : DEFAULT_MIN_STREAK;
    }

    @Override
    protected void onReset() {
        pitchGrid.reset();
        streak = 0;
    }

    @Override
    public void onRotation(LookChange change) {
        if (change.teleport() || player.vehicle().inVehicle()) {
            streak = 0;
            return;
        }
        rotation(change.toYaw() - change.fromYaw(), change.fromPitch(), change.toPitch());
    }

    /** Decision logic on primitives (tests). */
    void rotation(float deltaYaw, float fromPitch, float toPitch) {
        float deltaPitch = Math.abs(toPitch - fromPitch);
        if (deltaPitch > 0.0f) {
            pitchGrid.add(deltaPitch);
        }
        if (deltaYaw != 0.0f || deltaPitch <= 0.0f || deltaPitch >= 1.0f || Math.abs(toPitch) == 90.0f) {
            return;
        }
        if (pitchGrid.samples() < SensitivityEstimator.MIN_SAMPLES || pitchGrid.known()) {
            streak = 0;
            return;
        }
        if (++streak > minStreak && !isExempt()) {
            streak = 0;
            if (raiseBuffer(1.0)) {
                violation("pitch-only rotations off the mouse grid delta=" + deltaPitch);
            }
        }
    }
}
