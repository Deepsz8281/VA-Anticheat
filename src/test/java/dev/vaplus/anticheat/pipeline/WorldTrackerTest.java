package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.ArrayColumnStorage;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.PendingChanges;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorldTracker (chunk/block feeder with ack windows)")
class WorldTrackerTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int ICE = 2;
    private static final BlockStateResolver RESOLVER = id -> switch (id) {
        case AIR -> new BlockSnapshot(VanillaBlocks.AIR, VanillaBlocks.properties(VanillaBlocks.AIR), true);
        case STONE -> new BlockSnapshot(VanillaBlocks.STONE, VanillaBlocks.DEFAULT_PROPERTIES, true);
        case ICE -> new BlockSnapshot(VanillaBlocks.ICE, VanillaBlocks.properties(VanillaBlocks.ICE), true);
        default -> BlockSnapshot.UNKNOWN;
    };

    private Harness harness;
    private PlayerData player;
    private RecordingTransport transport;
    private WorldTracker tracker;
    private WorldMirror world;
    private int acknowledged;

    @BeforeEach
    void setUp() {
        SimulationModule module = new SimulationModule(RESOLVER);
        harness = TestContexts.builder().module(module).build();
        player = harness.newPlayer();
        transport = harness.lastTransport();
        tracker = module.worldTracker();
        world = player.component(module.worldMirrorKey());
    }

    private static ArrayColumnStorage column() {
        return new ArrayColumnStorage(-64, 384).fillLayers(-64, 63, STONE).fillLayers(64, 80, AIR);
    }

    /** Heartbeat marker + client acknowledges everything sent so far. */
    private void ackAll() {
        player.acks().sendMarker();
        while (acknowledged < transport.markerIds().size()) {
            player.acks().onPong(transport.markerIds().get(acknowledged++));
        }
    }

    private void settle() {
        world.pending().settle(player.acks().lastAckedMarker());
    }

    @Test
    @DisplayName("registered by the simulation module after the velocity tracker (own entity id first)")
    void wiring() {
        List<OutboundTracker> trackers = harness.context.setup().outboundTrackers();
        int velocity = -1;
        int feeder = -1;
        for (int i = 0; i < trackers.size(); i++) {
            if (trackers.get(i) instanceof VelocityTracker) {
                velocity = i;
            } else if (trackers.get(i) == tracker) {
                feeder = i;
            }
        }
        assertTrue(velocity >= 0 && feeder > velocity);
        assertTrue(trackers.stream().anyMatch(t -> t instanceof AttributeTracker));
        assertTrue(trackers.stream().anyMatch(t -> t instanceof EffectTracker));
    }

    @Test
    @DisplayName("a chunk is readable at once but pending until a marker sent after it is acknowledged")
    void chunkWindow() {
        List<Runnable> afterSend = new ArrayList<>();
        tracker.onChunkData(player, afterSend, 0, 0, column());
        assertTrue(world.getBlock(3, 63, 3).known());
        assertTrue(world.pending().intersects(0, 60, 0, 15, 70, 15));
        assertEquals(1, afterSend.size(), "one resolve task, no extra ping per chunk");
        assertTrue(transport.markerIds().isEmpty());

        settle();
        assertTrue(world.pending().any(), "unresolved before the packet left");
        afterSend.forEach(Runnable::run);
        settle();
        assertTrue(world.pending().any(), "the closing marker was not even sent yet");
        ackAll();
        settle();
        assertFalse(world.pending().any());
    }

    @Test
    @DisplayName("a marker sent before the packet left does not close the window")
    void earlierMarkerDoesNotClose() {
        player.acks().sendMarker();
        List<Runnable> afterSend = new ArrayList<>();
        tracker.onChunkData(player, afterSend, 0, 0, column());
        player.acks().onPong(transport.markerIds().get(0));
        acknowledged = 1;
        afterSend.forEach(Runnable::run);
        settle();
        assertTrue(world.pending().any());
        ackAll();
        settle();
        assertFalse(world.pending().any());
    }

    @Test
    @DisplayName("block updates: changed ids open a window and update the mirror, equal ids do nothing")
    void blockChange() {
        tracker.onChunkData(player, new ArrayList<>(), 0, 0, column());
        world.pending().resolve(1);
        ackAll();
        settle();
        assertFalse(world.pending().any());

        assertFalse(tracker.onBlockChange(player, 3, 63, 3, STONE), "same state: nothing uncertain");
        assertFalse(world.pending().any());

        List<Runnable> afterSend = new ArrayList<>();
        assertTrue(tracker.onBlockChange(player, 3, 63, 3, ICE));
        tracker.scheduleResolve(player, afterSend);
        assertEquals(VanillaBlocks.ICE, world.getBlock(3, 63, 3).key());
        assertTrue(world.pending().intersects(3, 63, 3, 3, 63, 3));
        assertFalse(world.pending().intersects(5, 63, 5, 6, 64, 6), "other blocks stay certain");
        afterSend.forEach(Runnable::run);
        ackAll();
        settle();
        assertFalse(world.pending().any());
    }

    @Test
    @DisplayName("updates for chunks the client does not have, or outside the column, are ignored")
    void ignoredUpdates() {
        assertFalse(tracker.onBlockChange(player, 100, 64, 100, STONE));
        tracker.onChunkData(player, new ArrayList<>(), 0, 0, column());
        long version = world.version();
        assertFalse(tracker.onBlockChange(player, 1, 1000, 1, STONE));
        assertFalse(tracker.onBlockChange(player, 1, 64, 1, -5), "negative ids are not states");
        assertEquals(version, world.version());
        assertEquals(0, world.pending().blockCount());
    }

    @Test
    @DisplayName("an unknown id is stored as such and reads UNKNOWN, never air")
    void unknownIdStaysUnknown() {
        tracker.onChunkData(player, new ArrayList<>(), 0, 0, column());
        assertTrue(tracker.onBlockChange(player, 2, 63, 2, 99999));
        assertFalse(world.getBlock(2, 63, 2).known());
    }

    @Test
    @DisplayName("unload removes the chunk immediately, partial chunks become unknown")
    void unload() {
        tracker.onChunkData(player, new ArrayList<>(), 0, 0, column());
        tracker.onChunkData(player, new ArrayList<>(), 1, 0, column());
        tracker.onUnloadChunk(player, 0, 0);
        assertFalse(world.hasChunk(0, 0));
        assertFalse(world.getBlock(3, 63, 3).known());
        assertEquals(1, world.pending().chunkCount(), "the unloaded chunk's window is gone");
        tracker.onPartialChunk(player, 1, 0);
        assertFalse(world.hasChunk(1, 0));
    }

    @Test
    @DisplayName("join/respawn clear the mirror and keep the whole world pending until acknowledged")
    void worldReset() {
        tracker.onChunkData(player, new ArrayList<>(), 0, 0, column());
        List<Runnable> afterSend = new ArrayList<>();
        tracker.onWorldReset(player, afterSend);
        assertEquals(0, world.chunkCount());
        assertTrue(world.pending().allPending());
        // Chunks of the new world arrive before the client acknowledged the reset.
        tracker.onChunkData(player, afterSend, 0, 0, column());
        afterSend.forEach(Runnable::run);
        settle();
        assertTrue(world.pending().allPending());
        ackAll();
        settle();
        assertFalse(world.pending().any());
        assertTrue(world.getBlock(3, 63, 3).known());
    }

    @Test
    @DisplayName("a burst of chunk windows beyond the list size escalates to a whole-world window")
    void burst() {
        List<Runnable> afterSend = new ArrayList<>();
        ArrayColumnStorage shared = new ArrayColumnStorage(0, 16).fillLayers(0, 15, STONE);
        for (int i = 0; i <= PendingChanges.MAX_CHUNKS; i++) {
            tracker.onChunkData(player, afterSend, i, 0, shared);
        }
        assertTrue(world.pending().allPending());
        afterSend.forEach(Runnable::run);
        ackAll();
        settle();
        assertFalse(world.pending().any());
    }
}
