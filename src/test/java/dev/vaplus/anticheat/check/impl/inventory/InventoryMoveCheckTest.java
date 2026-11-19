package dev.vaplus.anticheat.check.impl.inventory;

import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InventoryMove (movement and rotation inside a container screen)")
class InventoryMoveCheckTest {

    private static final long TICK_BASE = 1000L;

    private record Fixture(Harness harness, PlayerData player, InventoryMoveCheck check) {

        /** Opens a server container and acknowledges it at {@code tick}. */
        void openAcked(int id, long tick) {
            int generation = check.windowOpened(id);
            check.openAcknowledged(generation, tick);
        }

        /** One tick with key state held / released and nothing else going on. */
        void keys(long tick, boolean forward, boolean jump) {
            check.evaluate(tick, false, true, forward, jump, false, 0.0, true);
        }

        void idle(long from, int count) {
            for (long t = from; t < from + count; t++) {
                keys(t, false, false);
            }
        }

        int flags() {
            return check.violationCount();
        }
    }

    private static Fixture fixture() {
        Harness harness = TestContexts.builder()
                .check(InventoryMoveCheck.DESCRIPTOR, InventoryMoveCheck::new)
                .build();
        PlayerData player = harness.newPlayer();
        return new Fixture(harness, player, player.checks().get(InventoryMoveCheck.class));
    }

    // ---- cheats that must flag ------------------------------------------------------------------

    @Test
    @DisplayName("movement keys stuck down in an acknowledged container flag")
    void stuckKeys() {
        Fixture f = fixture();
        f.openAcked(3, TICK_BASE);
        for (long t = TICK_BASE; t < TICK_BASE + 60; t++) {
            f.keys(t, true, false);
        }
        assertTrue(f.flags() >= 1, "stuck forward key must flag");
    }

    @Test
    @DisplayName("walking while the own inventory is open (inferred from a window-0 click) flags")
    void inferredInventory() {
        Fixture f = fixture();
        f.check().onClick(0, TICK_BASE);
        for (long t = TICK_BASE; t < TICK_BASE + 40; t++) {
            f.keys(t, true, false);
            if (t % 5 == 0) {
                f.check().onClick(0, t);
            }
        }
        assertTrue(f.flags() >= 1);
    }

    @Test
    @DisplayName("turning the camera while clicking in a container flags")
    void rotationWhileClicking() {
        Fixture f = fixture();
        f.openAcked(2, TICK_BASE);
        for (long t = TICK_BASE; t < TICK_BASE + 40; t++) {
            f.check().onClick(2, t);
            f.check().evaluate(t, false, true, false, false, true, 0.0, true);
        }
        assertTrue(f.flags() >= 1);
    }

    @Test
    @DisplayName("sustained non-decaying walking speed while clicking flags clients without key states")
    void legacyWalkingSpeed() {
        Fixture f = fixture();
        f.openAcked(1, TICK_BASE);
        for (long t = TICK_BASE; t < TICK_BASE + 60; t++) {
            f.check().onClick(1, t);
            f.check().evaluate(t, false, false, false, false, false, 0.216, true);
        }
        assertTrue(f.flags() >= 1);
    }

    @Test
    @DisplayName("a click proves an unacknowledged open and then walking flags")
    void clickProvesOpen() {
        Fixture f = fixture();
        f.check().windowOpened(4); // never acknowledged
        f.check().onClick(4, TICK_BASE);
        assertTrue(f.check().windowProven());
        for (long t = TICK_BASE; t < TICK_BASE + 40; t++) {
            f.keys(t, true, false);
        }
        assertTrue(f.flags() >= 1);
    }

    // ---- legitimate behaviour that must not flag --------------------------------------------------

    @Test
    @DisplayName("keys released after opening never flag, however long the screen stays open")
    void releasedKeys() {
        Fixture f = fixture();
        f.openAcked(3, TICK_BASE);
        f.idle(TICK_BASE, 2000);
        assertEquals(0, f.flags());
        assertEquals(0.0, f.check().buffer());
    }

    @Test
    @DisplayName("keys still held during the grace right after the open are ignored")
    void graceAfterOpen() {
        Fixture f = fixture();
        f.openAcked(3, TICK_BASE);
        for (long t = TICK_BASE; t < TICK_BASE + InventoryMoveCheck.DEFAULT_GRACE_TICKS; t++) {
            f.keys(t, true, true);
        }
        f.idle(TICK_BASE + InventoryMoveCheck.DEFAULT_GRACE_TICKS, 100);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("an open that was never acknowledged is not judged")
    void unacknowledgedOpen() {
        Fixture f = fixture();
        f.check().windowOpened(5);
        for (long t = TICK_BASE; t < TICK_BASE + 200; t++) {
            f.keys(t, true, true);
            f.check().evaluate(t, false, true, true, true, true, 0.3, true);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a late acknowledgement of a closed window is ignored")
    void staleAck() {
        Fixture f = fixture();
        int generation = f.check().windowOpened(5);
        f.check().closeWindow();
        f.check().openAcknowledged(generation, TICK_BASE);
        assertFalse(f.check().windowProven());
        for (long t = TICK_BASE; t < TICK_BASE + 100; t++) {
            f.keys(t, true, false);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a replaced window invalidates the older acknowledgement")
    void replacedWindow() {
        Fixture f = fixture();
        int first = f.check().windowOpened(5);
        int second = f.check().windowOpened(6);
        f.check().openAcknowledged(first, TICK_BASE);
        assertFalse(f.check().windowProven());
        f.check().openAcknowledged(second, TICK_BASE);
        assertTrue(f.check().windowProven());
        assertEquals(6, f.check().windowId());
    }

    @Test
    @DisplayName("movement after the container is closed is never judged")
    void afterClose() {
        Fixture f = fixture();
        f.openAcked(3, TICK_BASE);
        f.check().closeWindow();
        for (long t = TICK_BASE; t < TICK_BASE + 200; t++) {
            f.keys(t, true, true);
            f.check().evaluate(t, false, true, true, true, true, 0.3, true);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("the inferred inventory expires without clicks, so later movement is not judged")
    void inferredExpires() {
        Fixture f = fixture();
        f.check().onClick(0, TICK_BASE);
        long afterIdle = TICK_BASE + InventoryMoveCheck.DEFAULT_INFERRED_IDLE_TICKS + 1;
        f.keys(afterIdle, true, false);
        assertEquals(InventoryMoveCheck.NO_WINDOW, f.check().windowId());
        for (long t = afterIdle; t < afterIdle + 100; t++) {
            f.keys(t, true, false);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a click on an unknown non-zero window does not open anything")
    void unknownWindowClick() {
        Fixture f = fixture();
        f.check().onClick(7, TICK_BASE);
        assertEquals(InventoryMoveCheck.NO_WINDOW, f.check().windowId());
        for (long t = TICK_BASE; t < TICK_BASE + 100; t++) {
            f.keys(t, true, false);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("momentum after opening decays and never qualifies as sustained motion")
    void momentumDecays() {
        Fixture f = fixture();
        f.openAcked(1, TICK_BASE);
        // sprint-jump momentum on ice (friction 0.89 per tick) from 0.6 b/t
        double speed = 0.6;
        for (long t = TICK_BASE; t < TICK_BASE + 200; t++) {
            f.check().onClick(1, t);
            f.check().evaluate(t, false, false, false, false, false, speed, true);
            speed *= 0.89;
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("knockback speed decaying on normal ground never flags")
    void knockbackDecay() {
        Fixture f = fixture();
        f.openAcked(1, TICK_BASE);
        double speed = 0.8;
        for (long t = TICK_BASE; t < TICK_BASE + 100; t++) {
            f.check().evaluate(t, false, false, false, false, false, speed, true);
            speed *= 0.546;
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("excluded ticks (vehicle, teleport, exemption) are never judged")
    void excluded() {
        Fixture f = fixture();
        f.openAcked(3, TICK_BASE);
        for (long t = TICK_BASE; t < TICK_BASE + 100; t++) {
            f.check().evaluate(t, true, true, true, true, true, 0.3, true);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("steady motion in air or water (not usable) never counts")
    void motionNotUsable() {
        Fixture f = fixture();
        f.openAcked(1, TICK_BASE);
        for (long t = TICK_BASE; t < TICK_BASE + 100; t++) {
            f.check().evaluate(t, false, false, false, false, false, 0.3, false);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a single stray rotation or one-tick auto-jump stays below the buffer")
    void singleGlitch() {
        Fixture f = fixture();
        f.openAcked(2, TICK_BASE);
        f.check().evaluate(TICK_BASE + 10, false, true, false, false, true, 0.0, true);
        f.check().evaluate(TICK_BASE + 11, false, true, false, true, false, 0.0, true);
        f.idle(TICK_BASE + 12, 200);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("rotation far from the last click is not judged for the inferred inventory")
    void inferredRotationNeedsClick() {
        Fixture f = fixture();
        f.check().onClick(0, TICK_BASE);
        for (long t = TICK_BASE + 20; t < TICK_BASE + 90; t++) {
            f.check().evaluate(t, false, true, false, false, true, 0.0, true);
        }
        assertEquals(0, f.flags());
    }

    // ---- hooks through the real player ----------------------------------------------------------

    @Test
    @DisplayName("stuck input read from the player state flags through onClientTick")
    void viaPlayerState() {
        Fixture f = fixture();
        f.player().enableTickEndMode();
        int generation = f.check().windowOpened(3);
        f.check().openAcknowledged(generation, f.player().clientTicks());
        f.player().pose().applyInput(true, false, false, false, false, false, true, 0L);
        for (int i = 0; i < 60; i++) {
            f.player().onClientTick();
        }
        assertTrue(f.flags() >= 1);
    }

    @Test
    @DisplayName("released input through onClientTick does not flag and a rotation callback is consumed once")
    void releasedViaPlayerState() {
        Fixture f = fixture();
        f.player().enableTickEndMode();
        int generation = f.check().windowOpened(3);
        f.check().openAcknowledged(generation, f.player().clientTicks());
        f.player().pose().applyInput(false, false, false, false, false, false, false, 0L);
        LookChange look = f.player().lookChange();
        look.assign(0f, 0f, 30f, 0f, false, 0L);
        f.check().onRotation(look);
        for (int i = 0; i < 100; i++) {
            f.player().onClientTick();
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("an active teleport exemption suppresses judgement")
    void teleportExemption() {
        Fixture f = fixture();
        f.player().enableTickEndMode();
        int generation = f.check().windowOpened(3);
        f.check().openAcknowledged(generation, f.player().clientTicks());
        f.player().pose().applyInput(true, false, false, false, false, false, true, 0L);
        for (int i = 0; i < 60; i++) {
            f.player().exemptions().grant(ExemptionCause.TELEPORT, 2);
            f.player().onClientTick();
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("descriptor: id, category, experimental")
    void descriptor() {
        assertEquals("inventorymove", InventoryMoveCheck.DESCRIPTOR.id());
        assertEquals("INVENTORY", InventoryMoveCheck.DESCRIPTOR.category().name());
        assertTrue(InventoryMoveCheck.DESCRIPTOR.experimental());
    }
}
