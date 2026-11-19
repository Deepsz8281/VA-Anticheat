package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.CombatCheck;
import dev.vaplus.anticheat.check.impl.world.FreecamCheck;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;

/**
 * Reach: attacking a player from farther away than vanilla allows.
 *
 * <p>The distance is measured from the attacker's eye to the <em>closest point of the target's
 * hitbox</em> (not its center) plus a tolerance, so strafing, sneaking and ping jitter do not
 * produce false positives. Vanilla survival reach is 3.0 blocks; the default limit is 3.1 plus a
 * 0.1 box tolerance for float rounding.
 *
 * <p>Targets come from {@link NearbyPlayers}, which only tracks entities the server actually sent
 * to this viewer. Unknown ids never judge (the entity may have despawned between packets).
 * Spectators are exempt through {@link CheckCategory#COMBAT}; creative players are not, reach is an
 * advantage in every game mode.
 */
public final class ReachCheck extends Check implements CombatCheck {

    public static final double DEFAULT_MAX_REACH = 3.1;
    public static final double DEFAULT_TOLERANCE = 0.1;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("reach", CheckCategory.COMBAT)
            .displayName("Reach")
            .description("Attacks a player from beyond vanilla reach")
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private final ComponentKey<NearbyPlayers> trackedKey;

    private double maxReach = DEFAULT_MAX_REACH;
    private double tolerance = DEFAULT_TOLERANCE;

    public ReachCheck(PlayerData player, ComponentKey<NearbyPlayers> trackedKey) {
        super(player, DESCRIPTOR);
        this.trackedKey = trackedKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxReach = CombatOptions.positive(options, "max-reach", DEFAULT_MAX_REACH);
        tolerance = CombatOptions.tolerance(options, "hitbox-tolerance", DEFAULT_TOLERANCE);
    }

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked()) {
            return;
        }
        if (isExempt()) {
            lowerBuffer();
            return;
        }
        NearbyPlayers tracked = player.component(trackedKey);
        int index = tracked.indexOf(attack.entityId());
        MovementState movement = player.movement();
        if (index < 0 || !movement.positionKnown()) {
            lowerBuffer();
            return;
        }
        PoseState pose = player.pose();
        double eyeY = movement.y() + (pose.sneaking()
                ? FreecamCheck.SNEAKING_EYE_HEIGHT : FreecamCheck.STANDING_EYE_HEIGHT);
        double distance = distanceToHitbox(movement.x(), eyeY, movement.z(), tracked, index, tolerance);
        if (debugging()) {
            debug("entity=" + attack.entityId() + " distance=" + CombatOptions.number(distance)
                    + " limit=" + CombatOptions.number(maxReach) + " buffer="
                    + CombatOptions.number(buffer()));
        }
        if (distance > maxReach) {
            if (raiseBuffer(1.0)) {
                violation("entity=" + attack.entityId()
                        + " distance=" + CombatOptions.number(distance)
                        + " limit=" + CombatOptions.number(maxReach)
                        + " buffer=" + CombatOptions.number(buffer()));
                resetBuffer();
            }
        } else {
            lowerBuffer();
        }
    }

    /** Distance from the eye to the closest point of the target's box, expanded by {@code expand}. */
    static double distanceToHitbox(double eyeX, double eyeY, double eyeZ, NearbyPlayers tracked, int index,
                                   double expand) {
        double half = tracked.halfWidth(index) + expand;
        double height = tracked.height(index);
        double minX = tracked.x(index) - half;
        double maxX = tracked.x(index) + half;
        double minY = tracked.y(index) - expand;
        double maxY = tracked.y(index) + height + expand;
        double minZ = tracked.z(index) - half;
        double maxZ = tracked.z(index) + half;
        double dx = axisDistance(eyeX, minX, maxX);
        double dy = axisDistance(eyeY, minY, maxY);
        double dz = axisDistance(eyeZ, minZ, maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0;
    }
}
