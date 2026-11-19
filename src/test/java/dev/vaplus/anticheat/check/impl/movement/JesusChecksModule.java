package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Objects;

/**
 * Test-only module: registers just {@link JesusCheck}. Install after {@link SimulationModule}: the check reads the per-player
 * {@link WorldMirror} component that module registers.
 */
public final class JesusChecksModule implements AnticheatModule {

    private final SimulationModule simulation;

    public JesusChecksModule(SimulationModule simulation) {
        this.simulation = Objects.requireNonNull(simulation, "simulation");
    }

    @Override
    public String name() {
        return "jesus-checks";
    }

    @Override
    public void register(ModuleContext context) {
        ComponentKey<WorldMirror> worldKey = simulation.worldMirrorKey();
        context.registerCheck(JesusCheck.DESCRIPTOR, player -> new JesusCheck(player, worldKey));
    }

    /** Descriptors in registration order (tests, diagnostics). */
    public static CheckDescriptor[] descriptors() {
        return new CheckDescriptor[]{JesusCheck.DESCRIPTOR};
    }
}
