package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CrasherCheck (crash protection)")
class CrasherCheckTest {

    private static final long MS = 1_000_000L;

    private static CrasherCheck check() {
        TestContexts.Harness harness = TestContexts.builder().module(new PacketChecksModule()).build();
        PlayerData player = harness.newPlayer();
        return player.checks().get(CrasherCheck.class);
    }

    @Test
    @DisplayName("descriptor: id crasher, PACKET, registered by the packet module")
    void descriptor() {
        assertEquals("crasher", CrasherCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.PACKET, CrasherCheck.DESCRIPTOR.category());
        assertTrue(List.of(PacketChecksModule.descriptors()).contains(CrasherCheck.DESCRIPTOR));
        assertTrue(check() != null);
    }

    @Test
    @DisplayName("rate: a normal client and a lag burst pass, a flood is dropped and flagged once per window")
    void rate() {
        CrasherCheck check = check();
        long now = 10_000 * MS;
        // 20 s of a busy client: 150 packets per second
        for (int second = 0; second < 20; second++) {
            for (int i = 0; i < 150; i++) {
                assertTrue(check.rate(now + second * 1000 * MS + i * MS));
            }
        }
        // lag burst: 3 s of packets (450) arrive within one second
        long burst = now + 25_000 * MS;
        for (int i = 0; i < 450; i++) {
            assertTrue(check.rate(burst + i * MS / 10));
        }
        assertEquals(0, check.violationCount());

        long flood = now + 40_000 * MS;
        int passed = 0;
        for (int i = 0; i < 5000; i++) {
            if (check.rate(flood + i * MS / 10)) {
                passed++;
            }
        }
        assertEquals(CrasherCheck.DEFAULT_MAX_PACKETS_PER_SECOND, passed);
        assertEquals(1, check.violationCount(), "one flag per exceeded window");
    }

    @Test
    @DisplayName("size: large packets are dropped, normal ones pass")
    void size() {
        CrasherCheck check = check();
        assertTrue(check.size(PacketType.Play.Client.PLUGIN_MESSAGE, 32_767));
        assertTrue(check.size(PacketType.Play.Client.CLICK_WINDOW, 4_000));
        assertFalse(check.size(PacketType.Play.Client.PLUGIN_MESSAGE, 2_000_000));
        assertEquals(1, check.violationCount());
    }

    @Test
    @DisplayName("window click: every vanilla mode/button pair passes, impossible ones are dropped")
    void click() {
        CrasherCheck check = check();
        assertTrue(check.click(WindowClickType.PICKUP, 0, 12));
        assertTrue(check.click(WindowClickType.PICKUP, 1, CrasherCheck.OUTSIDE_SLOT));
        assertTrue(check.click(WindowClickType.QUICK_MOVE, 1, 40));
        assertTrue(check.click(WindowClickType.SWAP, 8, 3));
        assertTrue(check.click(WindowClickType.SWAP, 40, 3));
        assertTrue(check.click(WindowClickType.CLONE, 2, 3));
        assertTrue(check.click(WindowClickType.THROW, 0, -1));
        for (int button : new int[]{0, 1, 2, 4, 5, 6, 8, 9, 10}) {
            assertTrue(check.click(WindowClickType.QUICK_CRAFT, button, CrasherCheck.OUTSIDE_SLOT));
        }
        assertTrue(check.click(WindowClickType.PICKUP_ALL, 0, 89));
        assertEquals(0, check.violationCount());

        assertFalse(check.click(WindowClickType.SWAP, 9, 3));
        assertFalse(check.click(WindowClickType.PICKUP, 0, 30_000));
        assertFalse(check.click(WindowClickType.PICKUP, 0, -5));
        assertFalse(check.click(WindowClickType.QUICK_CRAFT, 3, 0));
        assertFalse(check.click(WindowClickType.UNKNOWN, 0, 0));
        assertFalse(check.click(null, 0, 0));
        assertEquals(6, check.violationCount());
    }

    @Test
    @DisplayName("book: a full vanilla book passes, too many or too long pages are dropped")
    void book() {
        CrasherCheck check = check();
        List<String> full = new ArrayList<>(Collections.nCopies(100, "x".repeat(1024)));
        assertTrue(check.book(full, "Title"));
        assertTrue(check.book(List.of(), null));
        full.add("one more");
        assertFalse(check.book(full, "Title"));
        assertFalse(check.book(List.of("x".repeat(5000)), ""));
        assertFalse(check.book(List.of("ok"), "t".repeat(200)));
        assertEquals(3, check.violationCount());
    }

    @Test
    @DisplayName("tab complete: long commands pass up to the limit")
    void tabComplete() {
        CrasherCheck check = check();
        assertTrue(check.tabComplete(256));
        assertTrue(check.tabComplete(CrasherCheck.DEFAULT_MAX_TAB_COMPLETE_LENGTH));
        assertFalse(check.tabComplete(30_000));
        assertEquals(1, check.violationCount());
    }
}
