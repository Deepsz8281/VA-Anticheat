package dev.vaplus.anticheat.player;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.testutil.Descriptors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PlayerData")
class PlayerDataTest {

    private static Harness harness(GlobalSettings settings) {
        return TestContexts.builder().settings(settings).build();
    }

    @Test
    @DisplayName("create wires identity, version, transport and state objects")
    void create() {
        Harness harness = TestContexts.empty();
        UUID id = UUID.randomUUID();
        RecordingTransport transport = new RecordingTransport();
        PlayerData player = PlayerData.create(harness.context, id, "Alex", ClientVersion.V_1_21_4, transport);
        assertEquals(id, player.uuid());
        assertEquals("Alex", player.name());
        assertEquals(ClientVersion.V_1_21_4, player.clientVersion());
        assertEquals(transport, player.transport());
        assertEquals(harness.time.currentTimeMillis(), player.createdAtMillis());
        assertNotNull(player.checks());
        assertNotNull(player.movement());
        assertNotNull(player.exemptions());
        assertNull(player.bukkitPlayer());
        assertFalse(player.bypassing());
        assertFalse(player.tickEndMode());
        assertEquals(0, player.clientTicks());
        assertEquals("PlayerData[Alex, " + ClientVersion.V_1_21_4.getReleaseName() + "]", player.toString());
    }

    @Test
    @DisplayName("a null client version becomes UNKNOWN; null identity is rejected")
    void nullHandling() {
        Harness harness = TestContexts.empty();
        PlayerData player = PlayerData.create(harness.context, UUID.randomUUID(), "A", null, new RecordingTransport());
        assertEquals(ClientVersion.UNKNOWN, player.clientVersion());
        assertThrows(NullPointerException.class,
                () -> PlayerData.create(harness.context, null, "A", ClientVersion.V_1_21_4, new RecordingTransport()));
        assertThrows(NullPointerException.class,
                () -> PlayerData.create(harness.context, UUID.randomUUID(), null, ClientVersion.V_1_21_4, new RecordingTransport()));
        assertThrows(NullPointerException.class,
                () -> PlayerData.create(harness.context, UUID.randomUUID(), "A", ClientVersion.V_1_21_4, null));
    }

    @ParameterizedTest
    @CsvSource({
            "SURVIVAL,MOVEMENT,false", "ADVENTURE,MOVEMENT,false", "CREATIVE,MOVEMENT,true", "SPECTATOR,MOVEMENT,true",
            "SURVIVAL,WORLD,false", "CREATIVE,WORLD,true", "SPECTATOR,WORLD,true",
            "SURVIVAL,INVENTORY,false", "CREATIVE,INVENTORY,true", "SPECTATOR,INVENTORY,true",
            "SURVIVAL,COMBAT,false", "CREATIVE,COMBAT,false", "SPECTATOR,COMBAT,true",
            "SURVIVAL,PACKET,false", "CREATIVE,PACKET,false", "SPECTATOR,PACKET,false"})
    @DisplayName("game-mode exemptions per category")
    void gameModeExemptions(GameMode mode, CheckCategory category, boolean exempt) {
        PlayerData player = TestContexts.empty().newPlayer();
        player.abilities().applyGameMode(mode);
        assertEquals(exempt, player.isExempt(category));
    }

    @Test
    @DisplayName("heartbeat sends a marker through the transport on the owner thread")
    void heartbeatSendsMarker() {
        Harness harness = TestContexts.empty();
        PlayerData player = harness.newPlayer();
        player.scheduleHeartbeat();
        player.scheduleHeartbeat();
        assertEquals(2, harness.lastTransport().markerIds().size());
        assertEquals(2, harness.lastTransport().ownerThreadTasks());
        assertEquals(2, player.acks().pendingCount());
    }

    @Test
    @DisplayName("a client that leaves a marker unanswered past ack-timeout gets timeout-kicks (one per 5 s)")
    void ackTimeoutKick() {
        Harness harness = harness(TestContexts.globals().ackTimeoutMillis(5_000).build());
        PlayerData player = harness.newPlayer();
        player.scheduleHeartbeat();
        harness.time.advanceMillis(5_000);
        player.scheduleHeartbeat();
        assertTrue(harness.punishments.kicks().isEmpty(), "exactly at the timeout is still fine");
        harness.time.advanceMillis(1);
        player.scheduleHeartbeat();
        assertEquals(1, harness.punishments.kicks().size());
        assertEquals("timeout-kick", harness.punishments.kicks().get(0).messageKey());
        assertFalse(player.disconnecting(), "a kick request no longer marks the player disconnecting");

        // claimKick rate limit: within 5 s no second kick is requested even though the marker stays unanswered
        harness.time.advanceMillis(4_999);
        player.scheduleHeartbeat();
        assertEquals(1, harness.punishments.kicks().size(), "kick request is rate limited");
        harness.time.advanceMillis(2);
        player.scheduleHeartbeat();
        assertEquals(2, harness.punishments.kicks().size(), "kick is retried after the 5 s rate limit");
    }

    @Test
    @DisplayName("answering markers keeps the player from timing out")
    void answeredMarkersDoNotTimeOut() {
        Harness harness = harness(TestContexts.globals().ackTimeoutMillis(5_000).build());
        PlayerData player = harness.newPlayer();
        for (int i = 0; i < 100; i++) {
            player.scheduleHeartbeat();
            harness.time.advanceMillis(50);
            player.acks().onPong(harness.lastTransport().lastMarkerId());
        }
        assertTrue(harness.punishments.kicks().isEmpty());
        assertFalse(player.disconnecting());
    }

    @Test
    @DisplayName("ack-timeout can no longer be disabled; 0 and out-of-range values are rejected")
    void timeoutDisabled() {
        assertThrows(IllegalArgumentException.class,
                () -> TestContexts.globals().ackTimeoutMillis(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> TestContexts.globals().ackTimeoutMillis(4_999).build());
        assertThrows(IllegalArgumentException.class,
                () -> TestContexts.globals().ackTimeoutMillis(120_001).build());
        assertEquals(15_000L, TestContexts.globals().build().ackTimeoutMillis());
    }

    @Test
    @DisplayName("heartbeat does nothing for a player already marked as disconnecting")
    void disconnectingNoHeartbeat() {
        Harness harness = TestContexts.empty();
        PlayerData player = harness.newPlayer();
        player.markDisconnecting();
        player.scheduleHeartbeat();
        assertTrue(harness.lastTransport().markerIds().isEmpty());
        assertEquals(0, harness.lastTransport().ownerThreadTasks());
    }

    @Test
    @DisplayName("setback cooldown: first claim succeeds, later ones only after the cooldown")
    void setbackCooldown() {
        PlayerData player = TestContexts.empty().newPlayer();
        long cooldown = 250_000_000L;
        assertTrue(player.claimSetback(1_000, cooldown));
        assertFalse(player.claimSetback(1_000 + cooldown - 1, cooldown));
        assertTrue(player.claimSetback(1_000 + cooldown, cooldown));
        assertFalse(player.claimSetback(1_000 + cooldown + 1, cooldown));
    }

    @Test
    @DisplayName("setback claim works on a clock that starts at zero or negative")
    void setbackCooldownClockEdge() {
        PlayerData player = TestContexts.empty().newPlayer();
        assertTrue(player.claimSetback(0, 100));
        assertFalse(player.claimSetback(50, 100));
        PlayerData other = TestContexts.empty().newPlayer();
        assertTrue(other.claimSetback(-5_000, 100));
        assertTrue(other.claimSetback(-4_900, 100));
    }

    @Test
    @DisplayName("onClientTick advances tick counters, exemptions, movement and runs tick checks")
    void clientTick() {
        var descriptor = Descriptors.movement("tick");
        Harness harness = TestContexts.builder().check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Tick::new)).build();
        PlayerData player = harness.newPlayer();
        player.exemptions().grant(dev.vaplus.anticheat.player.state.ExemptionCause.TELEPORT, 2);
        player.onClientTick();
        player.onClientTick();
        assertEquals(2, player.clientTicks());
        assertEquals(2, player.movement().ticksSincePosition());
        assertFalse(player.exemptions().isActive(dev.vaplus.anticheat.player.state.ExemptionCause.TELEPORT));
        assertEquals(2, player.checks().get(ProbeChecks.Tick.class).ticks);
        assertEquals(2, harness.profiler.report().clientTicks());
    }

    @Test
    @DisplayName("owner thread is the first thread that claims it")
    void ownerThread() throws Exception {
        PlayerData player = TestContexts.empty().newPlayer();
        assertFalse(player.isOwnerThread());
        player.claimOwnerThread();
        assertTrue(player.isOwnerThread());
        boolean[] other = new boolean[1];
        Thread thread = new Thread(() -> {
            player.claimOwnerThread();
            other[0] = player.isOwnerThread();
        });
        thread.start();
        thread.join();
        assertFalse(other[0], "a second thread must not steal ownership");
        assertTrue(player.isOwnerThread());
    }
}
