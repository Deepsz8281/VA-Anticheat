package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.latency.ServerTickMarkers;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.ExemptionState;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GameStateTracker JOIN/RESPAWN hold until PLAYER_LOADED (L11)")
class GameStateTrackerLoadTest {

    private record Fixture(Harness harness, PlayerData player, GameStateTracker tracker, RecordingTransport transport) {
    }

    private static Fixture fixture(boolean awaitPlayerLoaded) {
        CoreTrackingModule module = new CoreTrackingModule(false, awaitPlayerLoaded);
        Harness harness = TestContexts.builder().module(module).build();
        PlayerData player = harness.newPlayer();
        GameStateTracker tracker = (GameStateTracker) harness.context.setup().outboundTrackers().get(0);
        return new Fixture(harness, player, tracker, harness.lastTransport());
    }

    private static void tick(PlayerData player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.onClientTick();
        }
    }

    @Test
    @DisplayName("the module registers the same tracker for outbound state and the inbound PLAYER_LOADED signal")
    void wiring() {
        Fixture f = fixture(true);
        ModuleSetup setup = f.harness().context.setup();
        assertTrue(f.tracker().awaitsPlayerLoaded());
        assertEquals(3, setup.inboundTrackers().size());
        assertSame(f.tracker(), setup.inboundTrackers().get(2), "appended after the existing inbound trackers");
        assertTrue(setup.inboundTrackers().get(0) instanceof TeleportConfirmTracker);
        assertFalse(fixture(false).tracker().awaitsPlayerLoaded());
        assertEquals(ServerTickMarkers.class, setup.components().get(1).type());
    }

    @Test
    @DisplayName("1.21.4+: JOIN stays exempt beyond its timer until PLAYER_LOADED arrives")
    void joinHeldUntilLoaded() {
        Fixture f = fixture(true);
        ExemptionState exemptions = f.player().exemptions();
        f.tracker().onJoin(f.player(), GameMode.SURVIVAL);
        assertTrue(exemptions.awaitingLoad(ExemptionCause.JOIN));
        tick(f.player(), 45);
        assertEquals(0, exemptions.ticksLeft(ExemptionCause.JOIN), "the 40-tick timer ran out");
        assertTrue(exemptions.isActive(ExemptionCause.JOIN), "still loading");

        f.tracker().onPlayerLoaded(f.player());
        assertFalse(exemptions.isActive(ExemptionCause.JOIN));
        assertFalse(exemptions.awaitingLoad(ExemptionCause.JOIN));
    }

    @Test
    @DisplayName("1.21.4+: an early PLAYER_LOADED does not shorten the JOIN timer")
    void earlyLoadKeepsTimer() {
        Fixture f = fixture(true);
        f.tracker().onJoin(f.player(), GameMode.SURVIVAL);
        tick(f.player(), 3);
        f.tracker().onPlayerLoaded(f.player());
        assertTrue(f.player().exemptions().isActive(ExemptionCause.JOIN));
        tick(f.player(), 37);
        assertFalse(f.player().exemptions().isActive(ExemptionCause.JOIN));
    }

    @Test
    @DisplayName("1.21.4+: without PLAYER_LOADED the hold expiry ends the JOIN exemption")
    void joinExpiryFallback() {
        Fixture f = fixture(true);
        ExemptionState exemptions = f.player().exemptions();
        f.tracker().onJoin(f.player(), GameMode.SURVIVAL);
        tick(f.player(), ExemptionState.MAX_HOLD_TICKS);
        assertTrue(exemptions.isActive(ExemptionCause.JOIN));
        tick(f.player(), 1);
        assertFalse(exemptions.isActive(ExemptionCause.JOIN));
        assertEquals(1L, exemptions.expiredLoadHolds());
        assertEquals(0L, exemptions.expiredHolds());
    }

    @Test
    @DisplayName("legacy server: JOIN is a plain 40-tick grant and PLAYER_LOADED is ignored")
    void legacyJoin() {
        Fixture f = fixture(false);
        ExemptionState exemptions = f.player().exemptions();
        f.tracker().onJoin(f.player(), GameMode.SURVIVAL);
        assertFalse(exemptions.awaitingLoad(ExemptionCause.JOIN));
        tick(f.player(), 39);
        assertTrue(exemptions.isActive(ExemptionCause.JOIN));
        tick(f.player(), 1);
        assertFalse(exemptions.isActive(ExemptionCause.JOIN));
        exemptions.holdUntilLoaded(ExemptionCause.TELEPORT);
        f.tracker().onPlayerLoaded(f.player());
        assertTrue(exemptions.awaitingLoad(ExemptionCause.TELEPORT), "legacy trackers never release load holds");
    }

    @Test
    @DisplayName("1.21.4+: RESPAWN is held from the first ack until PLAYER_LOADED, the 20-tick grant still applies")
    void respawnHeldUntilLoaded() {
        Fixture f = fixture(true);
        ExemptionState exemptions = f.player().exemptions();
        List<Runnable> afterSend = new ArrayList<>();
        f.tracker().onRespawn(f.player(), afterSend, GameMode.SURVIVAL, (byte) 0);
        assertFalse(exemptions.isActive(ExemptionCause.RESPAWN), "nothing before the first acknowledgement");
        afterSend.forEach(Runnable::run);
        assertEquals(2, f.transport().markerIds().size());

        f.player().acks().onPong(f.transport().markerIds().get(0));
        assertTrue(exemptions.isActive(ExemptionCause.RESPAWN));
        assertTrue(exemptions.awaitingLoad(ExemptionCause.RESPAWN));

        f.player().acks().onPong(f.transport().markerIds().get(1));
        assertEquals(20, exemptions.ticksLeft(ExemptionCause.RESPAWN));
        tick(f.player(), 25);
        assertTrue(exemptions.isActive(ExemptionCause.RESPAWN), "grant ran out, but the client is still loading");

        f.tracker().onPlayerLoaded(f.player());
        assertFalse(exemptions.isActive(ExemptionCause.RESPAWN));
    }

    @Test
    @DisplayName("1.21.4+: PLAYER_LOADED between the two acks leaves the bracket hold until the second ack")
    void loadedBetweenAcks() {
        Fixture f = fixture(true);
        ExemptionState exemptions = f.player().exemptions();
        List<Runnable> afterSend = new ArrayList<>();
        f.tracker().onRespawn(f.player(), afterSend, GameMode.SURVIVAL, (byte) 0);
        afterSend.forEach(Runnable::run);
        f.player().acks().onPong(f.transport().markerIds().get(0));
        f.tracker().onPlayerLoaded(f.player());
        assertTrue(exemptions.isActive(ExemptionCause.RESPAWN), "bracket hold still pending");
        f.player().acks().onPong(f.transport().markerIds().get(1));
        tick(f.player(), 20);
        assertFalse(exemptions.isActive(ExemptionCause.RESPAWN));
    }

    @Test
    @DisplayName("legacy server: RESPAWN ends 20 ticks after the second ack, unchanged")
    void legacyRespawn() {
        Fixture f = fixture(false);
        ExemptionState exemptions = f.player().exemptions();
        List<Runnable> afterSend = new ArrayList<>();
        f.tracker().onRespawn(f.player(), afterSend, GameMode.CREATIVE, (byte) 0);
        afterSend.forEach(Runnable::run);
        f.player().acks().onPong(f.transport().markerIds().get(0));
        assertFalse(exemptions.awaitingLoad(ExemptionCause.RESPAWN));
        f.player().acks().onPong(f.transport().markerIds().get(1));
        assertEquals(GameMode.CREATIVE, f.player().abilities().gameMode());
        tick(f.player(), 19);
        assertTrue(exemptions.isActive(ExemptionCause.RESPAWN));
        tick(f.player(), 1);
        assertFalse(exemptions.isActive(ExemptionCause.RESPAWN));
    }

    @Test
    @DisplayName("PLAYER_LOADED support is gated on the server version (1.21.4+), false without PacketEvents")
    void serverVersionGate() {
        if (PacketEvents.getAPI() != null) {
            return; // another installed API would make the fake a no-op; nothing to assert reliably
        }
        assertFalse(GameStateTracker.serverHasPlayerLoaded());
        try {
            FakePacketEvents.install(ServerVersion.V_1_21_3);
            assertFalse(GameStateTracker.serverHasPlayerLoaded());
        } finally {
            FakePacketEvents.uninstall();
        }
        try {
            FakePacketEvents.install(ServerVersion.V_1_21_4);
            assertTrue(GameStateTracker.serverHasPlayerLoaded());
            assertTrue(((GameStateTracker) TestContexts.builder().module(new CoreTrackingModule(false)).build()
                    .context.setup().outboundTrackers().get(0)).awaitsPlayerLoaded());
        } finally {
            FakePacketEvents.uninstall();
        }
    }
}
