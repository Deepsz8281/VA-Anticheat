package dev.vaplus.anticheat.module;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckFactory;
import dev.vaplus.anticheat.check.CheckRegistry;
import dev.vaplus.anticheat.pipeline.ClientTickListener;
import dev.vaplus.anticheat.pipeline.InboundTracker;
import dev.vaplus.anticheat.pipeline.MovementHook;
import dev.vaplus.anticheat.pipeline.OutboundTracker;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.MovementSimulator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Registration surface handed to every {@link AnticheatModule} during enable. Order of
 * registration is the order of execution for trackers, hooks and tick listeners.
 */
public final class ModuleContext {

    private final CheckRegistry checks;
    private final List<InboundTracker> inbound = new ArrayList<>();
    private final List<OutboundTracker> outbound = new ArrayList<>();
    private final List<MovementHook> movementHooks = new ArrayList<>();
    private final List<ClientTickListener> tickListeners = new ArrayList<>();
    private final List<ComponentKey<?>> components = new ArrayList<>();
    private final Set<String> componentNames = new HashSet<>();
    private MovementSimulator simulator;
    private String simulatorOwner;
    private String currentModule = "?";
    private boolean closed;

    public ModuleContext(CheckRegistry checks) {
        this.checks = Objects.requireNonNull(checks, "checks");
    }

    /** Runs the registration of one module; used by the bootstrap. */
    public void install(AnticheatModule module) {
        requireOpen();
        currentModule = module.name();
        module.register(this);
        currentModule = "?";
    }

    public void registerCheck(CheckDescriptor descriptor, CheckFactory factory) {
        requireOpen();
        checks.register(descriptor, factory);
    }

    public void addInboundTracker(InboundTracker tracker) {
        requireOpen();
        inbound.add(Objects.requireNonNull(tracker, "tracker"));
    }

    public void addOutboundTracker(OutboundTracker tracker) {
        requireOpen();
        outbound.add(Objects.requireNonNull(tracker, "tracker"));
    }

    public void addMovementHook(MovementHook hook) {
        requireOpen();
        movementHooks.add(Objects.requireNonNull(hook, "hook"));
    }

    public void addTickListener(ClientTickListener listener) {
        requireOpen();
        tickListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Registers a per-player component; the factory runs once per player before checks are created. */
    public <T> ComponentKey<T> registerComponent(String name, Class<T> type, Function<PlayerData, ? extends T> factory) {
        requireOpen();
        if (!componentNames.add(name)) {
            throw new IllegalArgumentException("Duplicate component name: " + name);
        }
        ComponentKey<T> key = new ComponentKey<>(components.size(), name, type, factory);
        components.add(key);
        return key;
    }

    /** Installs the movement engine; only one module may do so. */
    public void installSimulator(MovementSimulator engine) {
        requireOpen();
        if (simulator != null) {
            throw new IllegalStateException("Simulator already installed by module " + simulatorOwner);
        }
        simulator = Objects.requireNonNull(engine, "engine");
        simulatorOwner = currentModule;
    }

    /** Freezes the check registry and returns the collected setup. */
    public ModuleSetup complete() {
        requireOpen();
        closed = true;
        checks.freeze();
        return new ModuleSetup(inbound, outbound, movementHooks, tickListeners, components, simulator);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Module registration is closed");
        }
    }
}
