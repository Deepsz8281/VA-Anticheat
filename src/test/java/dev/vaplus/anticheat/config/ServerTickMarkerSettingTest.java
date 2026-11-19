package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.testutil.Configs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("latency.server-tick-markers setting (M15)")
class ServerTickMarkerSettingTest {

    @Test
    @DisplayName("defaults to enabled, also through the pre-M15 constructor")
    void defaults() {
        assertTrue(GlobalSettings.defaults().serverTickMarkers());
        assertTrue(new GlobalSettings(true, true, 0, 50, 15_000, true, 0, true, 1, 1).serverTickMarkers());
    }

    @Test
    @DisplayName("the bundled config.yml enables it explicitly")
    void bundled() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());
        assertTrue(Configs.bundledConfig().isBoolean("latency.server-tick-markers"));
        assertTrue(snapshot.global().serverTickMarkers());
    }

    @Test
    @DisplayName("can be disabled; a missing key keeps the default")
    void parse() {
        ConfigSnapshot disabled = SettingsParser.parse(Configs.fromString("""
                latency:
                  server-tick-markers: false
                punishment-profiles: {}
                """));
        assertFalse(disabled.global().serverTickMarkers());
        assertEquals(java.util.List.of(), disabled.warnings());

        ConfigSnapshot missing = SettingsParser.parse(Configs.fromString("""
                punishment-profiles: {}
                """));
        assertTrue(missing.global().serverTickMarkers());
    }
}
