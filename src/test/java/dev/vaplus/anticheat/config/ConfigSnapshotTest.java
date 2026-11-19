package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.testutil.Configs;
import dev.vaplus.anticheat.violation.ActionThreshold;
import dev.vaplus.anticheat.violation.ViolationAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigSnapshot")
class ConfigSnapshotTest {

    private static final String PROFILES = """
            punishment-profiles:
              movement:
                - vl: 10
                  run: [kick]
              combat:
                - vl: 5
                  run: [notify]
              packet: []
              world: []
              inventory: []
              custom:
                - vl: 3
                  repeat: 3
                  run: ["cmd: warn %player%"]
            """;

    private static ConfigSnapshot snapshot(String body) {
        return SettingsParser.parse(Configs.fromString(PROFILES + body));
    }

    private static CheckDescriptor movement(String id) {
        return CheckDescriptor.builder(id, CheckCategory.MOVEMENT).build();
    }

    private static boolean anyContains(List<String> warnings, String text) {
        return warnings.stream().anyMatch(warning -> warning.contains(text));
    }

    @Test
    @DisplayName("a check without a config section gets its descriptor defaults and category profile")
    void defaultsWithoutSection() {
        ConfigSnapshot snapshot = snapshot("checks: {}\n");
        CheckDescriptor descriptor = movement("speed");
        CheckSettings settings = snapshot.resolve(descriptor);
        assertEquals(CheckSettings.of(descriptor.defaults(), snapshot.profile("movement")), settings);
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("a missing checks section is handled like an empty one")
    void noChecksSection() {
        ConfigSnapshot snapshot = snapshot("");
        assertEquals(movement("speed").defaults().bufferLimit(), snapshot.resolve(movement("speed")).bufferLimit());
        assertEquals(List.of(), snapshot.unknownCheckIds(List.of(movement("speed"))));
    }

    @Test
    @DisplayName("config values override defaults field by field")
    void overrides() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    enabled: false
                    buffer:
                      limit: 5.5
                      decay: 0.25
                      max: 12
                    vl-decay-per-minute: 1.5
                    alert-vl: 4
                    setback-vl: -1
                    options:
                      tolerance: 0.003
                      strict: true
                      modes: [a, b]
                """);
        CheckSettings settings = snapshot.resolve(movement("speed"));
        assertFalse(settings.enabled());
        assertEquals(5.5, settings.bufferLimit());
        assertEquals(0.25, settings.bufferDecay());
        assertEquals(12.0, settings.bufferMax());
        assertEquals(1.5, settings.vlDecayPerMinute());
        assertEquals(4.0, settings.alertVl());
        assertEquals(-1.0, settings.setbackVl());
        assertFalse(settings.setbackEnabled());
        assertEquals(0.003, settings.options().getDouble("tolerance", 0));
        assertTrue(settings.options().getBoolean("strict", false));
        assertEquals(List.of("a", "b"), settings.options().getStringList("modes"));
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("keys missing from a partial section fall back to the defaults")
    void partialSection() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    buffer:
                      limit: 5
                """);
        CheckDescriptor descriptor = movement("speed");
        CheckSettings settings = snapshot.resolve(descriptor);
        assertEquals(5.0, settings.bufferLimit());
        assertEquals(descriptor.defaults().bufferDecay(), settings.bufferDecay());
        assertEquals(descriptor.defaults().bufferMax(), settings.bufferMax());
        assertEquals(descriptor.defaults().alertVl(), settings.alertVl());
        assertTrue(settings.enabled());
    }

    @Test
    @DisplayName("non-numeric, negative and non-finite numbers warn and fall back")
    void invalidNumbers() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    buffer:
                      limit: lots
                      decay: -1
                      max: .inf
                    vl-decay-per-minute: .nan
                    alert-vl: -5
                    setback-vl: fast
                """);
        CheckDescriptor descriptor = movement("speed");
        CheckSettings settings = snapshot.resolve(descriptor);
        assertEquals(descriptor.defaults().bufferLimit(), settings.bufferLimit());
        assertEquals(descriptor.defaults().bufferDecay(), settings.bufferDecay());
        assertEquals(descriptor.defaults().bufferMax(), settings.bufferMax());
        assertEquals(descriptor.defaults().vlDecayPerMinute(), settings.vlDecayPerMinute());
        assertEquals(descriptor.defaults().alertVl(), settings.alertVl());
        assertEquals(descriptor.defaults().setbackVl(), settings.setbackVl());
        List<String> warnings = snapshot.warnings();
        assertEquals(6, warnings.size(), warnings.toString());
        assertTrue(anyContains(warnings, "checks.speed.buffer.limit"));
        assertTrue(anyContains(warnings, "checks.speed.buffer.decay"));
        assertTrue(anyContains(warnings, "checks.speed.buffer.max"));
        assertTrue(anyContains(warnings, "checks.speed.vl-decay-per-minute"));
        assertTrue(anyContains(warnings, "checks.speed.alert-vl"));
        assertTrue(anyContains(warnings, "checks.speed.setback-vl"));
    }

    @Test
    @DisplayName("a negative setback-vl is valid (disables setbacks) but a negative buffer is not")
    void negativeSetbackAllowed() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    setback-vl: -7
                """);
        assertEquals(-7.0, snapshot.resolve(movement("speed")).setbackVl());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("buffer.max below buffer.limit warns and derives a cap above the limit")
    void maxBelowLimit() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    buffer:
                      limit: 5
                      max: 3
                """);
        CheckSettings settings = snapshot.resolve(movement("speed"));
        assertEquals(11.0, settings.bufferMax(), "limit 5 + default head-room 6");
        assertEquals(5.0, settings.bufferLimit());
        assertEquals(1, snapshot.warnings().size());
        assertTrue(anyContains(snapshot.warnings(), "buffer.max"));
    }

    @Test
    @DisplayName("raising only buffer.limit above the code-default max warns and silently makes the buffer unbounded")
    void limitAboveDefaultMax() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    buffer:
                      limit: 9
                """);
        CheckSettings settings = snapshot.resolve(movement("speed"));
        assertEquals(9.0, settings.bufferLimit());
        assertEquals(15.0, settings.bufferMax(), "cap keeps the default head-room (8 - 2) above the raised limit");
        assertEquals(List.of(), snapshot.warnings(), "max was not configured explicitly, so nothing to warn about");
    }

    @Test
    @DisplayName("an explicitly configured buffer.max below buffer.limit warns and keeps a cap")
    void explicitMaxBelowLimit() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    buffer:
                      limit: 9
                      max: 4
                """);
        assertEquals(15.0, snapshot.resolve(movement("speed")).bufferMax());
        assertEquals(1, snapshot.warnings().size());
    }

    @Test
    @DisplayName("buffer.max equal to buffer.limit could never flag: a cap with the default head-room is derived and the admin is warned")
    void maxEqualsLimit() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    buffer:
                      limit: 3
                      max: 3
                """);
        CheckSettings settings = snapshot.resolve(movement("speed"));
        assertEquals(9.0, settings.bufferMax(), "limit 3 + head-room 6 (movement default max 8 - limit 2)");
        assertTrue(settings.bufferMax() > settings.bufferLimit());
        assertEquals(1, snapshot.warnings().size());
        assertTrue(anyContains(snapshot.warnings(), "buffer.max"));
    }

    @Test
    @DisplayName("a derived cap (explicit limit above the default max) produces no extra warning when buffer.max was not set")
    void derivedCapWithoutExplicitMax() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    buffer:
                      limit: 8
                """);
        CheckSettings settings = snapshot.resolve(movement("speed"));
        assertEquals(14.0, settings.bufferMax());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("category switch disables every check of the category regardless of the check's own flag")
    void categorySwitch() {
        ConfigSnapshot snapshot = snapshot("""
                categories:
                  movement:
                    enabled: false
                checks:
                  speed:
                    enabled: true
                """);
        assertFalse(snapshot.resolve(movement("speed")).enabled());
        assertFalse(snapshot.resolve(movement("other")).enabled());
        assertTrue(snapshot.resolve(CheckDescriptor.builder("reach", CheckCategory.COMBAT).build()).enabled());
    }

    @Test
    @DisplayName("a check disabled in code stays disabled unless the config enables it")
    void codeDisabledDefault() {
        CheckDescriptor descriptor = CheckDescriptor.builder("experiment", CheckCategory.MOVEMENT)
                .defaults(dev.vaplus.anticheat.check.CheckDefaults.of(CheckCategory.MOVEMENT).withEnabled(false))
                .build();
        assertFalse(snapshot("").resolve(descriptor).enabled());
        assertTrue(snapshot("checks:\n  experiment:\n    enabled: true\n").resolve(descriptor).enabled());
    }

    @Test
    @DisplayName("inline actions list replaces the profile")
    void inlineActions() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    profile: combat
                    actions:
                      - vl: 2
                        run: ["cmd: kickall"]
                """);
        assertEquals(List.of(new ActionThreshold(2, 0, List.of(new ViolationAction.ConsoleCommand("kickall")))),
                snapshot.resolve(movement("speed")).actions());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("an explicit empty inline list means alerts only")
    void inlineEmptyActions() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    actions: []
                """);
        assertEquals(List.of(), snapshot.resolve(movement("speed")).actions());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("inline action errors carry the check path in the warning")
    void inlineActionWarnings() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    actions:
                      - vl: 2
                        run: ["explode"]
                """);
        assertEquals(List.of(), snapshot.resolve(movement("speed")).actions());
        assertTrue(anyContains(snapshot.warnings(), "checks.speed.actions[0]"), snapshot.warnings().toString());
    }

    @Test
    @DisplayName("a named profile is used (case-insensitively) instead of the category default")
    void namedProfile() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    profile: CUSTOM
                """);
        assertEquals(List.of(new ActionThreshold(3, 3, List.of(new ViolationAction.ConsoleCommand("warn %player%")))),
                snapshot.resolve(movement("speed")).actions());
        assertEquals(List.of(), snapshot.warnings());
    }

    @Test
    @DisplayName("an unknown profile warns and results in alerts only")
    void unknownProfile() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed:
                    profile: does-not-exist
                """);
        assertEquals(List.of(), snapshot.resolve(movement("speed")).actions());
        assertEquals(1, snapshot.warnings().size());
        assertTrue(anyContains(snapshot.warnings(), "does-not-exist"));
        assertTrue(anyContains(snapshot.warnings(), "checks.speed"));
    }

    @Test
    @DisplayName("a missing default profile (no profiles section) warns per resolved check")
    void missingDefaultProfile() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("checks:\n  speed:\n    enabled: true\n"));
        assertEquals(List.of(), snapshot.resolve(movement("speed")).actions());
        assertTrue(anyContains(snapshot.warnings(), "unknown punishment profile 'movement'"));
    }

    @Test
    @DisplayName("unknownCheckIds lists config keys that match no registered descriptor")
    void unknownCheckIds() {
        ConfigSnapshot snapshot = snapshot("""
                checks:
                  speed: {}
                  spedd: {}
                  Fly: {}
                """);
        List<CheckDescriptor> known = List.of(movement("speed"), movement("fly"));
        assertEquals(List.of("spedd", "Fly"), snapshot.unknownCheckIds(known),
                "ids are case-sensitive in YAML, so a capitalised key is reported");
        assertEquals(List.of("speed", "spedd", "Fly"), snapshot.unknownCheckIds(List.of()));
    }

    @Test
    @DisplayName("warnings() returns a snapshot copy")
    void warningsCopy() {
        ConfigSnapshot snapshot = snapshot("checks:\n  speed:\n    alert-vl: bad\n");
        snapshot.resolve(movement("speed"));
        List<String> warnings = snapshot.warnings();
        assertEquals(1, warnings.size());
        try {
            warnings.add("x");
        } catch (UnsupportedOperationException expected) {
            // immutable as intended
        }
        assertEquals(1, snapshot.warnings().size());
    }

    @Test
    @DisplayName("GlobalSettings validates durations, heartbeat, ack timeout range and log limits")
    void globalSettingsValidation() {
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, -1, 50, 15_000, true, 0, true, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, 0, 9, 15_000, true, 0, true, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, 0, 50, 15_000, true, -1, true, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, 0, 50, 15_000, true, 0, true, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, 0, 50, 15_000, true, 0, true, 1, 0));
    }

    @Test
    @DisplayName("ack timeout must be within 5000..120000 ms, default 15000")
    void ackTimeoutRange() {
        assertEquals(15_000L, GlobalSettings.defaults().ackTimeoutMillis());
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, 0, 50, 0, true, 0, true, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, 0, 50, 4_999, true, 0, true, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalSettings(true, true, 0, 50, 120_001, true, 0, true, 1, 1));
        assertEquals(5_000L, new GlobalSettings(true, true, 0, 50, 5_000, true, 0, true, 1, 1).ackTimeoutMillis());
        assertEquals(120_000L, new GlobalSettings(true, true, 0, 50, 120_000, true, 0, true, 1, 1).ackTimeoutMillis());
    }
}
