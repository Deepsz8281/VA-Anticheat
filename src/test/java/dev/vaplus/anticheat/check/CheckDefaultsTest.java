package dev.vaplus.anticheat.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckDefaults and CheckCategory")
class CheckDefaultsTest {

    @Test
    @DisplayName("movement defaults enable setbacks from VL 1 and use the movement profile")
    void movementDefaults() {
        assertEquals(new CheckDefaults(true, 2.0, 0.05, 8.0, 6.0, 1.0, 1.0, "movement"),
                CheckDefaults.of(CheckCategory.MOVEMENT));
    }

    @Test
    @DisplayName("combat defaults")
    void combatDefaults() {
        assertEquals(new CheckDefaults(true, 3.0, 0.1, 10.0, 3.0, 1.0, -1.0, "combat"),
                CheckDefaults.of(CheckCategory.COMBAT));
    }

    @Test
    @DisplayName("packet defaults have no buffer (limit 0, unbounded)")
    void packetDefaults() {
        assertEquals(new CheckDefaults(true, 0.0, 0.0, 0.0, 2.0, 1.0, -1.0, "packet"),
                CheckDefaults.of(CheckCategory.PACKET));
    }

    @Test
    @DisplayName("world and inventory defaults")
    void worldAndInventoryDefaults() {
        assertEquals(new CheckDefaults(true, 2.0, 0.1, 8.0, 4.0, 1.0, -1.0, "world"),
                CheckDefaults.of(CheckCategory.WORLD));
        assertEquals(new CheckDefaults(true, 2.0, 0.1, 8.0, 4.0, 1.0, -1.0, "inventory"),
                CheckDefaults.of(CheckCategory.INVENTORY));
    }

    @ParameterizedTest
    @EnumSource(CheckCategory.class)
    @DisplayName("default profile name equals the category key; only movement may set back")
    void profileAndSetbackPerCategory(CheckCategory category) {
        CheckDefaults defaults = CheckDefaults.of(category);
        assertEquals(category.key(), defaults.profile());
        assertTrue(defaults.enabled());
        assertEquals(category == CheckCategory.MOVEMENT, defaults.setbackVl() >= 0);
        assertEquals(category == CheckCategory.MOVEMENT, category.setbackCapable());
        assertTrue(defaults.bufferMax() == 0 || defaults.bufferMax() >= defaults.bufferLimit(),
                "default buffer cap must not make the check unable to flag");
    }

    @Test
    @DisplayName("each wither changes exactly one component")
    void withers() {
        CheckDefaults base = CheckDefaults.of(CheckCategory.WORLD);
        assertEquals(new CheckDefaults(false, 2.0, 0.1, 8.0, 4.0, 1.0, -1.0, "world"), base.withEnabled(false));
        assertEquals(new CheckDefaults(true, 5.0, 0.1, 8.0, 4.0, 1.0, -1.0, "world"), base.withBufferLimit(5.0));
        assertEquals(new CheckDefaults(true, 2.0, 0.5, 8.0, 4.0, 1.0, -1.0, "world"), base.withBufferDecay(0.5));
        assertEquals(new CheckDefaults(true, 2.0, 0.1, 20.0, 4.0, 1.0, -1.0, "world"), base.withBufferMax(20.0));
        assertEquals(new CheckDefaults(true, 2.0, 0.1, 8.0, 9.0, 1.0, -1.0, "world"), base.withVlDecayPerMinute(9.0));
        assertEquals(new CheckDefaults(true, 2.0, 0.1, 8.0, 4.0, 7.0, -1.0, "world"), base.withAlertVl(7.0));
        assertEquals(new CheckDefaults(true, 2.0, 0.1, 8.0, 4.0, 1.0, 3.0, "world"), base.withSetbackVl(3.0));
        assertEquals(new CheckDefaults(true, 2.0, 0.1, 8.0, 4.0, 1.0, -1.0, "custom"), base.withProfile("custom"));
        assertEquals(CheckDefaults.of(CheckCategory.WORLD), base, "withers must not mutate the receiver");
    }

    @Test
    @DisplayName("negative buffer, decay or alert values are rejected, negative setback is allowed")
    void validation() {
        CheckDefaults base = CheckDefaults.of(CheckCategory.MOVEMENT);
        assertThrows(IllegalArgumentException.class, () -> base.withBufferLimit(-0.1));
        assertThrows(IllegalArgumentException.class, () -> base.withBufferDecay(-0.1));
        assertThrows(IllegalArgumentException.class, () -> base.withBufferMax(-1));
        assertThrows(IllegalArgumentException.class, () -> base.withVlDecayPerMinute(-1));
        assertThrows(IllegalArgumentException.class, () -> base.withAlertVl(-1));
        assertEquals(-5.0, base.withSetbackVl(-5.0).setbackVl());
        assertThrows(NullPointerException.class, () -> base.withProfile(null));
    }

    @Test
    @DisplayName("CheckCategory.fromKey is case/whitespace insensitive and null-safe")
    void categoryFromKey() {
        assertEquals(CheckCategory.MOVEMENT, CheckCategory.fromKey("movement"));
        assertEquals(CheckCategory.COMBAT, CheckCategory.fromKey("  COMBAT "));
        assertEquals(CheckCategory.INVENTORY, CheckCategory.fromKey("Inventory"));
        assertNull(CheckCategory.fromKey("nonsense"));
        assertNull(CheckCategory.fromKey(""));
        assertNull(CheckCategory.fromKey(null));
        for (CheckCategory category : CheckCategory.values()) {
            assertEquals(category, CheckCategory.fromKey(category.key()));
            assertFalse(category.key().isEmpty());
        }
    }
}
