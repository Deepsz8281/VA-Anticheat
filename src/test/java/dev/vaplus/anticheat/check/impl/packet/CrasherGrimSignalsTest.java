package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Crasher: Grim Crash A/B/D/E/G/I and Exploit A")
class CrasherGrimSignalsTest {

    private final TestContexts.Harness harness = TestContexts.builder().module(new PacketChecksModule()).build();
    private final PlayerData player = harness.newPlayer();
    private final CrasherCheck check = player.checks().get(CrasherCheck.class);

    @Test
    @DisplayName("position: inside the world limit passes, beyond it or non-finite is dropped")
    void position() {
        assertTrue(check.position(29_999_000.0, 320.0, -29_999_000.0));
        assertFalse(check.position(3.0E7, 64.0, 0.0));
        assertFalse(check.position(0.0, Double.NaN, 0.0));
        assertEquals(2, check.violationCount());
    }

    @Test
    @DisplayName("creative: only judged after the join, and allowed in creative mode")
    void creative() {
        assertTrue(check.creative(), "join not seen yet");
        check.onJoin();
        player.abilities().applyGameMode(GameMode.CREATIVE);
        assertTrue(check.creative());
        player.abilities().applyGameMode(GameMode.SURVIVAL);
        assertFalse(check.creative());
    }

    @Test
    @DisplayName("lectern: clicks in an open lectern are dropped, other windows pass")
    void lectern() {
        check.onOpenWindow(3, false);
        assertTrue(check.lectern(3));
        check.onOpenWindow(4, true);
        assertTrue(check.lectern(0));
        assertFalse(check.lectern(4));
    }

    @Test
    @DisplayName("view distance, sequence, bundle and item name limits")
    void limits() {
        assertTrue(check.viewDistance(2));
        assertFalse(check.viewDistance(1));
        assertTrue(check.sequence(0, "use"));
        assertFalse(check.sequence(-1, "dig"));
        assertTrue(check.bundle(-1));
        assertFalse(check.bundle(-2));
        assertTrue(check.itemName(50));
        assertFalse(check.itemName(51));
        assertEquals(4, check.violationCount());
    }
}
