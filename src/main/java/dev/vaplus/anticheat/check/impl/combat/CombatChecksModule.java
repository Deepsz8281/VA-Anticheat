package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Objects;

/**
 * Combat checks: {@link ReachCheck}, {@link ThroughWallCheck}, {@link KillauraCheck},
 * {@link AutoclickerCheck}, {@link TriggerbotCheck}, {@link HitboxCheck}, {@link CriticalsCheck},
 * {@link MaceCheck}, {@link AimCheck} and {@link SilentRotationCheck}.
 *
 * <p>Installed after {@code VisibilityChecksModule} (it resolves that module's
 * {@link NearbyPlayers} component) and after {@code SimulationModule} (world mirror). Combat checks
 * only judge targets the server actually sent to the viewer, so an entity the client cannot see
 * never produces a flag.
 */
public final class CombatChecksModule implements AnticheatModule {

    private final SimulationModule simulation;
    private final VisibilityChecksModule visibility;

    public CombatChecksModule(SimulationModule simulation, VisibilityChecksModule visibility) {
        this.simulation = Objects.requireNonNull(simulation, "simulation");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
    }

    @Override
    public String name() {
        return "combat-checks";
    }

    @Override
    public void register(ModuleContext context) {
        ComponentKey<NearbyPlayers> tracked = visibility.nearbyKey();
        ComponentKey<WorldMirror> world = simulation.worldMirrorKey();
        context.registerCheck(ReachCheck.DESCRIPTOR, player -> new ReachCheck(player, tracked));
        context.registerCheck(ThroughWallCheck.DESCRIPTOR, player -> new ThroughWallCheck(player, tracked, world));
        context.registerCheck(KillauraCheck.DESCRIPTOR, player -> new KillauraCheck(player, tracked));
        context.registerCheck(AutoclickerCheck.DESCRIPTOR, AutoclickerCheck::new);
        context.registerCheck(TriggerbotCheck.DESCRIPTOR, player -> new TriggerbotCheck(player, tracked));
        context.registerCheck(HitboxCheck.DESCRIPTOR, player -> new HitboxCheck(player, tracked));
        context.registerCheck(CriticalsCheck.DESCRIPTOR,
                player -> new CriticalsCheck(player, world, simulation.velocityKey()));
        context.registerCheck(MaceCheck.DESCRIPTOR, player -> new MaceCheck(player, simulation.velocityKey(), world));
        context.registerCheck(AimCheck.DESCRIPTOR, player -> new AimCheck(player, tracked));
        context.registerCheck(InteractCheck.DESCRIPTOR, player -> new InteractCheck(player, tracked));
        context.registerCheck(SilentRotationCheck.DESCRIPTOR,
                player -> new SilentRotationCheck(player, world, simulation.velocityKey()));
    }

    /** Descriptors in registration order (tests, diagnostics). */
    public static CheckDescriptor[] descriptors() {
        return new CheckDescriptor[]{ReachCheck.DESCRIPTOR, ThroughWallCheck.DESCRIPTOR,
                KillauraCheck.DESCRIPTOR, AutoclickerCheck.DESCRIPTOR, TriggerbotCheck.DESCRIPTOR, HitboxCheck.DESCRIPTOR,
                CriticalsCheck.DESCRIPTOR, MaceCheck.DESCRIPTOR, AimCheck.DESCRIPTOR,
                SilentRotationCheck.DESCRIPTOR, InteractCheck.DESCRIPTOR};
    }
}
