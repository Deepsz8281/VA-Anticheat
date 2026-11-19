package dev.vaplus.anticheat.check.impl.packet;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Chat (Grim ChatA-D) and ClientBrand")
class ChatAndBrandCheckTest {

    private final TestContexts.Harness harness = TestContexts.builder().module(new PacketChecksModule()).build();
    private final PlayerData player = harness.newPlayer();
    private final ChatCheck chat = player.checks().get(ChatCheck.class);
    private final ClientBrandCheck brand = player.checks().get(ClientBrandCheck.class);

    private boolean flagged(Object check, String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    private static byte[] payload(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length + 1];
        out[0] = (byte) bytes.length;
        System.arraycopy(bytes, 0, out, 1, bytes.length);
        return out;
    }

    @Test
    @DisplayName("invalid-message: trimmed messages and commands pass, untrimmed, empty or slash messages flag")
    void invalidMessage() {
        chat.onMessage("hello world");
        chat.onCommand("spawn");
        assertEquals(0, chat.violationCount());
        chat.onMessage(" hello");
        chat.onMessage("");
        chat.onMessage("/op me");
        chat.onCommand("spawn ");
        assertEquals(4, chat.violationCount());
        assertTrue(flagged(chat, "invalid-message"));
    }

    @Test
    @DisplayName("blank-tab: a blank suggestion request needs a repeat; real requests pass")
    void blankTab() {
        chat.onTabComplete("/gamemode cr");
        chat.onTabComplete("/");
        assertEquals(0, chat.violationCount());
        chat.onTabComplete(" ");
        assertTrue(flagged(chat, "blank-tab"));
    }

    @Test
    @DisplayName("moving: chatting while holding movement keys (1.21.2+) or sprinting flags after a repeat")
    void moving() {
        player.enableTickEndMode();
        player.pose().applyInput(false, false, false, false, false, false, false, 1L);
        chat.onMessage("hi");
        assertEquals(0, chat.violationCount());
        player.pose().applyInput(true, false, false, false, false, false, false, 2L);
        chat.onMessage("hi");
        chat.onCommand("home");
        assertTrue(flagged(chat, "moving"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("hidden: chatting with hidden chat flags; commands do not")
    void hidden() {
        player.chatHidden(true);
        chat.onCommand("home");
        assertEquals(0, chat.violationCount());
        chat.onMessage("hi");
        assertTrue(flagged(chat, "hidden"));
    }

    @Test
    @DisplayName("brand: decoding, blocked brands and malformed payloads")
    void clientBrand() {
        assertEquals("vanilla", ClientBrandCheck.decode(payload("vanilla")));
        assertNull(ClientBrandCheck.decode(new byte[0]));
        assertNull(ClientBrandCheck.decode(new byte[]{5, 'a'}));
        assertNull(ClientBrandCheck.decode(payload("x".repeat(70))));
        brand.onBrand(payload("fabric"));
        assertEquals(0, brand.violationCount(), "nothing is blocked by default");
        brand.onBrand(new byte[0]);
        assertEquals(0, brand.violationCount(), "judged once per session");
    }

    @Test
    @DisplayName("brand: an invalid payload flags")
    void invalidBrand() {
        brand.onBrand(new byte[]{3, 'a'});
        assertTrue(flagged(brand, "invalid"));
        player.clientBrandPayload(payload("vanilla"));
        assertEquals("vanilla", player.clientBrand());
    }
}
