package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Sprint (omni / rage sprint)")
class SprintCheckTest {

    private final Harness harness = TestContexts.builder().module(new MovementChecksModule()).build();
    private final PlayerData player = harness.newPlayer();

    private SprintCheck sprint() {
        return player.checks().get(SprintCheck.class);
    }

    private void keys(boolean forward, boolean backward, boolean left) {
        player.pose().applyInput(forward, backward, left, false, false, false, false, player.clientTicks());
    }

    @Test
    @DisplayName("sprinting forward, also diagonally, is vanilla")
    void forwardIsFine() {
        player.enableTickEndMode();
        player.pose().sprinting(true);
        keys(true, false, true);
        for (int i = 0; i < 200; i++) {
            player.onClientTick();
        }
        assertEquals(0, sprint().violationCount());
    }

    @Test
    @DisplayName("sprinting backwards flags; a one-tick overlap does not")
    void backwardsFlags() {
        player.enableTickEndMode();
        player.pose().sprinting(true);
        // Releasing W: vanilla stops sprinting within the tick, the state may lag one tick.
        for (int i = 0; i < 30; i++) {
            keys(false, false, false);
            player.onClientTick();
            keys(true, false, false);
            player.onClientTick();
        }
        assertEquals(0, sprint().violationCount());

        keys(false, true, false);
        for (int i = 0; i < 9; i++) {
            player.onClientTick();
        }
        assertEquals(1, sprint().violationCount(), () -> "alerts: " + harness.alerts.alerts());
    }

    @Test
    @DisplayName("clients without the input packet are not judged")
    void needsInputPacket() {
        player.enableTickEndMode();
        player.pose().sprinting(true);
        for (int i = 0; i < 50; i++) {
            player.onClientTick();
        }
        assertEquals(0, sprint().violationCount());
    }
}
