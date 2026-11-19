package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import dev.vaplus.anticheat.module.ModuleContext;

/**
 * Phase-2 movement checks: {@link SpeedCheck}, {@link FlyCheck}, {@link NoFallCheck} and
 * {@link PredictionCheck}. They only read the {@code SimulationResult} filled by the installed
 * movement simulator, so install this module after {@code SimulationModule}. Checks that need the
 * world mirror or velocity state (Spider, ...) are registered only when the simulation module is passed.
 */
public final class MovementChecksModule implements AnticheatModule {

    /** Source of the world-mirror and velocity component keys; {@code null} in simulation-free tests. */
    private final SimulationModule simulation;

    /** Without a simulation module: only the checks that read {@code SimulationResult} are registered. */
    public MovementChecksModule() {
        this(null);
    }

    /** Production: also registers the checks that need the world mirror or velocity state. */
    public MovementChecksModule(SimulationModule simulation) {
        this.simulation = simulation;
    }

    @Override
    public String name() {
        return "movement-checks";
    }

    @Override
    public void register(ModuleContext context) {
        context.registerCheck(FlyCheck.DESCRIPTOR, FlyCheck::new);
        context.registerCheck(NoFallCheck.DESCRIPTOR, NoFallCheck::new);
        context.registerCheck(PredictionCheck.DESCRIPTOR, PredictionCheck::new);
        context.registerCheck(NoSlowCheck.DESCRIPTOR, NoSlowCheck::new);
        context.registerCheck(SprintCheck.DESCRIPTOR, SprintCheck::new);
        if (simulation == null) {
            context.registerCheck(SpeedCheck.DESCRIPTOR, SpeedCheck::new);
            context.registerCheck(VelocityCheck.DESCRIPTOR, VelocityCheck::new);
            return;
        }
        ComponentKey<WorldMirror> worldKey = simulation.worldMirrorKey();
        ComponentKey<VelocityState> velocityKey = simulation.velocityKey();
        context.registerCheck(SpeedCheck.DESCRIPTOR, player -> new SpeedCheck(player, worldKey, velocityKey));
        context.registerCheck(VelocityCheck.DESCRIPTOR, player -> new VelocityCheck(player, worldKey, velocityKey));
        // Runs before the engine consumes acknowledged velocities (VelocityCheck distance signal).
        context.addMovementHook((player, update) -> {
            VelocityCheck velocity = player.checks().get(VelocityCheck.class);
            if (velocity != null) {
                velocity.beforeSimulation();
            }
        });
        context.registerCheck(SpiderCheck.DESCRIPTOR, player -> new SpiderCheck(player, worldKey, velocityKey));
        context.registerCheck(PhaseCheck.DESCRIPTOR, player -> new PhaseCheck(player, worldKey));
        context.registerCheck(StepCheck.DESCRIPTOR, player -> new StepCheck(player, worldKey, velocityKey));
        context.registerCheck(HighJumpCheck.DESCRIPTOR, player -> new HighJumpCheck(player, worldKey, velocityKey));
        context.registerCheck(FastLadderCheck.DESCRIPTOR, player -> new FastLadderCheck(player, worldKey, velocityKey));
        context.registerCheck(JesusCheck.DESCRIPTOR, player -> new JesusCheck(player, worldKey));
        context.registerCheck(VehicleCheck.DESCRIPTOR, player -> new VehicleCheck(player, worldKey));
        context.registerCheck(ElytraCheck.DESCRIPTOR, player -> new ElytraCheck(player, worldKey, velocityKey));
    }
}
