package dev.vaplus.anticheat.latency;

import dev.vaplus.anticheat.testutil.ManualTimeSource;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts.CapturingHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarkerTracker")
class MarkerTrackerTest {

    private ManualTimeSource time;
    private RecordingTransport transport;
    private CapturingHandler logs;
    private MarkerTracker tracker;

    @BeforeEach
    void setUp() {
        time = new ManualTimeSource();
        transport = new RecordingTransport();
        logs = new CapturingHandler();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(logs);
        tracker = new MarkerTracker(transport, time, logger);
    }

    /** Wire id of the marker with the given sequence number. */
    private static int wire(long sequence) {
        return MarkerTracker.toWireId(sequence);
    }

    private boolean pong(long sequence) {
        return tracker.onPong(wire(sequence));
    }

    private void sendMarkers(int count) {
        for (int i = 0; i < count; i++) {
            tracker.sendMarker();
        }
    }

    @Test
    @DisplayName("marker ids are signed shorts in -32768..-16385 and count up from the bottom of the range")
    void idsInShortRange() {
        sendMarkers(3);
        assertEquals(List.of(-32767, -32766, -32765), transport.markerIds());
        for (int id : transport.markerIds()) {
            assertTrue(id >= Short.MIN_VALUE && id <= -16385, "id " + id);
            assertEquals(id, (short) id, "must survive a short cast (ViaBackwards window confirmations)");
            assertTrue(MarkerTracker.isMarkerId(id));
        }
        assertEquals(3, tracker.lastSentMarker());
        assertEquals(0, tracker.lastAckedMarker());
    }

    @Test
    @DisplayName("isMarkerId accepts exactly -32768..-16385")
    void foreignIds() {
        assertTrue(MarkerTracker.isMarkerId(-32768));
        assertTrue(MarkerTracker.isMarkerId(-16385));
        assertFalse(MarkerTracker.isMarkerId(-32769));
        assertFalse(MarkerTracker.isMarkerId(-16384));
        assertFalse(MarkerTracker.isMarkerId(0));
        assertFalse(MarkerTracker.isMarkerId(1));
        assertFalse(MarkerTracker.isMarkerId(-1));
        assertFalse(MarkerTracker.isMarkerId(Integer.MIN_VALUE));
        assertFalse(MarkerTracker.isMarkerId(Integer.MAX_VALUE));
        assertFalse(MarkerTracker.isMarkerId(0xA5000001));
    }

    @Test
    @DisplayName("foreign ids return false and change nothing")
    void foreignPongIgnored() {
        sendMarkers(2);
        assertFalse(tracker.onPong(0));
        assertFalse(tracker.onPong(12345));
        assertFalse(tracker.onPong(-16384));
        assertFalse(tracker.onPong(-32769));
        assertFalse(tracker.onPong(-1));
        assertEquals(0, tracker.lastAckedMarker());
        assertEquals(2, tracker.pendingCount());
        assertEquals(0, tracker.anomalies());
    }

    @Test
    @DisplayName("in-order acknowledgements advance lastAcked one by one")
    void inOrderAcks() {
        sendMarkers(3);
        assertEquals(3, tracker.pendingCount());
        assertTrue(pong(1));
        assertEquals(1, tracker.lastAckedMarker());
        assertTrue(tracker.isAcked(1));
        assertFalse(tracker.isAcked(2));
        assertEquals(2, tracker.pendingCount());
        assertTrue(pong(2));
        assertTrue(pong(3));
        assertEquals(3, tracker.lastAckedMarker());
        assertEquals(0, tracker.pendingCount());
        assertEquals(0, tracker.anomalies());
    }

    @Test
    @DisplayName("RTT and smoothed RTT are -1 before the first measurement")
    void rttUnknown() {
        assertEquals(-1L, tracker.rttMillis());
        assertEquals(-1L, tracker.smoothedRttMillis());
        tracker.sendMarker();
        assertEquals(-1L, tracker.rttMillis());
    }

    @Test
    @DisplayName("RTT is measured per marker and smoothed with alpha 1/8")
    void rttAndSmoothing() {
        tracker.sendMarker();
        time.advanceMillis(40);
        pong(1);
        assertEquals(40L, tracker.rttMillis());
        assertEquals(40L, tracker.smoothedRttMillis(), "first sample seeds the average");

        tracker.sendMarker();
        time.advanceMillis(80);
        pong(2);
        assertEquals(80L, tracker.rttMillis());
        assertEquals(45L, tracker.smoothedRttMillis(), "40 + (80 - 40) / 8");

        tracker.sendMarker();
        time.advanceMillis(10);
        pong(3);
        assertEquals(10L, tracker.rttMillis());
        assertEquals(40L, tracker.smoothedRttMillis(), "45 + (10 - 45) / 8 = 40.625ms, truncated to whole ms");
    }

    @Test
    @DisplayName("the RTT of a marker is measured from its own send time, not from the pong order")
    void rttUsesSendTime() {
        tracker.sendMarker();
        time.advanceMillis(30);
        tracker.sendMarker();
        time.advanceMillis(20);
        pong(1);
        assertEquals(50L, tracker.rttMillis());
        time.advanceMillis(5);
        pong(2);
        assertEquals(25L, tracker.rttMillis());
    }

    @Test
    @DisplayName("whenAcked runs a task immediately when the marker was already acknowledged")
    void whenAckedImmediate() {
        long marker = tracker.sendMarker();
        pong(1);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(marker, () -> ran.add("now"));
        assertEquals(List.of("now"), ran);
        tracker.whenAcked(0, () -> ran.add("zero"));
        assertEquals(List.of("now", "zero"), ran);
    }

    @Test
    @DisplayName("whenAcked defers until the marker is acknowledged, then runs once")
    void whenAckedDeferred() {
        long marker = tracker.sendMarker();
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(marker, () -> ran.add("a"));
        assertTrue(ran.isEmpty());
        pong(1);
        assertEquals(List.of("a"), ran);
        pong(1); // duplicate echo
        assertEquals(List.of("a"), ran);
    }

    @Test
    @DisplayName("tasks run in marker order even when registered out of order; equal markers keep registration order")
    void tasksInMarkerOrder() {
        sendMarkers(3);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(3, () -> ran.add("m3"));
        tracker.whenAcked(1, () -> ran.add("m1"));
        tracker.whenAcked(2, () -> ran.add("m2-first"));
        tracker.whenAcked(2, () -> ran.add("m2-second"));
        pong(1);
        assertEquals(List.of("m1"), ran);
        pong(2);
        assertEquals(List.of("m1", "m2-first", "m2-second"), ran);
        pong(3);
        assertEquals(List.of("m1", "m2-first", "m2-second", "m3"), ran);
    }

    @Test
    @DisplayName("tasks of skipped markers run in marker order when a later marker is echoed")
    void tasksInOrderOnSkip() {
        sendMarkers(3);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(3, () -> ran.add("m3"));
        tracker.whenAcked(1, () -> ran.add("m1"));
        tracker.whenAcked(2, () -> ran.add("m2"));
        pong(3);
        assertEquals(List.of("m1", "m2", "m3"), ran);
    }

    @Test
    @DisplayName("a task for a marker that was never sent stays pending")
    void futureMarkerTask() {
        sendMarkers(1);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(5, () -> ran.add("future"));
        pong(1);
        assertTrue(ran.isEmpty());
    }

    @Test
    @DisplayName("a duplicate echo is ignored, counted as anomaly and does not acknowledge pending markers")
    void duplicateEcho() {
        sendMarkers(3);
        pong(1);
        assertTrue(pong(1), "still our id, caller should cancel the packet");
        assertEquals(1, tracker.anomalies());
        assertEquals(1, tracker.lastAckedMarker());
        assertEquals(2, tracker.pendingCount());
        assertFalse(tracker.isAcked(2));
    }

    @Test
    @DisplayName("a stale echo from far in the past is ignored")
    void staleEcho() {
        sendMarkers(10);
        for (int i = 1; i <= 5; i++) {
            pong(i);
        }
        assertTrue(pong(2));
        assertEquals(1, tracker.anomalies());
        assertEquals(5, tracker.lastAckedMarker());
        assertEquals(5, tracker.pendingCount());
    }

    @Test
    @DisplayName("an id that was never sent is ignored and cannot force-acknowledge pending markers")
    void neverSentEcho() {
        sendMarkers(2);
        assertTrue(pong(99));
        assertTrue(tracker.onPong(Short.MIN_VALUE));
        assertTrue(tracker.onPong(-16385));
        assertEquals(3, tracker.anomalies());
        assertEquals(0, tracker.lastAckedMarker());
        assertEquals(2, tracker.pendingCount());
        assertEquals(-1L, tracker.rttMillis());
    }

    @Test
    @DisplayName("an echo before any marker was sent is ignored")
    void echoWithoutMarkers() {
        assertTrue(pong(1));
        assertEquals(1, tracker.anomalies());
        assertEquals(0, tracker.lastAckedMarker());
    }

    @Test
    @DisplayName("skipping a marker acknowledges the earlier one and counts an anomaly")
    void skippedMarker() {
        sendMarkers(3);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(1, () -> ran.add("m1"));
        tracker.whenAcked(2, () -> ran.add("m2"));
        time.advanceMillis(25);
        assertTrue(pong(2));
        assertEquals(1, tracker.anomalies());
        assertEquals(2, tracker.lastAckedMarker());
        assertEquals(1, tracker.pendingCount());
        assertEquals(List.of("m1", "m2"), ran);
        assertEquals(25L, tracker.rttMillis(), "RTT comes from the echoed marker, skipped ones do not sample");
    }

    @Test
    @DisplayName("skipping several markers counts each skipped one")
    void skippedSeveral() {
        sendMarkers(5);
        pong(4);
        assertEquals(3, tracker.anomalies());
        assertEquals(4, tracker.lastAckedMarker());
        assertEquals(1, tracker.pendingCount());
        pong(5);
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    @DisplayName("bracket() sends the first marker now and the second when the after-send list runs")
    void bracket() {
        List<Runnable> afterSend = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        tracker.bracket(afterSend, () -> ran.add("first"), () -> ran.add("second"));

        assertEquals(1, transport.markerIds().size(), "first marker is sent immediately");
        assertEquals(1, afterSend.size());

        afterSend.get(0).run();
        assertEquals(2, transport.markerIds().size(), "second marker is sent from the after-send task");
        assertEquals(2, tracker.lastSentMarker());

        pong(1);
        assertEquals(List.of("first"), ran);
        pong(2);
        assertEquals(List.of("first", "second"), ran);
    }

    @Test
    @DisplayName("bracket() accepts null callbacks")
    void bracketNullCallbacks() {
        List<Runnable> afterSend = new ArrayList<>();
        tracker.bracket(afterSend, null, null);
        afterSend.get(0).run();
        pong(1);
        pong(2);
        assertEquals(2, tracker.lastAckedMarker());
    }

    @Test
    @DisplayName("bracket() with the second marker acked first still runs both in order")
    void bracketSkippedFirst() {
        List<Runnable> afterSend = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        tracker.bracket(afterSend, () -> ran.add("first"), () -> ran.add("second"));
        afterSend.get(0).run();
        pong(2);
        assertEquals(List.of("first", "second"), ran);
    }

    @Test
    @DisplayName("more than 64 pending markers grow the ring buffer and still acknowledge correctly")
    void growth() {
        sendMarkers(200);
        assertEquals(200, tracker.pendingCount());
        List<Long> ran = new ArrayList<>();
        for (long m = 1; m <= 200; m += 25) {
            final long marker = m;
            tracker.whenAcked(marker, () -> ran.add(marker));
        }
        for (long m = 1; m <= 200; m++) {
            assertTrue(pong(m));
            assertEquals(m, tracker.lastAckedMarker());
        }
        assertEquals(0, tracker.pendingCount());
        assertEquals(0, tracker.anomalies());
        assertEquals(List.of(1L, 26L, 51L, 76L, 101L, 126L, 151L, 176L), ran);
    }

    @Test
    @DisplayName("growth with a wrapped ring buffer (head not at index 0) keeps marker order and send times")
    void growthWithWrappedHead() {
        sendMarkers(50);
        time.advanceMillis(10);
        for (int m = 1; m <= 40; m++) {
            pong(m);
        }
        // head is at 40, 10 pending; sending 100 more wraps and then grows the 64-slot buffer
        sendMarkers(100);
        assertEquals(110, tracker.pendingCount());
        // oldest pending is marker 41, sent at the very start
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), tracker.oldestPendingAgeNanos());
        for (int m = 41; m <= 150; m++) {
            assertTrue(pong(m));
            assertEquals(m, tracker.lastAckedMarker());
        }
        assertEquals(0, tracker.anomalies());
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    @DisplayName("many deferred tasks (task array growth) run in order")
    void manyTasks() {
        sendMarkers(100);
        List<Integer> ran = new ArrayList<>();
        for (int i = 100; i >= 1; i--) {
            final int marker = i;
            tracker.whenAcked(marker, () -> ran.add(marker));
        }
        pong(100);
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            expected.add(i);
        }
        assertEquals(expected, ran);
    }

    @Test
    @DisplayName("re-entrancy: a task may send markers (even growing the buffer) while acknowledgements are processed")
    void reentrantSendDuringAck() {
        sendMarkers(64); // buffer is exactly full
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(1, () -> {
            tracker.sendMarker(); // 65
            tracker.sendMarker(); // 66 -> forces growth while onPong is mid-loop
            ran.add("sent two");
        });
        tracker.whenAcked(64, () -> ran.add("m64"));

        pong(64);

        assertEquals(List.of("sent two", "m64"), ran);
        assertEquals(64, tracker.lastAckedMarker());
        assertEquals(63, tracker.anomalies(), "63 markers were skipped");
        assertEquals(2, tracker.pendingCount(), "the two new markers are pending");
        assertEquals(66, tracker.lastSentMarker());
        assertTrue(pong(65));
        assertTrue(pong(66));
        assertEquals(66, tracker.lastAckedMarker());
        assertEquals(0, tracker.pendingCount());
        assertEquals(63, tracker.anomalies());
    }

    @Test
    @DisplayName("re-entrancy: a task may register new tasks, which run immediately or later as appropriate")
    void reentrantWhenAcked() {
        sendMarkers(2);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(1, () -> {
            tracker.whenAcked(1, () -> ran.add("already acked"));
            tracker.whenAcked(2, () -> ran.add("later"));
            ran.add("outer");
        });
        pong(1);
        assertEquals(List.of("already acked", "outer"), ran);
        pong(2);
        assertEquals(List.of("already acked", "outer", "later"), ran);
    }

    @Test
    @DisplayName("a throwing task is logged and does not stop later tasks or the acknowledgement")
    void throwingTask() {
        sendMarkers(2);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(1, () -> {
            throw new IllegalStateException("task failure");
        });
        tracker.whenAcked(1, () -> ran.add("after failure"));
        tracker.whenAcked(2, () -> ran.add("next marker"));
        pong(1);
        pong(2);
        assertEquals(List.of("after failure", "next marker"), ran);
        assertEquals(2, tracker.lastAckedMarker());
        assertEquals(1, logs.records().size());
        assertEquals(Level.WARNING, logs.records().get(0).getLevel());
    }

    @Test
    @DisplayName("a throwing immediate task is contained as well")
    void throwingImmediateTask() {
        tracker.whenAcked(0, () -> {
            throw new IllegalStateException("immediate");
        });
        assertEquals(1, logs.records().size());
    }

    @Test
    @DisplayName("whenAcked rejects a null task")
    void nullTask() {
        assertThrows(NullPointerException.class, () -> tracker.whenAcked(1, null));
    }

    @Test
    @DisplayName("oldestPendingAgeNanos is 0 without pending markers and follows the oldest one")
    void oldestPendingAge() {
        assertEquals(0L, tracker.oldestPendingAgeNanos());
        tracker.sendMarker();
        time.advanceMillis(7);
        tracker.sendMarker();
        time.advanceMillis(3);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), tracker.oldestPendingAgeNanos());
        pong(1);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(3), tracker.oldestPendingAgeNanos());
        pong(2);
        assertEquals(0L, tracker.oldestPendingAgeNanos());
    }

    @Test
    @DisplayName("when the client never answers, the oldest marker is dropped at 8192 pending and counted")
    void maxPending() {
        sendMarkers(8_193);
        assertEquals(8_192, tracker.pendingCount());
        assertEquals(1, tracker.anomalies());
        assertTrue(pong(1), "the dropped marker is now unknown");
        assertEquals(2, tracker.anomalies());
        assertEquals(0, tracker.lastAckedMarker());
        assertTrue(pong(2));
        assertEquals(2, tracker.lastAckedMarker());
        assertEquals(8_191, tracker.pendingCount());
    }

    @Test
    @DisplayName("the 14-bit wire sequence wraps; wrapped ids are unambiguous because at most 8192 markers are pending")
    void wireIdWrap() {
        assertEquals(Short.MIN_VALUE, MarkerTracker.toWireId(1L << 14));
        assertEquals(Short.MIN_VALUE + 1, MarkerTracker.toWireId((1L << 14) + 1));
        assertEquals(-16385, MarkerTracker.toWireId((1L << 14) - 1));
        assertTrue(MarkerTracker.MAX_PENDING < (1 << 14));
    }

    @Test
    @DisplayName("acknowledgements keep working across the wire-sequence wrap")
    void acknowledgementAcrossWrap() {
        List<String> ran = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 6_000; i++) {
                long marker = tracker.sendMarker();
                assertTrue(tracker.onPong(transport.lastMarkerId()));
                assertEquals(marker, tracker.lastAckedMarker());
            }
        }
        assertEquals(18_000, tracker.lastAckedMarker());
        assertEquals(0, tracker.anomalies());
        long marker = tracker.sendMarker();
        tracker.whenAcked(marker, () -> ran.add("after wrap"));
        assertTrue(tracker.onPong(transport.lastMarkerId()));
        assertEquals(List.of("after wrap"), ran);
        assertTrue(tracker.onPong(transport.lastMarkerId()), "echo of an already acknowledged wrapped id");
        assertEquals(1, tracker.anomalies());
    }

    @Test
    @DisplayName("a refused send returns the next sequence, queues nothing and sends nothing")
    void refusedMarkerReturnsNextSequence() {
        long first = tracker.sendMarker();
        transport.acceptMarkers(false);
        long refused = tracker.sendMarker();
        assertEquals(first + 1, refused);
        assertEquals(first, tracker.lastSentMarker());
        assertEquals(1, tracker.pendingCount());
        assertEquals(1, transport.markerIds().size());
        assertFalse(tracker.isAcked(refused));
        assertEquals(refused, tracker.sendMarker(), "repeated refusals hand out the same handle");
        assertEquals(1, tracker.pendingCount());
    }

    @Test
    @DisplayName("tasks attached to a refused handle resolve when the next real marker is acknowledged")
    void refusedHandleResolvesWithNextRealMarker() {
        long first = tracker.sendMarker();
        transport.acceptMarkers(false);
        long refused = tracker.sendMarker();
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(refused, () -> ran.add("refused"));
        assertTrue(pong(first));
        assertTrue(ran.isEmpty(), "acking the earlier marker must not release work attached to the refused handle");

        transport.acceptMarkers(true);
        long next = tracker.sendMarker();
        assertEquals(refused, next, "the real marker gets exactly the handle that was handed out");
        assertTrue(ran.isEmpty());
        assertTrue(pong(next));
        assertEquals(List.of("refused"), ran);
        assertEquals(List.of(wire(first), wire(next)), transport.markerIds());
    }

    @Test
    @DisplayName("before the first marker a refused send returns 1 and it is not acknowledged")
    void refusedBeforeAnyMarker() {
        transport.acceptMarkers(false);
        long refused = tracker.sendMarker();
        assertEquals(1L, refused);
        assertFalse(tracker.isAcked(refused));
        assertEquals(0, tracker.pendingCount());
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(refused, () -> ran.add("x"));
        assertEquals(1, tracker.scheduledTasks());
        transport.acceptMarkers(true);
        assertEquals(1L, tracker.sendMarker());
        assertTrue(ran.isEmpty());
        pong(1);
        assertEquals(List.of("x"), ran);
    }

    @Test
    @DisplayName("a bracket during the configuration phase does not release its tasks before a real marker is acknowledged")
    void bracketWhileRefused() {
        transport.acceptMarkers(false);
        List<Runnable> afterSend = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        tracker.bracket(afterSend, () -> ran.add("first"), () -> ran.add("second"));
        afterSend.get(0).run();
        assertTrue(ran.isEmpty());
        transport.acceptMarkers(true);
        tracker.sendMarker();
        pong(1);
        assertEquals(List.of("first", "second"), ran, "both refused handles alias the next real marker");
    }

    @Test
    @DisplayName("many due tasks run in order from the head-indexed queue and the queue is empty afterwards")
    void manyDueTasks() {
        sendMarkers(500);
        List<Integer> ran = new ArrayList<>();
        for (int m = 1; m <= 500; m++) {
            final int marker = m;
            tracker.whenAcked(m, () -> ran.add(marker));
        }
        assertEquals(500, tracker.scheduledTasks());
        pong(500);
        assertEquals(0, tracker.scheduledTasks());
        assertEquals(500, ran.size());
        for (int i = 0; i < 500; i++) {
            assertEquals(i + 1, ran.get(i));
        }
    }

    @Test
    @DisplayName("scheduling after a partial drain compacts the queue without losing or reordering tasks")
    void compactionAfterPartialDrain() {
        sendMarkers(100);
        List<Integer> ran = new ArrayList<>();
        for (int m = 1; m <= 100; m++) {
            final int marker = m;
            tracker.whenAcked(m, () -> ran.add(marker));
        }
        pong(50);
        assertEquals(50, tracker.scheduledTasks());
        for (int m = 101; m <= 200; m++) {
            final int marker = m;
            tracker.whenAcked(m, () -> ran.add(marker));
        }
        assertEquals(150, tracker.scheduledTasks());
        sendMarkers(100);
        pong(200);
        assertEquals(0, tracker.scheduledTasks());
        assertEquals(200, ran.size());
        for (int i = 0; i < 200; i++) {
            assertEquals(i + 1, ran.get(i));
        }
    }

    @Test
    @DisplayName("tasks scheduled by due tasks during the drain run in the same acknowledgement when already due")
    void reentrantSchedulingDuringDrain() {
        sendMarkers(3);
        List<String> ran = new ArrayList<>();
        tracker.whenAcked(1, () -> {
            ran.add("t1");
            tracker.whenAcked(2, () -> ran.add("t1->m2"));
            tracker.whenAcked(3, () -> ran.add("t1->m3"));
            tracker.whenAcked(0, () -> ran.add("t1->immediate"));
        });
        tracker.whenAcked(2, () -> ran.add("t2"));
        pong(2);
        assertEquals(List.of("t1", "t1->immediate", "t2", "t1->m2"), ran);
        assertEquals(1, tracker.scheduledTasks());
        pong(3);
        assertEquals(List.of("t1", "t1->immediate", "t2", "t1->m2", "t1->m3"), ran);
        assertEquals(0, tracker.scheduledTasks());
    }

    @Test
    @DisplayName("task queue is capped at MAX_TASKS: the oldest task is committed early and counted as anomaly")
    void taskCap() {
        List<Integer> ran = new ArrayList<>();
        for (int i = 0; i < MarkerTracker.MAX_TASKS; i++) {
            final int n = i;
            tracker.whenAcked(1_000_000L + i, () -> {
                if (n < 3) {
                    ran.add(n);
                }
            });
        }
        assertEquals(MarkerTracker.MAX_TASKS, tracker.scheduledTasks());
        assertEquals(0, tracker.anomalies());
        assertTrue(ran.isEmpty());
        tracker.whenAcked(2_000_000L, () -> ran.add(-1));
        assertEquals(MarkerTracker.MAX_TASKS, tracker.scheduledTasks());
        assertEquals(1, tracker.anomalies());
        assertEquals(List.of(0), ran, "oldest task committed early");
    }

    @Test
    @DisplayName("after a full 14-bit rotation a replayed pong aliases the currently pending marker (documented limitation)")
    void wireIdWrapAliasesStalePong() {
        tracker.sendMarker();
        int staleId = wire(1);
        assertTrue(pong(1), "answered normally the first time");
        assertEquals(1, tracker.lastAckedMarker());
        assertEquals(0, tracker.anomalies());

        // Spin the 14-bit sequence space: sequences 2..16384 are sent and answered in order.
        for (long sequence = 2; sequence <= 16384; sequence++) {
            tracker.sendMarker();
            assertTrue(pong(sequence));
        }

        // Sequence 16385 reuses the wire id of the long-answered sequence 1 and is still pending.
        long aliased = tracker.sendMarker();
        assertEquals(16385L, aliased);
        assertEquals(staleId, wire(16385), "the wire id repeats after 16384 sent markers");

        // A recorded (stale) pong for sequence 1 now acknowledges sequence 16385 as if it were genuine:
        // wire ids are matched only against pending markers and are not authenticated.
        assertTrue(tracker.onPong(staleId));
        assertEquals(16385L, tracker.lastAckedMarker(), "the stale pong aliased onto the pending marker");
        assertTrue(tracker.isAcked(16385));
        assertEquals(0L, tracker.anomalies(), "the aliased pong is not detectable as an anomaly");
    }
}
