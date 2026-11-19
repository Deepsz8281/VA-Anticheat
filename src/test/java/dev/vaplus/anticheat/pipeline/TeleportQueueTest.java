package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TeleportQueue (outbound position syncs / TELEPORT_CONFIRM FIFO)")
class TeleportQueueTest {

    private static final SetbackTarget TARGET = new SetbackTarget(1.5, 64.0, -3.5, 90f, 10f);
    private static final SetbackTarget OTHER = new SetbackTarget(9.0, 70.0, 9.0, 0f, 0f);

    private static TeleportQueue queue() {
        return new TeleportQueue();
    }

    private static boolean add(TeleportQueue queue, int id) {
        return queue.register(id, TARGET, 0, 0, 0L, TeleportQueue.SERVER_SYNC);
    }

    @Nested
    @DisplayName("FIFO and confirm matching")
    class Matching {

        @Test
        @DisplayName("confirms pop the queue in send order")
        void fifo() {
            TeleportQueue queue = queue();
            assertTrue(add(queue, 1));
            assertTrue(add(queue, 2));
            assertEquals(1, queue.head().teleportId());
            assertEquals(2, queue.pendingCount());

            assertEquals(TeleportQueue.ConfirmResult.CONFIRMED, queue.confirm(1));
            assertEquals(2, queue.head().teleportId());
            assertEquals(1, queue.pendingCount());

            assertEquals(TeleportQueue.ConfirmResult.CONFIRMED, queue.confirm(2));
            assertFalse(queue.hasPending());
            assertEquals(0, queue.anomalies());
        }

        @Test
        @DisplayName("an out-of-order confirm is an anomaly and does not pop anything")
        void outOfOrder() {
            TeleportQueue queue = queue();
            add(queue, 1);
            add(queue, 2);

            assertEquals(TeleportQueue.ConfirmResult.OUT_OF_ORDER, queue.confirm(2));
            assertEquals(1, queue.anomalies());
            assertEquals(2, queue.pendingCount(), "the queue stays consistent, no force-unlock");
            assertEquals(1, queue.head().teleportId());

            assertEquals(TeleportQueue.ConfirmResult.CONFIRMED, queue.confirm(1));
            assertEquals(1, queue.pendingCount());
        }

        @Test
        @DisplayName("unknown and duplicate confirmations count as anomalies without changing the queue")
        void unknownAndDuplicate() {
            TeleportQueue queue = queue();
            add(queue, 1);

            assertEquals(TeleportQueue.ConfirmResult.UNKNOWN, queue.confirm(99));
            assertEquals(1, queue.anomalies());
            assertEquals(1, queue.pendingCount());

            assertEquals(TeleportQueue.ConfirmResult.CONFIRMED, queue.confirm(1));
            assertEquals(TeleportQueue.ConfirmResult.NO_PENDING, queue.confirm(1));
            assertEquals(2, queue.anomalies());
            assertFalse(queue.hasPending());
        }

        @Test
        @DisplayName("a confirmation without any pending sync is an anomaly")
        void noPending() {
            TeleportQueue queue = queue();
            assertEquals(TeleportQueue.ConfirmResult.NO_PENDING, queue.confirm(1));
            assertEquals(1, queue.anomalies());
        }

        @Test
        @DisplayName("registering an already pending id is ignored (outbound observer sees our own setback)")
        void duplicateRegistration() {
            TeleportQueue queue = queue();
            add(queue, 7);
            assertFalse(queue.register(7, OTHER, 0, 0, 0L, "setback"));
            assertEquals(1, queue.pendingCount());
            assertEquals(TARGET, queue.head().target(), "the first registration wins");
            assertEquals(0, queue.anomalies());
        }
    }

    @Nested
    @DisplayName("entry data (relative flags, target, epoch)")
    class Entries {

        @Test
        @DisplayName("absolute and relative flags are stored and readable, including 1.21.2 velocity bits")
        void relativeFlags() {
            TeleportQueue queue = queue();
            int flags = TeleportQueue.FLAG_X | TeleportQueue.FLAG_YAW
                    | TeleportQueue.FLAG_DELTA_X | TeleportQueue.FLAG_DELTA_Z | TeleportQueue.FLAG_ROTATE_DELTA;
            queue.register(5, new SetbackTarget(0.25, 0, -0.5, 10f, 20f), flags, 3, 12L, "server-sync");

            TeleportQueue.Entry entry = queue.head();
            assertEquals(5, entry.teleportId());
            assertEquals(0.25, entry.target().x());
            assertEquals(-0.5, entry.target().z());
            assertEquals(10f, entry.target().yaw());
            assertEquals(3, entry.worldEpoch());
            assertEquals(12L, entry.registeredAtClientTick());
            assertEquals("server-sync", entry.reason());
            assertTrue(entry.relativeX());
            assertFalse(entry.relativeY());
            assertFalse(entry.relativeZ());
            assertTrue(entry.relativeYaw());
            assertFalse(entry.relativePitch());
            assertTrue(entry.relativeVelocity());
        }

        @Test
        @DisplayName("flag constants equal the installed PacketEvents RelativeFlag mapping")
        void flagMapping() {
            TeleportQueue.assertRelativeFlagMapping();
            assertEquals(0x0001, TeleportQueue.FLAG_X);
            assertEquals(0x0010, TeleportQueue.FLAG_PITCH);
            assertEquals(0x0080, TeleportQueue.FLAG_DELTA_Z);
            assertEquals(0x0100, TeleportQueue.FLAG_ROTATE_DELTA);
            assertFalse(queue().hasPending());
        }

        @Test
        @DisplayName("snapshot returns the pending entries oldest first")
        void snapshot() {
            TeleportQueue queue = queue();
            add(queue, 1);
            add(queue, 2);
            assertEquals(2, queue.snapshot().size());
            assertEquals(1, queue.snapshot().get(0).teleportId());
            assertEquals(2, queue.snapshot().get(1).teleportId());
        }
    }

    @Nested
    @DisplayName("gating, expiry and world changes")
    class Lifecycle {

        @Test
        @DisplayName("awaitingTeleport expires an entry only after MAX_AGE_TICKS without a confirm")
        void expiry() {
            TeleportQueue queue = queue();
            queue.register(1, TARGET, 0, 0, 5L, TeleportQueue.SERVER_SYNC);

            assertTrue(queue.awaitingTeleport(5L + TeleportQueue.MAX_AGE_TICKS));
            assertTrue(queue.hasPending(), "exactly MAX_AGE_TICKS is still pending");

            assertFalse(queue.awaitingTeleport(6L + TeleportQueue.MAX_AGE_TICKS));
            assertFalse(queue.hasPending());
            assertEquals(1, queue.expired());
            assertTrue(queue.anomalies() >= 1, "an expired sync is an anomaly");
        }

        @Test
        @DisplayName("discardOtherEpochs keeps syncs of the new world only")
        void epochDiscard() {
            TeleportQueue queue = queue();
            queue.register(1, TARGET, 0, 0, 0L, TeleportQueue.SERVER_SYNC);
            queue.register(2, TARGET, 0, 1, 0L, TeleportQueue.SERVER_SYNC);

            assertEquals(1, queue.discardOtherEpochs(1));
            assertEquals(1, queue.pendingCount());
            assertEquals(2, queue.head().teleportId());
            assertEquals(1, queue.dropped());
            assertEquals(0, queue.discardOtherEpochs(1), "the new-epoch sync stays");
            assertEquals(1, queue.pendingCount());
        }

        @Test
        @DisplayName("clear drops everything (join)")
        void clear() {
            TeleportQueue queue = queue();
            add(queue, 1);
            add(queue, 2);
            queue.clear();
            assertFalse(queue.hasPending());
            assertEquals(2, queue.dropped());
            assertEquals(0, queue.pendingCount());
        }

        @Test
        @DisplayName("overflow drops the oldest entry and counts an anomaly")
        void overflow() {
            TeleportQueue queue = queue();
            for (int id = 1; id <= TeleportQueue.MAX_PENDING + 1; id++) {
                add(queue, id);
            }
            assertEquals(TeleportQueue.MAX_PENDING, queue.pendingCount());
            assertEquals(2, queue.head().teleportId(), "oldest was dropped");
            assertEquals(1, queue.dropped());
            assertEquals(1, queue.anomalies());
        }
    }

    @Nested
    @DisplayName("self-sent sync ids")
    class OwnIds {

        @Test
        @DisplayName("own ids are negative and descending in send order")
        void negativeIds() {
            TeleportQueue queue = queue();
            int first = queue.registerOwn(TARGET, 0, 0L, "setback");
            int second = queue.registerOwn(TARGET, 0, 0L, "setback");
            assertTrue(first < 0);
            assertEquals(first - 1, second);
            assertEquals(2, queue.pendingCount());
            assertTrue(queue.containsId(first));
            assertTrue(queue.containsId(second));
        }

        @Test
        @DisplayName("nextOwnTeleportId skips ids that are already pending")
        void skipPending() {
            TeleportQueue queue = queue();
            assertEquals(-1, queue.nextOwnTeleportId());
            add(queue, -1);
            assertEquals(-2, queue.nextOwnTeleportId());
        }

        @Test
        @DisplayName("a self-registered sync has no relative flags and keeps the reason")
        void ownEntry() {
            TeleportQueue queue = queue();
            int id = queue.registerOwn(TARGET, 4, 8L, "speed");
            TeleportQueue.Entry entry = queue.head();
            assertEquals(id, entry.teleportId());
            assertEquals(TARGET, entry.target());
            assertEquals(0, entry.relativeFlags());
            assertEquals(4, entry.worldEpoch());
            assertEquals(8L, entry.registeredAtClientTick());
            assertEquals("speed", entry.reason());
        }
    }

    @Nested
    @DisplayName("component lookup")
    class Lookup {

        @Test
        @DisplayName("without the core-tracking module the queue is absent (callers use the Bukkit fallback)")
        void absent() {
            Harness harness = TestContexts.empty();
            assertNull(TeleportQueue.resolve(harness.newPlayer()));
            assertNull(TeleportQueue.keyOf(ModuleSetup.EMPTY));
        }

        @Test
        @DisplayName("the component is registered once per player and resolvable")
        void present() {
            CoreTrackingModule module = new CoreTrackingModule(false);
            Harness harness = TestContexts.builder().module(module).build();
            PlayerData one = harness.newPlayer();
            PlayerData two = harness.newPlayer();

            TeleportQueue first = TeleportQueue.resolve(one);
            TeleportQueue second = TeleportQueue.resolve(two);
            assertSame(first, one.component(module.teleportQueueKey()));
            assertNotSame(first, second);
            assertSame(module.teleportQueueKey(), TeleportQueue.keyOf(harness.context.setup()));
        }
    }
}
