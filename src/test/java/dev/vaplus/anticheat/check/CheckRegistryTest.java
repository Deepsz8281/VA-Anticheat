package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.profiling.CheckProfiler;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckRegistry")
class CheckRegistryTest {

    private static CheckFactory probeFactory(CheckDescriptor descriptor) {
        return ProbeChecks.factory(descriptor, ProbeChecks.Probe::new);
    }

    @Test
    @DisplayName("duplicate ids are rejected, also across different categories")
    void duplicateId() {
        CheckRegistry registry = new CheckRegistry();
        CheckDescriptor first = Descriptors.movement("speed");
        registry.register(first, probeFactory(first));
        CheckDescriptor second = Descriptors.combat("speed");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.register(second, probeFactory(second)));
        assertTrue(error.getMessage().contains("speed"));
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("registering after freeze is rejected")
    void registerAfterFreeze() {
        CheckRegistry registry = new CheckRegistry();
        registry.freeze();
        CheckDescriptor descriptor = Descriptors.movement("speed");
        assertThrows(IllegalStateException.class, () -> registry.register(descriptor, probeFactory(descriptor)));
        assertTrue(registry.frozen());
    }

    @Test
    @DisplayName("null descriptor or factory is rejected")
    void nullArguments() {
        CheckRegistry registry = new CheckRegistry();
        CheckDescriptor descriptor = Descriptors.movement("speed");
        assertThrows(NullPointerException.class, () -> registry.register(null, probeFactory(descriptor)));
        assertThrows(NullPointerException.class, () -> registry.register(descriptor, null));
    }

    @Test
    @DisplayName("entries, descriptors and settings need a frozen registry")
    void requiresFreeze() {
        CheckRegistry registry = new CheckRegistry();
        assertThrows(IllegalStateException.class, registry::entries);
        assertThrows(IllegalStateException.class, registry::descriptors);
        assertThrows(IllegalStateException.class, () -> registry.applySettings(d -> null));
    }

    @Test
    @DisplayName("freeze installs code defaults and is idempotent")
    void freezeInstallsDefaults() {
        CheckRegistry registry = new CheckRegistry();
        CheckDescriptor descriptor = Descriptors.of("a", CheckCategory.COMBAT, d -> d.withBufferLimit(7));
        registry.register(descriptor, probeFactory(descriptor));
        registry.freeze();
        int epoch = registry.epoch();
        registry.freeze();
        assertEquals(epoch, registry.epoch(), "second freeze must not bump the epoch");
        assertEquals(7.0, registry.settings(0).bufferLimit());
        assertEquals(descriptor.defaults().enabled(), registry.settings(0).enabled());
        assertEquals(List.of(), registry.settings(0).actions());
    }

    @Test
    @DisplayName("slots follow registration order; find is case-insensitive and null-safe")
    void slotsAndFind() {
        CheckRegistry registry = new CheckRegistry();
        CheckDescriptor a = Descriptors.movement("alpha");
        CheckDescriptor b = Descriptors.combat("beta");
        registry.register(a, probeFactory(a));
        registry.register(b, probeFactory(b));
        registry.freeze();
        assertEquals(List.of(a, b), registry.descriptors());
        assertSame(b, registry.descriptor(1));
        assertEquals(0, registry.entries().get(0).slot());
        assertEquals(1, registry.entries().get(1).slot());
        assertEquals(Optional.of(a), registry.find("alpha"));
        assertEquals(Optional.of(a), registry.find("ALPHA"));
        assertEquals(Optional.empty(), registry.find("gamma"));
        assertEquals(Optional.empty(), registry.find(null));
        assertThrows(UnsupportedOperationException.class, () -> registry.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.descriptors().clear());
    }

    @Test
    @DisplayName("applySettings publishes new settings and bumps the epoch; null result is rejected")
    void applySettings() {
        CheckRegistry registry = new CheckRegistry();
        CheckDescriptor a = Descriptors.movement("alpha");
        registry.register(a, probeFactory(a));
        registry.freeze();
        int before = registry.epoch();
        CheckSettings custom = CheckSettings.of(a.defaults().withEnabled(false), List.of());
        registry.applySettings(d -> custom);
        assertEquals(before + 1, registry.epoch());
        assertSame(custom, registry.settings(0));
        NullPointerException error = assertThrows(NullPointerException.class, () -> registry.applySettings(d -> null));
        assertTrue(error.getMessage().contains("alpha"), error.getMessage());
        assertSame(custom, registry.settings(0), "a failed apply must not replace the published settings");
        assertEquals(before + 1, registry.epoch());
    }

    @Test
    @DisplayName("context requires a frozen registry")
    void contextRequiresFrozen() {
        CheckRegistry registry = new CheckRegistry();
        Harness harness = TestContexts.empty();
        AnticheatContext.Builder builder = AnticheatContext.builder()
                .logger(harness.logger)
                .checks(registry)
                .profiler(new CheckProfiler(0, harness.time))
                .errors(harness.errors)
                .violations(harness.processor)
                .punishments(harness.punishments)
                .simulator(harness.context.simulator());
        assertThrows(IllegalStateException.class, builder::build);
        registry.freeze();
        assertSame(registry, builder.build().checks());
    }

    @Test
    @DisplayName("a factory that returns a check bound to another descriptor is rejected")
    void wrongDescriptor() {
        CheckDescriptor registered = Descriptors.movement("alpha");
        CheckDescriptor other = Descriptors.movement("other");
        Harness harness = TestContexts.builder()
                .check(registered, player -> new ProbeChecks.Probe(player, other))
                .build();
        IllegalStateException error = assertThrows(IllegalStateException.class, harness::newPlayer);
        assertTrue(error.getMessage().contains("alpha"), error.getMessage());
    }

    @Test
    @DisplayName("a factory that returns null is rejected with a clear message")
    void nullCheck() {
        CheckDescriptor registered = Descriptors.movement("alpha");
        Harness harness = TestContexts.builder().check(registered, player -> null).build();
        assertThrows(IllegalStateException.class, harness::newPlayer);
    }

    @Test
    @DisplayName("a factory that binds a different player is rejected")
    void wrongPlayer() {
        Harness victimHarness = TestContexts.empty();
        PlayerData stranger = victimHarness.newPlayer();
        CheckDescriptor descriptor = Descriptors.movement("alpha");
        Harness harness = TestContexts.builder()
                .check(descriptor, player -> new ProbeChecks.Probe(stranger, descriptor))
                .build();
        IllegalStateException error = assertThrows(IllegalStateException.class, harness::newPlayer);
        assertTrue(error.getMessage().contains("wrong player"), error.getMessage());
    }

    @Test
    @DisplayName("each player gets its own check instances")
    void perPlayerInstances() {
        CheckDescriptor descriptor = Descriptors.movement("alpha");
        Harness harness = TestContexts.builder().check(descriptor, probeFactory(descriptor)).build();
        PlayerData one = harness.newPlayer(UUID.randomUUID(), "One");
        PlayerData two = harness.newPlayer(UUID.randomUUID(), "Two");
        ProbeChecks.Probe a = one.checks().get(ProbeChecks.Probe.class);
        ProbeChecks.Probe b = two.checks().get(ProbeChecks.Probe.class);
        assertNotSame(a, b);
        a.raise(5);
        assertEquals(0.0, b.buffer());
        assertSame(descriptor, a.descriptor());
        assertFalse(a.player() == b.player());
    }
}
