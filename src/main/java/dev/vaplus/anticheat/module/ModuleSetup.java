package dev.vaplus.anticheat.module;

import dev.vaplus.anticheat.pipeline.ClientTickListener;
import dev.vaplus.anticheat.pipeline.InboundTracker;
import dev.vaplus.anticheat.pipeline.MovementHook;
import dev.vaplus.anticheat.pipeline.OutboundTracker;
import dev.vaplus.anticheat.simulation.MovementSimulator;

import java.util.List;

/**
 * Immutable result of all module registrations: everything the pipeline and player containers
 * need besides the check registry.
 *
 * @param simulator the installed movement engine, or {@code null} if no module provides one
 */
public record ModuleSetup(
        List<InboundTracker> inboundTrackers,
        List<OutboundTracker> outboundTrackers,
        List<MovementHook> movementHooks,
        List<ClientTickListener> tickListeners,
        List<ComponentKey<?>> components,
        MovementSimulator simulator) {

    public static final ModuleSetup EMPTY = new ModuleSetup(List.of(), List.of(), List.of(), List.of(), List.of(), null);

    public ModuleSetup {
        inboundTrackers = List.copyOf(inboundTrackers);
        outboundTrackers = List.copyOf(outboundTrackers);
        movementHooks = List.copyOf(movementHooks);
        tickListeners = List.copyOf(tickListeners);
        components = List.copyOf(components);
    }
}
