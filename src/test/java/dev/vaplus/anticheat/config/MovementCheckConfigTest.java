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
import dev.vaplus.anticheat.testutil.Configs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled {@code checks.speed/fly/nofall} sections stay in sync with the code defaults. */
@DisplayName("Bundled config: movement check sections")
class MovementCheckConfigTest {

    private static final List<CheckDescriptor> MOVEMENT = List.of(
            SpeedCheck.DESCRIPTOR, FlyCheck.DESCRIPTOR, NoFallCheck.DESCRIPTOR);

    @Test
    @DisplayName("speed, fly and nofall resolve without warnings to exactly their descriptor defaults")
    void sectionsMatchDescriptorDefaults() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.bundledConfig());
        for (CheckDescriptor descriptor : MOVEMENT) {
            CheckSettings settings = snapshot.resolve(descriptor);
            CheckDefaults defaults = descriptor.defaults();
            assertTrue(settings.enabled(), descriptor.id());
            assertEquals(defaults.bufferLimit(), settings.bufferLimit(), descriptor.id());
            assertEquals(defaults.bufferDecay(), settings.bufferDecay(), descriptor.id());
            assertEquals(defaults.bufferMax(), settings.bufferMax(), descriptor.id());
            assertEquals(defaults.vlDecayPerMinute(), settings.vlDecayPerMinute(), descriptor.id());
            assertEquals(defaults.alertVl(), settings.alertVl(), descriptor.id());
            assertEquals(defaults.setbackVl(), settings.setbackVl(), descriptor.id());
            assertEquals(snapshot.profile("movement"), settings.actions(), descriptor.id());
            assertFalse(settings.actions().isEmpty(), descriptor.id());
        }
        assertEquals(List.of(), snapshot.warnings());
        List<CheckDescriptor> configured = new ArrayList<>(MOVEMENT);
        configured.add(XrayCheck.DESCRIPTOR);
        configured.add(FreecamCheck.DESCRIPTOR);
        configured.add(EspCheck.DESCRIPTOR);
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

        CheckSettings speed = snapshot.resolve(SpeedCheck.DESCRIPTOR);
        assertEquals(Set.of("threshold", "ignore-slowdown"), speed.options().keys());
        assertEquals(SpeedCheck.DEFAULT_THRESHOLD, speed.options().getDouble("threshold", -1));
        assertTrue(speed.options().getBoolean("ignore-slowdown", false));

        CheckSettings fly = snapshot.resolve(FlyCheck.DESCRIPTOR);
        assertEquals(Set.of("upward-threshold", "check-downward", "downward-threshold"), fly.options().keys());
        assertEquals(FlyCheck.DEFAULT_UPWARD_THRESHOLD, fly.options().getDouble("upward-threshold", -1));
        assertEquals(FlyCheck.DEFAULT_DOWNWARD_THRESHOLD, fly.options().getDouble("downward-threshold", -1));
        assertTrue(fly.options().getBoolean("check-downward", false));

        CheckSettings nofall = snapshot.resolve(NoFallCheck.DESCRIPTOR);
        assertEquals(Set.of("max-offset", "flag-missing-ground"), nofall.options().keys());
        assertEquals(NoFallCheck.DEFAULT_MAX_OFFSET, nofall.options().getDouble("max-offset", -1));
        assertFalse(nofall.options().getBoolean("flag-missing-ground", true));
    }

    @Test
    @DisplayName("an options override from YAML reaches the resolved settings")
    void optionsOverride() {
        ConfigSnapshot snapshot = SettingsParser.parse(Configs.fromString("""
                punishment-profiles:
                  movement: []
                checks:
                  speed:
                    options:
                      threshold: 0.2
                      ignore-slowdown: false
                  fly:
                    options:
                      check-downward: false
                """));
        CheckSettings speed = snapshot.resolve(SpeedCheck.DESCRIPTOR);
        assertEquals(0.2, speed.options().getDouble("threshold", -1));
        assertFalse(speed.options().getBoolean("ignore-slowdown", true));
        assertFalse(snapshot.resolve(FlyCheck.DESCRIPTOR).options().getBoolean("check-downward", true));
        assertEquals(Set.of(), snapshot.resolve(NoFallCheck.DESCRIPTOR).options().keys());
    }
}
