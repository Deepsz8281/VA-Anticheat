package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.latency.MarkerTracker;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VelocityTracker (outbound entity velocity / explosion knockback)")
class VelocityTrackerTest {

    private Harness harness;
    private PlayerData player;
    private RecordingTransport transport;
    private VelocityTracker tracker;
    private VelocityState state;

    @BeforeEach
    void setUp() {
        SimulationModule module = new SimulationModule();
        harness = TestContexts.builder().module(module).build();
        player = harness.newPlayer();
        transport = harness.lastTransport();
        tracker = (VelocityTracker) harness.context.setup().outboundTrackers().stream()
                .filter(t -> t instanceof VelocityTracker).findFirst().orElseThrow();
        state = player.component(module.velocityKey());
    }

    private void pong(int index) {
        player.acks().onPong(transport.markerIds().get(index));
    }

    @Test
    @DisplayName("the simulation module registers the tracker")
    void wiring() {
        assertInstanceOf(VelocityTracker.class, tracker);
    }

    @Test
    @DisplayName("velocities of other entities, or before the join packet, are ignored")
    void foreignEntity() {
        List<Runnable> afterSend = new ArrayList<>();
        assertFalse(tracker.onEntityVelocity(player, afterSend, 5, 0.1, 0.2, 0.3), "own id unknown yet");
        tracker.onJoin(player, 5);
        assertFalse(tracker.onEntityVelocity(player, afterSend, 6, 0.1, 0.2, 0.3));
        assertEquals(0, state.size());
        assertTrue(afterSend.isEmpty());
        assertTrue(transport.markerIds().isEmpty(), "no marker for ignored packets");
    }

    @Test
    @DisplayName("own entity velocity is bracketed: SENT, WINDOW after the first ack, ACKED after the second")
    void bracketedSet() {
        tracker.onJoin(player, 5);
        List<Runnable> afterSend = new ArrayList<>();
        assertTrue(tracker.onEntityVelocity(player, afterSend, 5, 0.1, 0.2, 0.3));
        assertEquals(1, state.size());
        assertEquals(VelocityState.Kind.SET, state.kind(0));
        assertEquals(VelocityState.Phase.SENT, state.phase(0));
        assertEquals(1, transport.markerIds().size(), "first marker before the packet");
        assertEquals(1, afterSend.size());

        afterSend.get(0).run();
        assertEquals(2, transport.markerIds().size(), "second marker after the packet");
        pong(0);
        assertEquals(VelocityState.Phase.WINDOW, state.phase(0));
        pong(1);
        assertEquals(VelocityState.Phase.ACKED, state.phase(0));
        assertTrue(MarkerTracker.isMarkerId(transport.markerIds().get(1)));
    }

    @Test
    @DisplayName("explosion knockback is tracked as ADD, also without a known entity id")
    void explosion() {
        List<Runnable> afterSend = new ArrayList<>();
        assertTrue(tracker.onVelocity(player, afterSend, VelocityState.Kind.ADD, 0.5, 0.1, -0.5));
        assertEquals(VelocityState.Kind.ADD, state.kind(0));
        assertEquals(-0.5, state.z(0));
        assertEquals(player.movement().worldEpoch(), state.worldEpoch(0));
    }

    @Test
    @DisplayName("non-finite vectors are ignored; join clears pending entries")
    void nonFiniteAndJoin() {
        List<Runnable> afterSend = new ArrayList<>();
        assertFalse(tracker.onVelocity(player, afterSend, VelocityState.Kind.ADD, Double.NaN, 0, 0));
        assertEquals(0, state.size());
        tracker.onVelocity(player, afterSend, VelocityState.Kind.ADD, 1, 0, 0);
        tracker.onJoin(player, 9);
        assertEquals(0, state.size());
        assertEquals(9, state.selfEntityId());
    }
}
