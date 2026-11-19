package dev.vaplus.anticheat.check.impl.packet;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Timer: tick order, negative timer and the ping abuse limit")
class TimerSignalsTest {

    private static final long MILLI = 1_000_000L;

    private final Harness harness = TestContexts.builder().check(TimerCheck.DESCRIPTOR, TimerCheck::new).build();
    private final PlayerData player = harness.newPlayer();
    private final TimerCheck check = player.checks().get(TimerCheck.class);

    TimerSignalsTest() {
        player.enableTickEndMode();
    }

    private void tick(long gapMicros) {
        harness.time.advanceNanos(gapMicros * 1_000L);
        player.onClientTick();
    }

    private void answeredMarker() {
        player.acks().sendMarker();
        player.acks().onPong(harness.lastTransport().lastMarkerId());
    }

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    @Test
    @DisplayName("one movement packet per tick passes; a teleport answer is the only extra one; two flag")
    void tickOrder() {
        for (int i = 0; i < 20; i++) {
            check.onMovementPacket();
            tick(50_000);
        }
        check.onTeleportConfirm();
        check.onMovementPacket();
        check.onMovementPacket();
        tick(50_000);
        assertEquals(0, check.violationCount());
        check.onMovementPacket();
        check.onMovementPacket();
        assertTrue(flagged("tick-order movement packets=2"));
    }

    @Test
    @DisplayName("two vehicle moves in one tick flag unless the server corrected the vehicle")
    void vehicleTickOrder() {
        check.onVehicleMove();
        tick(50_000);
        check.onServerVehicleCorrection();
        check.onVehicleMove();
        check.onVehicleMove();
        tick(50_000);
        assertEquals(0, check.violationCount());
        check.onVehicleMove();
        check.onVehicleMove();
        assertTrue(flagged("tick-order vehicle moves=2"));
    }

    @Test
    @DisplayName("a client answering markers in real time but ticking at half speed flags (negative)")
    void negativeTimer() {
        for (int i = 0; i < 40; i++) {
            answeredMarker();
            tick(50_000);
        }
        assertEquals(0, check.violationCount());
        for (int i = 0; i < 200; i++) {
            answeredMarker();
            tick(100_000);
        }
        assertTrue(flagged("negative"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("withholding marker answers while ticking slowly banks at most the limit")
    void pingAbuseLimit() {
        for (int i = 0; i < 40; i++) {
            answeredMarker();
            tick(50_000);
        }
        // 3 s at half speed without answering a single marker (ticks keep coming: not a stall)
        for (int i = 0; i < 30; i++) {
            player.acks().sendMarker();
            tick(100_000);
        }
        // spend the "banked" 1.5 s in a burst
        for (int i = 0; i < 30; i++) {
            tick(1_000);
        }
        assertTrue(check.violationCount() >= 1, harness.alerts.alerts()::toString);
    }
}
