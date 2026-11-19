package dev.vaplus.anticheat.player.state;

import dev.vaplus.anticheat.player.state.VelocityState.Kind;
import dev.vaplus.anticheat.player.state.VelocityState.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VelocityState (pending server velocities)")
class VelocityStateTest {

    @Test
    @DisplayName("entries move SENT -> WINDOW -> ACKED; only acknowledged prefixes are offerable")
    void phases() {
        VelocityState state = new VelocityState();
        long a = state.register(Kind.SET, 0.1, 0.2, 0.3, 0);
        long b = state.register(Kind.ADD, 1.0, 0.0, -1.0, 0);
        assertEquals(2, state.size());
        assertEquals(0, state.offerableCount());
        assertEquals(Phase.SENT, state.phase(0));

        state.markFirstAck(a);
        assertEquals(Phase.WINDOW, state.phase(0));
        assertEquals(1, state.offerableCount());
        assertEquals(0, state.ackedCount());

        state.markSecondAck(a, 5L);
        state.markFirstAck(b);
        assertEquals(Phase.ACKED, state.phase(0));
        assertEquals(2, state.offerableCount());
        assertEquals(1, state.ackedCount());

        assertEquals(Kind.SET, state.kind(0));
        assertEquals(0.1, state.x(0));
        assertEquals(0.2, state.y(0));
        assertEquals(0.3, state.z(0));
        assertEquals(Kind.ADD, state.kind(1));
        assertEquals(-1.0, state.z(1));
        assertEquals(0, state.worldEpoch(1));
        assertThrows(IndexOutOfBoundsException.class, () -> state.x(2));
    }

    @Test
    @DisplayName("a late first ack never downgrades an ACKED entry; unknown ids are ignored")
    void ackOrdering() {
        VelocityState state = new VelocityState();
        long a = state.register(Kind.SET, 0, 0, 0, 0);
        state.markSecondAck(a, 1L);
        state.markFirstAck(a);
        assertEquals(Phase.ACKED, state.phase(0));
        state.markFirstAck(999L);
        state.markSecondAck(999L, 1L);
        assertEquals(1, state.size());
    }

    @Test
    @DisplayName("consume drops the applied prefix plus every ACKED entry, keeps WINDOW entries")
    void consume() {
        VelocityState state = new VelocityState();
        long a = state.register(Kind.SET, 1, 0, 0, 0);
        long b = state.register(Kind.SET, 2, 0, 0, 0);
        long c = state.register(Kind.SET, 3, 0, 0, 0);
        state.markSecondAck(a, 0L);
        state.markFirstAck(b);

        state.consume(0);
        assertEquals(2, state.size(), "ACKED entry offered once and dropped");
        assertEquals(2.0, state.x(0));
        assertEquals(Phase.WINDOW, state.phase(0));

        state.consume(1);
        assertEquals(1, state.size());
        assertEquals(3.0, state.x(0));
        assertEquals(1, state.appliedByEngine());
        assertEquals(Phase.SENT, state.phase(0));
        state.markFirstAck(c);
        assertEquals(1, state.offerableCount());
    }

    @Test
    @DisplayName("expire drops other world epochs and ACKED entries older than 60 client ticks")
    void expire() {
        VelocityState state = new VelocityState();
        long a = state.register(Kind.SET, 1, 0, 0, 0);
        state.register(Kind.SET, 2, 0, 0, 1);
        long c = state.register(Kind.SET, 3, 0, 0, 1);
        state.markSecondAck(a, 0L);
        state.markSecondAck(c, 10L);

        state.expire(10L, 1);
        assertEquals(2, state.size(), "the epoch-0 entry is gone");
        assertEquals(2.0, state.x(0));
        state.expire(10L + VelocityState.MAX_ACKED_AGE_TICKS + 1, 1);
        assertEquals(1, state.size(), "the old ACKED entry expired, the SENT one stays");
        assertEquals(2.0, state.x(0));
        assertEquals(2, state.expired());
    }

    @Test
    @DisplayName("overflow drops the oldest entry; clear keeps the entity id")
    void overflowAndClear() {
        VelocityState state = new VelocityState();
        state.selfEntityId(7);
        for (int i = 0; i < VelocityState.CAPACITY + 2; i++) {
            state.register(Kind.ADD, i, 0, 0, 0);
        }
        assertEquals(VelocityState.CAPACITY, state.size());
        assertEquals(2.0, state.x(0));
        assertEquals(2, state.dropped());
        assertEquals(VelocityState.CAPACITY + 2, state.registered());

        state.clear();
        assertEquals(0, state.size());
        assertTrue(state.isSelf(7));
        assertFalse(state.isSelf(8));
    }

    @Test
    @DisplayName("the own entity id is unknown until set; non-finite vectors are rejected")
    void validation() {
        VelocityState state = new VelocityState();
        assertEquals(VelocityState.UNKNOWN_ENTITY_ID, state.selfEntityId());
        assertFalse(state.isSelf(VelocityState.UNKNOWN_ENTITY_ID));
        assertThrows(IllegalArgumentException.class, () -> state.register(Kind.SET, Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> state.register(null, 0, 0, 0, 0));
    }
}
