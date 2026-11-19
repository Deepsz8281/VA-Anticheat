package dev.vaplus.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import dev.vaplus.anticheat.alert.AlertService;
import dev.vaplus.anticheat.alert.DebugService;
import dev.vaplus.anticheat.check.CheckErrorReporter;
import dev.vaplus.anticheat.check.CheckRegistry;
import dev.vaplus.anticheat.command.AcCommand;
import dev.vaplus.anticheat.config.ConfigService;
import dev.vaplus.anticheat.config.ConfigSnapshot;
import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.config.MessageCatalog;
import dev.vaplus.anticheat.config.MessageService;
import dev.vaplus.anticheat.latency.ServerTickMarkers;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.obfuscation.HealthIndicatorModule;
import dev.vaplus.anticheat.obfuscation.HealthIndicatorSpoofer;
import dev.vaplus.anticheat.check.impl.movement.MovementChecksModule;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.MotionTrackingModule;
import dev.vaplus.anticheat.pipeline.OutboundStateListener;
import dev.vaplus.anticheat.pipeline.PacketPipeline;
import dev.vaplus.anticheat.platform.BukkitFlagEventPublisher;
import dev.vaplus.anticheat.platform.BukkitPunishmentExecutor;
import dev.vaplus.anticheat.platform.ConnectionListener;
import dev.vaplus.anticheat.platform.ServerPlatform;
import dev.vaplus.anticheat.platform.ServerTickMarkerService;
import dev.vaplus.anticheat.platform.TeleportSetbackHandler;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.PlayerDataRegistry;
import dev.vaplus.anticheat.profiling.CheckProfiler;
import dev.vaplus.anticheat.profiling.PerformanceGovernor;
import dev.vaplus.anticheat.profiling.PerformanceGovernor;
import dev.vaplus.anticheat.simulation.InactiveSimulator;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.util.TimeSource;
import dev.vaplus.anticheat.violation.InMemoryViolationLog;
import dev.vaplus.anticheat.violation.PunishmentExecutor;
import dev.vaplus.anticheat.violation.ViolationDispatcher;
import dev.vaplus.anticheat.violation.ViolationLog;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bootstrap. Installs the modules, wires the services, registers the PacketEvents listeners and
 * Bukkit listeners and schedules the latency heartbeat on the async scheduler (Paper and Folia).
 */
public final class VAPlusPlugin extends JavaPlugin {

    private MessageService messages;
    private ConfigService configService;
    private CheckRegistry checks;
    private CheckProfiler profiler;
    private ViolationLog violationLog;
    private AnticheatContext context;
    private PlayerDataRegistry players;
    private PacketPipeline pipeline;
    private OutboundStateListener outboundState;
    private ConnectionListener connections;
    private ServerTickMarkerService tickMarkers;
    private HealthIndicatorModule healthIndicator;
    private ScheduledTask heartbeatTask;
    private ScheduledTask watchdogTask;
    private long heartbeatInterval;
    private volatile PerformanceGovernor governor = new PerformanceGovernor();
    private final java.util.Set<Integer> slowChecks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Modules in registration order. Each phase appends exactly one module here. */
    private static List<AnticheatModule> modules(boolean sneakFromInputPacket, HealthIndicatorModule healthIndicator) {
        // Order matters: SimulationModule looks up components registered by the two modules before it,
        // and VisibilityChecksModule resolves the world mirror SimulationModule registers.
        // HealthIndicatorModule rewrites outgoing entity data and must stay last, so every tracker
        // before it still reads the real values.
        SimulationModule simulation = new SimulationModule();
        VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
        return List.of(new CoreTrackingModule(sneakFromInputPacket), new MotionTrackingModule(), simulation,
                new MovementChecksModule(simulation), visibility,
                new dev.vaplus.anticheat.check.impl.combat.CombatChecksModule(simulation, visibility),
                new dev.vaplus.anticheat.check.impl.packet.PacketChecksModule(),
                new dev.vaplus.anticheat.check.impl.inventory.InventoryChecksModule(),
                healthIndicator);
    }

    @Override
    public void onEnable() {
        if (PacketEvents.getAPI() == null) {
            getLogger().severe("PacketEvents is not available; disabling VAPlus.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Paper/Purpur/Folia share the Bukkit API; hybrid servers with a Bukkit bridge are reported
        // separately so support requests can be triaged (see docs/PLATFORMS.md).
        ServerPlatform platform = ServerPlatform.detectCurrent();
        getLogger().info("Server platform: " + platform.displayName() + " (" + Bukkit.getVersion() + ")");

        messages = new MessageService(this);
        try {
            messages.apply(messages.prepare());
        } catch (MessageService.LoadException exception) {
            getLogger().severe(exception.getMessage()
                    + " - running with the built-in defaults until the file is fixed and /ac reload is used.");
        }
        configService = new ConfigService(this);
        ConfigSnapshot snapshot;
        try {
            snapshot = configService.load();
        } catch (ConfigService.LoadException exception) {
            getLogger().severe(exception.getMessage()
                    + " - running with the bundled defaults until the file is fixed and /ac reload is used.");
            snapshot = configService.loadBundled();
        }
        GlobalSettings global = snapshot.global();

        // PacketEvents parses packets in the server's protocol format, so protocol differences are
        // decided by the server version, not by the client version.
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        boolean sneakFromInputPacket = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_21_6);
        healthIndicator = new HealthIndicatorModule(HealthIndicatorSpoofer.Indexes.forServer(serverVersion));
        checks = new CheckRegistry();
        ModuleContext moduleContext = new ModuleContext(checks);
        for (AnticheatModule module : modules(sneakFromInputPacket, healthIndicator)) {
            moduleContext.install(module);
        }
        ModuleSetup setup = moduleContext.complete();

        TimeSource time = TimeSource.SYSTEM;
        profiler = new CheckProfiler(checks.size(), time);
        AlertService alerts = new AlertService(this, messages, () -> context);
        DebugService debug = new DebugService(this, messages);
        // Persistence: the in-memory log stays the fast per-session cache; the storage: section picks
        // the durable backend (SQLite by default). Changes require a restart.
        InMemoryViolationLog sessionLog = new InMemoryViolationLog(global.logEntriesPerPlayer(), global.logMaxPlayers());
        List<String> storageWarnings = new java.util.ArrayList<>();
        dev.vaplus.anticheat.storage.StorageSettings storageSettings = dev.vaplus.anticheat.storage.StorageSettingsParser
                .parse(getConfig().getConfigurationSection("storage"), storageWarnings);
        storageWarnings.forEach(warning -> getLogger().warning("config.yml: " + warning));
        violationLog = dev.vaplus.anticheat.storage.ViolationStorage.create(storageSettings,
                getDataFolder().toPath(), sessionLog, getLogger());
        PunishmentExecutor punishments = new BukkitPunishmentExecutor(this, messages, alerts);
        ViolationDispatcher violations = new ViolationDispatcher(new BukkitFlagEventPublisher(), violationLog, alerts,
                punishments, new TeleportSetbackHandler(this), checks.size());
        governor = new PerformanceGovernor();

        context = AnticheatContext.builder()
                .logger(getLogger())
                .time(time)
                .checks(checks)
                .profiler(profiler)
                .errors(new CheckErrorReporter(getLogger(), time))
                .violations(violations)
                .punishments(punishments)
                .debug(debug)
                .setup(setup)
                .simulator(InactiveSimulator.INSTANCE)
                .governor(governor)
                .settings(global)
                .build();
        applySnapshot(snapshot);

        players = new PlayerDataRegistry(context);
        pipeline = new PacketPipeline(context, players, setup);
        outboundState = new OutboundStateListener(context, players, setup);
        PacketEvents.getAPI().getEventManager().registerListener(pipeline);
        PacketEvents.getAPI().getEventManager().registerListener(outboundState);

        // Per-server-tick markers (M15): Paper tick-end event, or per-player 1-tick entity tasks on Folia
        // (attached/cancelled by the connection listener).
        tickMarkers = new ServerTickMarkerService(this, players, ServerTickMarkers.keyOf(setup),
                () -> context.settings(), ServerTickMarkerService.detectFolia());
        tickMarkers.start();
        connections = new ConnectionListener(this, players, alerts, debug, tickMarkers);
        getServer().getPluginManager().registerEvents(connections, this);

        PluginCommand command = getCommand("ac");
        if (command != null) {
            AcCommand executor = new AcCommand(this, messages, players, checks, profiler, alerts, debug,
                    () -> violationLog, this::reloadConfiguration, violations::metrics, () -> governor);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        startHeartbeat(global.heartbeatIntervalMillis());
        startWatchdog();

        // Players already online (plugin loaded late): attach them on their own region threads.
        // Hot reloading is unsupported: their game mode and abilities are only learned from the next
        // corresponding packets.
        for (Player online : getServer().getOnlinePlayers()) {
            online.getScheduler().run(this, task -> connections.attach(online), null);
        }
        getLogger().info("Enabled with " + checks.size() + " checks.");
    }

    @Override
    public void onDisable() {
        if (PacketEvents.getAPI() != null) {
            if (pipeline != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(pipeline);
            }
            if (outboundState != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(outboundState);
            }
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }
        // Flush the optional -Dvaplus.record file (plan item 7).
        dev.vaplus.anticheat.diagnostics.ReplayRecorder.closeActive();
        if (connections != null) {
            connections.shutdown();
        }
        if (tickMarkers != null) {
            tickMarkers.shutdown();
        }
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        if (violationLog != null) {
            violationLog.close();
        }
        if (players != null) {
            players.clear();
        }
    }

    /**
     * Reloads config.yml and messages.yml atomically: both files are parsed and validated first;
     * only if neither failed are the new settings and messages committed. Any broken file keeps
     * the previous configuration and messages unchanged; the error is returned as the sole warning.
     */
    public synchronized List<String> reloadConfiguration() {
        ConfigSnapshot snapshot;
        try {
            snapshot = configService.load();
        } catch (ConfigService.LoadException exception) {
            getLogger().severe(exception.getMessage());
            return List.of(exception.getMessage() + " - keeping the previous configuration");
        }
        MessageCatalog preparedMessages;
        try {
            preparedMessages = messages.prepare();
        } catch (MessageService.LoadException exception) {
            getLogger().severe(exception.getMessage());
            return List.of(exception.getMessage() + " - keeping the previous configuration and messages");
        }
        // Both files validated: commit together.
        messages.apply(preparedMessages);
        List<String> warnings = applySnapshot(snapshot);
        long interval = snapshot.global().heartbeatIntervalMillis();
        if (interval != heartbeatInterval) {
            startHeartbeat(interval);
        }
        return warnings;
    }

    public AnticheatContext context() {
        return context;
    }

    public PlayerDataRegistry players() {
        return players;
    }

    private List<String> applySnapshot(ConfigSnapshot snapshot) {
        context.updateSettings(snapshot.global());
        healthIndicator.update(snapshot.healthIndicator());
        checks.applySettings(snapshot::resolve);
        profiler.setEnabled(snapshot.global().profilingEnabled());
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        for (String id : snapshot.unknownCheckIds(checks.descriptors())) {
            warnings.add("Unknown check '" + id + "' under checks (typo?)");
        }
        for (String warning : warnings) {
            getLogger().warning("[config] " + warning);
        }
        return warnings;
    }

    /**
     * Performance watchdog (plan item 10): once a minute it compares the measured per-player-tick
     * cost and the server TPS with the governor thresholds. While degraded, violations are still
     * detected, logged and alerted, but setbacks/punishments are withheld and the X-Ray prevention
     * sends vanilla chunks. Recovery is automatic once the load is back to normal.
     */
    private void startWatchdog() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }
        watchdogTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> watchPerformance(),
                20L * 60L, 20L * 60L);
    }

    private void watchPerformance() {
        CheckProfiler.Report report = profiler.report();
        double perTick = report.microsPerPlayerTick();
        double tps = serverTps();
        boolean overloaded = players.size() > 0
                && (perTick > PerformanceGovernor.DEGRADE_MICROS_PER_PLAYER_TICK
                || (tps > 0.0 && tps < PerformanceGovernor.DEGRADE_TPS));
        if (overloaded) {
            String reason = String.format(java.util.Locale.ROOT, "%.0f us/tick, %.1f tps", perTick, tps);
            if (!governor.degraded()) {
                getLogger().warning("Performance governor degraded (" + reason
                        + "): alerts/logs stay active, setbacks and punishments are paused"
                        + " and X-Ray prevention sends vanilla chunks");
            }
            governor.degrade(reason);
        } else if (governor.degraded()) {
            governor.recover();
            getLogger().info("Performance governor recovered: full enforcement and X-Ray prevention resumed.");
        }
        for (CheckProfiler.CheckSample sample : report.checks()) {
            if (sample.averageNanos() < 50_000.0 || sample.calls() < 1_000L) {
                continue;
            }
            if (slowChecks.add(sample.slot())) {
                getLogger().warning("Check '" + checks.descriptor(sample.slot()).id() + "' averages "
                        + String.format(java.util.Locale.ROOT, "%.0f", sample.averageNanos())
                        + " ns/call (" + sample.calls() + " calls); consider disabling it or raising its interval");
            }
        }
    }

    private static double serverTps() {
        try {
            double[] tps = Bukkit.getTPS();
            return tps.length > 0 ? tps[0] : -1.0;
        } catch (RuntimeException | LinkageError unavailable) {
            // Not available on this implementation: the governor then only uses the profiler budget.
            return -1.0;
        }
    }

    private void startHeartbeat(long intervalMillis) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        heartbeatInterval = intervalMillis;
        heartbeatTask = Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> {
            for (PlayerData data : players.all()) {
                data.scheduleHeartbeat();
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }
}
