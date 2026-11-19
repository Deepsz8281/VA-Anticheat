package dev.vaplus.anticheat.platform;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.latency.ServerTickMarkers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.PlayerDataRegistry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Drives the per-server-tick marker (handoff M15) from the platform's tick source.
 *
 * <ul>
 *   <li><b>Paper</b>: {@code com.destroystokyo.paper.event.server.ServerTickEndEvent} (present in the
 *       compile API {@code paper-api 1.21.1}, verified with {@code javap}: {@code getTickNumber()},
 *       {@code getTickDuration()}, {@code getTimeRemaining()}). Fired on the main thread at the end of
 *       each tick; every tracked player's {@link ServerTickMarkers#publish(long)} only schedules the
 *       marker onto that player's netty event loop.</li>
 *   <li><b>Folia</b>: there is no global tick, so a 1-tick repeating task runs on each player's entity
 *       scheduler (attached on join, cancelled on quit/disable — same lifecycle as the permission
 *       refresh task in {@link ConnectionListener}). The tick index is the number of runs of that
 *       task. Folia builds for 26.x are UNCONFIRMED (version-notes §1.2); nothing here depends on them.</li>
 * </ul>
 *
 * <p>Folia is detected at runtime by the presence of {@code io.papermc.paper.threadedregions.RegionizedServer}
 * (the codebase had no detection before this class). The toggle {@code latency.server-tick-markers}
 * is read on every tick, so {@code /ac reload} switches it without re-registering anything.
 */
public final class ServerTickMarkerService {

    private static final String FOLIA_MARKER_CLASS = "io.papermc.paper.threadedregions.RegionizedServer";

    private final Plugin plugin;
    private final Supplier<? extends Collection<PlayerData>> allPlayers;
    private final Function<UUID, PlayerData> lookup;
    private final ComponentKey<ServerTickMarkers> key;
    private final Supplier<GlobalSettings> settings;
    private final boolean folia;
    private final ConcurrentHashMap<UUID, ScheduledTask> regionTasks = new ConcurrentHashMap<>();
    private Listener paperListener;

    /**
     * @param key component key, or {@code null} when the core-tracking module is not installed
     *            (then the service does nothing)
     */
    public ServerTickMarkerService(Plugin plugin, PlayerDataRegistry players, ComponentKey<ServerTickMarkers> key,
                                   Supplier<GlobalSettings> settings, boolean folia) {
        this(plugin, players::all, players::get, key, settings, folia);
    }

    /** Player source as functions, so the tick fan-out is testable without PacketEvents users. */
    ServerTickMarkerService(Plugin plugin, Supplier<? extends Collection<PlayerData>> allPlayers,
                            Function<UUID, PlayerData> lookup, ComponentKey<ServerTickMarkers> key,
                            Supplier<GlobalSettings> settings, boolean folia) {
        this.plugin = plugin;
        this.allPlayers = allPlayers;
        this.lookup = lookup;
        this.key = key;
        this.settings = settings;
        this.folia = folia;
    }

    /** Whether the server is Folia (regionised multithreading). */
    public static boolean detectFolia() {
        try {
            Class.forName(FOLIA_MARKER_CLASS, false, ServerTickMarkerService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }

    public boolean folia() {
        return folia;
    }

    /** Paper: registers the tick-end listener. Folia: nothing (tasks are attached per player). */
    public void start() {
        if (key == null || folia || paperListener != null) {
            return;
        }
        paperListener = new PaperTickListener();
        plugin.getServer().getPluginManager().registerEvents(paperListener, plugin);
    }

    /** Paper main thread: announces the finished tick to every tracked player. */
    void onServerTickEnd(long tickNumber) {
        if (key == null || !settings.get().serverTickMarkers()) {
            return;
        }
        for (PlayerData data : allPlayers.get()) {
            data.component(key).publish(tickNumber);
        }
    }

    /**
     * Folia: starts the player's 1-tick entity task (idempotent; replaces a previous task). Must run
     * on the player's region thread (join event or plugin enable), like {@link ConnectionListener#attach}.
     */
    public void attach(Player player) {
        if (key == null || !folia) {
            return;
        }
        UUID id = player.getUniqueId();
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, new RegionTicker(id), null, 1L, 1L);
        ScheduledTask previous = task != null ? regionTasks.put(id, task) : null;
        if (previous != null) {
            previous.cancel();
        }
    }

    /** Folia: cancels the player's task (quit). */
    public void detach(UUID id) {
        ScheduledTask task = regionTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /** Unregisters the listener and cancels every per-player task (plugin disable). */
    public void shutdown() {
        if (paperListener != null) {
            HandlerList.unregisterAll(paperListener);
            paperListener = null;
        }
        for (ScheduledTask task : regionTasks.values()) {
            task.cancel();
        }
        regionTasks.clear();
    }

    /** Number of attached Folia tasks (diagnostics/tests). */
    int attachedTasks() {
        return regionTasks.size();
    }

    /** One player's Folia region tick: counts its own runs as the tick index. */
    final class RegionTicker implements Consumer<ScheduledTask> {

        private final UUID id;
        /** Runs serially on the region owning the entity, possibly on changing threads. */
        private volatile long ticks;

        RegionTicker(UUID id) {
            this.id = id;
        }

        @Override
        public void accept(ScheduledTask task) {
            long tick = ++ticks;
            if (!settings.get().serverTickMarkers()) {
                return;
            }
            PlayerData data = lookup.apply(id);
            if (data != null) {
                data.component(key).publish(tick);
            }
        }

        long ticks() {
            return ticks;
        }
    }

    /** Separate class so the Paper-only event handler is never registered on Folia. */
    private final class PaperTickListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onTickEnd(ServerTickEndEvent event) {
            onServerTickEnd(event.getTickNumber());
        }
    }
}
