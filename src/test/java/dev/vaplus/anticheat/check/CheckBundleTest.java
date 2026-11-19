package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.profiling.CheckProfiler;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckBundle dispatch")
class CheckBundleTest {

    private static final CheckDescriptor POS_A = Descriptors.movement("pos-a");
    private static final CheckDescriptor POS_B = Descriptors.of("pos-b", CheckCategory.MOVEMENT, d -> d.withEnabled(false));
    private static final CheckDescriptor ROT = Descriptors.movement("rot");
    private static final CheckDescriptor TICK = Descriptors.movement("tick");
    private static final CheckDescriptor BOTH = Descriptors.movement("both");
    private static final CheckDescriptor PLAIN = Descriptors.movement("plain");
    private static final CheckDescriptor PACKET = Descriptors.packet("packet");
    private static final CheckDescriptor OUTBOUND = Descriptors.packet("outbound");
    private static final CheckDescriptor BLOCK = Descriptors.of("block", CheckCategory.WORLD);

    private static TestContexts.Builder fullBuilder() {
        return TestContexts.builder()
                .check(POS_A, ProbeChecks.factory(POS_A, ProbeChecks.Position::new))
                .check(POS_B, ProbeChecks.factory(POS_B, ProbeChecks.Position::new))
                .check(ROT, ProbeChecks.factory(ROT, ProbeChecks.Rotation::new))
                .check(TICK, ProbeChecks.factory(TICK, ProbeChecks.Tick::new))
                .check(BOTH, ProbeChecks.factory(BOTH, ProbeChecks.PositionAndTick::new))
                .check(PLAIN, ProbeChecks.factory(PLAIN, ProbeChecks.Probe::new))
                .check(PACKET, ProbeChecks.factory(PACKET, ProbeChecks.Packet::new))
                .check(OUTBOUND, ProbeChecks.factory(OUTBOUND, ProbeChecks.Outbound::new))
                .check(BLOCK, ProbeChecks.factory(BLOCK, ProbeChecks.Block::new));
    }

    private static <T extends Check> T find(PlayerData player, CheckDescriptor descriptor, Class<T> type) {
        for (Check check : player.checks().all()) {
            if (check.descriptor() == descriptor) {
                return type.cast(check);
            }
        }
        throw new AssertionError("missing " + descriptor.id());
    }

    @Test
    @DisplayName("all() is in slot order, immutable, and lookup by slot and type works")
    void lookups() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        CheckBundle bundle = player.checks();
        assertEquals(9, bundle.all().size());
        for (int i = 0; i < bundle.all().size(); i++) {
            assertEquals(i, bundle.all().get(i).slot());
            assertSame(bundle.all().get(i), bundle.get(i));
            assertSame(harness.registry.descriptor(i), bundle.get(i).descriptor());
        }
        assertThrows(UnsupportedOperationException.class, () -> bundle.all().clear());
        assertTrue(bundle.get(ProbeChecks.Rotation.class) instanceof ProbeChecks.Rotation);
        assertNull(bundle.get(ProbeChecks.Throwing.class));
    }

    @Test
    @DisplayName("position checks run only for enabled checks")
    void onlyEnabledChecks() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Position enabled = find(player, POS_A, ProbeChecks.Position.class);
        ProbeChecks.Position disabled = find(player, POS_B, ProbeChecks.Position.class);
        player.movementUpdate().assign(0, 0, 0, 1, 0, 0, true, true, false, true, false, false, 1, 0L);

        player.checks().dispatchPosition(player.movementUpdate());

        assertEquals(1, enabled.updates.size());
        assertEquals(0, disabled.updates.size());
    }

    @Test
    @DisplayName("enabling a check at runtime takes effect on the next dispatch (epoch sync)")
    void runtimeEnable() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Position check = find(player, POS_B, ProbeChecks.Position.class);
        harness.registry.applySettings(d -> CheckSettings.of(d.defaults().withEnabled(true), List.of()));

        player.checks().dispatchPosition(player.movementUpdate());
        assertEquals(1, check.updates.size());

        harness.registry.applySettings(d -> CheckSettings.of(d.defaults().withEnabled(false), List.of()));
        player.checks().dispatchPosition(player.movementUpdate());
        assertEquals(1, check.updates.size(), "disabled again");
    }

    @Test
    @DisplayName("capability arrays: each callback reaches exactly the checks implementing it")
    void capabilityRouting() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Position pos = find(player, POS_A, ProbeChecks.Position.class);
        ProbeChecks.Rotation rot = find(player, ROT, ProbeChecks.Rotation.class);
        ProbeChecks.Tick tick = find(player, TICK, ProbeChecks.Tick.class);
        ProbeChecks.PositionAndTick both = find(player, BOTH, ProbeChecks.PositionAndTick.class);
        ProbeChecks.Packet packet = find(player, PACKET, ProbeChecks.Packet.class);
        ProbeChecks.Outbound outbound = find(player, OUTBOUND, ProbeChecks.Outbound.class);
        ProbeChecks.Block block = find(player, BLOCK, ProbeChecks.Block.class);

        player.checks().dispatchPosition(player.movementUpdate());
        assertEquals(1, pos.updates.size());
        assertEquals(1, both.updates.size());
        assertEquals(0, both.ticks);
        assertEquals(0, tick.ticks);
        assertEquals(0, rot.updates.size());

        player.checks().dispatchClientTick();
        assertEquals(1, tick.ticks);
        assertEquals(1, both.ticks, "a check implementing two capabilities receives both callbacks");
        assertEquals(1, both.updates.size());
        assertEquals(1, pos.updates.size());

        player.checks().dispatchRotation(player.lookChange());
        assertEquals(1, rot.updates.size());

        player.checks().dispatchReceive(null);
        player.checks().dispatchReceive(null);
        player.checks().dispatchSend(null);
        assertEquals(2, packet.received);
        assertEquals(2, packet.received, "outbound dispatch must not reach inbound-only packet checks");
        assertEquals(1, outbound.sent, "inbound dispatch must not reach outbound-only checks");

        player.checks().dispatchInteraction(new BlockInteraction());
        assertEquals(1, block.interactions);
    }

    @Test
    @DisplayName("bypassing players are skipped by every dispatch method, and resume after bypass ends")
    void bypass() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.PositionAndTick both = find(player, BOTH, ProbeChecks.PositionAndTick.class);
        ProbeChecks.Packet packet = find(player, PACKET, ProbeChecks.Packet.class);
        ProbeChecks.Outbound outbound = find(player, OUTBOUND, ProbeChecks.Outbound.class);
        ProbeChecks.Block block = find(player, BLOCK, ProbeChecks.Block.class);
        ProbeChecks.Rotation rot = find(player, ROT, ProbeChecks.Rotation.class);

        player.updateBypass(true);
        player.checks().dispatchPosition(player.movementUpdate());
        player.checks().dispatchRotation(player.lookChange());
        player.checks().dispatchClientTick();
        player.checks().dispatchReceive(null);
        player.checks().dispatchSend(null);
        player.checks().dispatchInteraction(new BlockInteraction());
        assertEquals(0, both.updates.size() + both.ticks + packet.received + outbound.sent + block.interactions + rot.updates.size());

        player.updateBypass(false);
        player.checks().dispatchPosition(player.movementUpdate());
        assertEquals(1, both.updates.size());
    }

    @Test
    @DisplayName("an exception in one check does not stop the next check and is counted")
    void exceptionIsolation() {
        CheckDescriptor bad = Descriptors.movement("bad");
        CheckDescriptor good = Descriptors.movement("good");
        Harness harness = TestContexts.builder()
                .check(bad, ProbeChecks.factory(bad, ProbeChecks.Throwing::new))
                .check(good, ProbeChecks.factory(good, ProbeChecks.Position::new))
                .build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Throwing throwing = find(player, bad, ProbeChecks.Throwing.class);
        ProbeChecks.Position next = find(player, good, ProbeChecks.Position.class);

        player.checks().dispatchPosition(player.movementUpdate());

        assertEquals(1, throwing.calls);
        assertEquals(1, next.updates.size(), "the second check must still run");
        assertEquals(1, harness.errors.totalErrors());
        List<LogRecord> logs = harness.logs.records();
        assertEquals(1, logs.size());
        assertEquals(Level.WARNING, logs.get(0).getLevel());
        assertTrue(logs.get(0).getMessage().contains("bad"));
        assertTrue(logs.get(0).getMessage().contains("Steve"));
        assertEquals("boom-position", logs.get(0).getThrown().getMessage());
    }

    @Test
    @DisplayName("every dispatch method isolates exceptions")
    void allDispatchMethodsIsolate() {
        CheckDescriptor bad = Descriptors.movement("bad");
        Harness harness = TestContexts.builder().check(bad, ProbeChecks.factory(bad, ProbeChecks.Throwing::new)).build();
        PlayerData player = harness.newPlayer();
        player.checks().dispatchPosition(player.movementUpdate());
        player.checks().dispatchRotation(player.lookChange());
        player.checks().dispatchClientTick();
        player.checks().dispatchReceive(null);
        assertEquals(4, find(player, bad, ProbeChecks.Throwing.class).calls);
        assertEquals(4, harness.errors.totalErrors());
    }

    @Test
    @DisplayName("linkage errors from a check are contained as well")
    void linkageErrorContained() {
        CheckDescriptor descriptor = Descriptors.movement("linky");
        Harness harness = TestContexts.builder().check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Position::new)).build();
        PlayerData player = harness.newPlayer();
        find(player, descriptor, ProbeChecks.Position.class).onUpdate = update -> {
            throw new NoClassDefFoundError("missing");
        };
        player.checks().dispatchPosition(player.movementUpdate());
        assertEquals(1, harness.errors.totalErrors());
    }

    @Test
    @DisplayName("settings are re-applied after registry.applySettings, including the onSettingsApplied callback")
    void settingsReapplied() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Position check = find(player, POS_A, ProbeChecks.Position.class);
        assertEquals(1, check.appliedSettings.size(), "initial sync at creation");
        assertEquals(POS_A.defaults().bufferLimit(), check.appliedSettings.get(0).bufferLimit());

        player.checks().dispatchPosition(player.movementUpdate());
        assertEquals(1, check.appliedSettings.size(), "no reload, no re-apply");

        harness.registry.applySettings(d -> CheckSettings.of(d.defaults().withBufferMax(100.0).withBufferLimit(42.0), List.of()));
        assertEquals(1, check.appliedSettings.size(), "applied lazily on the owner thread, not from the reloading thread");
        player.checks().dispatchPosition(player.movementUpdate());
        assertEquals(2, check.appliedSettings.size());
        assertEquals(42.0, check.settings().bufferLimit());
        assertEquals(42.0, check.appliedSettings.get(1).bufferLimit());

        player.checks().syncSettings();
        assertEquals(2, check.appliedSettings.size(), "sync is idempotent for the same epoch");
    }

    @Test
    @DisplayName("every dispatch kind syncs settings first")
    void everyDispatchSyncs() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Probe plain = find(player, PLAIN, ProbeChecks.Probe.class);
        int expected = 1;
        Runnable[] dispatches = {
                () -> player.checks().dispatchPosition(player.movementUpdate()),
                () -> player.checks().dispatchRotation(player.lookChange()),
                () -> player.checks().dispatchClientTick(),
                () -> player.checks().dispatchReceive(null),
                () -> player.checks().dispatchSend(null),
                () -> player.checks().dispatchInteraction(new BlockInteraction())
        };
        for (Runnable dispatch : dispatches) {
            harness.registry.applySettings(d -> CheckSettings.of(d.defaults(), List.of()));
            dispatch.run();
            expected++;
            assertEquals(expected, plain.appliedSettings.size());
        }
    }

    @Test
    @DisplayName("an exception thrown by onSettingsApplied is reported and does not block other checks")
    void brokenSettingsCallback() {
        CheckDescriptor broken = Descriptors.movement("broken");
        CheckDescriptor healthy = Descriptors.movement("healthy");
        Harness harness = TestContexts.builder()
                .check(broken, ProbeChecks.factory(broken, ProbeChecks.BrokenSettings::new))
                .check(healthy, ProbeChecks.factory(healthy, ProbeChecks.Probe::new))
                .build();
        PlayerData player = harness.newPlayer();
        assertEquals(1, harness.errors.totalErrors());
        assertEquals(1, find(player, healthy, ProbeChecks.Probe.class).appliedSettings.size());

        harness.registry.applySettings(d -> CheckSettings.of(d.defaults().withBufferMax(20).withBufferLimit(9), List.of()));
        player.checks().syncSettings();
        assertEquals(2, harness.errors.totalErrors());
        assertEquals(9.0, find(player, broken, ProbeChecks.Probe.class).settings().bufferLimit(),
                "settings are stored even if the callback throws");
        assertEquals(2, find(player, healthy, ProbeChecks.Probe.class).appliedSettings.size());
    }

    @Test
    @DisplayName("resetAll clears buffers and calls onReset on every check")
    void resetAll() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Probe a = find(player, POS_A, ProbeChecks.Probe.class);
        ProbeChecks.Probe b = find(player, PLAIN, ProbeChecks.Probe.class);
        a.raise(3);
        b.raise(1);
        player.checks().resetAll();
        assertEquals(0.0, a.buffer());
        assertEquals(0.0, b.buffer());
        assertEquals(1, a.resets);
        assertEquals(1, b.resets);
    }

    @Test
    @DisplayName("resetAll keeps violation level and count")
    void resetKeepsViolations() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        ProbeChecks.Probe a = find(player, POS_A, ProbeChecks.Probe.class);
        a.flag(2.0, "x");
        player.checks().resetAll();
        assertEquals(2.0, a.violationLevel(), 1e-9);
        assertEquals(1, a.violationCount());
    }

    @Test
    @DisplayName("profiler records one call per executed check when enabled and nothing when disabled")
    void profilerIntegration() {
        Harness harness = fullBuilder().build();
        PlayerData player = harness.newPlayer();
        CheckProfiler profiler = harness.profiler;
        assertTrue(profiler.enabled());
        player.checks().dispatchPosition(player.movementUpdate());
        List<CheckProfiler.CheckSample> samples = profiler.report().checks();
        assertEquals(2, samples.size(), "pos-a and both are enabled position checks; pos-b is disabled");
        for (CheckProfiler.CheckSample sample : samples) {
            assertEquals(1, sample.calls());
            assertTrue(sample.totalNanos() >= 0);
        }
        profiler.reset();
        profiler.setEnabled(false);
        player.checks().dispatchPosition(player.movementUpdate());
        assertTrue(profiler.report().checks().isEmpty());
        assertFalse(profiler.enabled());
    }
}
