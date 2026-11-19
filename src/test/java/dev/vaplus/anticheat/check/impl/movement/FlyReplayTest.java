package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.testutil.replay.scenarios.CheckReplay;
import dev.vaplus.anticheat.testutil.replay.scenarios.Frame;
import dev.vaplus.anticheat.testutil.replay.scenarios.MovementScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FlyCheck replay regression")
class FlyReplayTest {

    @Test
    @DisplayName("hovering 8 blocks above the floor flags on the 4th packet (buffer limit 3)")
    void hover() {
        List<Frame> frames = MovementScenarios.hover(20);
        CheckReplay replay = MovementReplays.inAir(72.0);
        FlyCheck fly = replay.check(FlyCheck.class);
        for (int i = 0; i < 3; i++) {
            replay.play(frames.get(i));
        }
        assertEquals(0, fly.violationCount());
        replay.play(frames.get(3));
        assertEquals(1, fly.violationCount());
        replay.play(frames.subList(4, frames.size()));
        MovementReplays.assertAllFramesSimulated(replay, frames.size());

        String details = replay.harness.alerts.alerts().get(0).details();
        assertTrue(details.startsWith("up offset="), details);
        assertTrue(details.contains(" air=4 "), details);
        assertTrue(fly.violationCount() >= 10, "sustained hover keeps flagging: " + fly.violationCount());
        assertEquals(0, replay.check(SpeedCheck.class).violationCount());
        assertEquals(0, replay.check(NoFallCheck.class).violationCount(), "airborne is claimed honestly");
    }

    @Test
    @DisplayName("ascending 0.1 blocks per tick in mid-air flags")
    void ascend() {
        List<Frame> frames = MovementScenarios.ascend(12);
        CheckReplay replay = MovementReplays.inAir(68.0).play(frames);
        MovementReplays.assertAllFramesSimulated(replay, frames.size());
        FlyCheck fly = replay.check(FlyCheck.class);
        assertTrue(fly.violationCount() >= 1, "violations " + fly.violationCount());
        assertTrue(replay.harness.alerts.alerts().get(0).details().startsWith("up "));
        assertEquals(0, replay.check(SpeedCheck.class).violationCount());
    }
}
