package dev.vaplus.anticheat.check.impl.world;

import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockInteraction;

/**
 * BlockReach: digging a block farther away than the block interaction range.
 *
 * <p>The vanilla client only digs the block its pick ray hits, and the ray ends at the
 * {@code block_interaction_range} attribute (4.5, plus 0.5 in creative). The point it hits lies on
 * the block, so the distance from the eye to the closest point of the block's full cube can never be
 * larger. The server itself only rejects digs beyond range + 1.0, which leaves a whole block of
 * reach for cheats (reach, nuker and speed-mine modules). Dig starts and finishes are judged; the
 * reach of placements and block uses is the {@code scaffold} check's {@code reach} signal.
 *
 * <p>Why legitimate play does not trigger it: the eye is taken anywhere on the segment between the
 * previous and the current position (the client picks with an interpolated eye) and at any height
 * between the crawling and the standing eye; the block is its full cube (a superset of every shape).
 * Players with a non-default scale, pending attribute changes, vehicles, teleports and the usual
 * exemption causes are not judged. All numeric defaults are TUNING values.
 */
public final class BlockReachCheck extends Check implements BlockCheck {

    /** TUNING: blocks tolerated beyond the interaction range. */
    public static final double DEFAULT_TOLERANCE = 0.1;
    /** Vanilla creative bonus on the block interaction range. */
    static final double CREATIVE_BONUS = 0.5;
    static final double MAX_EYE_HEIGHT = FreecamCheck.STANDING_EYE_HEIGHT;
    static final double MIN_EYE_HEIGHT = 0.4;
    private static final int EYE_STEPS = 5;

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.VEHICLE,
            ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("blockreach", CheckCategory.WORLD)
            .displayName("BlockReach")
            .description("Digging blocks beyond the block interaction range")
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE)
            .build();

    private double tolerance = DEFAULT_TOLERANCE;

    public BlockReachCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        double value = options.getDouble("tolerance", DEFAULT_TOLERANCE);
        tolerance = Double.isFinite(value) && value >= 0.0 && value <= 2.0 ? value : DEFAULT_TOLERANCE;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind != BlockInteraction.Kind.DIG_START && kind != BlockInteraction.Kind.DIG_FINISH) {
            return;
        }
        if (!judgeable()) {
            return;
        }
        int bx = interaction.blockX();
        int by = interaction.blockY();
        int bz = interaction.blockZ();
        double range = range();
        double distance = closestDistance(player.movement(), bx, by, bz);
        if (distance <= range + tolerance) {
            lowerBuffer();
            return;
        }
        String details = "distance=" + VisibilityOptions.number(distance)
                + " range=" + VisibilityOptions.number(range)
                + " block=" + bx + "," + by + "," + bz
                + " action=" + (kind == BlockInteraction.Kind.DIG_START ? "start" : "finish");
        if (debugging()) {
            debug(details + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(details);
        }
    }

    /** Effective interaction range: the attribute, plus the creative bonus in creative mode. */
    double range() {
        double range = player.attributes().blockInteractionRange();
        return player.abilities().creativeMode() ? range + CREATIVE_BONUS : range;
    }

    private boolean judgeable() {
        if (isExempt() || player.exemptions().anyActive(EXEMPT_CAUSES)) {
            return false;
        }
        MovementState movement = player.movement();
        PoseState pose = player.pose();
        if (!movement.positionKnown() || player.vehicle().inVehicle() || pose.gliding()) {
            return false;
        }
        return !player.attributes().pending() && Math.abs(player.attributes().scale() - 1.0) < 1.0e-6;
    }

    /**
     * Smallest distance from any eye on the segment between the previous and the current position
     * (at any height between the crawling and the standing eye) to the block's full cube.
     */
    static double closestDistance(MovementState movement, int bx, int by, int bz) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < EYE_STEPS; i++) {
            double t = (double) i / (EYE_STEPS - 1);
            double x = movement.lastX() + (movement.x() - movement.lastX()) * t;
            double y = movement.lastY() + (movement.y() - movement.lastY()) * t;
            double z = movement.lastZ() + (movement.z() - movement.lastZ()) * t;
            double dx = axisDistance(x, bx, bx + 1.0);
            double dz = axisDistance(z, bz, bz + 1.0);
            double low = y + MIN_EYE_HEIGHT;
            double high = y + MAX_EYE_HEIGHT;
            double dy = high < by ? by - high : low > by + 1.0 ? low - (by + 1.0) : 0.0;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0;
    }
}
