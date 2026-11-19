package dev.vaplus.anticheat.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TextTemplates")
class TextTemplatesTest {

    private static String sub(String template, String... pairs) {
        return TextTemplates.substitute(template, pairs, false);
    }

    @Test
    @DisplayName("substitutes known placeholders, also repeated and adjacent ones")
    void basics() {
        assertEquals("Steve failed Speed", sub("%player% failed %check%", "player", "Steve", "check", "Speed"));
        assertEquals("a-a", sub("%x%-%x%", "x", "a"));
        assertEquals("12", sub("%a%%b%", "a", "1", "b", "2"));
    }

    @Test
    @DisplayName("unknown keys are left intact")
    void unknownKeys() {
        assertEquals("%unknown% stays", sub("%unknown% stays", "player", "Steve"));
        assertEquals("%a% Steve", sub("%a% %player%", "player", "Steve"));
        assertEquals("%%", sub("%%", "player", "Steve"));
        assertEquals("%player", sub("%player", "player", "Steve"));
    }

    @Test
    @DisplayName("literal percent signs such as 100% survive and do not swallow a later placeholder")
    void literalPercent() {
        assertEquals("100% sure", sub("100% sure"));
        assertEquals("100% done for Steve", sub("100% done for %player%", "player", "Steve"));
        assertEquals("ping 50% of Steve", sub("ping 50% of %player%", "player", "Steve"));
        assertEquals("Steve 100%", sub("%player% 100%", "player", "Steve"));
        assertEquals("Steve%", sub("%player%%", "player", "Steve"));
        assertEquals("x 12.5%", sub("%check% %share%%", "check", "x", "share", "12.5"));
    }

    @Test
    @DisplayName("values are not substituted recursively")
    void noRecursion() {
        assertEquals("%check%", sub("%player%", "player", "%check%", "check", "Speed"));
    }

    @Test
    @DisplayName("null template returns null, template without percent is returned as is")
    void nullAndPlain() {
        assertNull(TextTemplates.substitute(null, new String[0], false));
        String plain = "no placeholders";
        assertSame(plain, TextTemplates.substitute(plain, new String[]{"a", "b"}, false));
    }

    @Test
    @DisplayName("null values become empty strings; a dangling key without value is ignored")
    void nullValuesAndOddPairs() {
        assertEquals("[]", sub("[%a%]", "a", null));
        assertEquals("[%a%]", sub("[%a%]", "a"));
        assertEquals("", sub("%a%", "a", ""));
    }

    @Test
    @DisplayName("raw mode inserts values untouched (console commands)")
    void rawMode() {
        assertEquals("ban <red>x 'y'", sub("ban %player%", "player", "<red>x 'y'"));
    }

    @Test
    @DisplayName("escape mode neutralises MiniMessage tags in values")
    void escapeMode() {
        String template = "<gray>%player%";
        String result = TextTemplates.substitute(template, new String[]{"player", "<red>Evil</red>"}, true);
        Component component = MiniMessage.miniMessage().deserialize(result);
        assertEquals("<red>Evil</red>", PlainTextComponentSerializer.plainText().serialize(component));
        assertTrue(component.children().stream().noneMatch(child -> child.color() != null && child.color().equals(net.kyori.adventure.text.format.NamedTextColor.RED)));
    }

    @Test
    @DisplayName("click and hover injection through a value does not produce events")
    void noEventInjection() {
        String evil = "<click:run_command:'/op Evil'>x</click><hover:show_text:'y'>z</hover>";
        String result = TextTemplates.substitute("%v%", new String[]{"v", evil}, true);
        Component component = MiniMessage.miniMessage().deserialize(result);
        assertFalse(hasEvents(component), "value must be rendered as literal text");
        assertEquals(evil.replace('\'', '’'), PlainTextComponentSerializer.plainText().serialize(component));
    }

    @Test
    @DisplayName("escape replaces both quote characters so a value cannot terminate a quoted tag argument")
    void quotesReplaced() {
        String escaped = TextTemplates.escape("it's \"quoted\"");
        assertFalse(escaped.contains("'"));
        assertFalse(escaped.contains("\""));
        assertEquals("it’s ”quoted”", escaped);
    }

    @Test
    @DisplayName("escape replaces backslashes with U+2216 so they cannot un-escape tags in nested parsing")
    void backslashReplaced() {
        assertEquals("\u2216\\<red>x\u2216", TextTemplates.escape("\\<red>x\\"));
        assertFalse(TextTemplates.escape("a\\b").contains("\\"));
        assertEquals("a\u2216\u2216b", TextTemplates.escape("a\\\\b"));
    }

    @Test
    @DisplayName("escape keeps harmless text unchanged")
    void plainEscape() {
        assertEquals("offset=0.123 ground=true", TextTemplates.escape("offset=0.123 ground=true"));
        assertEquals("", TextTemplates.escape(""));
    }

    static boolean hasEvents(Component component) {
        if (component.clickEvent() != null || component.hoverEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasEvents(child)) {
                return true;
            }
        }
        return false;
    }
}
