package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Teleport trackers (outbound sync observer, inbound TELEPORT_CONFIRM)")
class TeleportTrackingTest {

    private CoreTrackingModule module;
    private Harness harness;
    private PlayerData player;
    private TeleportQueue queue;
    private TeleportSyncTracker syncTracker;
    private TeleportConfirmTracker confirmTracker;

    @BeforeEach
    void setUp() {
        module = new CoreTrackingModule(false);
        harness = TestContexts.builder().module(module).build();
        player = harness.newPlayer();
        queue = TeleportQueue.resolve(player);
        ModuleSetup setup = harness.context.setup();
        syncTracker = (TeleportSyncTracker) setup.outboundTrackers().get(1);
        confirmTracker = (TeleportConfirmTracker) setup.inboundTrackers().get(0);
    }

    @Test
    @DisplayName("CoreTrackingModule registers the queue, the sync observer and the confirm tracker")
    void wiring() {
        ModuleSetup setup = harness.context.setup();
        // Round 2: + server-tick-markers component (M15) and GameStateTracker as inbound PLAYER_LOADED tracker (L11).
        assertEquals(2, setup.components().size());
        assertEquals("teleport-queue", setup.components().get(0).name());
        assertEquals(TeleportQueue.class, setup.components().get(0).type());
        assertEquals("server-tick-markers", setup.components().get(1).name());
        assertEquals(2, setup.outboundTrackers().size());
        assertInstanceOf(GameStateTracker.class, setup.outboundTrackers().get(0));
        assertInstanceOf(TeleportSyncTracker.class, setup.outboundTrackers().get(1));
        assertEquals(3, setup.inboundTrackers().size());
        assertInstanceOf(TeleportConfirmTracker.class, setup.inboundTrackers().get(0));
        assertInstanceOf(GameStateTracker.class, setup.inboundTrackers().get(2));
        assertEquals(module.teleportQueueKey(), setup.components().get(0));
        assertThrows(IllegalStateException.class, new CoreTrackingModule(false)::teleportQueueKey);
    }

    @Test
    @DisplayName("an observed position sync is enqueued with target, relative flags, epoch and tick")
    void observeSync() {
        player.movement().advanceWorldEpoch();
        for (int i = 0; i < 3; i++) {
            player.onClientTick();
        }
        int flags = TeleportQueue.FLAG_Y | TeleportQueue.FLAG_ROTATE_DELTA;
        syncTracker.onPositionSync(player, 12, 1.25, 64.0, -7.5, 45f, -10f, flags);

        assertEquals(1, queue.pendingCount());
        TeleportQueue.Entry entry = queue.head();
        assertEquals(12, entry.teleportId());
        assertEquals(1.25, entry.target().x());
        assertEquals(64.0, entry.target().y());
        assertEquals(-7.5, entry.target().z());
        assertEquals(45f, entry.target().yaw());
        assertEquals(-10f, entry.target().pitch());
        assertEquals(flags, entry.relativeFlags());
        assertEquals(1, entry.worldEpoch());
        assertEquals(3L, entry.registeredAtClientTick());
        assertEquals(TeleportQueue.SERVER_SYNC, entry.reason());
        assertTrue(entry.relativeY());
        assertTrue(entry.relativeVelocity());
    }

    @Test
    @DisplayName("a sync that the setback already registered is not duplicated")
    void noDuplicate() {
        int id = queue.registerOwn(new dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget(
                1, 64, 1, 0f, 0f), 0, 0L, "setback");
        syncTracker.onPositionSync(player, id, 1, 64, 1, 0f, 0f, 0);
        assertEquals(1, queue.pendingCount());
        assertEquals("setback", queue.head().reason());
    }

    @Test
    @DisplayName("a matching confirmation pops the oldest sync and invalidates the tracked position")
    void confirmPops() {
        player.movement().applyPosition(10, 64, 10);
        syncTracker.onPositionSync(player, 3, 0, 64, 0, 0f, 0f, 0);
        assertTrue(player.movement().positionKnown());

        confirmTracker.onConfirm(player, 3);
        assertFalse(queue.hasPending());
        assertFalse(player.movement().positionKnown(),
                "the next position packet is the teleport destination, not a movement delta");
        assertEquals(0, queue.anomalies());
    }

    @Test
    @DisplayName("a wrong confirmation changes nothing and keeps the position")
    void confirmWrong() {
        player.movement().applyPosition(10, 64, 10);
        syncTracker.onPositionSync(player, 3, 0, 64, 0, 0f, 0f, 0);

        confirmTracker.onConfirm(player, 4);
        assertEquals(1, queue.pendingCount());
        assertEquals(1, queue.anomalies());
        assertTrue(player.movement().positionKnown());
    }

    @Test
    @DisplayName("a confirmation without a pending sync does not invalidate the position")
    void confirmWithoutPending() {
        player.movement().applyPosition(10, 64, 10);
        confirmTracker.onConfirm(player, 3);
        assertEquals(1, queue.anomalies());
        assertTrue(player.movement().positionKnown());
    }
}
