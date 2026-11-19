package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NoFallCheck (hand-built SimulationResults)")
class NoFallCheckTest {

    private static final double UNC = 1.0E-4;
    private static final double FALL = -0.0784;

    /** Explained fall step, but the client claims ground. */
    private static void spoof(ResultFeeder feeder) {
        feeder.complete(0, FALL, 0, true, 0, FALL, 0, false, 1, UNC);
    }

    @Test
    @DisplayName("descriptor: id nofall, MOVEMENT, registered by the module")
    void descriptor() {
        ResultFeeder feeder = new ResultFeeder();
        assertNotNull(feeder.check(NoFallCheck.class));
        assertEquals("nofall", NoFallCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, NoFallCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("matching claims (ground/ground, air/air) never raise the buffer")
    void legit() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 10; i++) {
            feeder.complete(0, 0, 0.1, true, 0, 0, 0.1, true, 1, UNC);
            feeder.complete(0, FALL, 0, false, 0, FALL, 0, false, 1, UNC);
        }
        assertEquals(0.0, feeder.check(NoFallCheck.class).buffer());
    }

    @Test
    @DisplayName("claiming ground while predicted airborne flags after the buffer limit (2.0)")
    void spoofFlags() {
        ResultFeeder feeder = new ResultFeeder();
        NoFallCheck check = feeder.check(NoFallCheck.class);
        spoof(feeder);
        spoof(feeder);
        assertEquals(0, check.violationCount());
        spoof(feeder);
        assertEquals(1, check.violationCount());
        String details = feeder.alerts().get(0).details();
        assertTrue(details.startsWith("spoofed-ground claimed=true predicted=false dy=-0.0784 offset=0.0000 ticks=1"),
                details);
    }

    @Test
    @DisplayName("claiming airborne while predicted on ground is ignored unless flag-missing-ground is set")
    void missingGround() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 10; i++) {
            feeder.complete(0, 0, 0, false, 0, 0, 0, true, 1, UNC);
        }
        assertEquals(0, feeder.check(NoFallCheck.class).violationCount());

        ResultFeeder strict = new ResultFeeder().options(NoFallCheck.DESCRIPTOR, Map.of("flag-missing-ground", true));
        for (int i = 0; i < 3; i++) {
            strict.complete(0, 0, 0, false, 0, 0, 0, true, 1, UNC);
        }
        assertEquals(1, strict.check(NoFallCheck.class).violationCount());
        assertTrue(strict.alerts().get(0).details().startsWith("missing-ground claimed=false predicted=true"));
    }

    @Test
    @DisplayName("an unexplained movement (offset > max-offset) leaves the ground verdict to Speed/Fly")
    void untrustedPrediction() {
        ResultFeeder feeder = new ResultFeeder();
        NoFallCheck check = feeder.check(NoFallCheck.class);
        spoof(feeder);
        for (int i = 0; i < 10; i++) {
            feeder.complete(0, 0, 0, true, 0, -0.5, 0, false, 1, UNC);
        }
        assertEquals(1.0, check.buffer(), "neither raised nor lowered");
        assertEquals(0, check.violationCount());

        ResultFeeder loose = new ResultFeeder().options(NoFallCheck.DESCRIPTOR, Map.of("max-offset", 1.0));
        for (int i = 0; i < 3; i++) {
            loose.complete(0, 0, 0, true, 0, -0.5, 0, false, 1, UNC);
        }
        assertEquals(1, loose.check(NoFallCheck.class).violationCount());
    }

    @Test
    @DisplayName("SKIPPED results and an active JOIN exemption are ignored")
    void skipsAndExemptions() {
        ResultFeeder feeder = new ResultFeeder();
        for (int i = 0; i < 10; i++) {
            feeder.skipped(0, FALL, 0, true, "world-unknown");
        }
        assertEquals(0.0, feeder.check(NoFallCheck.class).buffer());

        ResultFeeder joined = new ResultFeeder();
        joined.player.exemptions().grant(ExemptionCause.JOIN, 100);
        for (int i = 0; i < 10; i++) {
            spoof(joined);
        }
        assertEquals(0.0, joined.check(NoFallCheck.class).buffer());
    }
}
