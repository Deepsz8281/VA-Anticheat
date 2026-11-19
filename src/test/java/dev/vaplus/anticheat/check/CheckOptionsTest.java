package dev.vaplus.anticheat.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckOptions")
class CheckOptionsTest {

    @Test
    @DisplayName("typed getters convert numbers and fall back on missing or mistyped values")
    void typedGetters() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("d", 1.5);
        raw.put("i", 7);
        raw.put("l", 123456789012L);
        raw.put("b", true);
        raw.put("s", "text");
        raw.put("n", 3);
        raw.put("nullValue", null);
        CheckOptions options = new CheckOptions(raw);

        assertEquals(1.5, options.getDouble("d", 0));
        assertEquals(3.0, options.getDouble("n", 0), "ints convert to double");
        assertEquals(7, options.getInt("i", 0));
        assertEquals(1, options.getInt("d", 0) , "doubles truncate to int");
        assertEquals(123456789012L, options.getLong("l", 0));
        assertTrue(options.getBoolean("b", false));
        assertEquals("text", options.getString("s", "x"));

        assertEquals(9.0, options.getDouble("s", 9.0), "string is not a number");
        assertTrue(options.getBoolean("i", true), "number is not a boolean");
        assertEquals("fallback", options.getString("missing", "fallback"));
        assertEquals("fallback", options.getString("nullValue", "fallback"));
        assertFalse(options.contains("nullValue"), "null values are dropped");
        assertTrue(options.contains("d"));
    }

    @Test
    @DisplayName("getStringList stringifies elements, skips nulls and returns an immutable list")
    void stringList() {
        java.util.ArrayList<Object> list = new java.util.ArrayList<>();
        list.add("a");
        list.add(2);
        list.add(null);
        CheckOptions options = new CheckOptions(Map.of("list", list, "notAList", "x"));
        assertEquals(List.of("a", "2"), options.getStringList("list"));
        assertEquals(List.of(), options.getStringList("notAList"));
        assertEquals(List.of(), options.getStringList("missing"));
        assertThrows(UnsupportedOperationException.class, () -> options.getStringList("list").add("z"));
    }

    @Test
    @DisplayName("options are a defensive copy and EMPTY is empty")
    void defensiveCopy() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("a", 1);
        CheckOptions options = new CheckOptions(raw);
        raw.put("b", 2);
        raw.remove("a");
        assertTrue(options.contains("a"));
        assertFalse(options.contains("b"));
        assertThrows(UnsupportedOperationException.class, () -> options.keys().add("c"));
        assertTrue(CheckOptions.EMPTY.keys().isEmpty());
    }

    @Test
    @DisplayName("CheckSettings copies action lists and substitutes EMPTY options for null")
    void checkSettingsNormalisation() {
        CheckSettings settings = new CheckSettings(true, 1, 0, 0, 0, 1, -1, new java.util.ArrayList<>(), null);
        assertEquals(CheckOptions.EMPTY, settings.options());
        assertThrows(UnsupportedOperationException.class, () -> settings.actions().add(null));
        assertFalse(settings.setbackEnabled());
        assertTrue(new CheckSettings(true, 1, 0, 0, 0, 1, 0, List.of(), null).setbackEnabled());
    }
}
