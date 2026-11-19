package dev.vaplus.anticheat.violation;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.violation.CheckMetrics.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shadow mode and per-check metrics")
class ShadowModeAndMetricsTest {

    private record Fixture(Harness harness, PlayerData player, ProbeChecks.Probe check) {
    }

    private static Fixture fixture(String id, boolean shadow) {
        CheckDescriptor descriptor = Descriptors.of(id, CheckCategory.MOVEMENT,
                d -> d.withVlDecayPerMinute(0).withSetbackVl(1.0).withAlertVl(1.0));
        Harness harness = TestContexts.builder()
                .check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new))
                .build();
        PlayerData player = harness.newPlayer();
        CheckOptions options = shadow ? new CheckOptions(Map.of("log-only", true)) : CheckOptions.EMPTY;
        CheckSettings settings = new CheckSettings(true, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, List.of(), options);
        harness.applySettings(player, d -> settings);
        return new Fixture(harness, player, player.checks().get(ProbeChecks.Probe.class));
    }

    @Test
    @DisplayName("a normal violation reaches the setback handler")
    void baselineSetback() {
        Fixture fixture = fixture("probe-shadow", false);
        fixture.check().flag("x");
        assertEquals(1, fixture.harness().setbacks.requests().size());
    }

    @Test
    @DisplayName("log-only still alerts and logs, but never sets back or punishes")
    void shadowSuppressesActions() {
        Fixture fixture = fixture("probe-shadow", true);
        fixture.check().flag("x");

        assertTrue(fixture.harness().setbacks.requests().isEmpty(), "no setback in shadow mode");
        assertTrue(fixture.harness().punishments.executions().isEmpty()
                && fixture.harness().punishments.kicks().isEmpty(), "no punishment in shadow mode");
        assertEquals(1, fixture.harness().alerts.alerts().size(), "alerts are still produced");
        String details = fixture.harness().alerts.alerts().get(0).details();
        assertTrue(details.startsWith("[shadow] "), details);
        assertEquals(1, fixture.harness().violationLog.query(fixture.player().uuid(), 0, 10).join().size(),
                "the log still records the flag");
    }

    @Test
    @DisplayName("metrics count flags and distinct players per check and reset")
    void metrics() {
        Fixture fixture = fixture("probe-metrics", false);
        PlayerData second = fixture.harness().newPlayer();
        fixture.check().flag("a");
        fixture.check().flag("b");
        second.checks().get(ProbeChecks.Probe.class).flag("c");

        List<Entry> entries = fixture.harness().processor.metrics().entries(fixture.harness().registry);
        assertEquals(1, entries.size());
        assertEquals("probe-metrics", entries.get(0).checkId());
        assertEquals(3, entries.get(0).flags());
        assertEquals(2, entries.get(0).players());
        assertEquals(1.5, entries.get(0).flagsPerPlayer(), 1.0E-9);

        fixture.harness().processor.metrics().reset();
        assertTrue(fixture.harness().processor.metrics().entries(fixture.harness().registry).isEmpty());
    }
}
