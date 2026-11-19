package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FastPlace")
class FastPlaceCheckTest {

    private static final class Fixture {
        final Harness harness;
        final PlayerData player;
        final FastPlaceCheck check;
        int next;

        Fixture(boolean tickEnd) {
            harness = TestContexts.builder().check(FastPlaceCheck.DESCRIPTOR, FastPlaceCheck::new).build();
            player = harness.newPlayer();
            if (tickEnd) {
                player.enableTickEndMode();
            }
            check = player.checks().get(FastPlaceCheck.class);
        }

        /** A placement on a fresh block (bridging). */
        void place() {
            place(next++, InteractionHand.MAIN_HAND, BlockFace.UP);
        }

        void place(int x, InteractionHand hand, BlockFace face) {
            BlockInteraction interaction = new BlockInteraction();
            interaction.assignPlace(x, 64, 0, face, 0.5f, 1.0f, 0.5f, hand, 1, false, 0L);
            player.checks().dispatchInteraction(interaction);
        }

        void ticks(int count) {
            for (int i = 0; i < count; i++) {
                player.onClientTick();
            }
        }
    }

    @Test
    @DisplayName("holding right-click (every 4 ticks) does not flag")
    void holdRightClick() {
        Fixture f = new Fixture(true);
        for (int i = 0; i < 200; i++) {
            f.place();
            f.ticks(4);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("fast spam clicking with gaps (about 15 per second) does not flag")
    void spamClicking() {
        Fixture f = new Fixture(true);
        // 3 placements in every 4 ticks = 15/s, never more than 2 consecutive ticks
        for (int i = 0; i < 100; i++) {
            f.place();
            f.ticks(1);
            f.place();
            f.ticks(1);
            f.ticks(1);
            f.place();
            f.ticks(1);
        }
        assertEquals(0, f.check.violationCount(), () -> f.harness.alerts.alerts().toString());
    }

    @Test
    @DisplayName("both hands on the same target in one tick count once")
    void bothHands() {
        Fixture f = new Fixture(true);
        for (int i = 0; i < 100; i++) {
            int x = f.next++;
            f.place(x, InteractionHand.MAIN_HAND, BlockFace.UP);
            f.place(x, InteractionHand.OFF_HAND, BlockFace.UP);
            f.ticks(3);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("a lag burst (ticks arriving together) does not flag")
    void lagBurst() {
        Fixture f = new Fixture(true);
        for (int round = 0; round < 20; round++) {
            // normal vanilla cadence, delivered all at once after a lag spike
            for (int i = 0; i < 5; i++) {
                f.place();
                f.ticks(4);
            }
            f.ticks(20);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("two placements in a tick now and then do not flag")
    void occasionalDouble() {
        Fixture f = new Fixture(true);
        for (int i = 0; i < 100; i++) {
            f.place();
            f.place();
            f.ticks(5);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("a placement in every single tick flags")
    void everyTick() {
        Fixture f = new Fixture(true);
        for (int i = 0; i < 60; i++) {
            f.place();
            f.ticks(1);
        }
        assertTrue(f.check.violationCount() >= 1, "streak must flag");
    }

    @Test
    @DisplayName("several placements per tick flag")
    void severalPerTick() {
        Fixture f = new Fixture(true);
        for (int i = 0; i < 20; i++) {
            f.place();
            f.place();
            f.place();
            f.place();
            f.ticks(3);
        }
        assertTrue(f.check.violationCount() >= 1, "burst must flag");
    }

    @Test
    @DisplayName("legacy clients without tick-end packets are not judged")
    void legacyClient() {
        Fixture f = new Fixture(false);
        for (int i = 0; i < 60; i++) {
            f.place();
            f.place();
            f.place();
            f.ticks(1);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("repeated clicks on an unchanged target are ignored")
    void sameTarget() {
        Fixture f = new Fixture(true);
        for (int i = 0; i < 60; i++) {
            f.place(5, InteractionHand.MAIN_HAND, BlockFace.UP);
            f.ticks(1);
        }
        assertEquals(0, f.check.violationCount());
    }
}
