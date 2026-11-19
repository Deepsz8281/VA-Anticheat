package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.simulation.SimulationModule;

import java.util.Objects;

/** Test-only module: registers just {@link StepCheck}; needs the world mirror and velocity state of the {@link SimulationModule}. */
public final class StepChecksModule implements AnticheatModule {

    private final SimulationModule simulation;

    public StepChecksModule(SimulationModule simulation) {
        this.simulation = Objects.requireNonNull(simulation, "simulation");
    }

    @Override
    public String name() {
        return "step-checks";
    }

    @Override
    public void register(ModuleContext context) {
        var world = simulation.worldMirrorKey();
        var velocity = simulation.velocityKey();
        context.registerCheck(StepCheck.DESCRIPTOR, player -> new StepCheck(player, world, velocity));
    }
}
