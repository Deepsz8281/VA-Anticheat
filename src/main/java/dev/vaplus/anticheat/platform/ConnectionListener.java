package dev.vaplus.anticheat.platform;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import dev.vaplus.anticheat.alert.AlertService;
import dev.vaplus.anticheat.alert.DebugService;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.PlayerDataRegistry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Bukkit's join/quit lifecycle to {@link PlayerData}: attaches the Bukkit player, caches
 * permissions (refreshed every 5 seconds on the player's own entity scheduler, so permission
 * checks never happen on packet threads) and maintains alert recipients. On Folia it also starts and
 * stops each player's per-tick marker task ({@link ServerTickMarkerService}) with the same lifecycle.
 */
public final class ConnectionListener implements Listener {

    private static final long PERMISSION_REFRESH_TICKS = 100L;

    private final Plugin plugin;
    private final PlayerDataRegistry players;
    private final AlertService alerts;
    private final DebugService debug;
    private final ServerTickMarkerService tickMarkers;
    private final ConcurrentHashMap<UUID, ScheduledTask> refreshTasks = new ConcurrentHashMap<>();

    public ConnectionListener(Plugin plugin, PlayerDataRegistry players, AlertService alerts, DebugService debug) {
        this(plugin, players, alerts, debug, null);
    }

    /** @param tickMarkers per-tick marker service (Folia per-player tasks), or {@code null} */
    public ConnectionListener(Plugin plugin, PlayerDataRegistry players, AlertService alerts, DebugService debug,
                              ServerTickMarkerService tickMarkers) {
        this.plugin = plugin;
        this.players = players;
        this.alerts = alerts;
        this.debug = debug;
        this.tickMarkers = tickMarkers;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        players.removeIfAttached(id, player);
        alerts.handleQuit(id);
        debug.removeViewer(id);
        ScheduledTask task = refreshTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        if (tickMarkers != null) {
            tickMarkers.detach(id);
        }
    }

    /** Must run on the player's region thread (join event or plugin enable). Idempotent. */
    public void attach(Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        PlayerData data = players.getOrCreate(user);
        if (data != null) {
            data.attach(player);
            data.updateBypass(player.hasPermission(Permissions.BYPASS));
            data.updateHealthIndicatorBypass(player.hasPermission(Permissions.HEALTH_INDICATOR_BYPASS));
        }
        alerts.handleJoin(player);
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> refresh(player),
                null, PERMISSION_REFRESH_TICKS, PERMISSION_REFRESH_TICKS);
        ScheduledTask previous = task != null ? refreshTasks.put(player.getUniqueId(), task) : null;
        if (previous != null) {
            previous.cancel();
        }
        if (tickMarkers != null) {
            tickMarkers.attach(player);
        }
    }

    /** Cancels all refresh tasks and per-tick marker tasks (plugin disable). */
    public void shutdown() {
        for (ScheduledTask task : refreshTasks.values()) {
            task.cancel();
        }
        refreshTasks.clear();
        if (tickMarkers != null) {
            tickMarkers.shutdown();
        }
    }

    private void refresh(Player player) {
        PlayerData data = players.get(player.getUniqueId());
        if (data != null) {
            data.updateBypass(player.hasPermission(Permissions.BYPASS));
            data.updateHealthIndicatorBypass(player.hasPermission(Permissions.HEALTH_INDICATOR_BYPASS));
        }
        alerts.revalidate(player);
    }
}
