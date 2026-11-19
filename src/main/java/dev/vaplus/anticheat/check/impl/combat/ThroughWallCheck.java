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
import dev.vaplus.anticheat.world.BlockSight;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

/**
 * Through-wall: hitting a player whose hitbox is completely behind opaque blocks.
 *
 * <p>The check measures line of sight from the attacker's eye to two points of the target's box
 * (the closest point and the center). It only flags when <b>both</b> are
 * {@link BlockSight.Occlusion#OCCLUDED} and the target is farther than {@code min-distance}, so a
 * corner hit where one edge of the hitbox is visible cannot trigger it. Missing mirror data
 * ({@code UNKNOWN}) never flags: the client may legitimately hold a chunk the mirror does not.
 *
 * <p>Glass, leaves, bars and fluids are see-through by the shared {@link BlockSight} definition and
 * therefore not walls. Targets close to the attacker are skipped (a wall cannot be between them).
 */
public final class ThroughWallCheck extends Check implements CombatCheck {

    public static final double DEFAULT_MIN_DISTANCE = 1.5;
    public static final double DEFAULT_MAX_DISTANCE = 6.0;
    /** Targets wider than 2 blocks are not judged (end crystals, 2.0, still are). */
    static final double MAX_JUDGED_HALF_WIDTH = 1.0;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("through-wall", CheckCategory.COMBAT)
            .displayName("ThroughWall")
            .description("Hits a player who is fully behind opaque blocks")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private final ComponentKey<NearbyPlayers> trackedKey;
    private final ComponentKey<WorldMirror> worldKey;

    private double minDistance = DEFAULT_MIN_DISTANCE;
    private double maxDistance = DEFAULT_MAX_DISTANCE;

    public ThroughWallCheck(PlayerData player, ComponentKey<NearbyPlayers> trackedKey,
                            ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.trackedKey = trackedKey;
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        minDistance = CombatOptions.tolerance(options, "min-distance", DEFAULT_MIN_DISTANCE);
        maxDistance = CombatOptions.positive(options, "max-distance", DEFAULT_MAX_DISTANCE);
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
        if (tracked.halfWidth(index) > MAX_JUDGED_HALF_WIDTH) {
            // Huge boxes (ghast, ravager) can peek around a pillar while centre and closest point
            // are both hidden; two sample points are not enough to judge them.
            lowerBuffer();
            return;
        }
        double centerY = tracked.centerY(index);
        double dx = tracked.x(index) - movement.x();
        double dy = centerY - eyeY;
        double dz = tracked.z(index) - movement.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < minDistance || distance > maxDistance) {
            lowerBuffer();
            return;
        }
        WorldMirror world = player.component(worldKey);
        double half = tracked.halfWidth(index);
        double closestX = clamp(movement.x(), tracked.x(index) - half, tracked.x(index) + half);
        double closestY = clamp(eyeY, tracked.y(index), tracked.y(index) + tracked.height(index));
        double closestZ = clamp(movement.z(), tracked.z(index) - half, tracked.z(index) + half);
        BlockSight.Occlusion closest = BlockSight.lineOfSight(world, movement.x(), eyeY, movement.z(),
                closestX, closestY, closestZ);
        if (closest != BlockSight.Occlusion.OCCLUDED) {
            lowerBuffer();
            return;
        }
        BlockSight.Occlusion center = BlockSight.lineOfSight(world, movement.x(), eyeY, movement.z(),
                tracked.x(index), centerY, tracked.z(index));
        if (center != BlockSight.Occlusion.OCCLUDED) {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug("entity=" + attack.entityId() + " distance=" + CombatOptions.number(distance)
                    + " buffer=" + CombatOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("entity=" + attack.entityId()
                    + " distance=" + CombatOptions.number(distance)
                    + " buffer=" + CombatOptions.number(buffer()));
            resetBuffer();
        }
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
