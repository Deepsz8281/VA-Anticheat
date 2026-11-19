package dev.vaplus.anticheat.player.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MotionHistory (per-client-tick snapshot ring buffer)")
class MotionHistoryTest {

    private static MotionHistory.TickSnapshot snapshot(long tick) {
        return new MotionHistory.TickSnapshot(tick, tick * 50_000_000L,
                tick, tick * 2, tick * 3,
                tick % 2 == 0, tick % 4 == 0, tick % 8 == 0, tick % 16 == 0, tick % 32 == 0,
                (int) (tick / 100));
    }

    @Test
    @DisplayName("a fresh history is empty")
    void empty() {
        MotionHistory history = new MotionHistory();
        assertEquals(0, history.size());
        assertFalse(history.isPresent(0));
        List<MotionHistory.TickSnapshot> seen = new ArrayList<>();
        history.forEachReversed(seen::add);
        assertTrue(seen.isEmpty());
    }

    @Test
    @DisplayName("forEachReversed visits newest to oldest; entries keep their fields")
    void reversedOrder() {
        MotionHistory history = new MotionHistory();
        MotionHistory.TickSnapshot first = snapshot(1);
        MotionHistory.TickSnapshot second = snapshot(2);
        MotionHistory.TickSnapshot third = snapshot(3);
        history.push(first);
        history.push(second);
        history.push(third);

        assertEquals(3, history.size());
        List<MotionHistory.TickSnapshot> seen = new ArrayList<>();
        history.forEachReversed(seen::add);
        assertEquals(List.of(third, second, first), seen);
        assertSame(third, seen.get(0));
        assertSame(first, seen.get(2));
        assertTrue(history.isPresent(1));
        assertTrue(history.isPresent(3));
        assertFalse(history.isPresent(4));
        assertFalse(history.isPresent(0));
    }

    @Test
    @DisplayName("pushing beyond capacity overwrites the oldest entries and keeps 32")
    void ringOverwrite() {
        MotionHistory history = new MotionHistory();
        for (long tick = 1; tick <= MotionHistory.CAPACITY + 5; tick++) {
            history.push(snapshot(tick));
        }

        assertEquals(MotionHistory.CAPACITY, history.size());
        List<Long> ticks = new ArrayList<>();
        history.forEachReversed(s -> ticks.add(s.clientTick()));
        // newest first: 37, 36, ..., 6 (the first 5 entries were overwritten)
        long newest = MotionHistory.CAPACITY + 5;
        for (int i = 0; i < MotionHistory.CAPACITY; i++) {
            assertEquals(newest - i, ticks.get(i));
        }
        assertFalse(history.isPresent(1));
        assertFalse(history.isPresent(5));
        assertTrue(history.isPresent(6));
        assertTrue(history.isPresent(newest));
    }

    @Test
    @DisplayName("the ring keeps working after wrapping many times")
    void repeatedWrap() {
        MotionHistory history = new MotionHistory();
        long last = 500;
        for (long tick = 1; tick <= last; tick++) {
            history.push(snapshot(tick));
        }

        assertEquals(MotionHistory.CAPACITY, history.size());
        long[] seenTicks = new long[1];
        history.forEachReversed(s -> {
            if (seenTicks[0] == 0) {
                seenTicks[0] = s.clientTick();
            } else {
                assertEquals(seenTicks[0] - 1, s.clientTick(), "strictly descending, no gaps");
                seenTicks[0] = s.clientTick();
            }
        });
        assertEquals(last - MotionHistory.CAPACITY + 1, seenTicks[0], "oldest kept entry");
    }

    @Test
    @DisplayName("clear empties the buffer and it can be filled again")
    void clear() {
        MotionHistory history = new MotionHistory();
        for (long tick = 1; tick <= 10; tick++) {
            history.push(snapshot(tick));
        }
        history.clear();

        assertEquals(0, history.size());
        assertFalse(history.isPresent(10));
        List<MotionHistory.TickSnapshot> seen = new ArrayList<>();
        history.forEachReversed(seen::add);
        assertTrue(seen.isEmpty());

        MotionHistory.TickSnapshot after = snapshot(11);
        history.push(after);
        assertEquals(1, history.size());
        assertTrue(history.isPresent(11));
        history.forEachReversed(s -> assertSame(after, s));
    }

    @Test
    @DisplayName("isPresent distinguishes ticks inside and outside the window after overwrite")
    void presenceWindow() {
        MotionHistory history = new MotionHistory();
        for (long tick = 1; tick <= MotionHistory.CAPACITY; tick++) {
            history.push(snapshot(tick));
        }
        for (long tick = 1; tick <= MotionHistory.CAPACITY; tick++) {
            assertTrue(history.isPresent(tick), "tick " + tick + " should be present");
        }
        history.push(snapshot(MotionHistory.CAPACITY + 1));
        assertFalse(history.isPresent(1), "oldest entry is gone after one more push");
        assertTrue(history.isPresent(MotionHistory.CAPACITY + 1));
    }

    @Test
    @DisplayName("newest() returns the last pushed snapshot (null when empty), also after wrapping")
    void newest() {
        MotionHistory history = new MotionHistory();
        assertEquals(null, history.newest());
        MotionHistory.TickSnapshot first = snapshot(1);
        history.push(first);
        assertSame(first, history.newest());
        MotionHistory.TickSnapshot last = null;
        for (long tick = 2; tick <= MotionHistory.CAPACITY * 2L + 3; tick++) {
            last = snapshot(tick);
            history.push(last);
            assertSame(last, history.newest());
        }
        List<MotionHistory.TickSnapshot> seen = new ArrayList<>();
        history.forEachReversed(seen::add);
        assertSame(seen.get(0), history.newest(), "same as the first element of the reversed visit");
        history.clear();
        assertEquals(null, history.newest());
    }
}
