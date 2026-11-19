package dev.vaplus.anticheat.platform;

import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.TeleportQueue;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TeleportSetbackHandler packet-level request")
class TeleportSetbackHandlerTest {

    private static final SetbackTarget TARGET = new SetbackTarget(2.5, 64.0, -3.5, 45f, -10f);

    @BeforeAll
    static void installPacketEvents() {
        // The wrapper constructor reads the server version from the global PacketEvents API.
        FakePacketEvents.install(ServerVersion.V_1_21_4);
    }

    @AfterAll
    static void uninstallPacketEvents() {
        FakePacketEvents.uninstall();
    }

    private static Harness harness(boolean withQueue) {
        TestContexts.Builder builder = TestContexts.builder();
        if (withQueue) {
            builder.module(new CoreTrackingModule(false));
        }
        return builder.build();
    }

    @Test
    @DisplayName("request registers a self sync in the queue and sends it through the transport")
    void requestSendsSync() {
        Harness harness = harness(true);
        PlayerData player = harness.newPlayer();
        RecordingTransport transport = harness.lastTransport();
        TeleportQueue queue = TeleportQueue.resolve(player);
        player.movement().applyPosition(0, 64, 0);
        player.movement().finishChecks();

        TeleportSetbackHandler handler = new TeleportSetbackHandler(null);
        handler.request(player, TARGET, "speed");

        assertEquals(1, queue.pendingCount());
        TeleportQueue.Entry entry = queue.head();
        assertTrue(entry.teleportId() < 0, "own ids are disjoint from the server's non-negative ids");
        assertEquals(TARGET, entry.target());
        assertEquals(0, entry.relativeFlags());
        assertEquals("speed", entry.reason());
        assertTrue(player.movement().setbackRequested());

        assertEquals(1, transport.sent().size());
        assertEquals(1, transport.ownerThreadTasks(), "the send is scheduled on the owner thread");
        WrapperPlayServerPlayerPositionAndLook packet =
                assertInstanceOf(WrapperPlayServerPlayerPositionAndLook.class, transport.sent().get(0));
        assertEquals(entry.teleportId(), packet.getTeleportId());
        assertEquals(2.5, packet.getX());
        assertEquals(64.0, packet.getY());
        assertEquals(-3.5, packet.getZ());
        assertEquals(45f, packet.getYaw());
        assertEquals(-10f, packet.getPitch());
        assertEquals((byte) 0, packet.getRelativeMask(), "an absolute setback uses no relative flags");
        assertEquals(Vector3d.zero(), packet.getDeltaMovement());
        assertFalse(packet.isDismountVehicle());

        assertEquals(TeleportQueue.ConfirmResult.CONFIRMED, queue.confirm(entry.teleportId()));
    }

    @Test
    @DisplayName("the setback cooldown applies to the packet-level path too")
    void cooldown() {
        Harness harness = harness(true);
        PlayerData player = harness.newPlayer();
        TeleportQueue queue = TeleportQueue.resolve(player);
        TeleportSetbackHandler handler = new TeleportSetbackHandler(null);

        handler.request(player, TARGET, "one");
        handler.request(player, TARGET, "two");
        assertEquals(1, queue.pendingCount(), "second request inside the cooldown is dropped");

        harness.time.advanceMillis(250);
        handler.request(player, TARGET, "three");
        assertEquals(2, queue.pendingCount());
    }

    @Test
    @DisplayName("without the queue component the handler falls back to the Bukkit path (no send without a player)")
    void fallbackWithoutQueue() {
        Harness harness = harness(false);
        PlayerData player = harness.newPlayer();
        assertNull(TeleportQueue.resolve(player));

        TeleportSetbackHandler handler = new TeleportSetbackHandler(null);
        handler.request(player, TARGET, "speed");

        assertTrue(harness.lastTransport().sent().isEmpty());
        assertTrue(player.movement().setbackRequested());
    }

    @Test
    @DisplayName("a non-finite target is never encoded; the fallback is used instead")
    void nonFiniteTarget() {
        Harness harness = harness(true);
        PlayerData player = harness.newPlayer();
        TeleportQueue queue = TeleportQueue.resolve(player);
        TeleportSetbackHandler handler = new TeleportSetbackHandler(null);

        handler.request(player, new SetbackTarget(Double.NaN, 64, 0, 0f, 0f), "broken");

        assertTrue(queue.pendingCount() == 0);
        assertTrue(harness.lastTransport().sent().isEmpty());
    }

    @Test
    @DisplayName("the legacy Check-based request still uses the safe position (unchanged fallback)")
    void legacyRequest() {
        Harness harness = harness(true);
        PlayerData player = harness.newPlayer();
        player.movement().applyPosition(4, 65, 4);
        player.movement().finishChecks();
        assertEquals(4.0, player.movement().safeX());

        TeleportSetbackHandler handler = new TeleportSetbackHandler(null);
        handler.request(player, (dev.vaplus.anticheat.check.Check) null);

        assertEquals(0, TeleportQueue.resolve(player).pendingCount(),
                "the legacy path does not enqueue a packet-level sync");
        assertTrue(player.movement().setbackRequested());
    }

    @Test
    @DisplayName("a relative flag constant from PacketEvents is what the queue records")
    void relativeFlagContract() {
        assertEquals(RelativeFlag.NONE.getFullMask(), 0);
        assertEquals(RelativeFlag.ROTATE_DELTA.getFullMask(), TeleportQueue.FLAG_ROTATE_DELTA);
    }
}
