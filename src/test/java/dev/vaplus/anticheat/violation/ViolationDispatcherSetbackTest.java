package dev.vaplus.anticheat.violation;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.TeleportQueue;
import dev.vaplus.anticheat.platform.TeleportSetbackHandler;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.FakeFlagEventPublisher;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.RecordingAlertSink;
import dev.vaplus.anticheat.testutil.RecordingPunishmentExecutor;
import dev.vaplus.anticheat.testutil.RecordingSetbackHandler;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ViolationDispatcher setback path (packet-level API, plan item 7)")
class ViolationDispatcherSetbackTest {

    private record Fixture(Harness harness, PlayerData player, ProbeChecks.Probe check) {
        RecordingSetbackHandler setbacks() {
            return harness.setbacks;
        }
    }

    @BeforeAll
    static void installPacketEvents() {
        // Position-sync wrappers read the server version from the global PacketEvents API.
        FakePacketEvents.install(ServerVersion.V_1_21_4);
    }

    @AfterAll
    static void uninstallPacketEvents() {
        FakePacketEvents.uninstall();
    }

    private static Fixture fixture(CheckCategory category, boolean withQueue, TestContexts.Builder builder) {
        CheckDescriptor descriptor = Descriptors.of("speed", category, d -> d.withVlDecayPerMinute(0).withSetbackVl(2.0));
        builder.check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new));
        if (withQueue) {
            builder.module(new CoreTrackingModule(false, false));
        }
        Harness harness = builder.build();
        PlayerData player = harness.newPlayer();
        harness.applySettings(player, d -> CheckSettings.of(d.defaults(), List.of()));
        return new Fixture(harness, player, player.checks().get(ProbeChecks.Probe.class));
    }

    private static Fixture fixture(CheckCategory category, boolean withQueue) {
        return fixture(category, withQueue, TestContexts.builder());
    }

    private static void establishSafePosition(PlayerData player) {
        player.movement().applyPosition(10.5, 64.0, -3.25);
        player.movement().applyRotation(90f, 12.5f);
        player.movement().finishChecks();
    }

    @Test
    @DisplayName("with the teleport queue: >= setback-vl requests the packet-level setback to the safe position")
    void packetLevelPath() {
        Fixture f = fixture(CheckCategory.MOVEMENT, true);
        establishSafePosition(f.player());
        f.player().movement().applyPosition(30, 70, 30); // flagged movement, not yet safe

        f.check().flag(1.0, "below");
        assertTrue(f.setbacks().targetRequests().isEmpty(), "below setback-vl nothing happens");
        f.check().flag(1.0, "reached");

        assertEquals(1, f.setbacks().targetRequests().size());
        assertTrue(f.setbacks().requests().isEmpty(), "the legacy path is not used");
        RecordingSetbackHandler.TargetRequest request = f.setbacks().targetRequests().get(0);
        assertSame(f.player(), request.player());
        assertEquals(new SetbackTarget(10.5, 64.0, -3.25, 90f, 12.5f), request.target());
        assertEquals("speed", request.reason(), "the check id is the reason");
        assertTrue(f.player().movement().setbackRequested());
    }

    @Test
    @DisplayName("every further violation above setback-vl requests again (the handler applies the cooldown)")
    void repeatsAboveThreshold() {
        Fixture f = fixture(CheckCategory.MOVEMENT, true);
        establishSafePosition(f.player());
        f.check().flag(3.0, "a");
        f.check().flag(1.0, "b");
        assertEquals(2, f.setbacks().targetRequests().size());
    }

    @Test
    @DisplayName("a single spike is not confirmed: the configured window needs several violations")
    void setbackNeedsConfirmations() {
        Fixture f = fixture(CheckCategory.MOVEMENT, true,
                TestContexts.builder().settings(TestContexts.globals().setbackConfirmations(2).build()));
        establishSafePosition(f.player());

        f.check().flag(3.0, "single spike");
        assertTrue(f.setbacks().targetRequests().isEmpty(), "one violation must not rubber-band the player");

        f.check().flag(1.0, "second violation");
        assertEquals(1, f.setbacks().targetRequests().size());
    }

    @Test
    @DisplayName("fallback: without the teleport queue the legacy Check-based request is used")
    void fallbackWithoutQueue() {
        Fixture f = fixture(CheckCategory.MOVEMENT, false);
        establishSafePosition(f.player());
        f.check().flag(2.0, "x");
        assertTrue(f.setbacks().targetRequests().isEmpty());
        assertEquals(1, f.setbacks().requests().size());
        assertSame(f.check(), f.setbacks().requests().get(0).cause());
    }

    @Test
    @DisplayName("fallback: with the queue but no safe position yet the legacy request is used")
    void fallbackWithoutSafePosition() {
        Fixture f = fixture(CheckCategory.MOVEMENT, true);
        f.check().flag(2.0, "x");
        assertTrue(f.setbacks().targetRequests().isEmpty());
        assertEquals(1, f.setbacks().requests().size());
    }

    @Test
    @DisplayName("non-movement categories and disabled setbacks never request a setback")
    void notSetbackCapable() {
        Fixture combat = fixture(CheckCategory.COMBAT, true);
        establishSafePosition(combat.player());
        combat.check().flag(5.0, "x");
        assertTrue(combat.setbacks().targetRequests().isEmpty());
        assertTrue(combat.setbacks().requests().isEmpty());

        Fixture disabled = fixture(CheckCategory.MOVEMENT, true,
                TestContexts.builder().settings(TestContexts.globals().setbacksEnabled(false).build()));
        establishSafePosition(disabled.player());
        disabled.check().flag(5.0, "x");
        assertTrue(disabled.setbacks().targetRequests().isEmpty());
        assertTrue(disabled.setbacks().requests().isEmpty());
    }

    @Test
    @DisplayName("end to end with TeleportSetbackHandler: the sync is queued and sent on the owner thread")
    void endToEndWithTeleportHandler() {
        Fixture f = fixture(CheckCategory.MOVEMENT, true);
        establishSafePosition(f.player());
        ViolationDispatcher dispatcher = new ViolationDispatcher(new FakeFlagEventPublisher(),
                new InMemoryViolationLog(10, 10), new RecordingAlertSink(), new RecordingPunishmentExecutor(),
                new TeleportSetbackHandler(null));

        dispatcher.apply(f.check(), 0.0, 2.0, "speed=1.2");

        TeleportQueue queue = TeleportQueue.resolve(f.player());
        assertEquals(1, queue.pendingCount());
        TeleportQueue.Entry entry = queue.head();
        assertEquals("speed", entry.reason());
        assertEquals(new SetbackTarget(10.5, 64.0, -3.25, 90f, 12.5f), entry.target());
        assertEquals(1, f.harness().lastTransport().sent().size());
        WrapperPlayServerPlayerPositionAndLook packet = assertInstanceOf(WrapperPlayServerPlayerPositionAndLook.class,
                f.harness().lastTransport().sent().get(0));
        assertEquals(entry.teleportId(), packet.getTeleportId());
        assertEquals(10.5, packet.getX());
        assertTrue(f.player().movement().setbackRequested());
    }
}
