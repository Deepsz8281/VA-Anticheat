package dev.vaplus.anticheat.violation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ActionParser")
class ActionParserTest {

    @Test
    @DisplayName("kick without message uses the default message (null template)")
    void kickPlain() {
        assertEquals(new ViolationAction.Kick(null), ActionParser.parse("kick"));
        assertEquals(new ViolationAction.Kick(null), ActionParser.parse("  KICK  "));
        assertEquals(new ViolationAction.Kick(null), ActionParser.parse("kick:"));
        assertEquals(new ViolationAction.Kick(null), ActionParser.parse("kick:    "));
    }

    @Test
    @DisplayName("kick with message keeps everything after the first colon")
    void kickWithMessage() {
        assertEquals(new ViolationAction.Kick("Cheating"), ActionParser.parse("kick: Cheating"));
        assertEquals(new ViolationAction.Kick("<red>Bye: %player%"), ActionParser.parse("kick: <red>Bye: %player%"));
        assertEquals(new ViolationAction.Kick("a:b"), ActionParser.parse("kick:a:b"));
    }

    @Test
    @DisplayName("cmd and command run a console command")
    void commands() {
        assertEquals(new ViolationAction.ConsoleCommand("ban %player% 1d"), ActionParser.parse("cmd: ban %player% 1d"));
        assertEquals(new ViolationAction.ConsoleCommand("ban %player%"), ActionParser.parse("command: ban %player%"));
        assertEquals(new ViolationAction.ConsoleCommand("ban x"), ActionParser.parse("CMD:ban x"));
        assertEquals(new ViolationAction.ConsoleCommand("ban x"), ActionParser.parse("cmd : ban x"));
    }

    @Test
    @DisplayName("exactly one leading slash is stripped from commands")
    void leadingSlashStripped() {
        assertEquals(new ViolationAction.ConsoleCommand("ban x"), ActionParser.parse("cmd: /ban x"));
        assertEquals(new ViolationAction.ConsoleCommand("ban x"), ActionParser.parse("command:/ban x"));
        assertEquals(new ViolationAction.ConsoleCommand("ban x"), ActionParser.parse("cmd: /  ban x"));
        assertEquals(new ViolationAction.ConsoleCommand("/ban x"), ActionParser.parse("cmd: //ban x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cmd", "cmd:", "cmd:   ", "command: /", "cmd: /   "})
    @DisplayName("commands without a command are rejected")
    void emptyCommand(String raw) {
        assertThrows(IllegalArgumentException.class, () -> ActionParser.parse(raw));
    }

    @Test
    @DisplayName("notify with and without message")
    void notifications() {
        assertEquals(new ViolationAction.StaffNotice(null), ActionParser.parse("notify"));
        assertEquals(new ViolationAction.StaffNotice(null), ActionParser.parse("notify:"));
        assertEquals(new ViolationAction.StaffNotice("%player% is suspicious"), ActionParser.parse("notify: %player% is suspicious"));
    }

    @Test
    @DisplayName("unknown action types are rejected with a helpful message")
    void unknownType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ActionParser.parse("ban: Steve"));
        assertTrue(error.getMessage().contains("ban"), error.getMessage());
        assertTrue(error.getMessage().contains("kick, cmd or notify"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ActionParser.parse("kickk"));
        assertThrows(IllegalArgumentException.class, () -> ActionParser.parse("ban x"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "   \n "})
    @DisplayName("null and blank input is rejected")
    void blank(String raw) {
        assertThrows(IllegalArgumentException.class, () -> ActionParser.parse(raw));
    }

    @Test
    @DisplayName("ConsoleCommand itself rejects blank templates")
    void consoleCommandValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ViolationAction.ConsoleCommand("  "));
        assertThrows(NullPointerException.class, () -> new ViolationAction.ConsoleCommand(null));
    }
}
