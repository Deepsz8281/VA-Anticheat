package dev.vaplus.anticheat;

import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.simulation.InactiveSimulator;
import dev.vaplus.anticheat.simulation.MovementSimulator;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.util.TimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("AnticheatContext")
class AnticheatContextTest {

    @Test
    @DisplayName("the harness exposes the wired services")
    void wiring() {
        Harness harness = TestContexts.empty();
        assertSame(harness.registry, harness.context.checks());
        assertSame(harness.profiler, harness.context.profiler());
        assertSame(harness.errors, harness.context.errors());
        assertSame(harness.processor, harness.context.violations());
        assertSame(harness.punishments, harness.context.punishments());
        assertSame(harness.time, harness.context.time());
        assertSame(InactiveSimulator.INSTANCE, harness.context.simulator());
    }

    @Test
    @DisplayName("simulator and settings can be swapped at runtime but never set to null")
    void runtimeSwap() {
        Harness harness = TestContexts.empty();
        MovementSimulator simulator = (player, update, out) -> out.reset();
        harness.context.installSimulator(simulator);
        assertSame(simulator, harness.context.simulator());
        GlobalSettings settings = TestContexts.globals().setbacksEnabled(false).build();
        harness.context.updateSettings(settings);
        assertSame(settings, harness.context.settings());
        assertThrows(NullPointerException.class, () -> harness.context.installSimulator(null));
        assertThrows(NullPointerException.class, () -> harness.context.updateSettings(null));
    }

    @Test
    @DisplayName("every mandatory service is required by the builder")
    void requiredServices() {
        Harness harness = TestContexts.empty();
        assertThrows(NullPointerException.class, () -> AnticheatContext.builder().build());
        assertThrows(NullPointerException.class, () -> AnticheatContext.builder()
                .logger(harness.logger).time(TimeSource.SYSTEM).checks(harness.registry).profiler(harness.profiler)
                .errors(harness.errors).violations(harness.processor).punishments(harness.punishments)
                .build(), "simulator is missing");
    }
}
