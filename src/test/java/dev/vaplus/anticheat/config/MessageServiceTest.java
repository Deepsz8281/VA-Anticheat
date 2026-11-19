package dev.vaplus.anticheat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression tests for the strict messages.yml loading (review finding H3): syntax errors must fail
 * loudly instead of silently dropping every override, and valid files merge over the bundled defaults.
 * These run without a Bukkit server through {@link MessageService#merge(Reader, Reader)}.
 */
@DisplayName("MessageService loading")
class MessageServiceTest {

    private static Reader reader(String yaml) {
        return new StringReader(yaml);
    }

    @Test
    @DisplayName("a YAML syntax error in messages.yml throws instead of yielding an empty catalogue")
    void corruptUserFileThrows() {
        assertThrows(MessageService.LoadException.class,
                () -> MessageService.merge(reader("alert: [unclosed\n  bad: {"), reader("a: b")));
    }

    @Test
    @DisplayName("a YAML syntax error in the bundled defaults throws too")
    void corruptBundledThrows() {
        assertThrows(MessageService.LoadException.class,
                () -> MessageService.merge(reader("a: b"), reader("alert: [unclosed")));
    }

    @Test
    @DisplayName("a valid user file overrides keys and falls back to the bundled defaults")
    void validOverridesAndFallback() throws MessageService.LoadException {
        MessageCatalog catalog = MessageService.merge(
                reader("prefix: \"[User] \"\ncustom: hello\n"),
                reader("prefix: \"[Bundled] \"\nalert: \"default-alert\""));
        assertEquals("[User] ", catalog.raw("prefix"), "the user override must win");
        assertEquals("hello", catalog.raw("custom"));
        assertEquals("default-alert", catalog.raw("alert"), "missing user keys fall back to the bundled defaults");
    }

    @Test
    @DisplayName("a user file is not required: bundled defaults alone build the catalogue")
    void onlyBundled() throws MessageService.LoadException {
        MessageCatalog catalog = MessageService.merge(null, reader("prefix: \"[Bundled] \""));
        assertEquals("[Bundled] ", catalog.raw("prefix"));
    }
}
