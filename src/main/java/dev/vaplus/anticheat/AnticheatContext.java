package dev.vaplus.anticheat;

import dev.vaplus.anticheat.alert.DebugSink;
import dev.vaplus.anticheat.check.CheckErrorReporter;
import dev.vaplus.anticheat.check.CheckRegistry;
import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.pipeline.ClientTickListener;
import dev.vaplus.anticheat.profiling.CheckProfiler;
import dev.vaplus.anticheat.profiling.PerformanceGovernor;
import dev.vaplus.anticheat.simulation.MovementSimulator;
import dev.vaplus.anticheat.util.TimeSource;
import dev.vaplus.anticheat.violation.PunishmentExecutor;
import dev.vaplus.anticheat.violation.ViolationDispatcher;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Shared services reachable from every {@link dev.vaplus.anticheat.player.PlayerData}. Replaces
 * static singletons so the whole core can be instantiated with fakes in unit tests and replays.
 */
public final class AnticheatContext {

    private final Logger logger;
    private final TimeSource time;
    private final CheckRegistry checks;
    private final CheckProfiler profiler;
    private final CheckErrorReporter errors;
    private final ViolationDispatcher violations;
    private final PunishmentExecutor punishments;
    private final DebugSink debug;
    private final ModuleSetup setup;
    private final PerformanceGovernor governor;
    private final ClientTickListener[] tickListeners;
    private volatile MovementSimulator simulator;
    private volatile GlobalSettings settings;

    private AnticheatContext(Builder builder) {
        this.logger = Objects.requireNonNull(builder.logger, "logger");
        this.time = Objects.requireNonNull(builder.time, "time");
        this.checks = Objects.requireNonNull(builder.checks, "checks");
        this.profiler = Objects.requireNonNull(builder.profiler, "profiler");
        this.errors = Objects.requireNonNull(builder.errors, "errors");
        this.violations = Objects.requireNonNull(builder.violations, "violations");
        this.punishments = Objects.requireNonNull(builder.punishments, "punishments");
        this.debug = Objects.requireNonNull(builder.debug, "debug");
        this.setup = Objects.requireNonNull(builder.setup, "setup");
        this.governor = Objects.requireNonNull(builder.governor, "governor");
        this.tickListeners = setup.tickListeners().toArray(new ClientTickListener[0]);
        MovementSimulator engine = setup.simulator() != null ? setup.simulator() : builder.simulator;
        this.simulator = Objects.requireNonNull(engine, "simulator");
        this.settings = Objects.requireNonNull(builder.settings, "settings");
        if (!checks.frozen()) {
            throw new IllegalStateException("Check registry must be frozen before the context is built");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Logger logger() {
        return logger;
    }

    public TimeSource time() {
        return time;
    }

    public CheckRegistry checks() {
        return checks;
    }

    public CheckProfiler profiler() {
        return profiler;
    }

    public CheckErrorReporter errors() {
        return errors;
    }

    public ViolationDispatcher violations() {
        return violations;
    }

    public PunishmentExecutor punishments() {
        return punishments;
    }

    public DebugSink debug() {
        return debug;
    }

    /** Trackers, hooks, components and engine contributed by modules. */
    public ModuleSetup setup() {
        return setup;
    }

    /** Runtime performance gate: shadow-only mode and paused prevention while degraded. */
    public PerformanceGovernor governor() {
        return governor;
    }

    /** Tick listeners as an array for allocation-free iteration. Do not modify. */
    public ClientTickListener[] tickListeners() {
        return tickListeners;
    }

    public MovementSimulator simulator() {
        return simulator;
    }

    /** Installs the movement engine (phase 2). */
    public void installSimulator(MovementSimulator simulator) {
        this.simulator = Objects.requireNonNull(simulator, "simulator");
    }

    public GlobalSettings settings() {
        return settings;
    }

    public void updateSettings(GlobalSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public static final class Builder {
        private Logger logger;
        private TimeSource time = TimeSource.SYSTEM;
        private CheckRegistry checks;
        private CheckProfiler profiler;
        private CheckErrorReporter errors;
        private ViolationDispatcher violations;
        private PunishmentExecutor punishments;
        private DebugSink debug = DebugSink.NONE;
        private MovementSimulator simulator;
        private PerformanceGovernor governor = new PerformanceGovernor();
        private ModuleSetup setup = ModuleSetup.EMPTY;
        private GlobalSettings settings = GlobalSettings.defaults();

        private Builder() {
        }

        public Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public Builder time(TimeSource time) {
            this.time = time;
            return this;
        }

        public Builder checks(CheckRegistry checks) {
            this.checks = checks;
            return this;
        }

        public Builder profiler(CheckProfiler profiler) {
            this.profiler = profiler;
            return this;
        }

        public Builder errors(CheckErrorReporter errors) {
            this.errors = errors;
            return this;
        }

        public Builder violations(ViolationDispatcher violations) {
            this.violations = violations;
            return this;
        }

        public Builder punishments(PunishmentExecutor punishments) {
            this.punishments = punishments;
            return this;
        }

        public Builder debug(DebugSink debug) {
            this.debug = debug;
            return this;
        }

        public Builder simulator(MovementSimulator simulator) {
            this.simulator = simulator;
            return this;
        }

        public Builder governor(PerformanceGovernor governor) {
            this.governor = Objects.requireNonNull(governor, "governor");
            return this;
        }

        public Builder setup(ModuleSetup setup) {
            this.setup = setup;
            return this;
        }

        public Builder settings(GlobalSettings settings) {
            this.settings = settings;
            return this;
        }

        public AnticheatContext build() {
            return new AnticheatContext(this);
        }
    }
}
