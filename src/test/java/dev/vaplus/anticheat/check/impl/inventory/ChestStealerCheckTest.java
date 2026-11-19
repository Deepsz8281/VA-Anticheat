package dev.vaplus.anticheat.check.impl.inventory;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChestStealer (container emptying speed and regularity)")
class ChestStealerCheckTest {

    private record Fixture(Harness harness, PlayerData player, ChestStealerCheck check) {

        void tick() {
            harness.time.advanceNanos(50_000_000L);
            player.onClientTick();
        }

        void ticks(int count) {
            for (int i = 0; i < count; i++) {
                tick();
            }
        }

        /** Opens a container and lets the client acknowledge both markers of the bracket. */
        void open(int windowId) {
            List<Runnable> after = new ArrayList<>();
            check.onOpened(windowId, after);
            int first = harness.lastTransport().lastMarkerId();
            after.forEach(Runnable::run);
            int second = harness.lastTransport().lastMarkerId();
            assertTrue(player.acks().onPong(first));
            assertTrue(player.acks().onPong(second));
        }

        void move(int windowId, int slot) {
            check.onQuickMove(windowId, slot);
        }

        int flags() {
            return check.violationCount();
        }
    }

    private static Fixture fixture() {
        Harness harness = TestContexts.builder()
                .check(ChestStealerCheck.DESCRIPTOR, ChestStealerCheck::new)
                .build();
        PlayerData player = harness.newPlayer();
        player.enableTickEndMode();
        Fixture fixture = new Fixture(harness, player, player.checks().get(ChestStealerCheck.class));
        fixture.ticks(5);
        return fixture;
    }

    @Test
    @DisplayName("an acknowledged open is judged, an unacknowledged one is not")
    void ackRequired() {
        Fixture f = fixture();
        List<Runnable> after = new ArrayList<>();
        f.check().onOpened(1, after);
        assertFalse(f.check().judging());
        for (int i = 0; i < 40; i++) {
            f.move(1, i % 27);
        }
        assertEquals(0, f.flags());
        assertEquals(0.0, f.check().buffer());
        Fixture g = fixture();
        g.open(1);
        assertTrue(g.check().judging());
    }

    @Test
    @DisplayName("shift-clicking right after the open flags once buffered")
    void instantFirstClick() {
        Fixture f = fixture();
        for (int round = 0; round < 6; round++) {
            f.open(2 + round);
            f.move(2 + round, 0);
            f.ticks(5);
        }
        assertTrue(f.flags() >= 1, "flags: " + f.flags());
    }

    @Test
    @DisplayName("a human reaction time after the open does not flag")
    void humanOpenDelay() {
        Fixture f = fixture();
        for (int round = 0; round < 20; round++) {
            f.open(2 + round);
            f.ticks(4 + round % 4);
            f.move(2 + round, 3);
            f.ticks(10);
            f.check().onClosed(2 + round);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a stealer with constant one-per-tick timing and sequential slots flags")
    void constantStealer() {
        Fixture f = fixture();
        f.open(1);
        f.ticks(6);
        for (int i = 0; i < 27; i++) {
            f.move(1, i);
            f.tick();
        }
        assertTrue(f.flags() >= 1, "flags: " + f.flags());
    }

    @Test
    @DisplayName("constant 2.5-tick cadence (alternating 2 and 3) flags")
    void quantizedConstantStealer() {
        Fixture f = fixture();
        f.open(1);
        f.ticks(6);
        for (int i = 0; i < 27; i++) {
            f.move(1, (i * 7) % 27);
            f.ticks(i % 2 == 0 ? 2 : 3);
        }
        assertTrue(f.flags() >= 1, "flags: " + f.flags());
    }

    @Test
    @DisplayName("several shift-clicks per tick sustained flag")
    void burstStealer() {
        Fixture f = fixture();
        f.open(1);
        f.ticks(6);
        for (int t = 0; t < 12; t++) {
            for (int k = 0; k < 4; k++) {
                f.move(1, (t * 4 + k) % 27);
            }
            f.tick();
        }
        assertTrue(f.flags() >= 1, "flags: " + f.flags());
    }

    @Test
    @DisplayName("fast but jittery human shift-clicking does not flag")
    void humanJitter() {
        Fixture f = fixture();
        Random random = new Random(42);
        for (int session = 0; session < 30; session++) {
            int id = 1 + session % 100;
            f.open(id);
            f.ticks(5 + random.nextInt(6));
            for (int i = 0; i < 20; i++) {
                f.move(id, random.nextInt(27));
                f.ticks(1 + random.nextInt(4));
            }
            f.check().onClosed(id);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a short drag-style burst of a few ticks does not flag")
    void shortDragBurst() {
        Fixture f = fixture();
        f.open(1);
        f.ticks(8);
        for (int t = 0; t < 2; t++) {
            for (int k = 0; k < 5; k++) {
                f.move(1, t * 5 + k);
            }
            f.tick();
        }
        f.ticks(20);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("clicks delivered back to back in wall time but spaced by human tick gaps do not flag")
    void lagBurstKeepsTickSpacing() {
        Fixture f = fixture();
        f.open(1);
        f.ticks(6);
        int[] gaps = {2, 5, 1, 4, 3, 7, 2, 6, 1, 3, 4, 2};
        for (int i = 0; i < gaps.length; i++) {
            f.move(1, i);
            for (int k = 0; k < gaps[i]; k++) {
                f.player().onClientTick();
            }
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a closed or replaced window stops being judged; stale acks are ignored")
    void closeAndStaleAck() {
        Fixture f = fixture();
        List<Runnable> after = new ArrayList<>();
        f.check().onOpened(1, after);
        int first = f.harness().lastTransport().lastMarkerId();
        after.forEach(Runnable::run);
        int second = f.harness().lastTransport().lastMarkerId();
        f.check().onClosed(1);
        f.player().acks().onPong(first);
        f.player().acks().onPong(second);
        assertFalse(f.check().judging());
        f.open(5);
        assertTrue(f.check().judging());
        f.check().onClosed(5);
        assertFalse(f.check().judging());
        for (int i = 0; i < 30; i++) {
            f.move(5, i % 27);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("window 0 and a different window id are never judged")
    void wrongWindow() {
        Fixture f = fixture();
        f.open(3);
        f.ticks(6);
        for (int i = 0; i < 30; i++) {
            f.move(0, i);
            f.move(4, i);
            f.tick();
        }
        assertEquals(0, f.flags());
    }
}
