package dev.vaplus.anticheat.config;

import dev.vaplus.anticheat.testutil.Configs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MessageCatalog")
class MessageCatalogTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static MessageCatalog catalog(String user, String defaults) {
        YamlConfiguration userConfig = user == null ? null : Configs.fromString(user);
        YamlConfiguration defaultConfig = defaults == null ? null : Configs.fromString(defaults);
        return MessageCatalog.of(userConfig, defaultConfig);
    }

    private static MessageCatalog bundled() {
        return MessageCatalog.of(null, Configs.bundledMessages());
    }

    /** Collects click events of the whole tree, including those nested in hover text. */
    private static void collectClicks(Component component, List<ClickEvent> into) {
        if (component.clickEvent() != null) {
            into.add(component.clickEvent());
        }
        if (component.hoverEvent() != null && component.hoverEvent().value() instanceof Component hover) {
            collectClicks(hover, into);
        }
        for (Component child : component.children()) {
            collectClicks(child, into);
        }
    }

    private static String hoverText(Component component) {
        StringBuilder out = new StringBuilder();
        collectHover(component, out);
        return out.toString();
    }

    private static void collectHover(Component component, StringBuilder out) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.value() instanceof Component text) {
            out.append(plain(text));
        }
        for (Component child : component.children()) {
            collectHover(child, out);
        }
    }

    @Test
    @DisplayName("placeholders are substituted in rendered messages")
    void substitution() {
        MessageCatalog catalog = catalog(null, "greet: \"<green>Hello %player%, %n% flags\"");
        assertEquals("Hello Steve, 3 flags", plain(catalog.render("greet", "player", "Steve", "n", "3")));
    }

    @Test
    @DisplayName("100% literals and unknown keys survive rendering")
    void literalsAndUnknown() {
        MessageCatalog catalog = catalog(null, "m: \"Progress 100% of %player% %unknown%\"");
        assertEquals("Progress 100% of Steve %unknown%", plain(catalog.render("m", "player", "Steve")));
    }

    @Test
    @DisplayName("MiniMessage tags inside a value are escaped, not interpreted")
    void tagInjectionEscaped() {
        MessageCatalog catalog = catalog(null, "m: \"<gray>%player%\"");
        Component component = catalog.render("m", "player", "<red><bold>Evil</bold></red><click:run_command:'/op x'>c</click>");
        assertEquals("<red><bold>Evil</bold></red><click:run_command:’/op x’>c</click>", plain(component));
        List<ClickEvent> clicks = new ArrayList<>();
        collectClicks(component, clicks);
        assertTrue(clicks.isEmpty());
        assertTrue(component.children().stream().noneMatch(child -> NamedTextColor.RED.equals(child.color())));
    }

    @Test
    @DisplayName("%prefix% inserts the raw prefix template, which stays formatted")
    void prefixIsRaw() {
        MessageCatalog catalog = catalog(null, """
                prefix: "<gold>[VA]</gold> "
                m: "%prefix%body"
                """);
        Component component = catalog.render("m");
        assertEquals("[VA] body", plain(component));
        assertTrue(component.children().stream().anyMatch(child -> NamedTextColor.GOLD.equals(child.color())),
                "prefix markup must be interpreted");
    }

    @Test
    @DisplayName("a value containing %prefix% is not expanded")
    void prefixInValue() {
        MessageCatalog catalog = catalog(null, """
                prefix: "PFX"
                m: "%v%"
                """);
        assertEquals("%prefix%", plain(catalog.render("m", "v", "%prefix%")));
    }

    @Test
    @DisplayName("quotes in values cannot break the hover argument of the bundled alert")
    void quotesDoNotBreakHover() {
        MessageCatalog catalog = bundled();
        String details = "he said \"it's 'bad'\" ' ></hover><click:run_command:'/op Evil'>";
        Component alert = catalog.render("alert", alertPairs("Steve", details));

        List<ClickEvent> clicks = new ArrayList<>();
        collectClicks(alert, clicks);
        assertEquals(1, clicks.size(), "only the template's own click event may exist: " + clicks);
        assertEquals(ClickEvent.Action.RUN_COMMAND, clicks.get(0).action());
        assertEquals("/tp Steve", clicks.get(0).value());
        assertTrue(hoverText(alert).contains("Details:"), "hover must still render");
        assertTrue(plain(alert).contains("Steve"));
        assertTrue(plain(alert).contains("VL 1.0"));
    }

    @Test
    @DisplayName("angle brackets and backslashes in details keep the bundled alert intact")
    void tagsAndBackslashesInDetails() {
        MessageCatalog catalog = bundled();
        for (String details : new String[]{"<red>", "</hover>", "\\", "a\\", "\\<red>", "\\'", "\\<red>x\\", "\\\\<red>x\\\\", "<newline><newline>", "%player%", "100%"}) {
            for (String key : new String[]{"alert", "alert-experimental"}) {
                Component alert = catalog.render(key, alertPairs("Steve", details));
                List<ClickEvent> clicks = new ArrayList<>();
                collectClicks(alert, clicks);
                assertEquals(1, clicks.size(), key + " with details '" + details + "'");
                assertEquals("/tp Steve", clicks.get(0).value(), key + " with details '" + details + "'");
                assertTrue(plain(alert).contains("Steve") && plain(alert).contains("\u00bb"),
                        key + " with details '" + details + "' rendered as: " + plain(alert));
                assertTrue(hoverText(alert).contains("Details:"), key + " with details '" + details + "'");
            }
        }
    }

    @Test
    @DisplayName("a backslash-wrapped tag in details cannot apply a style or break the bundled alert hover")
    void backslashTagCannotStyleHover() {
        String evil = "\\<red>x\\";
        for (String key : new String[]{"alert", "alert-experimental"}) {
            Component alert = bundled().render(key, alertPairs("Steve", evil));
            List<ClickEvent> clicks = new ArrayList<>();
            collectClicks(alert, clicks);
            assertEquals(1, clicks.size(), key);
            assertEquals("/tp Steve", clicks.get(0).value());
            String hover = hoverText(alert);
            assertTrue(hover.contains("<red>x"), key + " hover must show the value literally: " + hover);
            assertTrue(hover.contains("Click to teleport"), key + " hover must stay intact: " + hover);
            assertFalse(hoverHasColor(alert, NamedTextColor.RED), key + ": the value must not apply red inside the hover");
            assertTrue(plain(alert).contains("VL 1.0"), key);
        }
    }

    private static boolean hoverHasColor(Component component, NamedTextColor color) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.value() instanceof Component text && hasColor(text, color)) {
            return true;
        }
        for (Component child : component.children()) {
            if (hoverHasColor(child, color)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasColor(Component component, NamedTextColor color) {
        if (color.equals(component.color())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasColor(child, color)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a hostile player name cannot add click events either")
    void hostilePlayerName() {
        Component alert = bundled().render("alert", alertPairs("<click:run_command:'/op x'>N", "d"));
        List<ClickEvent> clicks = new ArrayList<>();
        collectClicks(alert, clicks);
        assertEquals(1, clicks.size());
    }

    private static String[] alertPairs(String player, String details) {
        return new String[]{"player", player, "check", "Speed", "category", "movement", "vl", "1.0",
                "details", details, "ping", "42", "version", "1.21.4"};
    }

    @Test
    @DisplayName("user file overrides defaults; missing keys fall back to defaults")
    void userOverridesDefaults() {
        MessageCatalog catalog = catalog("a: user-a\nc: user-c\n", "a: default-a\nb: default-b\n");
        assertEquals("user-a", catalog.raw("a"));
        assertEquals("default-b", catalog.raw("b"));
        assertEquals("user-c", catalog.raw("c"));
        assertTrue(catalog.has("a") && catalog.has("b") && catalog.has("c"));
        assertFalse(catalog.has("d"));
    }

    @Test
    @DisplayName("a user message that replaces a default list becomes a single line and vice versa")
    void typeOverride() {
        MessageCatalog catalog = catalog("""
                list-to-string: single
                string-to-list:
                  - one
                  - two
                """, """
                list-to-string:
                  - x
                  - y
                string-to-list: old
                """);
        assertEquals(1, catalog.renderLines("list-to-string").size());
        assertEquals("single", plain(catalog.renderLines("list-to-string").get(0)));
        assertEquals(List.of("one", "two"), catalog.renderLines("string-to-list").stream().map(MessageCatalogTest::plain).toList());
    }

    @Test
    @DisplayName("null user and null defaults give an empty catalogue")
    void emptyCatalog() {
        MessageCatalog catalog = MessageCatalog.of(null, null);
        assertFalse(catalog.has("anything"));
        assertEquals("anything", catalog.raw("anything"));
    }

    @Test
    @DisplayName("list messages render one component per line with substitution in each line")
    void listMessages() {
        MessageCatalog catalog = catalog(null, """
                prefix: "P "
                info:
                  - "%prefix%for %player%"
                  - "<gray>ping %ping%ms"
                """);
        List<Component> lines = catalog.renderLines("info", "player", "Steve", "ping", "40");
        assertEquals(2, lines.size());
        assertEquals("P for Steve", plain(lines.get(0)));
        assertEquals("ping 40ms", plain(lines.get(1)));
        assertEquals("%prefix%for %player%", catalog.raw("info"), "raw() of a list is its first line");
    }

    @Test
    @DisplayName("missing keys are visible: raw returns the key, render shows the key text")
    void missingKey() {
        MessageCatalog catalog = catalog(null, "a: b");
        assertEquals("typo-key", catalog.raw("typo-key"));
        assertEquals("typo-key", plain(catalog.render("typo-key")));
        assertEquals(List.of("typo-key"), catalog.renderLines("typo-key").stream().map(MessageCatalogTest::plain).toList());
    }

    @Test
    @DisplayName("nested YAML sections are addressable by dotted key")
    void nestedKeys() {
        MessageCatalog catalog = catalog(null, """
                group:
                  inner: value
                """);
        assertTrue(catalog.has("group.inner"));
        assertFalse(catalog.has("group"));
        assertEquals("value", catalog.raw("group.inner"));
    }

    @Test
    @DisplayName("numbers and booleans in YAML become text")
    void nonStringValues() {
        MessageCatalog catalog = catalog(null, "n: 5\nb: true\n");
        assertEquals("5", catalog.raw("n"));
        assertEquals("true", catalog.raw("b"));
    }

    @Test
    @DisplayName("renderTemplate works for ad-hoc templates such as kick messages from the config")
    void renderTemplate() {
        MessageCatalog catalog = catalog(null, "prefix: \"P \"");
        assertEquals("P bye Steve", plain(catalog.renderTemplate("%prefix%bye %player%", "player", "Steve")));
    }

    @Test
    @DisplayName("every bundled message deserialises into a non-empty component with all placeholders filled")
    void bundledMessagesRender() {
        YamlConfiguration messages = Configs.bundledMessages();
        MessageCatalog catalog = bundled();
        assertFalse(messages.getKeys(true).isEmpty());
        String[] allPairs = {"player", "Steve", "uuid", "u", "check", "Speed", "check_id", "speed", "category", "movement",
                "vl", "1.0", "count", "1", "ping", "20", "version", "1.21.4", "brand", "vanilla", "details", "d", "usage", "/ac", "target", "T",
                "ping_avg", "21", "pending", "1", "gamemode", "survival", "flying", "false", "bypass", "false", "ticks", "9",
                "tick_mode", "movement", "x", "0", "y", "0", "z", "0", "ground", "true", "exemptions", "-", "ago", "1s",
                "page", "1", "enabled", "true", "window", "10", "players", "2", "packets", "5", "packet_avg", "1",
                "tick_avg", "1", "budget", "100", "avg", "1", "calls", "1", "share", "5.0", "warnings", "0",
                "warning", "w", "message", "m"};
        for (String key : messages.getKeys(true)) {
            if (messages.isConfigurationSection(key)) {
                continue;
            }
            for (Component line : catalog.renderLines(key, allPairs)) {
                assertNotNull(line);
                String text = plain(line);
                assertFalse(text.contains("%prefix%"), key);
                assertFalse(text.matches("(?s).*%[a-z_]+%.*"), "unfilled placeholder in '" + key + "': " + text);
            }
        }
    }
}
