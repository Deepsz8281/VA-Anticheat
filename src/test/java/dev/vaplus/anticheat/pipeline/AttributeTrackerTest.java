package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.AttributeState;
import dev.vaplus.anticheat.player.state.AttributeState.Modifier;
import dev.vaplus.anticheat.player.state.AttributeState.Operation;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AttributeTracker (UPDATE_ATTRIBUTES of the own entity)")
class AttributeTrackerTest {

    private static final String SPRINT = "minecraft:sprinting";

    private PlayerData player;
    private RecordingTransport transport;
    private AttributeTracker tracker;
    private SimulationModule module;
    private int acknowledged;

    @BeforeEach
    void setUp() {
        module = new SimulationModule();
        Harness harness = TestContexts.builder().module(module).build();
        player = harness.newPlayer();
        transport = harness.lastTransport();
        tracker = (AttributeTracker) harness.context.setup().outboundTrackers().stream()
                .filter(t -> t instanceof AttributeTracker).findFirst().orElseThrow();
    }

    private void pongNext() {
        player.acks().onPong(transport.markerIds().get(acknowledged++));
    }

    private static AttributeTracker.Entry speed(double base, Modifier... modifiers) {
        return new AttributeTracker.Entry(AttributeState.MOVEMENT_SPEED, base, List.of(modifiers));
    }

    /** Sends one update, runs its after-send task and returns whether it opened a window. */
    private boolean send(AttributeTracker.Entry... entries) {
        List<Runnable> afterSend = new ArrayList<>();
        boolean relevant = tracker.onAttributes(player, afterSend, List.of(entries));
        afterSend.forEach(Runnable::run);
        return relevant;
    }

    @Test
    @DisplayName("bracketed: unchanged before the first ack, pending in the window, committed after the second")
    void bracket() {
        AttributeState attributes = player.attributes();
        assertTrue(send(speed(0.1, new Modifier(Operation.MULTIPLY_TOTAL, 0.4, "minecraft:effect.speed"))));
        assertEquals(0.1, attributes.movementSpeed(), 1e-12);
        assertFalse(attributes.pending(), "the client cannot have it before the first marker");
        pongNext();
        assertTrue(attributes.pending());
        assertEquals(0.1, attributes.movementSpeed(), 1e-12, "not committed in the window");
        pongNext();
        assertFalse(attributes.pending());
        assertEquals(0.14, attributes.movementSpeed(), 1e-12);
        assertTrue(attributes.hasModifier(AttributeState.MOVEMENT_SPEED, "minecraft:effect.speed"));
    }

    @Test
    @DisplayName("a packet replaces the attribute's modifier list (vanilla semantics)")
    void replacesModifiers() {
        send(speed(0.1, new Modifier(Operation.ADD, 0.05, "a")));
        pongNext();
        pongNext();
        send(speed(0.1));
        pongNext();
        pongNext();
        assertEquals(0.1, player.attributes().movementSpeed(), 1e-12);
        assertTrue(player.attributes().modifiers(AttributeState.MOVEMENT_SPEED).isEmpty());
    }

    @Test
    @DisplayName("sprint-only toggles and non-movement attributes open no window but are committed")
    void irrelevantUpdates() {
        assertTrue(send(speed(0.1)), "first update after join counts as a change");
        pongNext();
        pongNext();
        assertFalse(send(speed(0.1, new Modifier(Operation.MULTIPLY_TOTAL, 0.3, SPRINT))), "sprint on");
        pongNext();
        assertFalse(player.attributes().pending());
        pongNext();
        assertTrue(player.attributes().hasModifier(AttributeState.MOVEMENT_SPEED, SPRINT), "still committed");
        assertFalse(send(speed(0.1)), "sprint off");
        assertFalse(send(new AttributeTracker.Entry(AttributeState.ATTACK_SPEED, 4.0,
                List.of(new Modifier(Operation.ADD, -2.4, "minecraft:base_attack_speed")))));
        assertTrue(send(speed(0.12)), "a real base change is relevant again");
    }

    @Test
    @DisplayName("A -> B -> A in flight: the second packet still opens a window (compared with the sent view)")
    void revertInFlight() {
        send(speed(0.1));
        pongNext();
        pongNext();
        assertTrue(send(speed(0.2)));
        assertTrue(send(speed(0.1)), "differs from what was last sent, although equal to the committed value");
    }

    @Test
    @DisplayName("join resets attributes and the sent view; respawn forgets the sent view")
    void joinAndRespawn() {
        send(speed(0.2));
        pongNext();
        pongNext();
        tracker.onJoin(player);
        assertEquals(AttributeState.DEFAULT_MOVEMENT_SPEED, player.attributes().movementSpeed());
        assertEquals(0, player.component(module.sentAttributesKey()).size());
        assertTrue(send(speed(0.1)));
        tracker.onRespawn(player);
        assertTrue(send(speed(0.1)), "after respawn the next update counts as a change");
    }

    @Test
    @DisplayName("names are canonicalised; operations and modifier ids map from the PacketEvents types")
    void conversion() {
        assertEquals("minecraft:movement_speed", AttributeTracker.canonicalName("minecraft:generic.movement_speed"));
        assertEquals("minecraft:block_interaction_range",
                AttributeTracker.canonicalName("minecraft:player.block_interaction_range"));
        assertEquals("minecraft:gravity", AttributeTracker.canonicalName("gravity"));
        assertEquals("minecraft:scale", AttributeTracker.canonicalName("MINECRAFT:SCALE"));
        assertNull(AttributeTracker.canonicalName(" "));
        assertNull(AttributeTracker.canonicalName("bad name:x"));

        assertEquals(Operation.ADD, AttributeTracker.operation(
                WrapperPlayServerUpdateAttributes.PropertyModifier.Operation.ADDITION));
        assertEquals(Operation.MULTIPLY_BASE, AttributeTracker.operation(
                WrapperPlayServerUpdateAttributes.PropertyModifier.Operation.MULTIPLY_BASE));
        assertEquals(Operation.MULTIPLY_TOTAL, AttributeTracker.operation(
                WrapperPlayServerUpdateAttributes.PropertyModifier.Operation.MULTIPLY_TOTAL));
        assertNull(AttributeTracker.operation(null));

        UUID uuid = UUID.fromString("662a6b8d-da3e-4c1c-8813-96ea6097278d");
        assertEquals("minecraft:sprinting", AttributeTracker.modifierId(new ResourceLocation("minecraft:sprinting"), uuid));
        assertEquals(uuid.toString(), AttributeTracker.modifierId(null, uuid));
        assertNull(AttributeTracker.modifierId(null, null));
    }

    @Test
    @DisplayName("PacketEvents properties convert to tracker entries (base + modifiers)")
    void convertProperty() {
        FakePacketEvents.install(ServerVersion.V_1_21_4);
        try {
            WrapperPlayServerUpdateAttributes.Property property = new WrapperPlayServerUpdateAttributes.Property(
                    "minecraft:generic.movement_speed", 0.1, new ArrayList<>(List.of(
                    new WrapperPlayServerUpdateAttributes.PropertyModifier(new ResourceLocation("minecraft:sprinting"),
                            0.3, WrapperPlayServerUpdateAttributes.PropertyModifier.Operation.MULTIPLY_TOTAL))));
            AttributeTracker.Entry entry = AttributeTracker.convert(property);
            assertEquals(AttributeState.MOVEMENT_SPEED, entry.name());
            assertEquals(0.1, entry.base());
            assertEquals(List.of(new Modifier(Operation.MULTIPLY_TOTAL, 0.3, SPRINT)), entry.modifiers());
        } finally {
            FakePacketEvents.uninstall();
        }
    }
}
