package dev.vaplus.anticheat.world.mirror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BlockKey (namespaced block identifier)")
class BlockKeyTest {

    @Test
    @DisplayName("valid keys keep their exact value; toString is the value")
    void validKeys() {
        BlockKey key = new BlockKey("minecraft:stone");
        assertEquals("minecraft:stone", key.value());
        assertEquals("minecraft:stone", key.toString());

        // dot, dash, slash and underscore segments are legal per the identifier pattern
        assertEquals("vaplus:unknown", BlockKey.UNKNOWN.value());
        assertEquals("minecraft:some/path.with-dash_underscore", new BlockKey("minecraft:some/path.with-dash_underscore").value());
    }

    @Test
    @DisplayName("keys without namespace, with uppercase or with illegal characters are rejected")
    void invalidKeys() {
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("stone"),
                "missing namespace");
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("minecraft:"),
                "empty path");
        assertThrows(IllegalArgumentException.class, () -> new BlockKey(":stone"),
                "empty namespace");
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("Minecraft:stone"),
                "uppercase namespace");
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("minecraft:STONE"),
                "uppercase path");
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("minecraft:ston e"),
                "space");
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("minecraft:stone!"),
                "exclamation mark");
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("minecraft:"),
                "empty path again");
        assertThrows(NullPointerException.class, () -> new BlockKey(null),
                "null value");
    }

    @Test
    @DisplayName("the mirror sentinel is not a minecraft key and equality is value based")
    void sentinelAndEquality() {
        assertTrue(BlockKey.UNKNOWN.value().startsWith("vaplus:"),
                "sentinel must never collide with a claimed minecraft registry entry");
        assertEquals(new BlockKey("minecraft:dirt"), new BlockKey("minecraft:dirt"));
        assertEquals(new BlockKey("minecraft:dirt").hashCode(), new BlockKey("minecraft:dirt").hashCode());
        assertNotEquals(new BlockKey("minecraft:dirt"), new BlockKey("vaplus:dirt"));
    }
}
