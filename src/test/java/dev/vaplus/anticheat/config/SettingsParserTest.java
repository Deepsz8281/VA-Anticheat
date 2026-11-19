package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.testutil.Configs;
import dev.vaplus.anticheat.violation.ActionThreshold;
import dev.vaplus.anticheat.violation.ViolationAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SettingsParser")
class SettingsParserTest {

    private static boolean anyContains(List<String> warnings, String text) {
        return warnings.stream().anyMatch(warning -> warning.contains(text));
    }

    @Test
    @DisplayName("the bundled config.yml parses with zero warnings")
    void bundledConfigIsClean() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("the bundled config.yml matches the code defaults of GlobalSettings")
    void bundledConfigMatchesDefaults() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());
        assertEquals(GlobalSettings.defaults(), snapshot.global());
    }

    @Test
    @DisplayName("the bundled config defines a profile for every category plus alerts-only, and all categories are on")
    void bundledProfiles() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());
        for (CheckCategory category : CheckCategory.values()) {
            assertNotNull(snapshot.profile(category.key()), "profile " + category.key());
            assertTrue(snapshot.categoryEnabled(category));
        }
        assertEquals(List.of(), snapshot.profile("alerts-only"));
        assertEquals(List.of(
                        new ActionThreshold(40, 20, List.of(new ViolationAction.StaffNotice(null))),
                        new ActionThreshold(100, 0, List.of(new ViolationAction.Kick(null)))),
                snapshot.profile("movement"));
        assertNull(snapshot.profile("does-not-exist"));
        assertEquals(snapshot.profile("movement"), snapshot.profile("MOVEMENT"), "profile lookup is case-insensitive");
    }

    @Test
    @DisplayName("every category's default descriptor resolves against the bundled config without warnings")
    void bundledConfigResolvesEveryCategory() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());
        for (CheckCategory category : CheckCategory.values()) {
            CheckDescriptor descriptor = CheckDescriptor.builder("probe-" + category.key(), category).build();
            CheckSettings settings = snapshot.resolve(descriptor);
            assertTrue(settings.enabled());
            assertFalse(settings.actions().isEmpty(), category + " must get its category profile");
        }
        assertEquals(List.of(), snapshot.warnings());
        // The bundled checks: section only configures registered checks (movement and visibility).
        List<CheckDescriptor> configured = new java.util.ArrayList<>(List.of(
                dev.vaplus.anticheat.check.impl.movement.SpeedCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.movement.FlyCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.movement.NoFallCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.world.XrayCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.world.FreecamCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.world.EspCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.movement.PredictionCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.combat.ReachCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.combat.ThroughWallCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.combat.KillauraCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.combat.AutoclickerCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.combat.TriggerbotCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.combat.HitboxCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.packet.BadPacketsCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.packet.TimerCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.movement.SpiderCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.movement.NoSlowCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.inventory.ChestStealerCheck.DESCRIPTOR,
                dev.vaplus.anticheat.check.impl.world.FastPlaceCheck.DESCRIPTOR));
        configured.addAll(dev.vaplus.anticheat.testutil.ConfiguredChecks.ROUND_8);
        assertEquals(List.of(), snapshot.unknownCheckIds(configured));
    }

    @Test
    @DisplayName("an empty config yields the defaults plus a missing-profiles warning")
    void emptyConfig() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString(""));
        assertEquals(GlobalSettings.defaults(), snapshot.global());
        assertEquals(1, snapshot.warnings().size());
        assertTrue(anyContains(snapshot.warnings(), "punishment-profiles"));
    }

    @Test
    @DisplayName("global values are read from their paths")
    void globalValues() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                alerts:
                  enable-on-join: false
                  console: false
                  min-interval: 250
                latency:
                  heartbeat-interval: 100
                  ack-timeout: 20000
                setback:
                  enabled: false
                  cooldown: 500
                profiling:
                  enabled: false
                logging:
                  memory-per-player: 10
                  memory-max-players: 20
                punishment-profiles: {}
                """));
        assertEquals(new GlobalSettings(false, false, 250, 100, 20_000, false, 500, false, 10, 20, true,
                        GlobalSettings.defaults().setbackConfirmations(),
                        GlobalSettings.defaults().setbackWindowMillis()),
                snapshot.global());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("invalid global values produce warnings and fall back to defaults")
    void invalidGlobalValues() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                alerts:
                  min-interval: -5
                latency:
                  heartbeat-interval: 5
                  ack-timeout: -1
                setback:
                  cooldown: -250
                logging:
                  memory-per-player: 0
                  memory-max-players: -3
                punishment-profiles: {}
                """));
        GlobalSettings defaults = GlobalSettings.defaults();
        assertEquals(new GlobalSettings(defaults.alertsEnableOnJoin(), defaults.alertsToConsole(), defaults.alertMinIntervalMillis(),
                defaults.heartbeatIntervalMillis(), GlobalSettings.MIN_ACK_TIMEOUT_MILLIS, defaults.setbacksEnabled(),
                defaults.setbackCooldownMillis(), defaults.profilingEnabled(), defaults.logEntriesPerPlayer(),
                defaults.logMaxPlayers(), defaults.serverTickMarkers(), defaults.setbackConfirmations(),
                defaults.setbackWindowMillis()), snapshot.global(),
                "a too-small ack-timeout is clamped to 5000, everything else falls back");
        List<String> warnings = snapshot.warnings();
        assertEquals(6, warnings.size(), warnings.toString());
        assertTrue(anyContains(warnings, "alerts.min-interval"));
        assertTrue(anyContains(warnings, "latency.heartbeat-interval"));
        assertTrue(anyContains(warnings, "latency.ack-timeout"));
        assertTrue(anyContains(warnings, "setback.cooldown"));
        assertTrue(anyContains(warnings, "logging.memory-per-player"));
        assertTrue(anyContains(warnings, "logging.memory-max-players"));
    }

    @Test
    @DisplayName("ack-timeout outside 5000..120000 is clamped with a warning; 0 no longer disables the timeout")
    void ackTimeoutClamping() {
        String head = "punishment-profiles: {}\nlatency:\n  ack-timeout: ";
        ConfigSnapshot zero = SettingsParser.parse(Configs.fromString(head + "0\n"));
        assertEquals(5_000L, zero.global().ackTimeoutMillis());
        assertEquals(1, zero.warnings().size());
        assertTrue(anyContains(zero.warnings(), "latency.ack-timeout"));
        ConfigSnapshot huge = SettingsParser.parse(Configs.fromString(head + "999999\n"));
        assertEquals(120_000L, huge.global().ackTimeoutMillis());
        assertEquals(1, huge.warnings().size());
        ConfigSnapshot low = SettingsParser.parse(Configs.fromString(head + "4999\n"));
        assertEquals(5_000L, low.global().ackTimeoutMillis());
        for (long valid : new long[]{5_000L, 15_000L, 120_000L}) {
            ConfigSnapshot ok = SettingsParser.parse(Configs.fromString(head + valid + "\n"));
            assertEquals(valid, ok.global().ackTimeoutMillis());
            assertEquals(List.of(), ok.warnings());
        }
    }

    @Test
    @DisplayName("heartbeat exactly at the minimum is accepted")
    void heartbeatBoundary() {
        ConfigSnapshot ok = SettingsParser.parse(Configs.fromString("latency:\n  heartbeat-interval: 10\npunishment-profiles: {}\n"));
        assertEquals(10L, ok.global().heartbeatIntervalMillis());
        assertEquals(List.of(), ok.warnings());
        ConfigSnapshot bad = SettingsParser.parse(Configs.fromString("latency:\n  heartbeat-interval: 9\npunishment-profiles: {}\n"));
        assertEquals(50L, bad.global().heartbeatIntervalMillis());
        assertEquals(1, bad.warnings().size());
    }

    @Test
    @DisplayName("non-numeric global values do not crash the parser")
    void garbageGlobalValues() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                latency:
                  heartbeat-interval: fast
                  ack-timeout: never
                logging:
                  memory-per-player: lots
                punishment-profiles: {}
                """));
        assertEquals(GlobalSettings.defaults(), snapshot.global());
        assertEquals(3, snapshot.warnings().size(), "each non-numeric value must be reported: " + snapshot.warnings());
    }

    @Test
    @DisplayName("profile entries: thresholds are sorted by vl, single run string accepted, bad actions dropped with a warning")
    void profileEntries() {
        List<String> warnings = new ArrayList<>();
        List<ActionThreshold> thresholds = SettingsParser.parseThresholds(List.of(
                Map.of("vl", 50, "run", "kick"),
                Map.of("vl", 10, "repeat", 5, "run", List.of("notify", "bogus", "cmd: ban %player%"))
        ), "p", warnings);
        assertEquals(2, thresholds.size());
        assertEquals(10.0, thresholds.get(0).vl());
        assertEquals(5.0, thresholds.get(0).repeatEvery());
        assertEquals(List.of(new ViolationAction.StaffNotice(null), new ViolationAction.ConsoleCommand("ban %player%")),
                thresholds.get(0).actions());
        assertEquals(List.of(new ViolationAction.Kick(null)), thresholds.get(1).actions());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("p[1]"), warnings.get(0));
        assertTrue(warnings.get(0).contains("bogus"), warnings.get(0));
    }

    @Test
    @DisplayName("profile entries: invalid shapes are skipped with warnings")
    void invalidProfileEntries() {
        List<String> warnings = new ArrayList<>();
        List<Object> entries = new ArrayList<>();
        entries.add("just a string");
        entries.add(Map.of("run", "kick"));
        entries.add(Map.of("vl", -3, "run", "kick"));
        entries.add(Map.of("vl", 0, "run", "kick"));
        entries.add(Map.of("vl", "ten", "run", "kick"));
        entries.add(Map.of("vl", 5));
        entries.add(Map.of("vl", 5, "run", List.of("nonsense")));
        entries.add(Map.of("vl", 7, "repeat", -1, "run", "kick"));
        entries.add(Map.of("vl", 8, "repeat", "often", "run", "kick"));
        List<ActionThreshold> thresholds = SettingsParser.parseThresholds(entries, "x", warnings);

        assertEquals(2, thresholds.size(), "only the two entries with bad repeat survive, with repeat ignored");
        assertEquals(7.0, thresholds.get(0).vl());
        assertEquals(0.0, thresholds.get(0).repeatEvery());
        assertEquals(8.0, thresholds.get(1).vl());
        assertEquals(0.0, thresholds.get(1).repeatEvery());
        assertTrue(anyContains(warnings, "x[0]"));
        assertTrue(anyContains(warnings, "x[1].vl"));
        assertTrue(anyContains(warnings, "x[2].vl"));
        assertTrue(anyContains(warnings, "x[3].vl"));
        assertTrue(anyContains(warnings, "x[4].vl"));
        assertTrue(anyContains(warnings, "x[5] has no valid actions"));
        assertTrue(anyContains(warnings, "x[6]"));
        assertTrue(anyContains(warnings, "x[7].repeat"));
        assertTrue(anyContains(warnings, "x[8].repeat"));
    }

    @Test
    @DisplayName("null entry list yields no thresholds")
    void nullThresholds() {
        assertEquals(List.of(), SettingsParser.parseThresholds(null, "x", new ArrayList<>()));
    }

    @Test
    @DisplayName("profiles are parsed from YAML including repeat and cmd actions")
    void profilesFromYaml() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                punishment-profiles:
                  Strict:
                    - vl: 5
                      repeat: 5
                      run:
                        - "notify: %player% flagged %check%"
                        - "cmd: /ban %player% cheating"
                """));
        List<ActionThreshold> strict = snapshot.profile("strict");
        assertNotNull(strict, "profile names are lower-cased");
        assertEquals(1, strict.size());
        assertEquals(List.of(new ViolationAction.StaffNotice("%player% flagged %check%"),
                new ViolationAction.ConsoleCommand("ban %player% cheating")), strict.get(0).actions());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("category switch: enabled=false disables the category, unknown categories warn")
    void categories() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                punishment-profiles: {}
                categories:
                  movement:
                    enabled: false
                  COMBAT:
                    enabled: false
                  nonsense:
                    enabled: false
                """));
        assertFalse(snapshot.categoryEnabled(CheckCategory.MOVEMENT));
        assertTrue(snapshot.categoryEnabled(CheckCategory.PACKET));
        assertTrue(anyContains(snapshot.warnings(), "nonsense"));
        assertEquals(1, snapshot.warnings().size());
    }

    @Test
    @DisplayName("categories default to enabled when the key has no enabled flag")
    void categoryWithoutFlag() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                punishment-profiles: {}
                categories:
                  world: {}
                """));
        assertTrue(snapshot.categoryEnabled(CheckCategory.WORLD));
    }

    @Test
    @DisplayName("shorthand 'movement: false' disables the category; garbage values warn")
    void categoryShorthand() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                punishment-profiles: {}
                categories:
                  movement: false
                  combat: {enabled: false}
                  world: maybe
                """));
        assertFalse(snapshot.categoryEnabled(CheckCategory.MOVEMENT));
        assertFalse(snapshot.categoryEnabled(CheckCategory.COMBAT));
        assertTrue(snapshot.categoryEnabled(CheckCategory.WORLD));
        assertEquals(1, snapshot.warnings().size(), snapshot.warnings().toString());
    }
}
