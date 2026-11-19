package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FlyCheck (hand-built SimulationResults)")
class FlyCheckTest {

    private static final double UNC = 1.0E-4;
    /** (0 - 0.08) * 0.98: first tick of a fall from rest. */
    private static final double FALL = -0.0784;

    /** Hover: reported dy 0 while the best candidate falls. */
    private static void hover(ResultFeeder feeder) {
        feeder.complete(0, 0, 0, false, 0, FALL, 0, false, 1, UNC);
    }

    @Test
    @DisplayName("descriptor: id fly, MOVEMENT, registered by the module")
    void descriptor() {
        ResultFeeder feeder = new ResultFeeder();
        assertNotNull(feeder.check(FlyCheck.class));
        assertEquals("fly", FlyCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, FlyCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("explained falling and jumping never raise the buffer")
    void legit() {
        ResultFeeder feeder = new ResultFeeder();
        feeder.complete(0, 0.42, 0, false, 0, 0.42, 0, false, 1, UNC);
        feeder.complete(0, FALL, 0, false, 0, FALL + 1.0E-6, 0, false, 1, UNC);
        feeder.complete(0, -0.5, 0, true, 0, -0.5, 0, true, 1, UNC);
        assertEquals(0.0, feeder.check(FlyCheck.class).buffer());
    }

    @Test
    @DisplayName("hovering (positive vertical offset) flags after the buffer limit with air-time details")
    void hoverFlags() {
        ResultFeeder feeder = new ResultFeeder();
        FlyCheck check = feeder.check(FlyCheck.class);
        for (int i = 0; i < 3; i++) {
            hover(feeder);
        }
        assertEquals(0, check.violationCount());
        hover(feeder);
        assertEquals(1, check.violationCount());
        assertEquals(4, check.airPackets());
        String details = feeder.alerts().get(0).details();
        assertTrue(details.startsWith("up offset=0.0784 limit=0.0301 dy=0.0000 predicted=-0.0784 air=4 ticks=1"),
                details);
    }

    @Test
    @DisplayName("falling clearly faster than predicted flags as 'down'; check-downward=false disables it")
    void downward() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 4; i++) {
            feeder.complete(0, -1.0, 0, false, 0, FALL, 0, false, 1, UNC);
        }
        assertEquals(1, feeder.check(FlyCheck.class).violationCount());
        assertTrue(feeder.alerts().get(0).details().startsWith("down offset=-0.9216 limit=0.1001"));

        ResultFeeder off = new ResultFeeder().options(FlyCheck.DESCRIPTOR, Map.of("check-downward", false));
        for (int i = 0; i < 10; i++) {
            off.complete(0, -1.0, 0, false, 0, FALL, 0, false, 1, UNC);
        }
        assertEquals(0.0, off.check(FlyCheck.class).buffer());
    }

    @Test
    @DisplayName("a small downward offset inside downward-threshold is tolerated")
    void smallDownwardTolerated() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 10; i++) {
            feeder.complete(0, FALL - 0.05, 0, false, 0, FALL, 0, false, 1, UNC);
        }
        assertEquals(0.0, feeder.check(FlyCheck.class).buffer());
    }

    @Test
    @DisplayName("uncertainty widens the upward limit; SKIPPED results are ignored")
    void uncertaintyAndSkips() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 10; i++) {
            feeder.complete(0, 0, 0, false, 0, -0.05, 0, false, 1, 0.03);
            feeder.skipped(0, 1.0, 0, false, "flying");
        }
        assertEquals(0.0, feeder.check(FlyCheck.class).buffer());
        assertEquals(0, feeder.check(FlyCheck.class).airPackets(), "a skip resets the airborne counter");
    }

    @Test
    @DisplayName("an active declared exemption (RESPAWN) suppresses judging")
    void exemption() {
        ResultFeeder feeder = new ResultFeeder();
        feeder.player.exemptions().grant(ExemptionCause.RESPAWN, 100);
        for (int i = 0; i < 10; i++) {
            hover(feeder);
        }
        assertEquals(0.0, feeder.check(FlyCheck.class).buffer());
    }

    @Test
    @DisplayName("upward-threshold option raises the tolerance")
    void upwardThresholdOption() {
        ResultFeeder feeder = new ResultFeeder().options(FlyCheck.DESCRIPTOR, Map.of("upward-threshold", 0.1));
        for (int i = 0; i < 10; i++) {
            hover(feeder);
        }
        assertEquals(0, feeder.check(FlyCheck.class).violationCount());
    }
}
