package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.impl.movement.FlyCheck;
import dev.vaplus.anticheat.check.impl.movement.NoFallCheck;
import dev.vaplus.anticheat.check.impl.movement.SpeedCheck;
import dev.vaplus.anticheat.check.impl.world.EspCheck;
import dev.vaplus.anticheat.check.impl.world.FreecamCheck;
import dev.vaplus.anticheat.check.impl.world.XrayCheck;
import dev.vaplus.anticheat.obfuscation.XrayObfuscator;
import dev.vaplus.anticheat.testutil.Configs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled {@code checks.xray/freecam/esp} sections stay in sync with the code defaults. */
@DisplayName("Bundled config: visibility check sections")
class VisibilityCheckConfigTest {

    private static final List<CheckDescriptor> VISIBILITY = List.of(
            XrayCheck.DESCRIPTOR, FreecamCheck.DESCRIPTOR, EspCheck.DESCRIPTOR);

    @Test
    @DisplayName("xray, freecam and esp resolve without warnings to exactly their descriptor defaults")
    void sectionsMatchDescriptorDefaults() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());
        for (CheckDescriptor descriptor : VISIBILITY) {
            CheckSettings settings = snapshot.resolve(descriptor);
            CheckDefaults defaults = descriptor.defaults();
            String id = descriptor.id();
            assertTrue(settings.enabled(), id);
            assertEquals(defaults.bufferLimit(), settings.bufferLimit(), id);
            assertEquals(defaults.bufferDecay(), settings.bufferDecay(), id);
            assertEquals(defaults.bufferMax(), settings.bufferMax(), id);
            assertEquals(defaults.vlDecayPerMinute(), settings.vlDecayPerMinute(), id);
            assertEquals(defaults.alertVl(), settings.alertVl(), id);
            assertEquals(defaults.setbackVl(), settings.setbackVl(), id);
            assertEquals(snapshot.profile(defaults.profile()), settings.actions(), id);
            assertFalse(settings.actions().isEmpty(), id);
            assertFalse(settings.setbackEnabled(), id + " must not be able to set players back");
        }
        assertEquals(List.of(), snapshot.warnings());
        // every checks: section of the bundled config belongs to a registered check
        List<CheckDescriptor> configured = new ArrayList<>(VISIBILITY);
        configured.add(SpeedCheck.DESCRIPTOR);
        configured.add(FlyCheck.DESCRIPTOR);
        configured.add(NoFallCheck.DESCRIPTOR);
        configured.add(dev.vaplus.anticheat.check.impl.movement.PredictionCheck.DESCRIPTOR);
        configured.addAll(List.of(dev.vaplus.anticheat.check.impl.combat.CombatChecksModule.descriptors()));
        configured.add(dev.vaplus.anticheat.check.impl.packet.BadPacketsCheck.DESCRIPTOR);
        configured.add(dev.vaplus.anticheat.check.impl.packet.TimerCheck.DESCRIPTOR);
        configured.add(dev.vaplus.anticheat.check.impl.movement.SpiderCheck.DESCRIPTOR);
        configured.add(dev.vaplus.anticheat.check.impl.movement.NoSlowCheck.DESCRIPTOR);
        configured.add(dev.vaplus.anticheat.check.impl.inventory.ChestStealerCheck.DESCRIPTOR);
        configured.add(dev.vaplus.anticheat.check.impl.world.FastPlaceCheck.DESCRIPTOR);
        configured.addAll(dev.vaplus.anticheat.testutil.ConfiguredChecks.ROUND_8);
        assertEquals(List.of(), snapshot.unknownCheckIds(configured));
    }

    @Test
    @DisplayName("the options blocks carry the documented keys with the code-default values")
    void optionsMatchCodeDefaults() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());

        CheckSettings xray = snapshot.resolve(XrayCheck.DESCRIPTOR);
        assertEquals(Set.of("valuable-blocks", "only-hidden", "obfuscate", "fake-block", "max-per-chunk"),
                xray.options().keys());
        assertEquals(12, xray.options().getStringList("valuable-blocks").size());
        assertTrue(xray.options().getStringList("valuable-blocks").contains("diamond_ore"));
        assertTrue(xray.options().getBoolean("only-hidden", false));
        assertTrue(xray.options().getBoolean("obfuscate", false));
        assertEquals("stone", xray.options().getString("fake-block", ""));
        assertEquals(XrayObfuscator.DEFAULT_MAX_PER_CHUNK, xray.options().getInt("max-per-chunk", -1));

        CheckSettings freecam = snapshot.resolve(FreecamCheck.DESCRIPTOR);
        assertEquals(Set.of("max-reach", "max-angle", "ray-tolerance", "check-place", "check-dig"),
                freecam.options().keys());
        assertEquals(FreecamCheck.DEFAULT_MAX_REACH, freecam.options().getDouble("max-reach", -1));
        assertEquals(FreecamCheck.DEFAULT_MAX_ANGLE, freecam.options().getDouble("max-angle", -1));
        assertEquals(FreecamCheck.DEFAULT_RAY_TOLERANCE, freecam.options().getDouble("ray-tolerance", -1));
        assertTrue(freecam.options().getBoolean("check-place", false));
        assertTrue(freecam.options().getBoolean("check-dig", false));

        CheckSettings esp = snapshot.resolve(EspCheck.DESCRIPTOR);
        assertEquals(Set.of("max-distance", "max-miss-distance", "miss-per-block", "min-ticks",
                "min-target-movement", "interval"), esp.options().keys());
        assertEquals(EspCheck.DEFAULT_MAX_DISTANCE, esp.options().getDouble("max-distance", -1));
        assertEquals(EspCheck.DEFAULT_MAX_MISS_DISTANCE, esp.options().getDouble("max-miss-distance", -1));
        assertEquals(EspCheck.DEFAULT_MISS_PER_BLOCK, esp.options().getDouble("miss-per-block", -1));
        assertEquals(EspCheck.DEFAULT_MIN_TICKS, esp.options().getInt("min-ticks", -1));
        assertEquals(EspCheck.DEFAULT_MIN_TARGET_MOVEMENT, esp.options().getDouble("min-target-movement", -1));
        assertEquals(EspCheck.DEFAULT_INTERVAL, esp.options().getInt("interval", -1));
    }

    @Test
    @DisplayName("option overrides from YAML reach the resolved settings")
    void optionsOverride() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                punishment-profiles:
                  world: []
                  combat: []
                checks:
                  xray:
                    options:
                      only-hidden: false
                      valuable-blocks: [emerald_ore]
                  freecam:
                    options:
                      max-angle: 40.0
                  esp:
                    options:
                      min-ticks: 40
                """));
        CheckSettings xray = snapshot.resolve(XrayCheck.DESCRIPTOR);
        assertFalse(xray.options().getBoolean("only-hidden", true));
        assertEquals(List.of("emerald_ore"), xray.options().getStringList("valuable-blocks"));
        assertEquals(40.0, snapshot.resolve(FreecamCheck.DESCRIPTOR).options().getDouble("max-angle", -1));
        assertEquals(40, snapshot.resolve(EspCheck.DESCRIPTOR).options().getInt("min-ticks", -1));
        assertEquals(List.of(), snapshot.warnings());
    }
}
