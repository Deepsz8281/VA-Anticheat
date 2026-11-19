package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Baritone (pitch-only rotations off the mouse grid)")
class BaritoneCheckTest {

    private final TestContexts.Harness harness = TestContexts.builder()
            .check(BaritoneCheck.DESCRIPTOR, BaritoneCheck::new)
            .build();
    private final PlayerData player = harness.newPlayer();
    private final BaritoneCheck check = player.checks().get(BaritoneCheck.class);

    @Test
    @DisplayName("mouse: pitch changes on a 0.15 degree grid never flag")
    void mouse() {
        float pitch = 0f;
        for (int i = 0; i < 200; i++) {
            float delta = 0.15f * (1 + i % 5);
            check.rotation(0f, pitch, pitch + delta);
            pitch += i % 20 < 10 ? delta : -delta;
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("bot: smooth interpolated pitch steps with a fixed yaw flag")
    void bot() {
        float pitch = 10f;
        for (int i = 0; i < 200; i++) {
            float delta = 0.1f + 0.37f * (float) Math.abs(Math.sin(i * 0.731));
            float next = i % 40 < 20 ? pitch + delta : pitch - delta;
            check.rotation(0f, pitch, next);
            pitch = next;
        }
        assertTrue(check.violationCount() > 0, harness.alerts.alerts()::toString);
    }
}
