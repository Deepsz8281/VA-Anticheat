package dev.vaplus.anticheat.module;

import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckRegistry;
import dev.vaplus.anticheat.pipeline.ClientTickListener;
import dev.vaplus.anticheat.pipeline.InboundTracker;
import dev.vaplus.anticheat.pipeline.MovementHook;
import dev.vaplus.anticheat.pipeline.OutboundTracker;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.MovementSimulator;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Module system (ModuleContext, ModuleSetup, ComponentKey)")
class ModuleContextTest {

    private static final MovementSimulator SIM_A = (player, update, out) -> out.reset();
    private static final MovementSimulator SIM_B = (player, update, out) -> out.reset();

    private static AnticheatModule module(String name, java.util.function.Consumer<ModuleContext> body) {
        return new AnticheatModule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void register(ModuleContext context) {
                body.accept(context);
            }
        };
    }

    @Test
    @DisplayName("registerCheck registers in the registry; complete() freezes it and returns the setup")
    void registerCheckAndComplete() {
        CheckRegistry registry = new CheckRegistry();
        ModuleContext context = new ModuleContext(registry);
        CheckDescriptor descriptor = Descriptors.movement("alpha");
        context.registerCheck(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new));
        assertEquals(1, registry.size());
        assertFalse(registry.frozen());
        ModuleSetup setup = context.complete();
        assertTrue(registry.frozen());
        assertEquals(List.of(), setup.components());
        assertNull(setup.simulator());
    }

    @Test
    @DisplayName("duplicate check ids across modules are rejected")
    void duplicateCheckId() {
        ModuleContext context = new ModuleContext(new CheckRegistry());
        CheckDescriptor descriptor = Descriptors.movement("alpha");
        context.registerCheck(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new));
        CheckDescriptor again = Descriptors.combat("alpha");
        assertThrows(IllegalArgumentException.class,
                () -> context.registerCheck(again, ProbeChecks.factory(again, ProbeChecks.Probe::new)));
    }

    @Test
    @DisplayName("duplicate component names are rejected")
    void duplicateComponentName() {
        ModuleContext context = new ModuleContext(new CheckRegistry());
        context.registerComponent("world", Object.class, player -> new Object());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> context.registerComponent("world", String.class, player -> "x"));
        assertTrue(error.getMessage().contains("world"));
    }

    @Test
    @DisplayName("component keys get sequential indices and keep name and type")
    void componentKeys() {
        ModuleContext context = new ModuleContext(new CheckRegistry());
        ComponentKey<String> first = context.registerComponent("first", String.class, player -> "a");
        ComponentKey<Integer> second = context.registerComponent("second", Integer.class, player -> 7);
        assertEquals(0, first.index());
        assertEquals(1, second.index());
        assertEquals("first", first.name());
        assertEquals("first", first.toString());
        assertEquals(Integer.class, second.type());
        assertEquals(List.of(first, second), context.complete().components());
    }

    @Test
    @DisplayName("only one module may install a simulator; the error names the first one")
    void secondSimulatorRejected() {
        ModuleContext context = new ModuleContext(new CheckRegistry());
        context.install(module("physics", c -> c.installSimulator(SIM_A)));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> context.install(module("rival", c -> c.installSimulator(SIM_B))));
        assertTrue(error.getMessage().contains("physics"), error.getMessage());
        assertSame(SIM_A, context.complete().simulator());
    }

    @Test
    @DisplayName("null simulators, trackers, hooks and listeners are rejected")
    void nullArguments() {
        ModuleContext context = new ModuleContext(new CheckRegistry());
        assertThrows(NullPointerException.class, () -> context.installSimulator(null));
        assertThrows(NullPointerException.class, () -> context.addInboundTracker(null));
        assertThrows(NullPointerException.class, () -> context.addOutboundTracker(null));
        assertThrows(NullPointerException.class, () -> context.addMovementHook(null));
        assertThrows(NullPointerException.class, () -> context.addTickListener(null));
        assertThrows(NullPointerException.class, () -> new ModuleContext(null));
    }

    @Test
    @DisplayName("after complete() every registration method and a second complete() fail")
    void closedAfterComplete() {
        ModuleContext context = new ModuleContext(new CheckRegistry());
        context.complete();
        CheckDescriptor descriptor = Descriptors.movement("late");
        assertThrows(IllegalStateException.class,
                () -> context.registerCheck(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new)));
        assertThrows(IllegalStateException.class, () -> context.addInboundTracker((p, e) -> { }));
        assertThrows(IllegalStateException.class, () -> context.addOutboundTracker((p, e) -> { }));
        assertThrows(IllegalStateException.class, () -> context.addMovementHook((p, u) -> { }));
        assertThrows(IllegalStateException.class, () -> context.addTickListener(p -> { }));
        assertThrows(IllegalStateException.class, () -> context.registerComponent("x", Object.class, p -> new Object()));
        assertThrows(IllegalStateException.class, () -> context.installSimulator(SIM_A));
        assertThrows(IllegalStateException.class, () -> context.install(module("late", c -> { })));
        assertThrows(IllegalStateException.class, context::complete);
    }

    @Test
    @DisplayName("trackers, hooks and listeners keep registration order across modules")
    void registrationOrder() {
        InboundTracker in1 = (p, e) -> { };
        InboundTracker in2 = (p, e) -> { };
        OutboundTracker out1 = (p, e) -> { };
        OutboundTracker out2 = (p, e) -> { };
        MovementHook hook1 = (p, u) -> { };
        MovementHook hook2 = (p, u) -> { };
        ClientTickListener tick1 = p -> { };
        ClientTickListener tick2 = p -> { };
        ModuleContext context = new ModuleContext(new CheckRegistry());
        context.install(module("one", c -> {
            c.addInboundTracker(in1);
            c.addOutboundTracker(out1);
            c.addMovementHook(hook1);
            c.addTickListener(tick1);
        }));
        context.install(module("two", c -> {
            c.addInboundTracker(in2);
            c.addOutboundTracker(out2);
            c.addMovementHook(hook2);
            c.addTickListener(tick2);
        }));
        ModuleSetup setup = context.complete();
        assertEquals(List.of(in1, in2), setup.inboundTrackers());
        assertEquals(List.of(out1, out2), setup.outboundTrackers());
        assertEquals(List.of(hook1, hook2), setup.movementHooks());
        assertEquals(List.of(tick1, tick2), setup.tickListeners());
    }

    @Test
    @DisplayName("ModuleSetup copies its lists; EMPTY is empty")
    void setupImmutability() {
        List<InboundTracker> mutable = new ArrayList<>();
        ModuleSetup setup = new ModuleSetup(mutable, List.of(), List.of(), List.of(), List.of(), null);
        mutable.add((p, e) -> { });
        assertTrue(setup.inboundTrackers().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> setup.inboundTrackers().add((p, e) -> { }));
        assertTrue(ModuleSetup.EMPTY.components().isEmpty());
        assertTrue(ModuleSetup.EMPTY.tickListeners().isEmpty());
        assertNull(ModuleSetup.EMPTY.simulator());
    }

    @Test
    @DisplayName("the context uses the setup's simulator even when the builder also got one")
    void setupSimulatorWins() {
        ModuleContext modules = new ModuleContext(new CheckRegistry());
        modules.installSimulator(SIM_A);
        Harness base = TestContexts.empty();
        AnticheatContext context = AnticheatContext.builder()
                .logger(base.logger).checks(base.registry).profiler(base.profiler).errors(base.errors)
                .violations(base.processor).punishments(base.punishments)
                .simulator(SIM_B)
                .setup(modules.complete())
                .build();
        assertSame(SIM_A, context.simulator());
    }

    @Test
    @DisplayName("without a setup simulator the builder's simulator is used; with neither the build fails")
    void builderSimulatorFallback() {
        Harness base = TestContexts.empty();
        AnticheatContext.Builder builder = AnticheatContext.builder()
                .logger(base.logger).checks(base.registry).profiler(base.profiler).errors(base.errors)
                .violations(base.processor).punishments(base.punishments);
        assertThrows(NullPointerException.class, builder::build);
        assertSame(SIM_B, builder.simulator(SIM_B).build().simulator());
        assertSame(ModuleSetup.EMPTY, builder.build().setup());
    }

    @Test
    @DisplayName("the context exposes tick listeners of the setup in order")
    void contextTickListeners() {
        ClientTickListener first = p -> { };
        ClientTickListener second = p -> { };
        Harness harness = TestContexts.builder().module(module("m", c -> {
            c.addTickListener(first);
            c.addTickListener(second);
        })).build();
        assertEquals(2, harness.context.tickListeners().length);
        assertSame(first, harness.context.tickListeners()[0]);
        assertSame(second, harness.context.tickListeners()[1]);
        assertEquals(List.of(first, second), harness.context.setup().tickListeners());
    }

    @Test
    @DisplayName("components are created once per player, before checks, and resolved by key")
    void componentsViaPlayerData() {
        AtomicInteger created = new AtomicInteger();
        List<String> order = new ArrayList<>();
        ComponentKey<?>[] holder = new ComponentKey<?>[1];
        CheckDescriptor descriptor = Descriptors.movement("needs-component");
        Harness harness = TestContexts.builder()
                .check(descriptor, player -> {
                    order.add("check");
                    Object component = player.component(holder[0]);
                    order.add("check sees " + component);
                    return new ProbeChecks.Probe(player, descriptor);
                })
                .module(module("m", c -> holder[0] = c.registerComponent("counter", String.class, player -> {
                    order.add("component");
                    return "component-" + created.incrementAndGet();
                })))
                .build();
        @SuppressWarnings("unchecked")
        ComponentKey<String> key = (ComponentKey<String>) holder[0];

        PlayerData one = harness.newPlayer();
        PlayerData two = harness.newPlayer();

        assertEquals(2, created.get());
        assertEquals("component-1", one.component(key));
        assertEquals("component-2", two.component(key));
        assertSame(one.component(key), one.component(key));
        assertNotSame(one.component(key), two.component(key));
        assertEquals(List.of("component", "check", "check sees component-1", "component", "check", "check sees component-2"), order);
    }

    @Test
    @DisplayName("a component factory returning null fails player creation with a clear message")
    void nullComponent() {
        Harness harness = TestContexts.builder()
                .module(module("m", c -> c.registerComponent("broken", String.class, player -> null)))
                .build();
        IllegalStateException error = assertThrows(IllegalStateException.class, harness::newPlayer);
        assertTrue(error.getMessage().contains("broken"));
    }

    @Test
    @DisplayName("several components are independent and addressable by their own key")
    void multipleComponents() {
        ComponentKey<?>[] keys = new ComponentKey<?>[2];
        Harness harness = TestContexts.builder().module(module("m", c -> {
            keys[0] = c.registerComponent("text", String.class, player -> "text of " + player.name());
            keys[1] = c.registerComponent("number", Integer.class, player -> player.name().length());
        })).build();
        PlayerData player = harness.newPlayer(java.util.UUID.randomUUID(), "Alexander");
        @SuppressWarnings("unchecked")
        ComponentKey<String> text = (ComponentKey<String>) keys[0];
        @SuppressWarnings("unchecked")
        ComponentKey<Integer> number = (ComponentKey<Integer>) keys[1];
        assertEquals("text of Alexander", player.component(text));
        assertEquals(9, player.component(number));
    }

    @Test
    @DisplayName("tick listeners run before tick checks, in order, also for bypassing players")
    void tickListenersBeforeChecks() {
        CheckDescriptor descriptor = Descriptors.movement("tick");
        List<String> log = new ArrayList<>();
        ProbeChecks.Tick[] probe = new ProbeChecks.Tick[1];
        Harness harness = TestContexts.builder()
                .check(descriptor, player -> probe[0] = new ProbeChecks.Tick(player, descriptor))
                .module(module("m", c -> {
                    c.addTickListener(player -> log.add("first sees check ticks=" + probe[0].ticks));
                    c.addTickListener(player -> log.add("second sees check ticks=" + probe[0].ticks));
                }))
                .build();
        PlayerData player = harness.newPlayer();

        player.onClientTick();
        assertEquals(List.of("first sees check ticks=0", "second sees check ticks=0"), log);
        assertEquals(1, probe[0].ticks);

        log.clear();
        player.updateBypass(true);
        player.onClientTick();
        assertEquals(List.of("first sees check ticks=1", "second sees check ticks=1"), log, "listeners also run for bypassing players");
        assertEquals(1, probe[0].ticks, "tick checks are skipped for bypassing players");
        assertEquals(2, player.clientTicks());
    }

    @Test
    @DisplayName("a listener sees the state advanced for the current tick (movement ticks, exemptions)")
    void listenerSeesAdvancedState() {
        int[] seenTicks = new int[1];
        Harness harness = TestContexts.builder()
                .module(module("m", c -> c.addTickListener(player -> seenTicks[0] = player.movement().ticksSincePosition())))
                .build();
        PlayerData player = harness.newPlayer();
        player.onClientTick();
        player.onClientTick();
        assertEquals(2, seenTicks[0]);
    }
}
