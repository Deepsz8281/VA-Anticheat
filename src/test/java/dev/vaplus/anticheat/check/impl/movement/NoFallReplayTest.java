package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.testutil.replay.scenarios.CheckReplay;
import dev.vaplus.anticheat.testutil.replay.scenarios.Frame;
import dev.vaplus.anticheat.testutil.replay.scenarios.MovementScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NoFallCheck replay regression")
class NoFallReplayTest {

    @Test
    @DisplayName("a correct fall that claims ground every tick flags on the 3rd packet (buffer limit 2)")
    void fakeGround() {
        List<Frame> frames = MovementScenarios.fakeGroundFall(72.0);
        assertTrue(frames.size() > 6, "the fall must be long enough to exceed the buffer");
        CheckReplay replay = MovementReplays.inAir(72.0);
        NoFallCheck nofall = replay.check(NoFallCheck.class);
        replay.play(frames.get(0)).play(frames.get(1));
        assertEquals(0, nofall.violationCount());
        replay.play(frames.get(2));
        assertEquals(1, nofall.violationCount());
        replay.play(frames.subList(3, frames.size()));
        MovementReplays.assertAllFramesSimulated(replay, frames.size());

        // every airborne frame is a spoof; the final landing frame claims ground legitimately
        assertEquals(frames.size() - 1 - 2, nofall.violationCount());
        String details = replay.harness.alerts.alerts().get(0).details();
        assertTrue(details.startsWith("spoofed-ground claimed=true predicted=false"), details);
        assertEquals(0, replay.check(SpeedCheck.class).violationCount(), "movement itself is legit");
        assertEquals(0, replay.check(FlyCheck.class).violationCount(), "movement itself is legit");
    }
}
