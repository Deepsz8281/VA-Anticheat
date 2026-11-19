package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpeedCheck (hand-built SimulationResults)")
class SpeedCheckTest {

    private static final double UNC = 1.0E-4;

    /** Reported 0.5 forward, best candidate 0.28 forward: horizontal offset 0.22. */
    private static void fast(ResultFeeder feeder) {
        feeder.complete(0, 0, 0.5, true, 0, 0, 0.28, true, 1, UNC);
    }

    @Test
    @DisplayName("descriptor: id speed, MOVEMENT (setback capable), registered by the module")
    void descriptor() {
        ResultFeeder feeder = new ResultFeeder();
        SpeedCheck check = feeder.check(SpeedCheck.class);
        assertNotNull(check);
        assertEquals("speed", SpeedCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, SpeedCheck.DESCRIPTOR.category());
        assertTrue(SpeedCheck.DESCRIPTOR.category().setbackCapable());
        assertEquals(SpeedCheck.DEFAULT_THRESHOLD, check.threshold());
        assertTrue((SpeedCheck.DESCRIPTOR.exemptionMask() & ExemptionCause.TELEPORT.bit()) != 0);
    }

    @Test
    @DisplayName("explained movement never raises the buffer")
    void legit() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 50; i++) {
            feeder.complete(0, 0, 0.2806, true, 0, 0, 0.2806 - 1.0E-6, true, 1, UNC);
        }
        SpeedCheck check = feeder.check(SpeedCheck.class);
        assertEquals(0.0, check.buffer());
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("an unexplained horizontal offset flags once the buffer exceeds its limit (3.0)")
    void flagsAfterBuffer() {
        ResultFeeder feeder = new ResultFeeder();
        SpeedCheck check = feeder.check(SpeedCheck.class);
        for (int i = 0; i < 3; i++) {
            fast(feeder);
        }
        assertEquals(3.0, check.buffer());
        assertEquals(0, check.violationCount(), "buffer at the limit does not flag yet");
        fast(feeder);
        assertEquals(1, check.violationCount());
        String details = feeder.alerts().get(0).details();
        assertTrue(details.startsWith("offset=0.2200 limit=0.0301"), details);
        assertTrue(details.contains("moved=0.5000 predicted=0.2800 ticks=1 uncertainty=0.0001"), details);
        assertFalse(feeder.harness.setbacks.requests().isEmpty() && feeder.harness.setbacks.targetRequests().isEmpty(),
                "MOVEMENT violations at setback-vl request a setback");
    }

    @Test
    @DisplayName("the engine uncertainty widens the limit")
    void uncertaintyWidensLimit() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 10; i++) {
            // offset 0.05 > 0.03 threshold, but within 0.03 + 0.03 uncertainty
            feeder.complete(0, 0, 0.33, true, 0, 0, 0.28, true, 1, 0.03);
        }
        assertEquals(0.0, feeder.check(SpeedCheck.class).buffer());
    }

    @Test
    @DisplayName("legitimate packets decay the buffer by buffer.decay (0.1)")
    void decay() {
        ResultFeeder feeder = new ResultFeeder();
        SpeedCheck check = feeder.check(SpeedCheck.class);
        fast(feeder);
        fast(feeder);
        feeder.complete(0, 0, 0.1, true, 0, 0, 0.1, true, 1, UNC);
        assertEquals(1.9, check.buffer(), 1.0E-9);
    }

    @Test
    @DisplayName("SKIPPED and NOT_RUN results are ignored without touching the buffer")
    void skippedIgnored() {
        ResultFeeder feeder = new ResultFeeder();
        SpeedCheck check = feeder.check(SpeedCheck.class);
        fast(feeder);
        for (int i = 0; i < 20; i++) {
            feeder.skipped(0, 0, 3.0, true, "world-unknown");
            feeder.notRun(0, 0, 3.0, true);
        }
        assertEquals(1.0, check.buffer());
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("clearly slower than predicted is not speed (ignore-slowdown), unless disabled")
    void slowdown() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 10; i++) {
            feeder.complete(0, 0, 0.0, true, 0, 0, 0.25, true, 1, UNC);
        }
        assertEquals(0, feeder.check(SpeedCheck.class).violationCount());

        ResultFeeder strict = new ResultFeeder().options(SpeedCheck.DESCRIPTOR, Map.of("ignore-slowdown", false));
        for (int i = 0; i < 4; i++) {
            strict.complete(0, 0, 0.0, true, 0, 0, 0.25, true, 1, UNC);
        }
        assertEquals(1, strict.check(SpeedCheck.class).violationCount());
    }

    @Test
    @DisplayName("same speed in an impossible direction is still flagged")
    void wrongDirection() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 4; i++) {
            feeder.complete(0.25, 0, 0, true, 0, 0, 0.25, true, 1, UNC);
        }
        assertEquals(1, feeder.check(SpeedCheck.class).violationCount());
    }

    @Test
    @DisplayName("an active declared exemption (TELEPORT) suppresses judging")
    void exemption() {
        ResultFeeder feeder = new ResultFeeder();
        feeder.player.exemptions().grant(ExemptionCause.TELEPORT, 100);
        for (int i = 0; i < 10; i++) {
            fast(feeder);
        }
        assertEquals(0.0, feeder.check(SpeedCheck.class).buffer());
    }

    @Test
    @DisplayName("options: threshold is applied, invalid values fall back to the default")
    void options() {
        ResultFeeder loose = new ResultFeeder().options(SpeedCheck.DESCRIPTOR, Map.of("threshold", 0.5));
        assertEquals(0.5, loose.check(SpeedCheck.class).threshold());
        for (int i = 0; i < 10; i++) {
            fast(loose);
        }
        assertEquals(0, loose.check(SpeedCheck.class).violationCount());

        ResultFeeder invalid = new ResultFeeder().options(SpeedCheck.DESCRIPTOR, Map.of("threshold", -1.0));
        assertEquals(SpeedCheck.DEFAULT_THRESHOLD, invalid.check(SpeedCheck.class).threshold());
        ResultFeeder text = new ResultFeeder().options(SpeedCheck.DESCRIPTOR, Map.of("threshold", "abc"));
        assertEquals(SpeedCheck.DEFAULT_THRESHOLD, text.check(SpeedCheck.class).threshold());
        ResultFeeder nan = new ResultFeeder().options(SpeedCheck.DESCRIPTOR, Map.of("threshold", Double.NaN));
        assertEquals(SpeedCheck.DEFAULT_THRESHOLD, nan.check(SpeedCheck.class).threshold());
    }
}
