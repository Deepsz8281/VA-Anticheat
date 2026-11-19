package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.simulation.SimulationResult;

import java.util.List;

/**
 * Turns movement packets into {@link MovementUpdate}/{@link LookChange}, runs hooks, the
 * simulation engine and the position/rotation checks. {@link #process} takes primitives so
 * recorded movement can be replayed in tests without PacketEvents.
 *
 * <p>Before the hooks run, the teleport gate (handoff H5) checks the player's
 * {@link TeleportQueue}: while a server position sync is unconfirmed, the packet updates the
 * tracked position but no hook, simulation or position/rotation check runs. The simulation reports
 * {@code SKIPPED} with reason {@code awaiting-teleport}. Acknowledgement/marker handling is
 * untouched (it lives in the inbound pipeline before this class).
 */
public final class MovementProcessor {

    /** Vanilla clamps horizontal coordinates to this range. */
    private static final double HORIZONTAL_LIMIT = 3.0E7;
    /** Vanilla clamps vertical coordinates to this range. */
    private static final double VERTICAL_LIMIT = 2.0E7;

    private static final String SKIP_FIRST = "first-position";
    private static final String SKIP_TELEPORT = "teleport";
    private static final String SKIP_AWAITING_TELEPORT = "awaiting-teleport";

    private final MovementHook[] hooks;
    private final ComponentKey<TeleportQueue> teleportKey;

    public MovementProcessor(List<MovementHook> hooks) {
        this(hooks, null);
    }

    /** Production constructor: enables the teleport gate when the core-tracking module is installed. */
    public MovementProcessor(ModuleSetup setup) {
        this(setup.movementHooks(), TeleportQueue.keyOf(setup));
    }

    public MovementProcessor(List<MovementHook> hooks, ComponentKey<TeleportQueue> teleportKey) {
        this.hooks = hooks.toArray(new MovementHook[0]);
        this.teleportKey = teleportKey;
    }

    /**
     * Owner thread. Malformed packets (non-finite values) are ignored here and left to the server,
     * which disconnects the client for them.
     */
    public void handle(PlayerData player, PacketReceiveEvent event) {
        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        Location location = packet.getLocation();
        boolean hasPosition = packet.hasPositionChanged();
        boolean hasRotation = packet.hasRotationChanged();
        boolean accepted = process(player,
                hasPosition, location.getX(), location.getY(), location.getZ(),
                hasRotation, location.getYaw(), location.getPitch(),
                packet.isOnGround(), packet.isHorizontalCollision());
        if (accepted) {
            // Optional replay recording (plan item 7); one boolean check when disabled.
            dev.vaplus.anticheat.diagnostics.ReplayRecorder.active()
                    .record(player, player.context().time().nanoTime());
        }
    }

    /**
     * Processes one movement packet.
     *
     * @return {@code false} if the packet contained non-finite values and was ignored
     */
    public boolean process(PlayerData player,
                           boolean hasPosition, double x, double y, double z,
                           boolean hasRotation, float yaw, float pitch,
                           boolean onGround, boolean horizontalCollision) {
        if (hasPosition && !(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
            return false;
        }
        if (hasRotation && !(Float.isFinite(yaw) && Float.isFinite(pitch))) {
            return false;
        }

        MovementState movement = player.movement();
        long now = player.context().time().nanoTime();
        double fromX = movement.x();
        double fromY = movement.y();
        double fromZ = movement.z();
        boolean fromGround = movement.onGround();
        float fromYaw = movement.yaw();
        float fromPitch = movement.pitch();
        boolean firstPosition = hasPosition && !movement.positionKnown();
        boolean firstRotation = hasRotation && !movement.rotationKnown();
        int elapsedTicks = player.tickEndMode() ? Math.max(1, movement.ticksSincePosition()) : 1;

        double toX = hasPosition ? clamp(x, HORIZONTAL_LIMIT) : fromX;
        double toY = hasPosition ? clamp(y, VERTICAL_LIMIT) : fromY;
        double toZ = hasPosition ? clamp(z, HORIZONTAL_LIMIT) : fromZ;
        float toYaw = hasRotation ? yaw : fromYaw;
        float toPitch = hasRotation ? pitch : fromPitch;

        if (hasPosition) {
            movement.applyPosition(toX, toY, toZ);
        }
        if (hasRotation) {
            movement.applyRotation(toYaw, toPitch);
        }
        movement.applyFlags(onGround, horizontalCollision);

        MovementUpdate update = player.movementUpdate();
        update.assign(fromX, fromY, fromZ, toX, toY, toZ, fromGround, onGround, horizontalCollision,
                hasPosition, hasRotation, firstPosition, elapsedTicks, now);

        boolean awaitingTeleport = awaitingTeleport(player);

        if (hasPosition) {
            if (awaitingTeleport) {
                // Gate (handoff H5): the client has not confirmed the most recent outbound position
                // sync yet. Hooks, simulation and position checks stay off; the safe position must
                // not advance to an unconfirmed position.
                player.simulation().skip(SKIP_AWAITING_TELEPORT);
                movement.markSetbackRequested();
                movement.finishChecks();
            } else {
                for (MovementHook hook : hooks) {
                    hook.beforeChecks(player, update);
                }
                SimulationResult simulation = player.simulation();
                if (firstPosition) {
                    simulation.skip(SKIP_FIRST);
                } else {
                    if (update.teleport()) {
                        simulation.skip(SKIP_TELEPORT);
                    } else {
                        player.context().simulator().simulate(player, update, simulation);
                    }
                    player.checks().dispatchPosition(update);
                }
                movement.finishChecks();
            }
        }

        if (!awaitingTeleport && hasRotation && !firstRotation) {
            LookChange rotation = player.lookChange();
            rotation.assign(fromYaw, fromPitch, toYaw, toPitch, update.teleport(), now);
            player.checks().dispatchRotation(rotation);
        }

        if (!player.tickEndMode()) {
            player.onClientTick();
        }
        return true;
    }

    private boolean awaitingTeleport(PlayerData player) {
        if (teleportKey == null) {
            return false;
        }
        return player.component(teleportKey).awaitingTeleport(player.clientTicks());
    }

    private static double clamp(double value, double limit) {
        return value < -limit ? -limit : Math.min(value, limit);
    }
}
