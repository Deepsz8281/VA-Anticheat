package dev.vaplus.anticheat.command;

import dev.vaplus.anticheat.alert.AlertService;
import dev.vaplus.anticheat.alert.DebugService;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckRegistry;
import dev.vaplus.anticheat.config.MessageService;
import dev.vaplus.anticheat.config.Placeholders;
import dev.vaplus.anticheat.platform.Permissions;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.PlayerDataRegistry;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.profiling.CheckProfiler;
import dev.vaplus.anticheat.profiling.PerformanceGovernor;
import dev.vaplus.anticheat.violation.CheckMetrics;
import dev.vaplus.anticheat.violation.ViolationLog;
import dev.vaplus.anticheat.violation.ViolationRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/** {@code /ac alerts|info|logs|profile|debug|reload}. */
public final class AcCommand implements CommandExecutor, TabCompleter {

    private static final int LOG_PAGE_SIZE = 10;
    private static final int LOG_MAX_PAGE = 10_000;
    private static final int INFO_MAX_VIOLATIONS = 8;
    private static final int PROFILE_MAX_ENTRIES = 10;
    private static final double TICK_BUDGET_MICROS = 500.0;

    private final Plugin plugin;
    private final MessageService messages;
    private final PlayerDataRegistry players;
    private final CheckRegistry checks;
    private final CheckProfiler profiler;
    private final AlertService alerts;
    private final DebugService debug;
    private final Supplier<ViolationLog> log;
    private final Supplier<List<String>> reloader;
    private final Supplier<CheckMetrics> metrics;
    private final Supplier<PerformanceGovernor> governor;

    public AcCommand(Plugin plugin, MessageService messages, PlayerDataRegistry players, CheckRegistry checks,
                     CheckProfiler profiler, AlertService alerts, DebugService debug,
                     Supplier<ViolationLog> log, Supplier<List<String>> reloader) {
        this(plugin, messages, players, checks, profiler, alerts, debug, log, reloader,
                () -> new CheckMetrics(0), PerformanceGovernor::new);
    }

    public AcCommand(Plugin plugin, MessageService messages, PlayerDataRegistry players, CheckRegistry checks,
                     CheckProfiler profiler, AlertService alerts, DebugService debug,
                     Supplier<ViolationLog> log, Supplier<List<String>> reloader,
                     Supplier<CheckMetrics> metrics, Supplier<PerformanceGovernor> governor) {
        this.plugin = plugin;
        this.messages = messages;
        this.players = players;
        this.checks = checks;
        this.profiler = profiler;
        this.alerts = alerts;
        this.debug = debug;
        this.log = log;
        this.reloader = reloader;
        this.metrics = metrics;
        this.governor = governor;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "alerts" -> alerts(sender);
            case "info" -> info(sender, args);
            case "logs" -> logs(sender, args);
            case "profile" -> profile(sender, args);
            case "metrics" -> metrics(sender, args);
            case "debug" -> debug(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        if (!sender.hasPermission(Permissions.ALERTS) && !sender.hasPermission(Permissions.ADMIN)) {
            sender.sendMessage(messages.render("no-permission"));
            return;
        }
        for (Component line : messages.renderLines("help")) {
            sender.sendMessage(line);
        }
    }

    private void alerts(CommandSender sender) {
        if (!require(sender, Permissions.ALERTS)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.render("player-only"));
            return;
        }
        boolean enabled = alerts.toggle(player);
        sender.sendMessage(messages.render(enabled ? "alerts-enabled" : "alerts-disabled"));
    }

    private void info(CommandSender sender, String[] args) {
        if (!require(sender, Permissions.ALERTS)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.render("usage", "usage", "/ac info <player>"));
            return;
        }
        PlayerData data = players.findByName(args[1]);
        if (data == null) {
            sender.sendMessage(messages.render("unknown-player", "target", args[1]));
            return;
        }
        // Values written on the netty thread are read here without synchronisation; they may be a
        // packet out of date, which is fine for a diagnostic view.
        List<String> exemptions = new ArrayList<>();
        for (ExemptionCause cause : data.exemptions().activeCauses()) {
            exemptions.add(cause.name().toLowerCase(Locale.ROOT));
        }
        String[] pairs = {
                "player", data.name(),
                "version", data.clientVersion().getReleaseName(),
                "brand", data.clientBrand(),
                "ping", formatPing(data.acks().rttMillis()),
                "ping_avg", formatPing(data.acks().smoothedRttMillis()),
                "pending", Integer.toString(data.acks().pendingCount()),
                "gamemode", data.abilities().gameMode().name().toLowerCase(Locale.ROOT),
                "flying", Boolean.toString(data.abilities().flying()),
                "bypass", Boolean.toString(data.bypassing()),
                "ticks", Long.toString(data.clientTicks()),
                "tick_mode", data.tickEndMode() ? "tick-end" : "movement",
                "x", String.format(Locale.ROOT, "%.3f", data.movement().x()),
                "y", String.format(Locale.ROOT, "%.3f", data.movement().y()),
                "z", String.format(Locale.ROOT, "%.3f", data.movement().z()),
                "ground", Boolean.toString(data.movement().onGround()),
                "exemptions", exemptions.isEmpty() ? "-" : String.join(", ", exemptions)
        };
        List<Component> lines = new ArrayList<>(messages.renderLines("info", pairs));

        // Snapshot the levels first: they decay continuously, and sorting on a live value can break
        // the comparator contract.
        record Flagged(String name, double level, int count) {
        }
        List<Flagged> flagged = new ArrayList<>();
        for (Check check : data.checks().all()) {
            if (check.violationCount() > 0) {
                flagged.add(new Flagged(check.descriptor().displayName(), check.violationLevel(), check.violationCount()));
            }
        }
        flagged.sort(Comparator.comparingDouble(Flagged::level).reversed());
        if (flagged.isEmpty()) {
            lines.add(messages.render("info-no-violations"));
        }
        for (int i = 0; i < Math.min(INFO_MAX_VIOLATIONS, flagged.size()); i++) {
            Flagged entry = flagged.get(i);
            lines.add(messages.render("info-violation",
                    "check", entry.name(),
                    "vl", Placeholders.formatLevel(entry.level()),
                    "count", Integer.toString(entry.count())));
        }
        deliver(sender, lines);
    }

    private void logs(CommandSender sender, String[] args) {
        if (!require(sender, Permissions.ALERTS)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.render("usage", "usage", "/ac logs <player> [page]"));
            return;
        }
        int page = 1;
        if (args.length > 2) {
            try {
                page = Math.max(1, Math.min(LOG_MAX_PAGE, Integer.parseInt(args[2])));
            } catch (NumberFormatException exception) {
                sender.sendMessage(messages.render("usage", "usage", "/ac logs <player> [page]"));
                return;
            }
        }
        UUID target;
        String targetName;
        PlayerData online = players.findByName(args[1]);
        if (online != null) {
            target = online.uuid();
            targetName = online.name();
        } else {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (cached == null) {
                sender.sendMessage(messages.render("unknown-player", "target", args[1]));
                return;
            }
            target = cached.getUniqueId();
            targetName = cached.getName() != null ? cached.getName() : args[1];
        }
        int shownPage = page;
        long now = System.currentTimeMillis();
        log.get().query(target, (page - 1) * LOG_PAGE_SIZE, LOG_PAGE_SIZE).whenComplete((records, error) -> {
            List<Component> lines = new ArrayList<>();
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Violation log query failed", error);
                lines.add(messages.render("logs-error"));
            } else if (records.isEmpty()) {
                lines.add(messages.render("logs-empty", "player", targetName, "page", Integer.toString(shownPage)));
            } else {
                lines.add(messages.render("logs-header", "player", targetName, "page", Integer.toString(shownPage)));
                for (ViolationRecord record : records) {
                    lines.add(messages.render("logs-entry",
                            "ago", formatAgo(now - record.timestampMillis()),
                            "check", record.checkId(),
                            "vl", Placeholders.formatLevel(record.violationLevel()),
                            "ping", record.pingMillis() < 0 ? "?" : Long.toString(record.pingMillis()),
                            "details", record.details()));
                }
            }
            deliver(sender, lines);
        });
    }

    private void profile(CommandSender sender, String[] args) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
            profiler.reset();
            sender.sendMessage(messages.render("profile-reset"));
            return;
        }
        CheckProfiler.Report report = profiler.report();
        double perTick = report.microsPerPlayerTick();
        List<Component> lines = new ArrayList<>(messages.renderLines("profile-header",
                "enabled", Boolean.toString(profiler.enabled()),
                "window", String.format(Locale.ROOT, "%.0f", report.windowNanos() / 1.0E9),
                "players", Integer.toString(players.size()),
                "packets", Long.toString(report.packets()),
                "ticks", Long.toString(report.clientTicks()),
                "packet_avg", String.format(Locale.ROOT, "%.0f", report.averagePacketNanos()),
                "tick_avg", String.format(Locale.ROOT, "%.1f", perTick),
                "budget", String.format(Locale.ROOT, "%.0f", TICK_BUDGET_MICROS)));
        lines.add(messages.render(perTick <= TICK_BUDGET_MICROS ? "profile-within-budget" : "profile-over-budget"));
        long totalCheckNanos = 0L;
        for (CheckProfiler.CheckSample sample : report.checks()) {
            totalCheckNanos += sample.totalNanos();
        }
        List<CheckProfiler.CheckSample> samples = report.checks();
        for (int i = 0; i < Math.min(PROFILE_MAX_ENTRIES, samples.size()); i++) {
            CheckProfiler.CheckSample sample = samples.get(i);
            double share = totalCheckNanos == 0 ? 0.0 : sample.totalNanos() * 100.0 / totalCheckNanos;
            lines.add(messages.render("profile-entry",
                    "check", checks.descriptor(sample.slot()).displayName(),
                    "avg", String.format(Locale.ROOT, "%.0f", sample.averageNanos()),
                    "calls", Long.toString(sample.calls()),
                    "share", String.format(Locale.ROOT, "%.1f", share)));
        }
        deliver(sender, lines);
    }

    /** Per-check flag/unique-player counters plus the performance governor state (plan items 5/6). */
    private void metrics(CommandSender sender, String[] args) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        CheckMetrics counters = metrics.get();
        if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
            counters.reset();
            sender.sendMessage(Component.text("VA+ metrics reset."));
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("VA+ metrics - engine: " + governor.get().describe()));
        lines.add(Component.text("Players: " + players.size() + ", checks: " + checks.size()));
        List<CheckMetrics.Entry> entries = counters.entries(checks);
        if (entries.isEmpty()) {
            lines.add(Component.text("No violations recorded yet."));
        }
        for (int i = 0; i < Math.min(12, entries.size()); i++) {
            CheckMetrics.Entry entry = entries.get(i);
            lines.add(Component.text(entry.checkId() + ": " + entry.flags() + " flags / "
                    + entry.players() + " players ("
                    + String.format(Locale.ROOT, "%.1f", entry.flagsPerPlayer()) + "/player)"));
        }
        deliver(sender, lines);
    }

    private void debug(CommandSender sender, String[] args) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.render("usage", "usage", "/ac debug <check|all> [player]"));
            return;
        }
        String checkId = null;
        String checkName = "all";
        if (!args[1].equalsIgnoreCase("all")) {
            CheckDescriptor descriptor = checks.find(args[1]).orElse(null);
            if (descriptor == null) {
                sender.sendMessage(messages.render("unknown-check", "check", args[1]));
                return;
            }
            checkId = descriptor.id();
            checkName = descriptor.displayName();
        }
        UUID target = null;
        String targetName = "everyone";
        if (args.length > 2) {
            PlayerData data = players.findByName(args[2]);
            if (data == null) {
                sender.sendMessage(messages.render("unknown-player", "target", args[2]));
                return;
            }
            target = data.uuid();
            targetName = data.name();
        }
        if (!DebugService.supportsViewer(sender)) {
            sender.sendMessage(messages.render("player-only"));
            return;
        }
        UUID viewerId = sender instanceof Player player ? player.getUniqueId() : DebugService.CONSOLE_ID;
        boolean enabled = debug.toggle(sender, viewerId, target, checkId);
        sender.sendMessage(messages.render(enabled ? "debug-enabled" : "debug-disabled",
                "check", checkName, "target", targetName));
    }

    private void reload(CommandSender sender) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        List<String> warnings = reloader.get();
        sender.sendMessage(messages.render("reload-done", "warnings", Integer.toString(warnings.size())));
        for (String warning : warnings) {
            sender.sendMessage(messages.render("reload-warning", "warning", warning));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission(Permissions.ALERTS)) {
                options.addAll(List.of("alerts", "info", "logs"));
            }
            if (sender.hasPermission(Permissions.ADMIN)) {
                options.addAll(List.of("profile", "metrics", "debug", "reload"));
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "info", "logs" -> addPlayerNames(options);
                case "debug" -> {
                    options.add("all");
                    for (CheckDescriptor descriptor : checks.descriptors()) {
                        options.add(descriptor.id());
                    }
                }
                case "profile", "metrics" -> options.add("reset");
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("debug")) {
            addPlayerNames(options);
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private void addPlayerNames(List<String> into) {
        for (PlayerData data : players.all()) {
            into.add(data.name());
        }
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(messages.render("no-permission"));
        return false;
    }

    /** Sends lines on the recipient's own thread (log queries may complete on another thread). */
    private void deliver(CommandSender sender, List<Component> lines) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> {
                for (Component line : lines) {
                    player.sendMessage(line);
                }
            }, null);
        } else if (sender instanceof ConsoleCommandSender) {
            for (Component line : lines) {
                sender.sendMessage(line);
            }
        } else {
            // Command blocks, RCON and other senders are not safe to message from arbitrary threads.
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                for (Component line : lines) {
                    sender.sendMessage(line);
                }
            });
        }
    }

    private static String formatPing(long millis) {
        return millis < 0 ? "?" : Long.toString(millis);
    }

    static String formatAgo(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }
}
