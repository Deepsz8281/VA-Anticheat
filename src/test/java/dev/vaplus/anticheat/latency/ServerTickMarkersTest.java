package dev.vaplus.anticheat.latency;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.player.PacketTransport;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.ManualTimeSource;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ServerTickMarkers (per-server-tick marker, M15)")
class ServerTickMarkersTest {

    /** Transport whose owner-thread tasks run only when the test drains them (a busy event loop). */
    private static final class DeferredTransport implements PacketTransport {
        final List<Integer> markerIds = new ArrayList<>();
        final Deque<Runnable> queued = new ArrayDeque<>();
        boolean accepting = true;

        @Override
        public boolean sendMarker(int markerId) {
            if (!accepting) {
                return false;
            }
            markerIds.add(markerId);
            return true;
        }

        @Override
        public void send(PacketWrapper<?> packet) {
        }

        @Override
        public void runOnOwnerThread(Runnable task) {
            queued.add(task);
        }

        @Override
        public void close() {
        }

        void runQueued() {
            while (!queued.isEmpty()) {
                queued.poll().run();
            }
        }
    }

    private static final class Fixture {
        final DeferredTransport transport = new DeferredTransport();
        final MarkerTracker acks = new MarkerTracker(transport, new ManualTimeSource(), Logger.getAnonymousLogger());
        boolean disconnecting;
        final ServerTickMarkers markers = new ServerTickMarkers(acks, transport, () -> disconnecting);

        void ack(int index) {
            acks.onPong(transport.markerIds.get(index));
        }
    }

    @Test
    @DisplayName("publish schedules one marker on the owner thread; the tick counts once its marker is acked")
    void publishAndAck() {
        Fixture f = new Fixture();
        assertEquals(ServerTickMarkers.NONE, f.markers.acknowledgedServerTick());
        f.markers.publish(100);
        assertTrue(f.transport.markerIds.isEmpty(), "nothing is sent from the publishing thread");
        assertEquals(100, f.markers.announcedServerTick());
        f.transport.runQueued();
        assertEquals(1, f.transport.markerIds.size());
        assertEquals(100, f.markers.lastMarkedServerTick());
        assertEquals(1, f.markers.pendingTicks());
        assertEquals(ServerTickMarkers.NONE, f.markers.acknowledgedServerTick());

        f.ack(0);
        assertEquals(100, f.markers.acknowledgedServerTick());
        assertEquals(100, f.markers.acknowledgedServerTickSnapshot());
        assertEquals(0, f.markers.pendingTicks());
    }

    @Test
    @DisplayName("acknowledgements resolve ticks in order; unanswered ticks stay pending")
    void partialAcks() {
        Fixture f = new Fixture();
        for (long tick = 1; tick <= 3; tick++) {
            f.markers.publish(tick);
            f.transport.runQueued();
        }
        assertEquals(3, f.transport.markerIds.size());
        f.ack(1);
        assertEquals(2, f.markers.acknowledgedServerTick(), "acking tick 2's marker implies tick 1 too");
        assertEquals(1, f.markers.pendingTicks());
        f.ack(2);
        assertEquals(3, f.markers.acknowledgedServerTick());
    }

    @Test
    @DisplayName("ticks announced faster than the owner thread runs collapse onto the newest one")
    void coalesces() {
        Fixture f = new Fixture();
        f.markers.publish(7);
        f.markers.publish(8);
        f.markers.publish(9);
        f.transport.runQueued();
        assertEquals(1, f.transport.markerIds.size(), "one marker for the newest announced tick");
        assertEquals(9, f.markers.lastMarkedServerTick());
        f.ack(0);
        assertEquals(9, f.markers.acknowledgedServerTick());
    }

    @Test
    @DisplayName("repeated or older tick indices send nothing")
    void ignoresOldTicks() {
        Fixture f = new Fixture();
        f.markers.publish(50);
        f.transport.runQueued();
        f.markers.publish(50);
        f.markers.publish(49);
        f.transport.runQueued();
        assertEquals(1, f.transport.markerIds.size());
        f.markers.markNow();
        assertEquals(1, f.transport.markerIds.size(), "markNow without a newer announcement is a no-op");
    }

    @Test
    @DisplayName("a disconnecting player gets no markers")
    void disconnecting() {
        Fixture f = new Fixture();
        f.markers.publish(1);
        f.disconnecting = true;
        f.transport.runQueued();
        f.markers.publish(2);
        f.transport.runQueued();
        assertTrue(f.transport.markerIds.isEmpty());
        assertTrue(f.transport.queued.isEmpty());
    }

    @Test
    @DisplayName("a refused send resolves the tick late (with the next real marker), never early")
    void refusedSend() {
        Fixture f = new Fixture();
        f.transport.accepting = false;
        f.markers.publish(1);
        f.transport.runQueued();
        assertTrue(f.transport.markerIds.isEmpty());
        assertEquals(ServerTickMarkers.NONE, f.markers.acknowledgedServerTick());

        f.transport.accepting = true;
        f.markers.publish(2);
        f.transport.runQueued();
        assertEquals(1, f.transport.markerIds.size());
        assertEquals(ServerTickMarkers.NONE, f.markers.acknowledgedServerTick());
        f.ack(0);
        assertEquals(2, f.markers.acknowledgedServerTick());
    }

    @Test
    @DisplayName("heartbeat markers in between do not shift the mapping")
    void interleavedHeartbeats() {
        Fixture f = new Fixture();
        f.acks.sendMarker(); // heartbeat
        f.markers.publish(10);
        f.transport.runQueued();
        f.acks.sendMarker(); // heartbeat
        f.ack(0);
        assertEquals(ServerTickMarkers.NONE, f.markers.acknowledgedServerTick(), "only the heartbeat before the tick was acked");
        f.ack(1);
        assertEquals(10, f.markers.acknowledgedServerTick());
    }

    @Test
    @DisplayName("an unanswering client cannot grow the mapping beyond its capacity")
    void overflow() {
        Fixture f = new Fixture();
        int extra = 5;
        for (int tick = 1; tick <= ServerTickMarkers.CAPACITY + extra; tick++) {
            f.markers.publish(tick);
            f.transport.runQueued();
        }
        assertEquals(ServerTickMarkers.CAPACITY, f.markers.pendingTicks());
        assertEquals(extra, f.markers.overflows());
        f.ack(f.transport.markerIds.size() - 1);
        assertEquals(ServerTickMarkers.CAPACITY + extra, f.markers.acknowledgedServerTick());
    }

    @Test
    @DisplayName("CoreTrackingModule registers one instance per player bound to the player's ack tracker")
    void componentWiring() {
        CoreTrackingModule module = new CoreTrackingModule(false, false);
        Harness harness = TestContexts.builder().module(module).build();
        ComponentKey<ServerTickMarkers> key = ServerTickMarkers.keyOf(harness.context.setup());
        assertNotNull(key);
        assertSame(module.serverTickMarkersKey(), key);

        PlayerData player = harness.newPlayer();
        RecordingTransport transport = harness.lastTransport();
        ServerTickMarkers markers = player.component(key);
        markers.publish(42);
        assertEquals(1, transport.markerIds().size(), "RecordingTransport runs the owner-thread task inline");
        assertEquals(1, transport.ownerThreadTasks());
        assertEquals(player.acks().lastSentMarker(), 1L);
        player.acks().onPong(transport.lastMarkerId());
        assertEquals(42, markers.acknowledgedServerTick());

        player.markDisconnecting();
        markers.publish(43);
        assertEquals(1, transport.markerIds().size());

        assertNull(ServerTickMarkers.keyOf(TestContexts.empty().context.setup()));
    }
}
