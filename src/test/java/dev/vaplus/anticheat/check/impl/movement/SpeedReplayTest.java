package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.testutil.replay.scenarios.CheckReplay;
import dev.vaplus.anticheat.testutil.replay.scenarios.Frame;
import dev.vaplus.anticheat.testutil.replay.scenarios.MovementScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpeedCheck replay regression")
class SpeedReplayTest {

    @Test
    @DisplayName("a 0.5 blocks/tick ground speed hack flags on the 4th packet (buffer limit 3) and requests a setback")
    void speedHack() {
        List<Frame> frames = MovementScenarios.speedHack(20);
        CheckReplay replay = MovementReplays.onStone();
        SpeedCheck speed = replay.check(SpeedCheck.class);
        for (int i = 0; i < 3; i++) {
            replay.play(frames.get(i));
        }
        assertEquals(0, speed.violationCount(), "no single-tick verdict: the buffer absorbs the first packets");
        replay.play(frames.get(3));
        assertEquals(1, speed.violationCount());
        replay.play(frames.subList(4, frames.size()));
        MovementReplays.assertAllFramesSimulated(replay, frames.size());

        assertTrue(speed.violationCount() >= 10, "sustained hack keeps flagging: " + speed.violationCount());
        String details = replay.harness.alerts.alerts().get(0).details();
        assertTrue(details.startsWith("offset="), details);
        assertTrue(details.contains(" ticks=1 uncertainty="), details);
        assertFalse(replay.harness.setbacks.targetRequests().isEmpty(),
                "MOVEMENT violation at setback-vl goes through the packet-level setback path");
        assertEquals("speed", replay.harness.setbacks.targetRequests().get(0).reason());

        assertEquals(0, replay.check(FlyCheck.class).violationCount(), "no vertical anomaly");
        assertEquals(0, replay.check(NoFallCheck.class).violationCount(), "ground claim is honest");
    }
}
