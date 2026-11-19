package dev.vaplus.anticheat.platform;

import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.movement.SetbackHandler;
import dev.vaplus.anticheat.pipeline.TeleportQueue;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.MovementState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Baseline setback: teleports the player to the last position that passed all movement checks.
 * The target is captured on the packet thread; the teleport itself runs on the player's entity
 * scheduler via {@code teleportAsync} and is skipped if the player changed worlds meanwhile.
 *
 * <p>Phase 2 adds a packet-level path ({@link #request(PlayerData, SetbackTarget, String)}): the
 * destination is registered in the player's {@link TeleportQueue} and sent as an outbound
 * {@code player_position_and_look} through the {@code PacketTransport}, on the connection's owner
 * thread. That path never touches thread-bound Bukkit API (Folia-safe) and makes the movement gate
 * wait for the client's {@code TELEPORT_CONFIRM}. When the queue component is not installed (or the
 * target is unusable) the handler falls back to the {@code teleportAsync} logic unchanged.
 */
public final class TeleportSetbackHandler implements SetbackHandler {

    private final Plugin plugin;

    public TeleportSetbackHandler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void request(PlayerData player, Check cause) {
        MovementState movement = player.movement();
        movement.markSetbackRequested();
        if (!movement.safeKnown() || player.disconnecting()) {
            return;
        }
        if (!claimSetback(player)) {
            return;
        }
        teleportToSafePosition(player);
    }

    @Override
    public void request(PlayerData player, SetbackTarget target, String reason) {
        MovementState movement = player.movement();
        movement.markSetbackRequested();
        if (player.disconnecting()) {
            return;
        }
        if (target == null || !target.isFinite()) {
            // No usable packet-level target: the Bukkit fallback still knows the safe position.
            if (movement.safeKnown() && claimSetback(player)) {
                teleportToSafePosition(player);
            }
            return;
        }
        if (!claimSetback(player)) {
            return;
        }
        TeleportQueue queue = TeleportQueue.resolve(player);
        if (queue == null) {
            teleportToSafePosition(player);
            return;
        }
        int epoch = movement.worldEpoch();
        String origin = reason == null || reason.isEmpty() ? "setback" : reason;
        // Register + send on the owner thread (the transport hop is a no-op when we are already on
        // it). No Bukkit scheduler, no main-thread entity access: Folia-safe.
        player.transport().runOnOwnerThread(() -> {
            if (player.disconnecting() || movement.worldEpoch() != epoch) {
                return;
            }
            int teleportId = queue.registerOwn(target, epoch, player.clientTicks(), origin);
            player.transport().send(new WrapperPlayServerPlayerPositionAndLook(
                    teleportId,
                    new Vector3d(target.x(), target.y(), target.z()),
                    Vector3d.zero(),
                    target.yaw(),
                    target.pitch(),
                    RelativeFlag.NONE));
        });
    }

    private boolean claimSetback(PlayerData player) {
        long cooldownNanos = player.context().settings().setbackCooldownMillis() * 1_000_000L;
        return player.claimSetback(player.context().time().nanoTime(), cooldownNanos);
    }

    /** Legacy Bukkit path: {@code teleportAsync} on the player's entity scheduler. */
    private void teleportToSafePosition(PlayerData player) {
        MovementState movement = player.movement();
        if (!movement.safeKnown() || plugin == null) {
            return;
        }
        Player bukkit = player.bukkitPlayer();
        if (bukkit == null) {
            return;
        }
        double x = movement.safeX();
        double y = movement.safeY();
        double z = movement.safeZ();
        float yaw = movement.yaw();
        float pitch = movement.pitch();
        int epoch = movement.worldEpoch();
        bukkit.getScheduler().run(plugin, task -> {
            if (movement.worldEpoch() != epoch || !bukkit.isOnline()) {
                return;
            }
            bukkit.teleportAsync(new Location(bukkit.getWorld(), x, y, z, yaw, pitch));
        }, null);
    }
}
