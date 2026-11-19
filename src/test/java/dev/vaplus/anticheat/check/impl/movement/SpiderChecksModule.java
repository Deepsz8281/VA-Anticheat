package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.simulation.SimulationModule;

import java.util.Objects;

/** Registers {@link SpiderCheck}; install after {@link SimulationModule} (needs its world and velocity components). */
public final class SpiderChecksModule implements AnticheatModule {

    private final SimulationModule simulation;

    public SpiderChecksModule(SimulationModule simulation) {
        this.simulation = Objects.requireNonNull(simulation, "simulation");
    }

    @Override
    public String name() {
        return "spider-checks";
    }

    @Override
    public void register(ModuleContext context) {
        var worldKey = simulation.worldMirrorKey();
        var velocityKey = simulation.velocityKey();
        context.registerCheck(SpiderCheck.DESCRIPTOR, player -> new SpiderCheck(player, worldKey, velocityKey));
    }
}
