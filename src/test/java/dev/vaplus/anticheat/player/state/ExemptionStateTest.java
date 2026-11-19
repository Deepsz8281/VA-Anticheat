package dev.vaplus.anticheat.player.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExemptionState")
class ExemptionStateTest {

    @Test
    @DisplayName("nothing is active initially")
    void initial() {
        ExemptionState state = new ExemptionState();
        assertEquals(0L, state.activeMask());
        assertTrue(state.activeCauses().isEmpty());
        for (ExemptionCause cause : ExemptionCause.values()) {
            assertFalse(state.isActive(cause));
        }
    }

    @Test
    @DisplayName("grant is active for exactly the given number of ticks")
    void grantExpires() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.TELEPORT, 3);
        assertTrue(state.isActive(ExemptionCause.TELEPORT));
        assertEquals(3, state.ticksLeft(ExemptionCause.TELEPORT));
        state.tick();
        state.tick();
        assertTrue(state.isActive(ExemptionCause.TELEPORT));
        state.tick();
        assertFalse(state.isActive(ExemptionCause.TELEPORT));
        assertEquals(0, state.ticksLeft(ExemptionCause.TELEPORT));
        state.tick();
        assertEquals(0, state.ticksLeft(ExemptionCause.TELEPORT), "never negative");
    }

    @Test
    @DisplayName("grant never shortens a running grant but extends it")
    void grantMaxSemantics() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.KNOCKBACK, 5);
        state.grant(ExemptionCause.KNOCKBACK, 2);
        assertEquals(5, state.ticksLeft(ExemptionCause.KNOCKBACK));
        state.grant(ExemptionCause.KNOCKBACK, 9);
        assertEquals(9, state.ticksLeft(ExemptionCause.KNOCKBACK));
    }

    @Test
    @DisplayName("grant of zero or negative ticks does not activate")
    void nonPositiveGrant() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.JOIN, 0);
        state.grant(ExemptionCause.RESPAWN, -4);
        assertEquals(0L, state.activeMask());
    }

    @Test
    @DisplayName("hold stays active until released, but at most MAX_HOLD_TICKS ticks after the first hold")
    void holdAndRelease() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.VEHICLE);
        for (int i = 0; i < ExemptionState.MAX_HOLD_TICKS; i++) {
            state.tick();
        }
        assertTrue(state.isActive(ExemptionCause.VEHICLE));
        state.release(ExemptionCause.VEHICLE);
        assertFalse(state.isActive(ExemptionCause.VEHICLE));
        assertEquals(0, state.expiredHolds());
    }

    @Test
    @DisplayName("a hold whose release never arrives expires and is counted")
    void holdExpires() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.VEHICLE);
        for (int i = 0; i < ExemptionState.MAX_HOLD_TICKS; i++) {
            state.tick();
        }
        assertTrue(state.isActive(ExemptionCause.VEHICLE));
        state.tick();
        assertFalse(state.isActive(ExemptionCause.VEHICLE), "61st tick expires the hold");
        assertEquals(1, state.expiredHolds());
        state.release(ExemptionCause.VEHICLE);
        assertFalse(state.isActive(ExemptionCause.VEHICLE), "a late release must not underflow or reactivate");
        state.hold(ExemptionCause.VEHICLE);
        assertTrue(state.isActive(ExemptionCause.VEHICLE), "a fresh hold works after expiry");
        state.release(ExemptionCause.VEHICLE);
        assertFalse(state.isActive(ExemptionCause.VEHICLE));
    }

    @Test
    @DisplayName("hold expiry is counted from the first hold and a second hold does not extend it; all nested holds expire together")
    void holdExpiryFromFirstHold() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.ABILITY_CHANGE);
        for (int i = 0; i < 50; i++) {
            state.tick();
        }
        state.hold(ExemptionCause.ABILITY_CHANGE);
        for (int i = 0; i < 10; i++) {
            state.tick();
        }
        assertTrue(state.isActive(ExemptionCause.ABILITY_CHANGE));
        state.tick();
        assertFalse(state.isActive(ExemptionCause.ABILITY_CHANGE));
        assertEquals(1, state.expiredHolds());
    }

    @Test
    @DisplayName("an expired hold does not cancel a still running timed grant of the same cause")
    void expiryKeepsGrant() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.KNOCKBACK);
        state.grant(ExemptionCause.KNOCKBACK, 200);
        for (int i = 0; i < ExemptionState.MAX_HOLD_TICKS + 1; i++) {
            state.tick();
        }
        assertTrue(state.isActive(ExemptionCause.KNOCKBACK));
        assertEquals(1, state.expiredHolds());
    }

    @Test
    @DisplayName("holds are counted: two holds need two releases")
    void nestedHolds() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.ABILITY_CHANGE);
        state.hold(ExemptionCause.ABILITY_CHANGE);
        state.release(ExemptionCause.ABILITY_CHANGE);
        assertTrue(state.isActive(ExemptionCause.ABILITY_CHANGE));
        state.release(ExemptionCause.ABILITY_CHANGE);
        assertFalse(state.isActive(ExemptionCause.ABILITY_CHANGE));
    }

    @Test
    @DisplayName("an unbalanced release does not go negative or poison later holds")
    void unbalancedRelease() {
        ExemptionState state = new ExemptionState();
        state.release(ExemptionCause.GAMEMODE_CHANGE);
        state.release(ExemptionCause.GAMEMODE_CHANGE);
        assertFalse(state.isActive(ExemptionCause.GAMEMODE_CHANGE));
        state.hold(ExemptionCause.GAMEMODE_CHANGE);
        assertTrue(state.isActive(ExemptionCause.GAMEMODE_CHANGE));
        state.release(ExemptionCause.GAMEMODE_CHANGE);
        assertFalse(state.isActive(ExemptionCause.GAMEMODE_CHANGE));
    }

    @Test
    @DisplayName("timed grant and hold combine: active until both are gone")
    void grantAndHold() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.EXPLOSION, 2);
        state.hold(ExemptionCause.EXPLOSION);
        state.tick();
        state.tick();
        assertTrue(state.isActive(ExemptionCause.EXPLOSION), "hold keeps it active after the grant ran out");
        state.release(ExemptionCause.EXPLOSION);
        assertFalse(state.isActive(ExemptionCause.EXPLOSION));

        state.grant(ExemptionCause.EXPLOSION, 5);
        state.hold(ExemptionCause.EXPLOSION);
        state.release(ExemptionCause.EXPLOSION);
        assertTrue(state.isActive(ExemptionCause.EXPLOSION), "grant keeps it active after the hold ended");
    }

    @Test
    @DisplayName("tick only affects causes with remaining ticks and leaves others alone")
    void tickIsolation() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.JOIN, 1);
        state.grant(ExemptionCause.RESPAWN, 3);
        state.hold(ExemptionCause.PORTAL);
        state.tick();
        assertFalse(state.isActive(ExemptionCause.JOIN));
        assertTrue(state.isActive(ExemptionCause.RESPAWN));
        assertTrue(state.isActive(ExemptionCause.PORTAL));
        assertEquals(2, state.ticksLeft(ExemptionCause.RESPAWN));
    }

    @Test
    @DisplayName("mask helpers: bits are unique, mask() combines, anyActive matches overlap")
    void masks() {
        Set<Long> bits = new HashSet<>();
        for (ExemptionCause cause : ExemptionCause.values()) {
            assertEquals(1L << cause.ordinal(), cause.bit());
            assertTrue(bits.add(cause.bit()), "duplicate bit for " + cause);
        }
        assertTrue(ExemptionCause.values().length <= 63, "mask is a long");
        assertEquals(0L, ExemptionCause.mask());
        assertEquals(ExemptionCause.JOIN.bit() | ExemptionCause.LUNGE.bit(),
                ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.LUNGE));
        assertEquals(ExemptionCause.JOIN.bit(), ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.JOIN));

        ExemptionState state = new ExemptionState();
        long relevant = ExemptionCause.mask(ExemptionCause.TELEPORT, ExemptionCause.KNOCKBACK);
        assertFalse(state.anyActive(relevant));
        state.grant(ExemptionCause.KNOCKBACK, 1);
        assertTrue(state.anyActive(relevant));
        assertFalse(state.anyActive(ExemptionCause.mask(ExemptionCause.VEHICLE)));
        assertEquals(ExemptionCause.KNOCKBACK.bit(), state.activeMask());
        assertFalse(state.anyActive(0L));
    }

    @Test
    @DisplayName("activeCauses lists active causes in declaration order")
    void activeCauses() {
        ExemptionState state = new ExemptionState();
        state.hold(ExemptionCause.LUNGE);
        state.grant(ExemptionCause.JOIN, 4);
        state.grant(ExemptionCause.PORTAL, 4);
        assertEquals(List.of(ExemptionCause.JOIN, ExemptionCause.PORTAL, ExemptionCause.LUNGE), state.activeCauses());
    }

    @Test
    @DisplayName("clear drops grants and holds")
    void clear() {
        ExemptionState state = new ExemptionState();
        state.grant(ExemptionCause.JOIN, 10);
        state.hold(ExemptionCause.VEHICLE);
        state.clear();
        assertEquals(0L, state.activeMask());
        assertEquals(0, state.ticksLeft(ExemptionCause.JOIN));
        state.release(ExemptionCause.VEHICLE);
        state.hold(ExemptionCause.VEHICLE);
        state.release(ExemptionCause.VEHICLE);
        assertFalse(state.isActive(ExemptionCause.VEHICLE), "holds restart from zero after clear");
    }
}
