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
import dev.vaplus.anticheat.world.BlockSight;

/**
 * Freecam: block interactions that are geometrically impossible with the rotation the client sent.
 *
 * <p>A freecam client detaches the camera from the body: the player looks around and interacts
 * through the free camera while the movement packets keep describing the body. Vanilla clients
 * always send the interaction for the block under the crosshair of the camera they are looking
 * with, so the look direction and the target have to agree. Two independent tests cover the two
 * interaction kinds:
 * <ul>
 *   <li><b>place</b> — the packet carries the exact hit point ({@code cursorX/Y/Z}). The angle
 *       between the look direction and the vector from the eye to that point must be below
 *       {@code max-angle}; the point must lie within {@code max-reach}.</li>
 *   <li><b>dig start</b> — the client only starts digging a block its crosshair is on, so the look
 *       ray must intersect the target block's box (grown by {@code ray-tolerance}) within
 *       {@code max-reach}.</li>
 * </ul>
 * Both tests tolerate the small eye-height and float rounding errors of a normal client and run
 * through the buffer, so a single packet never flags.
 *
 * <p>Not covered: freecam that is only used to look around (that never touches a block) and freecam
 * while gliding or swimming, whose camera pose is not modelled here. All numbers are TUNING values.
 */
public final class FreecamCheck extends Check implements BlockCheck {

    /** Vanilla standing eye height above the feet. */
    public static final double STANDING_EYE_HEIGHT = 1.62;
    /** Vanilla sneaking eye height above the feet. */
    public static final double SNEAKING_EYE_HEIGHT = 1.27;

    /** TUNING: block interaction range tolerated; above the vanilla attribute and its latency slack. */
    public static final double DEFAULT_MAX_REACH = 6.0;
    /** TUNING: degrees between look direction and hit point a normal client can produce. */
    public static final double DEFAULT_MAX_ANGLE = 25.0;
    /** TUNING: box growth that absorbs float rounding and the eye-height approximation. */
    public static final double DEFAULT_RAY_TOLERANCE = 0.15;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("freecam", CheckCategory.WORLD)
            .displayName("Freecam")
            .description("Block interactions that do not match the sent look direction")
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.VEHICLE)
            .build();

    private double maxReach = DEFAULT_MAX_REACH;
    private double maxAngleDegrees = DEFAULT_MAX_ANGLE;
    private double maxAngleCos = Math.cos(Math.toRadians(DEFAULT_MAX_ANGLE));
    private double rayTolerance = DEFAULT_RAY_TOLERANCE;
    private boolean checkPlace = true;
    private boolean checkDig = true;

    /** Reused look vector; owner thread only. */
    private final double[] look = new double[3];

    public FreecamCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxReach = VisibilityOptions.tolerance(options, "max-reach", DEFAULT_MAX_REACH);
        maxAngleDegrees = VisibilityOptions.tolerance(options, "max-angle", DEFAULT_MAX_ANGLE);
        maxAngleCos = Math.cos(Math.toRadians(Math.min(maxAngleDegrees, 180.0)));
        rayTolerance = VisibilityOptions.tolerance(options, "ray-tolerance", DEFAULT_RAY_TOLERANCE);
        checkPlace = options.getBoolean("check-place", true);
        checkDig = options.getBoolean("check-dig", true);
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind != BlockInteraction.Kind.DIG_START && kind != BlockInteraction.Kind.PLACE) {
            return;
        }
        if (kind == BlockInteraction.Kind.PLACE ? !checkPlace : !checkDig) {
            return;
        }
        if (isExempt()) {
            return;
        }
        if (kind == BlockInteraction.Kind.PLACE && interaction.insideBlock()) {
            // The client reports placements inside its own hitbox; the camera position is not usable then.
            return;
        }
        MovementState movement = player.movement();
        if (!movement.positionKnown() || !movement.rotationKnown()) {
            return;
        }
        PoseState pose = player.pose();
        if (pose.gliding() || pose.swimming()) {
            // Elytra/swim camera poses are not modelled; interactions are rare in both.
            return;
        }
        double eyeHeight = pose.sneaking() ? SNEAKING_EYE_HEIGHT : STANDING_EYE_HEIGHT;
        double eyeX = movement.x();
        double eyeY = movement.y() + eyeHeight;
        double eyeZ = movement.z();
        direction(movement.yaw(), movement.pitch(), look);

        String details = kind == BlockInteraction.Kind.PLACE
                ? checkPlace(interaction, eyeX, eyeY, eyeZ)
                : checkDig(interaction, eyeX, eyeY, eyeZ);
        if (details == null) {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug(details + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(details);
            resetBuffer();
        }
    }

    /** @return {@code null} when the interaction matches the look direction, otherwise the details */
    private String checkPlace(BlockInteraction interaction, double eyeX, double eyeY, double eyeZ) {
        double hitX = interaction.blockX() + interaction.cursorX();
        double hitY = interaction.blockY() + interaction.cursorY();
        double hitZ = interaction.blockZ() + interaction.cursorZ();
        double vx = hitX - eyeX;
        double vy = hitY - eyeY;
        double vz = hitZ - eyeZ;
        double distance = Math.sqrt(vx * vx + vy * vy + vz * vz);
        String target = interaction.blockX() + "," + interaction.blockY() + "," + interaction.blockZ();
        if (!(distance > 1.0E-6)) {
            return null;
        }
        if (distance > maxReach + rayTolerance) {
            return "kind=PLACE target=" + target + " distance=" + VisibilityOptions.number(distance)
                    + " limit=" + VisibilityOptions.number(maxReach + rayTolerance);
        }
        double dot = (vx * look[0] + vy * look[1] + vz * look[2]) / distance;
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        if (angle <= maxAngleDegrees) {
            return null;
        }
        return "kind=PLACE target=" + target + " angle=" + VisibilityOptions.number(angle)
                + " limit=" + VisibilityOptions.number(maxAngleDegrees)
                + " distance=" + VisibilityOptions.number(distance);
    }

    /** @return {@code null} when the look ray intersects the targeted block, otherwise the details */
    private String checkDig(BlockInteraction interaction, double eyeX, double eyeY, double eyeZ) {
        int blockX = interaction.blockX();
        int blockY = interaction.blockY();
        int blockZ = interaction.blockZ();
        double entry = BlockSight.rayBoxEntry(eyeX, eyeY, eyeZ, look[0], look[1], look[2],
                blockX - rayTolerance, blockY - rayTolerance, blockZ - rayTolerance,
                blockX + 1.0 + rayTolerance, blockY + 1.0 + rayTolerance, blockZ + 1.0 + rayTolerance);
        double limit = maxReach + rayTolerance;
        if (!Double.isNaN(entry) && entry <= limit) {
            return null;
        }
        String target = blockX + "," + blockY + "," + blockZ;
        if (Double.isNaN(entry)) {
            return "kind=DIG target=" + target + " ray=miss reach=" + VisibilityOptions.number(limit);
        }
        return "kind=DIG target=" + target + " ray=" + VisibilityOptions.number(entry)
                + " reach=" + VisibilityOptions.number(limit);
    }

    /** Minecraft look vector (yaw 0 = south/+Z, yaw 90 = west/-X, positive pitch = down). */
    /** Shared look-vector helper (freecam, ESP and combat checks use the same convention). */
    public static void direction(float yaw, float pitch, double[] out) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        out[0] = -Math.sin(yawRadians) * horizontal;
        out[1] = -Math.sin(pitchRadians);
        out[2] = Math.cos(yawRadians) * horizontal;
    }

    /** Effective maximum angle in degrees (tests, diagnostics). */
    public double maxAngleDegrees() {
        return maxAngleDegrees;
    }

    /** Cosine of the configured maximum angle (tests, diagnostics). */
    public double maxAngleCos() {
        return maxAngleCos;
    }
}
