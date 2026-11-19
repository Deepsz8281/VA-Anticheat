package dev.vaplus.anticheat.check.impl.world;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.obfuscation.XrayObfuscator;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Objects;

/**
 * Visibility checks: {@link XrayCheck} (anti-xray), {@link EspCheck} (anti-esp) and
 * {@link FreecamCheck} (anti-freecam).
 *
 * <p>Install after {@code SimulationModule}: the x-ray and ESP checks read the per-player
 * {@link WorldMirror} that the simulation module registers, and it must already be fed before these
 * checks judge. The module also registers the {@link NearbyPlayers} component and its outbound
 * {@link NearbyPlayersTracker}; positions of other players are not part of any state container yet
 * (the full entity tracker belongs to the combat phase).
 */
public final class VisibilityChecksModule implements AnticheatModule {

    private final SimulationModule simulation;
    private ComponentKey<NearbyPlayers> nearbyKey;

    public VisibilityChecksModule(SimulationModule simulation) {
        this.simulation = Objects.requireNonNull(simulation, "simulation");
    }

    @Override
    public String name() {
        return "visibility-checks";
    }

    @Override
    public void register(ModuleContext context) {
        ComponentKey<WorldMirror> worldKey = simulation.worldMirrorKey();
        nearbyKey = context.registerComponent("nearby-players", NearbyPlayers.class, player -> new NearbyPlayers());
        context.addOutboundTracker(new NearbyPlayersTracker(nearbyKey));
        // Prevention: rewrites outgoing chunk packets. Registered after the simulation module's
        // WorldTracker (module order), so the mirror is fed before the transformer reads it.
        context.addOutboundTracker(new XrayObfuscator(worldKey));

        context.registerCheck(XrayCheck.DESCRIPTOR, player -> new XrayCheck(player, worldKey));
        context.registerCheck(FreecamCheck.DESCRIPTOR, player -> new FreecamCheck(player));
        context.registerCheck(EspCheck.DESCRIPTOR, player -> new EspCheck(player, worldKey, nearbyKey));
        context.registerCheck(FastPlaceCheck.DESCRIPTOR, FastPlaceCheck::new);
        context.registerCheck(ScaffoldCheck.DESCRIPTOR, player -> new ScaffoldCheck(player, worldKey));
        context.registerCheck(GhostHandCheck.DESCRIPTOR, player -> new GhostHandCheck(player, worldKey));
        context.registerCheck(FastBreakCheck.DESCRIPTOR, player -> new FastBreakCheck(player, worldKey));
        context.registerCheck(FastUseCheck.DESCRIPTOR, FastUseCheck::new);
    }

    /** Component of tracked other players (tests, diagnostics). */
    public ComponentKey<NearbyPlayers> nearbyKey() {
        if (nearbyKey == null) {
            throw new IllegalStateException("VisibilityChecksModule is not registered yet");
        }
        return nearbyKey;
    }

    /** Descriptors in registration order (tests, diagnostics). */
    public static CheckDescriptor[] descriptors() {
        return new CheckDescriptor[]{XrayCheck.DESCRIPTOR, FreecamCheck.DESCRIPTOR, EspCheck.DESCRIPTOR, FastPlaceCheck.DESCRIPTOR,
                ScaffoldCheck.DESCRIPTOR, GhostHandCheck.DESCRIPTOR,
                FastBreakCheck.DESCRIPTOR, FastUseCheck.DESCRIPTOR};
    }
}
