package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.enchantment.Enchantment;
import com.github.retrooper.packetevents.protocol.item.enchantment.type.EnchantmentTypes;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import dev.vaplus.anticheat.config.SettingsParser;
import dev.vaplus.anticheat.testutil.Configs;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthIndicatorSpooferTest {

    private static final ServerVersion SERVER = ServerVersion.V_1_21_5;
    private static final ClientVersion VERSION = SERVER.toClientVersion();
    private static final HealthIndicatorSpoofer.Indexes INDEXES = HealthIndicatorSpoofer.Indexes.forServer(SERVER);

    @BeforeAll
    static void installPacketEvents() {
        FakePacketEvents.install(SERVER);
    }

    @AfterAll
    static void uninstallPacketEvents() {
        FakePacketEvents.uninstall();
    }

    private static HealthIndicatorSettings fixed(float value) {
        return new HealthIndicatorSettings(true, false, true, value, false, true, true, true, true, true, true,
                true, true, true, false);
    }

    private static List<EntityData<?>> entries(EntityData<?>... data) {
        return new ArrayList<>(List.of(data));
    }

    @Test
    void indexesFollowTheAvatarShiftOf1219() {
        assertEquals(new HealthIndicatorSpoofer.Indexes(9, 15, 16), HealthIndicatorSpoofer.Indexes.forServer(ServerVersion.V_1_21_8));
        assertEquals(new HealthIndicatorSpoofer.Indexes(9, 17, 18), HealthIndicatorSpoofer.Indexes.forServer(ServerVersion.V_1_21_9));
        assertEquals(new HealthIndicatorSpoofer.Indexes(9, 17, 18), HealthIndicatorSpoofer.Indexes.forServer(ServerVersion.V_26_1));
    }

    @Test
    void playerHealthAbsorptionScoreAndAirAreHidden() {
        List<EntityData<?>> data = entries(
                new EntityData<>(1, EntityDataTypes.INT, 42),
                new EntityData<>(9, EntityDataTypes.FLOAT, 7.5f),
                new EntityData<>(15, EntityDataTypes.FLOAT, 4.0f),
                new EntityData<>(16, EntityDataTypes.INT, 1234));

        assertTrue(HealthIndicatorSpoofer.spoofMetadata(data, EntityTypes.PLAYER, INDEXES, fixed(20.0f)));

        assertEquals(HealthIndicatorSpoofer.FULL_AIR, data.get(0).getValue());
        assertEquals(20.0f, data.get(1).getValue());
        assertEquals(0.0f, data.get(2).getValue());
        assertEquals(0, data.get(3).getValue());
    }

    @Test
    void deadEntitiesKeepZeroHealth() {
        List<EntityData<?>> data = entries(new EntityData<>(9, EntityDataTypes.FLOAT, 0.0f));
        assertFalse(HealthIndicatorSpoofer.spoofMetadata(data, EntityTypes.ZOMBIE, INDEXES, fixed(20.0f)));
        assertEquals(0.0f, data.get(0).getValue());
    }

    @Test
    void absorptionIndexOfMobsIsNotTouched() {
        // Index 15 of a zombie is not absorption; only players carry absorption/score.
        List<EntityData<?>> data = entries(new EntityData<>(15, EntityDataTypes.FLOAT, 3.0f));
        assertFalse(HealthIndicatorSpoofer.spoofMetadata(data, EntityTypes.ZOMBIE, INDEXES, fixed(20.0f)));
        assertEquals(3.0f, data.get(0).getValue());
    }

    @Test
    void unexpectedValueTypesAreLeftAlone() {
        // e.g. an index shift on an unknown future version: a byte at the absorption index.
        List<EntityData<?>> data = entries(new EntityData<>(15, EntityDataTypes.BYTE, (byte) 0x7f));
        assertFalse(HealthIndicatorSpoofer.spoofMetadata(data, EntityTypes.PLAYER, INDEXES, fixed(20.0f)));
        assertEquals((byte) 0x7f, data.get(0).getValue());
    }

    @Test
    void ironGolemsKeepTheirCrackStage() {
        assertEquals(100.0f, HealthIndicatorSpoofer.ironGolemHealth(80.0f));
        assertEquals(74.0f, HealthIndicatorSpoofer.ironGolemHealth(60.0f));
        assertEquals(49.0f, HealthIndicatorSpoofer.ironGolemHealth(30.0f));
        assertEquals(24.0f, HealthIndicatorSpoofer.ironGolemHealth(3.0f));

        List<EntityData<?>> data = entries(new EntityData<>(9, EntityDataTypes.FLOAT, 61.0f));
        assertTrue(HealthIndicatorSpoofer.spoofMetadata(data, EntityTypes.IRON_GOLEM, INDEXES, fixed(20.0f)));
        assertEquals(74.0f, data.get(0).getValue());
    }

    @Test
    void randomizedHealthStaysInRange() {
        HealthIndicatorSettings settings = HealthIndicatorSettings.defaults();
        for (int i = 0; i < 1_000; i++) {
            float value = settings.nextSpoofHealth();
            assertTrue(value >= 1.0f && value <= settings.spoofHealth(), "out of range: " + value);
        }
    }

    @Test
    void onlyLivingEntitiesAreRewrittenAndBossesAreExempt() {
        assertTrue(HealthIndicatorSpoofer.living(EntityTypes.PLAYER));
        assertTrue(HealthIndicatorSpoofer.living(EntityTypes.ZOMBIE));
        assertFalse(HealthIndicatorSpoofer.living(EntityTypes.INTERACTION));
        assertFalse(HealthIndicatorSpoofer.living(EntityTypes.ITEM));
        assertFalse(HealthIndicatorSpoofer.living(null));
        assertTrue(HealthIndicatorSpoofer.exempt(EntityTypes.WOLF));
        assertTrue(HealthIndicatorSpoofer.exempt(EntityTypes.WITHER));
        assertTrue(HealthIndicatorSpoofer.exempt(EntityTypes.ENDER_DRAGON));
        assertFalse(HealthIndicatorSpoofer.exempt(EntityTypes.PLAYER));
    }

    @Test
    void equipmentLosesAmountDamageAndEnchantmentsButKeepsGlint() {
        ItemStack sword = ItemStack.builder().type(ItemTypes.DIAMOND_SWORD).amount(3).build();
        sword.setDamageValue(500);
        sword.setEnchantments(List.of(new Enchantment(EnchantmentTypes.SHARPNESS, 5)), VERSION);
        assertFalse(sword.getEnchantments(VERSION).isEmpty());

        assertTrue(HealthIndicatorSpoofer.spoofItem(sword, VERSION, fixed(20.0f)));

        assertEquals(1, sword.getAmount());
        assertEquals(0, sword.getDamageValue());
        assertTrue(sword.getEnchantments(VERSION).isEmpty());
        assertEquals(Boolean.TRUE, sword.getComponentOr(ComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, null));
    }

    @Test
    void brokenElytraStaysBroken() {
        ItemStack elytra = ItemStack.builder().type(ItemTypes.ELYTRA).amount(1).build();
        int broken = elytra.getMaxDamage() - 1;
        elytra.setDamageValue(broken);
        assertFalse(HealthIndicatorSpoofer.spoofItem(elytra, VERSION, fixed(20.0f)));
        assertEquals(broken, elytra.getDamageValue());
    }

    @Test
    void untouchedItemsReportNoChange() {
        ItemStack stone = ItemStack.builder().type(ItemTypes.STONE).amount(1).build();
        assertFalse(HealthIndicatorSpoofer.spoofItem(stone, VERSION, fixed(20.0f)));
        assertFalse(HealthIndicatorSpoofer.spoofItem(ItemStack.EMPTY, VERSION, fixed(20.0f)));
    }

    @Test
    void bundledConfigParsesWithoutWarnings() {
        var snapshot = SettingsParser.parse(Configs.bundledConfig());
        assertEquals(HealthIndicatorSettings.defaults(), snapshot.healthIndicator());
        assertTrue(snapshot.warnings().stream().noneMatch(w -> w.startsWith("anti-health-indicator")),
                snapshot.warnings().toString());
    }

    @Test
    void invalidSpoofValueFallsBackWithWarning() {
        List<String> warnings = new ArrayList<>();
        HealthIndicatorSettings settings = HealthIndicatorSettings.parse(Configs.fromString(
                "health:\n  spoof-value: 0\nplayers-only: true\ngamemode: false\n"), warnings);
        assertEquals(HealthIndicatorSettings.DEFAULT_SPOOF_HEALTH, settings.spoofHealth());
        assertTrue(settings.playersOnly());
        assertFalse(settings.gamemode());
        assertEquals(1, warnings.size());
    }

    @Test
    void missingSectionUsesDefaults() {
        assertEquals(HealthIndicatorSettings.defaults(), HealthIndicatorSettings.parse(null, new ArrayList<>()));
    }
}
