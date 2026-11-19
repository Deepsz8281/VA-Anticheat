package dev.vaplus.anticheat.testutil;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckErrorReporter;
import dev.vaplus.anticheat.check.CheckFactory;
import dev.vaplus.anticheat.check.CheckRegistry;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.profiling.CheckProfiler;
import dev.vaplus.anticheat.simulation.InactiveSimulator;
import dev.vaplus.anticheat.simulation.MovementSimulator;
import dev.vaplus.anticheat.violation.InMemoryViolationLog;
import dev.vaplus.anticheat.violation.ViolationDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Builds a fully wired {@link AnticheatContext} out of fakes. Typical use:
 * <pre>{@code
 * TestContexts.Harness harness = TestContexts.builder().check(descriptor, factory).build();
 * PlayerData player = harness.newPlayer();
 * }</pre>
 */
public final class TestContexts {

    private TestContexts() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Harness without any check types. */
    public static Harness empty() {
        return builder().build();
    }

    /** Mutable helper to derive {@link GlobalSettings} from the defaults. */
    public static GlobalSettingsBuilder globals() {
        return new GlobalSettingsBuilder();
    }

    public static final class GlobalSettingsBuilder {
        private GlobalSettings base = GlobalSettings.defaults();
        private long alertMinIntervalMillis = base.alertMinIntervalMillis();
        private long ackTimeoutMillis = base.ackTimeoutMillis();
        private boolean setbacksEnabled = base.setbacksEnabled();
        private long setbackCooldownMillis = base.setbackCooldownMillis();
        /** Single-violation setbacks by default, so mechanism tests stay focused. */
        private int setbackConfirmations = 1;
        private long setbackWindowMillis = GlobalSettings.DEFAULT_SETBACK_WINDOW_MILLIS;

        public GlobalSettingsBuilder alertMinIntervalMillis(long value) {
            this.alertMinIntervalMillis = value;
            return this;
        }

        public GlobalSettingsBuilder ackTimeoutMillis(long value) {
            this.ackTimeoutMillis = value;
            return this;
        }

        public GlobalSettingsBuilder setbacksEnabled(boolean value) {
            this.setbacksEnabled = value;
            return this;
        }

        public GlobalSettingsBuilder setbackCooldownMillis(long value) {
            this.setbackCooldownMillis = value;
            return this;
        }

        public GlobalSettingsBuilder setbackConfirmations(int value) {
            this.setbackConfirmations = value;
            return this;
        }

        public GlobalSettingsBuilder setbackWindowMillis(long value) {
            this.setbackWindowMillis = value;
            return this;
        }

        public GlobalSettings build() {
            return new GlobalSettings(base.alertsEnableOnJoin(), base.alertsToConsole(), alertMinIntervalMillis,
                    base.heartbeatIntervalMillis(), ackTimeoutMillis, setbacksEnabled, setbackCooldownMillis,
                    base.profilingEnabled(), base.logEntriesPerPlayer(), base.logMaxPlayers(),
                    base.serverTickMarkers(), setbackConfirmations, setbackWindowMillis);
        }
    }

    public static final class Builder {
        private record Registration(CheckDescriptor descriptor, CheckFactory factory) {
        }

        private final List<Registration> registrations = new ArrayList<>();
        private final List<AnticheatModule> modules = new ArrayList<>();
        private ManualTimeSource time = new ManualTimeSource();
        // Mechanism tests use single-violation setbacks; the production default (2) is covered by
        // dedicated confirmation tests and by config.yml.
        private GlobalSettings settings = globals().build();
        private MovementSimulator simulator = InactiveSimulator.INSTANCE;
        private int logPerPlayer = 200;
        private int logMaxPlayers = 500;

        public Builder check(CheckDescriptor descriptor, CheckFactory factory) {
            registrations.add(new Registration(descriptor, factory));
            return this;
        }

        /** Installs a module after the checks registered through {@link #check}. */
        public Builder module(AnticheatModule module) {
            this.modules.add(module);
            return this;
        }

        public Builder time(ManualTimeSource time) {
            this.time = time;
            return this;
        }

        public Builder settings(GlobalSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder simulator(MovementSimulator simulator) {
            this.simulator = simulator;
            return this;
        }

        public Builder log(int perPlayer, int maxPlayers) {
            this.logPerPlayer = perPlayer;
            this.logMaxPlayers = maxPlayers;
            return this;
        }

        public Harness build() {
            CheckRegistry registry = new CheckRegistry();
            ModuleContext modules = new ModuleContext(registry);
            for (Registration registration : registrations) {
                modules.registerCheck(registration.descriptor(), registration.factory());
            }
            for (AnticheatModule module : this.modules) {
                modules.install(module);
            }
            ModuleSetup setup = modules.complete();

            CapturingHandler logHandler = new CapturingHandler();
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(logHandler);

            CheckProfiler profiler = new CheckProfiler(registry.size(), time);
            CheckErrorReporter errors = new CheckErrorReporter(logger, time);
            InMemoryViolationLog violationLog = new InMemoryViolationLog(logPerPlayer, logMaxPlayers);
            RecordingAlertSink alerts = new RecordingAlertSink();
            RecordingPunishmentExecutor punishments = new RecordingPunishmentExecutor();
            RecordingSetbackHandler setbacks = new RecordingSetbackHandler();
            FakeFlagEventPublisher flags = new FakeFlagEventPublisher();
            // Same slot count as production, otherwise /ac metrics would be empty in tests.
            ViolationDispatcher processor = new ViolationDispatcher(flags, violationLog, alerts, punishments,
                    setbacks, registry.size());

            AnticheatContext context = AnticheatContext.builder()
                    .logger(logger)
                    .time(time)
                    .checks(registry)
                    .profiler(profiler)
                    .errors(errors)
                    .violations(processor)
                    .punishments(punishments)
                    .simulator(simulator)
                    .setup(setup)
                    .settings(settings)
                    .build();
            return new Harness(context, time, registry, profiler, errors, logger, logHandler, violationLog, alerts,
                    punishments, setbacks, flags, processor);
        }
    }

    /** Captures log records of the test logger. */
    public static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        public synchronized List<LogRecord> records() {
            return new ArrayList<>(records);
        }
    }

    /** The wired context together with every fake, for assertions. */
    public static final class Harness {
        public final AnticheatContext context;
        public final ManualTimeSource time;
        public final CheckRegistry registry;
        public final CheckProfiler profiler;
        public final CheckErrorReporter errors;
        public final Logger logger;
        public final CapturingHandler logs;
        public final InMemoryViolationLog violationLog;
        public final RecordingAlertSink alerts;
        public final RecordingPunishmentExecutor punishments;
        public final RecordingSetbackHandler setbacks;
        public final FakeFlagEventPublisher flags;
        public final ViolationDispatcher processor;
        private RecordingTransport lastTransport;

        Harness(AnticheatContext context, ManualTimeSource time, CheckRegistry registry, CheckProfiler profiler,
                CheckErrorReporter errors, Logger logger, CapturingHandler logs, InMemoryViolationLog violationLog,
                RecordingAlertSink alerts, RecordingPunishmentExecutor punishments, RecordingSetbackHandler setbacks,
                FakeFlagEventPublisher flags, ViolationDispatcher processor) {
            this.context = context;
            this.time = time;
            this.registry = registry;
            this.profiler = profiler;
            this.errors = errors;
            this.logger = logger;
            this.logs = logs;
            this.violationLog = violationLog;
            this.alerts = alerts;
            this.punishments = punishments;
            this.setbacks = setbacks;
            this.flags = flags;
            this.processor = processor;
        }

        public PlayerData newPlayer() {
            return newPlayer(UUID.randomUUID(), "Steve");
        }

        public PlayerData newPlayer(UUID uuid, String name) {
            return newPlayer(uuid, name, new RecordingTransport());
        }

        public PlayerData newPlayer(UUID uuid, String name, RecordingTransport transport) {
            this.lastTransport = transport;
            return PlayerData.create(context, uuid, name, ClientVersion.V_1_21_4, transport);
        }

        /**
         * Publishes new settings through the registry (as a config reload would) and makes the player's
         * bundle pick them up immediately.
         */
        public void applySettings(PlayerData player, Function<CheckDescriptor, CheckSettings> resolver) {
            registry.applySettings(resolver);
            player.checks().syncSettings();
        }

        /** Transport of the most recently created player. */
        public RecordingTransport lastTransport() {
            return lastTransport;
        }
    }
}
