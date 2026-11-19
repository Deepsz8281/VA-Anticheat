package dev.vaplus.anticheat.obfuscation;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleContext;

import java.util.Objects;

/**
 * Anti-health-indicator prevention (see {@link HealthIndicatorObfuscator}). Install last: its
 * outbound tracker must run after every state tracker so those still read the real values.
 * Settings come from the {@code anti-health-indicator} section and are pushed via {@link #update}.
 */
public final class HealthIndicatorModule implements AnticheatModule {

    private final HealthIndicatorSpoofer.Indexes indexes;
    private HealthIndicatorObfuscator obfuscator;
    private ComponentKey<HealthIndicatorState> stateKey;

    public HealthIndicatorModule(HealthIndicatorSpoofer.Indexes indexes) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
    }

    @Override
    public String name() {
        return "anti-health-indicator";
    }

    @Override
    public void register(ModuleContext context) {
        stateKey = context.registerComponent("health-indicator", HealthIndicatorState.class,
                player -> new HealthIndicatorState());
        obfuscator = new HealthIndicatorObfuscator(stateKey, indexes);
        context.addOutboundTracker(obfuscator);
    }

    /** Applies (re)loaded settings. */
    public void update(HealthIndicatorSettings settings) {
        if (obfuscator == null) {
            throw new IllegalStateException("HealthIndicatorModule is not registered yet");
        }
        obfuscator.update(settings);
    }

    public HealthIndicatorObfuscator obfuscator() {
        return obfuscator;
    }

    public ComponentKey<HealthIndicatorState> stateKey() {
        return stateKey;
    }
}
