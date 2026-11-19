package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PredictionCheck (cumulative position error)")
class PredictionCheckTest {

    private record Fixture(TestContexts.Harness harness, PlayerData player, PredictionCheck check) {
        void simulate(double cumulativeOffset, int cumulativeTicks) {
            player.simulation().complete(0, 0, 0.1, 0, 0, 0.0, true, 1, 0, 0.0);
            player.simulation().completeCumulative(cumulativeOffset, cumulativeTicks);
            player.checks().dispatchPosition(player.movementUpdate());
        }
    }

    private static Fixture fixture() {
        TestContexts.Harness harness = TestContexts.builder().module(new MovementChecksModule()).build();
        PlayerData player = harness.newPlayer();
        return new Fixture(harness, player, player.checks().get(PredictionCheck.class));
    }

    @Test
    @DisplayName("descriptor: MOVEMENT, experimental, registered by the module")
    void descriptor() {
        Fixture fixture = fixture();
        assertNotNull(fixture.check());
        assertEquals(CheckCategory.MOVEMENT, PredictionCheck.DESCRIPTOR.category());
        assertTrue(PredictionCheck.DESCRIPTOR.experimental());
    }

    @Test
    @DisplayName("short chains and explained packets never flag, even with a large offset")
    void ignoredWithoutEnoughTicks() {
        Fixture fixture = fixture();
        for (int i = 0; i < 10; i++) {
            fixture.simulate(0.4, PredictionCheck.DEFAULT_MIN_TICKS - 1);
        }
        assertEquals(0, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("a sustained offset above the threshold flags after the buffer")
    void sustainedOffsetFlags() {
        Fixture fixture = fixture();
        fixture.simulate(0.2, 30);
        assertTrue(fixture.check().buffer() > 0.0, "first sample raises the buffer");

        for (int i = 0; i < 3; i++) {
            fixture.simulate(0.2, 30);
        }
        assertEquals(1, fixture.check().violationCount());
    }

    @Test
    @DisplayName("an offset below the threshold decays the buffer and never flags")
    void smallOffsetIsLegit() {
        Fixture fixture = fixture();
        for (int i = 0; i < 20; i++) {
            fixture.simulate(0.05, 30);
        }
        assertEquals(0, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("a SKIPPED simulation is never judged")
    void skippedSimulationIsIgnored() {
        Fixture fixture = fixture();
        for (int i = 0; i < 10; i++) {
            fixture.player().simulation().skip("world-unknown");
            fixture.player().checks().dispatchPosition(fixture.player().movementUpdate());
        }
        assertEquals(0, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer());
    }
}
