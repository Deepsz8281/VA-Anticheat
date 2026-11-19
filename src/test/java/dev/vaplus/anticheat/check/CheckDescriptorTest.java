package dev.vaplus.anticheat.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckDescriptor")
class CheckDescriptorTest {

    @ParameterizedTest
    @ValueSource(strings = {"speed", "a", "fly-a", "reach-2", "x1-y2-z3", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuv"})
    @DisplayName("accepts well-formed ids")
    void acceptsValidIds(String id) {
        assertEquals(id, CheckDescriptor.builder(id, CheckCategory.MOVEMENT).build().id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Speed", "SPEED", "1speed", "-speed", "spe.ed", "spe_ed", "spe ed", "spéed",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvw"})
    @DisplayName("rejects ids that are not [a-z][a-z0-9-]{0,47}")
    void rejectsInvalidIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> CheckDescriptor.builder(id, CheckCategory.MOVEMENT));
    }

    @Test
    @DisplayName("48 characters is the maximum id length")
    void maximumLength() {
        String max = "a".repeat(48);
        assertEquals(48, CheckDescriptor.builder(max, CheckCategory.PACKET).build().id().length());
        assertThrows(IllegalArgumentException.class, () -> CheckDescriptor.builder(max + "a", CheckCategory.PACKET));
    }

    @Test
    @DisplayName("null id or category is rejected")
    void nullArguments() {
        assertThrows(NullPointerException.class, () -> CheckDescriptor.builder(null, CheckCategory.MOVEMENT));
        assertThrows(NullPointerException.class, () -> CheckDescriptor.builder("speed", null));
    }

    @Test
    @DisplayName("optional fields fall back to id, empty description, non-experimental and category defaults")
    void fallbacks() {
        CheckDescriptor descriptor = CheckDescriptor.builder("speed", CheckCategory.MOVEMENT).build();
        assertEquals("speed", descriptor.displayName());
        assertEquals("", descriptor.description());
        assertFalse(descriptor.experimental());
        assertEquals(CheckDefaults.of(CheckCategory.MOVEMENT), descriptor.defaults());
        assertEquals(CheckCategory.MOVEMENT, descriptor.category());
        assertEquals("speed", descriptor.toString());
    }

    @Test
    @DisplayName("builder values are kept")
    void builderValues() {
        CheckDefaults custom = CheckDefaults.of(CheckCategory.COMBAT).withBufferLimit(9.0);
        CheckDescriptor descriptor = CheckDescriptor.builder("reach", CheckCategory.COMBAT)
                .displayName("Reach")
                .description("Hits from too far away")
                .experimental(true)
                .defaults(custom)
                .build();
        assertEquals("Reach", descriptor.displayName());
        assertEquals("Hits from too far away", descriptor.description());
        assertTrue(descriptor.experimental());
        assertSame(custom, descriptor.defaults());
    }

    @Test
    @DisplayName("descriptors are compared by identity")
    void identityEquality() {
        CheckDescriptor a = CheckDescriptor.builder("speed", CheckCategory.MOVEMENT).build();
        CheckDescriptor b = CheckDescriptor.builder("speed", CheckCategory.MOVEMENT).build();
        assertNotEquals(a, b);
        assertEquals(a, a);
    }
}
