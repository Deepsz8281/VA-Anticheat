package dev.vaplus.anticheat.player.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExemptionState load holds (PLAYER_LOADED, L11)")
class ExemptionLoadHoldTest {

    private static void tick(ExemptionState state, int ticks) {
        for (int i = 0; i < ticks; i++) {
            state.tick();
        }
    }

    @Test
    @DisplayName("a load hold keeps the cause active until releaseLoadHolds")
    void holdUntilReleased() {
        ExemptionState state = new ExemptionState();
        state.holdUntilLoaded(ExemptionCause.JOIN);
        assertTrue(state.isActive(ExemptionCause.JOIN));
        assertTrue(state.awaitingLoad(ExemptionCause.JOIN));
        tick(state, 30);
        assertTrue(state.isActive(ExemptionCause.JOIN));

        assertEquals(ExemptionCause.JOIN.bit(), state.releaseLoadHolds());
        assertFalse(state.isActive(ExemptionCause.JOIN));
        assertFalse(state.awaitingLoad(ExemptionCause.JOIN));
        assertEquals(0L, state.releaseLoadHolds(), "a second release is a no-op");
    }

    @Test
    @DisplayName("the timed grant outlives an early release; the release does not shorten it")
    void grantSurvivesRelease() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.JOIN, 40);
        state.holdUntilLoaded(ExemptionCause.JOIN);
        tick(state, 5);
        state.releaseLoadHolds();
        assertTrue(state.isActive(ExemptionCause.JOIN));
        assertEquals(35, state.ticksLeft(ExemptionCause.JOIN));
        tick(state, 35);
        assertFalse(state.isActive(ExemptionCause.JOIN));
    }

    @Test
    @DisplayName("the hold outlives the timed grant while the client is still loading")
    void holdOutlivesGrant() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.JOIN, 10);
        state.holdUntilLoaded(ExemptionCause.JOIN);
        tick(state, 20);
        assertEquals(0, state.ticksLeft(ExemptionCause.JOIN));
        assertTrue(state.isActive(ExemptionCause.JOIN), "still loading: the hold keeps JOIN active");
        state.releaseLoadHolds();
        assertFalse(state.isActive(ExemptionCause.JOIN));
    }

    @Test
    @DisplayName("only one load hold per cause; it releases exactly its own count")
    void singleLoadHoldPerCause() {
        ExemptionState state = new ExemptionState();
        state.holdUntilLoaded(ExemptionCause.RESPAWN);
        state.holdUntilLoaded(ExemptionCause.RESPAWN);
        state.releaseLoadHolds();
        assertFalse(state.isActive(ExemptionCause.RESPAWN), "the duplicate request did not add a second hold");
    }

    @Test
    @DisplayName("releasing the load hold leaves a bracket hold of the same cause in place")
    void bracketHoldUnaffected() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.RESPAWN);
        state.holdUntilLoaded(ExemptionCause.RESPAWN);
        state.releaseLoadHolds();
        assertTrue(state.isActive(ExemptionCause.RESPAWN), "the bracket hold is still pending");
        state.release(ExemptionCause.RESPAWN);
        assertFalse(state.isActive(ExemptionCause.RESPAWN));
    }

    @Test
    @DisplayName("releases JOIN and RESPAWN load holds together and reports both")
    void releasesAll() {
        ExemptionState state = new ExemptionState();
        state.holdUntilLoaded(ExemptionCause.JOIN);
        state.holdUntilLoaded(ExemptionCause.RESPAWN);
        assertEquals(ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN), state.releaseLoadHolds());
        assertEquals(0L, state.activeMask());
    }

    @Test
    @DisplayName("without PLAYER_LOADED the hold expires after MAX_HOLD_TICKS, counted as a load expiry only")
    void expiryFallback() {
        ExemptionState state = new ExemptionState();
        state.holdUntilLoaded(ExemptionCause.JOIN);
        tick(state, ExemptionState.MAX_HOLD_TICKS);
        assertTrue(state.isActive(ExemptionCause.JOIN));
        state.tick();
        assertFalse(state.isActive(ExemptionCause.JOIN));
        assertFalse(state.awaitingLoad(ExemptionCause.JOIN));
        assertEquals(1L, state.expiredLoadHolds());
        assertEquals(0L, state.expiredHolds(), "slow loading is not a withheld bracket acknowledgement");
        assertEquals(0L, state.releaseLoadHolds(), "nothing left to release after the expiry");
        state.hold(ExemptionCause.JOIN);
        state.releaseLoadHolds();
        assertTrue(state.isActive(ExemptionCause.JOIN), "a later bracket hold is not consumed by a stale release");
    }

    @Test
    @DisplayName("an expiring cause with a bracket hold besides the load hold still counts as withheld")
    void expiryWithBracketHold() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.RESPAWN);
        state.holdUntilLoaded(ExemptionCause.RESPAWN);
        tick(state, ExemptionState.MAX_HOLD_TICKS + 1);
        assertFalse(state.isActive(ExemptionCause.RESPAWN));
        assertEquals(1L, state.expiredLoadHolds());
        assertEquals(1L, state.expiredHolds());
    }

    @Test
    @DisplayName("clear drops pending load holds")
    void clearDropsLoadHolds() {
        ExemptionState state = new ExemptionState();
        state.holdUntilLoaded(ExemptionCause.JOIN);
        state.clear();
        assertFalse(state.awaitingLoad(ExemptionCause.JOIN));
        assertEquals(0L, state.releaseLoadHolds());
    }
}
