package dev.vaplus.anticheat.violation;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.RecordingPunishmentExecutor;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ViolationDispatcher")
class ViolationDispatcherTest {

    private static final ViolationAction KICK = new ViolationAction.Kick(null);
    private static final ViolationAction NOTIFY = new ViolationAction.StaffNotice(null);
    private static final ViolationAction BAN = new ViolationAction.ConsoleCommand("ban %player%");

    private record Fixture(Harness harness, PlayerData player, ProbeChecks.Probe check) {
    }

    private static Fixture withThresholds(ActionThreshold... thresholds) {
        CheckDescriptor descriptor = Descriptors.of("probe", CheckCategory.COMBAT, d -> d.withVlDecayPerMinute(0));
        Harness harness = TestContexts.builder()
                .check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new))
                .build();
        PlayerData player = harness.newPlayer(UUID.randomUUID(), "Alex");
        harness.applySettings(player, d -> CheckSettings.of(d.defaults(), List.of(thresholds)));
        return new Fixture(harness, player, player.checks().get(ProbeChecks.Probe.class));
    }

    @Test
    @DisplayName("a threshold without repeat fires exactly once when crossed")
    void firesOnce() {
        Fixture f = withThresholds(new ActionThreshold(3.0, 0.0, List.of(KICK)));
        f.check().flag(1.0, "1");
        f.check().flag(1.0, "2");
        assertTrue(f.harness().punishments.executions().isEmpty());
        f.check().flag(1.0, "3");
        assertEquals(1, f.harness().punishments.executions().size());
        for (int i = 0; i < 10; i++) {
            f.check().flag(1.0, "more");
        }
        assertEquals(1, f.harness().punishments.executions().size(), "no re-fire above the threshold");
    }

    @Test
    @DisplayName("execution carries action, player, check and the new level")
    void executionContent() {
        Fixture f = withThresholds(new ActionThreshold(2.0, 0.0, List.of(NOTIFY)));
        f.check().flag(1.5, "a");
        f.check().flag(1.5, "b");
        RecordingPunishmentExecutor.Execution first = f.harness().punishments.executions().get(0);
        assertSame(NOTIFY, first.action());
        assertSame(f.player(), first.player());
        assertSame(f.check(), first.check());
        assertEquals(3.0, first.violationLevel(), 1e-9);
    }

    @Test
    @DisplayName("repeat steps fire at vl, vl+repeat, vl+2*repeat ...")
    void repeatSteps() {
        Fixture f = withThresholds(new ActionThreshold(2.0, 2.0, List.of(NOTIFY)));
        for (int i = 1; i <= 7; i++) {
            f.check().flag(1.0, "v" + i);
        }
        List<Double> levels = f.harness().punishments.executions().stream()
                .map(execution -> execution.violationLevel()).toList();
        assertEquals(List.of(2.0, 4.0, 6.0), levels);
    }

    @Test
    @DisplayName("a single big violation fires a threshold once, not once per skipped step")
    void bigJump() {
        Fixture f = withThresholds(new ActionThreshold(2.0, 1.0, List.of(NOTIFY)));
        f.check().flag(50.0, "huge");
        assertEquals(1, f.harness().punishments.executions().size());
    }

    @Test
    @DisplayName("several thresholds and several actions fire in threshold order, actions in listed order")
    void multipleThresholds() {
        Fixture f = withThresholds(
                new ActionThreshold(1.0, 0.0, List.of(NOTIFY, BAN)),
                new ActionThreshold(3.0, 0.0, List.of(KICK)));
        f.check().flag(1.0, "1");
        assertEquals(List.of(NOTIFY, BAN), f.harness().punishments.actions());
        f.check().flag(1.0, "2");
        assertEquals(2, f.harness().punishments.actions().size());
        f.check().flag(1.0, "3");
        assertEquals(List.of(NOTIFY, BAN, KICK), f.harness().punishments.actions());
    }

    @Test
    @DisplayName("one violation can cross two thresholds at once")
    void crossTwo() {
        Fixture f = withThresholds(
                new ActionThreshold(1.0, 0.0, List.of(NOTIFY)),
                new ActionThreshold(2.0, 0.0, List.of(KICK)));
        f.check().flag(5.0, "x");
        assertEquals(List.of(NOTIFY, KICK), f.harness().punishments.actions());
    }

    @Test
    @DisplayName("no thresholds means alerts only")
    void noThresholds() {
        Fixture f = withThresholds();
        f.check().flag(100.0, "x");
        assertTrue(f.harness().punishments.executions().isEmpty());
        assertEquals(1, f.harness().alerts.alerts().size());
    }

    @Test
    @DisplayName("after the level decays below a threshold it fires again when re-crossed")
    void refiresAfterDecay() {
        CheckDescriptor descriptor = Descriptors.of("probe", CheckCategory.COMBAT, d -> d.withVlDecayPerMinute(60));
        Harness harness = TestContexts.builder()
                .check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new)).build();
        PlayerData player = harness.newPlayer();
        harness.applySettings(player, d -> CheckSettings.of(d.defaults(), List.of(new ActionThreshold(3.0, 0.0, List.of(KICK)))));
        ProbeChecks.Probe check = player.checks().get(ProbeChecks.Probe.class);
        check.flag(3.0, "first");
        assertEquals(1, harness.punishments.executions().size());
        harness.time.advanceMinutes(1);
        check.flag(3.0, "second");
        assertEquals(2, harness.punishments.executions().size());
    }

    @Test
    @DisplayName("log record carries player, check, level, details, ping, version and wall-clock time")
    void logRecordContent() {
        Fixture f = withThresholds();
        // Give the player a measured ping of 40 ms.
        f.player().acks().sendMarker();
        f.harness().time.advanceMillis(40);
        f.player().acks().onPong(f.harness().lastTransport().lastMarkerId());
        long expectedTimestamp = f.harness().time.currentTimeMillis();

        f.check().flag(2.5, "offset=0.42");

        List<ViolationRecord> records = f.harness().violationLog.query(f.player().uuid(), 0, 10).join();
        assertEquals(1, records.size());
        ViolationRecord record = records.get(0);
        assertEquals(f.player().uuid(), record.playerId());
        assertEquals("Alex", record.playerName());
        assertEquals("probe", record.checkId());
        assertEquals(CheckCategory.COMBAT, record.category());
        assertEquals(2.5, record.violationLevel(), 1e-9);
        assertEquals("offset=0.42", record.details());
        assertEquals(40L, record.pingMillis());
        assertEquals(ClientVersion.V_1_21_4.getReleaseName(), record.clientVersion());
        assertEquals(expectedTimestamp, record.timestampMillis());
    }

    @Test
    @DisplayName("ping is -1 in the log until the first marker round trip completed")
    void unknownPing() {
        Fixture f = withThresholds();
        f.check().flag("x");
        assertEquals(-1L, f.harness().violationLog.query(f.player().uuid(), 0, 1).join().get(0).pingMillis());
    }

    @Test
    @DisplayName("log is written in newest-first order with the level after each violation")
    void logOrdering() {
        Fixture f = withThresholds();
        f.check().flag(1.0, "first");
        f.check().flag(1.0, "second");
        List<ViolationRecord> records = f.harness().violationLog.query(f.player().uuid(), 0, 10).join();
        assertEquals(List.of("second", "first"), records.stream().map(ViolationRecord::details).toList());
        assertEquals(List.of(2.0, 1.0), records.stream().map(ViolationRecord::violationLevel).toList());
    }

    @Test
    @DisplayName("players are logged independently")
    void perPlayerLogs() {
        Fixture f = withThresholds();
        RecordingTransport transport = new RecordingTransport();
        PlayerData other = f.harness().newPlayer(UUID.randomUUID(), "Bob", transport);
        other.checks().get(ProbeChecks.Probe.class).flag("bob");
        f.check().flag("alex");
        assertEquals("bob", f.harness().violationLog.query(other.uuid(), 0, 5).join().get(0).details());
        assertEquals("alex", f.harness().violationLog.query(f.player().uuid(), 0, 5).join().get(0).details());
    }
}
